package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoDownloadRemovalTest {
    /** Keeps the stored payload in memory. Files live in a temporary folder that the test owns. */
    private inner class FakeBackend : DownloadsBackend {
        var payload: String? = null
        var trashWorks = true
        override val usesSystemTrash: Boolean get() = trash.usesSystemTrash
        var afterSave: () -> Unit = {}
        val trashed = mutableListOf<String>()

        override fun loadPayload(): String? = payload

        override fun savePayload(payload: String) {
            this.payload = payload
            afterSave()
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            finish[request.item.episodeNumber] = onSuccess
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean = true

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri

        override fun trashFile(localFileUri: String): Boolean {
            trashed += localFileUri
            return trashWorks && trash.moveAway(localFileUri)
        }

        override fun trashWatchedFile(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult {
            trashed += localFileUri
            return if (trashWorks) trash.moveWatchedAway(localFileUri, stillWanted) else AutoDownloadRemovalResult.NotMoved
        }

        override fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean =
            trash.isSameFile(firstFileUri, secondFileUri)
    }

    private val showId = "removal-test"
    private val ruleKey = autoDownloadRuleKey("series", showId)
    private val finish = mutableMapOf<Int?, (String, Long?) -> Unit>()
    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private lateinit var folder: Path
    private lateinit var downloadsDir: Path
    private lateinit var trash: DesktopDownloadsTrash
    private lateinit var systemTrashDir: Path
    private var afterTrashMove: () -> Unit = {}

    @BeforeTest
    fun use_an_empty_in_memory_repository_and_a_temporary_downloads_folder() {
        folder = Files.createTempDirectory("nuvio-removal-test").toRealPath()
        downloadsDir = folder.resolve("downloads").createDirectories()
        systemTrashDir = folder.resolve("system-trash").createDirectories()
        trash = DesktopDownloadsTrash(downloadsDir = { downloadsDir }, systemTrash = { file ->
            Files.move(file.toPath(), systemTrashDir.resolve(file.name))
            afterTrashMove()
            true
        })
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        folder.toFile().deleteRecursively()
    }

    /** Queues an episode and finishes it with a file in the downloads folder. Returns the file. */
    private fun download(episode: Int, automatic: Boolean, file: Path = downloadsDir.resolve("episode-$episode.mkv")): Path {
        DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "$showId:1:$episode",
            parentMetaId = showId,
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = episode,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = testStream("https://example.com/$episode.mp4"),
            autoDownloadRuleKey = ruleKey.takeIf { automatic },
            // A download the user starts by choosing a source replaces what is there; an automatic one never does.
            userChoseReplacement = !automatic,
        )
        if (!file.exists()) file.writeText("video $episode")
        finish.getValue(episode)(file.toUri().toString(), 7)
        return file
    }

    private fun item(episode: Int) = DownloadsRepository.uiState.value.items.firstOrNull { it.episodeNumber == episode }

    private fun remove(episode: Int) = DownloadsRepository.removeWatchedAutoDownload(item(episode)!!.id, ruleKey)

    private fun heldFiles() = downloadsDir.resolve(DesktopDownloadsTrash.HoldingFolderName).toFile().list().orEmpty()
        .filter { it != DesktopDownloadsTrash.IndexFileName }

    @Test
    fun a_manual_download_uses_the_same_trash_step_with_no_rule_key() {
        val file = download(1, automatic = false)
        val result = DownloadsRepository.removeWatchedAutoDownload(item(1)!!.id, null)
        assertEquals(AutoDownloadRemovalResult.Removed, result)
        assertEquals(listOf(file.toUri().toString()), backend.trashed)
        assertFalse(file.exists())
        assertTrue(heldFiles().isEmpty())
        assertTrue(systemTrashDir.resolve(file.fileName).exists())
        assertNull(item(1))
    }

    @Test
    fun a_manual_removal_is_left_alone_when_the_last_check_says_keep() {
        val file = download(1, automatic = false)
        assertEquals(AutoDownloadRemovalResult.Changed, DownloadsRepository.removeWatchedAutoDownload(item(1)!!.id, null) { false })
        assertTrue(file.exists())
        assertTrue(backend.trashed.isEmpty())
    }

    @Test
    fun watched_downloads_are_left_alone_without_system_trash() {
        val file = download(1, automatic = false)
        trash = DesktopDownloadsTrash(downloadsDir = { downloadsDir }, systemTrash = null)
        assertEquals(AutoDownloadRemovalResult.NotMoved, DownloadsRepository.removeWatchedAutoDownload(item(1)!!.id, null))
        assertTrue(file.exists())
        assertTrue(backend.trashed.isEmpty())
        assertTrue(heldFiles().isEmpty())
        assertEquals(DownloadStatus.Completed, item(1)?.status)
    }

    @Test
    fun keep_after_the_last_check_during_the_list_save_keeps_the_file_and_record() =
        cancelDuringRemoval(afterMove = false) { store, id -> store.keep(1, id) }

    @Test
    fun off_after_the_last_check_during_the_list_save_keeps_the_file_and_record() =
        cancelDuringRemoval(afterMove = false) { store, _ -> store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.Off) }

    @Test
    fun automatic_only_after_the_last_check_keeps_a_manual_file_and_record() =
        cancelDuringRemoval(afterMove = false) { store, _ -> store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AutomaticOnly) }

    @Test
    fun keep_after_the_trash_move_restores_the_file_and_record() =
        cancelDuringRemoval(afterMove = true) { store, id -> store.keep(1, id) }

    @Test
    fun off_after_the_trash_move_restores_the_file_and_record() =
        cancelDuringRemoval(afterMove = true) { store, _ -> store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.Off) }

    @Test
    fun automatic_only_after_the_trash_move_restores_a_manual_file_and_record() =
        cancelDuringRemoval(afterMove = true) { store, _ -> store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AutomaticOnly) }

    @Test
    fun turning_off_a_rule_during_the_list_save_keeps_the_file_and_record() =
        cancelDuringRemoval(afterMove = false, automatic = true) { store, _ -> store.remove(1, ruleKey) }

    @Test
    fun turning_off_a_rule_after_the_trash_move_restores_the_file_and_record() =
        cancelDuringRemoval(afterMove = true, automatic = true) { store, _ -> store.remove(1, ruleKey) }

    @Test
    fun turning_off_a_rules_removal_switch_during_the_list_save_keeps_the_file_and_record() =
        cancelDuringRemoval(afterMove = false, automatic = true) { store, _ ->
            store.upsert(1, testRule(showId = showId).copy(deleteAfterWatching = false))
        }

    @Test
    fun turning_off_a_rules_removal_switch_after_the_trash_move_restores_the_file_and_record() =
        cancelDuringRemoval(afterMove = true, automatic = true) { store, _ ->
            store.upsert(1, testRule(showId = showId).copy(deleteAfterWatching = false))
        }

    @Test
    fun saving_a_disabled_switch_signals_before_reading_the_rule_during_the_list_save() =
        cancelDuringRemoval(afterMove = false, automatic = true) { store, _ ->
            assertEquals(AutoDownloadSaveResult.Changed,
                saveAutoDownloadRule(store, 1, testRule(showId = showId).copy(deleteAfterWatching = false)))
        }

    @Test
    fun saving_a_disabled_switch_signals_before_reading_the_rule_after_the_move() =
        cancelDuringRemoval(afterMove = true, automatic = true) { store, _ ->
            assertEquals(AutoDownloadSaveResult.Changed,
                saveAutoDownloadRule(store, 1, testRule(showId = showId).copy(deleteAfterWatching = false)))
        }

    private fun cancelDuringRemoval(afterMove: Boolean, automatic: Boolean = false, change: (AutoDownloadStore, String) -> Unit) {
        val file = download(1, automatic = automatic)
        val original = item(1)!!
        val store = AutoDownloadStore(MemoryAutoDownloadPayloads()) { 1 }
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        if (automatic) store.upsert(1, testRule(showId = showId).copy(deleteAfterWatching = true))
        val feature = DeleteAfterWatching(
            store, { DownloadsRepository.uiState.value.items }, { true }, { false }, { _, _ -> true },
            DownloadsRepository::removeWatchedAutoDownload, { 0L }, holdMs = 0L,
        )
        assertTrue(feature.onLocalPlaybackCompleted(1, original))
        val failure = AtomicReference<Throwable?>()
        var worker: Thread? = null
        var interrupted = false
        val interrupt = {
            if (!interrupted) {
                interrupted = true
                // The record has already been saved without this item, after stillWanted was true.
                assertNull(item(1))
                assertEquals(!afterMove, file.exists())
                val unchanged = WatchedRemovalGuard.check { true }
                val started = CountDownLatch(1)
                worker = thread(isDaemon = true) {
                    started.countDown()
                    runCatching { change(store, original.id) }.onFailure { failure.set(it) }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (unchanged() && System.nanoTime() < deadline) Thread.yield()
                assertFalse(unchanged(), "The change must signal cancellation before waiting for the lock")
            }
        }
        if (afterMove) afterTrashMove = interrupt else backend.afterSave = interrupt
        assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())
        worker?.join(5_000)
        assertFalse(worker?.isAlive ?: true)
        assertNull(failure.get())
        assertTrue(interrupted)
        assertEquals(original, item(1))
        assertEquals("video 1", file.readText())
        assertTrue(heldFiles().isEmpty())
        assertTrue(store.removals(1).isEmpty())
        assertTrue(store.recentlyDeleted.value.isEmpty())
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        assertEquals(original, item(1))
    }

    @Test
    fun opening_the_player_returns_on_the_ui_thread_while_another_thread_is_saving_removal() =
        openWhileRemovalIsPaused(afterMove = false)

    @Test
    fun opening_the_player_returns_on_the_ui_thread_while_another_thread_is_moving_the_file() =
        openWhileRemovalIsPaused(afterMove = true)

    private fun openWhileRemovalIsPaused(afterMove: Boolean) {
        val file = download(1, automatic = false)
        val original = item(1)!!
        val uri = file.toUri().toString()
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<AutoDownloadRemovalResult?>()
        var intercepted = false
        val pause = {
            if (!intercepted) {
                intercepted = true
                paused.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        if (afterMove) afterTrashMove = pause else backend.afterSave = pause
        val removal = thread(isDaemon = true) {
            runCatching {
                result.set(DownloadsRepository.removeWatchedAutoDownload(original.id, null) { !AutoDownloads.isPlaying(original) })
            }.onFailure { failure.set(it) }
        }
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            assertEquals(!afterMove, file.exists())
            SwingUtilities.invokeLater {
                try {
                    AutoDownloads.onLocalFileOpened(uri)
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    opened.countDown()
                }
            }
            assertTrue(opened.await(2, TimeUnit.SECONDS), "Opening must return before the removal lock is released")
            assertTrue(removal.isAlive)
            assertTrue(AutoDownloads.isPlaying(original), "Playback must be recorded while removal still holds the lock")
        } finally {
            release.countDown()
            removal.join(5_000)
            opened.await(5, TimeUnit.SECONDS)
            AutoDownloads.onLocalFileClosed(uri)
        }
        assertFalse(removal.isAlive)
        assertNull(failure.get())
        assertEquals(AutoDownloadRemovalResult.Changed, result.get())
        assertEquals(original, item(1))
        assertEquals("video 1", file.readText())
        assertTrue(heldFiles().isEmpty())
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        assertEquals(original, item(1))
    }

    @Test
    fun opening_the_player_during_the_list_save_keeps_the_file_and_record() =
        playbackChangesDuringRemoval(afterMove = false, progressChanged = false)

    @Test
    fun opening_the_player_after_the_move_restores_the_file_and_record() =
        playbackChangesDuringRemoval(afterMove = true, progressChanged = false)

    @Test
    fun changed_progress_during_the_list_save_keeps_the_file_and_record() =
        playbackChangesDuringRemoval(afterMove = false, progressChanged = true)

    @Test
    fun changed_progress_after_the_move_restores_the_file_and_record() =
        playbackChangesDuringRemoval(afterMove = true, progressChanged = true)

    private fun playbackChangesDuringRemoval(afterMove: Boolean, progressChanged: Boolean) {
        val file = download(1, automatic = false)
        val original = item(1)!!
        val store = AutoDownloadStore(MemoryAutoDownloadPayloads()) { 1 }
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        var watched = true
        val feature = DeleteAfterWatching(
            store, { DownloadsRepository.uiState.value.items }, { watched }, AutoDownloads::isPlaying, { _, _ -> true },
            DownloadsRepository::removeWatchedAutoDownload, { 0L }, holdMs = 0L,
        )
        feature.onLocalPlaybackCompleted(1, original)
        val interrupt = {
            if (progressChanged) watched = false else {
                AutoDownloads.onLocalFileOpened(file.toUri().toString())
                assertTrue(AutoDownloads.isPlaying(original), "Playback must still be found while the row is absent")
            }
        }
        if (afterMove) afterTrashMove = interrupt else backend.afterSave = interrupt
        try {
            assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())
        } finally {
            AutoDownloads.onLocalFileClosed(file.toUri().toString())
        }
        assertEquals(original, item(1))
        assertEquals("video 1", file.readText())
        assertTrue(heldFiles().isEmpty())
        assertTrue(store.recentlyDeleted.value.isEmpty())
    }

    @Test
    fun a_finished_automatic_download_is_moved_out_of_the_downloads_folder_and_dropped() {
        val file = download(1, automatic = true)

        assertEquals(AutoDownloadRemovalResult.Removed, remove(1))

        assertFalse(file.exists())
        assertTrue(heldFiles().isEmpty())
        assertTrue(systemTrashDir.resolve(file.fileName).exists())
        assertNull(item(1))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        assertNull(item(1))
    }

    @Test
    fun a_download_the_user_started_is_never_removed() {
        val file = download(1, automatic = false)

        assertEquals(AutoDownloadRemovalResult.Changed, remove(1))

        assertTrue(file.exists())
        assertEquals(emptyList(), backend.trashed)
        assertEquals(DownloadStatus.Completed, item(1)?.status)
    }

    @Test
    fun a_download_that_was_replaced_by_the_user_after_the_decision_is_left_alone() {
        val file = download(1, automatic = true)
        val decidedFor = item(1)!!.id
        download(1, automatic = false, file = downloadsDir.resolve("again.mkv"))

        assertEquals(AutoDownloadRemovalResult.Changed, DownloadsRepository.removeWatchedAutoDownload(decidedFor, ruleKey))
        assertEquals(AutoDownloadRemovalResult.Changed, remove(1))

        assertEquals(emptyList(), backend.trashed)
        assertTrue(downloadsDir.resolve("again.mkv").exists())
        assertEquals(1, DownloadsRepository.uiState.value.items.size)
        assertTrue(file.exists() || heldFiles().isEmpty())
    }

    @Test
    fun a_download_that_is_not_finished_is_left_alone() {
        download(1, automatic = true)
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "$showId:1:2", parentMetaId = showId, parentMetaType = "series",
            title = "Show", logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 2,
            episodeTitle = null, episodeThumbnail = null, stream = testStream("https://example.com/2.mp4"),
            autoDownloadRuleKey = ruleKey,
        )

        assertEquals(AutoDownloadRemovalResult.Changed, remove(2))

        assertEquals(DownloadStatus.Downloading, item(2)?.status)
    }

    @Test
    fun a_file_that_another_download_also_uses_stays_and_only_the_entry_is_dropped() {
        val shared = download(1, automatic = true)
        download(2, automatic = false, file = shared)

        assertEquals(AutoDownloadRemovalResult.Removed, remove(1))

        assertTrue(shared.exists())
        assertEquals(emptyList(), backend.trashed)
        assertNull(item(1))
        assertEquals(DownloadStatus.Completed, item(2)?.status)
    }

    @Test
    fun a_shared_file_is_recognised_when_the_other_download_spells_its_address_differently() {
        val shared = download(1, automatic = true)
        Files.createSymbolicLink(folder.resolve("alias"), downloadsDir)
        download(2, automatic = false, file = folder.resolve("alias").resolve(shared.fileName))

        assertEquals(AutoDownloadRemovalResult.Removed, remove(1))

        assertTrue(shared.exists())
        assertEquals(emptyList(), backend.trashed)
        assertEquals(DownloadStatus.Completed, item(2)?.status)
    }

    @Test
    fun nothing_is_touched_when_the_removal_is_no_longer_wanted_at_the_last_moment() {
        val file = download(1, automatic = true)

        val result = DownloadsRepository.removeWatchedAutoDownload(item(1)!!.id, ruleKey, stillWanted = { false })

        assertEquals(AutoDownloadRemovalResult.Changed, result)
        assertTrue(file.exists())
        assertEquals(emptyList(), backend.trashed)
        assertEquals(DownloadStatus.Completed, item(1)?.status)
    }

    @Test
    fun a_file_that_cannot_be_moved_keeps_its_download() {
        val file = download(1, automatic = true)
        backend.trashWorks = false

        assertEquals(AutoDownloadRemovalResult.NotMoved, remove(1))

        assertTrue(file.exists())
        assertEquals(DownloadStatus.Completed, item(1)?.status)
    }

    @Test
    fun a_link_in_the_downloads_folder_that_points_outside_is_not_followed_and_nothing_is_removed() {
        val outside = folder.resolve("outside.mkv").also { it.writeText("not a download") }
        val link = downloadsDir.resolve("episode-1.mkv")
        Files.createSymbolicLink(link, outside)
        download(1, automatic = true, file = link)

        assertEquals(AutoDownloadRemovalResult.NotMoved, remove(1))

        assertTrue(outside.exists())
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(emptyList(), heldFiles())
        assertEquals(DownloadStatus.Completed, item(1)?.status)
    }

    @Test
    fun a_download_whose_file_is_already_gone_is_dropped() {
        val file = download(1, automatic = true)
        Files.delete(file)

        assertEquals(AutoDownloadRemovalResult.Removed, remove(1))

        assertNull(item(1))
    }

    @Test
    fun an_episode_played_to_the_end_is_found_by_its_file_and_removed_a_day_later_while_the_users_own_stays() {
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads) { 1 }
        store.upsert(1, AutoDownloadRule("series", showId, "Show", deleteAfterWatching = true))
        var now = 0L
        val feature = DeleteAfterWatching(
            store = store,
            downloads = { DownloadsRepository.uiState.value.items },
            isWatchedToTheEnd = { true },
            isInPlayer = { false },
            hasLaterEpisode = { _, _ -> true },
            removeDownload = DownloadsRepository::removeWatchedAutoDownload,
            nowEpochMs = { now },
        )
        val automatic = download(1, automatic = true)
        val own = download(2, automatic = false)

        val playedAutomatic = DownloadsRepository.findCompletedDownloadByLocalFile("file://${automatic.toUri().path}")
        val playedOwn = DownloadsRepository.findCompletedDownloadByLocalFile(own.toUri().toString())
        assertEquals(item(1)?.id, playedAutomatic?.id)
        assertTrue(feature.onLocalPlaybackCompleted(1, playedAutomatic!!))
        assertFalse(feature.onLocalPlaybackCompleted(1, playedOwn!!))
        now = DeleteAfterWatching.DefaultHoldMs
        feature.runDue(1)

        assertFalse(automatic.exists())
        assertNull(item(1))
        assertTrue(own.exists())
        assertEquals(DownloadStatus.Completed, item(2)?.status)
    }
}
