package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.controller.ControllerDirection
import com.nuvio.app.controller.ControllerEvent
import com.nuvio.app.controller.XboxButton
import com.nuvio.app.core.ui.DesktopEscapeShortcut
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerControllerCommandTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() {
        scope.cancel()
        PlayerStreamsRepository.stopSourcesLoading()
        PlayerStreamsRepository.clearEpisodeStreams()
        PluginRepository.setLocalPluginSearchPaused(false)
    }

    private fun createRuntime(
        isSeries: Boolean = true,
        videoId: String? = if (isSeries) "tt1234567:1:2" else "tt1234567",
        onExit: () -> Unit = {},
    ): PlayerScreenRuntime = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = if (isSeries) "Example Show" else "Example Movie",
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
            onBack = { _ -> onExit() },
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = if (isSeries) 1 else null,
            episodeNumber = if (isSeries) 2 else null,
            episodeTitle = if (isSeries) "Episode 2" else null,
            episodeThumbnail = null,
            contentType = if (isSeries) "series" else "movie",
            videoId = videoId,
            parentMetaId = "tt1234567",
            parentMetaType = if (isSeries) "series" else "movie",
            providerAddonId = "addon",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@PlayerControllerCommandTest.scope
        if (isSeries) {
            playerMetaVideos = listOf(
                MetaVideo(id = "tt1234567:1:1", title = "Episode 1", season = 1, episode = 1),
                MetaVideo(id = "tt1234567:1:2", title = "Episode 2", season = 1, episode = 2),
            )
        }
    }

    @Test
    fun gamepad_back_dismisses_audio_and_subtitle_modals_before_exiting_playback() {
        var exitCalls = 0
        val runtime = createRuntime(onExit = { exitCalls += 1 })

        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Audio))
        assertTrue(runtime.showAudioModal)
        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
        assertFalse(runtime.showAudioModal)
        assertEquals(0, exitCalls)

        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Subtitles))
        assertTrue(runtime.showSubtitleModal)
        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
        assertFalse(runtime.showSubtitleModal)
        assertEquals(0, exitCalls)

        // With no modal open, Back exits playback.
        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
        assertEquals(1, exitCalls)
    }

    @Test
    fun gamepad_episodes_and_sources_commands_open_panels_and_back_closes_them() {
        var exitCalls = 0
        val runtime = createRuntime(isSeries = true, onExit = { exitCalls += 1 })

        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Episodes))
        assertTrue(runtime.showEpisodesPanel)
        assertFalse(runtime.showSourcesPanel)
        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
        assertFalse(runtime.showEpisodesPanel)
        assertEquals(0, exitCalls)

        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Sources))
        assertTrue(runtime.showSourcesPanel)
        assertFalse(runtime.showEpisodesPanel)
        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
        assertFalse(runtime.showSourcesPanel)
        assertEquals(0, exitCalls)
    }

    @Test
    fun gamepad_episodes_returns_false_for_movies_and_sources_returns_false_without_video_id() {
        val movieRuntime = createRuntime(isSeries = false, videoId = null)

        assertFalse(movieRuntime.handlePlayerControllerCommand(PlayerCommand.Episodes))
        assertFalse(movieRuntime.showEpisodesPanel)

        assertFalse(movieRuntime.handlePlayerControllerCommand(PlayerCommand.Sources))
        assertFalse(movieRuntime.showSourcesPanel)
    }

    @Test
    fun gamepad_back_runs_registered_desktop_modal_handler_before_exiting_playback() {
        var exitCalls = 0
        var modalDismissals = 0
        val runtime = createRuntime(onExit = { exitCalls += 1 })
        val modalHandler: () -> Unit = { modalDismissals += 1 }

        DesktopEscapeShortcut.registerModal(modalHandler)
        try {
            assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
            assertEquals(1, modalDismissals)
            assertEquals(0, exitCalls)
        } finally {
            DesktopEscapeShortcut.unregisterModal(modalHandler)
        }
    }

    @Test
    fun controller_navigation_and_select_yield_while_player_panel_is_open_and_back_closes_it() {
        var exitCalls = 0
        val runtime = createRuntime(isSeries = true, onExit = { exitCalls += 1 })
        val dispatched = mutableListOf<PlayerCommand>()
        val dispatch: (PlayerCommand) -> Boolean = { cmd ->
            dispatched += cmd
            runtime.handlePlayerControllerCommand(cmd)
        }

        // Open Episodes panel via Menu button.
        assertTrue(runtime.handlePlayerControllerEvent(ControllerEvent.ButtonPressed(XboxButton.Menu), modalOpen = false, dispatch = dispatch, onNavigateHandled = {}))
        assertTrue(runtime.showEpisodesPanel)
        assertTrue(runtime.hasOpenPlayerOverlay)

        // While panel is open, D-pad and A yield (return false) so Compose focus operates the panel.
        dispatched.clear()
        assertFalse(runtime.handlePlayerControllerEvent(ControllerEvent.Navigate(ControllerDirection.Down), modalOpen = false, dispatch = dispatch, onNavigateHandled = {}))
        assertFalse(runtime.handlePlayerControllerEvent(ControllerEvent.Navigate(ControllerDirection.Right), modalOpen = false, dispatch = dispatch, onNavigateHandled = {}))
        assertFalse(runtime.handlePlayerControllerEvent(ControllerEvent.ButtonPressed(XboxButton.A), modalOpen = false, dispatch = dispatch, onNavigateHandled = {}))
        assertFalse(runtime.handlePlayerControllerEvent(ControllerEvent.ButtonPressed(XboxButton.X), modalOpen = false, dispatch = dispatch, onNavigateHandled = {}))
        assertTrue(dispatched.isEmpty(), "playback commands must not fire while overlay is open: $dispatched")

        // Pressing B closes the panel without exiting playback.
        assertTrue(runtime.handlePlayerControllerEvent(ControllerEvent.ButtonPressed(XboxButton.B), modalOpen = false, dispatch = dispatch, onNavigateHandled = {}))
        assertFalse(runtime.showEpisodesPanel)
        assertFalse(runtime.hasOpenPlayerOverlay)
        assertEquals(0, exitCalls)

        // Once closed, A toggles playback again.
        dispatched.clear()
        assertTrue(runtime.handlePlayerControllerEvent(ControllerEvent.ButtonPressed(XboxButton.A), modalOpen = false, dispatch = dispatch, onNavigateHandled = {}))
        assertEquals(listOf(PlayerCommand.TogglePlayback), dispatched)
    }

    @Test
    fun native_player_popup_forwards_gamepad_navigation_and_select_and_back_closes_popup_before_exiting() {
        var exitCalls = 0
        val runtime = createRuntime(isSeries = true, onExit = { exitCalls += 1 })
        val initialCloseToken = runtime.playerControlsCloseModalsToken
        val initialNavToken = runtime.playerControlsModalNavigationToken

        // Native macOS WebView popup (e.g. Audio, Subtitles, or Speed) opens.
        assertTrue(runtime.handlePlayerControlsEvent("nativeModalState", 1.0))
        assertTrue(runtime.nativeControlsModalOpen)
        assertTrue(runtime.hasOpenPlayerOverlay)

        // D-pad and A forward into the native popup (even when PlatformModalBackHandler marks modalOpen = true).
        assertTrue(runtime.handlePlayerControllerEvent(ControllerEvent.Navigate(ControllerDirection.Down), modalOpen = true, dispatch = runtime::handlePlayerControllerCommand, onNavigateHandled = {}))
        assertEquals("down", runtime.playerControlsModalNavigationAction)
        assertEquals(initialNavToken + 1L, runtime.playerControlsModalNavigationToken)

        // Repeated Down must produce another update while the popup rebuilds its rows.
        assertTrue(runtime.handlePlayerControllerEvent(ControllerEvent.Navigate(ControllerDirection.Down), modalOpen = true, dispatch = runtime::handlePlayerControllerCommand, onNavigateHandled = {}))
        assertEquals("down", runtime.playerControlsModalNavigationAction)
        assertEquals(initialNavToken + 2L, runtime.playerControlsModalNavigationToken)

        assertTrue(runtime.handlePlayerControllerEvent(ControllerEvent.Navigate(ControllerDirection.Up), modalOpen = true, dispatch = runtime::handlePlayerControllerCommand, onNavigateHandled = {}))
        assertEquals("up", runtime.playerControlsModalNavigationAction)
        assertEquals(initialNavToken + 3L, runtime.playerControlsModalNavigationToken)

        assertTrue(runtime.handlePlayerControllerEvent(ControllerEvent.ButtonPressed(XboxButton.A), modalOpen = true, dispatch = runtime::handlePlayerControllerCommand, onNavigateHandled = {}))
        assertEquals("select", runtime.playerControlsModalNavigationAction)
        assertEquals(initialNavToken + 4L, runtime.playerControlsModalNavigationToken)

        // First Back closes the native popup via closeModalsToken without exiting playback.
        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
        assertFalse(runtime.nativeControlsModalOpen)
        assertEquals(initialCloseToken + 1L, runtime.playerControlsCloseModalsToken)
        assertEquals(0, exitCalls)

        // Second Back exits playback now that the native popup is closed.
        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
        assertEquals(1, exitCalls)
    }

    @Test
    fun back_dismisses_desktop_p2p_consent_popup_and_clears_pending_switch_in_one_step() {
        var exitCalls = 0
        val runtime = createRuntime(isSeries = true, onExit = { exitCalls += 1 })
        val initialCloseToken = runtime.playerControlsCloseModalsToken

        runtime.playerControlsPendingP2pSwitch = PendingPlayerP2pSwitch(
            stream = StreamItem(name = "P2P", infoHash = "abcdef1234567890", addonName = "Addon", addonId = "addon"),
            episode = null,
            isAutoPlay = false,
        )
        assertTrue(runtime.handlePlayerControlsEvent("nativeModalState", 1.0))

        // First Back clears BOTH nativeControlsModalOpen and playerControlsPendingP2pSwitch so controls.js cannot re-open p2pConsent.
        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
        assertFalse(runtime.nativeControlsModalOpen)
        assertNull(runtime.playerControlsPendingP2pSwitch)
        assertEquals(initialCloseToken + 1L, runtime.playerControlsCloseModalsToken)
        assertEquals(0, exitCalls)

        // Second Back leaves playback cleanly.
        assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
        assertEquals(1, exitCalls)
    }

    @Test
    fun back_closes_native_popup_before_dismissing_local_next_episode_card_handler() {
        var exitCalls = 0
        var nextEpisodeCardDismissals = 0
        val runtime = createRuntime(isSeries = true, onExit = { exitCalls += 1 })
        val localNextEpisodeHandler: () -> Unit = { nextEpisodeCardDismissals += 1 }

        DesktopEscapeShortcut.registerLocal(localNextEpisodeHandler)
        try {
            assertTrue(runtime.handlePlayerControlsEvent("nativeModalState", 1.0))

            // First Back closes the open native Audio/Subtitles popup while leaving the next-episode card visible.
            assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
            assertFalse(runtime.nativeControlsModalOpen)
            assertEquals(0, nextEpisodeCardDismissals)
            assertEquals(0, exitCalls)

            // Second Back dismisses the local next-episode card handler.
            assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
            assertEquals(1, nextEpisodeCardDismissals)
            assertEquals(0, exitCalls)

            DesktopEscapeShortcut.unregisterLocal(localNextEpisodeHandler)

            // Third Back exits playback.
            assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.Back))
            assertEquals(1, exitCalls)
        } finally {
            DesktopEscapeShortcut.unregisterLocal(localNextEpisodeHandler)
        }
    }

    @Test
    fun player_controller_bridge_reports_overlay_open_state_for_desktop_keyboard_navigation() {
        val runtime = createRuntime(isSeries = true)
        PlayerControllerBridge.register(
            scope = null,
            handler = runtime::handlePlayerControllerCommand,
            isOverlayOpen = { runtime.hasOpenPlayerOverlay },
        )
        try {
            assertFalse(PlayerControllerBridge.isOverlayOpen())
            runtime.showSourcesPanel = true
            assertTrue(PlayerControllerBridge.isOverlayOpen())
            runtime.showSourcesPanel = false
            assertFalse(PlayerControllerBridge.isOverlayOpen())
            runtime.handlePlayerControlsEvent("nativeModalState", 1.0)
            assertTrue(PlayerControllerBridge.isOverlayOpen())
        } finally {
            PlayerControllerBridge.register(null, null, null)
        }
    }
}
