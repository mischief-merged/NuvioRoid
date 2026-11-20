package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A hold that arrives while the finished file is being published must let that transfer finish. */
class LimitedConnectionPublishWindowTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private val hold = ConnectionHold()
    private val realBackend = DownloadsRepository.backend
    private val realHold = DownloadsRepository.connectionHold
    private val realCompanions = DownloadsRepository.companions
    private val realFreeBytes = DownloadsPlatformDownloader.freeBytesProvider
    private val starts = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var payloadStore: String? = null
    @Volatile private var holdAtSuccess = true

    /** The real downloader; the hold is reported after the file was published and before the list hears of it. */
    private val backend = object : DownloadsBackend {
        override fun loadPayload(): String? = payloadStore
        override fun savePayload(payload: String) { payloadStore = payload }
        override fun restoreItem(item: DownloadItem): DownloadItem = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (Long, Long?) -> Unit,
            onSuccess: (String, Long?) -> Unit,
            onFailure: (String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            starts.incrementAndGet()
            return DownloadsPlatformDownloader.start(
                request, onProgress,
                { uri, total ->
                    if (holdAtSuccess) {
                        holdAtSuccess = false
                        // The final file exists and the partial file is gone right now.
                        hold.report(NetworkPathReport.Known(true, false, pathKey("en0/wifi", "172.20.10.1")))
                    }
                    onSuccess(uri, total)
                },
                onFailure, onPaused,
            )
        }
        override fun removeFile(localFileUri: String?) = DownloadsPlatformDownloader.removeFile(localFileUri)
        override fun removePartialFile(destinationFileName: String) = DownloadsPlatformDownloader.removePartialFile(destinationFileName)
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String) =
            DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, destinationFileName)
    }

    @BeforeTest
    fun set_up() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.connectionHold = hold
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
        DownloadsPlatformDownloader.freeBytesProvider = { null }
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun restore() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.connectionHold = realHold
        DownloadsRepository.companions = realCompanions
        DownloadsPlatformDownloader.freeBytesProvider = realFreeBytes
    }

    @Test
    fun a_hold_between_publication_and_the_report_of_success_lets_the_download_finish() {
        val payload = Random(5).nextBytes(512 * 1024)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/m.mkv") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        val dir = DesktopStorage.rootDir.resolve("downloads").toFile()
        val title = "Publish window ${System.nanoTime()}"
        try {
            DownloadsRepository.enqueueFromStream(
                contentType = "movie", videoId = "tt8", parentMetaId = "tt8", parentMetaType = "movie", title = title,
                logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null,
                episodeTitle = null, episodeThumbnail = null,
                stream = StreamItem(name = "1080p", url = "http://127.0.0.1:${server.address.port}/m.mkv", addonName = "A", addonId = "a", behaviorHints = StreamBehaviorHints()),
            )
            fun current() = DownloadsRepository.uiState.value.items.single()
            val deadline = System.currentTimeMillis() + 30_000
            while (current().status == DownloadStatus.Downloading && System.currentTimeMillis() < deadline) Thread.sleep(20)

            assertEquals(DownloadStatus.Completed, current().status, "the published file was thrown away by the hold")
            assertFalse(current().pausedByConnection)
            assertEquals(1, starts.get(), "the download was started again")
            assertContentEquals(payload, File(URI(current().localFileUri!!)).readBytes())

            // When the hold ends nothing is fetched again and no second copy appears.
            hold.report(NetworkPathReport.Known(false, false, pathKey("en0/wifi", "192.168.1.1")))
            Thread.sleep(300)
            assertEquals(1, starts.get())
            assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith(title) && it.name.contains("(2)") })
        } finally {
            server.stop(0)
            dir.listFiles().orEmpty().filter { it.name.startsWith(title) }.forEach { it.delete() }
        }
    }
}

