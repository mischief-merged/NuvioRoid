package com.nuvio.app.features.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_auto_recent_heading
import nuvio.composeapp.generated.resources.downloads_cleanup_clear_selection
import nuvio.composeapp.generated.resources.downloads_cleanup_confirm_archive
import nuvio.composeapp.generated.resources.downloads_cleanup_empty_30_days
import nuvio.composeapp.generated.resources.downloads_cleanup_empty_all
import nuvio.composeapp.generated.resources.downloads_cleanup_filter_30_days
import nuvio.composeapp.generated.resources.downloads_cleanup_filter_all
import nuvio.composeapp.generated.resources.downloads_cleanup_total_selected
import nuvio.composeapp.generated.resources.downloads_cleanup_watched_button
import nuvio.composeapp.generated.resources.downloads_cleanup_watched_title
import nuvio.composeapp.generated.resources.downloads_delete_confirm_trash
import nuvio.composeapp.generated.resources.downloads_delete_message_held
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
import kotlin.test.assertNotEquals
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
        val payloadsByProfile = mutableMapOf<Int, String?>()
        var payload: String?
            get() = payloadsByProfile[ProfileRepository.activeProfileId]
            set(value) {
                payloadsByProfile[ProfileRepository.activeProfileId] = value
            }
        val disconnectedLocations = mutableSetOf<String>()
        val ownedFileUris = mutableSetOf<String>()
        var useProductionDownloadsTrash = false
        var failSave = false
        var failDurableSync = false
        var onAfterTrashMove: (() -> Unit)? = null
        var onAfterRestoreMove: (() -> Unit)? = null

        override fun ownershipOf(item: DownloadItem, fileUri: String): FileOwnership? =
            if (fileUri in ownedFileUris) FileOwnership.Owned else null

        override fun loadPayload(): String? = payload

        override fun loadPayload(profileId: Int): String? = payloadsByProfile[profileId]

        override fun savePayload(payload: String) {
            if (failSave) throw java.io.IOException("Simulated save failure")
            this.payload = payload
        }

        override fun savePayload(profileId: Int, payload: String) {
            if (failSave) throw java.io.IOException("Simulated save failure")
            payloadsByProfile[profileId] = payload
        }

        override fun syncPayload(): Boolean = !failDurableSync

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun isConnected(item: DownloadItem): Boolean = item.locationId !in disconnectedLocations

        override fun locationConnected(locationId: String?): Boolean = locationId !in disconnectedLocations

        override val usesSystemTrash: Boolean
            get() = if (useProductionDownloadsTrash) DownloadsTrash.usesSystemTrash else trash.usesSystemTrash

        override val usesUndoableSystemTrash: Boolean
            get() = if (useProductionDownloadsTrash) DownloadsTrash.usesUndoableSystemTrash else trash.usesUndoableSystemTrash

        override fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean =
            if (useProductionDownloadsTrash) DownloadsTrash.isSameFile(firstFileUri, secondFileUri) else trash.isSameFile(firstFileUri, secondFileUri)

        override fun trashFile(localFileUri: String): Boolean =
            if (useProductionDownloadsTrash) DownloadsTrash.moveAway(localFileUri) else trash.moveAway(localFileUri)

        override fun trashWatchedFile(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
            if (useProductionDownloadsTrash) DownloadsTrash.moveWatchedAway(localFileUri, stillWanted) else trash.moveWatchedAway(localFileUri, stillWanted)

        override fun trashWatchedFileWithUndo(
            localFileUri: String,
            allowHoldingWhenNoSystemTrash: Boolean,
            stillWanted: () -> Boolean,
        ): WatchedTrashOutcome = if (useProductionDownloadsTrash) {
            DownloadsTrash.moveWatchedAwayWithUndo(
                localFileUri = localFileUri,
                allowHoldingWhenNoSystemTrash = allowHoldingWhenNoSystemTrash,
                stillWanted = stillWanted,
            )
        } else {
            trash.moveWatchedAwayWithUndo(
                localFileUri = localFileUri,
                allowHoldingWhenNoSystemTrash = allowHoldingWhenNoSystemTrash,
                stillWanted = stillWanted,
            )
        }

        override fun trashWatchedFileWithUndo(
            localFileUri: String,
            allowHoldingWhenNoSystemTrash: Boolean,
            stillWanted: () -> Boolean,
            onMoved: (TrashedWatchedFile?) -> Boolean,
        ): WatchedTrashOutcome = trashWatchedFileWithUndo(
            localFileUri = localFileUri,
            allowHoldingWhenNoSystemTrash = allowHoldingWhenNoSystemTrash,
            includeSubtitles = true,
            stillWanted = stillWanted,
            onMoved = onMoved,
        )

        override fun trashWatchedFileWithUndo(
            localFileUri: String,
            allowHoldingWhenNoSystemTrash: Boolean,
            includeSubtitles: Boolean,
            stillWanted: () -> Boolean,
            onMoved: (TrashedWatchedFile?) -> Boolean,
        ): WatchedTrashOutcome = if (useProductionDownloadsTrash) {
            DownloadsTrash.moveWatchedAwayWithUndo(
                localFileUri = localFileUri,
                allowHoldingWhenNoSystemTrash = allowHoldingWhenNoSystemTrash,
                includeSubtitles = includeSubtitles,
                stillWanted = stillWanted,
                onMoved = { trashed ->
                    onAfterTrashMove?.invoke()
                    onMoved(trashed)
                },
            )
        } else {
            trash.moveWatchedAwayWithUndo(
                localFileUri = localFileUri,
                allowHoldingWhenNoSystemTrash = allowHoldingWhenNoSystemTrash,
                includeSubtitles = includeSubtitles,
                stillWanted = stillWanted,
                onMoved = { trashed ->
                    onAfterTrashMove?.invoke()
                    onMoved(trashed)
                },
            )
        }

        override fun restoreTrashedFile(trashed: TrashedWatchedFile): UndoTrashResult =
            restoreTrashedFileWithOutcome(trashed).result

        override fun restoreTrashedFileWithOutcome(trashed: TrashedWatchedFile): UndoRestoreOutcome {
            val outcome = if (useProductionDownloadsTrash) {
                DownloadsTrash.restoreFromTrashWithOutcome(trashed)
            } else {
                trash.restoreFromTrashWithOutcome(trashed)
            }
            if (outcome.result == UndoTrashResult.Restored ||
                outcome.result == UndoTrashResult.PartiallyRestored
            ) {
                onAfterRestoreMove?.invoke()
            }
            return outcome
        }

        override fun trashRestoredSubtitlesWithUndo(
            trashed: TrashedWatchedFile,
            onMoved: (TrashedWatchedFile) -> Boolean,
        ): WatchedTrashOutcome = if (useProductionDownloadsTrash) {
            DownloadsTrash.trashSubtitlesForUndoRollback(trashed) { updated ->
                onAfterTrashMove?.invoke()
                onMoved(updated)
            }
        } else {
            trash.trashSubtitlesForUndoRollback(trashed) { updated ->
                onAfterTrashMove?.invoke()
                onMoved(updated)
            }
        }

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

        override fun locate(item: DownloadItem): FileLookup {
            val uri = item.localFileUri ?: return FileLookup.Missing
            val path = runCatching { Path.of(java.net.URI(uri)) }.getOrNull() ?: return FileLookup.Missing
            return if (path.exists()) FileLookup.Found(uri, item.locationId) else FileLookup.Missing
        }
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
        backend.payloadsByProfile.clear()
        backend.disconnectedLocations.clear()
        backend.ownedFileUris.clear()
        backend.useProductionDownloadsTrash = false
        backend.failSave = false
        backend.failDurableSync = false
        backend.onAfterTrashMove = null
        backend.onAfterRestoreMove = null

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

    @Test
    fun active_provider_without_stored_map_does_not_fall_back_to_local_watched_timestamps() {
        val profile = ProfileRepository.activeProfileId
        val watchedStore = DesktopStorage.store("nuvio_watched")
        val previousPayload = WatchedStorage.loadPayload(profile)
        try {
            WatchedRepository.clearLocalState()
            watchedStore.remove("watched_$profile")
            WatchedRepository.activateSource(WatchProgressSource.NUVIO_SYNC)
            WatchedRepository.markWatchedLocally(
                listOf(WatchedItem(id = "m-local", type = "movie", name = "Local Film", markedAtEpochMs = now - 20 * dayMs)),
            )

            val filmFile = downloadsDir.resolve("m-local.mkv").also { it.writeText("local-bytes") }
            val film = movie("m-local", "Local Film", 900L, uri = filmFile.toUri().toString())
                .copy(fileName = "m-local.mkv")

            assertTrue(WatchedRepository.isWatched("m-local", "movie"))
            assertNotNull(WatchedRepository.watchedAtEpochMs("m-local", "movie"))
            assertTrue(film.watchStatus(WatchProgressUiState()).isWatched)

            // Switch to Trakt when Trakt has no stored map in providerItems: local nuvioItems must NOT bleed in.
            WatchedRepository.activateSource(WatchProgressSource.TRAKT)
            assertFalse(WatchedRepository.isWatched("m-local", "movie"))
            assertNull(WatchedRepository.watchedAtEpochMs("m-local", "movie"))
            val statusUnderProvider = film.watchStatus(WatchProgressUiState())
            assertFalse(statusUnderProvider.isWatched)
            assertNull(statusUnderProvider.watchedAtEpochMs)

            // Also verify DownloadWatchStatus directly when historyWatchedAt would return a timestamp while isHistoryWatched is false.
            val mismatchedStatus = film.watchStatus(
                progressState = WatchProgressUiState(),
                isHistoryWatched = { _, _, _, _ -> false },
                historyWatchedAt = { _, _, _, _ -> now - 10 * dayMs },
            )
            assertFalse(mismatchedStatus.isWatched)
            assertNull(mismatchedStatus.watchedAtEpochMs)

            assertTrue(
                cleanableWatchedDownloads(
                    items = listOf(film),
                    watchStatusOf = { it.watchStatus(WatchProgressUiState()) },
                    keptDownloadIds = emptySet(),
                    movingIds = emptySet(),
                    isReachable = { true },
                    isPlaying = { false },
                ).isEmpty(),
            )

            val store = AutoDownloadStore(MemoryAutoDownloadPayloads(), nowEpochMs = { now }) { profile }
            assertTrue(store.setMakeRoomAutomatically(profile, true))
            totalLimitBytes = 500L
            backend.payload = DownloadsCodec.encode(listOf(film), null, 1L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            val makeRoom = MakeRoomAutomatically(
                store = store,
                downloads = { DownloadsRepository.uiState.value.items },
                allDownloadBytes = DownloadsRepository::allDownloadBytes,
                autoDownloadBytes = DownloadsRepository::autoDownloadBytes,
                totalStorageLimitBytes = { totalLimitBytes },
                autoStorageLimitBytes = { null },
                watchStatusOf = { it.watchStatus(WatchProgressUiState()) },
                isPlaying = { false },
                nowEpochMs = { now },
            )
            assertEquals(0, makeRoom.runOnce(profile))
            assertTrue(filmFile.exists(), "title unwatched in active provider must never be trashed")
        } finally {
            WatchedRepository.clearLocalState()
            WatchedRepository.activateSource(WatchProgressSource.NUVIO_SYNC)
            if (previousPayload == null) {
                watchedStore.remove("watched_$profile")
            } else {
                WatchedStorage.savePayload(profile, previousPayload)
            }
        }
    }

    @Test
    fun undo_rolls_file_back_to_trash_on_save_failure_or_profile_switch_and_succeeds_on_retry() {
        val originalProfile = ProfileRepository.activeProfileId
        val otherProfile = originalProfile + 17
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { ProfileRepository.activeProfileId }

        try {
            val filmFile = downloadsDir.resolve("film.mkv").also { it.writeText("transactional-bytes") }
            val subsDir = downloadsDir.resolve("film.mkv.subtitles").createDirectories()
                .also { it.resolve("en.srt").writeText("transactional-subs") }
            val film = movie("m-tx", "Transactional Film", 700L, uri = filmFile.toUri().toString())
                .copy(fileName = "film.mkv")
            backend.payload = DownloadsCodec.encode(listOf(film), null, 1L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            assertEquals(
                1,
                executeWatchedCleanup(
                    profileId = originalProfile,
                    downloadIds = listOf("m-tx"),
                    store = store,
                    isWatched = { true },
                    isPlaying = { false },
                    nowEpochMs = { now },
                ),
            )
            assertFalse(filmFile.exists())

            // 1a. savePayload throws IOException: Undo returns NotRestored, puts file back in Trash, and keeps Undo available.
            backend.failSave = true
            val firstEntry = store.recentlyDeleted.value.single()
            assertEquals(UndoTrashResult.NotRestored, undoDeletedWatchedDownload(originalProfile, firstEntry, store))
            assertFalse(filmFile.exists(), "failed save must roll the restored file back to Trash")
            assertFalse(subsDir.exists(), "failed save must roll restored subtitles back to Trash")
            assertTrue(DownloadsRepository.uiState.value.items.none { it.id == "m-tx" })
            backend.failSave = false

            // 1b. syncPayload returns false: Undo returns NotRestored, rolls back stored payload + file, and keeps Undo available.
            backend.failDurableSync = true
            val afterWriteFailure = store.recentlyDeleted.value.single()
            assertEquals(UndoTrashResult.NotRestored, undoDeletedWatchedDownload(originalProfile, afterWriteFailure, store))
            assertFalse(filmFile.exists(), "failed durable sync must roll the restored file back to Trash")
            assertFalse(subsDir.exists(), "failed durable sync must roll restored subtitles back to Trash")
            assertTrue(DownloadsRepository.uiState.value.items.none { it.id == "m-tx" })
            val afterSaveFailure = store.recentlyDeleted.value.single()
            assertTrue(afterSaveFailure.canUndo)

            // 2. Profile switches right after physical move out of Trash: Undo rolls file back to Trash and updates profile's Undo entry.
            backend.failDurableSync = false
            backend.onAfterRestoreMove = {
                backend.onAfterRestoreMove = null
                ProfileRepository.setActiveProfileIdForTest(otherProfile)
                DownloadsRepository.onProfileChanged()
            }
            assertEquals(UndoTrashResult.NotRestored, undoDeletedWatchedDownload(originalProfile, afterSaveFailure, store))
            assertFalse(filmFile.exists(), "mid-restore profile switch must roll the restored file back to Trash")
            assertTrue(DownloadsRepository.uiState.value.items.none { it.id == "m-tx" })

            // 3. Switch back to originalProfile and press Undo again -> succeeds cleanly without TargetTaken!
            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
            store.currentRules()
            val afterProfileRollback = store.recentlyDeleted.value.single()
            assertTrue(afterProfileRollback.canUndo)
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(originalProfile, afterProfileRollback, store))
            assertEquals("transactional-bytes", filmFile.readText())
            assertEquals("transactional-subs", subsDir.resolve("en.srt").readText())
            assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-tx" && it.status == DownloadStatus.Completed })

            // Verify the restored entry was durably saved to the catalog payload as well.
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-tx" && it.status == DownloadStatus.Completed })
        } finally {
            backend.onAfterRestoreMove = null
            backend.failSave = false
            backend.failDurableSync = false
            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
        }
    }

    @Test
    fun bulk_cleanup_exceeding_twenty_items_preserves_every_undo_entry_including_the_first() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }

        // Seed 25 plain history entries first; non-undoable history still caps at 20.
        (1..25).forEach { ep ->
            assertTrue(store.recordDeleted(profile, AutoDownloadDeleted("History Show", 1, ep, atEpochMs = now - 2 * dayMs + ep)))
        }
        assertEquals(20, store.recentlyDeleted.value.count { !it.canUndo })

        // Clean up 25 watched downloads in a single batch (> MaxRecentlyDeleted = 20).
        val batchCount = 25
        val films = (1..batchCount).map { idx ->
            val path = downloadsDir.resolve("batch-$idx.mkv").also { it.writeText("video-bytes-$idx") }
            movie("batch-$idx", "Batch Film $idx", 400L + idx, uri = path.toUri().toString())
                .copy(fileName = "batch-$idx.mkv")
        }
        backend.payload = DownloadsCodec.encode(films, null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        val moved = executeWatchedCleanup(
            profileId = profile,
            downloadIds = films.map { it.id },
            store = store,
            isWatched = { true },
            isPlaying = { false },
            nowEpochMs = { now },
        )
        assertEquals(batchCount, moved)

        // Add more non-undoable history entries over a week later; pending Undo entries must still stay intact.
        val laterNow = now + 8 * dayMs
        (26..30).forEach { ep ->
            assertTrue(store.recordDeleted(profile, AutoDownloadDeleted("Later History", 1, ep, atEpochMs = laterNow + ep)))
        }

        // Reload from serialized payload to verify StoredAutoDownloads.normalized() preserves all 25 Undo entries.
        val reloaded = AutoDownloadStore(payloads, nowEpochMs = { laterNow }) { profile }
        reloaded.currentRules()
        val storedEntries = reloaded.recentlyDeleted.value
        val undoEntries = storedEntries.filter { it.canUndo }
        assertEquals(
            (1..batchCount).map { "batch-$it" },
            undoEntries.map { it.downloadId },
            "every pending Undo record in a >20 batch must survive normalization and later history writes",
        )
        assertTrue(storedEntries.count { !it.canUndo } <= AutoDownloadStore.MaxRecentlyDeleted)

        // All 25 pending Undo entries remain visible to the user in recentlyDeletedShown (including the first one).
        val shown = recentlyDeletedShown(storedEntries, nowEpochMs = laterNow + 100L)
        assertTrue((1..batchCount).all { idx -> shown.any { it.downloadId == "batch-$idx" && it.canUndo } })

        // Undo the very first download in the batch (batch-1) and verify full file + catalog restoration.
        val firstEntry = undoEntries.first { it.downloadId == "batch-1" }
        assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(profile, firstEntry, reloaded))
        assertEquals("video-bytes-1", downloadsDir.resolve("batch-1.mkv").readText())
        assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "batch-1" && it.status == DownloadStatus.Completed })
        assertTrue("batch-1" in reloaded.keptDownloadIds.value)
        assertEquals((2..batchCount).map { "batch-$it" }, reloaded.recentlyDeleted.value.filter { it.canUndo }.map { it.downloadId })
    }

    @Test
    fun cleanup_preserves_undo_across_profile_switch_and_rolls_back_when_undo_save_fails() {
        val originalProfile = ProfileRepository.activeProfileId
        val otherProfile = originalProfile + 23
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { ProfileRepository.activeProfileId }
        val rule = testRule(showId = "tt-tx-cleanup")
        store.upsert(originalProfile, rule)

        try {
            // 1. Profile switches right after the file moves to Trash: originating profile keeps its Undo entry and owned key.
            val ep1File = downloadsDir.resolve("ep1.mkv").also { it.writeText("ep1-video-bytes") }
            val ep1Subs = downloadsDir.resolve("ep1.mkv.subtitles").createDirectories()
                .also { it.resolve("en.srt").writeText("ep1-subtitles") }
            val ep2File = downloadsDir.resolve("ep2.mkv").also { it.writeText("ep2-video-bytes") }
            val ep2Subs = downloadsDir.resolve("ep2.mkv.subtitles").createDirectories()
                .also { it.resolve("en.srt").writeText("ep2-subtitles") }

            val ep1 = testDownload(1, DownloadStatus.Completed, showId = "tt-tx-cleanup", ruleKey = rule.key, localFileUri = ep1File.toUri().toString(), id = "ep-1")
                .copy(fileName = "ep1.mkv", downloadedBytes = 600L, totalBytes = 600L)
            val ep2 = testDownload(2, DownloadStatus.Completed, showId = "tt-tx-cleanup", ruleKey = rule.key, localFileUri = ep2File.toUri().toString(), id = "ep-2")
                .copy(fileName = "ep2.mkv", downloadedBytes = 600L, totalBytes = 600L)

            backend.payload = DownloadsCodec.encode(listOf(ep1, ep2), null, 1L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            backend.onAfterTrashMove = {
                backend.onAfterTrashMove = null
                ProfileRepository.setActiveProfileIdForTest(otherProfile)
            }

            val movedOnSwitch = executeWatchedCleanup(
                profileId = originalProfile,
                downloadIds = listOf("ep-1"),
                store = store,
                isWatched = { true },
                isPlaying = { false },
                nowEpochMs = { now },
            )
            assertEquals(1, movedOnSwitch)
            assertFalse(ep1File.exists())
            assertFalse(ep1Subs.exists())

            // Other profile's state remains empty; switching back reveals originalProfile's Undo entry and owned key.
            assertTrue(store.currentRules().isEmpty())
            assertTrue(store.recentlyDeleted.value.isEmpty())

            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
            store.currentRules()

            assertTrue(autoDownloadEpisodeKey(1, 1) in store.rule(originalProfile, rule.key)!!.ownedEpisodeKeys)
            val switchedEntry = store.recentlyDeleted.value.single { it.downloadId == "ep-1" }
            assertEquals(DeletedDownloadReason.CleanedUp, switchedEntry.reason)
            assertTrue(switchedEntry.canUndo)
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(originalProfile, switchedEntry, store))
            assertEquals("ep1-video-bytes", ep1File.readText())
            assertEquals("ep1-subtitles", ep1Subs.resolve("en.srt").readText())

            // 2. Saving Undo metadata throws IOException after the Trash move: move and catalog removal roll back cleanly.
            backend.onAfterTrashMove = {
                backend.onAfterTrashMove = null
                payloads.failSave = true
            }
            val movedOnFailure = executeWatchedCleanup(
                profileId = originalProfile,
                downloadIds = listOf("ep-2"),
                store = store,
                isWatched = { true },
                isPlaying = { false },
                nowEpochMs = { now },
            )
            assertEquals(0, movedOnFailure, "failed Undo save must not report a completed cleanup")
            assertEquals("ep2-video-bytes", ep2File.readText(), "failed Undo save must restore video out of Trash")
            assertEquals("ep2-subtitles", ep2Subs.resolve("en.srt").readText(), "failed Undo save must restore subtitles out of Trash")
            assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "ep-2" && it.status == DownloadStatus.Completed })
            assertTrue(store.recentlyDeleted.value.none { it.downloadId == "ep-2" })

            // Once storage recovers, retrying cleanup moves ep-2 with a valid Undo entry.
            payloads.failSave = false
            val movedOnRetry = executeWatchedCleanup(
                profileId = originalProfile,
                downloadIds = listOf("ep-2"),
                store = store,
                isWatched = { true },
                isPlaying = { false },
                nowEpochMs = { now },
            )
            assertEquals(1, movedOnRetry)
            assertFalse(ep2File.exists())
            val retriedEntry = store.recentlyDeleted.value.single { it.downloadId == "ep-2" }
            assertTrue(retriedEntry.canUndo)
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(originalProfile, retriedEntry, store))
            assertEquals("ep2-video-bytes", ep2File.readText())

            // 3. DeleteAfterWatching with Undo also preserves Undo across profile switches and rolls back on sync failure.
            val deleteRule = testRule(showId = "tt-tx-delete").copy(deleteAfterWatching = true)
            store.upsert(originalProfile, deleteRule)
            val ep3File = downloadsDir.resolve("ep3.mkv").also { it.writeText("ep3-video-bytes") }
            val ep3 = testDownload(3, DownloadStatus.Completed, showId = "tt-tx-delete", ruleKey = deleteRule.key, localFileUri = ep3File.toUri().toString(), id = "ep-3")
                .copy(fileName = "ep3.mkv", downloadedBytes = 600L, totalBytes = 600L)
            backend.payload = DownloadsCodec.encode(listOf(ep3), null, 2L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            val deleteAfterWatching = DeleteAfterWatching(
                store = store,
                downloads = { DownloadsRepository.uiState.value.items },
                isWatchedToTheEnd = { true },
                isInPlayer = { false },
                hasLaterEpisode = { _, _ -> true },
                removeDownload = DownloadsRepository::removeWatchedAutoDownload,
                nowEpochMs = { now },
                holdMs = 0L,
                removeDownloadWithUndo = DownloadsRepository::removeWatchedAutoDownloadWithUndo,
            )
            assertTrue(deleteAfterWatching.onLocalPlaybackCompleted(originalProfile, ep3))

            backend.onAfterTrashMove = {
                backend.onAfterTrashMove = null
                payloads.failSync = true
            }
            assertEquals(AutoDownloadRemovalOutcome.NotMoved, deleteAfterWatching.runDue(originalProfile).getValue("ep-3"))
            assertEquals("ep3-video-bytes", ep3File.readText(), "failed durable Undo sync must roll back DeleteAfterWatching Trash move")
            assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "ep-3" && it.status == DownloadStatus.Completed })
            payloads.failSync = false

            backend.onAfterTrashMove = {
                backend.onAfterTrashMove = null
                ProfileRepository.setActiveProfileIdForTest(otherProfile)
            }
            assertEquals(AutoDownloadRemovalOutcome.Removed, deleteAfterWatching.runDue(originalProfile).getValue("ep-3"))
            assertFalse(ep3File.exists())

            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
            store.currentRules()
            val afterWatchingEntry = store.recentlyDeleted.value.single { it.downloadId == "ep-3" }
            assertEquals(DeletedDownloadReason.AfterWatching, afterWatchingEntry.reason)
            assertTrue(afterWatchingEntry.canUndo)
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(originalProfile, afterWatchingEntry, store))
            assertEquals("ep3-video-bytes", ep3File.readText())

            // 4. Real PlatformAutoDownloadPayloads (DesktopStorage + fsync) across real IOException and profile switch.
            val realStoreDir = root.resolve("real-store").createDirectories()
            val realCacheDir = root.resolve("real-cache").createDirectories()
            DesktopStorage.setRootDirForTest(realStoreDir, realCacheDir)
            try {
                val diskStore = AutoDownloadStore(PlatformAutoDownloadPayloads, nowEpochMs = { now }) { ProfileRepository.activeProfileId }
                diskStore.upsert(originalProfile, rule)

                val ep4File = downloadsDir.resolve("ep4.mkv").also { it.writeText("ep4-real-disk-bytes") }
                val ep4Subs = downloadsDir.resolve("ep4.mkv.subtitles").createDirectories()
                    .also { it.resolve("en.srt").writeText("ep4-real-disk-subs") }
                val ep4 = testDownload(4, DownloadStatus.Completed, showId = "tt-tx-cleanup", ruleKey = rule.key, localFileUri = ep4File.toUri().toString(), id = "ep-4")
                    .copy(fileName = "ep4.mkv", downloadedBytes = 600L, totalBytes = 600L)
                backend.payload = DownloadsCodec.encode(listOf(ep4), null, 3L)
                DownloadsRepository.clearLocalState()
                DownloadsRepository.ensureLoaded()

                // Force a real filesystem IOException inside DesktopStorage.Store.persist() right after the Trash move.
                val blockingTmpDir = realStoreDir.resolve("nuvio_auto_downloads.properties.tmp")
                backend.onAfterTrashMove = {
                    backend.onAfterTrashMove = null
                    blockingTmpDir.createDirectories()
                }
                val diskFailedMove = executeWatchedCleanup(
                    profileId = originalProfile,
                    downloadIds = listOf("ep-4"),
                    store = diskStore,
                    isWatched = { true },
                    isPlaying = { false },
                    nowEpochMs = { now },
                )
                assertEquals(0, diskFailedMove)
                assertEquals("ep4-real-disk-bytes", ep4File.readText())
                assertEquals("ep4-real-disk-subs", ep4Subs.resolve("en.srt").readText())
                assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "ep-4" && it.status == DownloadStatus.Completed })

                // Remove blocking directory and switch profile right after Trash move on the real disk store.
                Files.deleteIfExists(blockingTmpDir)
                backend.onAfterTrashMove = {
                    backend.onAfterTrashMove = null
                    ProfileRepository.setActiveProfileIdForTest(otherProfile)
                }
                assertEquals(
                    1,
                    executeWatchedCleanup(
                        profileId = originalProfile,
                        downloadIds = listOf("ep-4"),
                        store = diskStore,
                        isWatched = { true },
                        isPlaying = { false },
                        nowEpochMs = { now },
                    ),
                )
                assertFalse(ep4File.exists())

                ProfileRepository.setActiveProfileIdForTest(originalProfile)
                DownloadsRepository.onProfileChanged()
                val reopenedDiskStore = AutoDownloadStore(PlatformAutoDownloadPayloads, nowEpochMs = { now }) { ProfileRepository.activeProfileId }
                reopenedDiskStore.currentRules()
                val diskEntry = reopenedDiskStore.recentlyDeleted.value.single { it.downloadId == "ep-4" }
                assertTrue(diskEntry.canUndo)
                assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(originalProfile, diskEntry, reopenedDiskStore))
                assertEquals("ep4-real-disk-bytes", ep4File.readText())
                assertEquals("ep4-real-disk-subs", ep4Subs.resolve("en.srt").readText())
            } finally {
                DesktopStorage.setRootDirForTest(null, null)
            }
        } finally {
            backend.onAfterTrashMove = null
            payloads.failSave = false
            payloads.failSync = false
            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
        }
    }

    @Test
    fun cleanup_requires_restorable_trash_destination_or_uses_archive_and_never_commits_without_undo() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }

        val ep1File = downloadsDir.resolve("ep-no-path.mkv").also { it.writeText("video-no-path") }
        val ep1Subs = downloadsDir.resolve("ep-no-path.mkv.subtitles").createDirectories()
            .also { it.resolve("en.srt").writeText("subs-no-path") }
        val ep1 = testDownload(1, DownloadStatus.Completed, showId = "tt-undo-req", localFileUri = ep1File.toUri().toString(), id = "ep-no-path")
            .copy(fileName = "ep-no-path.mkv", downloadedBytes = 500L, totalBytes = 500L)

        backend.payload = DownloadsCodec.encode(listOf(ep1), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        // 1. System Trash moves the file away without returning a destination path (e.g. Java fallback):
        // cleanup refuses to commit without Undo and restores video + subtitles from hard-link recovery.
        trash = DesktopDownloadsTrash(
            downloadsDir = { downloadsDir },
            systemTrash = null,
            nowEpochMs = { now },
            systemTrashWithResult = { source ->
                Files.move(source, trashDir.resolve("untracked-${source.fileName}"))
                SystemTrashOutcome.Moved(null)
            },
        )

        val movedWithoutPath = executeWatchedCleanup(
            profileId = profile,
            downloadIds = listOf("ep-no-path"),
            store = store,
            isWatched = { true },
            isPlaying = { false },
            nowEpochMs = { now },
        )
        assertEquals(0, movedWithoutPath, "cleanup must not succeed when Trash provides no restorable destination")
        assertEquals("video-no-path", ep1File.readText())
        assertEquals("subs-no-path", ep1Subs.resolve("en.srt").readText())
        assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "ep-no-path" && it.status == DownloadStatus.Completed })
        assertTrue(store.recentlyDeleted.value.isEmpty())
        Files.list(downloadsDir).use { entries ->
            assertTrue(entries.noneMatch { it.fileName.toString().startsWith(".watched-removal-") })
        }

        // 2. When system Trash is boolean-only (no undoable path API), cleanup uses the .removed archive so Undo works.
        trash = DesktopDownloadsTrash(
            downloadsDir = { downloadsDir },
            systemTrash = { file -> file.renameTo(trashDir.resolve("legacy-${file.name}").toFile()) },
            nowEpochMs = { now },
            systemTrashWithResult = null,
        )
        assertTrue(trash.usesSystemTrash)
        assertFalse(trash.usesUndoableSystemTrash)

        val movedToArchive = executeWatchedCleanup(
            profileId = profile,
            downloadIds = listOf("ep-no-path"),
            store = store,
            isWatched = { true },
            isPlaying = { false },
            nowEpochMs = { now },
        )
        assertEquals(1, movedToArchive)
        assertFalse(ep1File.exists())
        assertFalse(ep1Subs.exists())
        val archivedEntry = store.recentlyDeleted.value.single()
        assertTrue(archivedEntry.canUndo)
        assertEquals(true, archivedEntry.trashed?.heldInArchive)

        assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(profile, archivedEntry, store))
        assertEquals("video-no-path", ep1File.readText())
        assertEquals("subs-no-path", ep1Subs.resolve("en.srt").readText())
        assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "ep-no-path" && it.status == DownloadStatus.Completed })
    }

    @Test
    fun archive_cleanup_preserves_undo_past_seven_day_sweep_including_across_profiles() {
        val originalProfile = ProfileRepository.activeProfileId
        val otherProfile = originalProfile + 41
        val realStoreDir = root.resolve("sweep-store").createDirectories()
        val realCacheDir = root.resolve("sweep-cache").createDirectories()
        DesktopStorage.setRootDirForTest(realStoreDir, realCacheDir)
        try {
            var currentClock = now
            trash = DesktopDownloadsTrash(
                downloadsDir = { downloadsDir },
                systemTrash = { file -> file.renameTo(trashDir.resolve("swept-${file.name}").toFile()) },
                nowEpochMs = { currentClock },
                systemTrashWithResult = null,
                isUndoStillPending = AutoDownloadStorage::isArchivePathReferencedByUndo,
            )
            assertTrue(trash.usesSystemTrash)
            assertFalse(trash.usesUndoableSystemTrash)

            val store = AutoDownloadStore(PlatformAutoDownloadPayloads, nowEpochMs = { currentClock }) {
                ProfileRepository.activeProfileId
            }

            // Hold an ordinary non-Undo deletion in .removed first (when system Trash was unavailable),
            // alongside two Undo-backed watched clean-ups (when system Trash is pathless).
            val archiveOnlyTrash = DesktopDownloadsTrash(
                downloadsDir = { downloadsDir },
                systemTrash = null,
                nowEpochMs = { currentClock },
            )
            val ordinaryFile = downloadsDir.resolve("ordinary.mkv").also { it.writeText("ordinary-video") }
            assertTrue(archiveOnlyTrash.moveAway(ordinaryFile.toUri().toString()))
            assertFalse(ordinaryFile.exists())

            val epKeepFile = downloadsDir.resolve("ep-keep-undo.mkv").also { it.writeText("keep-undo-video") }
            val epKeepSubs = downloadsDir.resolve("ep-keep-undo.mkv.subtitles").createDirectories()
                .also { it.resolve("en.srt").writeText("keep-undo-subs") }
            val epClearedFile = downloadsDir.resolve("ep-cleared-undo.mkv").also { it.writeText("cleared-undo-video") }

            val epKeep = testDownload(1, DownloadStatus.Completed, showId = "tt-sweep", localFileUri = epKeepFile.toUri().toString(), id = "ep-keep-undo")
                .copy(fileName = "ep-keep-undo.mkv", downloadedBytes = 600L, totalBytes = 600L)
            val epCleared = testDownload(2, DownloadStatus.Completed, showId = "tt-sweep", localFileUri = epClearedFile.toUri().toString(), id = "ep-cleared-undo")
                .copy(fileName = "ep-cleared-undo.mkv", downloadedBytes = 600L, totalBytes = 600L)

            backend.payload = DownloadsCodec.encode(listOf(epKeep, epCleared), null, 1L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            assertEquals(
                2,
                executeWatchedCleanup(
                    profileId = originalProfile,
                    downloadIds = listOf("ep-keep-undo", "ep-cleared-undo"),
                    store = store,
                    isWatched = { true },
                    isPlaying = { false },
                    nowEpochMs = { currentClock },
                ),
            )
            val clearedEntry = store.recentlyDeleted.value.single { it.downloadId == "ep-cleared-undo" }
            assertTrue(store.clearDeletedUndo(originalProfile, clearedEntry))

            // Switch to another profile and run the archive sweep after 8 days (> HeldFileDays = 7):
            // ordinaryFile and clearedEntry move to systemTrash, while ep-keep-undo's video and subtitles stay restorable!
            ProfileRepository.setActiveProfileIdForTest(otherProfile)
            DownloadsRepository.onProfileChanged()
            currentClock = now + 8 * dayMs
            val sweptCount = trash.purgeHeldFiles(currentClock)
            assertEquals(2, sweptCount, "only non-Undo and cleared-Undo archive entries should sweep to Trash after 7 days")

            // Switch back to originalProfile after 8 days and press Undo -> restores both video and sidecar subtitles!
            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
            store.currentRules()
            val pendingEntry = store.recentlyDeleted.value.single { it.downloadId == "ep-keep-undo" }
            assertTrue(pendingEntry.canUndo)
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(originalProfile, pendingEntry, store))
            assertEquals("keep-undo-video", epKeepFile.readText())
            assertEquals("keep-undo-subs", epKeepSubs.resolve("en.srt").readText())
            assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "ep-keep-undo" && it.status == DownloadStatus.Completed })
        } finally {
            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
            DesktopStorage.setRootDirForTest(null, null)
        }
    }

    @Test
    fun failed_undo_preserves_recovery_when_rollback_undo_save_fails_and_succeeds_on_retry() {
        val originalProfile = ProfileRepository.activeProfileId
        val otherProfile = originalProfile + 31
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { ProfileRepository.activeProfileId }

        try {
            // 1. Catalog save fails AND Undo-store save fails during rollback: recovery must not be lost as FileGone.
            val ep1File = downloadsDir.resolve("ep-rb-1.mkv").also { it.writeText("rb-1-video") }
            val ep1Subs = downloadsDir.resolve("ep-rb-1.mkv.subtitles").createDirectories()
                .also { it.resolve("en.srt").writeText("rb-1-subs") }
            val ep1 = movie("m-rb-1", "Rollback One", 700L, uri = ep1File.toUri().toString())
                .copy(fileName = "ep-rb-1.mkv")
            backend.payload = DownloadsCodec.encode(listOf(ep1), null, 1L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            assertEquals(
                1,
                executeWatchedCleanup(
                    profileId = originalProfile,
                    downloadIds = listOf("m-rb-1"),
                    store = store,
                    isWatched = { true },
                    isPlaying = { false },
                    nowEpochMs = { now },
                ),
            )
            assertFalse(ep1File.exists())

            backend.failSave = true
            payloads.failSave = true
            val firstEntry = store.recentlyDeleted.value.single { it.downloadId == "m-rb-1" }
            val failedWriteUndo = runCatching { undoDeletedWatchedDownload(originalProfile, firstEntry, store) }
                .getOrDefault(UndoTrashResult.NotRestored)
            assertEquals(UndoTrashResult.NotRestored, failedWriteUndo)

            // Once storage is writable again, retrying Undo must restore both video and subtitles (never FileGone or TargetTaken).
            backend.failSave = false
            payloads.failSave = false
            val afterWriteFailure = store.recentlyDeleted.value.single { it.downloadId == "m-rb-1" }
            assertTrue(afterWriteFailure.canUndo)
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(originalProfile, afterWriteFailure, store))
            assertEquals("rb-1-video", ep1File.readText())
            assertEquals("rb-1-subs", ep1Subs.resolve("en.srt").readText())
            assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-rb-1" && it.status == DownloadStatus.Completed })
            assertTrue("m-rb-1" in store.keptDownloadIds.value)
            assertTrue(store.recentlyDeleted.value.none { it.downloadId == "m-rb-1" })

            // 2. Mid-restore profile switch + Undo-store durable sync failure during rollback also retains recovery.
            val ep2File = downloadsDir.resolve("ep-rb-2.mkv").also { it.writeText("rb-2-video") }
            val ep2Subs = downloadsDir.resolve("ep-rb-2.mkv.subtitles").createDirectories()
                .also { it.resolve("en.srt").writeText("rb-2-subs") }
            val ep2 = movie("m-rb-2", "Rollback Two", 700L, uri = ep2File.toUri().toString())
                .copy(fileName = "ep-rb-2.mkv")
            backend.payload = DownloadsCodec.encode(listOf(ep2), null, 2L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            assertEquals(
                1,
                executeWatchedCleanup(
                    profileId = originalProfile,
                    downloadIds = listOf("m-rb-2"),
                    store = store,
                    isWatched = { true },
                    isPlaying = { false },
                    nowEpochMs = { now },
                ),
            )
            val secondEntry = store.recentlyDeleted.value.single { it.downloadId == "m-rb-2" }
            backend.onAfterRestoreMove = {
                backend.onAfterRestoreMove = null
                payloads.failSync = true
                ProfileRepository.setActiveProfileIdForTest(otherProfile)
                DownloadsRepository.onProfileChanged()
            }
            assertEquals(UndoTrashResult.NotRestored, undoDeletedWatchedDownload(originalProfile, secondEntry, store))

            payloads.failSync = false
            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
            store.currentRules()
            val afterSyncAndSwitchFailure = store.recentlyDeleted.value.single { it.downloadId == "m-rb-2" }
            assertTrue(afterSyncAndSwitchFailure.canUndo)
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(originalProfile, afterSyncAndSwitchFailure, store))
            assertEquals("rb-2-video", ep2File.readText())
            assertEquals("rb-2-subs", ep2Subs.resolve("en.srt").readText())

            // 3. Real PlatformAutoDownloadPayloads (DesktopStorage + real filesystem IOException blocking .tmp) during rollback.
            val realStoreDir = root.resolve("rb-real-store").createDirectories()
            val realCacheDir = root.resolve("rb-real-cache").createDirectories()
            DesktopStorage.setRootDirForTest(realStoreDir, realCacheDir)
            try {
                val diskStore = AutoDownloadStore(PlatformAutoDownloadPayloads, nowEpochMs = { now }) {
                    ProfileRepository.activeProfileId
                }
                val ep3File = downloadsDir.resolve("ep-rb-3.mkv").also { it.writeText("rb-3-real-video") }
                val ep3Subs = downloadsDir.resolve("ep-rb-3.mkv.subtitles").createDirectories()
                    .also { it.resolve("en.srt").writeText("rb-3-real-subs") }
                val ep3 = movie("m-rb-3", "Rollback Three", 700L, uri = ep3File.toUri().toString())
                    .copy(fileName = "ep-rb-3.mkv")
                backend.payload = DownloadsCodec.encode(listOf(ep3), null, 3L)
                DownloadsRepository.clearLocalState()
                DownloadsRepository.ensureLoaded()

                assertEquals(
                    1,
                    executeWatchedCleanup(
                        profileId = originalProfile,
                        downloadIds = listOf("m-rb-3"),
                        store = diskStore,
                        isWatched = { true },
                        isPlaying = { false },
                        nowEpochMs = { now },
                    ),
                )
                val diskEntry = diskStore.recentlyDeleted.value.single { it.downloadId == "m-rb-3" }
                val blockingTmpDir = realStoreDir.resolve("nuvio_auto_downloads.properties.tmp")
                backend.failSave = true
                backend.onAfterRestoreMove = {
                    backend.onAfterRestoreMove = null
                    blockingTmpDir.createDirectories()
                }
                assertEquals(UndoTrashResult.NotRestored, undoDeletedWatchedDownload(originalProfile, diskEntry, diskStore))

                Files.deleteIfExists(blockingTmpDir)
                backend.failSave = false
                val reopenedStore = AutoDownloadStore(PlatformAutoDownloadPayloads, nowEpochMs = { now }) {
                    ProfileRepository.activeProfileId
                }
                reopenedStore.currentRules()
                val reopenedEntry = reopenedStore.recentlyDeleted.value.single { it.downloadId == "m-rb-3" }
                assertTrue(reopenedEntry.canUndo)
                assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(originalProfile, reopenedEntry, reopenedStore))
                assertEquals("rb-3-real-video", ep3File.readText())
                assertEquals("rb-3-real-subs", ep3Subs.resolve("en.srt").readText())
                assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-rb-3" && it.status == DownloadStatus.Completed })
            } finally {
                DesktopStorage.setRootDirForTest(null, null)
            }
        } finally {
            backend.onAfterRestoreMove = null
            backend.failSave = false
            backend.failDurableSync = false
            payloads.failSave = false
            payloads.failSync = false
            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
        }
    }

    @Test
    fun undo_keeps_recovery_entry_when_subtitle_destination_collides_or_subtitle_move_fails() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }

        // 1. Subtitle destination collision in system Trash mode: video restores, collision stays untouched, Undo entry kept for subtitles.
        val ep1File = downloadsDir.resolve("ep-sub-1.mkv").also { it.writeText("sub-1-video") }
        val ep1Subs = downloadsDir.resolve("ep-sub-1.mkv.subtitles").createDirectories()
            .also { it.resolve("en.srt").writeText("sub-1-original") }
        val ep1 = movie("m-sub-1", "Subtitle Collision", 600L, uri = ep1File.toUri().toString())
            .copy(fileName = "ep-sub-1.mkv")
        backend.payload = DownloadsCodec.encode(listOf(ep1), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        assertEquals(
            1,
            executeWatchedCleanup(
                profileId = profile,
                downloadIds = listOf("m-sub-1"),
                store = store,
                isWatched = { true },
                isPlaying = { false },
                nowEpochMs = { now },
            ),
        )
        assertFalse(ep1File.exists())
        assertFalse(ep1Subs.exists())

        // Occupy the subtitle destination before pressing Undo.
        ep1Subs.createDirectories().resolve("other.srt").writeText("colliding-subtitle")
        val initialEntry1 = store.recentlyDeleted.value.single { it.downloadId == "m-sub-1" }

        // If catalog persistence also fails during a partial Undo, video rolls back without trashing colliding subtitles.
        backend.failSave = true
        assertEquals(UndoTrashResult.NotRestored, undoDeletedWatchedDownload(profile, initialEntry1, store))
        assertFalse(ep1File.exists())
        assertEquals("colliding-subtitle", ep1Subs.resolve("other.srt").readText())
        backend.failSave = false

        val afterRollbackEntry1 = store.recentlyDeleted.value.single { it.downloadId == "m-sub-1" }
        val collisionUndo = undoDeletedWatchedDownload(profile, afterRollbackEntry1, store)
        assertNotEquals(UndoTrashResult.Restored, collisionUndo, "Undo must not report full Restored when subtitles failed to restore")
        assertEquals(UndoTrashResult.PartiallyRestored, collisionUndo)
        assertEquals("sub-1-video", ep1File.readText(), "video should be restored")
        assertEquals("colliding-subtitle", ep1Subs.resolve("other.srt").readText(), "occupied subtitle destination must not be overwritten")
        assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-sub-1" && it.status == DownloadStatus.Completed })
        assertTrue("m-sub-1" in store.keptDownloadIds.value)
        val pendingSubsEntry1 = store.recentlyDeleted.value.single { it.downloadId == "m-sub-1" }
        assertTrue(pendingSubsEntry1.canUndo, "Undo entry must be retained for un-restored subtitles")
        assertNull(pendingSubsEntry1.trashed?.trashedFileUri)
        assertNotNull(pendingSubsEntry1.trashed?.trashedSubtitlesUri)

        // Retrying while destination is still occupied returns TargetTaken and keeps recovery.
        assertEquals(UndoTrashResult.TargetTaken, undoDeletedWatchedDownload(profile, pendingSubsEntry1, store))
        assertTrue(store.recentlyDeleted.value.single { it.downloadId == "m-sub-1" }.canUndo)

        // Removing the colliding directory allows Undo to restore the original subtitles and clear the Undo entry.
        ep1Subs.toFile().deleteRecursively()
        val afterClearCollision = store.recentlyDeleted.value.single { it.downloadId == "m-sub-1" }
        assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(profile, afterClearCollision, store))
        assertEquals("sub-1-original", ep1Subs.resolve("en.srt").readText())
        assertTrue(store.recentlyDeleted.value.none { it.downloadId == "m-sub-1" })

        // 2. Real OS filesystem move failure on subtitle directory (read-only Trash parent directory).
        val videoTrashDir = trashDir.resolve("videos").createDirectories()
        val lockedSubsTrashDir = trashDir.resolve("locked-subs").createDirectories()
        var moveCount = 0
        trash = DesktopDownloadsTrash(
            downloadsDir = { downloadsDir },
            systemTrash = null,
            nowEpochMs = { now },
            systemTrashWithResult = { source ->
                moveCount++
                val parent = if (source.fileName.toString().endsWith(".subtitles")) lockedSubsTrashDir else videoTrashDir
                val dest = parent.resolve("$moveCount-${source.fileName}")
                Files.move(source, dest)
                SystemTrashOutcome.Moved(dest)
            },
        )
        try {
            val ep2File = downloadsDir.resolve("ep-sub-2.mkv").also { it.writeText("sub-2-video") }
            val ep2Subs = downloadsDir.resolve("ep-sub-2.mkv.subtitles").createDirectories()
                .also { it.resolve("en.srt").writeText("sub-2-original") }
            val ep2 = movie("m-sub-2", "Subtitle Move Failure", 600L, uri = ep2File.toUri().toString())
                .copy(fileName = "ep-sub-2.mkv")
            backend.payload = DownloadsCodec.encode(listOf(ep2), null, 2L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            assertEquals(
                1,
                executeWatchedCleanup(
                    profileId = profile,
                    downloadIds = listOf("m-sub-2"),
                    store = store,
                    isWatched = { true },
                    isPlaying = { false },
                    nowEpochMs = { now },
                ),
            )
            assertTrue(lockedSubsTrashDir.toFile().setWritable(false), "must lock subtitle Trash parent directory")

            val initialEntry2 = store.recentlyDeleted.value.single { it.downloadId == "m-sub-2" }
            val moveFailUndo = undoDeletedWatchedDownload(profile, initialEntry2, store)
            assertNotEquals(UndoTrashResult.Restored, moveFailUndo, "Undo must not report full Restored when subtitle move fails")
            assertEquals(UndoTrashResult.PartiallyRestored, moveFailUndo)
            assertEquals("sub-2-video", ep2File.readText())
            assertFalse(ep2Subs.exists())
            val pendingSubsEntry2 = store.recentlyDeleted.value.single { it.downloadId == "m-sub-2" }
            assertTrue(pendingSubsEntry2.canUndo, "Undo entry must be retained when subtitle move throws IOException")

            assertTrue(lockedSubsTrashDir.toFile().setWritable(true))
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(profile, pendingSubsEntry2, store))
            assertEquals("sub-2-original", ep2Subs.resolve("en.srt").readText())
            assertTrue(store.recentlyDeleted.value.none { it.downloadId == "m-sub-2" })
        } finally {
            lockedSubsTrashDir.toFile().setWritable(true)
        }

        // 3. Archive mode (.removed) subtitle collision keeps archived subtitles protected across the 7-day sweep until restored.
        val archiveStoreDir = root.resolve("sub-archive-store").createDirectories()
        val archiveCacheDir = root.resolve("sub-archive-cache").createDirectories()
        DesktopStorage.setRootDirForTest(archiveStoreDir, archiveCacheDir)
        try {
            var archiveClock = now
            trash = DesktopDownloadsTrash(
                downloadsDir = { downloadsDir },
                systemTrash = { file -> file.renameTo(trashDir.resolve("swept-sub-${file.name}").toFile()) },
                nowEpochMs = { archiveClock },
                systemTrashWithResult = null,
                isUndoStillPending = AutoDownloadStorage::isArchivePathReferencedByUndo,
            )
            val diskStore = AutoDownloadStore(PlatformAutoDownloadPayloads, nowEpochMs = { archiveClock }) { profile }
            val ep3File = downloadsDir.resolve("ep-sub-3.mkv").also { it.writeText("sub-3-video") }
            val ep3Subs = downloadsDir.resolve("ep-sub-3.mkv.subtitles").createDirectories()
                .also { it.resolve("en.srt").writeText("sub-3-archive") }
            val ep3 = movie("m-sub-3", "Archive Subtitle Collision", 600L, uri = ep3File.toUri().toString())
                .copy(fileName = "ep-sub-3.mkv")
            backend.payload = DownloadsCodec.encode(listOf(ep3), null, 3L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            assertEquals(
                1,
                executeWatchedCleanup(
                    profileId = profile,
                    downloadIds = listOf("m-sub-3"),
                    store = diskStore,
                    isWatched = { true },
                    isPlaying = { false },
                    nowEpochMs = { archiveClock },
                ),
            )
            ep3Subs.createDirectories().resolve("other.srt").writeText("archive-colliding-sub")
            val archiveEntry = diskStore.recentlyDeleted.value.single { it.downloadId == "m-sub-3" }
            assertEquals(UndoTrashResult.PartiallyRestored, undoDeletedWatchedDownload(profile, archiveEntry, diskStore))
            assertEquals("sub-3-video", ep3File.readText())
            assertEquals("archive-colliding-sub", ep3Subs.resolve("other.srt").readText())

            // Advance 8 days (> HeldFileDays = 7): pending archived subtitles must NOT be swept to Trash.
            archiveClock = now + 8 * dayMs
            assertEquals(0, trash.purgeHeldFiles(archiveClock))

            ep3Subs.toFile().deleteRecursively()
            val pendingArchiveSubs = diskStore.recentlyDeleted.value.single { it.downloadId == "m-sub-3" }
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(profile, pendingArchiveSubs, diskStore))
            assertEquals("sub-3-archive", ep3Subs.resolve("en.srt").readText())
            assertTrue(diskStore.recentlyDeleted.value.none { it.downloadId == "m-sub-3" })
        } finally {
            DesktopStorage.setRootDirForTest(null, null)
        }
    }

    @Test
    fun undo_commits_keep_and_history_atomically_so_save_failure_never_exposes_restored_file_without_keep() {
        val profile = ProfileRepository.activeProfileId
        var failRemainingSaves = 0
        val payloads = object : AutoDownloadPayloads {
            val saved = mutableMapOf<Int, String>()
            override fun load(profileId: Int): String? = saved[profileId]
            override fun save(profileId: Int, payload: String) {
                if (failRemainingSaves > 0) {
                    failRemainingSaves--
                    throw java.io.IOException("Simulated transient store write failure")
                }
                saved[profileId] = payload
            }
            override fun saveUnreadable(profileId: Int, payload: String) = Unit
        }
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }
        assertTrue(store.setMakeRoomAutomatically(profile, true))

        val ep1File = downloadsDir.resolve("ep-atomic-keep.mkv").also { it.writeText("atomic-keep-video") }
        val ep1Subs = downloadsDir.resolve("ep-atomic-keep.mkv.subtitles").createDirectories()
            .also { it.resolve("en.srt").writeText("atomic-keep-subs") }
        val blockerFile = downloadsDir.resolve("blocker.mkv").also { it.writeText("blocker-video") }
        val ep1 = movie("m-atomic-keep", "Atomic Keep", 600L, uri = ep1File.toUri().toString())
            .copy(fileName = "ep-atomic-keep.mkv")
        val blocker = movie("m-blocker", "Unwatched Blocker", 500L, uri = blockerFile.toUri().toString())
            .copy(fileName = "blocker.mkv")
        totalLimitBytes = 800L
        backend.payload = DownloadsCodec.encode(listOf(ep1, blocker), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        assertEquals(
            1,
            executeWatchedCleanup(
                profileId = profile,
                downloadIds = listOf("m-atomic-keep"),
                store = store,
                isWatched = { it.id == "m-atomic-keep" },
                isPlaying = { false },
                nowEpochMs = { now },
            ),
        )
        assertFalse(ep1File.exists())

        val makeRoom = MakeRoomAutomatically(
            store = store,
            downloads = { DownloadsRepository.uiState.value.items },
            allDownloadBytes = DownloadsRepository::allDownloadBytes,
            autoDownloadBytes = DownloadsRepository::autoDownloadBytes,
            totalStorageLimitBytes = { totalLimitBytes },
            autoStorageLimitBytes = { null },
            watchStatusOf = { item ->
                DownloadWatchStatus(isWatched = item.id == "m-atomic-keep", watchedAtEpochMs = if (item.id == "m-atomic-keep") now - dayMs else null)
            },
            isPlaying = { false },
            nowEpochMs = { now },
        )

        // Fail ONLY the first store save during Undo (while subsequent saves succeed):
        // Undo must NOT drop the Undo entry or leave an un-kept restored download that makeRoom would re-trash!
        backend.onAfterRestoreMove = {
            backend.onAfterRestoreMove = null
            failRemainingSaves = 1
        }
        val firstEntry = store.recentlyDeleted.value.single { it.downloadId == "m-atomic-keep" }
        assertEquals(UndoTrashResult.NotRestored, undoDeletedWatchedDownload(profile, firstEntry, store))
        assertEquals(0, makeRoom.runOnce(profile), "make-room must not find an un-kept restored title after failed Undo")
        val retainedEntry = store.recentlyDeleted.value.single { it.downloadId == "m-atomic-keep" }
        assertTrue(retainedEntry.canUndo, "Undo entry must stay intact when Keep+history commit failed")

        // Retry Undo once writes succeed -> atomically marks Keep and removes Undo entry; makeRoom leaves it alone.
        assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(profile, retainedEntry, store))
        assertTrue("m-atomic-keep" in store.keptDownloadIds.value)
        assertTrue(store.recentlyDeleted.value.none { it.downloadId == "m-atomic-keep" })
        assertEquals("atomic-keep-video", ep1File.readText())
        assertEquals("atomic-keep-subs", ep1Subs.resolve("en.srt").readText())
        assertEquals(0, makeRoom.runOnce(profile), "restored title must stay protected by Keep")
        assertTrue(ep1File.exists())

        // Also verify PartiallyRestored (occupied subtitle destination) commits Keep + remaining Undo atomically.
        val ep2File = downloadsDir.resolve("ep-atomic-partial.mkv").also { it.writeText("atomic-partial-video") }
        val ep2Subs = downloadsDir.resolve("ep-atomic-partial.mkv.subtitles").createDirectories()
            .also { it.resolve("en.srt").writeText("atomic-partial-subs") }
        val ep2 = movie("m-atomic-partial", "Atomic Partial", 600L, uri = ep2File.toUri().toString())
            .copy(fileName = "ep-atomic-partial.mkv")
        backend.payload = DownloadsCodec.encode(listOf(ep2, blocker), null, 2L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        assertEquals(
            1,
            executeWatchedCleanup(
                profileId = profile,
                downloadIds = listOf("m-atomic-partial"),
                store = store,
                isWatched = { it.id == "m-atomic-partial" },
                isPlaying = { false },
                nowEpochMs = { now },
            ),
        )
        ep2Subs.createDirectories().resolve("other.srt").writeText("colliding-partial-sub")
        backend.onAfterRestoreMove = {
            backend.onAfterRestoreMove = null
            failRemainingSaves = 1
        }
        val partialEntry = store.recentlyDeleted.value.single { it.downloadId == "m-atomic-partial" }
        assertEquals(UndoTrashResult.NotRestored, undoDeletedWatchedDownload(profile, partialEntry, store))
        assertFalse("m-atomic-partial" in store.keptDownloadIds.value)
        assertTrue(DownloadsRepository.uiState.value.items.none { it.id == "m-atomic-partial" })
        assertEquals("colliding-partial-sub", ep2Subs.resolve("other.srt").readText())

        val partialRetained = store.recentlyDeleted.value.single { it.downloadId == "m-atomic-partial" }
        assertEquals(UndoTrashResult.PartiallyRestored, undoDeletedWatchedDownload(profile, partialRetained, store))
        assertTrue("m-atomic-partial" in store.keptDownloadIds.value)
        assertTrue(store.recentlyDeleted.value.single { it.downloadId == "m-atomic-partial" }.canUndo)
    }

    @Test
    fun shared_file_is_excluded_from_watched_cleanup_and_make_room_and_never_drops_row_without_undo() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }
        assertTrue(store.setMakeRoomAutomatically(profile, true))

        val sharedFile = downloadsDir.resolve("shared.mkv").also { it.writeText("shared-video") }
        val aliasDir = Files.createSymbolicLink(root.resolve("alias-downloads"), downloadsDir)
        val exclusiveFile = downloadsDir.resolve("exclusive.mkv").also { it.writeText("exclusive-video") }
        val crossProfileFile = downloadsDir.resolve("cross.mkv").also { it.writeText("cross-video") }

        val sharedWatched = movie("m-shared-a", "Shared A", 600L, uri = sharedFile.toUri().toString())
            .copy(fileName = "shared.mkv")
        val sharedSibling = movie("m-shared-b", "Shared B", 600L, uri = aliasDir.resolve("shared.mkv").toUri().toString())
            .copy(fileName = "shared.mkv")
        val crossShared = movie("m-cross", "Cross Profile Shared", 600L, uri = crossProfileFile.toUri().toString())
            .copy(fileName = "cross.mkv")
        val exclusiveWatched = movie("m-exclusive", "Exclusive", 500L, uri = exclusiveFile.toUri().toString())
            .copy(fileName = "exclusive.mkv")

        backend.ownedFileUris += crossProfileFile.toUri().toString()
        totalLimitBytes = 1_500L
        backend.payload = DownloadsCodec.encode(listOf(sharedWatched, sharedSibling, crossShared, exclusiveWatched), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        // 1. Clean-up candidate list excludes both same-profile (including alias path) and cross-profile shared files.
        val currentItems = DownloadsRepository.uiState.value.items
        val nonExclusivelyCleanable = DownloadsRepository.nonExclusivelyCleanableWatchedIds(currentItems)
        assertEquals(setOf("m-shared-a", "m-shared-b", "m-cross"), nonExclusivelyCleanable)
        val listed = cleanableWatchedDownloads(
            items = currentItems,
            watchStatusOf = { item ->
                DownloadWatchStatus(
                    isWatched = item.id != "m-shared-b",
                    watchedAtEpochMs = now - 5 * dayMs,
                )
            },
            excludedIds = nonExclusivelyCleanable,
        )
        assertEquals(listOf("m-exclusive"), listed.map { it.item.id })

        // 2. Direct executeWatchedCleanup on both same-profile and cross-profile shared items refuses to drop rows without Trash/Undo.
        val movedShared = executeWatchedCleanup(
            profileId = profile,
            downloadIds = listOf("m-shared-a", "m-cross"),
            store = store,
            isWatched = { true },
            isPlaying = { false },
            nowEpochMs = { now },
        )
        assertEquals(0, movedShared, "shared files must not be reported as moved to Trash")
        assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-shared-a" && it.status == DownloadStatus.Completed })
        assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-cross" && it.status == DownloadStatus.Completed })
        assertTrue(sharedFile.exists())
        assertTrue(crossProfileFile.exists())
        assertTrue(store.recentlyDeleted.value.isEmpty(), "must not record non-undoable cleanup history for unmoved shared files")

        // 3. MakeRoomAutomatically skips shared files and only moves the exclusively owned watched download.
        val makeRoom = MakeRoomAutomatically(
            store = store,
            downloads = { DownloadsRepository.uiState.value.items },
            allDownloadBytes = DownloadsRepository::allDownloadBytes,
            autoDownloadBytes = DownloadsRepository::autoDownloadBytes,
            totalStorageLimitBytes = { 2_000L },
            autoStorageLimitBytes = { null },
            watchStatusOf = { item ->
                DownloadWatchStatus(isWatched = item.id != "m-shared-b", watchedAtEpochMs = now - 10 * dayMs)
            },
            isPlaying = { false },
            nowEpochMs = { now },
        )
        assertEquals(1, makeRoom.runOnce(profile))
        assertFalse(exclusiveFile.exists())
        assertTrue(sharedFile.exists())
        assertTrue(crossProfileFile.exists())
        assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-shared-a" })
        assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-cross" })
        assertEquals(listOf("m-exclusive"), store.recentlyDeleted.value.map { it.downloadId })
        assertTrue(store.recentlyDeleted.value.single().canUndo)
    }

    @Test
    fun production_macos_downloads_trash_moves_video_and_subtitles_to_system_trash_and_undo_restores_both() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        org.junit.Assume.assumeTrue(isMac)

        val profile = ProfileRepository.activeProfileId
        val realStoreDir = root.resolve("prod-trash-store").createDirectories()
        val realCacheDir = root.resolve("prod-trash-cache").createDirectories()
        val prodDownloadsDir = realStoreDir.resolve("downloads").createDirectories()
        val uniqueName = "nuvio-prod-trash-${java.util.UUID.randomUUID()}.mkv"
        val videoFile = prodDownloadsDir.resolve(uniqueName).also { it.writeText("prod-macos-video-bytes") }
        val subsDir = prodDownloadsDir.resolve("$uniqueName.subtitles").createDirectories()
            .also { it.resolve("en.srt").writeText("prod-macos-subtitle-bytes") }

        var lastTrashed: TrashedWatchedFile? = null
        DesktopDownloads.resetForTest()
        DesktopStorage.setRootDirForTest(realStoreDir, realCacheDir)
        try {
            assertTrue(DownloadsTrash.usesSystemTrash)
            assertTrue(DownloadsTrash.usesUndoableSystemTrash)
            backend.useProductionDownloadsTrash = true

            val store = AutoDownloadStore(PlatformAutoDownloadPayloads, nowEpochMs = { now }) { profile }
            val film = movie("m-prod-mac", "Production Mac Trash", 900L, uri = videoFile.toUri().toString())
                .copy(fileName = uniqueName)
            backend.payload = DownloadsCodec.encode(listOf(film), null, 1L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            val moved = executeWatchedCleanup(
                profileId = profile,
                downloadIds = listOf("m-prod-mac"),
                store = store,
                isWatched = { true },
                isPlaying = { false },
                nowEpochMs = { now },
            )
            assertEquals(1, moved)
            assertFalse(videoFile.exists(), "production macOS Trash must move video out of downloads folder")
            assertFalse(subsDir.exists(), "production macOS Trash must move sidecar subtitles out of downloads folder")

            val deletedEntry = store.recentlyDeleted.value.single { it.downloadId == "m-prod-mac" }
            assertTrue(deletedEntry.canUndo)
            val trashed = assertNotNull(deletedEntry.trashed)
            lastTrashed = trashed
            assertFalse(trashed.heldInArchive, "macOS must use real system Trash rather than .removed archive")
            val trashedVideoPath = Path.of(java.net.URI(assertNotNull(trashed.trashedFileUri)))
            val trashedSubsPath = Path.of(java.net.URI(assertNotNull(trashed.trashedSubtitlesUri)))
            assertTrue(Files.exists(trashedVideoPath), "trashed video must exist at resultingItemURL in macOS Trash")
            assertTrue(Files.isDirectory(trashedSubsPath), "trashed subtitles must exist at resultingItemURL in macOS Trash")

            // Restore via production DownloadsTrash -> moves both video and subtitles back out of macOS Trash.
            assertEquals(UndoTrashResult.Restored, undoDeletedWatchedDownload(profile, deletedEntry, store))
            lastTrashed = null
            assertEquals("prod-macos-video-bytes", videoFile.readText())
            assertEquals("prod-macos-subtitle-bytes", subsDir.resolve("en.srt").readText())
            assertFalse(Files.exists(trashedVideoPath), "restored video must no longer remain in macOS Trash")
            assertFalse(Files.exists(trashedSubsPath), "restored subtitles must no longer remain in macOS Trash")
            assertTrue(DownloadsRepository.uiState.value.items.any { it.id == "m-prod-mac" && it.status == DownloadStatus.Completed })
            assertTrue("m-prod-mac" in store.keptDownloadIds.value)
        } finally {
            // Safety net: if an assertion failed mid-test, move our uniquely named test items out of Trash back into root before root cleanup.
            lastTrashed?.let { leftover ->
                listOfNotNull(leftover.trashedFileUri, leftover.trashedSubtitlesUri).forEach { uri ->
                    runCatching {
                        val path = Path.of(java.net.URI(uri))
                        if (Files.exists(path)) {
                            Files.move(path, root.resolve("cleanup-${path.fileName}"))
                        }
                    }
                }
            }
            backend.useProductionDownloadsTrash = false
            DesktopDownloads.resetForTest()
            DesktopStorage.setRootDirForTest(null, null)
        }
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

    @Test
    fun dialog_preserves_excluded_choices_across_candidate_refreshes() {
        val dayMs = 24L * 60 * 60 * 1_000L
        val now = 1_800_000_000_000L
        val ep1 = CleanableWatchedDownload(
            item = testDownload(1, DownloadStatus.Completed, id = "ep-1").copy(title = "Andor", downloadedBytes = 1_000_000_000L, totalBytes = 1_000_000_000L),
            sizeBytes = 1_000_000_000L,
            watchedAtEpochMs = now - 20 * dayMs,
        )
        val ep2 = CleanableWatchedDownload(
            item = testDownload(2, DownloadStatus.Completed, id = "ep-2").copy(title = "Andor", downloadedBytes = 1_500_000_000L, totalBytes = 1_500_000_000L),
            sizeBytes = 1_500_000_000L,
            watchedAtEpochMs = now - 10 * dayMs,
        )
        val ep3 = CleanableWatchedDownload(
            item = testDownload(3, DownloadStatus.Completed, id = "ep-3").copy(title = "Andor", downloadedBytes = 2_000_000_000L, totalBytes = 2_000_000_000L),
            sizeBytes = 2_000_000_000L,
            watchedAtEpochMs = now - 2 * dayMs,
        )
        var candidatesState by mutableStateOf(listOf(ep1, ep2))
        var confirmed: Set<String>? = null

        compose.setContent {
            NuvioTheme {
                WatchedCleanupDialog(
                    candidates = candidatesState,
                    nowEpochMs = now,
                    usesSystemTrash = true,
                    onConfirm = { confirmed = it },
                    onDismiss = {},
                )
            }
        }

        // Uncheck S01E01 while S01E02 remains checked.
        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E01"))
            .assertIsOn()
            .performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E01"))
            .assertIsOff()

        // Candidate list refreshes: S01E03 arrives while the dialog is open -> S01E01 stays unchecked, S01E02 stays checked, S01E03 arrives unchecked.
        compose.runOnIdle { candidatesState = listOf(ep1, ep2, ep3) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E01")).assertIsOff()
        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E02")).assertIsOn()
        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E03")).assertIsOff()

        // Another refresh removes S01E03 -> S01E01 still stays unchecked!
        compose.runOnIdle { candidatesState = listOf(ep1, ep2) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E01")).assertIsOff()
        compose.onNodeWithContentDescription(text(Res.string.downloads_select_description, "Andor S01E02")).assertIsOn()

        compose.onNode(hasText(text(Res.string.downloads_delete_confirm_trash)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(setOf("ep-2"), confirmed)
    }

    @Test
    fun dialog_distinguishes_all_watched_empty_state_and_archive_mode_wording() {
        val now = 1_800_000_000_000L
        compose.setContent {
            NuvioTheme {
                WatchedCleanupDialog(
                    candidates = emptyList(),
                    nowEpochMs = now,
                    usesSystemTrash = false,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText(text(Res.string.downloads_delete_message_held)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_cleanup_empty_all)).assertIsDisplayed()
        compose.onNode(hasText(text(Res.string.downloads_cleanup_confirm_archive)) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()
            .assertIsNotEnabled()

        compose.onNodeWithText(text(Res.string.downloads_cleanup_filter_30_days)).performClick()
        compose.onNodeWithText(text(Res.string.downloads_cleanup_empty_30_days)).assertIsDisplayed()
    }

    @Test
    fun downloads_screen_hides_cleanup_action_when_unsupported_and_routes_through_controller_when_supported() {
        val profile = ProfileRepository.activeProfileId
        val watchedStore = DesktopStorage.store("nuvio_watched")
        val previousWatched = WatchedStorage.loadPayload(profile)
        val realBackend = DownloadsRepository.backend
        val realOnTrip = DownloadsRepository.onProfileReadyForTrip
        val watchedFilm = DownloadItem(
            id = "m-screen-cleanup",
            contentType = "movie",
            parentMetaId = "tt-screen-cleanup",
            parentMetaType = "movie",
            videoId = "tt-screen-cleanup",
            title = "Arrival",
            streamTitle = "1080p",
            providerName = "A",
            sourceUrl = "https://example.com/arrival.mkv",
            localFileUri = "file:/downloads/arrival.mkv",
            fileName = "arrival.mkv",
            status = DownloadStatus.Completed,
            downloadedBytes = 2_000_000_000L,
            totalBytes = 2_000_000_000L,
            framesCheck = DownloadFramesCheck.Passed,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val memBackend = object : DownloadsBackend {
            var stored: String? = DownloadsCodec.encode(listOf(watchedFilm), null, 1L)
            override fun loadPayload(): String? = stored
            override fun savePayload(payload: String) { stored = payload }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle = object : DownloadsTaskHandle { override fun cancel() = Unit }
            override fun removeFile(localFileUri: String?): Boolean = false
            override fun removePartialFile(destinationFileName: String): Boolean = false
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
        }
        try {
            WatchedRepository.clearLocalState()
            watchedStore.remove("watched_$profile")
            WatchedRepository.activateSource(WatchProgressSource.NUVIO_SYNC)
            WatchedRepository.markWatchedLocally(
                listOf(WatchedItem(id = "tt-screen-cleanup", type = "movie", name = "Arrival", markedAtEpochMs = 1_700_000_000_000L)),
            )

            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = memBackend
            DownloadsRepository.onProfileReadyForTrip = {}
            DownloadsRepository.ensureLoaded()

            val cleanedCalls = mutableListOf<Pair<Int, Set<String>>>()
            var mode by mutableStateOf("mobile")
            val unsupportedController = object : AutoDownloadController {
                override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
                override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
                override val storageLimitGb = MutableStateFlow<Int?>(null)
                override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
                override val usesSystemTrash = false
                override val usesUndoableSystemTrash = false
                override val supportsWatchedCleanup = false
                override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean) = Unit
                override fun turnOff(profileId: Int, ruleKey: String) = Unit
                override fun setStorageLimit(profileId: Int, limitGb: Int?) = Unit
                override fun keepDownload(profileId: Int, downloadId: String) = Unit
                override fun deleteDownloadNow(profileId: Int, downloadId: String) = Unit
                override fun checkNow() = Unit
            }
            val archiveController = object : AutoDownloadController {
                override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
                override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
                override val storageLimitGb = MutableStateFlow<Int?>(null)
                override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
                override val usesSystemTrash = true
                override val usesUndoableSystemTrash = false
                override val supportsWatchedCleanup = true
                override fun cleanUpWatchedDownloads(profileId: Int, downloadIds: Set<String>) {
                    cleanedCalls += profileId to downloadIds
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
                    when (mode) {
                        "mobile" -> DownloadsScreenContent(
                            onBack = {},
                            onOpenDownload = {},
                            showAutoDownloads = false,
                            autoDownloads = null,
                        )
                        "unsupported" -> DownloadsScreenContent(
                            onBack = {},
                            onOpenDownload = {},
                            showAutoDownloads = true,
                            autoDownloads = unsupportedController,
                        )
                        else -> DownloadsScreenContent(
                            onBack = {},
                            onOpenDownload = {},
                            showAutoDownloads = true,
                            autoDownloads = archiveController,
                        )
                    }
                }
            }

            // 1. Mobile default (showAutoDownloads = false, autoDownloads = null): Clean up watched action is not offered.
            compose.onNodeWithText("Arrival").assertIsDisplayed()
            compose.onNodeWithText(text(Res.string.downloads_cleanup_watched_button)).assertDoesNotExist()

            // 2. Controller reporting supportsWatchedCleanup = false: Clean up watched action is not offered.
            compose.runOnIdle { mode = "unsupported" }
            compose.waitForIdle()
            compose.onNodeWithText(text(Res.string.downloads_cleanup_watched_button)).assertDoesNotExist()

            // 3. Supported controller (including archive mode on desktop): action opens dialog and confirms via controller.
            compose.runOnIdle { mode = "archive" }
            compose.waitForIdle()
            compose.onNodeWithText(text(Res.string.downloads_cleanup_watched_button)).assertIsDisplayed().performClick()
            compose.onNode(hasText(text(Res.string.downloads_cleanup_confirm_archive)) and hasAnyAncestor(isDialog()))
                .assertIsEnabled()
                .performClick()

            assertEquals(listOf(profile to setOf("m-screen-cleanup")), cleanedCalls)
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = realBackend
            DownloadsRepository.onProfileReadyForTrip = realOnTrip
            WatchedRepository.clearLocalState()
            WatchedRepository.activateSource(WatchProgressSource.NUVIO_SYNC)
            if (previousWatched == null) {
                watchedStore.remove("watched_$profile")
            } else {
                WatchedStorage.savePayload(profile, previousWatched)
            }
        }
    }
}
