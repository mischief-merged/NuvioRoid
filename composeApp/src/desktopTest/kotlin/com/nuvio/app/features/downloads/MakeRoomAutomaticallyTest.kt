package com.nuvio.app.features.downloads

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.MakeRoomAutomaticallySetting
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_auto_recent_heading
import nuvio.composeapp.generated.resources.downloads_recent_made_room_today
import nuvio.composeapp.generated.resources.downloads_undo_deleted_description
import nuvio.composeapp.generated.resources.settings_make_room_description
import nuvio.composeapp.generated.resources.settings_make_room_title
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
import kotlin.test.assertTrue

class MakeRoomAutomaticallyTest {
    private val dayMs = 24L * 60 * 60 * 1_000L
    private val now = 1_800_000_000_000L

    private lateinit var root: Path
    private lateinit var downloadsDir: Path
    private lateinit var trashDir: Path
    private lateinit var trash: DesktopDownloadsTrash

    private val startedIds = mutableListOf<String>()
    private var totalLimitBytes: Long? = null
    private var autoLimitBytes: Long? = null

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
        root = Files.createTempDirectory("nuvio-make-room-test").toRealPath()
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
        autoLimitBytes = null

        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.autoDownloadLimits = object : AutoDownloadLimits {
            override fun storageLimitBytes(): Long? = autoLimitBytes
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

    private fun movieOnDisk(
        id: String,
        title: String,
        bytes: Long,
        status: DownloadStatus = DownloadStatus.Completed,
        placeholder: Boolean = false,
        locationId: String? = null,
        ruleKey: String? = null,
    ): Pair<Path, DownloadItem> {
        val file = downloadsDir.resolve("$id.mkv").also { it.writeText("video-$id") }
        val item = DownloadItem(
            id = id,
            contentType = "movie",
            parentMetaId = id,
            parentMetaType = "movie",
            videoId = id,
            title = title,
            streamTitle = "1080p",
            providerName = "A",
            sourceUrl = "https://example.com/$id.mkv",
            localFileUri = if (status == DownloadStatus.Completed) file.toUri().toString() else null,
            fileName = "$id.mkv",
            locationId = locationId,
            autoDownloadRuleKey = ruleKey,
            status = status,
            downloadedBytes = bytes,
            totalBytes = bytes,
            framesCheck = if (placeholder) DownloadFramesCheck.NotTheRealVideo else DownloadFramesCheck.Passed,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        return file to item
    }

    private fun makeRoomRunner(
        store: AutoDownloadStore,
        watchStatusOf: (DownloadItem) -> DownloadWatchStatus,
        isPlaying: (DownloadItem) -> Boolean = { false },
        isBusyOrMoving: (DownloadItem) -> Boolean = { false },
        hasRuleWaitingForStorage: () -> Boolean = { false },
    ) = MakeRoomAutomatically(
        store = store,
        downloads = { DownloadsRepository.uiState.value.items },
        allDownloadBytes = DownloadsRepository::allDownloadBytes,
        autoDownloadBytes = DownloadsRepository::autoDownloadBytes,
        totalStorageLimitBytes = { totalLimitBytes },
        autoStorageLimitBytes = { autoLimitBytes },
        watchStatusOf = watchStatusOf,
        isPlaying = isPlaying,
        isConnected = backend::isConnected,
        isBusyOrMoving = isBusyOrMoving,
        usesSystemTrash = { backend.usesSystemTrash },
        hasRuleWaitingForStorage = hasRuleWaitingForStorage,
        nowEpochMs = { now },
    )

    @Test
    fun off_by_default_moves_nothing_even_when_over_limit_and_persists_per_profile() {
        val payloads = MemoryAutoDownloadPayloads()
        var activeProfile = 1
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { activeProfile }

        assertFalse(store.makeRoomAutomatically.value)
        assertFalse(store.isMakeRoomEnabled(1))

        val (watchedFile, watchedItem) = movieOnDisk("m-watched", "Watched Movie", 1_500L)
        totalLimitBytes = 1_000L
        backend.payload = DownloadsCodec.encode(listOf(watchedItem), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        val runner = makeRoomRunner(
            store = store,
            watchStatusOf = { DownloadWatchStatus(isWatched = true, watchedAtEpochMs = now - 30 * dayMs) },
        )
        assertEquals(0, runner.runOnce(1))
        assertTrue(watchedFile.exists(), "off by default must never move watched downloads")

        // Enable on profile 1 only; profile 2 stays off, and reloading preserves profile 1's choice.
        assertTrue(store.setMakeRoomAutomatically(1, true))
        assertTrue(store.makeRoomAutomatically.value)
        activeProfile = 2
        assertFalse(store.isMakeRoomEnabled(2))
        assertFalse(store.makeRoomAutomatically.value)

        activeProfile = 1
        val reloaded = AutoDownloadStore(payloads, nowEpochMs = { now }) { activeProfile }
        reloaded.currentRules()
        assertTrue(reloaded.isMakeRoomEnabled(1))
        assertTrue(reloaded.makeRoomAutomatically.value)
    }

    @Test
    fun evicts_oldest_watched_first_stops_as_soon_as_waiting_download_fits_and_resumes_it() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }
        assertTrue(store.setMakeRoomAutomatically(profile, true))

        val (oldFile, oldWatched) = movieOnDisk("m-old", "Old Film", 600L)
        val (midFile, midWatched) = movieOnDisk("m-mid", "Middle Film", 600L)
        val (recentFile, recentWatched) = movieOnDisk("m-recent", "Recent Film", 200L)

        // Put recent first in list order to prove sorting is by watchedAtEpochMs, not list index.
        totalLimitBytes = 1_400L
        backend.payload = DownloadsCodec.encode(listOf(recentWatched, midWatched, oldWatched), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        // Queue a 500 B hand-started download -> 1,400 + 500 = 1,900 > 1,400, so it waits.
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
                behaviorHints = StreamBehaviorHints(videoSize = 500L),
            ),
        )
        val waitingBefore = DownloadsRepository.uiState.value.items.first { it.parentMetaId == "tt-wait" }
        assertEquals(DownloadStatus.Paused, waitingBefore.status)
        assertEquals(AutoDownloadStop.TotalStorageLimit, waitingBefore.autoDownloadStop)
        assertTrue(startedIds.isEmpty())

        val watchedTimes = mapOf(
            "m-old" to now - 30 * dayMs,
            "m-mid" to now - 10 * dayMs,
            "m-recent" to now - 1 * dayMs,
        )
        val runner = makeRoomRunner(
            store = store,
            watchStatusOf = { item ->
                val at = watchedTimes[item.id]
                DownloadWatchStatus(isWatched = at != null, watchedAtEpochMs = at)
            },
        )

        val moved = runBlocking { runner.runIfNeeded(profile) }

        // Moving m-old (600 B) brings used bytes to 800 B; 800 + 500 = 1,300 <= 1,400, so m-mid and m-recent stay!
        assertEquals(1, moved)
        assertFalse(oldFile.exists(), "oldest watched download must move to Trash")
        assertTrue(midFile.exists(), "second watched download must not be evicted once room fits")
        assertTrue(recentFile.exists(), "most recent watched download must stay untouched")

        val waitingAfter = DownloadsRepository.uiState.value.items.first { it.parentMetaId == "tt-wait" }
        assertEquals(DownloadStatus.Downloading, waitingAfter.status)
        assertEquals(listOf(waitingAfter.id), startedIds)

        val entry = store.recentlyDeleted.value.single()
        assertEquals("m-old", entry.downloadId)
        assertEquals(DeletedDownloadReason.MadeRoom, entry.reason)
        assertTrue(entry.canUndo)
    }

