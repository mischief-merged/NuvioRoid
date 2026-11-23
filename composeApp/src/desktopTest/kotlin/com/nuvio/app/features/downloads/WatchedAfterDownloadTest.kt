package com.nuvio.app.features.downloads

import androidx.compose.ui.Modifier
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.tracking.SerializedOneTimeBootstrap
import com.nuvio.app.core.tracking.ensureTrackingProvidersRegistered
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
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
import com.nuvio.app.features.tracking.TrackingProfileStore
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
import com.nuvio.app.features.trakt.resolveEffectiveContentId
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watchprogress.WatchProgressCodec
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceLocal
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktPlayback
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        pulledItems: () -> List<WatchedItem> = { emptyList() },
    ): TrackingWatchedProvider = object : TrackingWatchedProvider {
        override val providerId: TrackingProviderId = id
        override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> = pulledItems()
        override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
            recorder.watchedPushes += RecordedWatchedPush(id, profileId, items.toList())
        }

        override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) = Unit
    }

    private fun fakeProgressProvider(
        id: TrackingProviderId,
        snapshotEntries: () -> List<WatchProgressEntry> = { emptyList() },
        ownsCompletedHistory: Boolean = true,
        normalizeParentId: (parentContentId: String, videoId: String?) -> String = { parentContentId, _ -> parentContentId },
        onOptimisticProgress: (WatchProgressEntry) -> Unit = {},
        onOptimisticRemoval: (Collection<WatchProgressEntry>) -> Unit = {},
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

        override fun applyOptimisticProgress(entry: WatchProgressEntry) = onOptimisticProgress(entry)

        override fun applyOptimisticRemoval(entries: Collection<WatchProgressEntry>) = onOptimisticRemoval(entries)

        override fun normalizeParentContentId(parentContentId: String, videoId: String?): String =
            normalizeParentId(parentContentId, videoId)
    }

    private inline fun withIsolatedTrackerHarness(
        source: WatchProgressSource,
        connectedProviders: Set<TrackingProviderId>,
        crossinline providerEntries: (TrackingProviderId) -> List<WatchProgressEntry> = { emptyList() },
        crossinline watchedPulledItems: (TrackingProviderId) -> List<WatchedItem> = { emptyList() },
        crossinline normalizeParentId: (TrackingProviderId, String, String?) -> String = { _, parentContentId, _ -> parentContentId },
        crossinline onOptimisticProgress: (TrackingProviderId, WatchProgressEntry) -> Unit = { _, _ -> },
        crossinline onOptimisticRemoval: (TrackingProviderId, Collection<WatchProgressEntry>) -> Unit = { _, _ -> },
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
                replaceProviderPortsForTest(
                    id = id,
                    auth = fakeAuthProvider(id, authenticated = id in connectedProviders),
                    scrobbler = recordingScrobbler(id, recorder),
                    historyWriter = recordingHistoryWriter(id, recorder),
                    watchedProvider = recordingWatchedProvider(
                        id = id,
                        recorder = recorder,
                        pulledItems = { watchedPulledItems(id) },
                    ),
                    progressProvider = fakeProgressProvider(
                        id = id,
                        snapshotEntries = { providerEntries(id) },
                        ownsCompletedHistory = id == TrackingProviderId.TRAKT || id == TrackingProviderId.MDBLIST,
                        normalizeParentId = { parentContentId, videoId -> normalizeParentId(id, parentContentId, videoId) },
                        onOptimisticProgress = { entry -> onOptimisticProgress(id, entry) },
                        onOptimisticRemoval = { entries -> onOptimisticRemoval(id, entries) },
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
                replaceProviderPortsForTest(
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

    private inline fun withIsolatedDownloadsAndAutoDownloadStore(
        profileId: Int,
        items: List<DownloadItem>,
        rule: AutoDownloadRule,
        block: (store: AutoDownloadStore) -> Unit,
    ) {
        val realBackend = DownloadsRepository.backend
        val autoDownloadsStore = DesktopStorage.store("nuvio_auto_downloads")
        val previousAutoPayload = AutoDownloadStorage.loadPayload(profileId)
        val inMemoryBackend = object : DownloadsBackend {
            var payload: String? = DownloadsCodec.encode(items, loaded = null)
            override fun loadPayload(): String? = payload
            override fun savePayload(payload: String) {
                this.payload = payload
            }

            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle = object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }

            override fun removeFile(localFileUri: String?): Boolean = true
            override fun removePartialFile(destinationFileName: String): Boolean = true
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
            override fun trashFile(localFileUri: String): Boolean = true
        }
        val liveStore = autoDownloadsStoreForTest()
        try {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = inMemoryBackend
            DownloadsRepository.ensureLoaded()
            liveStore.upsert(profileId, rule)
            block(liveStore)
        } finally {
            liveStore.remove(profileId, rule.key)
            if (previousAutoPayload == null) {
                autoDownloadsStore.remove("auto_downloads_$profileId")
            } else {
                AutoDownloadStorage.savePayload(profileId, previousAutoPayload)
            }
            resetLoadedProfileForTest(liveStore)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = realBackend
        }
    }

    private fun downloadedEpisodeSession(
        profileId: Int,
        showId: String,
        episodeNumber: Int = 1,
        videoId: String = "$showId:1:$episodeNumber",
    ) = WatchProgressPlaybackSession(
        profileId = profileId,
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        videoId = videoId,
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

        assertTrue(
            isEpisodeWatchedForAutoDownload(
                rule = rule,
                episode = episode,
                isWatchedInHistory = { _, _, _, _ -> false },
                isLocallyFinishedOnDevice = { _, _, _ -> true },
            ),
        )
        assertFalse(
            isEpisodeWatchedForAutoDownload(
                rule = rule,
                episode = episode,
                isWatchedInHistory = { _, _, _, _ -> false },
                isLocallyFinishedOnDevice = { _, _, _ -> false },
            ),
        )
    }

    @Test
    fun concurrent_tracker_bootstrap_callers_wait_until_all_providers_are_registered() {
        val bootstrap = SerializedOneTimeBootstrap()
        val initCalls = AtomicInteger(0)
        val registeredProviders = Collections.synchronizedList(mutableListOf<String>())
        val firstEntered = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val waitingCallersStarted = CountDownLatch(5)
        val finishedCount = AtomicInteger(0)
        val observedByCaller = Collections.synchronizedList(mutableListOf<List<String>>())

        val firstThread = thread(name = "bootstrap-first") {
            bootstrap.run {
                initCalls.incrementAndGet()
                registeredProviders += "mdblist"
                firstEntered.countDown()
                assertTrue(allowFinish.await(5, TimeUnit.SECONDS))
                registeredProviders += "trakt"
                registeredProviders += "simkl"
            }
            finishedCount.incrementAndGet()
            observedByCaller += registeredProviders.toList()
        }

        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        val concurrentThreads = (1..5).map { idx ->
            thread(name = "bootstrap-waiter-$idx") {
                waitingCallersStarted.countDown()
                bootstrap.run {
                    initCalls.incrementAndGet()
                }
                finishedCount.incrementAndGet()
                observedByCaller += registeredProviders.toList()
            }
        }

        assertTrue(waitingCallersStarted.await(5, TimeUnit.SECONDS))
        // Give waiting callers a brief moment to hit the guard; none may return before registration finishes.
        Thread.sleep(75L)
        assertEquals(0, finishedCount.get(), "Concurrent callers must wait until registration completes")

        allowFinish.countDown()
        firstThread.join(5_000L)
        concurrentThreads.forEach { it.join(5_000L) }

        assertEquals(1, initCalls.get())
        assertEquals(6, observedByCaller.size)
        observedByCaller.forEach { seen ->
            assertEquals(listOf("mdblist", "trakt", "simkl"), seen)
        }

        // A failed initialization must not publish readiness; a later caller retries and succeeds.
        val retryBootstrap = SerializedOneTimeBootstrap()
        val attempts = AtomicInteger(0)
        assertFailsWith<IllegalStateException> {
            retryBootstrap.run {
                attempts.incrementAndGet()
                throw IllegalStateException("transient failure")
            }
        }
        retryBootstrap.run {
            attempts.incrementAndGet()
            // Re-entrant call on the same thread must not recurse infinitely.
            retryBootstrap.run { attempts.incrementAndGet() }
        }
        assertEquals(2, attempts.get())
    }

    @Test
    fun replacing_and_restoring_tracking_auth_provider_does_not_leak_profile_stores() {
        ensureTrackingProvidersRegistered()
        val original = TrackingProviderRegistry.authProvider(TrackingProviderId.TRAKT)!!
        val fake = fakeAuthProvider(TrackingProviderId.TRAKT, authenticated = true)
        try {
            TrackingProviderRegistry.register(fake)
            val during = profileStoresSnapshotForTest()
            assertTrue(fake in during)
            assertFalse(original in during)
        } finally {
            TrackingProviderRegistry.register(original)
        }
        val after = profileStoresSnapshotForTest()
        assertTrue(original in after)
        assertFalse(fake in after, "Replaced fake auth provider must not remain in profileStores")
    }

    @Test
    fun old_cloud_synced_90_percent_row_on_a_later_episode_does_not_advance_the_auto_download_window() {
        val showId = "tt9911510"
        val oldCloudRow = WatchProgressEntry(
            contentType = "series",
            parentMetaId = showId,
            parentMetaType = "series",
            videoId = "$showId:1:8",
            title = "Example Show",
            seasonNumber = 1,
            episodeNumber = 8,
            lastPositionMs = 2_160_000L,
            durationMs = 2_400_000L,
            lastUpdatedEpochMs = 1_000L,
            isCompleted = true,
            source = WatchProgressSourceLocal,
        ).normalizedCompletion()

        withIsolatedTrackerHarness(
            source = WatchProgressSource.NUVIO_SYNC,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
        ) { profileId, _ ->
            // Simulate a cloud-synced 90% row persisted in local watch progress storage (whose
            // source defaults to "local" when decoded from ProgressSyncRecord).
            WatchProgressStorage.savePayload(
                profileId,
                WatchProgressCodec.encodeEntries(listOf(oldCloudRow)),
            )
            WatchProgressRepository.clearLocalState()
            WatchProgressRepository.activateSource(WatchProgressSource.NUVIO_SYNC)
            assertTrue(
                WatchProgressRepository.progressForVideo(
                    videoId = "$showId:1:8",
                    parentMetaId = showId,
                    seasonNumber = 1,
                    episodeNumber = 8,
                )?.isEffectivelyCompleted == true,
            )

            // Switch to Trakt so the old cloud-synced row remains in local storage behind Trakt.
            WatchedRepository.activateSource(WatchProgressSource.TRAKT)
            WatchProgressRepository.activateSource(WatchProgressSource.TRAKT)

            val rule = testRule(showId = showId, keepCount = 3)
            val released = (1..12).map { testEpisode(it, showId = showId) }
            val downloads = (1..3).map { testDownload(it, DownloadStatus.Completed, showId = showId, ruleKey = rule.key) }

            assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 8))
            assertFalse(
                isEpisodeWatchedForAutoDownload(rule, released[7]),
                "Old cloud-synced 90% row must not count as a device-local completion",
            )

            val beforeLocalFinish = planAutoDownload(
                rule = rule,
                released = released,
                isWatched = { isEpisodeWatchedForAutoDownload(rule, it) },
                downloads = downloads,
            )
            assertEquals(listOf(1, 2, 3), beforeLocalFinish.window.map { it.episodeNumber })
            assertEquals(emptyList(), beforeLocalFinish.toDownload.map { it.episodeNumber })

            // Finishing episode 1 on this device advances the window by one episode, not past episode 8.
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

            assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))
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
    fun in_app_unwatch_cancels_pending_removal_and_local_finished_mark_so_planning_treats_episode_as_unwatched() {
        val showId = "tt9911511"
        val rule = testRule(showId = showId, keepCount = 3).copy(deleteAfterWatching = true)
        val released = (1..6).map { testEpisode(it, showId = showId) }
        val downloads = (1..3).map { testDownload(it, DownloadStatus.Completed, showId = showId, ruleKey = rule.key) }
        val ep1Video = MetaVideo(id = "$showId:1:1", title = "Episode 1", released = "2024-01-01", season = 1, episode = 1)
        val ep2Video = MetaVideo(id = "$showId:1:2", title = "Episode 2", released = "2024-01-08", season = 1, episode = 2)
        val meta = MetaDetails(id = showId, type = "series", name = "Example Show", videos = listOf(ep1Video, ep2Video))

        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
        ) { profileId, _ ->
            withIsolatedDownloadsAndAutoDownloadStore(profileId, downloads, rule) { liveStore ->
                // Finish episodes 1 and 2 locally and schedule removals for both.
                for (ep in 1..2) {
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
                    assertTrue(
                        liveStore.scheduleRemoval(
                            profileId,
                            AutoDownloadRemoval(downloads[ep - 1].id, rule.key, dueAtEpochMs = 10_000_000L),
                        ),
                    )
                }
                WatchedRepository.reconcileSeriesWatchedState(meta, todayIsoDate = "2026-01-01")
                assertTrue(WatchedRepository.isWatched(id = showId, type = "series"))

                // Unwatching episode 2 clears episode 2's mark and removal, without touching episode 1
                // even though reconcileSeriesWatchedState unmarks the series-level watched marker.
                WatchingActions.toggleEpisodeWatched(meta = meta, episode = ep2Video, isCurrentlyWatched = true)
                assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 2))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 2))
                assertEquals(listOf(downloads[0].id), AutoDownloads.removals.value.map { it.downloadId })
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))

                val afterUnwatchEp2 = planAutoDownload(
                    rule = rule,
                    released = released,
                    isWatched = { isEpisodeWatchedForAutoDownload(rule, it) },
                    downloads = downloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(2, 3, 4), afterUnwatchEp2.window.map { it.episodeNumber })

                // Unwatching episode 1 cancels its pending removal and local finished mark; planning
                // returns the window to [1, 2, 3].
                WatchingActions.toggleEpisodeWatched(meta = meta, episode = ep1Video, isCurrentlyWatched = true)
                assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))
                assertEquals(emptyList(), AutoDownloads.removals.value)

                val afterUnwatchEp1 = planAutoDownload(
                    rule = rule,
                    released = released,
                    isWatched = { isEpisodeWatchedForAutoDownload(rule, it) },
                    downloads = downloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(1, 2, 3), afterUnwatchEp1.window.map { it.episodeNumber })
                assertEquals(emptyList(), afterUnwatchEp1.toDownload.map { it.episodeNumber })
            }
        }
    }

    @Test
    fun clearing_progress_cancels_pending_removal_and_local_finished_mark() {
        val showId = "tt9911512"
        val rule = testRule(showId = showId, keepCount = 3).copy(deleteAfterWatching = true)
        val released = (1..6).map { testEpisode(it, showId = showId) }
        val downloads = (1..3).map { testDownload(it, DownloadStatus.Completed, showId = showId, ruleKey = rule.key) }

        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
        ) { profileId, _ ->
            withIsolatedDownloadsAndAutoDownloadStore(profileId, downloads, rule) { liveStore ->
                // 1) clearProgress(videoId, parentMetaId)
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
                WatchedRepository.clearLocalState()
                DesktopStorage.store("nuvio_watched").remove("watched_$profileId")
                WatchedRepository.activateSource(WatchProgressSource.TRAKT)
                assertTrue(
                    liveStore.scheduleRemoval(
                        profileId,
                        AutoDownloadRemoval(downloads[0].id, rule.key, dueAtEpochMs = 10_000_000L),
                    ),
                )
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))

                WatchProgressRepository.clearProgress(videoId = "$showId:1:1", parentMetaId = showId)

                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))
                assertEquals(emptyList(), AutoDownloads.removals.value)
                val afterClear = planAutoDownload(
                    rule = rule,
                    released = released,
                    isWatched = { isEpisodeWatchedForAutoDownload(rule, it) },
                    downloads = downloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(1, 2, 3), afterClear.window.map { it.episodeNumber })

                // 2) removeProgress(contentId, seasonNumber, episodeNumber)
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
                WatchedRepository.clearLocalState()
                DesktopStorage.store("nuvio_watched").remove("watched_$profileId")
                WatchedRepository.activateSource(WatchProgressSource.TRAKT)
                assertTrue(
                    liveStore.scheduleRemoval(
                        profileId,
                        AutoDownloadRemoval(downloads[0].id, rule.key, dueAtEpochMs = 10_000_000L),
                    ),
                )

                WatchProgressRepository.removeProgress(contentId = showId, seasonNumber = 1, episodeNumber = 1)

                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))
                assertEquals(emptyList(), AutoDownloads.removals.value)
                val afterRemove = planAutoDownload(
                    rule = rule,
                    released = released,
                    isWatched = { isEpisodeWatchedForAutoDownload(rule, it) },
                    downloads = downloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(1, 2, 3), afterRemove.window.map { it.episodeNumber })
            }
        }
    }

    @Test
    fun out_of_band_unwatch_via_watched_repository_or_remote_sync_clears_local_finished_mark_and_pending_removal() = runTest {
        val showId = "tt9911513"
        val rule = testRule(showId = showId, keepCount = 3).copy(deleteAfterWatching = true)
        val released = (1..6).map { testEpisode(it, showId = showId) }
        val downloads = (1..3).map { testDownload(it, DownloadStatus.Completed, showId = showId, ruleKey = rule.key) }
        var remoteWatched: List<WatchedItem> = emptyList()

        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
            watchedPulledItems = { id -> if (id == TrackingProviderId.TRAKT) remoteWatched else emptyList() },
        ) { profileId, _ ->
            withIsolatedDownloadsAndAutoDownloadStore(profileId, downloads, rule) { liveStore ->
                // Part A: Direct WatchedRepository.unmarkWatched (bypassing WatchingActions).
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
                assertTrue(
                    liveStore.scheduleRemoval(
                        profileId,
                        AutoDownloadRemoval(downloads[0].id, rule.key, dueAtEpochMs = 10_000_000L),
                    ),
                )

                WatchedRepository.unmarkWatched(id = showId, type = "series", season = 1, episode = 1)

                assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))
                assertEquals(emptyList(), AutoDownloads.removals.value)

                // Part B: Remote tracker sync unmarks a previously confirmed episode out-of-band.
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
                assertTrue(
                    liveStore.scheduleRemoval(
                        profileId,
                        AutoDownloadRemoval(downloads[0].id, rule.key, dueAtEpochMs = 10_000_000L),
                    ),
                )
                // First pull confirms the watched episode on the tracker (clears dirty bit).
                remoteWatched = listOf(
                    WatchedItem(
                        id = showId,
                        type = "series",
                        name = "Example Show",
                        season = 1,
                        episode = 1,
                        markedAtEpochMs = 5_000L,
                    ),
                )
                WatchedRepository.pullFromServer(profileId)
                assertTrue(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))
                assertEquals(listOf(downloads[0].id), AutoDownloads.removals.value.map { it.downloadId })

                // Second pull reflects an out-of-band unwatch on the tracker.
                remoteWatched = emptyList()
                WatchedRepository.pullFromServer(profileId)

                assertFalse(WatchedRepository.isWatched(id = showId, type = "series", season = 1, episode = 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(showId, 1, 1))
                assertEquals(emptyList(), AutoDownloads.removals.value)
                val plan = planAutoDownload(
                    rule = rule,
                    released = released,
                    isWatched = { isEpisodeWatchedForAutoDownload(rule, it) },
                    downloads = downloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(1, 2, 3), plan.window.map { it.episodeNumber })
            }
        }
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

    @Test
    fun unwatching_through_canonical_id_clears_addon_id_alias_mark_pending_removal_and_moves_window_back() = runTest {
        val addonShowId = "tun_tt7821582"
        val canonicalShowId = "tt7821582"
        val addonRule = testRule(showId = addonShowId, keepCount = 3).copy(deleteAfterWatching = true)
        val released = (1..6).map { testEpisode(it, showId = addonShowId) }
        val downloads = (1..3).map {
            testDownload(it, DownloadStatus.Completed, showId = addonShowId, ruleKey = addonRule.key)
        }
        val ep1CanonicalVideo = MetaVideo(
            id = "$canonicalShowId:1:1",
            title = "Episode 1",
            released = "2024-01-01",
            season = 1,
            episode = 1,
        )
        val ep2CanonicalVideo = MetaVideo(
            id = "$canonicalShowId:1:2",
            title = "Episode 2",
            released = "2024-01-08",
            season = 1,
            episode = 2,
        )
        val canonicalMeta = MetaDetails(
            id = canonicalShowId,
            type = "series",
            name = "Example Show",
            videos = listOf(ep1CanonicalVideo, ep2CanonicalVideo),
        )
        var remoteWatched: List<WatchedItem> = emptyList()

        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
            watchedPulledItems = { id -> if (id == TrackingProviderId.TRAKT) remoteWatched else emptyList() },
            normalizeParentId = { _, parentId, videoId -> resolveEffectiveContentId(parentId, videoId) },
        ) { profileId, _ ->
            withIsolatedDownloadsAndAutoDownloadStore(profileId, downloads, addonRule) { liveStore ->
                // Finish episodes 1 and 2 locally under the addon id while Trakt normalizes to the canonical id.
                for (ep in 1..2) {
                    WatchProgressRepository.flushPlaybackProgress(
                        session = downloadedEpisodeSession(
                            profileId = profileId,
                            showId = addonShowId,
                            episodeNumber = ep,
                            videoId = "$canonicalShowId:1:$ep",
                        ),
                        snapshot = PlayerPlaybackSnapshot(
                            isLoading = false,
                            isPlaying = false,
                            isEnded = true,
                            durationMs = 2_400_000L,
                            positionMs = 2_400_000L,
                        ),
                    )
                    assertTrue(
                        liveStore.scheduleRemoval(
                            profileId,
                            AutoDownloadRemoval(downloads[ep - 1].id, addonRule.key, dueAtEpochMs = 10_000_000L),
                        ),
                    )
                }

                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 2))
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 2))
                val afterFinish = planAutoDownload(
                    rule = addonRule,
                    released = released,
                    isWatched = { isEpisodeWatchedForAutoDownload(addonRule, it) },
                    downloads = downloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(3, 4, 5), afterFinish.window.map { it.episodeNumber })

                // Part A: Out-of-band tracker unwatch of episode 2 under the canonical id (with no videoId).
                remoteWatched = (1..2).map { ep ->
                    WatchedItem(
                        id = canonicalShowId,
                        type = "series",
                        name = "Example Show",
                        season = 1,
                        episode = ep,
                        markedAtEpochMs = 5_000L,
                    )
                }
                WatchedRepository.pullFromServer(profileId)
                assertEquals(listOf(downloads[0].id, downloads[1].id), AutoDownloads.removals.value.map { it.downloadId })

                remoteWatched = remoteWatched.filter { it.episode == 1 }
                WatchedRepository.pullFromServer(profileId)

                assertFalse(WatchedRepository.isWatched(id = canonicalShowId, type = "series", season = 1, episode = 2))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 2))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 2))
                assertEquals(listOf(downloads[0].id), AutoDownloads.removals.value.map { it.downloadId })
                // Episode 1 must remain intact.
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))

                val afterRemoteUnwatchEp2 = planAutoDownload(
                    rule = addonRule,
                    released = released,
                    isWatched = { isEpisodeWatchedForAutoDownload(addonRule, it) },
                    downloads = downloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(2, 3, 4), afterRemoteUnwatchEp2.window.map { it.episodeNumber })

                // Part B: In-app unwatch of episode 1 through the canonical id clears both marks and cancels removal.
                WatchingActions.toggleEpisodeWatched(
                    meta = canonicalMeta,
                    episode = ep1CanonicalVideo,
                    isCurrentlyWatched = true,
                )

                assertFalse(WatchedRepository.isWatched(id = canonicalShowId, type = "series", season = 1, episode = 1))
                assertFalse(WatchedRepository.isWatched(id = addonShowId, type = "series", season = 1, episode = 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                assertEquals(emptyList(), AutoDownloads.removals.value)

                val afterInAppCanonicalUnwatch = planAutoDownload(
                    rule = addonRule,
                    released = released,
                    isWatched = { isEpisodeWatchedForAutoDownload(addonRule, it) },
                    downloads = downloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(1, 2, 3), afterInAppCanonicalUnwatch.window.map { it.episodeNumber })
                assertEquals(emptyList(), afterInAppCanonicalUnwatch.toDownload.map { it.episodeNumber })
            }
        }
    }

    @Test
    fun unwatching_through_addon_id_clears_canonical_alias_mark_pending_removal_and_moves_window_back() {
        val addonShowId = "tun_tt7821583"
        val canonicalShowId = "tt7821583"
        val addonRule = testRule(showId = addonShowId, keepCount = 3).copy(deleteAfterWatching = true)
        val canonicalRule = testRule(showId = canonicalShowId, keepCount = 3).copy(deleteAfterWatching = true)
        val addonReleased = (1..6).map { testEpisode(it, showId = addonShowId) }
        val canonicalReleased = (1..6).map { testEpisode(it, showId = canonicalShowId) }
        val canonicalDownloads = (1..3).map {
            testDownload(it, DownloadStatus.Completed, showId = canonicalShowId, ruleKey = canonicalRule.key)
        }
        val ep1AddonVideo = MetaVideo(
            id = "$addonShowId:1:1",
            title = "Episode 1",
            released = "2024-01-01",
            season = 1,
            episode = 1,
        )
        val addonMeta = MetaDetails(
            id = addonShowId,
            type = "series",
            name = "Example Show",
            videos = listOf(ep1AddonVideo),
        )

        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
            normalizeParentId = { _, parentId, videoId -> resolveEffectiveContentId(parentId, videoId) },
        ) { profileId, _ ->
            withIsolatedDownloadsAndAutoDownloadStore(profileId, canonicalDownloads, canonicalRule) { liveStore ->
                // Part A: Direct WatchedRepository.unmarkWatched through the addon id (with no videoId).
                WatchProgressRepository.flushPlaybackProgress(
                    session = downloadedEpisodeSession(
                        profileId = profileId,
                        showId = addonShowId,
                        episodeNumber = 1,
                        videoId = "$canonicalShowId:1:1",
                    ),
                    snapshot = PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = false,
                        isEnded = true,
                        durationMs = 2_400_000L,
                        positionMs = 2_400_000L,
                    ),
                )
                assertTrue(
                    liveStore.scheduleRemoval(
                        profileId,
                        AutoDownloadRemoval(canonicalDownloads[0].id, canonicalRule.key, dueAtEpochMs = 10_000_000L),
                    ),
                )
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                assertTrue(WatchedRepository.isWatched(id = canonicalShowId, type = "series", season = 1, episode = 1))

                WatchedRepository.unmarkWatched(id = addonShowId, type = "series", season = 1, episode = 1)

                assertFalse(WatchedRepository.isWatched(id = addonShowId, type = "series", season = 1, episode = 1))
                assertFalse(WatchedRepository.isWatched(id = canonicalShowId, type = "series", season = 1, episode = 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                assertEquals(emptyList(), AutoDownloads.removals.value)

                val canonicalPlan = planAutoDownload(
                    rule = canonicalRule,
                    released = canonicalReleased,
                    isWatched = { isEpisodeWatchedForAutoDownload(canonicalRule, it) },
                    downloads = canonicalDownloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(1, 2, 3), canonicalPlan.window.map { it.episodeNumber })

                val addonPlan = planAutoDownload(
                    rule = addonRule,
                    released = addonReleased,
                    isWatched = { isEpisodeWatchedForAutoDownload(addonRule, it) },
                    downloads = (1..3).map {
                        testDownload(it, DownloadStatus.Completed, showId = addonShowId, ruleKey = addonRule.key)
                    },
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(1, 2, 3), addonPlan.window.map { it.episodeNumber })

                // Part B: In-app unwatch through the addon id via WatchingActions.toggleEpisodeWatched.
                WatchProgressRepository.flushPlaybackProgress(
                    session = downloadedEpisodeSession(
                        profileId = profileId,
                        showId = addonShowId,
                        episodeNumber = 1,
                        videoId = "$canonicalShowId:1:1",
                    ),
                    snapshot = PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = false,
                        isEnded = true,
                        durationMs = 2_400_000L,
                        positionMs = 2_400_000L,
                    ),
                )
                assertTrue(
                    liveStore.scheduleRemoval(
                        profileId,
                        AutoDownloadRemoval(canonicalDownloads[0].id, canonicalRule.key, dueAtEpochMs = 10_000_000L),
                    ),
                )

                WatchingActions.toggleEpisodeWatched(
                    meta = addonMeta,
                    episode = ep1AddonVideo,
                    isCurrentlyWatched = true,
                )

                assertFalse(WatchedRepository.isWatched(id = addonShowId, type = "series", season = 1, episode = 1))
                assertFalse(WatchedRepository.isWatched(id = canonicalShowId, type = "series", season = 1, episode = 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                assertEquals(emptyList(), AutoDownloads.removals.value)

                val canonicalPlanAfterToggle = planAutoDownload(
                    rule = canonicalRule,
                    released = canonicalReleased,
                    isWatched = { isEpisodeWatchedForAutoDownload(canonicalRule, it) },
                    downloads = canonicalDownloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(1, 2, 3), canonicalPlanAfterToggle.window.map { it.episodeNumber })
            }
        }
    }

    @Test
    fun clearing_progress_under_canonical_or_addon_id_clears_all_aliases_and_pending_removal() {
        val addonShowId = "tun_tt7821584"
        val canonicalShowId = "tt7821584"
        val addonRule = testRule(showId = addonShowId, keepCount = 3).copy(deleteAfterWatching = true)
        val released = (1..6).map { testEpisode(it, showId = addonShowId) }
        val downloads = (1..3).map {
            testDownload(it, DownloadStatus.Completed, showId = addonShowId, ruleKey = addonRule.key)
        }

        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
            normalizeParentId = { _, parentId, videoId -> resolveEffectiveContentId(parentId, videoId) },
        ) { profileId, _ ->
            withIsolatedDownloadsAndAutoDownloadStore(profileId, downloads, addonRule) { liveStore ->
                fun finishAndSchedule() {
                    WatchProgressRepository.flushPlaybackProgress(
                        session = downloadedEpisodeSession(
                            profileId = profileId,
                            showId = addonShowId,
                            episodeNumber = 1,
                            videoId = "$canonicalShowId:1:1",
                        ),
                        snapshot = PlayerPlaybackSnapshot(
                            isLoading = false,
                            isPlaying = false,
                            isEnded = true,
                            durationMs = 2_400_000L,
                            positionMs = 2_400_000L,
                        ),
                    )
                    WatchedRepository.clearLocalState()
                    DesktopStorage.store("nuvio_watched").remove("watched_$profileId")
                    WatchedRepository.activateSource(WatchProgressSource.TRAKT)
                    assertTrue(
                        liveStore.scheduleRemoval(
                            profileId,
                            AutoDownloadRemoval(downloads[0].id, addonRule.key, dueAtEpochMs = 10_000_000L),
                        ),
                    )
                }

                fun assertClearedAndWindowBack() {
                    assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                    assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                    assertEquals(emptyList(), AutoDownloads.removals.value)
                    val plan = planAutoDownload(
                        rule = addonRule,
                        released = released,
                        isWatched = { isEpisodeWatchedForAutoDownload(addonRule, it) },
                        downloads = downloads,
                        pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                    )
                    assertEquals(listOf(1, 2, 3), plan.window.map { it.episodeNumber })
                }

                // 1) clearProgress via canonical id
                finishAndSchedule()
                WatchProgressRepository.clearProgress(videoId = "$canonicalShowId:1:1", parentMetaId = canonicalShowId)
                assertClearedAndWindowBack()

                // 2) clearProgress via addon id
                finishAndSchedule()
                WatchProgressRepository.clearProgress(videoId = "$addonShowId:1:1", parentMetaId = addonShowId)
                assertClearedAndWindowBack()

                // 3) removeProgress via canonical id
                finishAndSchedule()
                WatchProgressRepository.removeProgress(contentId = canonicalShowId, seasonNumber = 1, episodeNumber = 1)
                assertClearedAndWindowBack()

                // 4) removeProgress via addon id
                finishAndSchedule()
                WatchProgressRepository.removeProgress(contentId = addonShowId, seasonNumber = 1, episodeNumber = 1)
                assertClearedAndWindowBack()
            }
        }
    }

    @Test
    fun recorded_alias_group_persists_across_reload_so_canonical_unwatch_still_clears_addon_mark() {
        val addonShowId = "tun_tt7821585"
        val canonicalShowId = "tt7821585"
        val addonRule = testRule(showId = addonShowId, keepCount = 3).copy(deleteAfterWatching = true)
        val released = (1..6).map { testEpisode(it, showId = addonShowId) }
        val downloads = (1..3).map {
            testDownload(it, DownloadStatus.Completed, showId = addonShowId, ruleKey = addonRule.key)
        }

        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
            normalizeParentId = { _, parentId, videoId -> resolveEffectiveContentId(parentId, videoId) },
        ) { profileId, _ ->
            withIsolatedDownloadsAndAutoDownloadStore(profileId, downloads, addonRule) { liveStore ->
                WatchProgressRepository.flushPlaybackProgress(
                    session = downloadedEpisodeSession(
                        profileId = profileId,
                        showId = addonShowId,
                        episodeNumber = 1,
                        videoId = "$canonicalShowId:1:1",
                    ),
                    snapshot = PlayerPlaybackSnapshot(
                        isLoading = false,
                        isPlaying = false,
                        isEnded = true,
                        durationMs = 2_400_000L,
                        positionMs = 2_400_000L,
                    ),
                )
                assertTrue(
                    liveStore.scheduleRemoval(
                        profileId,
                        AutoDownloadRemoval(downloads[0].id, addonRule.key, dueAtEpochMs = 10_000_000L),
                    ),
                )

                // Wait for the async persist to land on disk, then reload from disk to simulate app restart.
                val deadlineMs = System.currentTimeMillis() + 5_000L
                while (System.currentTimeMillis() < deadlineMs) {
                    val saved = WatchProgressStorage.loadPayload(profileId).orEmpty()
                    if (addonShowId in saved && canonicalShowId in saved) break
                    Thread.sleep(10L)
                }
                val persisted = WatchProgressStorage.loadPayload(profileId).orEmpty()
                assertTrue(addonShowId in persisted && canonicalShowId in persisted)

                WatchProgressRepository.clearLocalState()
                WatchProgressRepository.activateSource(WatchProgressSource.TRAKT)
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))

                // Unwatch through canonical id after reload: both aliases and pending removal must clear.
                WatchedRepository.unmarkWatched(id = canonicalShowId, type = "series", season = 1, episode = 1)

                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                assertEquals(emptyList(), AutoDownloads.removals.value)

                val plan = planAutoDownload(
                    rule = addonRule,
                    released = released,
                    isWatched = { isEpisodeWatchedForAutoDownload(addonRule, it) },
                    downloads = downloads,
                    pendingRemovalDownloadIds = AutoDownloads.removals.value.mapTo(mutableSetOf()) { it.downloadId },
                )
                assertEquals(listOf(1, 2, 3), plan.window.map { it.episodeNumber })
            }
        }
    }

    @Test
    fun in_app_unwatch_through_addon_id_clears_canonical_completion_row_finished_mark_and_removal() {
        val addonShowId = "tun_tt7821586"
        val canonicalShowId = "tt7821586"
        val canonicalRule = testRule(showId = canonicalShowId, keepCount = 3).copy(deleteAfterWatching = true)
        val canonicalDownloads = (1..3).map {
            testDownload(it, DownloadStatus.Completed, showId = canonicalShowId, ruleKey = canonicalRule.key)
        }
        val ep1AddonVideo = MetaVideo(
            id = "$addonShowId:1:1",
            title = "Episode 1",
            released = "2024-01-01",
            season = 1,
            episode = 1,
        )
        val ep2AddonVideo = MetaVideo(
            id = "$addonShowId:1:2",
            title = "Episode 2",
            released = "2024-01-08",
            season = 1,
            episode = 2,
        )
        val addonMeta = MetaDetails(
            id = addonShowId,
            type = "series",
            name = "Example Show",
            videos = listOf(ep1AddonVideo, ep2AddonVideo),
        )
        val providerProgressEntries = mutableListOf<WatchProgressEntry>()

        withIsolatedTrackerHarness(
            source = WatchProgressSource.TRAKT,
            connectedProviders = setOf(TrackingProviderId.TRAKT),
            providerEntries = { id -> if (id == TrackingProviderId.TRAKT) providerProgressEntries.toList() else emptyList() },
            normalizeParentId = { _, parentId, videoId -> resolveEffectiveContentId(parentId, videoId) },
            onOptimisticProgress = { id, entry ->
                if (id == TrackingProviderId.TRAKT) {
                    providerProgressEntries.removeAll {
                        it.parentMetaId == entry.parentMetaId &&
                            it.seasonNumber == entry.seasonNumber &&
                            it.episodeNumber == entry.episodeNumber
                    }
                    providerProgressEntries += entry
                }
            },
            onOptimisticRemoval = { id, entries ->
                if (id == TrackingProviderId.TRAKT) {
                    providerProgressEntries.removeAll { existing ->
                        entries.any { removed ->
                            existing.parentMetaId == removed.parentMetaId &&
                                existing.seasonNumber == removed.seasonNumber &&
                                existing.episodeNumber == removed.episodeNumber
                        }
                    }
                }
            },
        ) { profileId, _ ->
            withIsolatedDownloadsAndAutoDownloadStore(profileId, canonicalDownloads, canonicalRule) { liveStore ->
                // Finish episodes 1 and 2 under the addon id while Trakt normalizes progress to the canonical id.
                for (ep in 1..2) {
                    WatchProgressRepository.flushPlaybackProgress(
                        session = downloadedEpisodeSession(
                            profileId = profileId,
                            showId = addonShowId,
                            episodeNumber = ep,
                            videoId = "$canonicalShowId:1:$ep",
                        ),
                        snapshot = PlayerPlaybackSnapshot(
                            isLoading = false,
                            isPlaying = false,
                            isEnded = true,
                            durationMs = 2_400_000L,
                            positionMs = 2_400_000L,
                        ),
                    )
                    assertTrue(
                        liveStore.scheduleRemoval(
                            profileId,
                            AutoDownloadRemoval(canonicalDownloads[ep - 1].id, canonicalRule.key, dueAtEpochMs = 10_000_000L),
                        ),
                    )
                }

                // Confirm canonical completion rows, finished marks, watched marks, and pending removals exist before unwatch.
                val ep1Before = WatchProgressRepository.progressForVideo(
                    videoId = "$canonicalShowId:1:1",
                    parentMetaId = canonicalShowId,
                    seasonNumber = 1,
                    episodeNumber = 1,
                )
                assertNotNull(ep1Before, "Expected canonical completion row for episode 1 before unwatch")
                assertTrue(ep1Before.isCompleted)
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                assertTrue(WatchedRepository.isWatched(id = canonicalShowId, type = "series", season = 1, episode = 1))
                assertEquals(
                    listOf(canonicalDownloads[0].id, canonicalDownloads[1].id),
                    AutoDownloads.removals.value.map { it.downloadId },
                )

                // Part 1: In-app single-episode unwatch through the addon id via WatchingActions.toggleEpisodeWatched.
                WatchingActions.toggleEpisodeWatched(
                    meta = addonMeta,
                    episode = ep1AddonVideo,
                    isCurrentlyWatched = true,
                )

                assertNull(
                    WatchProgressRepository.progressForVideo(
                        videoId = "$canonicalShowId:1:1",
                        parentMetaId = canonicalShowId,
                        seasonNumber = 1,
                        episodeNumber = 1,
                    ),
                    "Expected canonical completion row for episode 1 to be cleared after in-app addon unwatch",
                )
                assertTrue(
                    WatchProgressRepository.uiState.value.entries.none {
                        it.parentMetaId == canonicalShowId && it.seasonNumber == 1 && it.episodeNumber == 1
                    },
                    "Expected no published canonical progress entry for episode 1",
                )
                assertFalse(WatchedRepository.isWatched(id = addonShowId, type = "series", season = 1, episode = 1))
                assertFalse(WatchedRepository.isWatched(id = canonicalShowId, type = "series", season = 1, episode = 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 1))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 1))
                assertEquals(listOf(canonicalDownloads[1].id), AutoDownloads.removals.value.map { it.downloadId })

                // Episode 2 must remain intact until unwatched.
                val ep2StillPresent = WatchProgressRepository.progressForVideo(
                    videoId = "$canonicalShowId:1:2",
                    parentMetaId = canonicalShowId,
                    seasonNumber = 1,
                    episodeNumber = 2,
                )
                assertNotNull(ep2StillPresent, "Expected sibling episode 2 canonical completion row to remain")
                assertTrue(ep2StillPresent.isCompleted)
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 2))
                assertTrue(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 2))

                // Part 2: In-app multi-episode unwatch through the addon id via WatchingActions.toggleSeasonWatched.
                WatchingActions.toggleSeasonWatched(
                    meta = addonMeta,
                    episodes = listOf(ep2AddonVideo),
                    areCurrentlyWatched = true,
                )

                assertNull(
                    WatchProgressRepository.progressForVideo(
                        videoId = "$canonicalShowId:1:2",
                        parentMetaId = canonicalShowId,
                        seasonNumber = 1,
                        episodeNumber = 2,
                    ),
                    "Expected canonical completion row for episode 2 to be cleared after in-app season unwatch",
                )
                assertFalse(WatchedRepository.isWatched(id = addonShowId, type = "series", season = 1, episode = 2))
                assertFalse(WatchedRepository.isWatched(id = canonicalShowId, type = "series", season = 1, episode = 2))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(addonShowId, 1, 2))
                assertFalse(WatchProgressRepository.isEpisodeLocallyFinished(canonicalShowId, 1, 2))
                assertEquals(emptyList(), AutoDownloads.removals.value)

                // Stored local watch progress entries must also have no leftover canonical completion rows.
                WatchProgressRepository.activateSource(WatchProgressSource.NUVIO_SYNC)
                assertNull(
                    WatchProgressRepository.progressForVideo(
                        videoId = "$canonicalShowId:1:1",
                        parentMetaId = canonicalShowId,
                        seasonNumber = 1,
                        episodeNumber = 1,
                    ),
                    "Expected local stored canonical completion row for episode 1 to be cleared",
                )
                assertNull(
                    WatchProgressRepository.progressForVideo(
                        videoId = "$canonicalShowId:1:2",
                        parentMetaId = canonicalShowId,
                        seasonNumber = 1,
                        episodeNumber = 2,
                    ),
                    "Expected local stored canonical completion row for episode 2 to be cleared",
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun replaceProviderPortsForTest(
    id: TrackingProviderId,
    auth: TrackingAuthProvider?,
    scrobbler: TrackingScrobbler?,
    historyWriter: TrackingHistoryWriter?,
    watchedProvider: TrackingWatchedProvider?,
    progressProvider: TrackingProgressProvider?,
) {
    val registry = TrackingProviderRegistry
    val lock = registryField(registry, "lock")
    if (auth != null) {
        registry.register(auth)
    } else {
        val (removedAuth, previousJob) = synchronized(lock) {
            val authProviders = registryField(registry, "authProviders") as MutableMap<TrackingProviderId, TrackingAuthProvider>
            val profileStores = registryField(registry, "profileStores") as MutableSet<TrackingProfileStore>
            val jobs = registryField(registry, "authObservationJobs") as MutableMap<TrackingProviderId, Job>
            val removed = authProviders.remove(id)
            if (removed != null) profileStores.remove(removed)
            removed to jobs.remove(id)
        }
        previousJob?.cancel()
        if (removedAuth != null) {
            val connected = registryField(registry, "_connectedProviderIds") as MutableStateFlow<Set<TrackingProviderId>>
            connected.update { it - id }
        }
    }
    if (scrobbler != null) registry.registerScrobbler(scrobbler) else removeRegistryMapEntry(registry, lock, "scrobblers", id)
    if (historyWriter != null) registry.registerHistoryWriter(historyWriter) else removeRegistryMapEntry(registry, lock, "historyWriters", id)
    if (watchedProvider != null) registry.registerWatchedProvider(watchedProvider) else removeRegistryMapEntry(registry, lock, "watchedProviders", id)
    if (progressProvider != null) registry.registerProgressProvider(progressProvider) else removeRegistryMapEntry(registry, lock, "progressProviders", id)
}

@Suppress("UNCHECKED_CAST")
private fun profileStoresSnapshotForTest(): Set<TrackingProfileStore> {
    val registry = TrackingProviderRegistry
    val lock = registryField(registry, "lock")
    return synchronized(lock) {
        (registryField(registry, "profileStores") as Set<TrackingProfileStore>).toSet()
    }
}

@Suppress("UNCHECKED_CAST")
private fun removeRegistryMapEntry(
    registry: TrackingProviderRegistry,
    lock: Any,
    fieldName: String,
    id: TrackingProviderId,
) {
    synchronized(lock) {
        (registryField(registry, fieldName) as MutableMap<TrackingProviderId, Any?>).remove(id)
    }
}

private fun registryField(registry: TrackingProviderRegistry, name: String): Any =
    registry.javaClass.getDeclaredField(name).let { field ->
        field.isAccessible = true
        field.get(registry)
    }

private fun autoDownloadsStoreForTest(): AutoDownloadStore =
    AutoDownloads.javaClass.getDeclaredField("store").let { field ->
        field.isAccessible = true
        field.get(AutoDownloads) as AutoDownloadStore
    }

private fun resetLoadedProfileForTest(store: AutoDownloadStore) {
    store.javaClass.getDeclaredField("loadedProfileId").let { field ->
        field.isAccessible = true
        field.set(store, null)
    }
}
