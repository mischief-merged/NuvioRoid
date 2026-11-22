package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.testDownload
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadedEpisodeSwitchTest {
    @Test
    fun downloaded_next_waits_for_subtitles_and_a_second_request_does_not_prepare_again() = runBlocking {
        val fileUri = "file:///tmp/episode-6.mkv"
        val preparations = AtomicInteger(0)
        val started = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val runtime = runtime(scope, targetEpisode = 6, preparations, started, gate, fileUri)
            runtime.playNextEpisode()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals("https://example.com/5.mp4", runtime.activeSourceUrl)
            assertEquals("show:1:5", runtime.activeVideoId)
            assertEquals(1, preparations.get())

            runtime.playNextEpisode()
            val episode6 = runtime.playerMetaVideos.first { it.episode == 6 }
            val second = runtime.switchToDownloadedEpisode(
                testDownload(episode = 6, status = DownloadStatus.Completed, showId = "show"),
                episode6,
            )
            assertEquals(DownloadedEpisodeSwitchResult.Pending, second)
            assertEquals(1, preparations.get())

            gate.complete(Unit)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (runtime.activeSourceUrl != fileUri && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(fileUri, runtime.activeSourceUrl)
            assertEquals("show:1:6", runtime.activeVideoId)
            assertEquals(1, preparations.get())
        } finally {
            gate.complete(Unit)
            parent.cancel()
        }
    }

    @Test
    fun switching_to_another_downloaded_episode_replaces_the_pending_preparation() = runBlocking {
        val preparations = AtomicInteger(0)
        val started = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val runtime = runtime(scope, targetEpisode = 6, preparations, started, gate)
            runtime.playNextEpisode()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val firstJob = runtime.downloadedEpisodeSwitch?.job
            assertTrue(firstJob?.isActive == true)

            runtime.playableDownloadedUri = { "file:///tmp/episode-4.mkv" }
            runtime.prepareDownloadedSubtitles = {
                preparations.incrementAndGet()
                PreparedStartupSubtitles()
            }
            val episode4 = runtime.playerMetaVideos.first { it.episode == 4 }
            val result = runtime.switchToDownloadedEpisode(
                testDownload(episode = 4, status = DownloadStatus.Completed, showId = "show"),
                episode4,
            )
            assertEquals(DownloadedEpisodeSwitchResult.Accepted, result)
            assertFalse(firstJob?.isActive == true)

            val deadline = System.nanoTime() + 5_000_000_000L
            while (runtime.activeSourceUrl != "file:///tmp/episode-4.mkv" && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertEquals("file:///tmp/episode-4.mkv", runtime.activeSourceUrl)
            assertEquals("show:1:4", runtime.activeVideoId)
            assertEquals(2, preparations.get())
        } finally {
            gate.complete(Unit)
            parent.cancel()
        }
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
            runtime.playNextEpisode()
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
}
