package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Why the connection counts as limited, so downloads wait. Each reason is one rule; later rules (a
 * measured speed below what the user set, a Wi-Fi network the user named) are added as further cases.
 */
internal sealed interface LimitedConnectionReason {
    /** The user chose Travel mode. Only automatic downloads wait for this reason. */
    data object Travel : LimitedConnectionReason

    /** A fresh network check must finish before any transfer can resume. */
    data object CheckingNetwork : LimitedConnectionReason

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
    get() = this != LimitedConnectionReason.SlowConnection && this != LimitedConnectionReason.Travel

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

internal const val DefaultTravelCapBytesPerSecond = 1_000_000L

@Serializable
internal data class TravelPreferences(
    val enabled: Boolean = false,
    val capBytesPerSecond: Long = DefaultTravelCapBytesPerSecond,
    val networks: Set<String> = emptySet(),
)

internal data class TravelState(
    val enabled: Boolean = false,
    val remembered: Boolean = false,
    val canRemember: Boolean = false,
    val capBytesPerSecond: Long = DefaultTravelCapBytesPerSecond,
) {
    val downloadCapBytesPerSecond: Long? get() = capBytesPerSecond.takeIf { enabled }
    val previewsAllowed: Boolean get() = !enabled
    fun yieldingEnabled(savedChoice: Boolean): Boolean = enabled || savedChoice
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
    val travel: TravelState = TravelState(),
    val checkingNetwork: Boolean = false,
) {
    /** The current hold first, then a detected limit whose pause setting is off. */
    val reason: LimitedConnectionReason? get() = holdReason ?: pathReason ?: quality

    /** Why a download waits now, or null when it does not. [automatic]: queued by an automatic-download rule. */
    fun holdReasonFor(automatic: Boolean): LimitedConnectionReason? {
        if (checkingNetwork) return LimitedConnectionReason.CheckingNetwork
        if (travel.enabled && automatic) {
            if (!overridden && quality == LimitedConnectionReason.NetworkBlocksDownloads) return quality
            return LimitedConnectionReason.Travel
        }
        if (overridden) return null
        pathReason?.takeIf { enabled && !travel.enabled }?.let { return it }
        return quality?.takeIf { automatic || it.holdsUserDownloads }
    }

    /** Some download waits now. */
    val holding: Boolean get() = holdReason != null

    /** Why some download waits now (automatic ones wait for every reason), or null when none does. */
    val holdReason: LimitedConnectionReason? get() = holdReasonFor(automatic = true)
}

/**
 * Decides whether downloads wait because of the connection, Travel mode or a pending wake check. A
 * "Download anyway" lasts until the network changes or the app quits: it is not saved.
 *
 * Whoever applies the hold listens with [listen]; it is told after every change of [state], outside
 * any lock of this class, and reads [state] itself (a later change may have come in between).
 */
internal class ConnectionHold(
    private val loadEnabled: () -> Boolean? = { null },
    private val saveEnabled: (Boolean) -> Unit = {},
    loadTravel: () -> TravelPreferences = { TravelPreferences() },
    private val saveTravel: (TravelPreferences) -> Unit = {},
    private val applyTravelCap: (Long?) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private var report: NetworkPathReport = NetworkPathReport.Unknown
    private var quality: LimitedConnectionReason? = null
    private var enabled: Boolean? = null
    private var travel = runCatching { loadTravel() }.getOrDefault(TravelPreferences()).let {
        it.copy(capBytesPerSecond = it.capBytesPerSecond.coerceIn(250_000L, 10_000_000L))
    }
    private var checkingNetwork = false

    // The network the user said "Download anyway" for, null when there is no such choice.
    private var overrideKey: String? = null
    private var listener: (() -> Unit)? = null

    private val _state = MutableStateFlow(ConnectionHoldState())
    val state: StateFlow<ConnectionHoldState> = _state.asStateFlow()

    init {
        synchronized(lock) {
            _state.value = computeLocked()
            applyTravelCap(_state.value.travel.downloadCapBytesPerSecond)
        }
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

    fun report(report: NetworkPathReport, notifyInBackground: Boolean = false) = change(notifyInBackground) {
        this.report = report
        if (report is NetworkPathReport.Known && report.networkKey.isNotBlank()) checkingNetwork = false
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

    /** Startup and wake fallback for platforms without a working path monitor. */
    fun networkMonitorUnavailable() = change {
        checkingNetwork = false
        report = NetworkPathReport.Unknown
        overrideKey = null
    }

    /** Holds transfers before sleep. Only a fresh, usable path ends this barrier. */
    fun beginNetworkRecheck() = change(notifyInBackground = true) {
        checkingNetwork = true
        overrideKey = null
        report = NetworkPathReport.Unknown
    }

    fun setTravelEnabled(value: Boolean) = change {
        val key = currentNetworkKey()
        // Switching off also forgets this network, so the next report cannot turn it straight back on.
        travel = travel.copy(enabled = value, networks = if (!value && key != null) {
            travel.networks - key
        } else travel.networks)
    }

    fun rememberTravelNetwork(value: Boolean) = change {
        val key = currentNetworkKey() ?: return@change
        val others = travel.networks - key
        travel = travel.copy(enabled = if (value) false else travel.enabled, networks = if (value) others + key else others)
    }

    fun setTravelCap(value: Long) = change {
        travel = travel.copy(capBytesPerSecond = value.coerceIn(250_000L, 10_000_000L))
    }

    private fun currentNetworkKey(): String? = (report as? NetworkPathReport.Known)?.networkKey
        ?.takeIf { key ->
            !checkingNetwork && key.substringBefore('|').isNotBlank() &&
                key.substringAfter('|', "").split(',').any { it.substringAfter('@', "").isNotBlank() }
        }

    fun setEnabled(value: Boolean) = change {
        enabled = value
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
            travel = TravelState(
                enabled = travel.enabled || currentNetworkKey() in travel.networks,
                remembered = currentNetworkKey() in travel.networks,
                canRemember = currentNetworkKey() != null,
                capBytesPerSecond = travel.capBytesPerSecond,
            ),
            checkingNetwork = checkingNetwork,
            overridden = overrideKey?.let { it == key || (key != UnknownNetworkKey && it != UnknownNetworkKey && sameNetwork(it, key)) } == true,
        )
    }

    private fun change(notifyInBackground: Boolean = false, block: () -> Unit) {
        val effects = synchronized(lock) {
            val before = _state.value
            val beforeTravel = travel
            val beforeEnabled = enabled
            block()
            val next = computeLocked()
            _state.value = next
            val travelToSave = travel.takeIf { it != beforeTravel }
            val enabledToSave = enabled.takeIf { it != beforeEnabled }
            val notify = listener.takeIf { next != before }
            val work: () -> Unit = {
                // Read the latest cap: an older queued notification must not restore an old limit.
                applyTravelCap(_state.value.travel.downloadCapBytesPerSecond)
                travelToSave?.let { runCatching { saveTravel(it) } }
                enabledToSave?.let { runCatching { saveEnabled(it) } }
                notify?.invoke()
            }
            work
        }
        if (notifyInBackground) ConnectionUpdates.submit(effects) else effects()
    }
}

/** Keeps settings writes and download-list work ordered, away from UI and native power callbacks. */
internal object ConnectionUpdates {
    private val pending = Channel<() -> Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (update in pending) runCatching { update() }.onFailure {
                co.touchlab.kermit.Logger.w(it) { "connection update failed" }
            }
        }
    }

