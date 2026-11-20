package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.testDownload
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadedEpisodeOfflineSeekTest {
    @Test
    fun a_damaged_offline_download_seeks_once_and_does_not_switch() {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val seeks = CopyOnWriteArrayList<Long>()
        try {
            val runtime = PlayerScreenRuntime(
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
                episodePlaybackOffline = { true },
                findEpisodeDownload = { _, video ->
                    if (video.episode == 6) {
                        testDownload(episode = 6, status = DownloadStatus.Completed, showId = "show")
                    } else {
                        null
                    }
                },
            ).apply {
                this.scope = scope
                verifyEpisodeDownload = { false }
                playableDownloadedUri = { "file:///tmp/episode-6.mkv" }
                playerMetaVideos = listOf(
                    MetaVideo(id = "show:1:5", title = "Episode 5", season = 1, episode = 5, thumbnail = null),
                    MetaVideo(id = "show:1:6", title = "Episode 6", season = 1, episode = 6, thumbnail = null),
                )
                nextEpisodeInfo = NextEpisodeInfo(
                    videoId = "show:1:6", season = 1, episode = 6, title = "Episode 6",
                    thumbnail = null, overview = null, released = null,
                    hasAired = true, isWatched = false, unairedMessage = null,
                )
            }
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
            runtime.playerController = controller
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
                it.invoke(controller, "mediaNext", 0.0)
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (seeks.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(listOf(10_000L), seeks.toList())
            Thread.sleep(200)
            assertEquals(listOf(10_000L), seeks.toList())
            assertEquals("https://example.com/5.mp4", runtime.activeSourceUrl)
        } finally {
            parent.cancel()
        }
    }
}
