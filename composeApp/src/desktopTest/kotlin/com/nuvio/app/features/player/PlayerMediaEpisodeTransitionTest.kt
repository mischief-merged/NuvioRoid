package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerMediaEpisodeTransitionTest {
    @Test
    fun `previous search shows the requested episode and previous heading`() = runTest {
        val runtime = runtime(offline = false).apply { scope = backgroundScope }

        assertTrue(runtime.handlePlayerControlsEvent("mediaPrevious", 0.0))

        assertTrue(runtime.nextEpisodeAutoPlaySearching)
        val card = assertNotNull(runtime.episodeCardState)
        assertEquals("show:1:4", card.episode.videoId)
        assertEquals("Episode 4", card.episode.title)
        assertEquals("https://example.com/4.jpg", card.episode.thumbnail)
        assertTrue(card.isPrevious)
        assertEquals("show:1:6", runtime.nextEpisodeInfo?.videoId)
    }

    @Test
    fun `previous countdown keeps the requested episode and previous heading`() = runTest {
        val runtime = runtime(offline = false).apply { scope = backgroundScope }
        runtime.handlePlayerControlsEvent("mediaPrevious", 0.0)
        runtime.nextEpisodeAutoPlaySearching = false
        runtime.nextEpisodeAutoPlaySourceName = "Provider"
        runtime.nextEpisodeAutoPlayCountdown = 3

        val card = assertNotNull(runtime.episodeCardState)
        assertEquals("show:1:4", card.episode.videoId)
        assertTrue(card.isPrevious)

        runtime.cancelNextEpisodeAutoPlay()
        runtime.showNextEpisodeCard = true
        assertEquals("show:1:6", runtime.episodeCardState?.episode?.videoId)
        assertFalse(assertNotNull(runtime.episodeCardState).isPrevious)
    }

    @Test
    fun `offline next without a download falls back to forward seek`() = runTest {
        val runtime = runtime(offline = true).apply { scope = backgroundScope }
        assertEquals(listOf(10_000L), sendMediaEvent(runtime, "mediaNext"))
        assertFalse(runtime.nextEpisodeAutoPlaySearching)
        assertNull(runtime.nextEpisodeAutoPlayCountdown)
        assertEquals("show:1:5", runtime.activeVideoId)
    }

    @Test
    fun `offline previous without a download falls back to backward seek`() = runTest {
        val runtime = runtime(offline = true).apply { scope = backgroundScope }
        assertEquals(listOf(-10_000L), sendMediaEvent(runtime, "mediaPrevious"))
        assertFalse(runtime.nextEpisodeAutoPlaySearching)
        assertNull(runtime.nextEpisodeAutoPlayCountdown)
        assertEquals("show:1:5", runtime.activeVideoId)
    }

    @Test
    fun `next with missing episode metadata falls back to forward seek`() = runTest {
        val runtime = runtime(offline = false).apply {
            scope = backgroundScope
            playerMetaVideos = playerMetaVideos.filterNot { it.episode == 6 }
        }
        assertEquals(listOf(10_000L), sendMediaEvent(runtime, "mediaNext"))
    }

    @Test
    fun `online next starts a search without seeking and retains the next heading`() = runTest {
        val runtime = runtime(offline = false).apply { scope = backgroundScope }
        assertTrue(sendMediaEvent(runtime, "mediaNext").isEmpty())
        assertTrue(runtime.nextEpisodeAutoPlaySearching)
        assertEquals("show:1:6", runtime.episodeCardState?.episode?.videoId)
        assertFalse(assertNotNull(runtime.episodeCardState).isPrevious)
    }

    @Test
    fun `media commands during a previous search do not seek or replace its card`() = runTest {
        val runtime = runtime(offline = false).apply { scope = backgroundScope }
        runtime.handlePlayerControlsEvent("mediaPrevious", 0.0)

        assertTrue(sendMediaEvent(runtime, "mediaNext").isEmpty())
        assertTrue(sendMediaEvent(runtime, "mediaPrevious").isEmpty())
        assertEquals("show:1:4", runtime.episodeCardState?.episode?.videoId)
        assertTrue(assertNotNull(runtime.episodeCardState).isPrevious)
    }

    private fun sendMediaEvent(runtime: PlayerScreenRuntime, event: String): List<Long> {
        val seeks = mutableListOf<Long>()
        val controller = NativePlayerController(
            host = NativePlayerHost(),
            nativeCreate = { _, _, _, _, _, _, _, _, _ -> error("unused") },
            nativeDispose = {},
            nativeSeekBy = { _, offset -> seeks += offset },
            nativeUpdateControls = { _, _ -> },
        )
        controller.javaClass.getDeclaredField("handle").also {
            it.isAccessible = true
            it.setLong(controller, 42L)
        }
        controller.setControlCallbacks(
            onAction = { false },
            onEvent = runtime::handlePlayerControlsEvent,
            onScrubChange = { false },
            onScrubFinished = { false },
        )
        controller.javaClass.getDeclaredMethod(
            "handlePlayerEvent", String::class.java, Double::class.javaPrimitiveType,
        ).also {
            it.isAccessible = true
            it.invoke(controller, event, 0.0)
        }
        return seeks
    }

    private fun runtime(offline: Boolean) = PlayerScreenRuntime(
        args = PlayerScreenArgs(
            profileId = 1,
            title = "Series",
            sourceUrl = "https://example.com/5.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Provider",
            streamTitle = "Episode 5",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 5,
            episodeTitle = "Episode 5",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "show:1:5",
            parentMetaId = "show",
            parentMetaType = "series",
            providerAddonId = null,
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
        episodePlaybackOffline = { offline },
        findEpisodeDownload = { _, _ -> null },
    ).apply {
        playerMetaVideos = (4..6).map { number ->
            MetaVideo(
                id = "show:1:$number", title = "Episode $number", season = 1, episode = number,
                thumbnail = "https://example.com/$number.jpg",
            )
        }
        nextEpisodeInfo = NextEpisodeInfo(
            videoId = "show:1:6", season = 1, episode = 6, title = "Episode 6",
            thumbnail = "https://example.com/6.jpg", overview = null, released = null,
            hasAired = true, isWatched = false, unairedMessage = null,
        )
    }
}