    fun submit(update: () -> Unit) { pending.trySend(update) }
}

internal fun ConnectionHold.updateInBackground(update: ConnectionHold.() -> Unit) {
    ConnectionUpdates.submit { update() }
}

/** The network key of a "Download anyway" given while the path is not known (no detector). */
private const val UnknownNetworkKey = "?"

/** The app's hold, with the setting saved where the platform keeps settings. */
internal object LimitedConnections {
    private val travelJson = Json { ignoreUnknownKeys = true }
    val hold: ConnectionHold = ConnectionHold(
        loadEnabled = LimitedConnectionStorage::loadPauseOnLimited,
        saveEnabled = LimitedConnectionStorage::savePauseOnLimited,
        loadTravel = {
            LimitedConnectionStorage.loadTravel()?.let { travelJson.decodeFromString<TravelPreferences>(it) } ?: TravelPreferences()
        },
        saveTravel = { LimitedConnectionStorage.saveTravel(travelJson.encodeToString(it)) },
        applyTravelCap = DownloadBandwidth::setTravelCap,
    )
}

/** Where the setting "Pause downloads on hotspots and Low Data Mode networks" is kept. Null: never set (on). */
internal expect object LimitedConnectionStorage {
    fun loadTravel(): String?
    fun saveTravel(value: String)
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
