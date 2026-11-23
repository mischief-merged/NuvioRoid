package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleOffsetMemoryTest {

    @Test
    fun subtitleOffsetIsRememberedAcrossEpisodesOfSameShow() {
        val showId = "tt-offset-show-a-${System.nanoTime()}"
        val otherShowId = "tt-offset-show-b-${System.nanoTime()}"
        val ep1VideoId = "$showId:1:1"
        val ep2VideoId = "$showId:1:2"
        val ep3VideoId = "$showId:2:1"

        assertNull(loadShowSubtitleDelayMs(parentMetaId = showId, videoId = ep1VideoId))

        saveShowSubtitleDelayMs(parentMetaId = showId, videoId = ep1VideoId, delayMs = 450)

        assertEquals(450, loadShowSubtitleDelayMs(parentMetaId = showId, videoId = ep1VideoId))
        assertEquals(450, loadShowSubtitleDelayMs(parentMetaId = showId, videoId = ep2VideoId))
        assertEquals(450, loadShowSubtitleDelayMs(parentMetaId = showId, videoId = ep3VideoId))
        assertNull(loadShowSubtitleDelayMs(parentMetaId = otherShowId, videoId = "$otherShowId:1:1"))
    }

    @Test
    fun resettingShowOffsetToZeroOverridesPreviousAndLegacyEpisodeDelays() {
        val showId = "tt-offset-reset-${System.nanoTime()}"
        val ep1VideoId = "$showId:1:1"
        val ep2VideoId = "$showId:1:2"

        PlayerTrackPreferenceStorage.saveSubtitleDelayMs(ep1VideoId, -600)
        assertEquals(-600, loadShowSubtitleDelayMs(parentMetaId = showId, videoId = ep1VideoId))

        saveShowSubtitleDelayMs(parentMetaId = showId, videoId = ep2VideoId, delayMs = 0)

        assertEquals(0, loadShowSubtitleDelayMs(parentMetaId = showId, videoId = ep1VideoId))
        assertEquals(0, loadShowSubtitleDelayMs(parentMetaId = showId, videoId = ep2VideoId))
    }

    @Test
    fun standaloneBlankParentMetaIdFallsBackToVideoIdAndClampsBounds() {
        val videoId = "standalone-${System.nanoTime()}"

        assertEquals(videoId, subtitleDelayStorageKey(parentMetaId = "  ", videoId = videoId))

        saveShowSubtitleDelayMs(parentMetaId = "", videoId = videoId, delayMs = 999_999)
        assertEquals(SUBTITLE_DELAY_MAX_MS, loadShowSubtitleDelayMs(parentMetaId = "", videoId = videoId))

        saveShowSubtitleDelayMs(parentMetaId = "", videoId = videoId, delayMs = -999_999)
        assertEquals(SUBTITLE_DELAY_MIN_MS, loadShowSubtitleDelayMs(parentMetaId = "", videoId = videoId))
    }
}
