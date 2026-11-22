package com.nuvio.app.features.downloads

import androidx.compose.ui.Modifier
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerScreenArgs
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.currentPlaybackProgressPercent
import com.nuvio.app.features.player.flushWatchProgress
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceLocal
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktPlayback
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchedAfterDownloadTest {
    private data class RecordedScrobble(
        val providerId: TrackingProviderId,
        val profileId: Int,
        val action: TrackingScrobbleAction,
        val event: TrackingScrobbleEvent,
    )

    private data class RecordedHistoryWrite(
        val providerId: TrackingProviderId,
        val profileId: Int,
        val items: List<TrackingHistoryItem>,
    )

    private data class RecordedWatchedPush(
        val providerId: TrackingProviderId,
        val profileId: Int,
        val items: List<WatchedItem>,
    )

    private class TrackerCallRecorder {
        val scrobbles = mutableListOf<RecordedScrobble>()
        val historyWrites = mutableListOf<RecordedHistoryWrite>()
        val watchedPushes = mutableListOf<RecordedWatchedPush>()

        fun completionScrobbles(): List<RecordedScrobble> =
            scrobbles.filter { it.action == TrackingScrobbleAction.STOP && it.event.progressPercent >= 80.0 }
    }

    private data class SavedProviderPorts(
        val id: TrackingProviderId,
        val auth: TrackingAuthProvider?,
        val scrobbler: TrackingScrobbler?,
        val historyWriter: TrackingHistoryWriter?,
        val watchedProvider: TrackingWatchedProvider?,
        val progressProvider: TrackingProgressProvider?,
    )

    private fun fakeAuthProvider(
        id: TrackingProviderId,
        authenticated: Boolean,
    ): TrackingAuthProvider = object : TrackingAuthProvider {
        override val descriptor = TrackingProviderDescriptor(
            id = id,
            displayName = id.displayName,
            capabilities = setOf(
                TrackingCapability.AUTHENTICATION,
                TrackingCapability.WATCHED_READ,
                TrackingCapability.WATCHED_WRITE,
                TrackingCapability.PROGRESS_READ,
                TrackingCapability.PROGRESS_WRITE,
                TrackingCapability.SCROBBLE,
            ),
        )
        override val isAuthenticated: StateFlow<Boolean> = MutableStateFlow(authenticated)
        override fun ensureLoaded() = Unit
        override fun onProfileChanged() = Unit
        override fun clearLocalState() = Unit
        override fun removeStoredProfile(profileId: Int) = Unit
    }

    private fun recordingScrobbler(
        id: TrackingProviderId,
        recorder: TrackerCallRecorder,
    ): TrackingScrobbler = object : TrackingScrobbler {
        override val providerId: TrackingProviderId = id
        override suspend fun scrobble(
            profileId: Int,
            action: TrackingScrobbleAction,
            event: TrackingScrobbleEvent,
        ) {
            recorder.scrobbles += RecordedScrobble(id, profileId, action, event)
        }
    }

    private fun recordingHistoryWriter(
        id: TrackingProviderId,
        recorder: TrackerCallRecorder,
    ): TrackingHistoryWriter = object : TrackingHistoryWriter {
        override val providerId: TrackingProviderId = id
        override suspend fun addToHistory(
            profileId: Int,
            items: Collection<TrackingHistoryItem>,
        ): TrackingMutationResult {
            recorder.historyWrites += RecordedHistoryWrite(id, profileId, items.toList())
            return TrackingMutationResult(attemptedCount = items.size)
        }

        override suspend fun removeFromHistory(
            profileId: Int,
            items: Collection<TrackingMediaReference>,
        ): TrackingMutationResult = TrackingMutationResult(attemptedCount = items.size)
    }

    private fun recordingWatchedProvider(
        id: TrackingProviderId,
        recorder: TrackerCallRecorder,
    ): TrackingWatchedProvider = object : TrackingWatchedProvider {
        override val providerId: TrackingProviderId = id
        override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> = emptyList()
        override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
            recorder.watchedPushes += RecordedWatchedPush(id, profileId, items.toList())
        }

        override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) = Unit
    }

    private fun fakeProgressProvider(
        id: TrackingProviderId,
        snapshotEntries: () -> List<WatchProgressEntry> = { emptyList() },
        ownsCompletedHistory: Boolean = true,
    ): TrackingProgressProvider = object : TrackingProgressProvider {
        override val providerId: TrackingProviderId = id
        override val changes: Flow<Unit> = emptyFlow()
        override val ownsCompletedHistoryProjection: Boolean = ownsCompletedHistory
        override fun ensureLoaded() = Unit
        override suspend fun refresh(force: Boolean, sourceChanged: Boolean) = Unit
        override fun snapshot(): TrackingProgressSnapshot = TrackingProgressSnapshot(
            entries = snapshotEntries(),
            hasLoadedRemoteProgress = true,
        )

        override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) = Unit
    }

    private inline fun withIsolatedTrackerHarness(
        source: WatchProgressSource,
        connectedProviders: Set<TrackingProviderId>,
        crossinline providerEntries: (TrackingProviderId) -> List<WatchProgressEntry> = { emptyList() },
        block: (profileId: Int, recorder: TrackerCallRecorder) -> Unit,
    ) {
        ensureTrackingProvidersRegistered()
        val profileId = ProfileRepository.activeProfileId
        val watchedStore = DesktopStorage.store("nuvio_watched")
        val progressStore = DesktopStorage.store("nuvio_watch_progress")
        val previousWatched = WatchedStorage.loadPayload(profileId)
        val previousProgress = WatchProgressStorage.loadPayload(profileId)
        val savedPorts = TrackingProviderId.entries.map { id ->
            SavedProviderPorts(
                id = id,
                auth = TrackingProviderRegistry.authProvider(id),
                scrobbler = TrackingProviderRegistry.scrobbler(id),
                historyWriter = TrackingProviderRegistry.historyWriter(id),
                watchedProvider = TrackingProviderRegistry.watchedProvider(id),
                progressProvider = TrackingProviderRegistry.progressProvider(id),
            )
        }
        val recorder = TrackerCallRecorder()
        try {
            TrackingProviderId.entries.forEach { id ->
                TrackingProviderRegistry.replaceProviderPorts(
                    id = id,
                    auth = fakeAuthProvider(id, authenticated = id in connectedProviders),
                    scrobbler = recordingScrobbler(id, recorder),
                    historyWriter = recordingHistoryWriter(id, recorder),
                    watchedProvider = recordingWatchedProvider(id, recorder),
                    progressProvider = fakeProgressProvider(
                        id = id,
                        snapshotEntries = { providerEntries(id) },
                        ownsCompletedHistory = id == TrackingProviderId.TRAKT || id == TrackingProviderId.MDBLIST,
                    ),
                )
            }
            WatchedRepository.clearLocalState()
            WatchProgressRepository.clearLocalState()
            watchedStore.remove("watched_$profileId")
            progressStore.remove("watch_progress_$profileId")
            WatchedRepository.activateSource(source)
            WatchProgressRepository.activateSource(source)
            block(profileId, recorder)
        } finally {
            WatchedRepository.clearLocalState()
            WatchProgressRepository.clearLocalState()
            savedPorts.forEach { saved ->
                TrackingProviderRegistry.replaceProviderPorts(
                    id = saved.id,
                    auth = saved.auth,
                    scrobbler = saved.scrobbler,
                    historyWriter = saved.historyWriter,
                    watchedProvider = saved.watchedProvider,
                    progressProvider = saved.progressProvider,
                )
            }
            WatchedRepository.activateSource(WatchProgressSource.NUVIO_SYNC)
            WatchProgressRepository.activateSource(WatchProgressSource.NUVIO_SYNC)
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

    private fun downloadedEpisodeSession(
        profileId: Int,
        showId: String,
        episodeNumber: Int = 1,
    ) = WatchProgressPlaybackSession(
        profileId = profileId,
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        videoId = "$showId:1:$episodeNumber",
        title = "Example Show",
        seasonNumber = 1,
        episodeNumber = episodeNumber,
        episodeTitle = "Episode $episodeNumber",
        lastSourceUrl = "file:///downloads/Example.Show.S01E0$episodeNumber.mkv",
    )

    private fun localPlayerRuntime(
        profileId: Int,
        showId: String,
        episodeNumber: Int = 1,
    ) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = profileId,
            title = "Example Show",
            sourceUrl = "file:///downloads/Example.Show.S01E0$episodeNumber.mkv",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Downloaded",
            streamTitle = "Example.Show.S01E0$episodeNumber.mkv",
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
            episodeNumber = episodeNumber,
            episodeTitle = "Episode $episodeNumber",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "$showId:1:$episodeNumber",
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
    fun finishing_a_downloaded_episode_under_trakt_sends_one_completion_and_writes_local_watched_storage() = runTest {
        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
        ) { profileId, recorder ->
            val showId = "tt9911501"
            val runtime = localPlayerRuntime(profileId = profileId, showId = showId, episodeNumber = 1)
            runtime.scope = this
            runtime.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 2_400_000L,
                positionMs = 2_400_000L,
            )

            runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
            // A second flush on exit after end must not duplicate the outgoing completion.
            runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
            advanceUntilIdle()

            assertTrue(
                WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1),
                "Expected episode to be marked watched in WatchedRepository under Trakt",
            )
            val savedWatched = WatchedStorage.loadPayload(profileId).orEmpty()
            assertTrue(
                showId in savedWatched,
                "Expected watched storage to be written with $showId, got: $savedWatched",
            )
            assertEquals(1, recorder.scrobbles.size)
            val call = recorder.scrobbles.single()
            assertEquals(TrackingProviderId.TRAKT, call.providerId)
            assertEquals(TrackingScrobbleAction.STOP, call.action)
            assertEquals(100.0, call.event.progressPercent)
            assertEquals(showId, call.event.media.ids.imdb)
            assertEquals(1, call.event.media.episode?.season)
            assertEquals(1, call.event.media.episode?.number)
            assertEquals(emptyList(), recorder.historyWrites)
            assertEquals(emptyList(), recorder.watchedPushes)
        }
    }

    @Test
    fun finishing_a_downloaded_episode_under_mdblist_sends_one_completion_and_writes_local_watched_storage() = runTest {
        withIsolatedTrackerHarness(
            source = WatchProgressSource.MDBLIST,
            connectedProviders = setOf(TrackingProviderId.MDBLIST),
        ) { profileId, recorder ->
            val showId = "tt9911502"
            val runtime = localPlayerRuntime(profileId = profileId, showId = showId, episodeNumber = 2)
            runtime.scope = this
            runtime.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 2_400_000L,
                positionMs = 2_400_000L,
            )

            runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
            runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
            advanceUntilIdle()

            assertTrue(
                WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 2),
                "Expected episode to be marked watched in WatchedRepository under MDBList",
            )
            val savedWatched = WatchedStorage.loadPayload(profileId).orEmpty()
            assertTrue(
                showId in savedWatched,
                "Expected watched storage to be written with $showId, got: $savedWatched",
            )
            assertEquals(1, recorder.scrobbles.size)
            val call = recorder.scrobbles.single()
            assertEquals(TrackingProviderId.MDBLIST, call.providerId)
            assertEquals(TrackingScrobbleAction.STOP, call.action)
            assertEquals(100.0, call.event.progressPercent)
            assertEquals(showId, call.event.media.ids.imdb)
            assertEquals(1, call.event.media.episode?.season)
            assertEquals(2, call.event.media.episode?.number)
            assertEquals(emptyList(), recorder.historyWrites)
            assertEquals(emptyList(), recorder.watchedPushes)
        }
    }

    @Test
    fun finishing_a_downloaded_episode_with_no_tracker_sends_no_tracker_calls_and_marks_watched_locally() = runTest {
        withIsolatedTrackerHarness(
            source = WatchProgressSource.NUVIO_SYNC,
            connectedProviders = emptySet(),
        ) { profileId, recorder ->
            val showId = "tt9911506"
            val runtime = localPlayerRuntime(profileId = profileId, showId = showId, episodeNumber = 1)
            runtime.scope = this
            runtime.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 2_400_000L,
                positionMs = 2_400_000L,
            )

            runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
            advanceUntilIdle()

            assertTrue(
                WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1),
                "Expected local watched list to record completion without any tracker",
            )
            assertTrue(showId in WatchedStorage.loadPayload(profileId).orEmpty())
            assertEquals(emptyList(), recorder.scrobbles)
            assertEquals(emptyList(), recorder.historyWrites)
            assertEquals(emptyList(), recorder.watchedPushes)
        }
    }

    @Test
    fun partial_playback_and_short_placeholders_send_no_completion_and_do_not_mark_watched() = runTest {
        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT, TrackingProviderId.MDBLIST),
        ) { profileId, recorder ->
            val showId = "tt9911507"

            // Partial playback without an active start scrobble sends nothing.
            val idlePartial = localPlayerRuntime(profileId = profileId, showId = showId, episodeNumber = 1)
            idlePartial.scope = this
            idlePartial.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = false,
                durationMs = 2_400_000L,
                positionMs = 1_200_000L,
            )
            idlePartial.flushWatchProgress(TrackingScrobbleAction.STOP)
            advanceUntilIdle()
            assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
            assertEquals(emptyList(), recorder.scrobbles)
            assertEquals(emptyList(), recorder.historyWrites)
            assertEquals(emptyList(), recorder.watchedPushes)

            // Partial playback with an active scrobble sends only a non-completion stop (< 80%).
            val activePartial = localPlayerRuntime(profileId = profileId, showId = showId, episodeNumber = 2)
            activePartial.scope = this
            activePartial.hasRequestedScrobbleStartForCurrentItem = true
            activePartial.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = false,
                durationMs = 2_400_000L,
                positionMs = 1_200_000L,
            )
            activePartial.flushWatchProgress(TrackingScrobbleAction.STOP)
            advanceUntilIdle()
            assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 2))
            assertEquals(emptyList(), recorder.completionScrobbles())
            assertTrue(recorder.scrobbles.all { it.event.progressPercent < 80.0 })
            assertEquals(emptyList(), recorder.historyWrites)
            assertEquals(emptyList(), recorder.watchedPushes)

            // Ended short placeholder clip sends no tracker call and is never marked watched.
            recorder.scrobbles.clear()
            val placeholder = localPlayerRuntime(profileId = profileId, showId = showId, episodeNumber = 3)
            placeholder.scope = this
            placeholder.hasRequestedScrobbleStartForCurrentItem = true
            placeholder.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 30_000L,
                positionMs = 30_000L,
            )
            placeholder.flushWatchProgress(TrackingScrobbleAction.STOP)
            advanceUntilIdle()
            assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 3))
            assertEquals(emptyList(), recorder.scrobbles)
            assertEquals(emptyList(), recorder.historyWrites)
            assertEquals(emptyList(), recorder.watchedPushes)
        }
    }

    @Test
    fun rewatching_an_already_watched_episode_sends_no_duplicate_and_does_not_rewind_auto_downloads() = runTest {
        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
        ) { profileId, recorder ->
            val showId = "tt9911508"
            // First finish on episode 1.
            val firstWatch = localPlayerRuntime(profileId = profileId, showId = showId, episodeNumber = 1)
            firstWatch.scope = this
            firstWatch.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 2_400_000L,
                positionMs = 2_400_000L,
            )
            firstWatch.flushWatchProgress(TrackingScrobbleAction.STOP)
            // Replaying/re-flushing inside the same finished session sends no second completion.
            firstWatch.flushWatchProgress(TrackingScrobbleAction.STOP)
            advanceUntilIdle()
            assertEquals(1, recorder.completionScrobbles().size)

            // Mark episodes 2 and 3 watched too so the show frontier is at episode 3.
            for (ep in 2..3) {
                WatchProgressRepository.flushPlaybackProgress(
                    session = downloadedEpisodeSession(profileId, showId, episodeNumber = ep),
                    snapshot = PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = false,
                        isEnded = true,
                        durationMs = 2_400_000L,
                        positionMs = 2_400_000L,
                    ),
                )
            }

            // Rewatch episode 1 in a fresh playback session: one completion for that finish, no history
            // write duplicate, no repeated-flush duplicate, no duplicate local watched item.
            recorder.scrobbles.clear()
            val rewatch = localPlayerRuntime(profileId = profileId, showId = showId, episodeNumber = 1)
            rewatch.scope = this
            rewatch.playbackSnapshot = PlayerPlaybackSnapshot(
                isLoading = false,
                isPlaying = false,
                isEnded = true,
                durationMs = 2_400_000L,
                positionMs = 2_400_000L,
            )
            rewatch.flushWatchProgress(TrackingScrobbleAction.STOP)
            rewatch.flushWatchProgress(TrackingScrobbleAction.STOP)
            advanceUntilIdle()

            assertEquals(1, recorder.completionScrobbles().size)
            assertEquals(1, recorder.scrobbles.size)
            assertEquals(emptyList(), recorder.historyWrites)
            assertEquals(emptyList(), recorder.watchedPushes)
            val matchingWatched = WatchedRepository.uiState.value.items.filter {
                it.id == showId && it.season == 1 && it.episode == 1
            }
            assertEquals(1, matchingWatched.size)

            // Rewatching older episode 1 must not pull the auto-download window backwards.
            val rule = testRule(showId = showId, keepCount = 3)
            val released = (1..6).map { testEpisode(it, showId = showId) }
            val plan = planAutoDownload(
                rule = rule,
                released = released,
                isWatched = { isEpisodeWatchedForAutoDownload(rule, it) },
                downloads = emptyList(),
            )
            assertEquals(listOf(4, 5, 6), plan.window.map { it.episodeNumber })
        }
    }

    @Test
    fun stale_tracker_progress_at_90_percent_on_a_later_episode_does_not_advance_the_auto_download_window() {
        val showId = "tt9911509"
        val staleTraktPausedRow = WatchProgressEntry(
            contentType = "series",
            parentMetaId = showId,
            parentMetaType = "series",
            videoId = "$showId:1:8",
            title = "Example Show",
            seasonNumber = 1,
            episodeNumber = 8,
            lastPositionMs = 0L,
            durationMs = 0L,
            lastUpdatedEpochMs = 1_000L,
            isCompleted = true,
            progressPercent = 90f,
            source = WatchProgressSourceTraktPlayback,
        ).normalizedCompletion()

        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
            providerEntries = { id -> if (id == TrackingProviderId.TRAKT) listOf(staleTraktPausedRow) else emptyList() },
        ) { profileId, _ ->
            // Confirm the tracker snapshot really exposes episode 8 as 90% completed through progressForVideo.
            assertTrue(
                WatchProgressRepository.progressForVideo(
                    videoId = "$showId:1:8",
                    parentMetaId = showId,
                    seasonNumber = 1,
                    episodeNumber = 8,
                )?.isEffectivelyCompleted == true,
            )

            val rule = testRule(showId = showId, keepCount = 3)
            val released = (1..12).map { testEpisode(it, showId = showId) }
            val downloads = (1..3).map { testDownload(it, DownloadStatus.Completed, showId = showId, ruleKey = rule.key) }

            assertFalse(
                isEpisodeWatchedForAutoDownload(rule, released[7]),
                "Stale tracker progress at 90% must not count as verified local completion",
            )

            val beforeLocalFinish = planAutoDownload(
                rule = rule,
                released = released,
                isWatched = { isEpisodeWatchedForAutoDownload(rule, it) },
                downloads = downloads,
            )
            assertEquals(listOf(1, 2, 3), beforeLocalFinish.window.map { it.episodeNumber })
            assertEquals(emptyList(), beforeLocalFinish.toDownload.map { it.episodeNumber })

            // Finishing episode 1 locally on this device advances the window by one episode, not past episode 8.
            WatchProgressRepository.flushPlaybackProgress(
                session = downloadedEpisodeSession(profileId, showId, episodeNumber = 1),
                snapshot = PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = false,
                    isEnded = true,
                    durationMs = 2_400_000L,
                    positionMs = 2_400_000L,
                ),
            )

            val afterLocalFinish = planAutoDownload(
                rule = rule,
                released = released,
                isWatched = { isEpisodeWatchedForAutoDownload(rule, it) },
                downloads = downloads,
            )
            assertEquals(listOf(2, 3, 4), afterLocalFinish.window.map { it.episodeNumber })
            assertEquals(listOf(4), afterLocalFinish.toDownload.map { it.episodeNumber })
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
    fun local_completed_progress_counts_as_watched_but_tracker_progress_fallback_does_not() {
        val rule = testRule(keepCount = 3)
        val episode = testEpisode(1)
        val localCompleted = WatchProgressEntry(
            contentType = "series",
            parentMetaId = rule.parentMetaId,
            parentMetaType = "series",
            videoId = episode.videoId,
            title = rule.title,
            seasonNumber = 1,
            episodeNumber = 1,
            lastPositionMs = 2_400_000L,
            durationMs = 2_400_000L,
            lastUpdatedEpochMs = 1_000L,
            isCompleted = true,
            source = WatchProgressSourceLocal,
        )
        val trackerSnapshot = localCompleted.copy(
            progressPercent = 90f,
            source = WatchProgressSourceTraktPlayback,
        )

        assertTrue(
            isEpisodeWatchedForAutoDownload(
                rule = rule,
                episode = episode,
                isWatchedInHistory = { _, _, _, _ -> false },
                localProgressForEpisode = { _, _, _, _ -> localCompleted },
            ),
        )
        assertFalse(
            isEpisodeWatchedForAutoDownload(
                rule = rule,
                episode = episode,
                isWatchedInHistory = { _, _, _, _ -> false },
                localProgressForEpisode = { _, _, _, _ -> trackerSnapshot },
            ),
        )
    }

    @Test
    fun flushing_ended_local_playback_with_unknown_duration_sends_one_completion_scrobble() = runTest {
        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
        ) { profileId, recorder ->
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
            advanceUntilIdle()

            assertTrue(
                runtime.hasSentCompletionScrobbleForCurrentItem,
                "Expected ended local playback to emit a completion stop scrobble",
            )
            assertEquals(1, recorder.scrobbles.size)
            val call = recorder.scrobbles.single()
            assertEquals(TrackingProviderId.TRAKT, call.providerId)
            assertEquals(TrackingScrobbleAction.STOP, call.action)
            assertEquals(100.0, call.event.progressPercent)
            assertEquals(showId, call.event.media.ids.imdb)
            assertEquals(1, call.event.media.episode?.season)
            assertEquals(1, call.event.media.episode?.number)
            assertEquals(emptyList(), recorder.historyWrites)
            assertEquals(emptyList(), recorder.watchedPushes)
            assertTrue(
                WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1),
                "Expected ended local playback to mark the episode watched locally",
            )
        }
    }
}
