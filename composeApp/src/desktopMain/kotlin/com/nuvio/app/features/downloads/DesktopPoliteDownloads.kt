package com.nuvio.app.features.downloads

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

/**
 * Runs [PoliteDownloads] in the app: a tick every [PoliteTickMs], a TCP connection to the download host
 * as the round trip probe, and Apple's connectivity check pages (the ones macOS itself uses to find a
 * sign-in page) as the look at the network.
 */
internal object DesktopPoliteDownloads {
    private val log = Logger.withTag("PoliteDownloads")
    private val mark = TimeSource.Monotonic.markNow()

    @Volatile
    private var running: PoliteDownloads? = null

    suspend fun recheckAfterWake() {
        addresses.clear()
        running?.recheckAfterWake()
    }

    fun onTransferFailure(host: String, kind: TransferFailureKind) {
        running?.onTransferFailure(host, kind)
    }

    /** Starts the loop. Called once when the app starts. Never throws. */
    fun install() {
        val polite = PoliteDownloads(
            clock = { mark.elapsedNow().inWholeMilliseconds },
            probeRoundTrip = ::connectRoundTripMs,
            checkNetwork = ::checkNetwork,
            traffic = DownloadTraffic.app,
            enabled = { LimitedConnections.hold.state.value.travel.yieldingEnabled(PoliteDownloadsHost.enabled.value) },
            userCapBytesPerSecond = {
                effectiveDownloadCap(DownloadBandwidth.userCapBytesPerSecond.value,
                    LimitedConnections.hold.state.value.travel.downloadCapBytesPerSecond, null)
            },
            setYieldCap = DownloadBandwidth::setYieldCap,
            reportQuality = { reason ->
                log.i { "Network for downloads: ${reason ?: "fine"}" }
                LimitedConnections.hold.reportQuality(reason)
            },
        )
        running = polite
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (true) {
                runCatching { if (!LimitedConnections.hold.state.value.checkingNetwork) polite.tick() }.onFailure { log.w(it) { "a polite downloads tick failed" } }
                PoliteDownloadsHost.stateFlow.value = polite.state.value
                delay(PoliteTickMs)
            }
        }
    }

    // Names are looked up once in a while, so the probe times the connection and not the name lookup.
    private val addresses = ConcurrentHashMap<String, Pair<Long, InetAddress>>()

    /** The time a TCP connection to [endpoint] takes to open (one round trip), in ms; [ProbeTimeoutMs] when none opens in time. */
    private suspend fun connectRoundTripMs(endpoint: TransferEndpoint): Long? = runInterruptible(Dispatchers.IO) {
        if (endpoint.host.isBlank()) return@runInterruptible null
        val now = System.currentTimeMillis()
        val address = addresses[endpoint.host]?.takeIf { now - it.first < 5 * 60_000L }?.second
            ?: runCatching { InetAddress.getByName(endpoint.host) }.getOrNull()?.also { addresses[endpoint.host] = now to it }
            ?: return@runInterruptible null
        val started = System.nanoTime()
        try {
            Socket().use { it.connect(InetSocketAddress(address, endpoint.port), ProbeTimeoutMs.toInt()) }
            (System.nanoTime() - started) / 1_000_000L
        } catch (_: java.net.SocketTimeoutException) {
            ProbeTimeoutMs
        } catch (_: Exception) {
            null
        }
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Looks at the page macOS uses to find a sign-in page, and at a secure one, to tell what the network does. */
    private suspend fun checkNetwork(): NetworkCheck = runInterruptible(Dispatchers.IO) {
        val page = runCatching {
            client.send(
                HttpRequest.newBuilder(URI("http://captive.apple.com/hotspot-detect.html")).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }.getOrNull() ?: return@runInterruptible NetworkCheck.Unreachable
        val redirectHost = page.headers().firstValue("Location").orElse(null)?.let { runCatching { URI(it).host }.getOrNull() }
        val found = networkCheckOf(page.statusCode(), page.body(), redirectHost)
        if (found != NetworkCheck.Open) return@runInterruptible found
        val secure = runCatching {
            client.send(
                HttpRequest.newBuilder(URI("https://www.apple.com/library/test/success.html")).timeout(Duration.ofSeconds(5))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }
        when (secure.exceptionOrNull()) {
            null -> NetworkCheck.Open
            is javax.net.ssl.SSLException -> NetworkCheck.SecureConnectionBroken
            else -> NetworkCheck.Open
        }
    }
}
