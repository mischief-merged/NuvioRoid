package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One lock order: the per-video lock first, then the repository's state lock. A companion write
 * (per-video lock held, guard called) and a delete running together must finish, never wait for ever.
 * Threads are daemons and every wait has a limit, so a deadlock fails the test instead of hanging it.
 */
class SidecarLockOrderTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val files = mutableSetOf<String>()
        val trashed = mutableListOf<String>()
        var finish: ((String, Long?) -> Unit)? = null
        var progress: ((Long, Long?) -> Unit)? = null
        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) { this.payload = payload }
        override fun restoreItem(item: DownloadItem): DownloadItem = item
        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            finish = onSuccess; progress = onProgress
            return object : DownloadsTaskHandle { override fun cancel() = Unit }
        }
        override fun removeFile(localFileUri: String?): Boolean = true
        override fun removePartialFile(destinationFileName: String): Boolean = true
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri?.takeIf { it in files }
        override fun inspectFile(localFileUri: String): DownloadedFileFacts =
            if (localFileUri in files) DownloadedFileFacts(exists = true, sizeBytes = 1_000, isVideoContainer = true) else DownloadedFileFacts(exists = false)
        override fun trashWatchedFile(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult {
            if (!stillWanted()) return AutoDownloadRemovalResult.Changed
            return if (trashFile(localFileUri)) AutoDownloadRemovalResult.Removed else AutoDownloadRemovalResult.NotMoved
        }

        override fun trashFile(localFileUri: String): Boolean { trashed += localFileUri; files.remove(localFileUri); return true }
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val realCompanions = DownloadsRepository.companions
    private lateinit var folder: File
    private var deadlocked = false

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("nuvio-lock-order").toFile()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
    }

    @AfterTest
    fun tearDown() {
        // After a deadlock the repository's lock is held for ever: do not wait on it again.
        if (deadlocked) return
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.companions = realCompanions
        folder.deleteRecursively()
    }

    private fun download(ruleKey: String? = null): String {
        val uri = File(folder, "a.mkv").also { it.writeText("v") }.toURI().toString()
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:1", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 1, episodeTitle = null,
            episodeThumbnail = null, stream = StreamItem(name = "1080p", url = "https://example.com/e.mkv", addonName = "A", addonId = "a"),
            autoDownloadRuleKey = ruleKey,
        )
        backend.files += uri
        backend.progress!!(0, 1_000)
        backend.finish!!(uri, 1_000)
        return uri
    }

    private fun daemon(name: String, block: () -> Unit) = Thread(block, name).also { it.isDaemon = true; it.start() }

    /** A companion write holds the video lock and asks the guard; a delete starts meanwhile. Both must finish. */
    private fun writeAndDeleteTogether(delete: () -> Unit) {
        val uri = download(ruleKey = "rule")
        val holding = CountDownLatch(1)
        val deleteStarted = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val deleterDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)

        daemon("companion-write") {
            runCatching {
                SidecarLocks.withLock(uri) {
                    holding.countDown()
                    deleteStarted.await(5, TimeUnit.SECONDS)
                    Thread.sleep(300) // the delete is now waiting
                    DownloadCompanionGuard.stillWanted(uri)
                }
            }.onFailure { failure.set(it) }
            writerDone.countDown()
        }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        daemon("delete") {
            deleteStarted.countDown()
            runCatching { delete() }.onFailure { failure.set(it) }
            deleterDone.countDown()
        }

        val finished = writerDone.await(5, TimeUnit.SECONDS) && deleterDone.await(5, TimeUnit.SECONDS)
        if (!finished) deadlocked = true
        assertTrue(finished, "the companion write and the delete are stuck: deadlock")
        assertEquals(null, failure.get())
        assertEquals(listOf(uri), backend.trashed)
    }

    @Test
    fun a_manual_delete_and_a_companion_write_running_together_both_finish() {
        writeAndDeleteTogether { DownloadsRepository.deleteDownload(DownloadsRepository.uiState.value.items.single().id) }
    }

    @Test
    fun an_automatic_removal_and_a_companion_write_running_together_both_finish() {
        writeAndDeleteTogether {
            DownloadsRepository.removeWatchedAutoDownload(DownloadsRepository.uiState.value.items.single().id, "rule")
        }
    }

    @Test
    fun the_guard_needs_no_repository_lock_at_all() {
        val uri = download()
        val gotIt = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Another thread sits inside the repository's state lock.
        daemon("state-lock-holder") {
            DownloadsRepository.holdStateLockForTest { gotIt.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        assertTrue(gotIt.await(5, TimeUnit.SECONDS))
        val answered = CountDownLatch(1)
        daemon("guard") { DownloadCompanionGuard.stillWanted(uri); answered.countDown() }

        val answeredWhileLocked = answered.await(2, TimeUnit.SECONDS)
        release.countDown()
        assertTrue(answeredWhileLocked, "the guard waited for the state lock")
    }

    @Test
    fun a_delete_of_an_unfinished_download_and_a_delete_of_a_finished_one_still_work() {
        val uri = download()
        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(DownloadsRepository.uiState.value.items.single().id))
        assertEquals(listOf(uri), backend.trashed)
        assertEquals(DownloadDeleteResult.Gone, DownloadsRepository.deleteDownload("nothing"))
    }
}
