package com.nuvio.app.features.downloads

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_auto_recent_heading
import nuvio.composeapp.generated.resources.downloads_cleanup_clear_selection
import nuvio.composeapp.generated.resources.downloads_cleanup_empty_30_days
import nuvio.composeapp.generated.resources.downloads_cleanup_filter_30_days
import nuvio.composeapp.generated.resources.downloads_cleanup_filter_all
import nuvio.composeapp.generated.resources.downloads_cleanup_total_selected
import nuvio.composeapp.generated.resources.downloads_cleanup_watched_title
import nuvio.composeapp.generated.resources.downloads_delete_confirm_trash
import nuvio.composeapp.generated.resources.downloads_recent_cleaned_up_today
import nuvio.composeapp.generated.resources.downloads_select_description
import nuvio.composeapp.generated.resources.downloads_undo
import nuvio.composeapp.generated.resources.downloads_undo_deleted_description
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchedCleanupModelAndUndoTest {
    private val dayMs = 24L * 60 * 60 * 1_000L
    private val now = 1_800_000_000_000L

    private lateinit var root: Path
    private lateinit var downloadsDir: Path
    private lateinit var trashDir: Path
    private lateinit var trash: DesktopDownloadsTrash

    private val startedIds = mutableListOf<String>()
    private var totalLimitBytes: Long? = null

    private inner class TrashBackedBackend : DownloadsBackend {
        var payload: String? = null
        val disconnectedLocations = mutableSetOf<String>()

        override fun loadPayload(): String? = payload

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun isConnected(item: DownloadItem): Boolean = item.locationId !in disconnectedLocations

        override fun locationConnected(locationId: String?): Boolean = locationId !in disconnectedLocations

        override val usesSystemTrash: Boolean get() = trash.usesSystemTrash

        override fun trashFile(localFileUri: String): Boolean = trash.moveAway(localFileUri)

        override fun trashWatchedFile(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
            trash.moveWatchedAway(localFileUri, stillWanted)

        override fun trashWatchedFileWithUndo(
            localFileUri: String,
            allowHoldingWhenNoSystemTrash: Boolean,
            stillWanted: () -> Boolean,
        ): WatchedTrashOutcome = trash.moveWatchedAwayWithUndo(localFileUri, allowHoldingWhenNoSystemTrash, stillWanted)

        override fun restoreTrashedFile(trashed: TrashedWatchedFile): UndoTrashResult =
            trash.restoreFromTrash(trashed)

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            startedIds += request.item.id
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean = true

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private val backend = TrashBackedBackend()
    private val realBackend = DownloadsRepository.backend
    private val realLimits = DownloadsRepository.autoDownloadLimits
    private val realOnTrip = DownloadsRepository.onProfileReadyForTrip
    private val realOnBlocked = DownloadsRepository.onStorageLimitBlocked

    @BeforeTest
    fun set_up() {
        root = Files.createTempDirectory("nuvio-watched-cleanup-test").toRealPath()
        downloadsDir = root.resolve("downloads").createDirectories()
        trashDir = root.resolve("trash").createDirectories()
        var counter = 0
        trash = DesktopDownloadsTrash(
            downloadsDir = { downloadsDir },
            systemTrash = null,
            nowEpochMs = { now },
            systemTrashWithResult = { source ->
                counter++
                val dest = trashDir.resolve("$counter-${source.fileName}")
                Files.move(source, dest)
                SystemTrashOutcome.Moved(dest)
            },
        )
        startedIds.clear()
        totalLimitBytes = null

        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.autoDownloadLimits = object : AutoDownloadLimits {
            override fun storageLimitBytes(): Long? = null
            override fun totalStorageLimitBytes(): Long? = totalLimitBytes
            override val minFreeBytes: Long = 0L
        }
        DownloadsRepository.onProfileReadyForTrip = {}
        DownloadsRepository.onStorageLimitBlocked = {}
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun tear_down() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.autoDownloadLimits = realLimits
        DownloadsRepository.onProfileReadyForTrip = realOnTrip
        DownloadsRepository.onStorageLimitBlocked = realOnBlocked
        root.toFile().deleteRecursively()
    }

    private fun movie(
        id: String,
        title: String,
        bytes: Long,
        status: DownloadStatus = DownloadStatus.Completed,
        uri: String? = "file:/downloads/$id.mkv",
        placeholder: Boolean = false,
        locationId: String? = null,
    ) = DownloadItem(
        id = id,
        contentType = "movie",
        parentMetaId = id,
        parentMetaType = "movie",
        videoId = id,
        title = title,
        streamTitle = "1080p",
        providerName = "A",
        sourceUrl = "https://example.com/$id.mkv",
        localFileUri = uri,
        fileName = "$id.mkv",
        locationId = locationId,
        status = status,
        downloadedBytes = bytes,
        totalBytes = bytes,
        framesCheck = if (placeholder) DownloadFramesCheck.NotTheRealVideo else DownloadFramesCheck.Passed,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    @Test
    fun cleanable_list_computes_sizes_totals_30_day_filter_and_excludes_protected_items() {
        val oldWatched = movie("m-old", "Old Film", 2_500_000_000L)
        val recentWatched = movie("m-recent", "Recent Film", 1_500_000_000L)
        val unwatched = movie("m-unwatched", "Unwatched Film", 3_000_000_000L)
        val rewatching = movie("m-rewatch", "Rewatching Film", 1_800_000_000L)
        val kept = movie("m-kept", "Kept Film", 1_200_000_000L)
        val playing = movie("m-playing", "Playing Film", 1_100_000_000L)
        val downloading = movie("m-dl", "Downloading Film", 900_000_000L, status = DownloadStatus.Downloading, uri = null)
        val placeholder = movie("m-bad", "Placeholder Film", 50_000_000L, placeholder = true)
        val moving = movie("m-moving", "Moving Film", 800_000_000L)
        val away = movie("m-away", "Away Film", 4_000_000_000L, locationId = "away")

        val progressState = WatchProgressUiState(
            entries = listOf(
                WatchProgressEntry(
                    contentType = "movie",
                    parentMetaId = "m-rewatch",
                    parentMetaType = "movie",
                    videoId = "m-rewatch",
                    title = "Rewatching Film",
                    lastPositionMs = 20 * 60_000L,
                    durationMs = 100 * 60_000L,
                    lastUpdatedEpochMs = now - dayMs,
                    isCompleted = false,
                ),
            ),
        )
        val historyTimestamps = mapOf(
            "m-old" to now - 45 * dayMs,
            "m-recent" to now - 5 * dayMs,
            "m-rewatch" to now - 60 * dayMs,
            "m-kept" to now - 50 * dayMs,
            "m-playing" to now - 50 * dayMs,
            "m-bad" to now - 50 * dayMs,
            "m-moving" to now - 50 * dayMs,
            "m-away" to now - 50 * dayMs,
        )

        val candidates = cleanableWatchedDownloads(
            items = listOf(recentWatched, oldWatched, unwatched, rewatching, kept, playing, downloading, placeholder, moving, away),
            watchStatusOf = { item ->
                item.watchStatus(
                    progressState = progressState,
                    isHistoryWatched = { id, _, _, _ -> id in historyTimestamps },
                    historyWatchedAt = { id, _, _, _ -> historyTimestamps[id] },
                )
            },
            keptDownloadIds = setOf("m-kept"),
            movingIds = setOf("m-moving"),
            isReachable = { it.locationId != "away" },
            isPlaying = { it.id == "m-playing" },
        )

        // Sorted oldest-watched first: m-old (45d ago), then m-recent (5d ago).
        assertEquals(listOf("m-old", "m-recent"), candidates.map { it.item.id })
        assertEquals(4_000_000_000L, selectedCleanupTotalBytes(candidates, setOf("m-old", "m-recent")))
        assertEquals(2_500_000_000L, selectedCleanupTotalBytes(candidates, setOf("m-old")))

        val older30 = candidates.matchingFilter(WatchedCleanupFilter.OlderThan30Days, now)
        assertEquals(listOf("m-old"), older30.map { it.item.id })
        assertEquals(2_500_000_000L, selectedCleanupTotalBytes(older30, setOf("m-old", "m-recent")))
    }

    @Test
    fun cleanup_moves_chosen_files_to_trash_resumes_waiting_download_and_undo_restores_file_and_record() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }
        val rule = testRule(showId = "tt-show")
        store.upsert(profile, rule)

        val ep1File = downloadsDir.resolve("ep1.mkv").also { it.writeText("ep1-video-bytes") }
        val ep1Subs = downloadsDir.resolve("ep1.mkv.subtitles").createDirectories()
            .also { it.resolve("en.srt").writeText("ep1-subtitles") }
        val ep2File = downloadsDir.resolve("ep2.mkv").also { it.writeText("ep2-video-bytes") }

        val ep1 = testDownload(1, DownloadStatus.Completed, showId = "tt-show", ruleKey = rule.key, localFileUri = ep1File.toUri().toString(), id = "ep-1")
            .copy(fileName = "ep1.mkv", downloadedBytes = 600L, totalBytes = 600L)
        val ep2 = testDownload(2, DownloadStatus.Completed, showId = "tt-show", ruleKey = rule.key, localFileUri = ep2File.toUri().toString(), id = "ep-2")
            .copy(fileName = "ep2.mkv", downloadedBytes = 500L, totalBytes = 500L)

        totalLimitBytes = 1_200L
        backend.payload = DownloadsCodec.encode(listOf(ep1, ep2), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        // Enqueue a hand-started download of 400 bytes -> 600 + 500 + 400 = 1,500 > 1,200, so it waits.
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-wait",
            parentMetaId = "tt-wait",
            parentMetaType = "movie",
            title = "Waiting Movie",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "1080p",
                url = "https://example.com/wait.mkv",
                addonName = "A",
                addonId = "addon:A",
                behaviorHints = StreamBehaviorHints(videoSize = 400L),
            ),
        )
        val waitingBefore = DownloadsRepository.uiState.value.items.first { it.parentMetaId == "tt-wait" }
        assertEquals(DownloadStatus.Paused, waitingBefore.status)
        assertEquals(AutoDownloadStop.TotalStorageLimit, waitingBefore.autoDownloadStop)
        assertTrue(startedIds.isEmpty())

        // Clean up only ep-1 (leaving ep-2 untouched).
        val moved = executeWatchedCleanup(
            profileId = profile,
            downloadIds = listOf("ep-1"),
            store = store,
            isWatched = { it.id == "ep-1" || it.id == "ep-2" },
            isPlaying = { false },
            nowEpochMs = { now },
        )

        assertEquals(1, moved)
        assertFalse(ep1File.exists(), "cleaned up file must leave downloads folder")
        assertFalse(ep1Subs.exists(), "cleaned up subtitles must leave downloads folder")
        assertTrue(ep2File.exists(), "unchosen watched download must remain untouched")
        assertTrue(autoDownloadEpisodeKey(1, 1) in store.rule(profile, rule.key)!!.ownedEpisodeKeys)

        // Room appeared (500 + 400 <= 1,200): the waiting download started automatically!
        val waitingAfter = DownloadsRepository.uiState.value.items.first { it.parentMetaId == "tt-wait" }
        assertEquals(DownloadStatus.Downloading, waitingAfter.status)
        assertEquals(listOf(waitingAfter.id), startedIds)

        // Recently deleted lists ep-1 with Undo.
        val deletedEntry = store.recentlyDeleted.value.single()
        assertEquals(DeletedDownloadReason.CleanedUp, deletedEntry.reason)
        assertTrue(deletedEntry.canUndo)

        // Undo restores the video file, subtitles, and download record, and marks it Keep.
        val undoResult = undoDeletedWatchedDownload(profile, deletedEntry, store)
        assertEquals(UndoTrashResult.Restored, undoResult)
        assertEquals("ep1-video-bytes", ep1File.readText())
        assertEquals("ep1-subtitles", ep1Subs.resolve("en.srt").readText())
        val restoredRecord = assertNotNull(DownloadsRepository.uiState.value.items.firstOrNull { it.id == "ep-1" })
        assertEquals(DownloadStatus.Completed, restoredRecord.status)
        assertTrue("ep-1" in store.keptDownloadIds.value, "restored download must be marked Keep")
        assertTrue(store.recentlyDeleted.value.isEmpty(), "restored entry must be removed from recently deleted")
    }

    @Test
    fun undo_reports_file_gone_and_clears_button_when_trash_was_emptied() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }

        val filmFile = downloadsDir.resolve("film.mkv").also { it.writeText("film-bytes") }
        val film = movie("m-1", "Film", 500L, uri = filmFile.toUri().toString()).copy(fileName = "film.mkv")
        backend.payload = DownloadsCodec.encode(listOf(film), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        assertEquals(
            1,
            executeWatchedCleanup(
                profileId = profile,
                downloadIds = listOf("m-1"),
                store = store,
                isWatched = { true },
                isPlaying = { false },
                nowEpochMs = { now },
            ),
        )
        val entry = store.recentlyDeleted.value.single()
        assertTrue(entry.canUndo)

        // Simulate the user emptying the system Trash in our isolated test directory.
        trashDir.toFile().listFiles()?.forEach { it.deleteRecursively() }

        val undoResult = undoDeletedWatchedDownload(profile, entry, store)
        assertEquals(UndoTrashResult.FileGone, undoResult)
        assertFalse(store.recentlyDeleted.value.single().canUndo)
        assertNull(DownloadsRepository.uiState.value.items.firstOrNull { it.id == "m-1" })
    }
}

