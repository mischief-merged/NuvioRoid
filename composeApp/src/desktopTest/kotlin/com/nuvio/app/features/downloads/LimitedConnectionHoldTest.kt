package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Keys of the shape the native monitor produces: interfaces | routers.
private val hotspot = NetworkPathReport.Known(expensive = true, constrained = false, networkKey = pathKey("en0/wifi", "172.20.10.1@aa:bb:cc:00:00:01"))
private val otherHotspot = NetworkPathReport.Known(expensive = true, constrained = false, networkKey = pathKey("en7/wired", "172.20.10.1@aa:bb:cc:00:00:09"))
private val lowData = NetworkPathReport.Known(expensive = false, constrained = true, networkKey = pathKey("en0/wifi", "10.0.0.1@aa:bb:cc:00:00:03"))
private val home = NetworkPathReport.Known(expensive = false, constrained = false, networkKey = pathKey("en0/wifi", "192.168.1.1@aa:bb:cc:00:00:05"))

/** The rule alone: when the connection hold holds, and what ends a "Download anyway". */
class ConnectionHoldRuleTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    @Test
    fun an_unknown_path_never_holds() {
        val hold = ConnectionHold()
        assertNull(hold.holdReason)
        hold.report(NetworkPathReport.Unknown)
        assertNull(hold.holdReason)
        assertNull(hold.state.value.reason)
    }

    @Test
    fun a_hotspot_and_a_low_data_mode_network_hold_and_say_why() {
        val hold = ConnectionHold()
        hold.report(hotspot)
        assertEquals(LimitedConnectionReason.Hotspot, hold.holdReason)
        hold.report(lowData)
        assertEquals(LimitedConnectionReason.LowDataMode, hold.holdReason)
        hold.report(home)
        assertNull(hold.holdReason)
    }

    @Test
    fun a_hotspot_with_low_data_mode_is_called_a_hotspot() {
        val hold = ConnectionHold()
        hold.report(NetworkPathReport.Known(expensive = true, constrained = true, networkKey = pathKey("en0/wifi", "172.20.10.1")))
        assertEquals(LimitedConnectionReason.Hotspot, hold.holdReason)
    }

    @Test
    fun with_the_setting_off_nothing_is_held_and_the_choice_is_saved() {
        val saved = mutableListOf<Boolean>()
        val hold = ConnectionHold(loadEnabled = { null }, saveEnabled = { saved += it })
        assertTrue(hold.state.value.enabled, "the setting is on until the user turns it off")
        hold.report(hotspot)
        hold.setEnabled(false)
        assertNull(hold.holdReason)
        assertEquals(LimitedConnectionReason.Hotspot, hold.state.value.reason, "the reason is still known")
        assertEquals(listOf(false), saved)
    }

    @Test
    fun a_saved_setting_is_read_back() {
        val hold = ConnectionHold(loadEnabled = { false })
        hold.report(hotspot)
        assertFalse(hold.state.value.enabled)
        assertNull(hold.holdReason)
    }

    @Test
    fun download_anyway_lasts_until_the_network_changes() {
        val hold = ConnectionHold()
        hold.report(hotspot)
        hold.downloadAnyway()
        assertNull(hold.holdReason)
        assertTrue(hold.state.value.overridden)

        // The same network reported again (an address change, say) keeps the choice.
        hold.report(hotspot.copy())
        assertNull(hold.holdReason)

        // Another limited network does not inherit it.
        hold.report(otherHotspot)
        assertEquals(LimitedConnectionReason.Hotspot, hold.holdReason)
        assertFalse(hold.state.value.overridden)
    }

    @Test
    fun download_anyway_ends_when_the_network_stops_being_limited_even_if_it_comes_back() {
        val hold = ConnectionHold()
        hold.report(hotspot)
        hold.downloadAnyway()
        hold.report(home)
        hold.report(hotspot)
        assertEquals(LimitedConnectionReason.Hotspot, hold.holdReason)
    }

    @Test
    fun download_anyway_on_a_regular_network_does_nothing() {
        val hold = ConnectionHold()
        hold.report(home)
        hold.downloadAnyway()
        assertFalse(hold.state.value.overridden)
        hold.report(home)
        hold.report(hotspot.copy(networkKey = home.networkKey))
        assertEquals(LimitedConnectionReason.Hotspot, hold.holdReason)
    }

    @Test
    fun the_listener_hears_changes_only() {
        val hold = ConnectionHold()
        var calls = 0
        hold.listen { calls++ }
        hold.report(home)
        hold.report(home)
        assertEquals(0, calls, "nothing changed for a regular network")
        hold.report(hotspot)
        hold.report(hotspot)
        assertEquals(1, calls)
        hold.setEnabled(false)
        assertEquals(2, calls)
    }

    @Test
    fun the_native_report_is_read_as_a_path_with_a_stable_network_key() {
        val first = pathReport(satisfied = true, expensive = true, constrained = false, interfaces = "en0/wifi,utun3/other", gateways = "172.20.10.1")
        val second = pathReport(satisfied = true, expensive = true, constrained = false, interfaces = "utun3/other,en0/wifi", gateways = "172.20.10.1")
        assertEquals(first, second)
        assertTrue(first.expensive)
        // A path that carries nothing is not limited: downloads wait for the internet in their own way.
        val down = pathReport(satisfied = false, expensive = true, constrained = true, interfaces = "en0/wifi", gateways = "")
        assertNull(limitedReasonOf(down))
    }

    @Test
    fun a_detector_that_cannot_start_leaves_the_path_unknown() {
        val detector = MacNetworkPathDetector(
            isMac = true,
            loadLibrary = { throw UnsatisfiedLinkError("no bridge") },
        )
        assertFalse(detector.start { error("nothing may be reported") })

        val notMac = MacNetworkPathDetector(isMac = false, loadLibrary = { error("not loaded off a Mac") })
        assertFalse(notMac.start { error("nothing may be reported") })

        val tooOld = MacNetworkPathDetector(
            isMac = true,
            loadLibrary = {},
            native = object : MacNetworkPathDetector.NativePathMonitor {
                override fun start(listener: NetworkPathListener): Boolean = throw UnsatisfiedLinkError("startMonitor")
                override fun stop() = Unit
            },
        )
        assertFalse(tooOld.start { error("nothing may be reported") })
    }

    @Test
    fun a_detector_hands_native_reports_over_off_the_native_queue() {
        var nativeListener: NetworkPathListener? = null
        val detector = MacNetworkPathDetector(
            isMac = true,
            loadLibrary = {},
            native = object : MacNetworkPathDetector.NativePathMonitor {
                override fun start(listener: NetworkPathListener): Boolean {
                    nativeListener = listener
                    return true
                }
                override fun stop() = Unit
            },
        )
        val got = java.util.concurrent.LinkedBlockingQueue<Pair<NetworkPathReport, String>>()
        assertTrue(detector.start { got.put(it to Thread.currentThread().name) })
        nativeListener!!.onPath(true, false, true, "en0/wifi", "192.168.1.1@aa:bb:cc:00:00:05")
        val (report, thread) = got.poll(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals(LimitedConnectionReason.LowDataMode, limitedReasonOf(report))
        assertEquals("nuvio-network-path", thread)
        detector.stop()
    }
}

