package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Called by the native monitor, on a queue of its own, with the path traffic takes now. */
internal fun interface NetworkPathListener {
    fun onSleep() {}
    fun onWake() {}
    fun onPath(satisfied: Boolean, expensive: Boolean, constrained: Boolean, interfaces: String, gateways: String)
}

/** The network path monitor of the macOS native bridge (Network.framework). Lives in the player bridge library. */
internal object MacNetworkPathNative {
    external fun startMonitor(listener: NetworkPathListener): Boolean
    external fun stopMonitor()
}

/**
 * Reports the macOS network path: expensive (a phone's hotspot, tethering, cellular) and constrained
 * (Low Data Mode). Anything that goes wrong (another system, a native library that did not load or
 * is too old to have the monitor) makes [start] return false, and nothing is reported: the hold then
 * stays on an unknown path, which never holds a download.
 *
 * Reports are handed to [onReport] on a thread of their own, never on the native queue.
 */
internal class MacNetworkPathDetector(
    private val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac"),
    private val loadLibrary: () -> Unit = NativePlayerBridge::ensureLoaded,
    private val native: NativePathMonitor = NativePathMonitor.Real,
    private val onSleep: () -> Unit = {},
    private val onWake: () -> Unit = {},
    private val onPathChanged: () -> Unit = {},
) : NetworkPathDetector {
    /** The two native calls, replaceable in tests. */
    internal interface NativePathMonitor {
        fun start(listener: NetworkPathListener): Boolean
        fun stop()

        object Real : NativePathMonitor {
            override fun start(listener: NetworkPathListener): Boolean = MacNetworkPathNative.startMonitor(listener)
            override fun stop() = MacNetworkPathNative.stopMonitor()
        }
    }

    private val log = Logger.withTag("NetworkPath")
    private var executor: ExecutorService? = null
    private val generation = AtomicLong()
    private val pathRevision = AtomicLong()

    @Synchronized
    override fun start(onReport: (NetworkPathReport) -> Unit): Boolean {
        if (!isMac) return false
        stop()
        val reports = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nuvio-network-path").apply { isDaemon = true }
        }
        val started = try {
            loadLibrary()
            val current = generation.incrementAndGet()
            native.start(object : NetworkPathListener {
                override fun onPath(satisfied: Boolean, expensive: Boolean, constrained: Boolean, interfaces: String, gateways: String) {
                    val report = pathReport(satisfied, expensive, constrained, interfaces, gateways)
                    val revision = pathRevision.incrementAndGet()
                    onPathChanged.invoke()
                    runCatching { reports.execute {
                        if (generation.get() == current && pathRevision.get() == revision) runCatching { onReport(report) }
                            .onFailure { log.w(it) { "a path report failed" } }
                    } }
                }
                override fun onSleep() {
                    generation.incrementAndGet()
                    onSleep.invoke()
                }
                override fun onWake() { onWake.invoke() }
            })
        } catch (error: Throwable) {
            // UnsatisfiedLinkError from a bridge without the monitor, or the library not loading at all.
            log.w(error) { "network path monitor unavailable" }
            false
        }
        if (!started) {
            reports.shutdown()
            return false
        }
        executor = reports
        return true
    }

    @Synchronized
    override fun stop() {
        generation.incrementAndGet()
        val running = executor ?: return
        executor = null
        runCatching { native.stop() }
        running.shutdown()
    }
}

/**
 * The report for what the native monitor said. The network key is the interfaces and the routers
 * ("address@hardware address" where that is known), each in a stable order (see [sameNetwork]).
 */
internal fun pathReport(
    satisfied: Boolean,
    expensive: Boolean,
    constrained: Boolean,
    interfaces: String,
    gateways: String,
): NetworkPathReport.Known {
    fun sortedList(text: String) = text.split(',').map(String::trim).filter(String::isNotEmpty).distinct().sorted().joinToString(",")
    val key = sortedList(interfaces) + "|" + sortedList(gateways)
    return NetworkPathReport.Known(
        // A path that cannot carry traffic is not limited: downloads wait for the internet in their own way then.
        expensive = satisfied && expensive,
        constrained = satisfied && constrained,
        networkKey = if (satisfied) key else "",
    )
}

/** Serializes sleep and fresh reports; a check begun before another sleep cannot release its hold. */
internal class WakeNetworkRecheck(
    private val hold: ConnectionHold,
    private val checkQuality: suspend () -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var sleeping = false

    fun sleep() = synchronized(lock) {
        generation++
        sleeping = true
        hold.beginNetworkRecheck()
    }

    fun wake() = synchronized(lock) {
        generation++
        sleeping = false
        hold.beginNetworkRecheck()
    }

    fun pathChanged() = synchronized(lock) { generation++ }

    suspend fun report(report: NetworkPathReport) {
        val token = synchronized(lock) { if (sleeping) return else generation }
        if (hold.state.value.checkingNetwork) {
            if (report !is NetworkPathReport.Known || report.networkKey.isBlank()) return
            checkQuality()
        }
        synchronized(lock) {
            if (!sleeping && token == generation) hold.report(report, notifyInBackground = true)
        }
    }
}

/** Owns one monitor at a time; callers restart it on a background thread. */
internal class NetworkPathMonitorSession(
    private val hold: ConnectionHold,
    private val recheck: WakeNetworkRecheck,
    private val createDetector: () -> NetworkPathDetector,
) {
    private var detector: NetworkPathDetector? = null

    @Synchronized
    fun restart() {
        runCatching {
            detector?.stop()
            val next = createDetector()
            detector = next
            if (!next.start { report -> runBlocking { recheck.report(report) } }) {
                hold.networkMonitorUnavailable()
            }
        }.onFailure {
            hold.networkMonitorUnavailable()
        }
    }
}

/** Watches network changes and restarts the path monitor on wake for a fresh report. */
internal object DesktopLimitedConnections {
    fun install() {
        val hold = LimitedConnections.hold
        val recheck = WakeNetworkRecheck(hold, DesktopPoliteDownloads::recheckAfterWake)
        lateinit var session: NetworkPathMonitorSession
        fun startMonitor() {
            Thread { session.restart() }.apply {
                name = "nuvio-network-path-start"
                isDaemon = true
                start()
            }
        }
        session = NetworkPathMonitorSession(hold, recheck) {
            MacNetworkPathDetector(
                onSleep = recheck::sleep,
                onPathChanged = recheck::pathChanged,
                onWake = { recheck.wake(); startMonitor() },
            )
        }
        // No restored transfer starts before the initial path is known, either.
        hold.beginNetworkRecheck()
        startMonitor()
    }
}