@OptIn(ExperimentalTestApi::class)
class WatchedCleanupUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    @Test
    fun dialog_shows_watched_downloads_sizes_30_day_filter_and_confirms_chosen_subset() {
        val dayMs = 24L * 60 * 60 * 1_000L
        val now = 1_800_000_000_000L
        val itemA = testDownload(1, DownloadStatus.Completed, id = "ep-1").copy(
            title = "Andor",
            downloadedBytes = 2_000_000_000L,
            totalBytes = 2_000_000_000L,
        )
        val itemB = testDownload(2, DownloadStatus.Completed, id = "ep-2").copy(
            title = "Andor",
            downloadedBytes = 1_500_000_000L,
            totalBytes = 1_500_000_000L,
        )
        val candidates = listOf(
            CleanableWatchedDownload(itemA, sizeBytes = 2_000_000_000L, watchedAtEpochMs = now - 40 * dayMs),
            CleanableWatchedDownload(itemB, sizeBytes = 1_500_000_000L, watchedAtEpochMs = now - 5 * dayMs),
        )
        var confirmed: Set<String>? = null

        compose.setContent {
            NuvioTheme {
                WatchedCleanupDialog(
                    candidates = candidates,
                    nowEpochMs = now,
                    usesSystemTrash = true,
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText(text(Res.string.downloads_cleanup_watched_title)).assertIsDisplayed()
        compose.onNodeWithText("Andor S01E01").assertIsDisplayed()
        compose.onNodeWithText("2.0 GB").assertIsDisplayed()
        compose.onNodeWithText("Andor S01E02").assertIsDisplayed()
        compose.onNodeWithText("1.5 GB").assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_cleanup_total_selected, 2, "3.5 GB")).assertIsDisplayed()

        // Filter to Watched 30+ days ago -> only S01E01 (2.0 GB) remains.
        compose.onNodeWithText(text(Res.string.downloads_cleanup_filter_30_days)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_cleanup_total_selected, 1, "2.0 GB")).assertIsDisplayed()
        compose.onNodeWithText("Andor S01E02").assertDoesNotExist()

        // Switch back to All watched, uncheck S01E01, and confirm S01E02 only.
        compose.onNodeWithText(text(Res.string.downloads_cleanup_filter_all)).performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E01")).performClick()
        compose.onNodeWithText(text(Res.string.downloads_cleanup_total_selected, 1, "1.5 GB")).assertIsDisplayed()

        // Clearing selection disables Move to Trash until something is selected.
        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E02")).performClick()
        compose.onNode(hasText(text(Res.string.downloads_delete_confirm_trash)) and hasAnyAncestor(isDialog())).assertIsNotEnabled()

        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E02")).performClick()
        compose.onNode(hasText(text(Res.string.downloads_delete_confirm_trash)) and hasAnyAncestor(isDialog()))
            .assertIsEnabled()
            .performClick()

        assertEquals(setOf("ep-2"), confirmed)
    }

    @Test
    fun empty_30_day_filter_explains_none_match_and_recently_deleted_offers_undo() {
        val now = 1_800_000_000_000L
        val restoredItem = testDownload(3, DownloadStatus.Completed, id = "ep-3").copy(title = "Severance")
        val deleted = AutoDownloadDeleted(
            title = "Severance",
            seasonNumber = 1,
            episodeNumber = 3,
            atEpochMs = now,
            downloadId = "ep-3",
            reason = DeletedDownloadReason.CleanedUp,
            restorableItem = restoredItem,
            trashed = TrashedWatchedFile(
                originalFileUri = "file:/downloads/s1e3.mkv",
                trashedFileUri = "file:/trash/s1e3.mkv",
            ),
        )
        val undone = mutableListOf<Pair<Int, AutoDownloadDeleted>>()
        val controller = object : AutoDownloadController {
            override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
            override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
            override val storageLimitGb = MutableStateFlow<Int?>(null)
            override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
            override val recentlyDeleted = MutableStateFlow(listOf(deleted))
            override val usesSystemTrash = true
            override fun undoDeletedDownload(profileId: Int, entry: AutoDownloadDeleted) {
                undone += profileId to entry
            }
            override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean) = Unit
            override fun turnOff(profileId: Int, ruleKey: String) = Unit
            override fun setStorageLimit(profileId: Int, limitGb: Int?) = Unit
            override fun keepDownload(profileId: Int, downloadId: String) = Unit
            override fun deleteDownloadNow(profileId: Int, downloadId: String) = Unit
            override fun checkNow() = Unit
        }

        compose.setContent {
            NuvioTheme {
                AutoDownloadSection(
                    controller = controller,
                    profileId = 9,
                    downloads = MutableStateFlow(DownloadsUiState()),
                    nowEpochMs = { now },
                )
            }
        }

        compose.onNodeWithText(text(Res.string.downloads_auto_recent_heading)).assertIsDisplayed()
        compose.onNodeWithText("Severance S1 E3 • " + text(Res.string.downloads_recent_cleaned_up_today)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_undo_deleted_description, "Severance S01E03"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .performClick()

        assertEquals(listOf(9 to deleted), undone)
    }
}