/** The hold applied by the downloads list, over an in-memory store and a fake downloader. */
class LimitedConnectionDownloadsTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val started = mutableListOf<DownloadPlatformRequest>()
        val cancelled = mutableListOf<String>()
        val progress = mutableMapOf<String, (Long, Long?) -> Unit>()
        val success = mutableMapOf<String, (String, Long?) -> Unit>()
        val partials = mutableMapOf<String, Long>()
        val partialsRemoved = mutableListOf<String>()

        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            started += request
            val id = request.item.id
            progress[id] = { bytes, total ->
                partials[id] = bytes
                onProgress(bytes, total)
            }
            success[id] = onSuccess
            return object : DownloadsTaskHandle {
                override fun cancel() {
                    cancelled += id
                }
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true
        override fun removePartialFile(destinationFileName: String): Boolean {
            partialsRemoved += destinationFileName
            return true
        }

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
        override fun inspectFile(localFileUri: String): DownloadedFileFacts? = null

        fun startsOf(id: String) = started.count { it.item.id == id }
    }

    private val backend = FakeBackend()
    private val hold = ConnectionHold()
    private val realBackend = DownloadsRepository.backend
    private val realHold = DownloadsRepository.connectionHold
    private val realCompanions = DownloadsRepository.companions

    @BeforeTest
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.connectionHold = hold
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.connectionHold = realHold
        DownloadsRepository.companions = realCompanions
    }

    private fun enqueue(episode: Int, autoRule: String? = null): String {
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:$episode", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = episode, episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(name = "1080p", url = "https://example.com/e$episode.mkv", addonName = "A", addonId = "a", behaviorHints = StreamBehaviorHints()),
            autoDownloadRuleKey = autoRule,
        )
        return DownloadsRepository.uiState.value.items.first { it.episodeNumber == episode }.id
    }

    private fun item(id: String) = DownloadsRepository.uiState.value.items.single { it.id == id }

    @Test
    fun on_a_limited_connection_a_new_download_waits_instead_of_starting() {
        hold.report(hotspot)
        val id = enqueue(1)

        assertEquals(0, backend.startsOf(id), "a transfer started on a hotspot")
        assertEquals(DownloadStatus.Paused, item(id).status)
        assertTrue(item(id).pausedByConnection)
        assertEquals(DownloadPhase.WaitingOnHotspot, downloadRowStatus(item(id), 0L, limitedBy = hold.state.value.reason).phase)
    }

    @Test
    fun automatic_downloads_wait_too() {
        hold.report(lowData)
        val id = enqueue(1, autoRule = "rule-1")

        assertEquals(0, backend.startsOf(id))
        assertTrue(item(id).pausedByConnection)
        assertEquals(DownloadPhase.WaitingOnLowDataMode, downloadRowStatus(item(id), 0L, limitedBy = hold.state.value.reason).phase)
    }

    @Test
    fun a_running_download_is_paused_keeping_its_partial_and_goes_on_from_it_when_the_connection_is_regular() {
        val id = enqueue(1)
        backend.progress.getValue(id)(400_000L, 1_000_000L)
        val partialKey = "dev:ino:1"
        // The downloader records the identity of the partial file it created.
        backend.started.single().onPartialCreated(partialKey)

        hold.report(hotspot)

        assertEquals(listOf(id), backend.cancelled, "the transfer was not stopped")
        assertEquals(DownloadStatus.Paused, item(id).status)
        assertTrue(item(id).pausedByConnection)
        assertEquals(400_000L, item(id).downloadedBytes, "what was downloaded was forgotten")
        assertEquals(partialKey, item(id).partialFileKey, "the partial file is no longer known as this download's")
        assertTrue(backend.partialsRemoved.isEmpty(), "the partial file was removed")

        hold.report(home)

        assertEquals(2, backend.startsOf(id), "it did not go on by itself")
        val resumed = backend.started.last().item
        assertEquals(partialKey, resumed.partialFileKey, "the new transfer would not continue the partial file")
        assertEquals(400_000L, resumed.downloadedBytes)
        assertEquals(DownloadStatus.Downloading, item(id).status)
        assertFalse(item(id).pausedByConnection)
    }

    @Test
    fun an_unknown_path_never_holds_anything() {
        hold.report(NetworkPathReport.Unknown)
        val id = enqueue(1)
        assertEquals(1, backend.startsOf(id))
        assertEquals(DownloadStatus.Downloading, item(id).status)
    }

    @Test
    fun a_detector_that_goes_quiet_after_a_hotspot_lets_downloads_go_on() {
        hold.report(hotspot)
        val id = enqueue(1)
        hold.report(NetworkPathReport.Unknown)
        assertEquals(1, backend.startsOf(id))
    }

    @Test
    fun download_anyway_lets_held_downloads_run_until_the_network_changes() {
        hold.report(hotspot)
        val first = enqueue(1)
        hold.downloadAnyway()

        assertEquals(1, backend.startsOf(first))
        assertEquals(DownloadStatus.Downloading, item(first).status)
        val second = enqueue(2)
        assertEquals(1, backend.startsOf(second), "a download added after Download anyway waited")

        hold.report(otherHotspot)

        assertTrue(item(first).pausedByConnection)
        assertTrue(item(second).pausedByConnection)
        assertEquals(setOf(first, second), backend.cancelled.toSet())
    }

    @Test
    fun with_the_setting_off_nothing_waits_and_turning_it_off_lets_held_ones_go_on() {
        hold.report(hotspot)
        val id = enqueue(1)
        assertEquals(0, backend.startsOf(id))

        hold.setEnabled(false)

        assertEquals(1, backend.startsOf(id))
        val another = enqueue(2)
        assertEquals(1, backend.startsOf(another))

        // Turned on again while on the hotspot: running downloads wait again.
        hold.setEnabled(true)
        assertTrue(item(id).pausedByConnection)
        assertTrue(item(another).pausedByConnection)
    }

    @Test
    fun a_download_the_user_paused_stays_paused_when_the_connection_becomes_regular() {
        val paused = enqueue(1)
        DownloadsRepository.pauseDownload(paused)
        hold.report(hotspot)
        val held = enqueue(2)
        // The user pauses one that waits: it becomes an ordinary pause.
        DownloadsRepository.pauseDownload(held)
        assertFalse(item(held).pausedByConnection)

        hold.report(home)

        assertEquals(DownloadStatus.Paused, item(paused).status)
        assertEquals(DownloadStatus.Paused, item(held).status)
        assertEquals(1, backend.startsOf(paused))
        assertEquals(0, backend.startsOf(held))
    }

    @Test
    fun a_download_whose_file_is_complete_and_being_checked_is_not_stopped() {
        val id = enqueue(1)
        val finishing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // The check of the finished file runs outside the lock; the hold arrives in the middle of it.
        DownloadsRepository.backend = object : DownloadsBackend by backend {
            override fun sampleFrames(localFileUri: String): FrameSamples? {
                finishing.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return null
            }
        }
        val finisher = Thread { backend.success.getValue(id)("file:/tmp/e1.mkv", 10L) }.apply { start() }
        assertTrue(finishing.await(10, java.util.concurrent.TimeUnit.SECONDS))

        hold.report(hotspot)
        release.countDown()
        finisher.join(10_000)

        assertTrue(backend.cancelled.isEmpty(), "a finished file was stopped while it was checked")
        assertEquals(DownloadStatus.Completed, item(id).status)
    }

    @Test
    fun once_publication_is_allowed_a_hold_leaves_the_transfer_to_finish() {
        val id = enqueue(1)
        assertTrue(backend.started.single().mayPublish(), "a transfer that owns its download was refused")

        hold.report(hotspot)

        assertTrue(backend.cancelled.isEmpty(), "a transfer that was publishing its file was stopped")
        assertEquals(DownloadStatus.Downloading, item(id).status)
        backend.success.getValue(id)("file:/tmp/e1.mkv", 10L)
        assertEquals(DownloadStatus.Completed, item(id).status)
    }

    @Test
    fun a_hold_that_comes_before_publication_keeps_the_partial_file_unpublished() {
        val id = enqueue(1)
        hold.report(hotspot)

        assertFalse(backend.started.single().mayPublish(), "a paused transfer was allowed to publish")
        assertTrue(item(id).pausedByConnection)
    }

    @Test
    fun a_download_waiting_when_the_app_quit_goes_on_at_load_only_when_the_connection_is_regular() {
        hold.report(hotspot)
        val id = enqueue(1)
        assertTrue(item(id).pausedByConnection)
        val stored = backend.payload

        // Next launch, still on the hotspot: it keeps waiting.
        DownloadsRepository.clearLocalState()
        backend.payload = stored
        DownloadsRepository.ensureLoaded()
        assertEquals(0, backend.startsOf(id))
        assertTrue(item(id).pausedByConnection)

        // Next launch, at home: it goes on.
        DownloadsRepository.clearLocalState()
        hold.report(home)
        backend.payload = stored
        DownloadsRepository.ensureLoaded()
        assertEquals(1, backend.startsOf(id))
        assertEquals(DownloadStatus.Downloading, item(id).status)
    }

    @Test
    fun the_row_says_why_it_waits_and_says_so_plainly_when_the_reason_is_no_longer_known() {
        hold.report(hotspot)
        val id = enqueue(1)
        assertEquals(DownloadPhase.WaitingOnHotspot, downloadRowStatus(item(id), 0L, limitedBy = LimitedConnectionReason.Hotspot).phase)
        assertEquals(DownloadPhase.WaitingOnLowDataMode, downloadRowStatus(item(id), 0L, limitedBy = LimitedConnectionReason.LowDataMode).phase)
        assertEquals(DownloadPhase.WaitingForConnection, downloadRowStatus(item(id), 0L, limitedBy = null).phase)
    }
}

