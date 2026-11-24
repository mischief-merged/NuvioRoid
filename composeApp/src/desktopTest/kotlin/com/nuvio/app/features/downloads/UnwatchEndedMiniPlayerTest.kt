package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.player.ActivePlayerSessionRegistry
import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.BindPlayerRuntimeEffects
import com.nuvio.app.features.player.BindPlayerSessionBridge
import com.nuvio.app.features.player.MiniPlayerDock
import com.nuvio.app.features.player.MiniPlayerDockTestTag
import com.nuvio.app.features.player.MiniPlayerPlaybackState
import com.nuvio.app.features.player.PlayerBackReleaseGuard
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerExitTargetHolder
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerPresentationMode
import com.nuvio.app.features.player.PlayerScreenArgs
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.PlayerSessionController
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.PreparedStartupSubtitles
import com.nuvio.app.features.player.SubtitleTrack
import com.nuvio.app.features.player.requestMinimize
import com.nuvio.app.features.player.stopPlaybackAndFlushForQuit
import com.nuvio.app.features.player.updatePlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProfileStore
import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_close
import nuvio.composeapp.generated.resources.detail_btn_play
import nuvio.composeapp.generated.resources.player_next_episode
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Before
import org.junit.Rule

class UnwatchEndedMiniPlayerTest {
    @get:Rule
    val compose = createComposeRule()

    private val showId = "tt9913901"
    private val episodes = (1..3).map { number ->
        MetaVideo(
            id = "$showId:1:$number",
            title = "Episode $number",
            released = "2024-01-0$number",
            season = 1,
            episode = number,
        )
    }
    private val showMeta = MetaDetails(
        id = showId,
        type = "series",
        name = "Pantheon",
        videos = episodes,
    )

    private val finishDownload = mutableMapOf<Int?, (String, Long?) -> Unit>()
    private val realBackend = DownloadsRepository.backend
    private lateinit var tempFolder: Path
    private lateinit var downloadsDir: Path
    private lateinit var systemTrashDir: Path
    private lateinit var trash: DesktopDownloadsTrash
    private lateinit var runtimeScope: CoroutineScope

    private var profileId: Int = 1
    private var previousWatchedPayload: String? = null
    private var previousProgressPayload: String? = null
    private var previousAutoPayload: String? = null
    private var previousAutoStarted: Boolean = false
    private var savedProviderPorts: List<SavedPorts> = emptyList()

    private data class RecordedScrobble(
        val providerId: TrackingProviderId,
        val action: TrackingScrobbleAction,
        val event: TrackingScrobbleEvent,
    )

    private data class SavedPorts(
        val id: TrackingProviderId,
        val auth: TrackingAuthProvider?,
        val scrobbler: TrackingScrobbler?,
        val historyWriter: TrackingHistoryWriter?,
        val watchedProvider: TrackingWatchedProvider?,
        val progressProvider: TrackingProgressProvider?,
    )

    private val recordedScrobbles = mutableListOf<RecordedScrobble>()

    private inner class TestDownloadsBackend : DownloadsBackend {
        var payload: String? = null
        override val usesSystemTrash: Boolean get() = trash.usesSystemTrash

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
            finishDownload[request.item.episodeNumber] = onSuccess
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true
        override fun removePartialFile(destinationFileName: String): Boolean = true
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
        override fun trashFile(localFileUri: String): Boolean = trash.moveAway(localFileUri)
        override fun trashWatchedFile(localFileUri: String, stillWanted: () -> Boolean): AutoDownloadRemovalResult =
            trash.moveWatchedAway(localFileUri, stillWanted)
        override fun isSameFile(firstFileUri: String, secondFileUri: String): Boolean =
            trash.isSameFile(firstFileUri, secondFileUri)
    }

    private class TestEngineController : PlayerEngineController {
        var playCalls = 0
        var pauseCalls = 0
        val seekTargets = mutableListOf<Long>()
        var releaseCalls = 0

        override fun play() {
            playCalls += 1
        }

        override fun pause() {
            pauseCalls += 1
        }

        override fun seekTo(positionMs: Long) {
            seekTargets += positionMs
        }

        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun getAudioTracks(): List<AudioTrack> = emptyList()
        override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit

        override fun releaseBeforeNavigation(
            onReleased: () -> Unit,
            onReleaseFailed: (String) -> Unit,
        ) {
            releaseCalls += 1
            onReleased()
        }
    }

    private class MiniSessionHarness(
        val sessionController: PlayerSessionController,
        val engineController: TestEngineController,
    ) {
        lateinit var runtime: PlayerScreenRuntime
    }

