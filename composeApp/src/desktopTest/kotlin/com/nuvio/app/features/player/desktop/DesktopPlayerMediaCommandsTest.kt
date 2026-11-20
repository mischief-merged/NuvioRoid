package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.skip.MediaTrackCommand
import com.nuvio.app.features.player.skip.MediaTrackResolution
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.player.skip.resolveMediaTrackCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlayerMediaCommandsTest {
    @Test
    fun `mediaNext and mediaPrevious delegate to onEvent without seeking when runtime handles episode switch`() {
        val host = NativePlayerHost()
        val seekOffsets = mutableListOf<Long>()
        val events = mutableListOf<String>()
        val controller = NativePlayerController(
            host = host,
            nativeCreate = { _, _, _, _, _, _, _, _, _ -> error("unused") },
            nativeDispose = {},
            nativeSeekBy = { _, offset -> seekOffsets += offset },
            nativeUpdateControls = { _, _ -> },
        )
        controller.setNativeHandleForTest(42L)
        controller.setControlCallbacks(
            onAction = { false },
            onEvent = { type, _ ->
                events += type
                true
            },
            onScrubChange = { false },
            onScrubFinished = { false },
        )

        controller.handlePlayerEventForTest("mediaNext", 0.0)
        controller.handlePlayerEventForTest("mediaPrevious", 0.0)

        assertEquals(listOf("mediaNext", "mediaPrevious"), events)
        assertTrue(seekOffsets.isEmpty())
    }

    @Test
    fun `mediaNext and mediaPrevious fall back to 10 second seek when onEvent does not handle them`() {
        val host = NativePlayerHost()
        val seekOffsets = mutableListOf<Long>()
        val controller = NativePlayerController(
            host = host,
            nativeCreate = { _, _, _, _, _, _, _, _, _ -> error("unused") },
            nativeDispose = {},
            nativeSeekBy = { _, offset -> seekOffsets += offset },
            nativeUpdateControls = { _, _ -> },
        )
        controller.setNativeHandleForTest(42L)
        controller.setControlCallbacks(
            onAction = { false },
            onEvent = { _, _ -> false },
            onScrubChange = { false },
            onScrubFinished = { false },
        )

        controller.handlePlayerEventForTest("mediaNext", 0.0)
        controller.handlePlayerEventForTest("mediaPrevious", 0.0)

        assertEquals(listOf(10_000L, -10_000L), seekOffsets)
    }

    @Test
    fun `resolvePreviousEpisode walks backward across episodes and seasons`() {
        val videos = listOf(
            MetaVideo(id = "ep-1-2", title = "S1E2", season = 1, episode = 2),
            MetaVideo(id = "ep-2-1", title = "S2E1", season = 2, episode = 1),
            MetaVideo(id = "ep-1-1", title = "S1E1", season = 1, episode = 1),
        )

        assertEquals(
            "ep-1-2",
            PlayerNextEpisodeRules.resolvePreviousEpisode(videos, currentSeason = 2, currentEpisode = 1)?.id,
        )
        assertEquals(
            "ep-1-1",
            PlayerNextEpisodeRules.resolvePreviousEpisode(videos, currentSeason = 1, currentEpisode = 2)?.id,
        )
        assertNull(
            PlayerNextEpisodeRules.resolvePreviousEpisode(videos, currentSeason = 1, currentEpisode = 1),
        )
    }

    @Test
    fun `resolveMediaTrackCommand switches episodes for series and falls back to seek for movies or boundaries`() {
        assertEquals(
            MediaTrackResolution.SWITCH_NEXT_EPISODE,
            resolveMediaTrackCommand(
                command = MediaTrackCommand.NEXT,
                isSeries = true,
                hasPlayableNextEpisode = true,
                hasPlayablePreviousEpisode = true,
                isEpisodeSwitchInProgress = false,
            ),
        )
        assertEquals(
            MediaTrackResolution.SWITCH_PREVIOUS_EPISODE,
            resolveMediaTrackCommand(
                command = MediaTrackCommand.PREVIOUS,
                isSeries = true,
                hasPlayableNextEpisode = true,
                hasPlayablePreviousEpisode = true,
                isEpisodeSwitchInProgress = false,
            ),
        )
        assertEquals(
            MediaTrackResolution.IGNORE_WHILE_SWITCHING,
            resolveMediaTrackCommand(
                command = MediaTrackCommand.NEXT,
                isSeries = true,
                hasPlayableNextEpisode = true,
                hasPlayablePreviousEpisode = true,
                isEpisodeSwitchInProgress = true,
            ),
        )
        assertEquals(
            MediaTrackResolution.SEEK_BACKWARD,
            resolveMediaTrackCommand(
                command = MediaTrackCommand.PREVIOUS,
                isSeries = true,
                hasPlayableNextEpisode = true,
                hasPlayablePreviousEpisode = false,
                isEpisodeSwitchInProgress = false,
            ),
        )
        assertEquals(
            MediaTrackResolution.SEEK_FORWARD,
            resolveMediaTrackCommand(
                command = MediaTrackCommand.NEXT,
                isSeries = false,
                hasPlayableNextEpisode = false,
                hasPlayablePreviousEpisode = false,
                isEpisodeSwitchInProgress = false,
            ),
        )
    }
}

private fun NativePlayerController.setNativeHandleForTest(value: Long) {
    javaClass.getDeclaredField("handle").also { field ->
        field.isAccessible = true
        field.setLong(this, value)
    }
}

private fun NativePlayerController.handlePlayerEventForTest(type: String, value: Double) {
    javaClass.getDeclaredMethod("handlePlayerEvent", String::class.java, Double::class.javaPrimitiveType).also { method ->
        method.isAccessible = true
        method.invoke(this, type, value)
    }
}