/** The hold over the real desktop downloader and a slow local server: the bytes of a paused transfer are kept and continued. */
class LimitedConnectionRealTransferTest {
    private class RealDownloaderBackend : DownloadsBackend {
        @Volatile
        var payload: String? = null
        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) {
            this.payload = payload
        }
        override fun restoreItem(item: DownloadItem): DownloadItem = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle = DownloadsPlatformDownloader.start(request, onProgress, onSuccess, onFailure, onPaused)
        override fun removeFile(localFileUri: String?): Boolean = DownloadsPlatformDownloader.removeFile(localFileUri)
        override fun removePartialFile(destinationFileName: String): Boolean = DownloadsPlatformDownloader.removePartialFile(destinationFileName)
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, destinationFileName)
    }

    private val hold = ConnectionHold()
    private val realBackend = DownloadsRepository.backend
    private val realHold = DownloadsRepository.connectionHold
    private val realCompanions = DownloadsRepository.companions
    private val realFreeBytes = DownloadsPlatformDownloader.freeBytesProvider
    private val realInterval = DownloadsPlatformDownloader.progressIntervalMs

    @BeforeTest
    fun use_the_real_downloader_with_an_in_memory_list() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = RealDownloaderBackend()
        DownloadsRepository.connectionHold = hold
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
        DownloadsPlatformDownloader.freeBytesProvider = { null }
        DownloadsPlatformDownloader.progressIntervalMs = 50
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun restore() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.connectionHold = realHold
        DownloadsRepository.companions = realCompanions
        DownloadsPlatformDownloader.freeBytesProvider = realFreeBytes
        DownloadsPlatformDownloader.progressIntervalMs = realInterval
    }

    private fun waitFor(timeoutMs: Long = 30_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting: $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun a_transfer_paused_for_a_hotspot_keeps_its_partial_file_and_continues_it_with_a_range_request() {
        val payload = Random(11).nextBytes(2 * 1024 * 1024)
        val ranges = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/e1.mkv") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            ranges += range
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val body = payload.copyOfRange(start, payload.size)
            if (start > 0) exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            exchange.sendResponseHeaders(if (start > 0) 206 else 200, body.size.toLong())
            try {
                exchange.responseBody.use { out ->
                    var sent = 0
                    while (sent < body.size) {
                        val n = minOf(32 * 1024, body.size - sent)
                        out.write(body, sent, n)
                        out.flush()
                        sent += n
                        // Slow at first, so the hold comes in the middle; quick once resumed.
                        if (start == 0) Thread.sleep(25)
                    }
                }
            } catch (_: IOException) {
                // The client went away: the pause.
            }
        }
        server.start()
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile()
        var fileName: String? = null
        try {
            DownloadsRepository.enqueueFromStream(
                contentType = "movie", videoId = "tt9", parentMetaId = "tt9", parentMetaType = "movie",
                title = "Hold test ${System.nanoTime()}", logo = null, poster = null, background = null,
                seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null,
                stream = StreamItem(
                    name = "1080p", url = "http://127.0.0.1:${server.address.port}/e1.mkv",
                    addonName = "A", addonId = "a", behaviorHints = StreamBehaviorHints(),
                ),
            )
            fun current() = DownloadsRepository.uiState.value.items.single()
            fileName = current().fileName
            val part = File(downloadsDir, "${current().fileName}.part")
            waitFor(what = "some bytes to arrive") { part.length() > 256 * 1024 }

            hold.report(hotspot)

            assertEquals(DownloadStatus.Paused, current().status)
            assertTrue(current().pausedByConnection)
            // The stopped transfer closes its file; what it wrote stays.
            var last = -1L
            waitFor(what = "the partial file to settle") {
                val now = part.length()
                (now == last).also { last = now; Thread.sleep(150) }
            }
            val kept = part.length()
            assertTrue(kept in 1 until payload.size.toLong(), "partial file has $kept bytes")

            hold.report(home)

            waitFor(what = "the download to finish") { current().status == DownloadStatus.Completed }
            val finished = File(URI(current().localFileUri!!))
            assertContentEquals(payload, finished.readBytes())
            assertNull(ranges.first(), "the first request asked for a range")
            assertEquals("bytes=$kept-", ranges.last(), "the resumed request did not continue from the partial file")
            finished.delete()
        } finally {
            server.stop(0)
            fileName?.let {
                File(downloadsDir, it).delete()
                File(downloadsDir, "$it.part").delete()
            }
        }
    }
}