    @Before
    fun setUp() {
        ensureTrackingProvidersRegistered()
        runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        profileId = ProfileRepository.activeProfileId
        tempFolder = Files.createTempDirectory("nuvio-unwatch-mini-test").toRealPath()
        downloadsDir = tempFolder.resolve("downloads").createDirectories()
        systemTrashDir = tempFolder.resolve("system-trash").createDirectories()
        trash = DesktopDownloadsTrash(
            downloadsDir = { downloadsDir },
            systemTrash = { file ->
                Files.move(file.toPath(), systemTrashDir.resolve(file.name))
                true
            },
        )

        previousWatchedPayload = WatchedStorage.loadPayload(profileId)
        previousProgressPayload = WatchProgressStorage.loadPayload(profileId)
        previousAutoPayload = AutoDownloadStorage.loadPayload(profileId)
        savedProviderPorts = TrackingProviderId.entries.map { id ->
            SavedPorts(
                id = id,
                auth = TrackingProviderRegistry.authProvider(id),
                scrobbler = TrackingProviderRegistry.scrobbler(id),
                historyWriter = TrackingProviderRegistry.historyWriter(id),
                watchedProvider = TrackingProviderRegistry.watchedProvider(id),
                progressProvider = TrackingProviderRegistry.progressProvider(id),
            )
        }
        recordedScrobbles.clear()
        installIsolatedTrackerPorts(connectedProviders = emptySet(), source = WatchProgressSource.NUVIO_SYNC)

        DesktopStorage.store("nuvio_auto_downloads").remove("auto_downloads_$profileId")
        resetAutoDownloadsLoadedProfile()
        previousAutoStarted = setAutoDownloadsStarted(true)

        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = TestDownloadsBackend()
        DownloadsRepository.ensureLoaded()
    }

    @After
    fun tearDown() {
        cancelAutoDownloadsPendingJobs()
        setAutoDownloadsStarted(previousAutoStarted)
        ActivePlayerSessionRegistry.setActive(false)
        runtimeScope.cancel()

        WatchedRepository.clearLocalState()
        WatchProgressRepository.clearLocalState()
        savedProviderPorts.forEach { saved ->
            replacePorts(
                id = saved.id,
                auth = saved.auth,
                scrobbler = saved.scrobbler,
                historyWriter = saved.historyWriter,
                watchedProvider = saved.watchedProvider,
                progressProvider = saved.progressProvider,
            )
        }
        WatchedRepository.activateSource(WatchProgressSource.NUVIO_SYNC)
        WatchProgressRepository.activateSource(WatchProgressSource.NUVIO_SYNC)

        val watchedStore = DesktopStorage.store("nuvio_watched")
        if (previousWatchedPayload == null) {
            watchedStore.remove("watched_$profileId")
        } else {
            WatchedStorage.savePayload(profileId, previousWatchedPayload!!)
        }
        val progressStore = DesktopStorage.store("nuvio_watch_progress")
        if (previousProgressPayload == null) {
            progressStore.remove("watch_progress_$profileId")
        } else {
            WatchProgressStorage.savePayload(profileId, previousProgressPayload!!)
        }
        val autoStore = DesktopStorage.store("nuvio_auto_downloads")
        if (previousAutoPayload == null) {
            autoStore.remove("auto_downloads_$profileId")
        } else {
            AutoDownloadStorage.savePayload(profileId, previousAutoPayload!!)
        }
        resetAutoDownloadsLoadedProfile()

        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        tempFolder.toFile().deleteRecursively()
    }

    @Test
    fun natural_finish_then_unwatch_then_closing_mini_player_keeps_unwatched_and_schedules_nothing() {
        autoDownloadsStore().setDeleteAfterWatchingMode(profileId, DeleteAfterWatchingMode.AllDownloads)
        val file = completeDownloadedEpisode(episodeNumber = 1)
        val item = downloadedItem(episodeNumber = 1)
        val harness = mountMiniPlayerSession(localFileUri = file.toUri().toString(), episodeNumber = 1)

        // Play to natural end in the mini player.
        deliverNaturalEnd(harness)
        awaitCondition("removal scheduled after natural finish") {
            AutoDownloads.removals.value.any { it.downloadId == item.id }
        }
        assertTrue(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
        assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))

        // Leave the ended mini player open; Episode details -> Mark as unwatched.
        compose.onNodeWithTag(MiniPlayerDockTestTag).assertIsDisplayed()
        WatchingActions.toggleEpisodeWatched(
            meta = showMeta,
            episode = episodes[0],
            isCurrentlyWatched = true,
        )
        compose.waitForIdle()

        assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
        assertNull(WatchProgressRepository.progressForVideo(videoId = episodes[0].id, parentMetaId = showId, seasonNumber = 1, episodeNumber = 1))
        assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))
        assertEquals(emptyList(), AutoDownloads.removals.value)

        // Close the ended mini player.
        val toastBeforeClose = NuvioToastController.currentToast.value?.id
        compose.onNodeWithContentDescription(text(Res.string.compose_player_close)).performClick()
        compose.waitForIdle()
        settleAsyncAutoDownloads()

        assertFalse(harness.sessionController.hasActiveSession)
        assertFalse(
            WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1),
            "Closing the ended mini player after Mark as unwatched must leave the episode unwatched",
        )
        assertNull(
            WatchProgressRepository.progressForVideo(videoId = episodes[0].id, parentMetaId = showId, seasonNumber = 1, episodeNumber = 1),
            "Closing the ended mini player after Mark as unwatched must not recreate completed progress",
        )
        assertFalse(
            WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1),
            "Closing the ended mini player after Mark as unwatched must not restore the locally-finished mark",
        )
        assertEquals(
            emptyList(),
            AutoDownloads.removals.value,
            "Closing the ended mini player after Mark as unwatched must not re-schedule removal",
        )
        assertEquals(
            toastBeforeClose,
            NuvioToastController.currentToast.value?.id,
            "Closing the ended mini player after Mark as unwatched must not show a scheduled-deletion toast",
        )
    }

    @Test
    fun natural_finish_then_keep_then_closing_mini_player_keeps_download_and_preserves_saved_schedule() {
        autoDownloadsStore().setDeleteAfterWatchingMode(profileId, DeleteAfterWatchingMode.AllDownloads)
        val file = completeDownloadedEpisode(episodeNumber = 1)
        val item = downloadedItem(episodeNumber = 1)
        val harness = mountMiniPlayerSession(localFileUri = file.toUri().toString(), episodeNumber = 1)

        deliverNaturalEnd(harness)
        awaitCondition("removal scheduled after natural finish") {
            AutoDownloads.removals.value.any { it.downloadId == item.id }
        }
        val originalDue = AutoDownloads.removals.value.single().dueAtEpochMs

        // Choose Keep while the ended mini player is still open.
        AutoDownloads.keepDownload(profileId, item.id)
        awaitCondition("Keep clears active removal and records kept download") {
            item.id in AutoDownloads.keptDownloadIds.value && AutoDownloads.removals.value.isEmpty()
        }

        // Close the ended mini player: Keep must still win and leave no active schedule.
        val toastBeforeClose = NuvioToastController.currentToast.value?.id
        compose.onNodeWithContentDescription(text(Res.string.compose_player_close)).performClick()
        compose.waitForIdle()
        settleAsyncAutoDownloads()

        assertFalse(harness.sessionController.hasActiveSession)
        assertEquals(emptyList(), AutoDownloads.removals.value)
        assertTrue(item.id in AutoDownloads.keptDownloadIds.value)
        assertEquals(toastBeforeClose, NuvioToastController.currentToast.value?.id)

        // Undoing Keep still restores the original pre-close due time.
        AutoDownloads.undoKeepDownload(profileId, item.id)
        awaitCondition("Undo Keep restores original schedule") {
            AutoDownloads.removals.value.any { it.downloadId == item.id }
        }
        assertEquals(originalDue, AutoDownloads.removals.value.single().dueAtEpochMs)
    }

    @Test
    fun unwatch_then_new_real_finish_schedules_again_and_removal_stays_trash_based() {
        installIsolatedTrackerPorts(
            connectedProviders = setOf(TrackingProviderId.TRAKT),
            source = WatchProgressSource.TRAKT,
        )
        autoDownloadsStore().setDeleteAfterWatchingMode(profileId, DeleteAfterWatchingMode.AllDownloads)
        val file = completeDownloadedEpisode(episodeNumber = 1)
        val item = downloadedItem(episodeNumber = 1)
        val harness = mountMiniPlayerSession(localFileUri = file.toUri().toString(), episodeNumber = 1)

        // First natural finish.
        deliverNaturalEnd(harness)
        awaitCondition("first finish schedules removal") {
            AutoDownloads.removals.value.any { it.downloadId == item.id }
        }
        assertEquals(1, completionScrobbles().size)

        // Mark as unwatched while the ended mini player remains open.
        WatchingActions.toggleEpisodeWatched(
            meta = showMeta,
            episode = episodes[0],
            isCurrentlyWatched = true,
        )
        compose.waitForIdle()
        assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
        assertEquals(emptyList(), AutoDownloads.removals.value)

        // Replay in the same open mini player to a genuinely new natural finish.
        compose.onNodeWithContentDescription(text(Res.string.detail_btn_play)).performClick()
        compose.runOnIdle {
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    isEnded = false,
                    positionMs = 15_000L,
                    durationMs = 2_473_826L,
                ),
            )
        }
        compose.waitForIdle()
        deliverNaturalEnd(harness)
        awaitCondition("new natural finish after unwatch schedules removal again") {
            AutoDownloads.removals.value.any { it.downloadId == item.id }
        }
        assertTrue(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
        assertEquals(
            2,
            completionScrobbles().size,
            "A genuinely new natural finish after unwatch should also send a fresh completion scrobble",
        )
        val secondDue = AutoDownloads.removals.value.single().dueAtEpochMs

        // Unwatch again, close the mini player, confirm no schedule, then open a brand-new playback and finish.
        WatchingActions.toggleEpisodeWatched(
            meta = showMeta,
            episode = episodes[0],
            isCurrentlyWatched = true,
        )
        compose.onNodeWithContentDescription(text(Res.string.compose_player_close)).performClick()
        compose.waitForIdle()
        settleAsyncAutoDownloads()
        assertFalse(harness.sessionController.hasActiveSession)
        assertEquals(emptyList(), AutoDownloads.removals.value)
        assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
        assertTrue(secondDue > 0L)

        // Reopen in a fresh session and play to natural end, then close.
        openSessionOnExistingContent(harness, localFileUri = file.toUri().toString(), episodeNumber = 1, launchId = 202L)
        deliverNaturalEnd(harness)
        awaitCondition("fresh session natural finish schedules removal") {
            AutoDownloads.removals.value.any { it.downloadId == item.id }
        }
        val thirdDue = AutoDownloads.removals.value.single().dueAtEpochMs
        compose.onNodeWithContentDescription(text(Res.string.compose_player_close)).performClick()
        compose.waitForIdle()
        settleAsyncAutoDownloads()
        assertEquals(listOf(AutoDownloadRemoval(item.id, null, thirdDue)), AutoDownloads.removals.value)

        // When the 24-hour hold expires, removal uses Trash only (never permanent deletion).
        val dueFeature = DeleteAfterWatching(
            store = autoDownloadsStore(),
            downloads = { DownloadsRepository.uiState.value.items },
            isWatchedToTheEnd = { true },
            isInPlayer = { false },
            hasLaterEpisode = { _, _ -> true },
            removeDownload = DownloadsRepository::removeWatchedAutoDownload,
            nowEpochMs = { thirdDue + 1_000L },
        )
        assertEquals(mapOf(item.id to AutoDownloadRemovalOutcome.Removed), dueFeature.runDue(profileId))
        assertFalse(file.exists(), "Removed download should leave the downloads folder")
        assertTrue(
            systemTrashDir.resolve(file.fileName.toString()).exists(),
            "Removed download must be moved to Trash, never permanently deleted",
        )
    }

    @Test
    fun normal_natural_finish_then_closing_mini_player_schedules_once_without_duplicate_toast_or_due_shift() {
        installIsolatedTrackerPorts(
            connectedProviders = setOf(TrackingProviderId.TRAKT),
            source = WatchProgressSource.TRAKT,
        )
        autoDownloadsStore().setDeleteAfterWatchingMode(profileId, DeleteAfterWatchingMode.AllDownloads)
        val file = completeDownloadedEpisode(episodeNumber = 1)
        val item = downloadedItem(episodeNumber = 1)
        val harness = mountMiniPlayerSession(localFileUri = file.toUri().toString(), episodeNumber = 1)

        deliverNaturalEnd(harness)
        awaitCondition("removal scheduled and toast shown after natural finish") {
            AutoDownloads.removals.value.any { it.downloadId == item.id } &&
                NuvioToastController.currentToast.value?.message?.contains("Pantheon") == true
        }
        val dueAfterFinish = AutoDownloads.removals.value.single().dueAtEpochMs
        val toastAfterFinish = NuvioToastController.currentToast.value?.id
        assertNotNull(toastAfterFinish)
        assertEquals(1, completionScrobbles().size)

        // Advance wall clock before closing so any duplicate scheduleRemoval would shift dueAtEpochMs.
        Thread.sleep(30)
        compose.onNodeWithContentDescription(text(Res.string.compose_player_close)).performClick()
        compose.waitForIdle()
        settleAsyncAutoDownloads()

        assertFalse(harness.sessionController.hasActiveSession)
        assertEquals(
            listOf(AutoDownloadRemoval(item.id, null, dueAfterFinish)),
            AutoDownloads.removals.value,
            "Closing after a normal natural finish must keep the single original schedule without resetting its due time",
        )
        assertEquals(
            toastAfterFinish,
            NuvioToastController.currentToast.value?.id,
            "Closing after a normal natural finish must not show a second scheduled-deletion toast",
        )
        assertEquals(1, completionScrobbles().size)
    }

    @Test
    fun paused_past_watched_threshold_then_unwatch_then_close_or_quit_stays_unwatched_and_unscheduled() {
        autoDownloadsStore().setDeleteAfterWatchingMode(profileId, DeleteAfterWatchingMode.AllDownloads)
        val file = completeDownloadedEpisode(episodeNumber = 1)
        val harness = mountMiniPlayerSession(localFileUri = file.toUri().toString(), episodeNumber = 1)

        // Play to 95% and pause (not ended, but >= 90% so pause marks it watched).
        compose.runOnIdle {
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    isEnded = false,
                    positionMs = 2_280_000L,
                    durationMs = 2_400_000L,
                ),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            harness.runtime.shouldPlay = false
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    isEnded = false,
                    positionMs = 2_280_000L,
                    durationMs = 2_400_000L,
                ),
            )
        }
        compose.waitForIdle()
        assertTrue(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))

        // User chooses Mark as unwatched while the mini player sits paused at 95%.
        WatchingActions.toggleEpisodeWatched(
            meta = showMeta,
            episode = episodes[0],
            isCurrentlyWatched = true,
        )
        compose.waitForIdle()
        assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
        assertEquals(emptyList(), AutoDownloads.removals.value)

        // Check quit flush first, unfreeze, then close the mini player.
        compose.runOnIdle {
            harness.runtime.stopPlaybackAndFlushForQuit()
            harness.runtime.frozenForQuit = false
        }
        compose.onNodeWithContentDescription(text(Res.string.compose_player_close)).performClick()
        compose.waitForIdle()
        settleAsyncAutoDownloads()

        assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
        assertNull(WatchProgressRepository.progressForVideo(videoId = episodes[0].id, parentMetaId = showId, seasonNumber = 1, episodeNumber = 1))
        assertEquals(emptyList(), AutoDownloads.removals.value)
    }

    @Test
    fun ended_mini_player_after_unwatch_next_episode_or_quit_keeps_episode_unwatched_and_unscheduled() {
        autoDownloadsStore().setDeleteAfterWatchingMode(profileId, DeleteAfterWatchingMode.AllDownloads)
        val file1 = completeDownloadedEpisode(episodeNumber = 1)
        completeDownloadedEpisode(episodeNumber = 2)
        val item1 = downloadedItem(episodeNumber = 1)
        val harness = mountMiniPlayerSession(localFileUri = file1.toUri().toString(), episodeNumber = 1)

        deliverNaturalEnd(harness)
        awaitCondition("removal scheduled for episode 1") {
            AutoDownloads.removals.value.any { it.downloadId == item1.id }
        }

        // Unwatch episode 1 while ended mini player stays open.
        WatchingActions.toggleEpisodeWatched(
            meta = showMeta,
            episode = episodes[0],
            isCurrentlyWatched = true,
        )
        compose.waitForIdle()
        assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
        assertEquals(emptyList(), AutoDownloads.removals.value)

        // Exercise quit flush and Next Episode from the ended mini player.
        compose.runOnIdle {
            harness.runtime.stopPlaybackAndFlushForQuit()
            harness.runtime.frozenForQuit = false
        }
        compose.onNodeWithContentDescription(text(Res.string.player_next_episode)).performClick()
        compose.waitForIdle()
        settleAsyncAutoDownloads()

        assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
        assertEquals(emptyList(), AutoDownloads.removals.value)
    }

    @Test
    fun downloaded_movie_finished_then_unwatched_then_closing_mini_player_stays_unwatched_and_unscheduled() {
        val movieId = "tt9913999"
        autoDownloadsStore().setDeleteAfterWatchingMode(profileId, DeleteAfterWatchingMode.AllDownloads)
        val movieFile = downloadsDir.resolve("Example.Movie.2025.mkv")
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = movieId,
            parentMetaId = movieId,
            parentMetaType = "movie",
            title = "Example Movie",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = testStream("https://example.com/movie.mkv"),
            autoDownloadRuleKey = null,
            userChoseReplacement = true,
        )
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        movieFile.writeBytes(bytes)
        finishDownload.getValue(null)(movieFile.toUri().toString(), bytes.size.toLong())
        val movieItem = DownloadsRepository.uiState.value.items.first { it.parentMetaId == movieId }

        val movieLaunch = PlayerLaunch(
            profileId = profileId,
            title = "Example Movie",
            sourceUrl = movieFile.toUri().toString(),
            providerName = "Downloaded",
            providerAddonId = null,
            streamTitle = "Example.Movie.2025.mkv",
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            contentType = "movie",
            videoId = movieId,
            parentMetaId = movieId,
            parentMetaType = "movie",
        )
        val movieMeta = MetaDetails(id = movieId, type = "movie", name = "Example Movie", videos = emptyList())
        val harness = mountMiniPlayerSessionForLaunch(movieLaunch, movieMeta)

        deliverNaturalEnd(harness)
        awaitCondition("movie removal scheduled after natural finish") {
            AutoDownloads.removals.value.any { it.downloadId == movieItem.id }
        }
        assertTrue(WatchedRepository.isWatched(id = movieId, type = "movie"))

        runBlocking {
            WatchingActions.togglePosterWatched(
                MetaPreview(
                    id = movieId,
                    type = "movie",
                    name = "Example Movie",
                    poster = null,
                ),
            )
        }
        compose.waitForIdle()
        assertFalse(WatchedRepository.isWatched(id = movieId, type = "movie"))
        assertEquals(emptyList(), AutoDownloads.removals.value)

        compose.onNodeWithContentDescription(text(Res.string.compose_player_close)).performClick()
        compose.waitForIdle()
        settleAsyncAutoDownloads()

        assertFalse(harness.sessionController.hasActiveSession)
        assertFalse(WatchedRepository.isWatched(id = movieId, type = "movie"))
        assertEquals(emptyList(), AutoDownloads.removals.value)
    }

    private fun completeDownloadedEpisode(episodeNumber: Int): Path {
        val file = downloadsDir.resolve("Pantheon.S01E0$episodeNumber.mkv")
        DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = episodes[episodeNumber - 1].id,
            parentMetaId = showId,
            parentMetaType = "series",
            title = showMeta.name,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = episodeNumber,
            episodeTitle = episodes[episodeNumber - 1].title,
            episodeThumbnail = null,
            stream = testStream("https://example.com/$episodeNumber.mkv"),
            autoDownloadRuleKey = null,
            userChoseReplacement = true,
        )
        val payloadBytes = ByteArray(1024) { (it % 251).toByte() }
        file.writeBytes(payloadBytes)
        finishDownload.getValue(episodeNumber)(file.toUri().toString(), payloadBytes.size.toLong())
        return file
    }

    private fun downloadedItem(episodeNumber: Int): DownloadItem =
        DownloadsRepository.uiState.value.items.first { it.episodeNumber == episodeNumber }

    private fun buildLaunch(localFileUri: String, episodeNumber: Int) = PlayerLaunch(
        profileId = profileId,
        title = showMeta.name,
        sourceUrl = localFileUri,
        providerName = "Downloaded",
        providerAddonId = null,
        streamTitle = "Pantheon.S01E0$episodeNumber.mkv",
        seasonNumber = 1,
        episodeNumber = episodeNumber,
        episodeTitle = episodes[episodeNumber - 1].title,
        contentType = "series",
        videoId = episodes[episodeNumber - 1].id,
        parentMetaId = showId,
        parentMetaType = "series",
    )

    private fun mountMiniPlayerSession(
        localFileUri: String,
        episodeNumber: Int,
    ): MiniSessionHarness = mountMiniPlayerSessionForLaunch(
        launch = buildLaunch(localFileUri, episodeNumber),
        meta = showMeta,
    )

    private fun mountMiniPlayerSessionForLaunch(
        launch: PlayerLaunch,
        meta: MetaDetails,
    ): MiniSessionHarness {
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val engineController = TestEngineController()
        sessionController.open(launch = launch, launchId = 101L)
        val harness = MiniSessionHarness(sessionController, engineController)

        compose.setContent {
            NuvioTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    val active = sessionController.activeSession
                    if (active != null) {
                        key(active.launchId) {
                            val guard = remember { PlayerBackReleaseGuard() }
                            val exitTarget = remember { PlayerExitTargetHolder() }
                            if (active.mode == PlayerPresentationMode.Mini) {
                                val dockState = sessionController.playbackState ?: MiniPlayerPlaybackState(
                                    title = active.launch.title,
                                    seasonNumber = active.launch.seasonNumber,
                                    episodeNumber = active.launch.episodeNumber,
                                    episodeTitle = active.launch.episodeTitle,
                                    videoId = active.launch.videoId,
                                    parentMetaId = active.launch.parentMetaId,
                                    parentMetaType = active.launch.parentMetaType,
                                    sourceUrl = active.launch.sourceUrl,
                                )
                                MiniPlayerDock(
                                    state = dockState,
                                    canPlayNextEpisode = sessionController.canPlayNextEpisode,
                                    onTogglePlayback = sessionController::togglePlayback,
                                    onSeekForward = sessionController::seekForward,
                                    onPlayNextEpisode = sessionController::playNextEpisode,
                                    onClose = { sessionController.closeActiveSession() },
                                    onReopenFullPlayer = { sessionController.restoreFullScreen() },
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                )
                            }
                            val args = PlayerScreenArgs(
                                profileId = active.launch.profileId,
                                title = active.launch.title,
                                sourceUrl = active.launch.sourceUrl,
                                sourceAudioUrl = null,
                                sourceHeaders = emptyMap(),
                                sourceResponseHeaders = emptyMap(),
                                streamType = null,
                                providerName = active.launch.providerName,
                                streamTitle = active.launch.streamTitle,
                                streamSubtitle = null,
                                initialBingeGroup = null,
                                pauseDescription = null,
                                onBack = { releaseBeforeBack ->
                                    guard.request(
                                        canStart = { sessionController.activeSession?.launchId == active.launchId },
                                        releaseBeforeBack = releaseBeforeBack,
                                        beforePop = {},
                                        pop = { sessionController.clearSession(active.launchId) },
                                    )
                                },
                                isMinimized = active.mode == PlayerPresentationMode.Mini,
                                onMinimize = { sessionController.minimize() },
                                onRestoreMiniPlayer = { sessionController.restoreFullScreen() },
                                onSessionStateChanged = { state ->
                                    sessionController.updatePlaybackState(active.launchId, state)
                                },
                                onSessionControlsChanged = { controls ->
                                    sessionController.bindControls(active.launchId, controls)
                                },
                                onExitTarget = { exitTarget.target = it },
                                onOpenInExternalPlayer = null,
                                onOpenExternalUrl = null,
                                modifier = Modifier,
                                logo = null,
                                poster = null,
                                background = null,
                                seasonNumber = active.launch.seasonNumber,
                                episodeNumber = active.launch.episodeNumber,
                                episodeTitle = active.launch.episodeTitle,
                                episodeThumbnail = null,
                                contentType = active.launch.contentType,
                                videoId = active.launch.videoId,
                                parentMetaId = active.launch.parentMetaId,
                                parentMetaType = active.launch.parentMetaType,
                                providerAddonId = active.launch.providerAddonId,
                                torrentInfoHash = null,
                                torrentFileIdx = null,
                                torrentFilename = null,
                                torrentTrackers = emptyList(),
                                initialPositionMs = 0L,
                                initialProgressFraction = null,
                                launchId = active.launchId,
                            )
                            val runtime = remember {
                                PlayerScreenRuntime(
                                    args = args,
                                    episodePlaybackOffline = { false },
                                ).apply {
                                    scope = runtimeScope
                                    playerSettingsUiState = PlayerSettingsUiState(
                                        skipIntroEnabled = false,
                                        streamAutoPlayNextEpisodeEnabled = false,
                                        streamReuseLastLinkEnabled = false,
                                    )
                                    metaUiState = MetaDetailsUiState(meta = meta)
                                    playerMeta = meta
                                    playerMetaVideos = meta.videos
                                    verifyEpisodeDownload = { true }
                                    playableDownloadedUri = { it.localFileUri }
                                    prepareDownloadedSubtitles = { PreparedStartupSubtitles() }
                                    playerController = engineController
                                    playerLifecycleController = engineController
                                    playerControllerSourceUrl = args.sourceUrl
                                }
                            }
                            runtime.args = args
                            harness.runtime = runtime
                            DisposableEffect(Unit) { onDispose {} }
                            runtime.BindPlayerRuntimeEffects()
                            runtime.BindPlayerSessionBridge()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        prepareMinimizedActivePlayback(harness)
        return harness
    }

    private fun openSessionOnExistingContent(
        harness: MiniSessionHarness,
        localFileUri: String,
        episodeNumber: Int,
        launchId: Long,
    ) {
        compose.runOnIdle {
            harness.sessionController.open(
                launch = buildLaunch(localFileUri, episodeNumber),
                launchId = launchId,
            )
        }
        compose.waitForIdle()
        prepareMinimizedActivePlayback(harness)
    }

    private fun prepareMinimizedActivePlayback(harness: MiniSessionHarness) {
        compose.runOnIdle {
            harness.runtime.playerController = harness.engineController
            harness.runtime.playerLifecycleController = harness.engineController
            harness.runtime.playerControllerSourceUrl = harness.runtime.activeSourceUrl
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    isEnded = false,
                    positionMs = 600_000L,
                    durationMs = 2_473_826L,
                ),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            harness.runtime.requestMinimize()
        }
        compose.waitForIdle()
        assertTrue(harness.sessionController.isMinimized)
    }

    private fun deliverNaturalEnd(harness: MiniSessionHarness) {
        compose.runOnIdle {
            harness.runtime.shouldPlay = false
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    isEnded = true,
                    positionMs = 2_472_999L,
                    durationMs = 2_473_826L,
                ),
            )
        }
        compose.waitForIdle()
    }

    private fun completionScrobbles(): List<RecordedScrobble> =
        recordedScrobbles.filter { it.action == TrackingScrobbleAction.STOP && it.event.progressPercent >= 80.0 }

    private fun text(resource: StringResource): String = runBlocking { getString(resource) }

    private fun awaitCondition(description: String, timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (predicate()) return
            Thread.sleep(15)
        }
        compose.waitForIdle()
        assertTrue(predicate(), "Timed out waiting for: $description")
    }

    private fun settleAsyncAutoDownloads() {
        Thread.sleep(150)
        compose.waitForIdle()
    }

    private fun installIsolatedTrackerPorts(
        connectedProviders: Set<TrackingProviderId>,
        source: WatchProgressSource,
    ) {
        TrackingProviderId.entries.forEach { id ->
            replacePorts(
                id = id,
                auth = object : TrackingAuthProvider {
                    override val descriptor = TrackingProviderDescriptor(
                        id = id,
                        displayName = id.displayName,
                        capabilities = setOf(
                            TrackingCapability.AUTHENTICATION,
                            TrackingCapability.WATCHED_READ,
                            TrackingCapability.WATCHED_WRITE,
                            TrackingCapability.PROGRESS_READ,
                            TrackingCapability.PROGRESS_WRITE,
                            TrackingCapability.SCROBBLE,
                        ),
                    )
                    override val isAuthenticated: StateFlow<Boolean> = MutableStateFlow(id in connectedProviders)
                    override fun ensureLoaded() = Unit
                    override fun onProfileChanged() = Unit
                    override fun clearLocalState() = Unit
                    override fun removeStoredProfile(profileId: Int) = Unit
                },
                scrobbler = object : TrackingScrobbler {
                    override val providerId: TrackingProviderId = id
                    override suspend fun scrobble(
                        profileId: Int,
                        action: TrackingScrobbleAction,
                        event: TrackingScrobbleEvent,
                    ) {
                        recordedScrobbles += RecordedScrobble(id, action, event)
                    }
                },
                historyWriter = object : TrackingHistoryWriter {
                    override val providerId: TrackingProviderId = id
                    override suspend fun addToHistory(
                        profileId: Int,
                        items: Collection<TrackingHistoryItem>,
                    ): TrackingMutationResult = TrackingMutationResult(attemptedCount = items.size)

                    override suspend fun removeFromHistory(
                        profileId: Int,
                        items: Collection<TrackingMediaReference>,
                    ): TrackingMutationResult = TrackingMutationResult(attemptedCount = items.size)
                },
                watchedProvider = object : TrackingWatchedProvider {
                    override val providerId: TrackingProviderId = id
                    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> = emptyList()
                    override suspend fun push(profileId: Int, items: Collection<WatchedItem>) = Unit
                    override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) = Unit
                },
                progressProvider = object : TrackingProgressProvider {
                    override val providerId: TrackingProviderId = id
                    override val changes: Flow<Unit> = emptyFlow()
                    override val ownsCompletedHistoryProjection: Boolean =
                        id == TrackingProviderId.TRAKT || id == TrackingProviderId.MDBLIST
                    override fun ensureLoaded() = Unit
                    override suspend fun refresh(force: Boolean, sourceChanged: Boolean) = Unit
                    override fun snapshot(): TrackingProgressSnapshot = TrackingProgressSnapshot(
                        entries = emptyList(),
                        hasLoadedRemoteProgress = true,
                    )
                    override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) = Unit
                },
            )
        }
        WatchedRepository.clearLocalState()
        WatchProgressRepository.clearLocalState()
        DesktopStorage.store("nuvio_watched").remove("watched_$profileId")
        DesktopStorage.store("nuvio_watch_progress").remove("watch_progress_$profileId")
        WatchedRepository.activateSource(source)
        WatchProgressRepository.activateSource(source)
    }

    @Suppress("UNCHECKED_CAST")
    private fun replacePorts(
        id: TrackingProviderId,
        auth: TrackingAuthProvider?,
        scrobbler: TrackingScrobbler?,
        historyWriter: TrackingHistoryWriter?,
        watchedProvider: TrackingWatchedProvider?,
        progressProvider: TrackingProgressProvider?,
    ) {
        val registry = TrackingProviderRegistry
        val lock = registry.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(registry)
        if (auth != null) {
            registry.register(auth)
        } else {
            val (removedAuth, previousJob) = synchronized(lock) {
                val authProviders = registry.javaClass.getDeclaredField("authProviders").apply { isAccessible = true }
                    .get(registry) as MutableMap<TrackingProviderId, TrackingAuthProvider>
                val profileStores = registry.javaClass.getDeclaredField("profileStores").apply { isAccessible = true }
                    .get(registry) as MutableSet<TrackingProfileStore>
                val jobs = registry.javaClass.getDeclaredField("authObservationJobs").apply { isAccessible = true }
                    .get(registry) as MutableMap<TrackingProviderId, Job>
                val removed = authProviders.remove(id)
                if (removed != null) profileStores.remove(removed)
                removed to jobs.remove(id)
            }
            previousJob?.cancel()
            if (removedAuth != null) {
                val connected = registry.javaClass.getDeclaredField("_connectedProviderIds").apply { isAccessible = true }
                    .get(registry) as MutableStateFlow<Set<TrackingProviderId>>
                connected.update { it - id }
            }
        }
        if (scrobbler != null) registry.registerScrobbler(scrobbler) else removeRegistryEntry(registry, lock, "scrobblers", id)
        if (historyWriter != null) registry.registerHistoryWriter(historyWriter) else removeRegistryEntry(registry, lock, "historyWriters", id)
        if (watchedProvider != null) registry.registerWatchedProvider(watchedProvider) else removeRegistryEntry(registry, lock, "watchedProviders", id)
        if (progressProvider != null) registry.registerProgressProvider(progressProvider) else removeRegistryEntry(registry, lock, "progressProviders", id)
    }

    @Suppress("UNCHECKED_CAST")
    private fun removeRegistryEntry(
        registry: TrackingProviderRegistry,
        lock: Any,
        fieldName: String,
        id: TrackingProviderId,
    ) {
        synchronized(lock) {
            val map = registry.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(registry)
            (map as MutableMap<TrackingProviderId, Any?>).remove(id)
        }
    }

    private fun autoDownloadsStore(): AutoDownloadStore =
        AutoDownloads.javaClass.getDeclaredField("store").let { field ->
            field.isAccessible = true
            field.get(AutoDownloads) as AutoDownloadStore
        }

    private fun resetAutoDownloadsLoadedProfile() {
        val store = autoDownloadsStore()
        store.javaClass.getDeclaredField("loadedProfileId").let { field ->
            field.isAccessible = true
            field.set(store, null)
        }
    }

    private fun setAutoDownloadsStarted(value: Boolean): Boolean {
        val field = AutoDownloads.javaClass.getDeclaredField("started").apply { isAccessible = true }
        return when (val raw = field.get(AutoDownloads)) {
            is kotlinx.atomicfu.AtomicBoolean -> raw.getAndSet(value)
            is java.util.concurrent.atomic.AtomicBoolean -> raw.getAndSet(value)
            is Boolean -> raw.also { field.setBoolean(AutoDownloads, value) }
            is Int -> (raw != 0).also { field.setInt(AutoDownloads, if (value) 1 else 0) }
            else -> error("Unexpected started field: ${raw?.javaClass}")
        }
    }

    private fun cancelAutoDownloadsPendingJobs() {
        val scope = AutoDownloads.javaClass.getDeclaredField("scope").let { field ->
            field.isAccessible = true
            field.get(AutoDownloads) as CoroutineScope
        }
        val job = scope.coroutineContext[Job] ?: return
        job.cancelChildren()
        runBlocking {
            job.children.toList().forEach { child -> runCatching { child.join() } }
        }
    }
}
