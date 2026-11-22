package com.nuvio.app.features.player

import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_replace_playback_confirm
import nuvio.composeapp.generated.resources.player_replace_playback_keep
import nuvio.composeapp.generated.resources.player_replace_playback_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.After
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplacePlaybackPromptTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(StandardTestDispatcher())
    private val firstVideoId = "tt1111111:1:1"
    private val secondVideoId = "tt2222222:1:1"

    @After
    fun tearDown() {
        WatchProgressRepository.clearProgress(firstVideoId)
        WatchProgressRepository.clearProgress(secondVideoId)
        WatchProgressRepository.clearLocalState()
        PlayerStreamsRepository.clearAll()
        ActivePlayerSessionRegistry.setActive(false)
        scope.cancel()
    }

    private fun text(resource: StringResource): String = runBlocking { getString(resource) }

    @Test
    fun starting_playback_when_nothing_is_playing_proceeds_at_once() {
        val decision = decidePlaybackLaunch(
            activeState = null,
            activeLaunch = null,
            incomingTitle = "The Bear",
            videoId = secondVideoId,
            parentMetaId = "tt2222222",
            seasonNumber = 1,
            episodeNumber = 1,
        )
        assertEquals(ReplacePlaybackDecision.ProceedNow, decision)
    }

    @Test
    fun starting_same_playing_episode_reopens_without_asking() {
        val activeState = MiniPlayerPlaybackState(
            title = "Severance",
            seasonNumber = 1,
            episodeNumber = 4,
            videoId = "tt11280740:1:4",
            parentMetaId = "tt11280740",
            parentMetaType = "series",
            isPlaying = true,
        )
        assertEquals(
            ReplacePlaybackDecision.ReopenActive,
            decidePlaybackLaunch(
                activeState = activeState,
                activeLaunch = null,
                incomingTitle = "Severance",
                videoId = "tt11280740:1:4",
                parentMetaId = "tt11280740",
                seasonNumber = 1,
                episodeNumber = 4,
            ),
        )
        assertEquals(
            ReplacePlaybackDecision.AskFirst(
                currentTitle = "Severance (S1 E4)",
                incomingTitle = "The Bear (S1 E1)",
            ),
            decidePlaybackLaunch(
                activeState = activeState,
                activeLaunch = null,
                incomingTitle = "The Bear",
                videoId = secondVideoId,
                parentMetaId = "tt2222222",
                seasonNumber = 1,
                episodeNumber = 1,
            ),
        )
    }

    @Test
    fun starting_another_title_asks_first_and_keep_playing_leaves_active_session_alone() {
        val sessionController = PlayerSessionController()
        val events = mutableListOf<String>()
        val firstLaunch = sampleLaunch(
            title = "Severance",
            videoId = firstVideoId,
            parentMetaId = "tt1111111",
            sourceUrl = "https://example.com/first.mp4",
        )
        val secondLaunch = sampleLaunch(
            title = "The Bear",
            videoId = secondVideoId,
            parentMetaId = "tt2222222",
            sourceUrl = "https://example.com/second.mp4",
        )
        sessionController.open(launch = firstLaunch, launchId = 1L)
        sessionController.minimize()

        compose.setContent {
            NuvioTheme {
                ReplacePlaybackDialog(
                    pending = sessionController.pendingReplacement,
                    onConfirm = sessionController::confirmPendingReplacement,
                    onDismiss = sessionController::cancelPendingReplacement,
                )
            }
        }

        compose.runOnIdle {
            sessionController.launchOrAskToReplace(
                incomingTitle = secondLaunch.title,
                videoId = secondLaunch.videoId,
                parentMetaId = secondLaunch.parentMetaId,
                seasonNumber = secondLaunch.seasonNumber,
                episodeNumber = secondLaunch.episodeNumber,
            ) {
                events += "open-second"
                sessionController.open(launch = secondLaunch, launchId = 2L)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(text(Res.string.player_replace_playback_title)).assertIsDisplayed()
        compose.onNodeWithText("Severance (S1 E1) is currently playing. Start The Bear (S1 E1) instead?")
            .assertIsDisplayed()

        // Choosing Keep playing cancels replacement and leaves Severance playing in the mini player.
        compose.onNodeWithText(text(Res.string.player_replace_playback_keep)).performClick()
        compose.waitForIdle()

        assertNull(sessionController.pendingReplacement)
        assertEquals(emptyList(), events)
        assertEquals(1L, sessionController.activeSession?.launchId)
        assertTrue(sessionController.isMinimized)
        compose.onAllNodesWithText(text(Res.string.player_replace_playback_title)).assertCountEquals(0)
    }

    @Test
    fun confirming_replace_playback_flushes_progress_releases_first_player_before_opening_second() {
        val sessionController = PlayerSessionController()
        val events = mutableListOf<String>()
        lateinit var firstRuntime: PlayerScreenRuntime

        val firstEngine = object : PlayerEngineController {
            override fun play() = Unit
            override fun pause() = Unit
            override fun seekTo(positionMs: Long) = Unit
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
                events += "release-first"
                onReleased()
            }
        }

        val firstLaunch = sampleLaunch(
            title = "Severance",
            videoId = firstVideoId,
            parentMetaId = "tt1111111",
            sourceUrl = "https://example.com/first.mp4",
        )
        val secondLaunch = sampleLaunch(
            title = "The Bear",
            videoId = secondVideoId,
            parentMetaId = "tt2222222",
            sourceUrl = "https://example.com/second.mp4",
        )
        sessionController.open(launch = firstLaunch, launchId = 10L)
        sessionController.minimize()

        compose.setContent {
            NuvioTheme {
                val active = sessionController.activeSession
                if (active != null && active.launchId == 10L) {
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
                                scope = this@ReplacePlaybackPromptTest.scope
                                playerSettingsUiState = PlayerSettingsUiState(skipIntroEnabled = false)
                                playerController = firstEngine
                                playerLifecycleController = firstEngine
                                playerControllerSourceUrl = args.sourceUrl
                            }
                        }
                        runtime.args = args
                        firstRuntime = runtime
                        runtime.BindPlayerRuntimeEffects()
                        runtime.BindPlayerSessionBridge()
                        if (runtime.playerController == null) {
                            runtime.playerController = firstEngine
                            runtime.playerLifecycleController = firstEngine
                            runtime.playerControllerSourceUrl = runtime.activeSourceUrl
                        }
                    }
                }
                ReplacePlaybackDialog(
                    pending = sessionController.pendingReplacement,
                    onConfirm = sessionController::confirmPendingReplacement,
                    onDismiss = sessionController::cancelPendingReplacement,
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            firstRuntime.playerController = firstEngine
            firstRuntime.playerLifecycleController = firstEngine
            firstRuntime.playerControllerSourceUrl = firstRuntime.activeSourceUrl
            firstRuntime.initialLoadCompleted = true
            firstRuntime.updatePlaybackSnapshot(
                PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    positionMs = 440_000L,
                    durationMs = 1_600_000L,
                ),
            )
            sessionController.launchOrAskToReplace(
                incomingTitle = secondLaunch.title,
                videoId = secondLaunch.videoId,
                parentMetaId = secondLaunch.parentMetaId,
                seasonNumber = secondLaunch.seasonNumber,
                episodeNumber = secondLaunch.episodeNumber,
            ) {
                events += "open-second"
                sessionController.open(launch = secondLaunch, launchId = 20L)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(text(Res.string.player_replace_playback_confirm)).performClick()
        compose.waitForIdle()

        assertEquals(listOf("release-first", "open-second"), events)
        assertEquals(440_000L, WatchProgressRepository.progressForVideo(firstVideoId)?.lastPositionMs)
        assertEquals(20L, sessionController.activeSession?.launchId)
        assertEquals("The Bear", sessionController.activeSession?.launch?.title)
        assertTrue(sessionController.isFullScreen)
    }

    private fun sampleLaunch(
        title: String,
        videoId: String,
        parentMetaId: String,
        sourceUrl: String,
    ) = PlayerLaunch(
        profileId = ProfileRepository.activeProfileId,
        title = title,
        sourceUrl = sourceUrl,
        providerName = "Test",
        providerAddonId = "test",
        streamTitle = "Episode 1",
        seasonNumber = 1,
        episodeNumber = 1,
        episodeTitle = "Pilot",
        contentType = "series",
        videoId = videoId,
        parentMetaId = parentMetaId,
        parentMetaType = "series",
    )
}