/** "Download anyway" with network keys of the shape the native monitor produces. */
class DownloadAnywayNetworkChangeTest {
    @Test
    fun another_hotspot_on_the_same_wifi_interface_ends_download_anyway() {
        val hold = ConnectionHold()
        hold.report(NetworkPathReport.Known(true, false, pathKey("en0/wifi", "172.20.10.1@aa:bb:cc:00:00:01")))
        hold.downloadAnyway()
        assertEquals(null, hold.holdReason)

        hold.report(NetworkPathReport.Known(true, false, pathKey("en0/wifi", "172.20.10.1@aa:bb:cc:00:00:02")))
        assertEquals(LimitedConnectionReason.Hotspot, hold.holdReason)
    }

    @Test
    fun another_low_data_mode_wifi_on_the_same_interface_ends_download_anyway() {
        val hold = ConnectionHold()
        hold.report(NetworkPathReport.Known(false, true, pathKey("en0/wifi", "10.0.0.1@aa:bb:cc:00:00:03")))
        hold.downloadAnyway()
        hold.report(NetworkPathReport.Known(false, true, pathKey("en0/wifi", "192.168.4.1@aa:bb:cc:00:00:04")))
        assertEquals(LimitedConnectionReason.LowDataMode, hold.holdReason)
    }

    @Test
    fun the_same_network_reported_again_keeps_download_anyway() {
        val hold = ConnectionHold()
        hold.report(NetworkPathReport.Known(true, false, pathKey("en0/wifi,utun6/other", "172.20.10.1@aa:bb:cc:00:00:01")))
        hold.downloadAnyway()
        hold.report(NetworkPathReport.Known(true, false, pathKey("utun6/other,en0/wifi", "172.20.10.1@aa:bb:cc:00:00:01")))
        assertEquals(null, hold.holdReason)
    }

    @Test
    fun a_router_hardware_address_learned_later_keeps_download_anyway_and_then_tells_networks_apart() {
        val hold = ConnectionHold()
        hold.report(NetworkPathReport.Known(true, false, pathKey("en0/wifi", "172.20.10.1")))
        hold.downloadAnyway()
        hold.report(NetworkPathReport.Known(true, false, pathKey("en0/wifi", "172.20.10.1@aa:bb:cc:00:00:01")))
        assertEquals(null, hold.holdReason, "the same network with its router now known ended the choice")
        hold.report(NetworkPathReport.Known(true, false, pathKey("en0/wifi", "172.20.10.1@aa:bb:cc:00:00:02")))
        assertEquals(LimitedConnectionReason.Hotspot, hold.holdReason)
    }

    @Test
    fun the_key_is_built_from_what_the_native_monitor_sends() {
        // As the monitor reports it on this Mac: interfaces repeat, routers in any order.
        val report = pathReport(true, true, false, "en0/wifi,en0/wifi,utun6/other", "fe80::1,172.20.10.1@aa:bb:cc:00:00:01")
        assertEquals("en0/wifi,utun6/other|172.20.10.1@aa:bb:cc:00:00:01,fe80::1", report.networkKey)
        assertTrue(sameNetwork(report.networkKey, pathReport(true, true, false, "utun6/other,en0/wifi", "172.20.10.1,fe80::1").networkKey))
        assertFalse(sameNetwork(report.networkKey, pathReport(true, true, false, "en0/wifi,utun6/other", "172.20.10.2").networkKey))
        assertFalse(sameNetwork(report.networkKey, pathReport(true, true, false, "en1/wifi", "172.20.10.1@aa:bb:cc:00:00:01").networkKey))
    }
}

/** The key the native monitor's report becomes: what the app compares to tell networks apart. */
internal fun pathKey(interfaces: String, gateways: String): String =
    pathReportForTest(interfaces, gateways).networkKey

internal fun pathReportForTest(interfaces: String, gateways: String) =
    pathReport(satisfied = true, expensive = false, constrained = false, interfaces = interfaces, gateways = gateways)
