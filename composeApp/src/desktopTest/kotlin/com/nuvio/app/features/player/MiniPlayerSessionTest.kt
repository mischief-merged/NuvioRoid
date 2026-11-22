package com.nuvio.app.features.player

import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadsRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.SettingsPageRoute
import com.nuvio.app.navigation.TabsRoute
import com.nuvio.app.openPlayerExitTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MiniPlayerSessionTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(StandardTestDispatcher())
    private val testSeriesId = "tt9991109"
    private val episodes = (1..4).map { number ->
        MetaVideo(
            id = "$testSeriesId:1:$number",
            title = "Episode $number",
            season = 1,
            episode = number,
        )
    }

    @After
    fun tearDown() {
        episodes.forEach { WatchProgressRepository.clearProgress(it.id) }
        WatchProgressRepository.clearProgress(testSeriesId)
        WatchProgressRepository.clearLocalState()
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
        assertFalse(
            shouldMinimizePlayerOnBack(
                miniPlayerSupported = true,
                hasError = false,
                isEnded = false,
                isPlaying = false,
                shouldPlay = false,
                initialLoadCompleted = true,
            ),
            "paused playback closes on Back/Escape",
        )
        assertFalse(
            shouldMinimizePlayerOnBack(
                miniPlayerSupported = true,
                hasError = false,
                isEnded = false,
                isPlaying = false,
                shouldPlay = true,
                initialLoadCompleted = false,
            ),
            "initial opening screen before load closes on Back/Escape",
        )
        assertFalse(
            shouldMinimizePlayerOnBack(
                miniPlayerSupported = true,
                hasError = false,
                isEnded = true,
                isPlaying = false,
                shouldPlay = false,
                initialLoadCompleted = true,
            ),
            "ended playback closes on Back/Escape",
        )
        assertFalse(
            shouldMinimizePlayerOnBack(
                miniPlayerSupported = true,
                hasError = true,
                isEnded = false,
                isPlaying = true,
                shouldPlay = true,
                initialLoadCompleted = true,
            ),
            "playback error closes on Back/Escape",
        )
        assertTrue(
            shouldMinimizePlayerOnBack(
                miniPlayerSupported = true,
                hasError = false,
                isEnded = false,
                isPlaying = true,
                shouldPlay = true,
                initialLoadCompleted = true,
            ),
            "active playback minimizes on Back/Escape",
        )
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
        val harness = startSessionHarness()

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
            // Cross the next-episode threshold while minimized.
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
            // Transition to the resolved next episode stream while still minimized.
            harness.runtime.switchToEpisodeStream(
                StreamItem(url = "https://example.com/episode-2.mp4", addonName = "Test", addonId = "test"),
                episodes[1],
            )
            harness.runtime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 15_000L,
                    durationMs = 1_200_000L,
                ),
            )
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue(harness.sessionController.isMinimized)
            assertEquals(1, harness.sessionController.playbackState?.seasonNumber)
            assertEquals(2, harness.sessionController.playbackState?.episodeNumber)
            assertEquals("Episode 2", harness.sessionController.playbackState?.episodeTitle)
            assertEquals(episodes[1].id, harness.sessionController.playbackState?.videoId)
            assertEquals("https://example.com/episode-2.mp4", harness.sessionController.playbackState?.sourceUrl)
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

    private fun startSessionHarness(): SessionHarness {
        WatchProgressRepository.clearLocalState()
        val sessionController = PlayerSessionController()
        val backStack = NavBackStack<NavKey>(
            TabsRoute,
            DetailRoute(type = "series", id = testSeriesId, title = "Series"),
        )
        val navigator = NuvioNavigator(backStack)
        val engineController = RecordingEngineController()
        val launch = PlayerLaunch(
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
                        initialBingeGroup = null,
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
