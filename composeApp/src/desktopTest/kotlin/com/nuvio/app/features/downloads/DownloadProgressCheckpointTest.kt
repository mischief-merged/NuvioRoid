package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.TrustedLocalServerTest
import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

internal fun progressItem(id: String, status: DownloadStatus = DownloadStatus.Downloading) = DownloadItem(
    id = id, contentType = "movie", parentMetaId = id, parentMetaType = "movie", videoId = id,
    title = id, streamTitle = "Video", providerName = "Source", sourceUrl = "https://example.com/$id.mp4",
    fileName = "$id.mp4", status = status, localFileUri = if (status == DownloadStatus.Completed) "file:/$id.mp4" else null,
    createdAtEpochMs = 1, updatedAtEpochMs = 1,
)

internal class ProgressBackend : DownloadsBackend {
    override var batchesProgress = true
    var payload: String? = null
    var writes = 0
    var freshReads = 0
    var failWrites = false
    var beforeProgressSave: () -> Unit = {}
    val progress = mutableMapOf<String, (Long, Long?) -> Unit>()
    val success = mutableMapOf<String, (String, Long?) -> Unit>()
    val failure = mutableMapOf<String, (String) -> Unit>()
    override fun loadPayload() = payload
    override fun savePayload(payload: String) { check(!failWrites); writes++; this.payload = payload }
    override val rereadsPayload = true
    override fun loadFreshPayload(): String? { freshReads++; return payload }
    override fun saveProgress(profileId: Int, items: List<DownloadItem>): Boolean {
        beforeProgressSave()
        return super.saveProgress(profileId, items)
    }
    override fun restoreItem(item: DownloadItem) = item
    override fun start(request: DownloadPlatformRequest, onProgress: (Long, Long?) -> Unit,
        onSuccess: (String, Long?) -> Unit, onFailure: (String) -> Unit, onPaused: () -> Unit): DownloadsTaskHandle {
        progress[request.item.id] = onProgress; success[request.item.id] = onSuccess; failure[request.item.id] = onFailure
        return object : DownloadsTaskHandle { override fun cancel() { onProgress(999, 1000) } }
    }
    override fun removeFile(localFileUri: String?) = true
    override fun removePartialFile(destinationFileName: String) = true
    override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProgressCheckpointTest : TrustedLocalServerTest() {
    private val original = DownloadsRepository.backend
    private val originalScope = DownloadsRepository.progressScope
    private val originalCompanions = DownloadsRepository.companions
    private val backend = ProgressBackend()
    @BeforeTest fun setup() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
    }
    @AfterTest fun cleanup() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = original
        DownloadsRepository.progressScope = originalScope
        DownloadsRepository.companions = originalCompanions
    }
    private fun load(vararg items: DownloadItem) {
        backend.payload = DownloadsCodec.encode(items.toList(), null)
        DownloadsRepository.ensureLoaded()
        backend.writes = 0; backend.freshReads = 0
    }
    private fun saved(id: String = "one") = decodeStoredDownloads(backend.payload!!)!!.first { it.id == id }

    @Test fun forty_ticks_for_two_downloads_share_one_write_each_five_seconds() = runTest {
        DownloadsRepository.progressScope = backgroundScope
        load(progressItem("one"), progressItem("two"))
        repeat(20) { n ->
            backend.progress.getValue("one")((n + 1).toLong(), 1000)
            backend.progress.getValue("two")((n + 1).toLong(), 1000)
            advanceTimeBy(250)
        }
        assertEquals(0, backend.writes)
        runCurrent()
        assertEquals(1, backend.writes)
        assertEquals(20L, saved().downloadedBytes)
        backend.progress.getValue("one")(30, 1000)
        advanceTimeBy(4999); runCurrent()
        assertEquals(1, backend.writes)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, backend.writes)
        assertEquals(0, backend.freshReads)
    }

    @Test fun pause_failure_and_completion_save_the_latest_count_without_waiting() = runTest {
        DownloadsRepository.progressScope = backgroundScope
        load(progressItem("one"), progressItem("two"), progressItem("three"))
        backend.progress.getValue("one")(123, 1000)
        DownloadsRepository.pauseDownload("one")
        assertEquals(123L, saved().downloadedBytes)
        assertEquals(DownloadStatus.Paused, saved().status)
        backend.progress.getValue("two")(234, 1000)
        backend.failure.getValue("two")("HTTP 403")
        assertEquals(234L, saved("two").downloadedBytes)
        assertEquals(DownloadStatus.Failed, saved("two").status)
        backend.progress.getValue("three")(345, 1000)
        backend.success.getValue("three")("file:/three.mp4", null)
        assertEquals(345L, saved("three").downloadedBytes)
        assertEquals(DownloadStatus.Completed, saved("three").status)
        assertEquals("file:/three.mp4", saved("three").localFileUri)
        // No frame look, so the address and the accepted result are one save after the row is shown.
        // The queued byte count for that row is dropped, so the timer does not write it again.
        assertEquals(3, backend.writes)
        advanceTimeBy(5000); runCurrent()
        assertEquals(3, backend.writes)
    }

    @Test fun quit_freezes_callbacks_and_flushes_before_returning() = runTest {
        DownloadsRepository.progressScope = backgroundScope
        load(progressItem("one"))
        backend.progress.getValue("one")(456, 1000)
        assertTrue(flushDownloadProgressOnExit())
        backend.progress.getValue("one")(789, 1000)
        assertEquals(456L, saved().downloadedBytes)
        assertEquals(456L, DownloadsRepository.uiState.value.items.single().downloadedBytes)
        assertEquals(DownloadStatus.Downloading, saved().status)
        assertEquals(1, backend.writes)
    }

    @Test fun failed_checkpoints_retry_without_another_tick() = runTest {
        DownloadsRepository.progressScope = backgroundScope
        load(progressItem("one"))
        backend.progress.getValue("one")(123, 1000)
        backend.failWrites = true
        advanceTimeBy(10000); runCurrent()
        assertEquals(0, backend.writes)
        backend.failWrites = false
        advanceTimeBy(5000); runCurrent()
        assertEquals(123L, saved().downloadedBytes)
        assertEquals(1, backend.writes)
    }

    @Test fun blocked_storage_does_not_hold_the_state_lock_and_quit_wait_is_bounded() = runTest {
        DownloadsRepository.progressScope = backgroundScope
        load(progressItem("one"))
        backend.progress.getValue("one")(123, 1000)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        backend.beforeProgressSave = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        val worker = thread { try { runBlocking { DownloadsRepository.flushProgress() } } finally { done.countDown() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            DownloadsRepository.holdStateLockForTest { }
            backend.progress.getValue("one")(456, 1000)
            val started = System.nanoTime()
            assertFalse(flushDownloadProgressOnExit(50))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
        } finally { release.countDown(); worker.join(5000) }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(flushDownloadProgressOnExit())
        assertEquals(456L, saved().downloadedBytes)
    }

    @Test fun cancelled_saver_cannot_forget_its_replacement_and_start_an_extra_timer() = runTest {
        DownloadsRepository.progressScope = backgroundScope
        load(progressItem("one"))
        backend.progress.getValue("one")(100, 1000)
        backend.beforeProgressSave = {
            backend.beforeProgressSave = {}
            // Reset during the synchronous save, before the cancelled coroutine can return.
            DownloadsRepository.clearLocalState()
            load(progressItem("one"))
            backend.progress.getValue("one")(200, 1000)
            DownloadsRepository.pauseDownload("one")
            DownloadsRepository.resumeDownload("one")
        }
        advanceTimeBy(5000); runCurrent()
        val writesAfterReset = backend.writes
        advanceTimeBy(1000); runCurrent()
        backend.progress.getValue("one")(300, 1000)
        advanceTimeBy(4000); runCurrent()
        assertEquals(writesAfterReset + 1, backend.writes)
        assertEquals(300L, saved().downloadedBytes)
        advanceTimeBy(500); runCurrent()
        backend.progress.getValue("one")(400, 1000)
        advanceTimeBy(500); runCurrent()
        assertEquals(writesAfterReset + 1, backend.writes, "An extra timer must not save after only one second")
        advanceTimeBy(4499); runCurrent()
        assertEquals(writesAfterReset + 1, backend.writes)
        advanceTimeBy(1); runCurrent()
        assertEquals(writesAfterReset + 2, backend.writes)
        assertEquals(400L, saved().downloadedBytes)
    }

    @Test fun real_store_progress_patch_preserves_other_records_profiles_and_unknown_fields() {
        val file = Files.createTempDirectory("progress-store").resolve("downloads.properties")
        val first = DesktopStorage.Store(file); val other = DesktopStorage.Store(file)
        val one = progressItem("one")
        first.putString("downloads_41", DownloadsCodec.encode(listOf(one), null))
        first.getString("downloads_42")
        other.putString("downloads_42", "other profile")
        other.putString("downloads_41", DownloadsCodec.encode(listOf(one, progressItem("added")), null).replaceFirst("{", "{\"future\":7,"))
        assertTrue(saveDownloadProgress(first, 41, listOf(one.copy(downloadedBytes = 456))))
        val stored = DesktopStorage.Store(file)
        assertEquals("other profile", stored.getString("downloads_42"))
        assertTrue(stored.getString("downloads_41")!!.contains("\"future\":7"))
        assertEquals(listOf("one", "added"), decodeStoredDownloads(stored.getString("downloads_41")!!)!!.map { it.id })
        assertEquals(456L, decodeStoredDownloads(stored.getString("downloads_41")!!)!!.first().downloadedBytes)
        other.putString("downloads_41", DownloadsCodec.encode(listOf(one.copy(status = DownloadStatus.Paused)), null))
        saveDownloadProgress(first, 41, listOf(one.copy(downloadedBytes = 789)))
        assertEquals(0L, decodeStoredDownloads(DesktopStorage.Store(file).getString("downloads_41")!!)!!.single().downloadedBytes)
    }

    @Test fun a_backend_without_atomic_updates_keeps_immediate_progress_saves() = runTest {
        DownloadsRepository.progressScope = backgroundScope
        backend.batchesProgress = false
        backend.beforeProgressSave = { error("Counter batching is unavailable") }
        load(progressItem("one"))
        repeat(40) { backend.progress.getValue("one")(it + 1L, 1000) }
        assertEquals(40, backend.writes)
        assertEquals(40L, saved().downloadedBytes)
        DownloadsRepository.pauseDownload("one")
        assertEquals(41, backend.writes)
        advanceTimeBy(10000); runCurrent()
        assertTrue(flushDownloadProgressOnExit())
        assertEquals(41, backend.writes)
        assertEquals(DownloadStatus.Paused, saved().status)
    }

    @Test fun real_store_does_not_write_tied_totals_or_older_counter_snapshots() {
        val file = Files.createTempDirectory("progress-order").resolve("downloads.properties")
        var writes = 0
        val writer = DesktopStorage.Store(file, write = { properties, output -> writes++; properties.store(output, null) })
        val other = DesktopStorage.Store(file)
        val current = progressItem("one").copy(downloadedBytes = 456, totalBytes = 1000, updatedAtEpochMs = 100)
        val payload = DownloadsCodec.encode(listOf(current), null)
        other.putString("downloads_41", payload)
        val stale = listOf(
            current.copy(totalBytes = null), current.copy(totalBytes = 900), current.copy(totalBytes = 2000),
            current.copy(downloadedBytes = 400, updatedAtEpochMs = 101),
            current.copy(downloadedBytes = 789, updatedAtEpochMs = 99),
        )
        stale.forEach { snapshot ->
            assertTrue(saveDownloadProgress(writer, 41, listOf(snapshot)))
            assertEquals(payload, other.getFreshString("downloads_41"))
        }
        assertEquals(0, writes)
    }

    @Test fun real_store_accepts_newer_bytes_in_the_same_millisecond_and_newer_totals_later() {
        assertTrue(DownloadsStorage.batchesProgress)
        val file = Files.createTempDirectory("progress-order").resolve("downloads.properties")
        var writes = 0
        val writer = DesktopStorage.Store(file, write = { properties, output -> writes++; properties.store(output, null) })
        val other = DesktopStorage.Store(file)
        val first = progressItem("one").copy(downloadedBytes = 456, updatedAtEpochMs = 100)
        other.putString("downloads_41", DownloadsCodec.encode(listOf(first), null))
        val laterBytes = first.copy(downloadedBytes = 789, totalBytes = 1000)
        val laterTotal = laterBytes.copy(totalBytes = 2000, updatedAtEpochMs = 101)
        listOf(laterBytes, laterTotal).forEach { snapshot ->
            assertTrue(saveDownloadProgress(writer, 41, listOf(snapshot)))
            assertEquals(snapshot, decodeStoredDownloads(other.getFreshString("downloads_41")!!)!!.single())
        }
        assertTrue(saveDownloadProgress(writer, 41, listOf(laterTotal)))
        assertEquals(2, writes)
    }

    @Test fun real_store_counter_patch_keeps_the_published_address_and_check_checkpoint() {
        val file = Files.createTempDirectory("progress-published").resolve("downloads.properties")
        val first = DesktopStorage.Store(file)
        val other = DesktopStorage.Store(file)
        val before = progressItem("one")
        val checking = before.copy(
            localFileUri = "file:/one.mp4", awaitingCheck = true, publishedFileKey = "final-file",
            publishedDigestVerified = true, downloadedBytes = 456, totalBytes = 1000, updatedAtEpochMs = 100,
        )
        other.putString("downloads_41", DownloadsCodec.encode(listOf(checking), null))
        assertTrue(saveDownloadProgress(first, 41, listOf(before.copy(downloadedBytes = 123, updatedAtEpochMs = 99))))
        assertEquals(checking, decodeStoredDownloads(other.getFreshString("downloads_41")!!)!!.single())
        val newer = before.copy(downloadedBytes = 789, totalBytes = 1000, updatedAtEpochMs = 101)
        assertTrue(saveDownloadProgress(first, 41, listOf(newer)))
        assertEquals(
            checking.copy(downloadedBytes = 789, updatedAtEpochMs = 101),
            decodeStoredDownloads(other.getFreshString("downloads_41")!!)!!.single(),
        )
    }

    @Test fun real_resume_uses_partial_length_when_the_stored_counter_is_stale() = runBlocking {
        val body = ByteArray(8192) { (it % 251).toByte() }
        val partial = Files.createTempDirectory("progress-resume").resolve("video.part").toFile()
        partial.writeBytes(body.copyOf(4096))
        var range: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video") { exchange ->
            range = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Range", "bytes 4096-8191/8192")
            exchange.sendResponseHeaders(206, 4096)
            exchange.responseBody.use { it.write(body, 4096, 4096) }
        }
        server.start()
        val freeBefore = DownloadsPlatformDownloader.freeBytesProvider
        try {
            DownloadsPlatformDownloader.freeBytesProvider = { Long.MAX_VALUE }
            val item = progressItem("one").copy(downloadedBytes = 1, sourceUrl = "http://127.0.0.1:${server.address.port}/video")
            DownloadsPlatformDownloader.transferToTempFile(DownloadPlatformRequest(item), partial, { _, _ -> })
            assertEquals("bytes=4096-", range)
            assertContentEquals(body, partial.readBytes())
        } finally { server.stop(0); DownloadsPlatformDownloader.freeBytesProvider = freeBefore }
    }
}
