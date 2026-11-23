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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadedEpisodeAutoPlayCancelTest {
    @Test
    fun cancelling_next_episode_auto_play_cancels_a_pending_download_check_and_does_not_switch() = runBlocking {
        val started = CountDownLatch(1)
        val gate = CompletableDeferred<Boolean>()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
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
                    if (video.episode == 6) testDownload(episode = 6, status = DownloadStatus.Completed, showId = "show") else null
                },
            ).apply {
                this.scope = scope
                verifyEpisodeDownload = {
                    started.countDown()
                    gate.await()
                }
                playableDownloadedUri = { "file:///tmp/episode-6.mkv" }
                playerMetaVideos = (5..6).map { number ->
                    MetaVideo(
                        id = "show:1:$number", title = "Episode $number", season = 1, episode = number,
                        thumbnail = null,
                    )
                }
                nextEpisodeInfo = NextEpisodeInfo(
                    videoId = "show:1:6", season = 1, episode = 6, title = "Episode 6",
                    thumbnail = null, overview = null, released = "2001-01-01",
                    hasAired = true, isWatched = false, unairedMessage = null,
                )
                initialSeekApplied = true
                playbackSnapshot = PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    isEnded = false,
                    durationMs = 1_000_000L,
                    positionMs = 995_000L,
                )
                playbackSnapshotKey = activePlaybackKey
                playerSettingsUiState = playerSettingsUiState.copy(
                    streamAutoPlayNextEpisodeEnabled = true,
                    preloadNextEpisodeSources = false,
                )
            }
            assertTrue(runtime.playNextEpisode(automatic = true))
            assertTrue(started.await(5, TimeUnit.SECONDS), "the download check did not start")
            runtime.cancelNextEpisodeAutoPlay()
            assertFalse(runtime.downloadedEpisodeSwitch?.job?.isActive == true)
            gate.complete(true)
            Thread.sleep(200)
            assertEquals("https://example.com/5.mp4", runtime.activeSourceUrl)
            assertEquals("show:1:5", runtime.activeVideoId)
        } finally {
            gate.complete(true)
            parent.cancel()
        }
    }
}
