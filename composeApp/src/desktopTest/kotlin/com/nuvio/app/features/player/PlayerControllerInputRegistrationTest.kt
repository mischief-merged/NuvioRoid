package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.controller.ControllerEvent
import com.nuvio.app.controller.ControllerFocusEngine
import com.nuvio.app.controller.XboxButton
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
        val runtime = PlayerScreenRuntime(
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
    fun leaving_the_full_player_drops_popups_and_ignores_a_late_native_modal_event() {
        val runtime = PlayerScreenRuntime(
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
}
