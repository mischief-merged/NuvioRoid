package com.nuvio.app.features.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsUiState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_action_pause
import nuvio.composeapp.generated.resources.compose_player_close
import nuvio.composeapp.generated.resources.compose_player_seek_forward_10
import nuvio.composeapp.generated.resources.detail_btn_play
import nuvio.composeapp.generated.resources.player_next_episode
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniPlayerDockUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(StandardTestDispatcher())
    private val testVideoId = "tt11280740:1:4"

    @After
    fun tearDown() {
        WatchProgressRepository.clearProgress(testVideoId)
        WatchProgressRepository.clearLocalState()
        PlayerStreamsRepository.clearAll()
        ActivePlayerSessionRegistry.setActive(false)
        scope.cancel()
    }

    private fun text(resource: StringResource): String = runBlocking { getString(resource) }

    @Test
    fun mini_player_dock_shows_title_episode_progress_bar_and_buttons() {
        val pauseLabel = text(Res.string.compose_action_pause)
        val seekLabel = text(Res.string.compose_player_seek_forward_10)
        val nextEpisodeLabel = text(Res.string.player_next_episode)
        val closeLabel = text(Res.string.compose_player_close)

        compose.setContent {
            MaterialTheme {
                MiniPlayerDock(
                    state = MiniPlayerPlaybackState(
                        title = "Severance",
                        seasonNumber = 1,
                        episodeNumber = 4,
                        episodeTitle = "The You You Are",
                        isPlaying = true,
                        positionMs = 350_000L,
                        durationMs = 1_000_000L,
                        nextEpisodePlayable = true,
                    ),
                    canPlayNextEpisode = true,
                    onTogglePlayback = {},
                    onSeekForward = {},
                    onPlayNextEpisode = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Severance").assertIsDisplayed()
        compose.onNodeWithText("S1 E4 • The You You Are").assertIsDisplayed()
        compose.onNodeWithTag(MiniPlayerProgressTestTag).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.35f, 0f..1f),
            ),
        )
        compose.onNodeWithContentDescription(pauseLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(seekLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(nextEpisodeLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(closeLabel).assertIsDisplayed()
    }

    @Test
    fun mini_player_dock_controls_pause_resume_skip_and_close_from_mini_player() {
        val playLabel = text(Res.string.detail_btn_play)
        val pauseLabel = text(Res.string.compose_action_pause)
        val seekLabel = text(Res.string.compose_player_seek_forward_10)
        val closeLabel = text(Res.string.compose_player_close)

        val sessionController = PlayerSessionController()
        var playCalls = 0
        var pauseCalls = 0
        val seekOffsets = mutableListOf<Long>()
        var releaseCalls = 0
        lateinit var runtimeRef: PlayerScreenRuntime

        val engineController = object : PlayerEngineController {
            override fun play() {
                playCalls += 1
                runtimeRef.updatePlaybackSnapshot(
                    runtimeRef.playbackSnapshot.copy(isPlaying = true, isLoading = false),
                )
            }

            override fun pause() {
                pauseCalls += 1
                runtimeRef.updatePlaybackSnapshot(
                    runtimeRef.playbackSnapshot.copy(isPlaying = false, isLoading = false),
                )
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

        val launch = PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = "Severance",
            sourceUrl = "https://example.com/s01e04.mp4",
            providerName = "Test",
            providerAddonId = "test",
            streamTitle = "S01E04",
            seasonNumber = 1,
            episodeNumber = 4,
            episodeTitle = "The You You Are",
            contentType = "series",
            videoId = testVideoId,
            parentMetaId = "tt11280740",
            parentMetaType = "series",
        )
        sessionController.open(launch = launch, launchId = 202L)
        sessionController.minimize()

        compose.setContent {
            MaterialTheme {
                val active = sessionController.activeSession
                if (active != null) {
                    key(active.launchId) {
                        val guard = remember { PlayerBackReleaseGuard() }
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
                            onSessionStateChanged = { state ->
                                sessionController.updatePlaybackState(active.launchId, state)
                            },
                            onSessionControlsChanged = { controls ->
                                sessionController.bindControls(active.launchId, controls)
                            },
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
                                scope = this@MiniPlayerDockUiTest.scope
                                playerSettingsUiState = PlayerSettingsUiState(skipIntroEnabled = false)
                                metaUiState = MetaDetailsUiState(
                                    meta = MetaDetails(
                                        id = "tt11280740",
                                        type = "series",
                                        name = "Severance",
                                        videos = listOf(
                                            MetaVideo(id = testVideoId, title = "The You You Are", season = 1, episode = 4),
                                        ),
                                    ),
                                )
                                playerController = engineController
                                playerLifecycleController = engineController
                                playerControllerSourceUrl = args.sourceUrl
                            }
                        }
                        runtime.args = args
                        runtimeRef = runtime
                        runtime.BindPlayerRuntimeEffects()
                        runtime.BindPlayerSessionBridge()
                        if (runtime.playerController == null) {
                            runtime.playerController = engineController
                            runtime.playerLifecycleController = engineController
                            runtime.playerControllerSourceUrl = runtime.activeSourceUrl
                        }
                        if (active.mode == PlayerPresentationMode.Mini) {
                            MiniPlayerDock(
                                state = sessionController.playbackState ?: MiniPlayerPlaybackState(title = active.launch.title),
                                canPlayNextEpisode = sessionController.canPlayNextEpisode,
                                onTogglePlayback = sessionController::togglePlayback,
                                onSeekForward = sessionController::seekForward,
                                onPlayNextEpisode = sessionController::playNextEpisode,
                                onClose = { sessionController.closeActiveSession() },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            runtimeRef.playerController = engineController
            runtimeRef.playerLifecycleController = engineController
            runtimeRef.playerControllerSourceUrl = runtimeRef.activeSourceUrl
            runtimeRef.initialLoadCompleted = true
            runtimeRef.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 500_000L,
                    durationMs = 2_000_000L,
                ),
            )
        }
        compose.waitForIdle()

        // Pause from the mini player.
        compose.onNodeWithContentDescription(pauseLabel).performClick()
        compose.waitForIdle()
        assertEquals(1, pauseCalls)
        compose.onNodeWithContentDescription(playLabel).assertIsDisplayed()

        // Resume from the mini player.
        compose.onNodeWithContentDescription(playLabel).performClick()
        compose.waitForIdle()
        assertEquals(1, playCalls)
        compose.onNodeWithContentDescription(pauseLabel).assertIsDisplayed()

        // Skip forward 10 seconds from the mini player.
        compose.onNodeWithContentDescription(seekLabel).performClick()
        compose.waitForIdle()
        assertEquals(listOf(10_000L), seekOffsets)

        // Close from the mini player: flushes progress, releases player, and removes dock.
        compose.onNodeWithContentDescription(closeLabel).performClick()
        compose.waitForIdle()
        assertEquals(1, releaseCalls)
        assertFalse(sessionController.hasActiveSession)
        assertNull(sessionController.playbackState)
        assertEquals(500_000L, WatchProgressRepository.progressForVideo(testVideoId)?.lastPositionMs)
        compose.onAllNodesWithContentDescription(closeLabel).assertCountEquals(0)
        assertTrue(!ActivePlayerSessionRegistry.isActive.value)
    }
}
