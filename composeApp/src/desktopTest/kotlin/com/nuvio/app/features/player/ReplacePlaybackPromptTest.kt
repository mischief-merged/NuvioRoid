package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.PlayerSessionHost
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryItemType
import com.nuvio.app.features.cloud.playbackVideoId
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.TabsRoute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import kotlin.test.assertFalse
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

    @Test
    fun cloud_library_files_sharing_parent_id_reopen_same_file_and_ask_before_replacing_different_file() {
        val cloudItem = CloudLibraryItem(
            providerId = "rd",
            providerName = "Cloud",
            id = "pack-42",
            type = CloudLibraryItemType.Torrent,
            name = "Archive Pack",
            files = listOf(
                CloudLibraryFile(id = "1", name = "Archive_01.mkv", playbackUrl = "https://example.com/archive-1.mkv"),
                CloudLibraryFile(id = "2", name = "Archive_02.mkv", playbackUrl = "https://example.com/archive-2.mkv"),
            ),
        )
        val fileOne = cloudItem.files[0]
        val fileTwo = cloudItem.files[1]
        val fileOneVideoId = cloudItem.playbackVideoId(fileOne)
        val fileTwoVideoId = cloudItem.playbackVideoId(fileTwo)

        val backend = CountingNativePlayerBackend()
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val navigator = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
        val firstCloudLaunch = PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = fileOne.name,
            sourceUrl = fileOne.playbackUrl!!,
            providerName = cloudItem.providerName,
            providerAddonId = "cloud:${cloudItem.providerId}",
            streamTitle = fileOne.name,
            contentType = "cloud",
            videoId = fileOneVideoId,
            parentMetaId = cloudItem.stableKey,
            parentMetaType = "cloud",
        )
        val secondCloudLaunch = firstCloudLaunch.copy(
            title = fileTwo.name,
            sourceUrl = fileTwo.playbackUrl!!,
            streamTitle = fileTwo.name,
            videoId = fileTwoVideoId,
        )

        sessionController.open(launch = firstCloudLaunch, launchId = 301L)
        mountPlayerSessionHost(sessionController, navigator, backend)
        compose.waitUntil(5_000L) { backend.createCount == 1 }

        compose.runOnIdle {
            sessionController.minimize()
            assertTrue(sessionController.isMinimized)

            // Selecting the same cloud file reopens Full mode without asking or creating a new player.
            assertTrue(
                sessionController.reopenIfMatching(
                    videoId = fileOneVideoId,
                    parentMetaId = cloudItem.stableKey,
                    seasonNumber = null,
                    episodeNumber = null,
                ),
            )
            assertTrue(sessionController.isFullScreen)
            assertNull(sessionController.pendingReplacement)
            assertEquals(1, backend.createCount)
            assertEquals(0, backend.disposeCount)

            // Minimize again and select a sibling cloud file sharing parentMetaId: must NOT reopen file 1.
            sessionController.minimize()
            assertFalse(
                sessionController.reopenIfMatching(
                    videoId = fileTwoVideoId,
                    parentMetaId = cloudItem.stableKey,
                    seasonNumber = null,
                    episodeNumber = null,
                ),
            )
            assertTrue(sessionController.isMinimized)

            sessionController.launchOrAskToReplace(
                incomingTitle = fileTwo.name,
                videoId = fileTwoVideoId,
                parentMetaId = cloudItem.stableKey,
                seasonNumber = null,
                episodeNumber = null,
                allowSameTargetReopen = false,
            ) {
                sessionController.open(launch = secondCloudLaunch, launchId = 302L)
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Archive_01.mkv is currently playing. Start Archive_02.mkv instead?")
            .assertIsDisplayed()
        assertEquals(1, backend.createCount)
        assertEquals(0, backend.disposeCount)

        compose.onNodeWithText(text(Res.string.player_replace_playback_confirm)).performClick()
        compose.waitUntil(5_000L) { backend.createCount == 2 && backend.disposeCount == 1 }
        compose.waitForIdle()

        assertEquals(1, backend.maxConcurrentHandles)
        assertEquals(listOf(1L), backend.disposedHandles.toList())
        assertEquals(302L, sessionController.activeSession?.launchId)
        assertEquals("Archive_02.mkv", sessionController.activeSession?.launch?.title)
        assertTrue(sessionController.isFullScreen)
    }

    @Test
    fun confirmed_replacement_while_back_release_is_in_flight_proceeds_once_after_release_completes() {
        val disposeStarted = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        val backend = CountingNativePlayerBackend(
            onDisposeHook = { handle ->
                if (handle == 1L) {
                    disposeStarted.countDown()
                    allowDispose.await(5, TimeUnit.SECONDS)
                }
            },
        )
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val navigator = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
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

        sessionController.open(launch = firstLaunch, launchId = 401L)
        mountPlayerSessionHost(sessionController, navigator, backend)
        compose.waitUntil(5_000L) { backend.createCount == 1 }

        // Pause first session so system Back triggers an asynchronous native release via PlayerBackReleaseGuard.
        compose.runOnIdle {
            backend.statesByHandle[1L]?.paused = true
            sessionController.togglePlayback()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            sessionController.closeActiveSession()
        }
        assertTrue(disposeStarted.await(5, TimeUnit.SECONDS))

        var openSecondCalls = 0
        compose.runOnIdle {
            // While back/close release is already in flight on handle 1, queue and confirm a replacement.
            sessionController.launchOrAskToReplace(
                incomingTitle = secondLaunch.title,
                videoId = secondLaunch.videoId,
                parentMetaId = secondLaunch.parentMetaId,
                seasonNumber = secondLaunch.seasonNumber,
                episodeNumber = secondLaunch.episodeNumber,
            ) {
                openSecondCalls += 1
                sessionController.open(launch = secondLaunch, launchId = 402L)
            }
            sessionController.confirmPendingReplacement()
            // Calling confirm a second time while teardown is still running must be a no-op.
            sessionController.confirmPendingReplacement()
        }
        compose.waitForIdle()

        // Replacement must wait until handle 1 finishes nativeDispose.
        assertEquals(0, openSecondCalls)
        assertEquals(1, backend.createCount)
        assertEquals(0, backend.disposeCount)

        allowDispose.countDown()
        compose.waitUntil(5_000L) { backend.createCount == 2 && backend.disposeCount == 1 }
        compose.waitForIdle()

        assertEquals(1, openSecondCalls)
        assertEquals(1, backend.maxConcurrentHandles)
        assertEquals(402L, sessionController.activeSession?.launchId)
        assertEquals("The Bear", sessionController.activeSession?.launch?.title)
    }

    @Test
    fun unbound_replacement_never_overlaps_two_native_players_and_proceeds_once() {
        val disposeStarted = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        val backend = CountingNativePlayerBackend(
            onDisposeHook = { handle ->
                if (handle == 1L) {
                    disposeStarted.countDown()
                    allowDispose.await(5, TimeUnit.SECONDS)
                }
            },
        )
        val hooks = backend.toDesktopHooks()
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val firstLaunch = sampleLaunch(
            title = "Severance",
            videoId = firstVideoId,
            parentMetaId = "tt1111111",
            sourceUrl = "https://example.com/unbound-first.mp4",
        )
        val secondLaunch = sampleLaunch(
            title = "The Bear",
            videoId = secondVideoId,
            parentMetaId = "tt2222222",
            sourceUrl = "https://example.com/unbound-second.mp4",
        )
        sessionController.open(launch = firstLaunch, launchId = 501L)

        // Mount PlatformPlayerSurface keyed by launchId WITHOUT binding PlayerSessionControls,
        // reproducing a replacement triggered before controls bind so teardown goes through onDispose -> dispose().
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalDesktopNativePlayerHooks provides hooks) {
                    val active = sessionController.activeSession
                    if (active != null) {
                        key(active.launchId) {
                            PlatformPlayerSurface(
                                sourceUrl = active.launch.sourceUrl,
                                modifier = Modifier.fillMaxSize(),
                                useNativeController = true,
                                onControllerReady = {},
                                onSnapshot = {},
                                onError = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000L) { backend.createCount == 1 }

        var openSecondCalls = 0
        compose.runOnIdle {
            sessionController.launchOrAskToReplace(
                incomingTitle = secondLaunch.title,
                videoId = secondLaunch.videoId,
                parentMetaId = secondLaunch.parentMetaId,
                seasonNumber = secondLaunch.seasonNumber,
                episodeNumber = secondLaunch.episodeNumber,
            ) {
                openSecondCalls += 1
                sessionController.open(launch = secondLaunch, launchId = 502L)
            }
            sessionController.confirmPendingReplacement()
        }
        compose.waitForIdle()

        // Session 2 is already the active session, and Surface 1 is tearing down on nuvio-player-dispose,
        // yet Surface 2 must wait until handle 1 finishes disposing before creating handle 2.
        assertTrue(disposeStarted.await(5, TimeUnit.SECONDS))
        assertEquals(1, openSecondCalls)
        assertEquals(502L, sessionController.activeSession?.launchId)
        assertEquals(1, backend.createCount)

        allowDispose.countDown()
        compose.waitUntil(5_000L) { backend.createCount == 2 && backend.disposeCount == 1 }
        compose.waitForIdle()

        assertEquals(1, openSecondCalls)
        assertEquals(1, backend.maxConcurrentHandles)
        assertEquals(listOf(1L), backend.disposedHandles.toList())
        assertEquals(
            listOf(
                "https://example.com/unbound-first.mp4",
                "https://example.com/unbound-second.mp4",
            ),
            backend.createdSources.toList(),
        )
    }

    @Test
    fun external_playback_reopens_same_target_and_awaits_native_teardown_before_replacing_active_session() {
        val disposeStarted = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        val backend = CountingNativePlayerBackend(
            onDisposeHook = {
                disposeStarted.countDown()
                allowDispose.await(5, TimeUnit.SECONDS)
            },
        )
        val sessionController = PlayerSessionController(onExitPictureInPicture = {})
        val navigator = NuvioNavigator(NavBackStack<NavKey>(TabsRoute))
        val firstLaunch = sampleLaunch(
            title = "Severance",
            videoId = firstVideoId,
            parentMetaId = "tt1111111",
            sourceUrl = "https://example.com/severance-s1e1.mp4",
        )
        val secondLaunch = sampleLaunch(
            title = "The Bear",
            videoId = secondVideoId,
            parentMetaId = "tt2222222",
            sourceUrl = "file:///tmp/the-bear-s1e1.mkv",
        )
        val externalLaunched = mutableListOf<String>()

        sessionController.open(launch = firstLaunch, launchId = 601L)
        mountPlayerSessionHost(sessionController, navigator, backend)
        compose.waitUntil(5_000L) { backend.createCount == 1 }

        compose.runOnIdle {
            backend.statesByHandle[1L]?.apply {
                loading = false
                paused = false
                positionMs = 510_000L
                durationMs = 1_800_000L
            }
        }
        compose.mainClock.advanceTimeBy(300L)
        compose.waitForIdle()

        compose.runOnIdle {
            sessionController.minimize()
            assertTrue(sessionController.isMinimized)
            // Same playing target with external player enabled reopens the active session instead of spawning a duplicate player.
            sessionController.launchExternalOrAskToReplace(
                launch = firstLaunch,
                allowSameTargetReopen = true,
            ) {
                externalLaunched += firstLaunch.sourceUrl
            }
        }
        compose.waitForIdle()
        assertTrue(sessionController.isFullScreen)
        assertTrue(externalLaunched.isEmpty())
        assertEquals(0, backend.disposeCount)

        // Minimize again and request a different title for external playback: prompts first.
        compose.runOnIdle {
            sessionController.minimize()
            sessionController.launchExternalOrAskToReplace(
                launch = secondLaunch,
                allowSameTargetReopen = false,
            ) {
                externalLaunched += secondLaunch.sourceUrl
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.player_replace_playback_title)).assertIsDisplayed()

        // Keep playing leaves the minimized session untouched and launches nothing externally.
        compose.onNodeWithText(text(Res.string.player_replace_playback_keep)).performClick()
        compose.waitForIdle()
        assertTrue(sessionController.isMinimized)
        assertTrue(externalLaunched.isEmpty())
        assertEquals(0, backend.disposeCount)

        // Confirm replacement: external launch waits until watch progress is flushed and native disposal finishes.
        compose.runOnIdle {
            sessionController.launchExternalOrAskToReplace(
                launch = secondLaunch,
                allowSameTargetReopen = false,
            ) {
                externalLaunched += secondLaunch.sourceUrl
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(text(Res.string.player_replace_playback_confirm)).performClick()

        assertTrue(disposeStarted.await(5, TimeUnit.SECONDS))
        assertTrue(externalLaunched.isEmpty(), "external player must not launch while native teardown is still running")

        allowDispose.countDown()
        compose.waitUntil(5_000L) { externalLaunched.size == 1 }
        compose.waitForIdle()

        assertFalse(sessionController.hasActiveSession)
        assertEquals(1, backend.disposeCount)
        assertEquals(0, backend.activeHandleCount)
        assertEquals(listOf(secondLaunch.sourceUrl), externalLaunched)
        assertEquals(510_000L, WatchProgressRepository.progressForVideo(firstVideoId)?.lastPositionMs)
    }

    private fun mountPlayerSessionHost(
        sessionController: PlayerSessionController,
        navigator: NuvioNavigator,
        backend: CountingNativePlayerBackend,
    ) {
        val hooks = backend.toDesktopHooks()
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