    @Test
    fun excludes_unwatched_rewatching_keep_playing_downloading_flagged_moving_and_unplugged_titles() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }
        assertTrue(store.setMakeRoomAutomatically(profile, true))
        store.keep(profile, "m-kept")

        val (unwatchedFile, unwatched) = movieOnDisk("m-unwatched", "Unwatched", 700L)
        val (rewatchFile, rewatch) = movieOnDisk("m-rewatch", "Rewatching", 700L)
        val (keptFile, kept) = movieOnDisk("m-kept", "Kept", 700L)
        val (playingFile, playing) = movieOnDisk("m-playing", "Playing", 700L)
        val (dlFile, downloading) = movieOnDisk("m-dl", "Downloading", 700L, status = DownloadStatus.Downloading)
        val (flaggedFile, flagged) = movieOnDisk("m-flagged", "Flagged", 700L, placeholder = true)
        val (movingFile, moving) = movieOnDisk("m-moving", "Moving", 700L)
        val (awayFile, away) = movieOnDisk("m-away", "Away", 700L, locationId = "away")
        backend.disconnectedLocations += "away"

        totalLimitBytes = 500L
        backend.payload = DownloadsCodec.encode(
            listOf(unwatched, rewatch, kept, playing, downloading, flagged, moving, away),
            null,
            1L,
        )
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        val progressState = WatchProgressUiState(
            entries = listOf(
                WatchProgressEntry(
                    contentType = "movie",
                    parentMetaId = "m-rewatch",
                    parentMetaType = "movie",
                    videoId = "m-rewatch",
                    title = "Rewatching",
                    lastPositionMs = 25 * 60_000L,
                    durationMs = 100 * 60_000L,
                    lastUpdatedEpochMs = now - dayMs,
                    isCompleted = false,
                ),
            ),
        )
        val historyWatched = mapOf(
            "m-rewatch" to now - 90 * dayMs,
            "m-kept" to now - 80 * dayMs,
            "m-playing" to now - 70 * dayMs,
            "m-flagged" to now - 60 * dayMs,
            "m-moving" to now - 50 * dayMs,
            "m-away" to now - 40 * dayMs,
        )

        val runner = makeRoomRunner(
            store = store,
            watchStatusOf = { item ->
                item.watchStatus(
                    progressState = progressState,
                    isHistoryWatched = { id, _, _, _ -> id in historyWatched },
                    historyWatchedAt = { id, _, _, _ -> historyWatched[id] },
                )
            },
            isPlaying = { it.id == "m-playing" },
            isBusyOrMoving = { it.id == "m-moving" },
        )

        assertEquals(0, runner.runOnce(profile))
        listOf(unwatchedFile, rewatchFile, keptFile, playingFile, dlFile, flaggedFile, movingFile, awayFile).forEach {
            assertTrue(it.exists(), "${it.fileName} must not be touched")
        }
        assertTrue(store.recentlyDeleted.value.isEmpty())
    }

    @Test
    fun does_not_evict_watched_files_when_waiting_download_exceeds_total_limit_or_cannot_fit() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }
        assertTrue(store.setMakeRoomAutomatically(profile, true))

        val (unwatchedFile, unwatched) = movieOnDisk("m-unwatched", "Unwatched", 600L)
        val (watchedFile, watched) = movieOnDisk("m-watched", "Watched", 300L)

        // 600 + 300 = 900 <= 1,000 limit; waiting download wants 600 B.
        // Even if watched (300 B) were removed, unwatched (600) + waiting (600) = 1,200 > 1,000!
        totalLimitBytes = 1_000L
        backend.payload = DownloadsCodec.encode(listOf(unwatched, watched), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-huge",
            parentMetaId = "tt-huge",
            parentMetaType = "movie",
            title = "Too Large To Fit",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "4K",
                url = "https://example.com/huge.mkv",
                addonName = "A",
                addonId = "addon:A",
                behaviorHints = StreamBehaviorHints(videoSize = 600L),
            ),
        )

        val runner = makeRoomRunner(
            store = store,
            watchStatusOf = { item ->
                DownloadWatchStatus(
                    isWatched = item.id == "m-watched",
                    watchedAtEpochMs = if (item.id == "m-watched") now - 30 * dayMs else null,
                )
            },
        )

        assertEquals(0, runner.runOnce(profile))
        assertTrue(unwatchedFile.exists())
        assertTrue(watchedFile.exists(), "watched file must not be trashed when waiting download still could not fit")
    }

    @Test
    fun auto_only_limit_skips_manual_downloads_evicts_oldest_auto_and_resumes_auto_download() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }
        val rule = testRule(showId = "tt-show")
        store.upsert(profile, rule)
        assertTrue(store.setMakeRoomAutomatically(profile, true))

        val (manualFile, manualWatched) = movieOnDisk("m-manual", "Manual Film", 600L)
        val ep1File = downloadsDir.resolve("ep1.mkv").also { it.writeText("ep1-bytes") }
        val ep2File = downloadsDir.resolve("ep2.mkv").also { it.writeText("ep2-bytes") }

        val ep1 = testDownload(1, DownloadStatus.Completed, showId = "tt-show", ruleKey = rule.key, localFileUri = ep1File.toUri().toString(), id = "ep-1")
            .copy(fileName = "ep1.mkv", downloadedBytes = 600L, totalBytes = 600L)
        val ep2 = testDownload(2, DownloadStatus.Completed, showId = "tt-show", ruleKey = rule.key, localFileUri = ep2File.toUri().toString(), id = "ep-2")
            .copy(fileName = "ep2.mkv", downloadedBytes = 400L, totalBytes = 400L)
        val ep3Waiting = testDownload(3, DownloadStatus.Paused, showId = "tt-show", ruleKey = rule.key, localFileUri = null, id = "ep-3")
            .copy(
                fileName = "ep3.mkv",
                downloadedBytes = 0L,
                totalBytes = 500L,
                waitingSizeBytes = 500L,
                autoDownloadStop = AutoDownloadStop.StorageLimit,
                createdAtEpochMs = 10L,
            )

        autoLimitBytes = 1_000L
        backend.payload = DownloadsCodec.encode(listOf(manualWatched, ep1, ep2, ep3Waiting), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        val watchedAt = mapOf(
            "m-manual" to now - 60 * dayMs,
            "ep-1" to now - 20 * dayMs,
            "ep-2" to now - 5 * dayMs,
        )
        val runner = makeRoomRunner(
            store = store,
            watchStatusOf = { item ->
                val at = watchedAt[item.id]
                DownloadWatchStatus(isWatched = at != null, watchedAtEpochMs = at)
            },
        )

        assertEquals(1, runner.runOnce(profile))
        assertTrue(manualFile.exists(), "manual watched download must be skipped when only auto limit needs room")
        assertFalse(ep1File.exists(), "oldest watched auto-download must be moved to Trash")
        assertTrue(ep2File.exists(), "newer watched auto-download must remain untouched once room fits")
        assertTrue(autoDownloadEpisodeKey(1, 1) in store.rule(profile, rule.key)!!.ownedEpisodeKeys)

        val ep3After = DownloadsRepository.uiState.value.items.first { it.id == "ep-3" }
        assertEquals(DownloadStatus.Downloading, ep3After.status)
        assertEquals(listOf("ep-3"), startedIds)
    }

    @Test
    fun undo_restores_made_room_file_subtitles_and_record_marks_keep_and_prevents_re_eviction() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }
        assertTrue(store.setMakeRoomAutomatically(profile, true))

        val (filmFile, film) = movieOnDisk("m-1", "Solaris", 800L)
        val subsDir = downloadsDir.resolve("m-1.mkv.subtitles").createDirectories()
            .also { it.resolve("en.srt").writeText("solaris-subs") }

        totalLimitBytes = 500L
        backend.payload = DownloadsCodec.encode(listOf(film), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        val runner = makeRoomRunner(
            store = store,
            watchStatusOf = { DownloadWatchStatus(isWatched = true, watchedAtEpochMs = now - 15 * dayMs) },
        )

        assertEquals(1, runner.runOnce(profile))
        assertFalse(filmFile.exists())
        assertFalse(subsDir.exists())

        val entry = store.recentlyDeleted.value.single()
        assertEquals(DeletedDownloadReason.MadeRoom, entry.reason)
        assertTrue(entry.canUndo)

        // Undo restores the video file, sidecar subtitles, and Completed record, and marks it Keep.
        val undoResult = undoDeletedWatchedDownload(profile, entry, store)
        assertEquals(UndoTrashResult.Restored, undoResult)
        assertEquals("video-m-1", filmFile.readText())
        assertEquals("solaris-subs", subsDir.resolve("en.srt").readText())
        val restored = assertNotNull(DownloadsRepository.uiState.value.items.firstOrNull { it.id == "m-1" })
        assertEquals(DownloadStatus.Completed, restored.status)
        assertTrue("m-1" in store.keptDownloadIds.value)
        assertTrue(store.recentlyDeleted.value.isEmpty())

        // Even though still over totalLimitBytes (800 > 500), a subsequent pass must NOT re-trash it!
        assertEquals(0, runner.runOnce(profile))
        assertTrue(filmFile.exists())
    }

    @Test
    fun paused_trip_fill_download_counts_only_partial_bytes_and_does_not_trigger_make_room_eviction() {
        val profile = ProfileRepository.activeProfileId
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads, nowEpochMs = { now }) { profile }
        assertTrue(store.setMakeRoomAutomatically(profile, true))

        val (watchedFile, watched) = movieOnDisk("m-watched", "Watched Film", 600L)
        val pausedTrip = testDownload(1, DownloadStatus.Paused, showId = "tt-trip", id = "trip-paused").copy(
            tripFill = true,
            downloadedBytes = 200L,
            totalBytes = 2_000L,
            expectedSizeBytes = 2_000L,
            pausedByConnection = false,
            autoDownloadStop = null,
        )

        // On-disk usage is 600 + 200 = 800 <= 1,000 limit; user-paused trip's 2,000 B expected size must NOT trigger eviction!
        totalLimitBytes = 1_000L
        autoLimitBytes = 1_000L
        backend.payload = DownloadsCodec.encode(listOf(watched, pausedTrip), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        val runner = makeRoomRunner(
            store = store,
            watchStatusOf = { item ->
                DownloadWatchStatus(
                    isWatched = item.id == "m-watched",
                    watchedAtEpochMs = if (item.id == "m-watched") now - 30 * dayMs else null,
                )
            },
        )

        assertEquals(0, runner.runOnce(profile))
        assertTrue(watchedFile.exists(), "user-paused trip download must not cause watched downloads to be trashed")
    }
}

