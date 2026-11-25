package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.controller.ControllerDirection
import com.nuvio.app.controller.ControllerEvent
import com.nuvio.app.controller.ControllerFocusEngine
import com.nuvio.app.controller.XboxButton
import com.nuvio.app.core.ui.DesktopEscapeShortcut
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule

class PlayerControllerInputRegistrationTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() {
        ControllerFocusEngine.setPlayerEventHandler(null)
        PlayerControllerBridge.register(null, null, null)
        scope.cancel()
    }

    @Test
    fun controller_input_is_registered_only_while_the_full_player_owns_the_screen() {
        val runtime = sampleRuntime()
        var ownsInput by mutableStateOf(true)
        compose.setContent {
            PlayerControllerInputRegistration(
                runtime = runtime,
                scope = scope,
                ownsInput = ownsInput,
            )
        }
        compose.waitForIdle()

        assertTrue(PlayerControllerBridge.dispatch(PlayerCommand.SeekBack))
        assertTrue(
            ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.A)),
        )

        ownsInput = false
        compose.waitForIdle()

        assertFalse(PlayerControllerBridge.dispatch(PlayerCommand.SeekBack))
        assertFalse(
            ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.A)),
        )
    }

    @Test
    fun native_popup_keeps_repeated_navigation_and_select_in_the_player() {
        val runtime = sampleRuntime()
        compose.setContent {
            PlayerControllerInputRegistration(runtime = runtime, scope = scope, ownsInput = true)
        }
        compose.waitForIdle()
        assertTrue(runtime.handlePlayerControlsEvent("nativeModalState", 1.0))
        val initialToken = runtime.playerControlsModalNavigationToken
        val initialPlaying = runtime.playbackSnapshot.isPlaying

        repeat(2) { index ->
            assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.Navigate(ControllerDirection.Down)))
            compose.waitUntil { runtime.playerControlsModalNavigationToken == initialToken + index + 1L }
            assertEquals("down", runtime.playerControlsModalNavigationAction)
            assertEquals(initialToken + index + 1L, runtime.playerControlsModalNavigationToken)
        }
        assertTrue(ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.A)))
        compose.waitUntil { runtime.playerControlsModalNavigationToken == initialToken + 3L }
        assertEquals("select", runtime.playerControlsModalNavigationAction)
        assertEquals(initialToken + 3L, runtime.playerControlsModalNavigationToken)
        assertEquals(initialPlaying, runtime.playbackSnapshot.isPlaying)
        assertTrue(runtime.nativeControlsModalOpen)
    }

    @Test
    fun leaving_the_full_player_drops_popups_and_ignores_a_late_native_modal_event() {
        val runtime = sampleRuntime()
        val pending = PendingPlayerP2pSwitch(
            stream = StreamItem(name = "P2P", infoHash = "abcdef1234567890", addonName = "Addon", addonId = "addon"),
            episode = null,
            isAutoPlay = false,
        )
        runtime.nativeControlsModalOpen = true
        runtime.playerControlsPendingP2pSwitch = pending
        runtime.pendingP2pSwitch = pending
        runtime.showAudioModal = true

        runtime.closeModalsForMiniPlayer()

        assertFalse(runtime.nativeControlsModalOpen)
        assertNull(runtime.playerControlsPendingP2pSwitch)
        assertNull(runtime.pendingP2pSwitch)
        assertFalse(runtime.showAudioModal)

        runtime.args = runtime.args.copy(isMinimized = true)
        assertTrue(runtime.handlePlayerControlsEvent("nativeModalState", 1.0))
        assertFalse(runtime.nativeControlsModalOpen)

        runtime.args = runtime.args.copy(isMinimized = false)
        runtime.pictureInPictureActive = true
        assertTrue(runtime.handlePlayerControlsEvent("nativeModalState", 1.0))
        assertFalse(runtime.nativeControlsModalOpen)

        runtime.pictureInPictureActive = false
        assertTrue(runtime.handlePlayerControlsEvent("nativeModalState", 1.0))
        assertTrue(runtime.nativeControlsModalOpen)
        assertTrue(playerOwnsControllerInput(isMinimized = false, isInPictureInPicture = false))
        assertFalse(playerOwnsControllerInput(isMinimized = true, isInPictureInPicture = false))
        assertFalse(playerOwnsControllerInput(isMinimized = false, isInPictureInPicture = true))
    }

    @Test
    fun dismissing_an_overlay_does_not_reenter_the_session_back_handler() {
        val runtime = sampleRuntime()
        var backs = 0
        val handler: () -> Unit = {
            if (!runtime.dismissOpenPlayerOverlay()) backs += 1
        }
        DesktopEscapeShortcut.registerLocal(handler)
        try {
            runtime.showAudioModal = true
            handler()
            assertFalse(runtime.showAudioModal)
            assertEquals(0, backs)

            handler()
            assertEquals(1, backs)

            runtime.showSubtitleModal = true
            assertTrue(runtime.dismissOpenPlayerOverlay())
            assertFalse(runtime.showSubtitleModal)
            assertEquals(1, backs)

            assertTrue(runtime.dismissOpenPlayerOverlay())
            assertEquals(2, backs)
            assertEquals(0, runtime.overlayDismissDepth)
        } finally {
            DesktopEscapeShortcut.unregisterLocal(handler)
        }
    }

    private fun sampleRuntime() = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Example",
            sourceUrl = "https://cdn.example/video.mkv",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Addon",
            streamTitle = "1080p",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = { _ -> },
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            contentType = "movie",
            videoId = "tt1",
            parentMetaId = "tt1",
            parentMetaType = "movie",
            providerAddonId = "addon",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    )
}
