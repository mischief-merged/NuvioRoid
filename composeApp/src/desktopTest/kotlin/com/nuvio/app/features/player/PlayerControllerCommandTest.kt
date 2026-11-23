package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.DesktopEscapeShortcut
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.plugins.PluginRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