@OptIn(ExperimentalTestApi::class)
class MakeRoomAutomaticallyUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    @Test
    fun setting_starts_unchecked_and_toggles_controller_and_recent_row_shows_made_room_with_undo() {
        val now = 1_800_000_000_000L
        val restoredItem = testDownload(2, DownloadStatus.Completed, id = "ep-2").copy(title = "Silo")
        val deleted = AutoDownloadDeleted(
            title = "Silo",
            seasonNumber = 1,
            episodeNumber = 2,
            atEpochMs = now,
            downloadId = "ep-2",
            reason = DeletedDownloadReason.MadeRoom,
            restorableItem = restoredItem,
            trashed = TrashedWatchedFile(
                originalFileUri = "file:/downloads/s1e2.mkv",
                trashedFileUri = "file:/trash/s1e2.mkv",
            ),
        )
        val toggled = mutableListOf<Pair<Int, Boolean>>()
        val undone = mutableListOf<Pair<Int, AutoDownloadDeleted>>()

        val controller = object : AutoDownloadController {
            override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
            override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
            override val storageLimitGb = MutableStateFlow<Int?>(null)
            override val makeRoomAutomatically = MutableStateFlow(false)
            override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
            override val recentlyDeleted = MutableStateFlow(listOf(deleted))
            override val usesSystemTrash = true

            override fun setMakeRoomAutomatically(profileId: Int, enabled: Boolean) {
                toggled += profileId to enabled
                makeRoomAutomatically.value = enabled
            }

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
                androidx.compose.foundation.layout.Column {
                    MakeRoomAutomaticallySetting(isTablet = false, controller = controller, profileId = 5)
                    AutoDownloadSection(
                        controller = controller,
                        profileId = 5,
                        downloads = MutableStateFlow(DownloadsUiState()),
                        nowEpochMs = { now },
                    )
                }
            }
        }

        compose.onNodeWithText(text(Res.string.settings_make_room_title)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_make_room_description)).assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff()

        compose.onNodeWithText(text(Res.string.settings_make_room_title)).performClick()
        assertEquals(listOf(5 to true), toggled)
        compose.onNode(isToggleable()).assertIsOn()

        compose.onNodeWithText(text(Res.string.downloads_auto_recent_heading)).assertIsDisplayed()
        compose.onNodeWithText("Silo S1 E2 • " + text(Res.string.downloads_recent_made_room_today)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(Res.string.downloads_undo_deleted_description, "Silo S01E02"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .performClick()

        assertEquals(listOf(5 to deleted), undone)
    }
}
