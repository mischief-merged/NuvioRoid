package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.PlayerSessionHost
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.desktop.NativePlayerLifecycleCoordinator
import com.nuvio.app.performDesktopQuit
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadsUiState
import com.nuvio.app.features.downloads.SeasonBatchDownloader
import com.nuvio.app.features.downloads.SeasonBatchEpisode
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.plugins.runtime.PluginRuntime
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_close
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
    private val episodes = (1..4).map { number ->
        MetaVideo(
            id = "$testSeriesId:1:$number",
            title = "Episode $number",
            season = 1,
            episode = number,
        )
    }

    private fun text(resource: StringResource): String = runBlocking { getString(resource) }

    @After
    fun tearDown() {
        episodes.forEach { WatchProgressRepository.clearProgress(it.id) }
        WatchProgressRepository.clearProgress(testSeriesId)
        WatchProgressRepository.clearLocalState()
        LibraryRepository.remove(browsedSeriesId)
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
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val navigator = NuvioNavigator(
            NavBackStack<NavKey>(
                TabsRoute,
                DetailRoute(type = "series", id = testSeriesId, title = "Series"),
            ),
        )
        sessionController.open(launch = sampleSeriesLaunch(), launchId = 601L)

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
        compose.waitUntil(5_000L) { backend.createCount == 1 }
        compose.runOnIdle {
            backend.statesByHandle[1L]?.apply {
                loading = false
                paused = false
                positionMs = 415_000L
                durationMs = 1_800_000L
            }
        }
        compose.mainClock.advanceTimeBy(300L)
        compose.waitForIdle()

        // Minimize while playing: keeps the same native handle, sets mini mode on native controller.
        compose.runOnIdle {
            sessionController.dispatchSystemBack()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(sessionController.isMinimized)
            assertEquals(1, backend.createCount)
            assertEquals(0, backend.disposeCount)
            assertEquals(true, backend.statesByHandle[1L]?.miniPlayerMode)
            assertEquals(true, backend.statesByHandle[1L]?.remoteCommandsEnabled)
        }

        // Browse to another show, start a season download, and add the title to Library while minimized.
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
            enqueue = { _, _, stream ->
                queuedSeasonUrls += stream.url.orEmpty()
                DownloadEnqueueResult.Started
            },
            onFinished = { _, _ -> },
            discard = {},
        )

        compose.runOnIdle {
            navigator.navigate(DetailRoute(type = "series", id = browsedSeriesId, title = "Browsed Show"))
            LibraryRepository.save(
                LibraryItem(
                    id = browsedSeriesId,
                    type = "series",
                    name = "Browsed Show",
                    savedAtEpochMs = 0L,
                ),
            )
            assertTrue(LibraryRepository.isSaved(browsedSeriesId, "series"))

            val startResult = seasonDownloader.start(
                SeasonBatchRequest(
                    contentType = "series",
                    parentMetaId = browsedSeriesId,
                    parentMetaType = "series",
                    title = "Browsed Show",
                    logo = null,
                    poster = null,
                    background = null,
                    seasonNumber = 1,
                    episodes = listOf(
                        SeasonBatchEpisode("$browsedSeriesId:1:1", 1, 1, "Ep 1", null, runtimeMinutes = 45),
                        SeasonBatchEpisode("$browsedSeriesId:1:2", 1, 2, "Ep 2", null, runtimeMinutes = 45),
                    ),
                    quality = BatchQuality.P1080,
                ),
            )
            assertEquals(SeasonBatchStart.Started, startResult)
            testDispatcher.scheduler.runCurrent()
            assertEquals(
                listOf("https://example.com/dl-1.mp4", "https://example.com/dl-2.mp4"),
                queuedSeasonUrls,
            )

            navigator.navigate(DownloadsRoute("Downloads"))
            backend.statesByHandle[1L]?.positionMs = 468_000L
        }
        compose.mainClock.advanceTimeBy(1_050L)
        compose.waitForIdle()

        // Reopen full player by clicking the docked mini-player title: zero new native creates, zero disposes.
        compose.onNodeWithText("Series").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(sessionController.isFullScreen)
            assertEquals(1, backend.createCount)
            assertEquals(0, backend.disposeCount)
            assertEquals(1, backend.activeHandleCount)
            assertEquals(false, backend.statesByHandle[1L]?.miniPlayerMode)
            assertEquals(468_000L, sessionController.playbackState?.positionMs)
        }

        // Minimize again and close from MiniPlayerDock: disposes exactly one native player.
        compose.runOnIdle {
            sessionController.minimize()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(Res.string.compose_player_close)).performClick()
        compose.waitUntil(5_000L) { backend.disposeCount == 1 && !sessionController.hasActiveSession }
        compose.waitForIdle()

        assertEquals(1, backend.createCount)
        assertEquals(1, backend.disposeCount)
        assertEquals(0, backend.activeHandleCount)
        assertEquals(listOf(1L), backend.disposedHandles.toList())
        assertFalse(ActivePlayerSessionRegistry.isActive.value)
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

        // Switch to mini mode: polls only once per second and reuses cached dimensions without JNI queries.
        snapshots.clear()
        inMini = true
        compose.mainClock.advanceTimeBy(50L)
        val miniEntrySnapshots = snapshots.size
        val queriesAtMiniStart = backend.videoDimensionQueries.get()

        compose.mainClock.advanceTimeBy(500L)
        assertEquals(miniEntrySnapshots, snapshots.size, "no 250 ms poll while minimized")

        backend.statesByHandle[1L]?.positionMs = 123_000L
        compose.mainClock.advanceTimeBy(600L)
        assertEquals(miniEntrySnapshots + 1, snapshots.size, "1 s poll fires while minimized")
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
