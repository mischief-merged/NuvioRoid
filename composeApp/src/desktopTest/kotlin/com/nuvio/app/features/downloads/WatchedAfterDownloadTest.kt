package com.nuvio.app.features.downloads

import androidx.compose.ui.Modifier
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerScreenArgs
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.currentPlaybackProgressPercent
import com.nuvio.app.features.player.flushWatchProgress
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchedAfterDownloadTest {
    private fun withIsolatedWatchingState(
        source: WatchProgressSource,
        block: (profileId: Int) -> Unit,
    ) {
        val profileId = ProfileRepository.activeProfileId
        val watchedStore = DesktopStorage.store("nuvio_watched")
        val progressStore = DesktopStorage.store("nuvio_watch_progress")
        val previousWatched = WatchedStorage.loadPayload(profileId)
        val previousProgress = WatchProgressStorage.loadPayload(profileId)
        try {
            WatchedRepository.clearLocalState()
            WatchProgressRepository.clearLocalState()
            watchedStore.remove("watched_$profileId")
            progressStore.remove("watch_progress_$profileId")
            WatchedRepository.activateSource(source)
            WatchProgressRepository.activateSource(source)
            block(profileId)
        } finally {
            WatchedRepository.clearLocalState()
            WatchProgressRepository.clearLocalState()
            if (previousWatched == null) {
                watchedStore.remove("watched_$profileId")
            } else {
                WatchedStorage.savePayload(profileId, previousWatched)
            }
            if (previousProgress == null) {
                progressStore.remove("watch_progress_$profileId")
            } else {
                WatchProgressStorage.savePayload(profileId, previousProgress)
            }
        }
    }

    private fun downloadedEpisodeSession(profileId: Int, showId: String) = WatchProgressPlaybackSession(
        profileId = profileId,
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        videoId = "$showId:1:1",
        title = "Example Show",
        seasonNumber = 1,
        episodeNumber = 1,
        episodeTitle = "Pilot",
        lastSourceUrl = "file:///downloads/Example.Show.S01E01.mkv",
    )

    private fun localPlayerRuntime(profileId: Int, showId: String) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = profileId,
            title = "Example Show",
            sourceUrl = "file:///downloads/Example.Show.S01E01.mkv",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Downloaded",
            streamTitle = "Example.Show.S01E01.mkv",
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
            episodeNumber = 1,
            episodeTitle = "Pilot",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "$showId:1:1",
            parentMetaId = showId,
            parentMetaType = "series",
            providerAddonId = null,
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    )

    @Test
    fun finishing_a_downloaded_episode_under_trakt_marks_it_watched_locally_and_writes_watched_storage() {
        withIsolatedWatchingState(WatchProgressSource.TRAKT) { profileId ->
            val showId = "tt9911501"
            WatchProgressRepository.flushPlaybackProgress(
                session = downloadedEpisodeSession(profileId, showId),
                snapshot = PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    isEnded = true,
                    durationMs = 2_400_000L,
                    positionMs = 2_400_000L,
                ),
            )

            assertTrue(
                WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1),
                "Expected episode to be marked watched in WatchedRepository under Trakt",
            )
            val savedWatched = WatchedStorage.loadPayload(profileId).orEmpty()
            assertTrue(
                showId in savedWatched,
                "Expected watched storage to be written with $showId, got: $savedWatched",
            )
        }
    }

    @Test
    fun finishing_a_downloaded_episode_under_mdblist_marks_it_watched_locally_and_writes_watched_storage() {
        withIsolatedWatchingState(WatchProgressSource.MDBLIST) { profileId ->
            val showId = "tt9911502"
            WatchProgressRepository.flushPlaybackProgress(
                session = downloadedEpisodeSession(profileId, showId),
                snapshot = PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    isEnded = true,
                    durationMs = 2_400_000L,
                    positionMs = 2_400_000L,
                ),
            )

            assertTrue(
                WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1),
                "Expected episode to be marked watched in WatchedRepository under MDBList",
            )
            val savedWatched = WatchedStorage.loadPayload(profileId).orEmpty()
            assertTrue(
                showId in savedWatched,
                "Expected watched storage to be written with $showId, got: $savedWatched",
            )
        }
    }

    @Test
    fun ended_playback_without_known_duration_still_reports_full_progress_for_tracker_scrobble() {
        val runtime = localPlayerRuntime(profileId = 1, showId = "tt9911503")

        val percent = runtime.currentPlaybackProgressPercent(
            PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 0L,
                positionMs = 0L,
            ),
        )

        assertEquals(100f, percent)
    }

    @Test
    fun short_placeholder_clip_that_ends_does_not_report_full_progress_for_tracker_scrobble() {
        val runtime = localPlayerRuntime(profileId = 1, showId = "tt9911505")

        val percent = runtime.currentPlaybackProgressPercent(
            PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 30_000L,
                positionMs = 0L,
            ),
        )

        assertEquals(0f, percent)
    }

    @Test
    fun an_episode_finished_in_local_progress_counts_as_watched_for_auto_download_rules() {
        val rule = testRule(keepCount = 3)
        val episode = testEpisode(1)

        assertTrue(
            isEpisodeWatchedForAutoDownload(
                rule = rule,
                episode = episode,
                isWatchedInHistory = { _, _, _, _ -> false },
                isCompletedInProgress = { _, _, _, _ -> true },
            ),
        )
    }

    @Test
    fun flushing_ended_local_playback_marks_episode_watched_and_triggers_completion_scrobble() = runTest {
        withIsolatedWatchingState(WatchProgressSource.TRAKT) { profileId ->
            val showId = "tt9911504"
            val runtime = localPlayerRuntime(profileId = profileId, showId = showId)
            runtime.scope = this
            runtime.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 0L,
                positionMs = 0L,
            )

            runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

            assertTrue(
                runtime.hasSentCompletionScrobbleForCurrentItem,
                "Expected ended local playback to emit a completion stop scrobble",
            )
            assertTrue(
                WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1),
                "Expected ended local playback to mark the episode watched locally",
            )
        }
    }
}
