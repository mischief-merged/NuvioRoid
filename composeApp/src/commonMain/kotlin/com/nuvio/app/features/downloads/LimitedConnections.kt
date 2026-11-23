package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why the connection counts as limited, so downloads wait. Each reason is one rule; later rules (a
 * measured speed below what the user set, a Wi-Fi network the user named) are added as further cases.
 */
internal sealed interface LimitedConnectionReason {
    /** The system says traffic is expensive: a phone's hotspot, tethering or a cellular link. */
    data object Hotspot : LimitedConnectionReason

    /** The user turned on Low Data Mode for this network. */
    data object LowDataMode : LimitedConnectionReason

    /** What downloads get has stayed very low. Only automatic downloads wait for this one. */
    data object SlowConnection : LimitedConnectionReason

    /** The network answers downloads with a sign-in page, breaks secure connections, or refuses them from every host. */
    data object NetworkBlocksDownloads : LimitedConnectionReason
}

/** Whether a download the user started waits for [reason] too, or only automatic ones do. */
internal val LimitedConnectionReason.holdsUserDownloads: Boolean
    get() = this != LimitedConnectionReason.SlowConnection

/** What the system says about the path traffic takes now. */
internal sealed interface NetworkPathReport {
    /** Nothing is known: no detector on this platform, or it could not start. Never holds anything. */
    data object Unknown : NetworkPathReport

    data class Known(
        val expensive: Boolean,
        val constrained: Boolean,
        /**
         * Names the network the path goes over: "interfaces|routers", each list sorted, a router as
         * "address@hardware address" or "address" when its hardware address is not known. Compared
         * with [sameNetwork]; another network ends a "Download anyway" given for the one before.
         */
        val networkKey: String,
    ) : NetworkPathReport
}

/** Tells what path traffic takes, now and whenever that changes. */
internal interface NetworkPathDetector {
    /**
     * Starts reporting to [onReport]: once with the path as it is now, as soon as that is known, and
     * again on every change. False when it cannot report on this machine; nothing is reported then.
     */
    fun start(onReport: (NetworkPathReport) -> Unit): Boolean

    fun stop()
}

/**
 * Whether two network keys name the same network: the same interfaces and the same router addresses,
 * and no router whose hardware address is known in both differs. A hardware address that is known in
 * one only (the router was not in the address table yet) is no difference.
 */
internal fun sameNetwork(first: String, second: String): Boolean {
    if (first == second) return true
    fun parse(key: String): Pair<String, Map<String, String?>> {
        val interfaces = key.substringBefore('|')
        val routers = key.substringAfter('|', "").split(',').filter(String::isNotEmpty).associate { router ->
            router.substringBefore('@') to router.substringAfter('@', "").ifEmpty { null }
        }
        return interfaces to routers
    }
    val (firstInterfaces, firstRouters) = parse(first)
    val (secondInterfaces, secondRouters) = parse(second)
    if (firstInterfaces != secondInterfaces || firstRouters.keys != secondRouters.keys) return false
    return firstRouters.all { (address, hardware) ->
        val other = secondRouters[address]
        hardware == null || other == null || hardware == other
    }
}

/** The limited reason of [report], whatever the setting. Hotspot first: it is the one people recognise. */
internal fun limitedReasonOf(report: NetworkPathReport): LimitedConnectionReason? = when (report) {
    NetworkPathReport.Unknown -> null
    is NetworkPathReport.Known -> when {
        report.expensive -> LimitedConnectionReason.Hotspot
        report.constrained -> LimitedConnectionReason.LowDataMode
        else -> null
    }
}

internal data class ConnectionHoldState(
    /** Why the network path is limited now (hotspot, Low Data Mode), or null. Whatever the setting. */
    val pathReason: LimitedConnectionReason? = null,
    /** What downloads found the network to be (slow, blocking downloads), or null. */
    val quality: LimitedConnectionReason? = null,
    /** Whether the setting to pause downloads on limited connections is on. */
    val enabled: Boolean = true,
    /** The user chose "Download anyway" on this network, in this run of the app. */
    val overridden: Boolean = false,
) {
    /** Why the connection is limited now, whatever the setting: the path first, then what downloads found. */
    val reason: LimitedConnectionReason? get() = pathReason ?: quality

    /** Why a download waits now, or null when it does not. [automatic]: queued by an automatic-download rule. */
    fun holdReasonFor(automatic: Boolean): LimitedConnectionReason? {
        if (overridden) return null
        pathReason?.takeIf { enabled }?.let { return it }
        return quality?.takeIf { automatic || it.holdsUserDownloads }
    }

    /** Some download waits now. */
    val holding: Boolean get() = holdReason != null

    /** Why some download waits now (automatic ones wait for every reason), or null when none does. */
    val holdReason: LimitedConnectionReason? get() = holdReasonFor(automatic = true)
}

