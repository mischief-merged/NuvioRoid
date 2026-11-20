package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.testDownload
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadedEpisodeVerificationHoldTest {
    @Test
    fun a_second_press_while_the_file_check_is_open_does_not_check_again_or_seek() = runBlocking {
        val checks = AtomicInteger(0)
        val started = CountDownLatch(1)
        val gate = CompletableDeferred<Boolean>()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val fileUri = "file:///tmp/episode-6.mkv"
        try {
            val runtime = runtime(scope, checks, started, gate, fileUri)
            assertTrue(sendMediaEvent(runtime, "mediaNext").isEmpty())
            assertTrue(started.await(5, TimeUnit.SECONDS), "the file check did not start")
            assertEquals(1, checks.get())
            assertEquals("https://example.com/5.mp4", runtime.activeSourceUrl)
            assertTrue(sendMediaEvent(runtime, "mediaNext").isEmpty(), "the second press sought")
            assertEquals(1, checks.get(), "the second press started another file check")
            gate.complete(true)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (runtime.activeSourceUrl != fileUri && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(fileUri, runtime.activeSourceUrl)
            assertEquals("show:1:6", runtime.activeVideoId)
            assertEquals(1, checks.get())
        } finally {
            gate.complete(true)
            parent.cancel()
        }
    }

    private fun runtime(
        scope: CoroutineScope,
        checks: AtomicInteger,
        started: CountDownLatch,
        gate: CompletableDeferred<Boolean>,
        fileUri: String,
    ) = PlayerScreenRuntime(
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
            if (video.episode == 6) testDownload(episode = 6, status = DownloadStatus.Completed, showId = "show") else null
        },
    ).apply {
        this.scope = scope
        verifyEpisodeDownload = {
            checks.incrementAndGet()
            started.countDown()
            gate.await()
        }
        playableDownloadedUri = { fileUri }
        prepareDownloadedSubtitles = { PreparedStartupSubtitles() }
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
}
