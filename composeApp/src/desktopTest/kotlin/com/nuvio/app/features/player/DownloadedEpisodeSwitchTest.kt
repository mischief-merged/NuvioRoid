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

class DownloadedEpisodeSwitchTest {
    @Test
    fun downloaded_next_waits_for_subtitles_and_a_second_press_does_not_seek_or_prepare_again() = runBlocking {
        slowSwitch(next = true)
    }

    @Test
    fun downloaded_previous_waits_for_subtitles_and_a_second_press_does_not_seek_or_prepare_again() = runBlocking {
        slowSwitch(next = false)
    }

    @Test
    fun a_downloaded_switch_that_is_no_longer_valid_is_not_applied() = runBlocking {
        val preparations = AtomicInteger(0)
        val started = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val runtime = runtime(scope, targetEpisode = 6, preparations, started, gate)
            assertTrue(sendMediaEvent(runtime, "mediaNext").isEmpty())
            assertTrue(started.await(5, TimeUnit.SECONDS))
            runtime.activeVideoId = "elsewhere"
            gate.complete(Unit)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (runtime.downloadedEpisodeSwitch?.job?.isActive == true && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertEquals("https://example.com/5.mp4", runtime.activeSourceUrl)
            assertEquals("elsewhere", runtime.activeVideoId)
            assertEquals(1, preparations.get())
        } finally {
            gate.complete(Unit)
            parent.cancel()
        }
    }

    private suspend fun slowSwitch(next: Boolean) {
        val targetEpisode = if (next) 6 else 4
        val event = if (next) "mediaNext" else "mediaPrevious"
        val fileUri = "file:///tmp/episode-$targetEpisode.mkv"
        val preparations = AtomicInteger(0)
        val started = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val runtime = runtime(scope, targetEpisode, preparations, started, gate, fileUri)
            assertTrue(sendMediaEvent(runtime, event).isEmpty())
            assertTrue(started.await(5, TimeUnit.SECONDS), "subtitle preparation did not start")
            assertEquals(1, preparations.get())
            assertEquals("https://example.com/5.mp4", runtime.activeSourceUrl)
            assertEquals("show:1:5", runtime.activeVideoId)
            assertTrue(sendMediaEvent(runtime, event).isEmpty())
            assertEquals(1, preparations.get())
            gate.complete(Unit)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (runtime.activeSourceUrl != fileUri && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(fileUri, runtime.activeSourceUrl)
            assertEquals("show:1:$targetEpisode", runtime.activeVideoId)
            assertEquals(1, preparations.get())
        } finally {
            gate.complete(Unit)
            parent.cancel()
        }
    }

    private fun runtime(
        scope: CoroutineScope,
        targetEpisode: Int,
        preparations: AtomicInteger,
        started: CountDownLatch,
        gate: CompletableDeferred<Unit>,
        fileUri: String = "file:///tmp/episode-$targetEpisode.mkv",
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
            if (video.episode == targetEpisode) {
                testDownload(episode = targetEpisode, status = DownloadStatus.Completed, showId = "show")
            } else {
                null
            }
        },
    ).apply {
        this.scope = scope
        verifyEpisodeDownload = { true }
        playableDownloadedUri = { fileUri }
        prepareDownloadedSubtitles = {
            preparations.incrementAndGet()
            started.countDown()
            gate.await()
            PreparedStartupSubtitles()
        }
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
