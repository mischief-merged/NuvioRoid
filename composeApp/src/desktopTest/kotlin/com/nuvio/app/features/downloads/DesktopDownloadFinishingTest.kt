package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDownloadFinishingTest {
    private val world = FolderWorld()
    private val originalBackend = DownloadsRepository.backend
    private val originalCompanions = DownloadsRepository.companions
    private val originalHold = DownloadsRepository.connectionHold
    private val originalRuntime = downloadRuntimeLookup
    private val requests = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val release = CountDownLatch(1)
    private var done = CountDownLatch(1)
    private var blockFrames = false
    private var sample: () -> Unit = {}
    private var serve: () -> Unit = {}
    private var save: (List<DownloadItem>) -> Unit = {}
    private var sync: () -> Boolean = { true }
    private var payload: String? = null
    private val handles = mutableListOf<DownloadsTaskHandle>()
    private val bytes = videoFile(21)
    private val backend = object : DownloadsBackend {
        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) {
            save(decodeStoredDownloads(payload).orEmpty())
            this@DesktopDownloadFinishingTest.payload = payload
        }
        override fun syncPayload(): Boolean = sync()
        override fun restoreItem(item: DownloadItem): DownloadItem = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (Long, Long?) -> Unit,
            onSuccess: (String, Long?) -> Unit,
            onFailure: (String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle = DownloadsPlatformDownloader.start(
            request.copy(onFinished = { request.onFinished(); done.countDown() }),
            onProgress, onSuccess, onFailure, onPaused,
        ).also { handles += it }
        override fun removeFile(localFileUri: String?): Boolean = error("must not remove an existing final file")
        override fun removeJustPublished(localFileUri: String, fileKey: String?): Boolean =
            DownloadsPlatformDownloader.removeJustPublished(localFileUri, fileKey)
        override fun removePartialFile(destinationFileName: String): Boolean = error("must not discard a partial")
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, destinationFileName)
        override fun inspectFile(localFileUri: String): DownloadedFileFacts? =
            inspectDownloadedFile(localFileUri)?.let { if (blockFrames) it.copy(durationMs = null) else it }
        override fun sampleFrames(localFileUri: String): FrameSamples? { sample(); return null }
    }

    @BeforeTest
    fun set_up() {
        Files.createDirectories(world.appFolder)
        server.createContext("/video.mp4") { exchange ->
            requests.incrementAndGet()
            serve()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.companions = { DownloadCompanions(emptyList(), isOnline = { false }) }
        DownloadsRepository.connectionHold = ConnectionHold()
        downloadRuntimeLookup = { null }
        DesktopDownloads.replaceForTest(DesktopDownloadFolders(world.locations, world.validator, world.mover()))
    }

    @AfterTest
    fun clean_up() {
        release.countDown()
        handles.forEach { assertTrue(it.cancelAndWait(5_000)) }
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = originalBackend
        DownloadsRepository.companions = originalCompanions
        DownloadsRepository.connectionHold = originalHold
        downloadRuntimeLookup = originalRuntime
        DesktopDownloads.replaceForTest(null)
        server.stop(0)
        world.close()
    }

    private fun item() = movieItem("finish", bytes = bytes.size.toLong()).copy(
        status = DownloadStatus.Downloading,
        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mp4",
        downloadedBytes = bytes.size / 2L,
    )

    private fun launch(item: DownloadItem) {
        payload = storedPayload(listOf(item))
        DownloadsRepository.ensureLoaded()
    }

    private fun awaitDone() = assertTrue(done.await(5, TimeUnit.SECONDS), "finishing did not end")
    private fun stored() = decodeStoredDownloads(payload!!).orEmpty().single()

    @Test
    fun a_final_file_left_after_publish_is_verified_and_saved_without_a_network_request() {
        val file = world.appFolder.resolve(item().fileName)
        Files.write(file, bytes)
        launch(item()) // Older record: the recorded location and name are its only address.
        awaitDone()
        assertEquals(0, requests.get())
        assertEquals(DownloadStatus.Completed, stored().status)
        assertEquals(file.toUri(), java.net.URI(stored().localFileUri!!))
        assertContentEquals(bytes, Files.readAllBytes(file))
        assertFalse(Files.exists(file.resolveSibling("${file.fileName}.part")))
    }

    @Test
    fun an_unknown_length_final_file_is_verified_without_claiming_a_server_size_check() {
        Files.write(world.appFolder.resolve(item().fileName), bytes)
        launch(item().copy(totalBytes = null))
        awaitDone()
        assertEquals(0, requests.get())
        assertEquals(DownloadStatus.Completed, stored().status)
        assertEquals(DownloadVerification.None, stored().verifiedBy)
    }

    @Test
    fun a_recovered_final_file_that_fails_verification_is_kept() {
        val file = world.appFolder.resolve(item().fileName)
        val bad = ByteArray(bytes.size) { 3 }
        Files.write(file, bad)
        launch(item().copy(localFileUri = file.toUri().toString()))
        awaitDone()
        assertEquals(0, requests.get())
        assertEquals(DownloadStatus.Failed, stored().status)
        assertContentEquals(bad, Files.readAllBytes(file))
    }

    @Test
    fun quit_waits_for_verification_and_the_completed_record_to_be_saved() = runBlocking {
        val sampling = CountDownLatch(1)
        downloadRuntimeLookup = { 1 }
        blockFrames = true
        sample = { sampling.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        Files.write(world.appFolder.resolve(item().fileName), bytes)
        launch(item())
        assertTrue(sampling.await(5, TimeUnit.SECONDS))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { DownloadsRepository.awaitFinishingDownloads(2_000) }
        assertFalse(waiting.isCompleted)
        assertEquals(DownloadStatus.Downloading, stored().status)
        release.countDown()
        assertTrue(waiting.await())
        assertEquals(DownloadStatus.Completed, stored().status)
        awaitDone()
    }

    @Test
    fun a_new_transfer_records_its_final_path_before_unlinking_and_quit_waits_for_publication() = runBlocking {
        val syncing = CountDownLatch(1)
        val part = world.appFolder.resolve("${item().fileName}.part")
        sync = {
            assertTrue(Files.exists(part))
            assertEquals(DownloadStatus.Downloading, stored().status)
            assertTrue(Files.isRegularFile(java.nio.file.Path.of(java.net.URI(stored().localFileUri!!))))
            syncing.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            true
        }
        launch(item().copy(downloadedBytes = 0, totalBytes = null))
        assertTrue(syncing.await(5, TimeUnit.SECONDS))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { DownloadsRepository.awaitFinishingDownloads(2_000) }
        assertFalse(waiting.isCompleted)
        release.countDown()
        assertTrue(waiting.await())
        awaitDone()
        assertEquals(1, requests.get())
        assertEquals(DownloadStatus.Completed, stored().status)
        assertFalse(Files.exists(part))
        assertContentEquals(bytes, Files.readAllBytes(world.appFolder.resolve(item().fileName)))
    }

    @Test
    fun quit_has_a_deadline_even_when_the_final_save_holds_the_repository_lock() = runBlocking {
        val saving = CountDownLatch(1)
        save = { items ->
            if (items.single().status == DownloadStatus.Completed) {
                saving.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        }
        Files.write(world.appFolder.resolve(item().fileName), bytes)
        launch(item())
        assertTrue(saving.await(5, TimeUnit.SECONDS))
        val start = System.nanoTime()
        assertFalse(DownloadsRepository.awaitFinishingDownloads(50))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
        release.countDown()
        awaitDone()
        assertEquals(DownloadStatus.Completed, stored().status)
    }

    @Test
    fun a_quit_timeout_followed_by_a_fresh_load_finishes_the_published_file_without_downloading_again() = runBlocking {
        val sampling = CountDownLatch(1)
        downloadRuntimeLookup = { 1 }
        blockFrames = true
        sample = { sampling.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        launch(item().copy(downloadedBytes = 0, totalBytes = null))
        assertTrue(sampling.await(5, TimeUnit.SECONDS))
        assertFalse(Files.exists(world.appFolder.resolve("${item().fileName}.part")))
        assertFalse(DownloadsRepository.awaitFinishingDownloads(50))
        val interrupted = payload
        assertEquals(DownloadStatus.Downloading, stored().status)
        // Drop the first process's in-memory ownership before allowing its callback to return.
        DownloadsRepository.clearLocalState()
        release.countDown()
        awaitDone()
        sample = {}
        downloadRuntimeLookup = { null }
        blockFrames = false
        payload = interrupted
        DownloadsRepository.ensureLoaded()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (stored().status == DownloadStatus.Downloading && System.nanoTime() < deadline) Thread.yield()
        DownloadsRepository.holdStateLockForTest {}
        assertEquals(1, requests.get())
        assertEquals(DownloadStatus.Completed, stored().status)
        assertContentEquals(bytes, Files.readAllBytes(world.appFolder.resolve(item().fileName)))
    }

    @Test
    fun a_final_path_is_saved_before_the_partial_goes_and_recovery_uses_that_path_after_a_crash() {
        val original = world.appFolder.resolve(item().fileName)
        val part = original.resolveSibling("${original.fileName}.part")
        Files.write(original, "another file".toByteArray())
        Files.write(part, bytes)
        var recorded = item()
        val published = publishDurably(
            part, original, flushFolder = { true },
            link = { _, _ -> throw UnsupportedOperationException() }, // A volume that needs the copy fallback.
            beforeRemovingPartial = { path ->
                assertTrue(Files.exists(part))
                recorded = recorded.copy(localFileUri = path.toUri().toString(), downloadedBytes = bytes.size.toLong())
                true
            },
        )
        assertFalse(Files.exists(part))
        assertTrue(published != original)
        launch(recorded) // No success callback ever ran in the previous process.
        awaitDone()
        assertEquals(0, requests.get())
        assertEquals(DownloadStatus.Completed, stored().status)
        assertEquals(published.toUri(), java.net.URI(stored().localFileUri!!))
        assertEquals("another file", Files.readString(original))
        assertContentEquals(bytes, Files.readAllBytes(published))
    }

    @Test
    fun implausible_final_files_partials_and_links_are_never_recovered_or_removed() {
        val destination = world.appFolder.resolve(item().fileName).toFile()
        val partial = File(destination.parentFile, "${destination.name}.part")
        fun recovered(record: DownloadItem = item()) = publishedDownloadToVerify(record, destination, partial)
        assertNull(recovered())
        destination.writeBytes(ByteArray(0))
        assertNull(recovered())
        destination.writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(recovered())
        assertNull(recovered(item().copy(totalBytes = null, downloadedBytes = bytes.size.toLong())))
        destination.writeBytes(bytes)
        partial.writeText("partial must stay")
        assertNull(recovered())
        assertEquals("partial must stay", partial.readText())
        partial.delete()
        assertEquals(destination, recovered())
        val target = destination.toPath().resolveSibling("target.mp4")
        Files.move(destination.toPath(), target)
        Files.createSymbolicLink(destination.toPath(), target)
        assertNull(recovered())
        assertContentEquals(bytes, Files.readAllBytes(target))
    }

    @Test
    fun quit_does_not_wait_for_transfer_bodies_and_refused_publication_keeps_the_partial_for_next_launch() = runBlocking {
        val receiving = CountDownLatch(1)
        serve = { receiving.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        launch(item().copy(downloadedBytes = 0, totalBytes = null))
        assertTrue(receiving.await(5, TimeUnit.SECONDS))
        assertTrue(DownloadsRepository.awaitFinishingDownloads(50))
        release.countDown()
        awaitDone()
        assertEquals(DownloadStatus.Downloading, stored().status)
        assertContentEquals(bytes, Files.readAllBytes(world.appFolder.resolve("${item().fileName}.part")))
        assertFalse(Files.exists(world.appFolder.resolve(item().fileName)))
    }

    @Test
    fun quit_waits_for_all_existing_finishing_steps_under_one_deadline() = runBlocking {
        val finishing = DownloadFinishingSteps()
        val first = Any()
        val second = Any()
        assertTrue(finishing.begin(first))
        assertTrue(finishing.begin(second))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { finishing.awaitForQuit(1_000) }
        assertFalse(finishing.begin(Any()))
        assertTrue(finishing.begin(first)) // The success callback is part of work already publishing.
        finishing.end(first)
        assertFalse(waiting.isCompleted)
        finishing.end(second)
        assertTrue(waiting.await())
    }
}