/**
 * Decides whether downloads wait because of the connection. It never holds on an unknown path. A
 * "Download anyway" lasts until the network changes or the app quits: it is not saved.
 *
 * Whoever applies the hold listens with [listen]; it is told after every change of [state], outside
 * any lock of this class, and reads [state] itself (a later change may have come in between).
 */
internal class ConnectionHold(
    private val loadEnabled: () -> Boolean? = { null },
    private val saveEnabled: (Boolean) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private var report: NetworkPathReport = NetworkPathReport.Unknown
    private var quality: LimitedConnectionReason? = null
    private var enabled: Boolean? = null

    // The network the user said "Download anyway" for, null when there is no such choice.
    private var overrideKey: String? = null
    private var listener: (() -> Unit)? = null

    private val _state = MutableStateFlow(ConnectionHoldState())
    val state: StateFlow<ConnectionHoldState> = _state.asStateFlow()

    init {
        synchronized(lock) { _state.value = computeLocked() }
    }

    /** Why some download waits now, or null. Reads the published state only, so it never waits for a lock. */
    val holdReason: LimitedConnectionReason? get() = _state.value.holdReason

    /** Why a download waits now, or null. Reads the published state only. */
    fun holdReasonFor(automatic: Boolean): LimitedConnectionReason? = _state.value.holdReasonFor(automatic)

    /** What downloads found the network to be: slow, blocking downloads, or neither (null). */
    fun reportQuality(reason: LimitedConnectionReason?) = change {
        quality = reason
    }

    fun listen(listener: () -> Unit) {
        synchronized(lock) { this.listener = listener }
    }

    fun report(report: NetworkPathReport) = change {
        this.report = report
        val key = (report as? NetworkPathReport.Known)?.networkKey
        // Another network, or one that is not limited, ends a "Download anyway".
        val chosen = overrideKey
        overrideKey = when {
            chosen == null -> null
            key == null && chosen != UnknownNetworkKey -> null
            key != null && !sameNetwork(key, chosen) -> null
            // The same network, maybe now with a router's hardware address that was not known before: kept, so a
            // network with the same addresses but another router is told apart from then on.
            else -> key ?: chosen
        }
    }

    fun setEnabled(value: Boolean) = change {
        enabled = value
        runCatching { saveEnabled(value) }
    }

    /**
     * Lets downloads run on this network until it changes or the app quits, for every reason, also one that
     * ends and comes back on the same network. Nothing when nothing is held.
     */
    fun downloadAnyway() = change {
        if (limitedReasonOf(report) == null && quality == null) return@change
        overrideKey = (report as? NetworkPathReport.Known)?.networkKey ?: UnknownNetworkKey
    }

    private fun enabledLocked(): Boolean =
        enabled ?: (runCatching { loadEnabled() }.getOrNull() ?: true).also { enabled = it }

    private fun computeLocked(): ConnectionHoldState {
        val key = (report as? NetworkPathReport.Known)?.networkKey ?: UnknownNetworkKey
        return ConnectionHoldState(
            pathReason = limitedReasonOf(report),
            quality = quality,
            enabled = enabledLocked(),
            overridden = overrideKey?.let { it == key || (key != UnknownNetworkKey && it != UnknownNetworkKey && sameNetwork(it, key)) } == true,
        )
    }

    private inline fun change(block: () -> Unit) {
        val notify = synchronized(lock) {
            val before = _state.value
            block()
            _state.value = computeLocked()
            listener.takeIf { _state.value != before }
        }
        notify?.invoke()
    }
}

/** The network key of a "Download anyway" given while the path is not known (no detector). */
private const val UnknownNetworkKey = "?"

/** The app's hold, with the setting saved where the platform keeps settings. */
internal object LimitedConnections {
    val hold: ConnectionHold = ConnectionHold(
        loadEnabled = LimitedConnectionStorage::loadPauseOnLimited,
        saveEnabled = LimitedConnectionStorage::savePauseOnLimited,
    )
}

/** Where the setting "Pause downloads on hotspots and Low Data Mode networks" is kept. Null: never set (on). */
internal expect object LimitedConnectionStorage {
    fun loadPauseOnLimited(): Boolean?
    fun savePauseOnLimited(value: Boolean)

    /** The setting "Let other apps go first". Null: never set (on). */
    fun loadPolite(): Boolean?
    fun savePolite(value: Boolean)
}

/** The setting "Let other apps go first" and what the downloads screen shows about it. */
internal object PoliteDownloadsHost {
    private val lock = SynchronizedObject()
    private var loaded = false
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean>
        get() {
            synchronized(lock) {
                if (!loaded) {
                    loaded = true
                    _enabled.value = runCatching { LimitedConnectionStorage.loadPolite() }.getOrNull() ?: true
                }
            }
            return _enabled.asStateFlow()
        }

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            loaded = true
            _enabled.value = value
        }
        runCatching { LimitedConnectionStorage.savePolite(value) }
    }

    /** Set by the platform that runs [PoliteDownloads]. */
    val stateFlow = MutableStateFlow(PoliteState())
    val state: StateFlow<PoliteState> get() = stateFlow.asStateFlow()
}
