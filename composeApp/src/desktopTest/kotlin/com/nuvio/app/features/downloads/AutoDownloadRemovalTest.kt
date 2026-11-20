package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.Path
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
        override var usesSystemTrash = true
        val trashed = mutableListOf<String>()

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

    @BeforeTest
    fun use_an_empty_in_memory_repository_and_a_temporary_downloads_folder() {
        folder = Files.createTempDirectory("nuvio-removal-test").toRealPath()
        downloadsDir = folder.resolve("downloads").createDirectories()
        trash = DesktopDownloadsTrash(downloadsDir = { downloadsDir }, systemTrash = null)
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
        assertEquals(1, heldFiles().size)
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
        backend.usesSystemTrash = false
        assertEquals(AutoDownloadRemovalResult.NotMoved, DownloadsRepository.removeWatchedAutoDownload(item(1)!!.id, null))
        assertTrue(file.exists())
        assertTrue(backend.trashed.isEmpty())
    }

    @Test
    fun a_finished_automatic_download_is_moved_out_of_the_downloads_folder_and_dropped() {
        val file = download(1, automatic = true)

        assertEquals(AutoDownloadRemovalResult.Removed, remove(1))

        assertFalse(file.exists())
        assertEquals(1, heldFiles().size)
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
