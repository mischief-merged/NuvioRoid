package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.MainAppContent
import com.nuvio.app.PlayerSessionHost
import com.nuvio.app.core.i18n.localizedPlayLabel
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.components.DetailActions
import com.nuvio.app.features.details.components.SeasonDownloadButton
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloadRemoval
import com.nuvio.app.features.downloads.AutoDownloadRule
import com.nuvio.app.features.downloads.AutoDownloadScope
import com.nuvio.app.features.downloads.AutoDownloadStatus
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsUiState
import com.nuvio.app.features.downloads.SeasonBatchDownloads
import com.nuvio.app.features.downloads.SeasonBatchDownloader
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsStorage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.player.desktop.NativePlayerLifecycleCoordinator
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.performDesktopQuit
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.plugins.runtime.PluginRuntime
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.trailer.TrailerPlaybackState
import com.nuvio.app.features.trailer.TrailerPlayer
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadsRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.SettingsPageRoute
import com.nuvio.app.navigation.TabsRoute
import com.nuvio.app.openPlayerExitTarget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.compose_action_pause
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_player_close
import nuvio.composeapp.generated.resources.compose_player_seek_forward_10
import nuvio.composeapp.generated.resources.compose_settings_page_root
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import nuvio.composeapp.generated.resources.compose_settings_root_switch_profile_title
import nuvio.composeapp.generated.resources.detail_btn_play
import nuvio.composeapp.generated.resources.details_actions_menu_label
import nuvio.composeapp.generated.resources.downloads_season_button
import nuvio.composeapp.generated.resources.downloads_season_dialog_title
import nuvio.composeapp.generated.resources.downloads_season_start
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.hero_remove_from_library
import nuvio.composeapp.generated.resources.player_replace_playback_confirm
import nuvio.composeapp.generated.resources.player_replace_playback_keep
import nuvio.composeapp.generated.resources.player_replace_playback_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MiniPlayerSessionTest {
    @get:Rule
    val compose = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(testDispatcher + SupervisorJob())
    private val testSeriesId = "tt9991109"
    private val browsedSeriesId = "tt9992202"
    private val appSeriesId = "tt9993303"
    private val appBrowsedSeriesId = "tt9994404"
    private val testDialogProfileId = 9_900_099
    private val episodes = (1..4).map { number ->
        MetaVideo(
            id = "$testSeriesId:1:$number",
            title = "Episode $number",
            season = 1,
            episode = number,
        )
    }

    private fun text(resource: StringResource, vararg args: Any): String = runBlocking { getString(resource, *args) }

    @After
    fun tearDown() {
        WatchProgressRepository.ensureLoaded()
        episodes.forEach { WatchProgressRepository.clearProgress(it.id) }
        WatchProgressRepository.clearProgress(testSeriesId)
        WatchProgressRepository.clearProgress(browsedSeriesId)
        WatchProgressRepository.removeProgress(contentId = appSeriesId)
        WatchProgressRepository.removeProgress(contentId = appBrowsedSeriesId)
        WatchProgressRepository.clearProgress("$appSeriesId:1:1")
        WatchProgressRepository.clearProgress("$appSeriesId:1:2")
        WatchProgressRepository.clearProgress(appSeriesId)
        WatchProgressRepository.clearProgress(appBrowsedSeriesId)
        WatchProgressRepository.clearLocalState()
        LibraryRepository.remove(testSeriesId)
        LibraryRepository.remove(browsedSeriesId)
        LibraryRepository.remove(appSeriesId)
        LibraryRepository.remove(appBrowsedSeriesId)
        MetaDetailsRepository.removeCachedMetaForTest("series", testSeriesId)
        MetaDetailsRepository.removeCachedMetaForTest("series", browsedSeriesId)
        MetaDetailsRepository.removeCachedMetaForTest("series", appSeriesId)
        MetaDetailsRepository.removeCachedMetaForTest("series", appBrowsedSeriesId)
        SeasonBatchDownloads.startOverrideForTest = null
        StreamsRepository.clear()
        SeasonDownloadDefaultsStorage.savePayload(testDialogProfileId, "")
        TrackingScrobbleCoordinator.scrobblersOverrideForTest = null
        sourceHealthTickObserverForTest = null
        PlayerStreamsRepository.clearAll()
        ActivePlayerSessionRegistry.setActive(false)
        scope.cancel()
    }

    @Test
    fun minimize_button_shrinks_active_session_and_keeps_same_runtime_alive() {
        val harness = startSessionHarness()
        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 420_000L,
                    durationMs = 1_500_000L,
                ),
            )
        }

        val initialRuntime = harness.runtime
        compose.runOnIdle {
            harness.runtime.requestMinimize()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isMinimized)
            assertFalse(harness.sessionController.isFullScreen)
            assertSame(initialRuntime, harness.runtime)
            assertEquals(0, harness.controller.releaseCalls)
            assertEquals(420_000L, harness.sessionController.playbackState?.positionMs)
            assertTrue(ActivePlayerSessionRegistry.isActive.value)
        }
    }

    @Test
    fun back_while_playing_minimizes_instead_of_closing_and_navigates_to_exit_target() {
        val harness = startSessionHarness()
        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 180_000L,
                    durationMs = 1_200_000L,
                ),
            )
            harness.runtime.requestBack(
                PlayerExitTarget.Show(type = "series", id = "tt2020202", title = "Other Show"),
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isMinimized)
            assertEquals(0, harness.controller.releaseCalls)
            assertEquals(
                DetailRoute(type = "series", id = "tt2020202", title = "Other Show"),
                harness.navigator.currentRoute,
            )
        }
    }

    @Test
    fun back_when_paused_unloaded_ended_or_errored_closes_instead_of_minimizing() {
        val harness = startSessionHarness()

        fun reopenForCase(launchId: Long) {
            compose.runOnIdle {
                harness.sessionController.open(
                    launch = sampleSeriesLaunch(),
                    launchId = launchId,
                )
            }
            compose.waitForIdle()
            compose.runOnIdle {
                harness.runtime.playerController = harness.controller
                harness.runtime.playerLifecycleController = harness.controller
                harness.runtime.playerControllerSourceUrl = harness.runtime.activeSourceUrl
            }
        }

        // 1. Not-yet-loaded initial opening state: requestBack closes and releases instead of minimizing.
        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = false
            harness.runtime.shouldPlay = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(isLoading = true, isPlaying = false),
            )
            harness.runtime.requestBack()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(harness.sessionController.hasActiveSession, "not-yet-loaded Back closes session")
            assertEquals(1, harness.controller.releaseCalls)
        }

        // 2. Paused after load: dispatchSystemBack -> requestBack closes and releases instead of minimizing.
        reopenForCase(launchId = 102L)
        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.shouldPlay = false
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    positionMs = 150_000L,
                    durationMs = 1_200_000L,
                ),
            )
            harness.sessionController.dispatchSystemBack()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(harness.sessionController.hasActiveSession, "paused Back closes session")
            assertEquals(2, harness.controller.releaseCalls)
        }

        // 3. Ended playback: requestBack closes and releases instead of minimizing.
        reopenForCase(launchId = 103L)
        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.shouldPlay = false
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    isEnded = true,
                    positionMs = 1_200_000L,
                    durationMs = 1_200_000L,
                ),
            )
            harness.runtime.requestBack()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(harness.sessionController.hasActiveSession, "ended Back closes session")
            assertEquals(3, harness.controller.releaseCalls)
        }

        // 4. Playback error: requestBack closes and releases even if snapshot still reported playing.
        reopenForCase(launchId = 104L)
        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.shouldPlay = true
            harness.runtime.errorMessage = "Stream failed to decode"
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 60_000L,
                    durationMs = 1_200_000L,
                ),
            )
            harness.runtime.requestBack()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(harness.sessionController.hasActiveSession, "errored Back closes session")
            assertEquals(4, harness.controller.releaseCalls)
        }
    }

    @Test
    fun explicit_close_while_playing_flushes_progress_releases_player_and_clears_session() {
        val harness = startSessionHarness()
        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 600_000L,
                    durationMs = 1_800_000L,
                ),
            )
            harness.runtime.requestClose()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, harness.controller.releaseCalls)
            assertFalse(harness.sessionController.hasActiveSession)
            assertNull(harness.sessionController.playbackState)
            assertFalse(ActivePlayerSessionRegistry.isActive.value)
            assertEquals(
                600_000L,
                WatchProgressRepository.progressForVideo(episodes[0].id)?.lastPositionMs,
            )
        }
    }

    @Test
    fun navigating_main_window_routes_while_minimized_keeps_playback_running() {
        val harness = startSessionHarness()
        val initialRuntime = harness.runtime

        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 240_000L,
                    durationMs = 1_200_000L,
                ),
            )
            harness.sessionController.dispatchSystemBack()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isMinimized)
            harness.navigator.navigate(DetailRoute(type = "series", id = "tt5555", title = "Another Show"))
            harness.navigator.navigate(DownloadsRoute("Downloads"))
            harness.navigator.navigate(SettingsPageRoute(pageName = "Playback", title = "Playback"))
            harness.navigator.popBackStack()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(DownloadsRoute("Downloads"), harness.navigator.currentRoute)
            assertTrue(harness.sessionController.isMinimized)
            assertSame(initialRuntime, harness.runtime)
            assertEquals(0, harness.controller.releaseCalls)
            assertEquals(0, harness.disposedCount)
        }
    }

    @Test
    fun watch_progress_saving_continues_while_minimized() {
        val harness = startSessionHarness()

        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 120_000L,
                    durationMs = 1_200_000L,
                ),
            )
            harness.runtime.requestMinimize()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isMinimized)
            assertEquals(
                120_000L,
                WatchProgressRepository.progressForVideo(episodes[0].id)?.lastPositionMs,
            )

            // Simulate the periodic persist window elapsing while minimized and playing.
            harness.runtime.lastProgressPersistEpochMs = 0L
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 310_000L,
                    durationMs = 1_200_000L,
                ),
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(
                310_000L,
                WatchProgressRepository.progressForVideo(episodes[0].id)?.lastPositionMs,
            )
            assertEquals(310_000L, harness.sessionController.playbackState?.positionMs)

            // Pausing while minimized also flushes latest progress immediately.
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    positionMs = 365_000L,
                    durationMs = 1_200_000L,
                ),
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(
                365_000L,
                WatchProgressRepository.progressForVideo(episodes[0].id)?.lastPositionMs,
            )
        }
    }

    @Test
    fun next_episode_autoplay_triggers_and_updates_session_metadata_while_minimized() {
        var requestedEpisodeKey: String? = null
        PlayerStreamsRepository.episodeStreamsProviderForTest = { type, videoId, season, episode ->
            requestedEpisodeKey = "$type:$videoId:$season:$episode"
            StreamsUiState(
                groups = listOf(
                    AddonStreamGroup(
                        addonName = "Test",
                        addonId = "test",
                        streams = listOf(
                            StreamItem(
                                name = "Other Release 1080p",
                                url = "https://example.com/episode-2-other.mp4",
                                addonName = "Test",
                                addonId = "test",
                                behaviorHints = StreamBehaviorHints(bingeGroup = "release-other"),
                            ),
                            StreamItem(
                                name = "Preferred Pack 1080p",
                                url = "https://example.com/episode-2-preferred.mp4",
                                addonName = "Test",
                                addonId = "test",
                                behaviorHints = StreamBehaviorHints(bingeGroup = "release-pack"),
                            ),
                        ),
                        isLoading = false,
                    ),
                ),
                isAnyLoading = false,
            )
        }

        val harness = startSessionHarness(initialBingeGroup = "release-pack")

        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 300_000L,
                    durationMs = 1_200_000L,
                ),
            )
            harness.runtime.requestMinimize()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isMinimized)
            // Cross the next-episode threshold while minimized so BindPlayerRuntimeEffects starts autoplay.
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 1_190_000L,
                    durationMs = 1_200_000L,
                ),
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(2, harness.runtime.nextEpisodeInfo?.episode)
            assertTrue(harness.runtime.nextEpisodeAutoPlaySearching)

            // Let the autoplay coroutine itself query episode streams, pick the matching bingeGroup
            // source, and enter the 3-second countdown without any manual switch call.
            testDispatcher.scheduler.runCurrent()
            assertEquals("series:${episodes[1].id}:1:2", requestedEpisodeKey)
            assertFalse(harness.runtime.nextEpisodeAutoPlaySearching)
            assertEquals("Preferred Pack 1080p", harness.runtime.nextEpisodeAutoPlaySourceName)
            assertEquals(3, harness.runtime.nextEpisodeAutoPlayCountdown)

            // Advance through the 3-second autoplay countdown so the job switches to Episode 2 itself.
            testDispatcher.scheduler.advanceTimeBy(3_100L)
            testDispatcher.scheduler.runCurrent()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isMinimized)
            assertEquals("https://example.com/episode-2-preferred.mp4", harness.runtime.activeSourceUrl)
            assertEquals(1, harness.sessionController.playbackState?.seasonNumber)
            assertEquals(2, harness.sessionController.playbackState?.episodeNumber)
            assertEquals("Episode 2", harness.sessionController.playbackState?.episodeTitle)
            assertEquals(episodes[1].id, harness.sessionController.playbackState?.videoId)
            assertEquals(
                "https://example.com/episode-2-preferred.mp4",
                harness.sessionController.playbackState?.sourceUrl,
            )
        }
    }

    @Test
    fun reopening_from_mini_player_keeps_same_runtime_controller_and_position_without_reload() {
        val harness = startSessionHarness()
        val initialRuntime = harness.runtime
        val initialLaunchId = harness.sessionController.activeSession?.launchId

        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 735_000L,
                    durationMs = 1_800_000L,
                ),
            )
            harness.runtime.requestMinimize()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isMinimized)
            harness.navigator.navigate(DetailRoute(type = "series", id = "tt8888", title = "Browsed Show"))
            harness.navigator.navigate(DownloadsRoute("Downloads"))
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 748_000L,
                    durationMs = 1_800_000L,
                ),
            )
            harness.runtime.handlePlayerControlsEvent("restoreMiniPlayer", 0.0)
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isFullScreen)
            assertFalse(harness.sessionController.isMinimized)
            assertEquals(initialLaunchId, harness.sessionController.activeSession?.launchId)
            assertSame(initialRuntime, harness.runtime)
            assertSame(harness.controller, harness.runtime.playerController)
            assertEquals(748_000L, harness.runtime.playbackSnapshot.positionMs)
            assertEquals(0, harness.controller.releaseCalls)
            assertEquals(0, harness.disposedCount)
        }
    }

    @Test
    fun playing_same_episode_while_minimized_reopens_and_resumes_without_reload() {
        val harness = startSessionHarness()
        val initialRuntime = harness.runtime

        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.switchToEpisodeStream(
                StreamItem(url = "https://example.com/episode-2.mp4", addonName = "Test", addonId = "test"),
                episodes[1],
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            harness.runtime.playerController = harness.controller
            harness.runtime.playerLifecycleController = harness.controller
            harness.runtime.playerControllerSourceUrl = harness.runtime.activeSourceUrl
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    positionMs = 92_000L,
                    durationMs = 1_200_000L,
                ),
            )
            harness.runtime.requestMinimize()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isMinimized)
            assertFalse(
                harness.sessionController.reopenIfMatching(
                    videoId = episodes[0].id,
                    parentMetaId = testSeriesId,
                    seasonNumber = 1,
                    episodeNumber = 1,
                ),
                "finished episode 1 does not match active episode 2",
            )
            assertTrue(
                harness.sessionController.reopenIfMatching(
                    videoId = episodes[1].id,
                    parentMetaId = testSeriesId,
                    seasonNumber = 1,
                    episodeNumber = 2,
                ),
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isFullScreen)
            assertSame(initialRuntime, harness.runtime)
            assertEquals(92_000L, harness.runtime.playbackSnapshot.positionMs)
            assertEquals(1, harness.controller.playCalls)
            assertEquals(0, harness.controller.releaseCalls)
        }
    }

    @Test
    fun picture_in_picture_frees_main_window_and_restores_full_player_without_reload() {
        var exitPipCalls = 0
        val harness = startSessionHarness(
            onExitPictureInPicture = { exitPipCalls += 1 },
        )
        val initialRuntime = harness.runtime
        val initialLaunchId = harness.sessionController.activeSession?.launchId

        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 510_000L,
                    durationMs = 1_800_000L,
                ),
            )
            assertTrue(harness.sessionController.setPictureInPicture(true))
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isPictureInPicture)
            assertFalse(harness.sessionController.isFullScreen)
            assertFalse(harness.sessionController.isMinimized)
            assertTrue(harness.sessionController.hasActiveSession)
            assertTrue(ActivePlayerSessionRegistry.isActive.value)

            // Main window navigation works freely while video floats in PiP.
            harness.navigator.navigate(DetailRoute(type = "series", id = "tt313131", title = "Another Show"))
            harness.navigator.navigate(DownloadsRoute("Downloads"))

            // Watch progress continues saving during PiP.
            harness.runtime.lastProgressPersistEpochMs = 0L
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 545_000L,
                    durationMs = 1_800_000L,
                ),
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(DownloadsRoute("Downloads"), harness.navigator.currentRoute)
            assertEquals(
                545_000L,
                WatchProgressRepository.progressForVideo(episodes[0].id)?.lastPositionMs,
            )
            assertSame(initialRuntime, harness.runtime)
            assertEquals(0, harness.controller.releaseCalls)
            assertEquals(0, harness.disposedCount)

            // Closing/restoring from the PiP window returns to Full mode at the same position.
            assertTrue(harness.sessionController.setPictureInPicture(false))
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isFullScreen)
            assertFalse(harness.sessionController.isPictureInPicture)
            assertEquals(initialLaunchId, harness.sessionController.activeSession?.launchId)
            assertSame(initialRuntime, harness.runtime)
            assertEquals(545_000L, harness.runtime.playbackSnapshot.positionMs)
            assertEquals(0, exitPipCalls)

            // Re-enter PiP and reopen the same episode from the main window: exits PiP cleanly.
            assertTrue(harness.sessionController.setPictureInPicture(true))
            assertTrue(
                harness.sessionController.reopenIfMatching(
                    videoId = episodes[0].id,
                    parentMetaId = testSeriesId,
                    seasonNumber = 1,
                    episodeNumber = 1,
                ),
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isFullScreen)
            assertFalse(harness.sessionController.isPictureInPicture)
            assertEquals(1, exitPipCalls)
            assertEquals(0, harness.controller.releaseCalls)
        }
    }

    @Test
    fun starting_another_title_during_picture_in_picture_asks_before_replacing() {
        val harness = startSessionHarness()
        val events = mutableListOf<String>()

        compose.runOnIdle {
            harness.runtime.initialLoadCompleted = true
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 390_000L,
                    durationMs = 1_500_000L,
                ),
            )
            harness.sessionController.setPictureInPicture(true)
            harness.sessionController.launchOrAskToReplace(
                incomingTitle = "Different Show",
                videoId = "tt7777777:1:1",
                parentMetaId = "tt7777777",
                seasonNumber = 1,
                episodeNumber = 1,
            ) {
                events += "open-replacement"
            }
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isPictureInPicture)
            assertEquals("Series (S1 E1)", harness.sessionController.pendingReplacement?.currentTitle)
            assertEquals("Different Show (S1 E1)", harness.sessionController.pendingReplacement?.incomingTitle)
            assertEquals(emptyList(), events)
            assertEquals(0, harness.controller.releaseCalls)

            harness.sessionController.confirmPendingReplacement()
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(1, harness.controller.releaseCalls)
            assertEquals(listOf("open-replacement"), events)
            assertEquals(
                390_000L,
                WatchProgressRepository.progressForVideo(episodes[0].id)?.lastPositionMs,
            )
        }
    }

    @Test
    fun native_session_host_browse_season_download_library_save_reopen_creates_none_and_close_disposes_one() {
        val backend = CountingNativePlayerBackend()
        val hooks = backend.toDesktopHooks()
        val scrobbles = java.util.Collections.synchronizedList(mutableListOf<Pair<TrackingScrobbleAction, Int>>())
        val fakeScrobbler = object : TrackingScrobbler {
            override val providerId: TrackingProviderId = TrackingProviderId.TRAKT
            override suspend fun scrobble(
                profileId: Int,
                action: TrackingScrobbleAction,
                event: TrackingScrobbleEvent,
            ) {
                scrobbles += action to event.progressPercent.toInt()
            }
        }
        TrackingScrobbleCoordinator.scrobblersOverrideForTest = { listOf(fakeScrobbler) }

        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val navigator = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
        val browsedMeta = MetaDetails(
            id = browsedSeriesId,
            type = "series",
            name = "Browsed Show",
            videos = listOf(
                MetaVideo(id = "$browsedSeriesId:1:1", title = "Ep 1", released = "2024-01-01T00:00:00Z", season = 1, episode = 1, runtime = 45),
                MetaVideo(id = "$browsedSeriesId:1:2", title = "Ep 2", released = "2024-01-08T00:00:00Z", season = 1, episode = 2, runtime = 45),
            ),
        )
        val queuedSeasonUrls = mutableListOf<String>()
        val fakeDownloadsState = MutableStateFlow(DownloadsUiState())
        val seasonDownloader = SeasonBatchDownloader(
            scope = scope,
            downloads = fakeDownloadsState,
            fetchStreams = { batchEp ->
                listOf(
                    StreamItem(
                        name = "1080p",
                        url = "https://example.com/dl-${batchEp.episodeNumber}.mp4",
                        addonName = "Test",
                        addonId = "test",
                    ),
                )
            },
            prepareStream = { stream, _ -> stream },
            enqueue = { req, batchEp, stream ->
                queuedSeasonUrls += stream.url.orEmpty()
                val added = DownloadItem(
                    id = "dl-${batchEp.episodeNumber}",
                    contentType = req.contentType,
                    parentMetaId = req.parentMetaId,
                    parentMetaType = req.parentMetaType,
                    videoId = batchEp.videoId,
                    title = "${req.title} — ${batchEp.title}",
                    seasonNumber = batchEp.seasonNumber,
                    episodeNumber = batchEp.episodeNumber,
                    episodeTitle = batchEp.title,
                    streamTitle = "1080p",
                    providerName = "Test",
                    sourceUrl = stream.url.orEmpty(),
                    fileName = "dl-${batchEp.episodeNumber}.mp4",
                    status = DownloadStatus.Downloading,
                    createdAtEpochMs = 1L,
                    updatedAtEpochMs = 1L,
                )
                fakeDownloadsState.value = DownloadsUiState(items = fakeDownloadsState.value.items + added)
                DownloadEnqueueResult.Started
            },
            onFinished = { _, _ -> },
            discard = {},
        )
        val noOpAutoDownloads = object : AutoDownloadController {
            override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
            override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
            override val storageLimitGb = MutableStateFlow<Int?>(50)
            override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
            override val usesSystemTrash = true
            override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean) = Unit
            override fun keepDownload(profileId: Int, downloadId: String) = Unit
            override fun deleteDownloadNow(profileId: Int, downloadId: String) = Unit
            override fun turnOff(profileId: Int, ruleKey: String) = Unit
            override fun setStorageLimit(profileId: Int, limitGb: Int?) = Unit
            override fun checkNow() = Unit
        }

        sessionController.open(launch = sampleSeriesLaunch(), launchId = 601L)

        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalDesktopNativePlayerHooks provides hooks) {
                    var activeTab by remember { mutableStateOf("home") }
                    val libraryState by LibraryRepository.uiState.collectAsState()
                    val downloadsUi by fakeDownloadsState.collectAsState()

                    Box(modifier = Modifier.fillMaxSize()) {
                        when (val currentRoute = navigator.currentRoute) {
                            is TabsRoute -> Column {
                                Button(onClick = { activeTab = "home" }) { Text("Tab Home") }
                                Button(onClick = { activeTab = "library" }) { Text("Tab Library") }
                                Button(onClick = { navigator.navigate(DownloadsRoute("Downloads")) }) { Text("Open Downloads") }
                                if (activeTab == "home") {
                                    Button(
                                        onClick = {
                                            navigator.navigate(DetailRoute(type = "series", id = browsedSeriesId, title = "Browsed Show"))
                                        },
                                    ) {
                                        Text("Browse Browsed Show")
                                    }
                                } else {
                                    libraryState.items.forEach { saved ->
                                        Text("Library Saved: ${saved.name}")
                                    }
                                }
                            }
                            is DetailRoute -> Column {
                                Text("Details: ${currentRoute.title}")
                                val isSaved = libraryState.items.any { it.id == browsedSeriesId && it.type == "series" }
                                DetailActions(
                                    playLabel = text(Res.string.detail_btn_play),
                                    playEnabled = true,
                                    isSaved = isSaved,
                                    isWatched = false,
                                    isTablet = false,
                                    shuffleEnabled = false,
                                    onPlayClick = {},
                                    onPlayLongClick = null,
                                    onShuffleClick = null,
                                    onWatchedClick = {},
                                    onSaveClick = {
                                        if (isSaved) {
                                            LibraryRepository.remove(browsedSeriesId)
                                        } else {
                                            LibraryRepository.save(
                                                LibraryItem(
                                                    id = browsedSeriesId,
                                                    type = "series",
                                                    name = browsedMeta.name,
                                                    savedAtEpochMs = 0L,
                                                ),
                                            )
                                        }
                                    },
                                    onSaveLongClick = null,
                                )
                                SeasonDownloadButton(
                                    meta = browsedMeta,
                                    seasonNumber = 1,
                                    profileId = testDialogProfileId,
                                    startBatch = seasonDownloader::start,
                                    autoDownloads = noOpAutoDownloads,
                                )
                                Button(onClick = { navigator.navigate(DownloadsRoute("Downloads")) }) {
                                    Text("Go To Downloads")
                                }
                            }
                            is DownloadsRoute -> Column {
                                Text("Downloads Screen")
                                downloadsUi.items.forEach { item ->
                                    Text("Queued Download: ${item.title}")
                                }
                                Button(
                                    onClick = {
                                        while (navigator.currentRoute !is TabsRoute) {
                                            navigator.popBackStack()
                                        }
                                        activeTab = "library"
                                    },
                                ) {
                                    Text("Return To Library")
                                }
                            }
                            else -> Unit
                        }

                        PlayerSessionHost(
                            controller = sessionController,
                            navController = navigator,
                            externalPlayerId = null,
                            externalPlayerNotConfiguredText = "",
                            externalPlayerFailedText = "",
                            onExternalPlayerLaunch = {},
                            launchExternalPlayer = { false },
                            openExternalStreamUrl = { false },
                            onOpenPartyMedia = {},
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000L) { backend.createCount == 1 }
        compose.runOnIdle {
            backend.statesByHandle[1L]?.apply {
                loading = false
                paused = false
                positionMs = 450_000L
                durationMs = 1_800_000L
            }
        }
        compose.mainClock.advanceTimeBy(300L)
        compose.waitForIdle()
        compose.runOnIdle { testDispatcher.scheduler.runCurrent() }
        compose.waitUntil(5_000L) {
            backend.statesByHandle[1L]?.nowPlayingTitle == "Series" &&
                scrobbles.any { it.first == TrackingScrobbleAction.START }
        }

        val fullSlotBounds = compose.onNodeWithTag("player_session_surface_slot").fetchSemanticsNode().boundsInRoot
        assertTrue(fullSlotBounds.width >= 1000f && fullSlotBounds.height >= 700f, "Full mode surface fills window")
        assertEquals("Series", backend.statesByHandle[1L]?.nowPlayingTitle)
        assertEquals("S1E1 - Episode 1", backend.statesByHandle[1L]?.nowPlayingSubtitle)

        // Minimize while playing: surface slot shrinks to exact 334x188 mini slot above dock controls.
        compose.runOnIdle {
            sessionController.dispatchSystemBack()
        }
        compose.waitForIdle()
        val miniSlotBounds = compose.onNodeWithTag("player_session_surface_slot").fetchSemanticsNode().boundsInRoot
        val dockBounds = compose.onNodeWithTag("mini_player_dock").fetchSemanticsNode().boundsInRoot
        assertTrue(sessionController.isMinimized)
        assertTrue(miniSlotBounds.width in 330f..340f && miniSlotBounds.height in 184f..192f, "Mini slot resized to 334x188 dp")
        assertTrue(miniSlotBounds.bottom <= dockBounds.bottom - 60f, "Mini video slot sits above 68 dp dock controls")
        assertEquals(1, backend.createCount)
        assertEquals(0, backend.disposeCount)
        assertEquals(true, backend.statesByHandle[1L]?.miniPlayerMode)
        assertEquals(true, backend.statesByHandle[1L]?.remoteCommandsEnabled)

        // Drive UI navigation: click Browsed Show card -> click Add to Library -> open SeasonDownloadDialog & confirm.
        compose.onNodeWithText("Browse Browsed Show").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Details: Browsed Show").assertIsDisplayed()

        compose.onNodeWithContentDescription(text(Res.string.details_actions_menu_label)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(Res.string.hero_add_to_library)).performClick()
        compose.waitForIdle()
        assertTrue(LibraryRepository.isSaved(browsedSeriesId, "series"))
        compose.onNodeWithContentDescription(text(Res.string.hero_remove_from_library)).assertIsDisplayed()

        val seasonLabel = text(Res.string.episodes_season, 1)
        compose.onNodeWithText(text(Res.string.downloads_season_start)).performClick()
        compose.onNodeWithContentDescription(text(Res.string.downloads_season_dialog_title, seasonLabel)).performClick()
        compose.onNode(hasText(text(Res.string.downloads_season_button)) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { testDispatcher.scheduler.runCurrent() }
        compose.waitForIdle()
        assertEquals(
            listOf("https://example.com/dl-1.mp4", "https://example.com/dl-2.mp4"),
            queuedSeasonUrls,
        )

        // Open Downloads screen via UI button and verify queued season episodes render while mini-player stays active.
        compose.onNodeWithText("Go To Downloads").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Queued Download: Browsed Show — Ep 1").assertIsDisplayed()
        compose.onNodeWithText("Queued Download: Browsed Show — Ep 2").assertIsDisplayed()

        // Navigate to Library tab via UI button and verify saved show renders while mini-player keeps Now Playing metadata.
        compose.onNodeWithText("Return To Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Library Saved: Browsed Show").assertIsDisplayed()
        assertEquals("Series", backend.statesByHandle[1L]?.nowPlayingTitle)
        assertEquals("S1E1 - Episode 1", backend.statesByHandle[1L]?.nowPlayingSubtitle)

        // Dispatch native media-key toggle while minimized: pauses playback and delivers PAUSE scrobble, then resumes & delivers START.
        val scrobblesBeforeMediaKey = scrobbles.size
        backend.statesByHandle[1L]?.eventSink?.onPlayerEvent("keyboardToggle", 0.0)
        compose.waitUntil(5_000L) { backend.statesByHandle[1L]?.paused == true }
        compose.mainClock.advanceTimeBy(200L)
        compose.waitForIdle()
        compose.waitUntil(5_000L) {
            scrobbles.drop(scrobblesBeforeMediaKey).any { it.first == TrackingScrobbleAction.PAUSE }
        }

        val scrobblesBeforeResume = scrobbles.size
        backend.emitPlaybackPosition(1L, 468_000L)
        backend.statesByHandle[1L]?.eventSink?.onPlayerEvent("keyboardToggle", 0.0)
        compose.waitUntil(5_000L) { backend.statesByHandle[1L]?.paused == false }
        compose.mainClock.advanceTimeBy(200L)
        compose.waitForIdle()
        compose.waitUntil(5_000L) {
            scrobbles.drop(scrobblesBeforeResume).any { it.first == TrackingScrobbleAction.START }
        }

        // Verify Picture in Picture shrinks main-window anchor to 1 dp without creating or disposing handles.
        compose.runOnIdle { sessionController.setPictureInPicture(true) }
        compose.waitForIdle()
        val pipSlotBounds = compose.onNodeWithTag("player_session_surface_slot").fetchSemanticsNode().boundsInRoot
        assertTrue(pipSlotBounds.width <= 2f && pipSlotBounds.height <= 2f, "PiP anchor shrinks to 1 dp")
        compose.runOnIdle {
            sessionController.setPictureInPicture(false)
            sessionController.minimize()
        }
        compose.waitForIdle()

        // Reopen full player by clicking the docked mini-player title: zero new native creates, zero disposes.
        compose.onNodeWithText("Series").performClick()
        compose.waitForIdle()
        val reopenedBounds = compose.onNodeWithTag("player_session_surface_slot").fetchSemanticsNode().boundsInRoot
        assertTrue(reopenedBounds.width >= 1000f && reopenedBounds.height >= 700f)
        compose.runOnIdle {
            assertTrue(sessionController.isFullScreen)
            assertEquals(1, backend.createCount)
            assertEquals(0, backend.disposeCount)
            assertEquals(1, backend.activeHandleCount)
            assertEquals(false, backend.statesByHandle[1L]?.miniPlayerMode)
            assertEquals(468_000L, sessionController.playbackState?.positionMs)
        }

        // Minimize again and close from MiniPlayerDock: delivers STOP scrobble and disposes exactly one native player.
        val scrobblesBeforeClose = scrobbles.size
        compose.runOnIdle {
            sessionController.minimize()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(Res.string.compose_player_close)).performClick()
        compose.waitUntil(5_000L) { backend.disposeCount == 1 && !sessionController.hasActiveSession }
        compose.waitForIdle()
        compose.waitUntil(5_000L) {
            scrobbles.drop(scrobblesBeforeClose).any { it.first == TrackingScrobbleAction.STOP }
        }

        assertEquals(1, backend.createCount)
        assertEquals(1, backend.disposeCount)
        assertEquals(0, backend.activeHandleCount)
        assertEquals(listOf(1L), backend.disposedHandles.toList())
        assertFalse(ActivePlayerSessionRegistry.isActive.value)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun main_app_content_end_to_end_play_minimize_browse_library_season_download_reopen_and_profile_switch() {
        PlayerSettingsRepository.ensureLoaded()
        val previousSettings = PlayerSettingsRepository.uiState.value
        val activeProfileId = ProfileRepository.activeProfileId
        val previousSeasonDefaults = SeasonDownloadDefaultsStorage.loadPayload(activeProfileId).orEmpty()

        try {
            WatchProgressRepository.ensureLoaded()
            WatchProgressRepository.removeProgress(contentId = appSeriesId)
            WatchProgressRepository.removeProgress(contentId = appBrowsedSeriesId)
            PlayerSettingsRepository.setExternalPlayerEnabled(false)
            PlayerSettingsRepository.setStreamReuseLastLinkEnabled(false)
            PlayerSettingsRepository.setStreamAutoPlayMode(StreamAutoPlayMode.MANUAL)
            LibraryRepository.remove(appBrowsedSeriesId)
            LibraryRepository.save(
                LibraryItem(
                    id = appSeriesId,
                    type = "series",
                    name = "Active Series",
                    savedAtEpochMs = Long.MAX_VALUE - 1_000L,
                ),
            )

            val playingMeta = MetaDetails(
                id = appSeriesId,
                type = "series",
                name = "Active Series",
                videos = listOf(
                    MetaVideo(
                        id = "$appSeriesId:1:1",
                        title = "Pilot Episode",
                        season = 1,
                        episode = 1,
                        streams = listOf(
                            StreamItem(
                                name = "Embedded 1080p",
                                description = "Embedded Direct Stream",
                                url = "https://example.com/main-app-ep1.mp4",
                                addonName = "Embedded",
                                addonId = "embedded",
                            ),
                        ),
                    ),
                    MetaVideo(
                        id = "$appSeriesId:1:2",
                        title = "Second Episode",
                        season = 1,
                        episode = 2,
                        streams = listOf(
                            StreamItem(
                                name = "Embedded 1080p",
                                description = "Embedded Direct Stream Ep2",
                                url = "https://example.com/main-app-ep2.mp4",
                                addonName = "Embedded",
                                addonId = "embedded",
                            ),
                        ),
                    ),
                ),
                moreLikeThis = listOf(
                    MetaPreview(
                        id = appBrowsedSeriesId,
                        type = "series",
                        name = "Browsed Show",
                    ),
                ),
            )
            val browsedMeta = MetaDetails(
                id = appBrowsedSeriesId,
                type = "series",
                name = "Browsed Show",
                videos = listOf(
                    MetaVideo(
                        id = "$appBrowsedSeriesId:1:1",
                        title = "Browsed Ep 1",
                        season = 1,
                        episode = 1,
                        streams = listOf(
                            StreamItem(
                                name = "1080p",
                                description = "Season Source 1080p",
                                url = "https://example.com/browsed-ep1.mp4",
                                addonName = "Embedded",
                                addonId = "embedded",
                            ),
                        ),
                    ),
                    MetaVideo(
                        id = "$appBrowsedSeriesId:1:2",
                        title = "Browsed Ep 2",
                        season = 1,
                        episode = 2,
                        streams = listOf(
                            StreamItem(
                                name = "1080p",
                                description = "Season Source 1080p",
                                url = "https://example.com/browsed-ep2.mp4",
                                addonName = "Embedded",
                                addonId = "embedded",
                            ),
                        ),
                    ),
                ),
            )
            MetaDetailsRepository.putCachedMetaForTest(playingMeta)
            MetaDetailsRepository.putCachedMetaForTest(browsedMeta)

            val capturedSeasonBatches = mutableListOf<SeasonBatchRequest>()
            SeasonBatchDownloads.startOverrideForTest = { request ->
                capturedSeasonBatches += request
                SeasonBatchStart.Started
            }

            val backend = CountingNativePlayerBackend()
            val hooks = backend.toDesktopHooks()
            val sessionController = PlayerSessionController()
            val switchProfileCount = AtomicInteger(0)

            compose.setContent {
                NuvioTheme {
                    CompositionLocalProvider(LocalDesktopNativePlayerHooks provides hooks) {
                        MainAppContent(
                            initialRoute = TabsRoute,
                            ownsAppRuntime = false,
                            showLaunchOverlay = false,
                            sessionController = sessionController,
                            onSwitchProfile = { switchProfileCount.incrementAndGet() },
                        )
                    }
                }
            }
            compose.waitForIdle()

            fun waitForStep(step: String, condition: () -> Boolean) {
                try {
                    compose.waitUntil(5_000L, condition)
                } catch (error: Throwable) {
                    throw AssertionError(
                        "$step timed out; meta=${MetaDetailsRepository.uiState.value.meta?.id}; tree=\n" +
                            compose.onRoot().printToString(),
                        error,
                    )
                }
            }

            fun leaveSidebarHover() {
                compose.onRoot().performMouseInput { moveTo(Offset(600f, 100f)) }
                compose.waitForIdle()
            }

            // 1. Navigate from Home tab to Library tab via MainAppContent's sidebar, then open "Active Series".
            val playEpisodeLabel = localizedPlayLabel(1, 1)
            compose.onNodeWithContentDescription(text(Res.string.compose_nav_library)).performClick()
            leaveSidebarHover()
            waitForStep("library-item") {
                compose.onAllNodes(hasText("Active Series") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodes(hasText("Active Series") and hasClickAction()).onFirst().performClick()
            waitForStep("detail-play-button") {
                compose.onAllNodes(hasText(playEpisodeLabel) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }

            // 2. Click Play on MetaDetailsScreen -> StreamDestination -> select embedded stream -> opens PlayerSessionHost.
            compose.onAllNodes(hasText(playEpisodeLabel) and hasClickAction()).onFirst().performClick()
            waitForStep("stream-item") {
                compose.onAllNodes(hasText("Embedded 1080p") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodes(hasText("Embedded 1080p") and hasClickAction()).onFirst().performClick()
            waitForStep("player-full") { backend.createCount == 1 && sessionController.isFullScreen }

            compose.runOnIdle {
                backend.statesByHandle[1L]?.apply {
                    loading = false
                    paused = false
                    positionMs = 300_000L
                    durationMs = 1_800_000L
                }
            }
            compose.mainClock.advanceTimeBy(300L)
            compose.waitForIdle()

            // 3. Minimize while playing via system Back: keeps 1 native handle alive and uncovers MetaDetailsScreen.
            compose.runOnIdle {
                sessionController.dispatchSystemBack()
            }
            compose.waitForIdle()
            assertTrue(sessionController.isMinimized)
            assertEquals(1, backend.createCount)
            assertEquals(0, backend.disposeCount)
            compose.onNodeWithTag("mini_player_dock").assertIsDisplayed()

            // 4. Browse from "Active Series" to "Browsed Show" through MainAppContent's More Like This navigation.
            val verticalListMatcher = hasScrollToNodeAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            compose.onNode(verticalListMatcher).performScrollToNode(hasText("Browsed Show"))
            compose.onAllNodes(hasText("Browsed Show") and hasClickAction()).onFirst().performClick()
            waitForStep("browsed-detail") {
                MetaDetailsRepository.uiState.value.meta?.id == appBrowsedSeriesId
            }
            compose.waitForIdle()

            // 5. Save "Browsed Show" to Library and queue Season 1 download through MainAppContent's real DetailsDestination.
            compose.onNodeWithContentDescription(text(Res.string.details_actions_menu_label)).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription(text(Res.string.hero_add_to_library)).performClick()
            waitForStep("library-saved") { LibraryRepository.isSaved(appBrowsedSeriesId, "series") }

            val seasonLabel = text(Res.string.episodes_season, 1)
            compose.onNode(verticalListMatcher).performScrollToNode(hasText(text(Res.string.downloads_season_start)))
            compose.onAllNodes(hasText(text(Res.string.downloads_season_start)) and hasClickAction()).onFirst().performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription(text(Res.string.downloads_season_dialog_title, seasonLabel)).performClick()
            compose.waitForIdle()
            compose.onNode(hasText(text(Res.string.downloads_season_button)) and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()
            assertEquals(1, capturedSeasonBatches.size)
            assertEquals(appBrowsedSeriesId, capturedSeasonBatches.single().parentMetaId)
            assertEquals(1, capturedSeasonBatches.single().seasonNumber)
            assertEquals(2, capturedSeasonBatches.single().episodes.size)

            // 6. Navigate Back through MainAppContent to TabsRoute (Library), open DownloadsRoute, and switch to Home tab.
            compose.onNodeWithContentDescription(text(Res.string.action_back)).performClick()
            waitForStep("back-to-active-series") { MetaDetailsRepository.uiState.value.meta?.id == appSeriesId }
            compose.onNodeWithContentDescription(text(Res.string.action_back)).performClick()
            waitForStep("back-to-library") {
                compose.onAllNodesWithText("Browsed Show").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithContentDescription(text(Res.string.compose_settings_root_downloads_title)).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription(text(Res.string.action_back)).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription(text(Res.string.compose_nav_home)).performClick()
            leaveSidebarHover()

            assertTrue(sessionController.isMinimized)
            assertEquals(1, backend.createCount)
            assertEquals(0, backend.disposeCount)

            // 7. Reopen Full Player from MiniPlayerDock: zero new native creates and zero disposes.
            compose.onAllNodes(hasText("Active Series") and hasAnyAncestor(hasTestTag("mini_player_dock")))
                .onFirst()
                .performClick()
            compose.waitForIdle()
            assertTrue(sessionController.isFullScreen)
            assertEquals(1, backend.createCount)
            assertEquals(0, backend.disposeCount)

            // 8. Minimize again, switch to Settings tab, and click Switch profile: MainAppContent closes active session
            //    (disposing the native player once) and invokes onSwitchProfile on successful close.
            compose.runOnIdle { sessionController.minimize() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(text(Res.string.compose_settings_page_root)).performClick()
            leaveSidebarHover()
            val switchProfileTitle = text(Res.string.compose_settings_root_switch_profile_title)
            waitForStep("settings-switch-profile") {
                compose.onAllNodes(hasText(switchProfileTitle) and hasClickAction())
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodes(hasText(switchProfileTitle) and hasClickAction()).onFirst().performClick()
            waitForStep("session-closed-and-profile-switched") {
                backend.disposeCount == 1 &&
                    !sessionController.hasActiveSession &&
                    switchProfileCount.get() == 1
            }

            assertEquals(1, backend.createCount)
            assertEquals(1, backend.disposeCount)
            assertEquals(0, backend.activeHandleCount)
            assertEquals(1, switchProfileCount.get())
            assertFalse(ActivePlayerSessionRegistry.isActive.value)
        } finally {
            PlayerSettingsRepository.setExternalPlayerEnabled(previousSettings.externalPlayerEnabled)
            PlayerSettingsRepository.setStreamReuseLastLinkEnabled(previousSettings.streamReuseLastLinkEnabled)
            PlayerSettingsRepository.setStreamAutoPlayMode(previousSettings.streamAutoPlayMode)
            SeasonDownloadDefaultsStorage.savePayload(activeProfileId, previousSeasonDefaults)
        }
    }

    @Test
    fun quitting_while_minimized_or_after_close_disposes_each_native_player_exactly_once() {
        val backend = CountingNativePlayerBackend()
        val hooks = backend.toDesktopHooks()
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val navigator = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
        var windowMounted by mutableStateOf(true)
        sessionController.open(launch = sampleSeriesLaunch(), launchId = 701L)

        compose.setContent {
            if (windowMounted) {
                NuvioTheme {
                    CompositionLocalProvider(LocalDesktopNativePlayerHooks provides hooks) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            PlayerSessionHost(
                                controller = sessionController,
                                navController = navigator,
                                externalPlayerId = null,
                                externalPlayerNotConfiguredText = "",
                                externalPlayerFailedText = "",
                                onExternalPlayerLaunch = {},
                                launchExternalPlayer = { false },
                                openExternalStreamUrl = { false },
                                onOpenPartyMedia = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000L) { backend.createCount == 1 }
        compose.runOnIdle {
            sessionController.minimize()
            assertTrue(sessionController.isMinimized)
            // Unmounting the window composition (as exitApplication() does on quit) disposes handle 1 once.
            windowMounted = false
        }
        compose.waitUntil(5_000L) { backend.disposeCount == 1 }
        compose.waitForIdle()

        assertEquals(1, backend.createCount)
        assertEquals(1, backend.disposeCount)
        assertEquals(0, backend.activeHandleCount)

        // Remount a fresh session, close it explicitly, then unmount window: still disposes handle 2 only once.
        compose.runOnIdle {
            sessionController.open(launch = sampleSeriesLaunch(), launchId = 702L)
            windowMounted = true
        }
        compose.waitUntil(5_000L) { backend.createCount == 2 }
        compose.runOnIdle {
            sessionController.closeActiveSession()
        }
        compose.waitUntil(5_000L) { backend.disposeCount == 2 && !sessionController.hasActiveSession }
        compose.runOnIdle {
            windowMounted = false
        }
        compose.waitForIdle()

        assertEquals(2, backend.createCount)
        assertEquals(2, backend.disposeCount)
        assertEquals(listOf(1L, 2L), backend.disposedHandles.toList())
    }

    @Test
    fun desktop_quit_flushes_progress_and_completes_native_disposal_before_exit_application_runs() {
        val coordinator = NativePlayerLifecycleCoordinator()
        val backend = CountingNativePlayerBackend(
            onDisposeHook = {
                Thread.sleep(75L)
            },
        )
        val hooks = backend.toDesktopHooks(coordinator = coordinator)
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val navigator = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
        var windowMounted by mutableStateOf(true)
        sessionController.open(launch = sampleSeriesLaunch(), launchId = 751L)

        compose.setContent {
            if (windowMounted) {
                NuvioTheme {
                    CompositionLocalProvider(LocalDesktopNativePlayerHooks provides hooks) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            PlayerSessionHost(
                                controller = sessionController,
                                navController = navigator,
                                externalPlayerId = null,
                                externalPlayerNotConfiguredText = "",
                                externalPlayerFailedText = "",
                                onExternalPlayerLaunch = {},
                                launchExternalPlayer = { false },
                                openExternalStreamUrl = { false },
                                onOpenPartyMedia = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000L) { backend.createCount == 1 }
        compose.runOnIdle {
            backend.statesByHandle[1L]?.apply {
                loading = false
                paused = false
                positionMs = 612_000L
                durationMs = 1_800_000L
            }
        }
        compose.mainClock.advanceTimeBy(300L)
        compose.waitForIdle()

        val exitCalled = CountDownLatch(1)
        val servicesShutDown = AtomicBoolean(false)
        var disposeCountAtExit = -1
        var activeHandlesAtExit = -1
        var trackedWorkersAtExit = -1
        var flushedPositionAtExit: Long? = null

        compose.runOnIdle {
            sessionController.minimize()
            assertTrue(sessionController.isMinimized)
            performDesktopQuit(
                coordinator = coordinator,
                shutdownServices = { servicesShutDown.set(true) },
            ) {
                disposeCountAtExit = backend.disposeCount
                activeHandlesAtExit = backend.activeHandleCount
                trackedWorkersAtExit = coordinator.trackedWorkerCountForTest
                flushedPositionAtExit = WatchProgressRepository.progressForVideo(episodes[0].id)?.lastPositionMs
                windowMounted = false
                exitCalled.countDown()
            }
        }

        assertTrue(exitCalled.await(5, TimeUnit.SECONDS), "performDesktopQuit must invoke exitApplication after native cleanup")
        compose.waitForIdle()

        assertTrue(servicesShutDown.get())
        assertEquals(1, disposeCountAtExit, "native disposal must already be complete when exitApplication runs")
        assertEquals(0, activeHandlesAtExit, "no active native handles may remain when exitApplication runs")
        assertEquals(0, trackedWorkersAtExit, "no teardown workers may remain in flight when exitApplication runs")
        assertEquals(612_000L, flushedPositionAtExit, "watch progress must already be flushed when exitApplication runs")
        assertEquals(1, backend.disposeCount, "window unmount after performDesktopQuit must not double-dispose")
    }

    @Test
    fun trailer_while_minimized_asks_before_playing_and_preserves_remote_commands() {
        val backend = CountingNativePlayerBackend()
        val hooks = backend.toDesktopHooks()
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val navigator = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
        var showTrailer by mutableStateOf(false)
        val trailerState = TrailerPlaybackState()
        val trailerSource = TrailerPlaybackSource(videoUrl = "https://example.com/dune-trailer.mp4")

        sessionController.open(launch = sampleSeriesLaunch(), launchId = 801L)

        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalDesktopNativePlayerHooks provides hooks) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showTrailer) {
                            TrailerPlayer(
                                source = trailerSource,
                                state = trailerState,
                                isLoading = false,
                                errorMessage = null,
                                onRetry = null,
                                modifier = Modifier.fillMaxSize(),
                                title = "Dune — Official Trailer",
                            )
                        }
                        PlayerSessionHost(
                            controller = sessionController,
                            navController = navigator,
                            externalPlayerId = null,
                            externalPlayerNotConfiguredText = "",
                            externalPlayerFailedText = "",
                            onExternalPlayerLaunch = {},
                            launchExternalPlayer = { false },
                            openExternalStreamUrl = { false },
                            onOpenPartyMedia = {},
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000L) { backend.createCount == 1 }
        compose.runOnIdle {
            sessionController.minimize()
            // Even if TrailerPlayer is shown directly while a session is active, it refuses to mount a surface.
            showTrailer = true
        }
        compose.waitForIdle()
        assertEquals(1, backend.createCount, "TrailerPlayer must not create a second player while session is active")
        assertEquals(0, backend.disposeCount)
        assertEquals(true, backend.statesByHandle[1L]?.remoteCommandsEnabled)

        compose.runOnIdle {
            showTrailer = false
            sessionController.requestTrailerOrAskToReplace(
                contentTitle = "Dune",
                trailerTitle = "Official Trailer",
            ) {
                showTrailer = true
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(text(Res.string.player_replace_playback_title)).assertIsDisplayed()
        compose.onNodeWithText("Series (S1 E1) is currently playing. Start Dune — Official Trailer instead?")
            .assertIsDisplayed()

        // Keep playing: episode stays alive, no trailer player created, remote commands remain enabled.
        compose.onNodeWithText(text(Res.string.player_replace_playback_keep)).performClick()
        compose.waitForIdle()
        assertFalse(showTrailer)
        assertEquals(1, backend.createCount)
        assertEquals(0, backend.disposeCount)
        assertEquals(true, backend.statesByHandle[1L]?.remoteCommandsEnabled)

        // Request again and confirm replacement: episode handle 1 disposes BEFORE trailer handle 2 creates,
        // and trailer disables remote command ownership (useNativeController = false).
        compose.runOnIdle {
            sessionController.requestTrailerOrAskToReplace(
                contentTitle = "Dune",
                trailerTitle = "Official Trailer",
            ) {
                showTrailer = true
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.player_replace_playback_confirm)).performClick()
        compose.waitUntil(5_000L) { backend.createCount == 2 && backend.disposeCount == 1 }
        compose.waitForIdle()

        assertTrue(showTrailer)
        assertFalse(sessionController.hasActiveSession)
        assertEquals(1, backend.maxConcurrentHandles)
        assertEquals(listOf(1L), backend.disposedHandles.toList())
        assertEquals(false, backend.statesByHandle[2L]?.remoteCommandsEnabled)

        // Closing the trailer disposes handle 2 cleanly.
        compose.runOnIdle {
            showTrailer = false
        }
        compose.waitUntil(5_000L) { backend.disposeCount == 2 }
        assertEquals(listOf(1L, 2L), backend.disposedHandles.toList())
    }

    @Test
    fun minimized_snapshot_polling_slows_to_one_second_skips_dimension_reads_and_restores_on_reopen() {
        assertEquals(250L, desktopSnapshotPollIntervalMs(isInMiniPlayer = false))
        assertEquals(1_000L, desktopSnapshotPollIntervalMs(isInMiniPlayer = true))

        val backend = CountingNativePlayerBackend()
        val hooks = backend.toDesktopHooks()
        var inMini by mutableStateOf(false)
        val snapshots = mutableListOf<PlayerPlaybackSnapshot>()

        compose.mainClock.autoAdvance = false
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalDesktopNativePlayerHooks provides hooks) {
                    PlatformPlayerSurface(
                        sourceUrl = "https://example.com/poll-test.mp4",
                        modifier = Modifier.fillMaxSize(),
                        useNativeController = true,
                        playerControlsState = PlayerControlsState(isInMiniPlayer = inMini),
                        onControllerReady = {},
                        onSnapshot = { snapshots += it },
                        onError = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(50L)
        compose.waitUntil(5_000L) { backend.createCount == 1 }

        // Advance one full-mode poll (250 ms) so handle 1 caches its 1920x1080 dimensions.
        compose.mainClock.advanceTimeBy(300L)
        val fullModeDimensionQueries = backend.videoDimensionQueries.get()
        assertTrue(fullModeDimensionQueries >= 2, "full mode reads video dimensions")

        // Switch to mini mode on macOS: zero timer polling, updates arrive only on native mpv property events.
        snapshots.clear()
        inMini = true
        compose.mainClock.advanceTimeBy(50L)
        val miniEntrySnapshots = snapshots.size
        val queriesAtMiniStart = backend.videoDimensionQueries.get()

        compose.mainClock.advanceTimeBy(5_000L)
        assertEquals(miniEntrySnapshots, snapshots.size, "zero timer polling while minimized on macOS")

        backend.emitPlaybackPosition(1L, 123_000L)
        compose.waitUntil(5_000L) { snapshots.any { it.positionMs == 123_000L } }
        compose.mainClock.advanceTimeBy(50L)
        assertEquals(123_000L, snapshots.last().positionMs)
        assertEquals(1920, snapshots.last().videoWidth)
        assertEquals(queriesAtMiniStart, backend.videoDimensionQueries.get(), "no dimension queries while minimized")

        // Reopen full mode: restores 250 ms polling and video-dimension queries immediately.
        snapshots.clear()
        inMini = false
        compose.mainClock.advanceTimeBy(300L)
        assertTrue(snapshots.size >= 2, "250 ms polling restored on reopen")
        assertTrue(backend.videoDimensionQueries.get() > queriesAtMiniStart, "dimension queries restored on reopen")
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun minimized_session_host_skips_audio_track_scans_and_stops_polling_while_paused() {
        val sourceHealthTicks = AtomicInteger(0)
        sourceHealthTickObserverForTest = { sourceHealthTicks.incrementAndGet() }

        val backend = CountingNativePlayerBackend()
        val hooks = backend.toDesktopHooks()
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val navigator = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
        sessionController.open(
            launch = sampleSeriesLaunch().copy(sourceAutoSelected = true),
            launchId = 905L,
        )

        compose.mainClock.autoAdvance = false
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalDesktopNativePlayerHooks provides hooks) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PlayerSessionHost(
                            controller = sessionController,
                            navController = navigator,
                            externalPlayerId = null,
                            externalPlayerNotConfiguredText = "",
                            externalPlayerFailedText = "",
                            onExternalPlayerLaunch = {},
                            launchExternalPlayer = { false },
                            openExternalStreamUrl = { false },
                            onOpenPartyMedia = {},
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(50L)
        compose.waitUntil(5_000L) { backend.createCount == 1 }
        backend.statesByHandle[1L]?.apply {
            loading = false
            paused = false
            positionMs = 100_000L
            durationMs = 1_800_000L
        }

        // Let initial track selection and full-screen source-health watch run in Full mode.
        compose.mainClock.advanceTimeBy(3_500L)
        assertTrue(backend.audioTrackQueries.get() >= 2, "Full mode scans audio tracks")
        assertTrue(sourceHealthTicks.get() >= 2, "Full mode source-health monitor runs while playing")

        // Minimize while playing: zero timer polls, zero source-health wakeups, zero audio-track or dimension reads;
        // updates occur only when native mpv property events push a state tick.
        sessionController.minimize()
        compose.mainClock.advanceTimeBy(100L)
        val audioQueriesAtMiniPlay = backend.audioTrackQueries.get()
        val dimQueriesAtMiniPlay = backend.videoDimensionQueries.get()
        val snapQueriesAtMiniPlay = backend.snapshotQueries.get()
        val healthTicksAtMiniPlay = sourceHealthTicks.get()

        compose.mainClock.advanceTimeBy(10_000L)
        assertEquals(snapQueriesAtMiniPlay, backend.snapshotQueries.get(), "zero snapshot timer polls while playing minimized")
        assertEquals(healthTicksAtMiniPlay, sourceHealthTicks.get(), "zero source-health wakeups while playing minimized")
        assertEquals(audioQueriesAtMiniPlay, backend.audioTrackQueries.get(), "no audio-track scans while minimized")
        assertEquals(dimQueriesAtMiniPlay, backend.videoDimensionQueries.get(), "no video-dimension reads while minimized")

        backend.emitPlaybackPosition(1L, 105_000L)
        compose.waitUntil(5_000L) { backend.snapshotQueries.get() > snapQueriesAtMiniPlay }
        compose.mainClock.advanceTimeBy(50L)
        assertEquals(105_000L, sessionController.playbackState?.positionMs)

        // Pause from MiniPlayerDock: zero snapshot polls and zero source-health wakeups across 10 seconds.
        compose.onNodeWithContentDescription(text(Res.string.compose_action_pause)).performClick()
        compose.mainClock.advanceTimeBy(100L)
        val snapQueriesWhenPaused = backend.snapshotQueries.get()
        val audioQueriesWhenPaused = backend.audioTrackQueries.get()
        val dimQueriesWhenPaused = backend.videoDimensionQueries.get()
        val healthTicksWhenPaused = sourceHealthTicks.get()

        compose.mainClock.advanceTimeBy(10_000L)
        assertEquals(snapQueriesWhenPaused, backend.snapshotQueries.get(), "zero snapshot polls while paused and minimized")
        assertEquals(healthTicksWhenPaused, sourceHealthTicks.get(), "zero source-health wakeups while paused and minimized")
        assertEquals(audioQueriesWhenPaused, backend.audioTrackQueries.get(), "zero audio-track scans while paused and minimized")
        assertEquals(dimQueriesWhenPaused, backend.videoDimensionQueries.get(), "zero dimension reads while paused and minimized")

        // Seeking forward +10s while paused refreshes the dock position once and goes right back to idle.
        compose.onNodeWithContentDescription(text(Res.string.compose_player_seek_forward_10)).performClick()
        compose.mainClock.advanceTimeBy(100L)
        assertEquals(115_000L, sessionController.playbackState?.positionMs)
        val snapQueriesAfterPausedSeek = backend.snapshotQueries.get()
        compose.mainClock.advanceTimeBy(5_000L)
        assertEquals(snapQueriesAfterPausedSeek, backend.snapshotQueries.get(), "returns to zero polling after paused seek")
        assertEquals(healthTicksWhenPaused, sourceHealthTicks.get(), "still zero source-health wakeups while paused")

        // Reopen Full mode while still paused: source-health remains suspended at 0 wakeups until resumed!
        sessionController.restoreFullScreen()
        compose.mainClock.advanceTimeBy(3_000L)
        assertEquals(healthTicksWhenPaused, sourceHealthTicks.get(), "zero source-health wakeups while paused in Full mode")

        // Resume playback in Full mode: restores 250 ms polling, source-health watch, dimension reads, and audio-track checks.
        sessionController.togglePlayback()
        compose.mainClock.advanceTimeBy(1_200L)
        assertTrue(backend.snapshotQueries.get() >= snapQueriesAfterPausedSeek + 3, "250 ms polling restored on reopen")
        assertTrue(sourceHealthTicks.get() > healthTicksWhenPaused, "source-health watch resumes when playing in Full mode")
        assertTrue(backend.videoDimensionQueries.get() > dimQueriesWhenPaused, "dimension queries restored on reopen")
        assertTrue(backend.audioTrackQueries.get() > audioQueriesWhenPaused, "audio-track checks restored on reopen")
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun minimizing_and_closing_player_do_not_leave_local_plugin_search_paused() {
        val harness = startSessionHarness()
        val scraperJs = """
            module.exports.getStreams = async function(tmdbId, mediaType) {
                return [{ title: "Stream " + tmdbId, url: "https://example.test/s.mp4", provider: "Test" }];
            };
        """.trimIndent()

        compose.runOnIdle { harness.runtime.requestMinimize() }
        compose.waitForIdle()
        assertEquals(PlayerPresentationMode.Mini, harness.sessionController.activeSession?.mode)

        val whileMinimized = runBlocking {
            withTimeout(5_000L) {
                PluginRuntime.executePlugin(
                    code = scraperJs,
                    tmdbId = "101",
                    mediaType = "movie",
                    season = null,
                    episode = null,
                    scraperId = "mini-plugin-check",
                )
            }
        }
        assertEquals(1, whileMinimized.size)

        // Switching episodes while minimized clears episode streams without re-pausing local scrapers.
        compose.runOnIdle {
            harness.runtime.switchToEpisodeStream(
                stream = StreamItem(
                    name = "1080p",
                    url = "https://example.com/episode-2.mp4",
                    addonName = "Test",
                    addonId = "test",
                ),
                episode = episodes[1],
            )
        }
        compose.waitForIdle()

        val afterMinimizedSwitch = runBlocking {
            withTimeout(5_000L) {
                PluginRuntime.executePlugin(
                    code = scraperJs,
                    tmdbId = "102",
                    mediaType = "movie",
                    season = null,
                    episode = null,
                    scraperId = "mini-switch-plugin-check",
                )
            }
        }
        assertEquals(1, afterMinimizedSwitch.size)

        // Reopen Full mode (re-pausing idle background search while focused), then close directly from Full mode.
        compose.runOnIdle { harness.sessionController.restoreFullScreen() }
        compose.waitForIdle()
        assertEquals(PlayerPresentationMode.Full, harness.sessionController.activeSession?.mode)

        compose.runOnIdle { harness.sessionController.closeActiveSession() }
        compose.waitForIdle()
        assertNull(harness.sessionController.activeSession)

        val afterClose = runBlocking {
            withTimeout(5_000L) {
                PluginRuntime.executePlugin(
                    code = scraperJs,
                    tmdbId = "103",
                    mediaType = "movie",
                    season = null,
                    episode = null,
                    scraperId = "closed-plugin-check",
                )
            }
        }
        assertEquals(1, afterClose.size)
    }

    private class RecordingEngineController : PlayerEngineController {
        var playCalls = 0
        var pauseCalls = 0
        val seekOffsets = mutableListOf<Long>()
        var releaseCalls = 0

        override fun play() {
            playCalls += 1
        }

        override fun pause() {
            pauseCalls += 1
        }

        override fun seekTo(positionMs: Long) = Unit

        override fun seekBy(offsetMs: Long) {
            seekOffsets += offsetMs
        }

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

    private class SessionHarness(
        val sessionController: PlayerSessionController,
        val navigator: NuvioNavigator,
        val controller: RecordingEngineController,
    ) {
        lateinit var runtime: PlayerScreenRuntime
        var disposedCount = 0
    }

    private fun sampleSeriesLaunch() = PlayerLaunch(
        profileId = ProfileRepository.activeProfileId,
        title = "Series",
        sourceUrl = "https://example.com/episode-1.mp4",
        providerName = "Test",
        providerAddonId = "test",
        streamTitle = "Episode 1",
        seasonNumber = 1,
        episodeNumber = 1,
        episodeTitle = "Episode 1",
        contentType = "series",
        videoId = episodes[0].id,
        parentMetaId = testSeriesId,
        parentMetaType = "series",
    )

    private fun startSessionHarness(
        onExitPictureInPicture: () -> Unit = {},
        initialBingeGroup: String? = null,
    ): SessionHarness {
        WatchProgressRepository.clearLocalState()
        val sessionController = PlayerSessionController(
            onExitPictureInPicture = onExitPictureInPicture,
        )
        val backStack = NavBackStack<NavKey>(
            TabsRoute,
            DetailRoute(type = "series", id = testSeriesId, title = "Series"),
        )
        val navigator = NuvioNavigator(backStack)
        val engineController = RecordingEngineController()
        val launch = sampleSeriesLaunch().copy(bingeGroup = initialBingeGroup)
        val session = sessionController.open(launch = launch, launchId = 101L)
        val harness = SessionHarness(
            sessionController = sessionController,
            navigator = navigator,
            controller = engineController,
        )

        compose.setContent {
            val active = sessionController.activeSession
            if (active != null) {
                key(active.launchId) {
                    val guard = remember { PlayerBackReleaseGuard() }
                    val exitTarget = remember { PlayerExitTargetHolder() }
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
                        initialBingeGroup = active.launch.bingeGroup,
                        pauseDescription = null,
                        onBack = { releaseBeforeBack ->
                            guard.request(
                                canStart = { sessionController.activeSession?.launchId == active.launchId },
                                releaseBeforeBack = releaseBeforeBack,
                                beforePop = {},
                                pop = {
                                    val cleared = sessionController.clearSession(active.launchId)
                                    if (cleared) {
                                        exitTarget.target?.let { navigator.openPlayerExitTarget(it) }
                                    }
                                    cleared
                                },
                            )
                        },
                        isMinimized = active.mode == PlayerPresentationMode.Mini,
                        onMinimize = {
                            if (sessionController.minimize()) {
                                exitTarget.target?.let {
                                    exitTarget.target = null
                                    navigator.openPlayerExitTarget(it)
                                }
                            }
                        },
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
                        PlayerScreenRuntime(args).apply {
                            scope = this@MiniPlayerSessionTest.scope
                            playerSettingsUiState = PlayerSettingsUiState(
                                skipIntroEnabled = false,
                                streamAutoPlayNextEpisodeEnabled = true,
                                streamAutoPlayPreferBingeGroup = true,
                                streamReuseLastLinkEnabled = false,
                            )
                            metaUiState = MetaDetailsUiState(
                                meta = MetaDetails(
                                    id = testSeriesId,
                                    type = "series",
                                    name = "Series",
                                    videos = episodes,
                                ),
                            )
                            playerController = engineController
                            playerLifecycleController = engineController
                            playerControllerSourceUrl = args.sourceUrl
                        }
                    }
                    runtime.args = args
                    harness.runtime = runtime
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        onDispose { harness.disposedCount += 1 }
                    }
                    runtime.BindPlayerRuntimeEffects()
                    runtime.BindPlayerSessionBridge()
                    if (runtime.playerController == null && session.launchId == active.launchId) {
                        runtime.playerController = engineController
                        runtime.playerLifecycleController = engineController
                        runtime.playerControllerSourceUrl = runtime.activeSourceUrl
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            harness.runtime.playerController = engineController
            harness.runtime.playerLifecycleController = engineController
            harness.runtime.playerControllerSourceUrl = harness.runtime.activeSourceUrl
        }
        return harness
    }
}
