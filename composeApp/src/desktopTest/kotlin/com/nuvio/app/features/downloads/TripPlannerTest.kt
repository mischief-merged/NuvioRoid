package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.home.CompletedSeriesCandidate
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.WantedContent
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.nextUpDismissKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TripPlannerTest {

    @Test
    fun `collectTripShowCandidates orders continue watching by activity then auto download rules and excludes hidden or dismissed`() {
        val olderNextUp = CompletedSeriesCandidate(
            content = WatchingContentRef(type = "series", id = "show-nextup"),
            seasonNumber = 1,
            episodeNumber = 3,
            markedAtEpochMs = 2_000L,
        )
        val dismissedNextUp = CompletedSeriesCandidate(
            content = WatchingContentRef(type = "series", id = "show-dismissed"),
            seasonNumber = 2,
            episodeNumber = 1,
            markedAtEpochMs = 9_000L,
        )
        val inProgressNewer = WatchProgressEntry(
            contentType = "series",
            parentMetaId = "show-active",
            parentMetaType = "series",
            videoId = "show-active:2:4",
            title = "Severance",
            seasonNumber = 2,
            episodeNumber = 4,
            lastPositionMs = 900_000L,
            durationMs = 3_000_000L,
            lastUpdatedEpochMs = 5_000L,
        )
        val hiddenEntry = inProgressNewer.copy(parentMetaId = "show-hidden", videoId = "show-hidden:1:1", title = "Hidden")
        val movieEntry = inProgressNewer.copy(contentType = "movie", parentMetaType = "movie", parentMetaId = "movie-1", seasonNumber = null, episodeNumber = null, title = "Dune")
        val staleEntry = inProgressNewer.copy(parentMetaId = "show-stale", videoId = "show-stale:1:1", title = "Stale", lastUpdatedEpochMs = 100L)

        val ruleForActive = AutoDownloadRule(
            parentMetaType = "series",
            parentMetaId = "show-active",
            title = "Severance",
        )
        val ruleOnlyShow = AutoDownloadRule(
            parentMetaType = "series",
            parentMetaId = "show-rule-only",
            title = "Andor",
        )

        val candidates = collectTripShowCandidates(
            inProgressEntries = listOf(inProgressNewer, hiddenEntry, movieEntry, staleEntry),
            nextUpSeeds = listOf(olderNextUp, dismissedNextUp),
            autoDownloadRules = listOf(ruleForActive, ruleOnlyShow),
            hiddenContentIds = setOf("show-hidden"),
            dismissedNextUpKeys = setOf(nextUpDismissKey("show-dismissed", seasonNumber = 2, episodeNumber = 1)),
            cutoffEpochMs = 1_000L,
            titlePeek = { _, id -> if (id == "show-nextup") "Silo" else null },
        )

        assertEquals(listOf("show-active", "show-nextup", "show-rule-only"), candidates.map { it.parentMetaId })
        assertTrue(candidates[0].fromContinueWatching)
        assertEquals(2, candidates[0].inProgressSeason)
        assertEquals(4, candidates[0].inProgressEpisode)
        assertEquals(ruleForActive, candidates[0].autoDownloadRule)
        assertEquals("Silo", candidates[1].title)
        assertTrue(candidates[1].fromContinueWatching)
        assertFalse(candidates[2].fromContinueWatching)
        assertEquals("Andor", candidates[2].title)
    }

    @Test
    fun `nextUnwatchedEpisodesForShow starts at active or after last watched and skips specials existing and user deleted`() {
        val episodes = listOf(
            ep("s0e1", 0, 1, 30),
            ep("s1e1", 1, 1, 45),
            ep("s1e2", 1, 2, 45),
            ep("s1e3", 1, 3, 45),
            ep("s1e4", 1, 4, 45),
            ep("s1e5", 1, 5, 45),
            ep("s1e6", 1, 6, 45),
        )
        val show = TripShowCandidate(
            parentMetaType = "series",
            parentMetaId = "show-1",
            title = "Severance",
            fromContinueWatching = true,
            inProgressSeason = 1,
            inProgressEpisode = 2,
            autoDownloadRule = AutoDownloadRule(
                parentMetaType = "series",
                parentMetaId = "show-1",
                title = "Severance",
                ownedEpisodeKeys = setOf(autoDownloadEpisodeKey(1, 4)), // Removed by user since no download row remains
            ),
        )
        val downloads = listOf(
            dl("d-3", "show-1", 1, 3, DownloadStatus.Completed),
            dl("d-5", "show-1", 1, 5, DownloadStatus.Downloading),
            dl("d-6", "show-1", 1, 6, DownloadStatus.Failed), // Failed can still be replanned!
        )

        val picked = nextUnwatchedEpisodesForShow(
            show = show,
            released = episodes,
            isWatched = { it.seasonNumber == 1 && it.episodeNumber == 1 },
            downloads = downloads,
        )

        // S00E01 excluded (special), S01E01 watched, S01E02 is in-progress -> kept,
        // S01E03 completed -> skipped, S01E04 user-deleted auto-download -> skipped,
        // S01E05 downloading -> skipped, S01E06 failed -> kept.
        assertEquals(listOf("1:2", "1:6"), picked.map { "${it.seasonNumber}:${it.episodeNumber}" })
    }

    @Test
    fun `planTripFill picks round robin up to target hours and prefers cached streams within quality settings`() = runBlocking {
        val showA = TripShowCandidate("series", "show-a", "Andor", fromContinueWatching = true)
        val showB = TripShowCandidate("series", "show-b", "Silo", fromContinueWatching = true)

        val metaByShow = mapOf(
            "show-a" to TripShowMeta(
                title = "Andor",
                defaultRuntimeMinutes = 60,
                episodes = (1..6).map { idx -> ep("a:$idx", 1, idx, runtimeMinutes = 60) },
            ),
            "show-b" to TripShowMeta(
                title = "Silo",
                defaultRuntimeMinutes = null,
                episodes = (1..6).map { idx -> ep("b:$idx", 1, idx, runtimeMinutes = null) }, // Uses DefaultTripEpisodeRuntimeMinutes = 45
            ),
        )

        val plan4h = planTripFill(
            targetHours = 4,
            shows = listOf(showA, showB),
            existingDownloads = emptyList(),
            qualityDefaults = SeasonDownloadDefaults(
                quality = BatchQuality.BestAvailable,
                maxBitrateMbps = 10,
                audioLanguage = DebridStreamLanguage.EN,
            ),
            storageLimitBytes = null,
            usedAgainstStorageLimitBytes = 0L,
            freeDiskBytes = 100_000_000_000L,
            connectionHoldReason = LimitedConnectionReason.Hotspot,
            isOffline = false,
            showLocationIsAway = { false },
            isEpisodeWatched = { _, _ -> false },
            loadShowMeta = { metaByShow[it.parentMetaId] },
            fetchStreams = { show, episode, _ ->
                val prefix = "${show.title}.S0${episode.seasonNumber}E0${episode.episodeNumber}"
                listOf(
                    stream("$prefix.2160p.WEB-DL.ENG", url = "https://ex/u4k", cached = false, bytes = 2_000_000_000L),
                    stream("$prefix.1080p.REMUX.ENG", url = "https://ex/remux", cached = true, bytes = 12_000_000_000L),
                    stream("$prefix.1080p.WEB-DL.FRENCH", url = "https://ex/fr", cached = true, bytes = 900_000_000L),
                    stream("$prefix.1080p.WEB-DL.ENG", url = "https://ex/c1080", cached = true, bytes = 900_000_000L),
                )
            },
        )

        // Round-robin: A1 (60m), B1 (45m=estimated), A2 (60m), B2 (45m), A3 (60m) -> 270m >= 240m (4h)!
        assertEquals(TripPlanStopReason.TargetReached, plan4h.stoppedReason)
        assertEquals(listOf("show-a:1:1", "show-b:1:1", "show-a:1:2", "show-b:1:2", "show-a:1:3"), plan4h.plannedEpisodes.map { "${it.show.parentMetaId}:${it.episode.seasonNumber}:${it.episode.episodeNumber}" })
        assertEquals(270, plan4h.totalPlannedMinutes)
        assertEquals(LimitedConnectionReason.Hotspot, plan4h.connectionHoldReason)
        // Cached English 1080p beats uncached 4K, French-only stream, and oversized >10Mbps REMUX.
        assertTrue(plan4h.plannedEpisodes.all { it.selectedStream.url == "https://ex/c1080" && it.isCached })
        // Show B episodes have runtimeEstimated = true (default 45m), Show A runtimeEstimated = false.
        assertFalse(plan4h.plannedEpisodes[0].runtimeEstimated)
        assertTrue(plan4h.plannedEpisodes[1].runtimeEstimated)
        assertEquals(45, plan4h.plannedEpisodes[1].effectiveRuntimeMinutes)
    }

    @Test
    fun `planTripFill enforces storage limit, disk free floor, offline, and skipped shows`() = runBlocking {
        val showAway = TripShowCandidate("series", "away", "OnUSB", fromContinueWatching = true)
        val showBroken = TripShowCandidate("series", "broken", "Broken", fromContinueWatching = true)
        val showOk = TripShowCandidate("series", "ok", "Severance", fromContinueWatching = true)

        val metaOk = TripShowMeta("Severance", listOf(ep("1:1", 1, 1, 50), ep("1:2", 1, 2, 50), ep("1:3", 1, 3, 50)))
        val stream1Gb = stream("Severance.1080p", url = "https://ex/1g", cached = true, bytes = 1_000_000_000L)

        // Storage limit = 4 GB, already used = 1.5 GB -> 2.5 GB left -> fits 2 episodes of 1 GB (2 GB <= 2.5 GB), stops on 3rd.
        val limitedPlan = planTripFill(
            targetHours = 8,
            shows = listOf(showAway, showBroken, showOk),
            existingDownloads = emptyList(),
            qualityDefaults = SeasonDownloadDefaults(),
            storageLimitBytes = 4_000_000_000L,
            usedAgainstStorageLimitBytes = 1_500_000_000L,
            freeDiskBytes = 50_000_000_000L,
            showLocationIsAway = { it == "away" },
            isEpisodeWatched = { _, _ -> false },
            loadShowMeta = { if (it.parentMetaId == "ok") metaOk else null },
            fetchStreams = { _, _, _ -> listOf(stream1Gb) },
        )

        assertEquals(TripPlanStopReason.StorageLimitReached, limitedPlan.stoppedReason)
        assertEquals(2, limitedPlan.plannedEpisodes.size)
        assertEquals(500_000_000L, limitedPlan.remainingStorageLimitBytes)
        assertEquals(
            listOf("away" to TripSkippedReason.FolderNotConnected, "broken" to TripSkippedReason.CouldNotLoadEpisodes),
            limitedPlan.skippedShows.map { it.show.parentMetaId to it.reason },
        )

        // Low disk space: free = 11.2 GB, minFree = 10 GB -> room = 1.2 GB -> fits only 1 x 1 GB episode.
        val lowDiskPlan = planTripFill(
            targetHours = 8,
            shows = listOf(showOk),
            existingDownloads = emptyList(),
            qualityDefaults = SeasonDownloadDefaults(),
            storageLimitBytes = null,
            usedAgainstStorageLimitBytes = 0L,
            freeDiskBytes = 11_200_000_000L,
            minFreeBytes = 10_000_000_000L,
            showLocationIsAway = { false },
            isEpisodeWatched = { _, _ -> false },
            loadShowMeta = { metaOk },
            fetchStreams = { _, _, _ -> listOf(stream1Gb) },
        )

        assertEquals(TripPlanStopReason.LowDiskSpace, lowDiskPlan.stoppedReason)
        assertEquals(1, lowDiskPlan.plannedEpisodes.size)

        val offlinePlan = planTripFill(
            targetHours = 4,
            shows = listOf(showOk),
            existingDownloads = emptyList(),
            qualityDefaults = SeasonDownloadDefaults(),
            storageLimitBytes = null,
            usedAgainstStorageLimitBytes = 0L,
            freeDiskBytes = 50_000_000_000L,
            isOffline = true,
            isEpisodeWatched = { _, _ -> false },
            loadShowMeta = { metaOk },
            fetchStreams = { _, _, _ -> emptyList() },
        )
        assertEquals(TripPlanStopReason.Offline, offlinePlan.stoppedReason)
        assertTrue(offlinePlan.plannedEpisodes.isEmpty())
    }

    @Test
    fun `TripFillRunner handles connection hold non-blockingly and enforces live storage limit with backup failover`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            val show = TripShowCandidate("series", "show-1", "Andor")
            val badStream = stream("Andor.S01E01.1080p.bad", url = "https://ex/bad", cached = true, bytes = 800_000_000L)
            val goodStream = stream("Andor.S01E01.1080p.good", url = "https://ex/good", cached = true, bytes = 800_000_000L)

            val planned = (1..3).map { index ->
                TripPlannedEpisode(
                    show = show,
                    episode = ep("ep-$index", 1, index, runtimeMinutes = 45),
                    effectiveRuntimeMinutes = 45,
                    runtimeEstimated = false,
                    rankedStreams = listOf(badStream, goodStream),
                    selectedStream = badStream,
                    isCached = true,
                    estimatedBytes = 800_000_000L,
                    sizeEstimated = false,
                )
            }
            val plan = TripFillPlan(
                requestedHours = 4,
                plannedEpisodes = planned,
                totalPlannedMinutes = 135,
                totalEstimatedBytes = 2_400_000_000L,
                qualityDefaults = SeasonDownloadDefaults(),
                storageLimitBytes = 2_000_000_000L, // Only fits 2 x 800MB (1.6GB), rejects 3rd!
                remainingStorageLimitBytes = 400_000_000L,
                freeDiskBytes = 50_000_000_000L,
                connectionHoldReason = LimitedConnectionReason.Hotspot,
                stoppedReason = TripPlanStopReason.StorageLimitReached,
            )

            val enqueuedUrls = mutableListOf<String>()
            val runner = TripFillRunner(
                scope = scope,
                downloads = downloads,
                prepareStream = { stream, _ -> stream },
                enqueue = { s, ep, chosen ->
                    val url = chosen.url.orEmpty()
                    enqueuedUrls += url
                    val item = if (url.endsWith("/bad")) {
                        dl("id-${ep.episodeNumber}-bad", s.parentMetaId, 1, ep.episodeNumber, DownloadStatus.Failed)
                    } else {
                        dl(
                            "id-${ep.episodeNumber}-good",
                            s.parentMetaId,
                            1,
                            ep.episodeNumber,
                            DownloadStatus.Paused,
                            pausedByConnection = true,
                            totalBytes = null, // Intentionally null so queuedEstimatedBytesByKey enforces 800MB!
                        )
                    }
                    downloads.value = DownloadsUiState(items = downloads.value.items.filterNot { it.episodeNumber == ep.episodeNumber && it.status == DownloadStatus.Failed } + item)
                    DownloadEnqueueResult.Started
                },
                storageLimitBytes = { plan.storageLimitBytes },
                autoDownloadBytes = { 0L },
                freeDiskBytes = { 50_000_000_000L },
                maxConcurrent = 1,
            )

            assertTrue(runner.start(plan))
            val finished = runner.state.first { !it.isRunning && it.doneSummary != null }
            val summary = finished.doneSummary!!

            // Ep 1 tried bad -> failed -> failed over to good (WaitingOnHold);
            // Ep 2 tried bad -> failed over to good (WaitingOnHold);
            // Ep 3 hit live storage limit (1.6GB + 800MB > 2.0GB) -> stopped early!
            assertEquals(2, summary.queuedCount)
            assertEquals(2, summary.waitingOnHoldCount)
            assertTrue(summary.stoppedEarly)
            assertEquals(listOf("https://ex/bad", "https://ex/good", "https://ex/bad", "https://ex/good"), enqueuedUrls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `TripFillRunner stop interrupts remaining episodes cleanly`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            val show = TripShowCandidate("series", "show-1", "Andor")
            val candidateStream = stream("Andor.1080p", url = "https://ex/ep", cached = true, bytes = 500_000_000L)
            val planned = (1..5).map { idx ->
                TripPlannedEpisode(
                    show = show,
                    episode = ep("ep-$idx", 1, idx, 45),
                    effectiveRuntimeMinutes = 45,
                    runtimeEstimated = false,
                    rankedStreams = listOf(candidateStream),
                    selectedStream = candidateStream,
                    isCached = true,
                    estimatedBytes = 500_000_000L,
                    sizeEstimated = false,
                )
            }
            val plan = TripFillPlan(
                requestedHours = 4,
                plannedEpisodes = planned,
                totalPlannedMinutes = 225,
                totalEstimatedBytes = 2_500_000_000L,
                qualityDefaults = SeasonDownloadDefaults(),
                storageLimitBytes = null,
                remainingStorageLimitBytes = null,
                freeDiskBytes = 50_000_000_000L,
                connectionHoldReason = null,
                stoppedReason = TripPlanStopReason.TargetReached,
            )

            val runner = TripFillRunner(
                scope = scope,
                downloads = downloads,
                prepareStream = { s, ep ->
                    if (ep.episodeNumber >= 2) delay(5_000)
                    s
                },
                enqueue = { s, ep, _ ->
                    downloads.value = DownloadsUiState(
                        items = downloads.value.items + dl("id-${ep.episodeNumber}", s.parentMetaId, 1, ep.episodeNumber, DownloadStatus.Completed),
                    )
                    DownloadEnqueueResult.Started
                },
                maxConcurrent = 1,
            )

            runner.start(plan)
            runner.state.first { it.queuedEpisodes == 1 }
            runner.stop()
            val done = runner.state.first { !it.isRunning && it.doneSummary != null }.doneSummary!!
            assertTrue(done.stoppedEarly)
            assertEquals(1, done.queuedCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `nextUnwatchedEpisodesForShow starts at inProgress episode even when earlier watched history is missing`() {
        val episodes = listOf(
            ep("s1e1", 1, 1, 45),
            ep("s1e2", 1, 2, 45),
            ep("s2e3", 2, 3, 45),
            ep("s2e4", 2, 4, 45),
            ep("s2e5", 2, 5, 45),
        )
        val show = TripShowCandidate(
            parentMetaType = "series",
            parentMetaId = "show-1",
            title = "Andor",
            fromContinueWatching = true,
            inProgressSeason = 2,
            inProgressEpisode = 4,
        )

        val picked = nextUnwatchedEpisodesForShow(
            show = show,
            released = episodes,
            isWatched = { false },
            downloads = emptyList(),
        )

        assertEquals(listOf("2:4", "2:5"), picked.map { "${it.seasonNumber}:${it.episodeNumber}" })
    }

    @Test
    fun `TripFillRunner does not adopt or discard a user replacement download`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            val show = TripShowCandidate("series", "show-1", "Andor")
            val streamA = stream("Andor.S01E01.1080p", url = "https://ex/a", cached = true, bytes = 800_000_000L)
            val planned = TripPlannedEpisode(
                show = show,
                episode = ep("ep-1", 1, 1, runtimeMinutes = 45),
                effectiveRuntimeMinutes = 45,
                runtimeEstimated = false,
                rankedStreams = listOf(streamA),
                selectedStream = streamA,
                isCached = true,
                estimatedBytes = 800_000_000L,
                sizeEstimated = false,
            )
            val plan = TripFillPlan(
                requestedHours = 4,
                plannedEpisodes = listOf(planned),
                totalPlannedMinutes = 45,
                totalEstimatedBytes = 800_000_000L,
                qualityDefaults = SeasonDownloadDefaults(audioLanguage = DebridStreamLanguage.EN),
                storageLimitBytes = null,
                remainingStorageLimitBytes = null,
                freeDiskBytes = 50_000_000_000L,
                connectionHoldReason = null,
                stoppedReason = TripPlanStopReason.TargetReached,
            )

            val discardedIds = mutableListOf<String>()
            val runner = TripFillRunner(
                scope = scope,
                downloads = downloads,
                prepareStream = { s, _ -> s },
                enqueue = { s, ep, _ ->
                    downloads.value = DownloadsUiState(
                        items = listOf(dl("id-a", s.parentMetaId, 1, ep.episodeNumber, DownloadStatus.Downloading)),
                    )
                    DownloadEnqueueResult.Started
                },
                enqueueOwned = { show, episode, _, _, _ ->
                    downloads.value = DownloadsUiState(listOf(dl("id-a", show.parentMetaId, 1, episode.episodeNumber, DownloadStatus.Downloading)))
                    "id-a"
                },
                discard = { discardedIds += it.id },
                inspect = { DownloadedFileFacts(exists = true, sizeBytes = 800_000_000L, durationMs = 45 * 60_000L, audioLanguages = listOf("fr")) },
                maxConcurrent = 1,
            )

            assertTrue(runner.start(plan))
            // Wait until runner's download A is active, then simulate user replacing A with B (French audio).
            downloads.first { state -> state.items.any { it.id == "id-a" } }
            downloads.value = DownloadsUiState(
                items = listOf(dl("id-b-user", "show-1", 1, 1, DownloadStatus.Completed)),
            )

            val done = runner.state.first { !it.isRunning && it.doneSummary != null }.doneSummary!!
            assertEquals(0, done.queuedCount)
            assertFalse(done.stoppedEarly)
            assertEquals(emptyList(), discardedIds)
            assertEquals(listOf("id-b-user"), downloads.value.items.map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `TripFillRunner rechecks before enqueueing so an episode started during prepareStream is not replaced`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            val show = TripShowCandidate("series", "show-1", "Andor")
            val candidate = stream("Andor.S01E01.1080p", url = "https://ex/a", cached = true, bytes = 800_000_000L)
            val planned = TripPlannedEpisode(
                show = show,
                episode = ep("ep-1", 1, 1, runtimeMinutes = 45),
                effectiveRuntimeMinutes = 45,
                runtimeEstimated = false,
                rankedStreams = listOf(candidate),
                selectedStream = candidate,
                isCached = true,
                estimatedBytes = 800_000_000L,
                sizeEstimated = false,
            )
            val plan = TripFillPlan(
                requestedHours = 4,
                plannedEpisodes = listOf(planned),
                totalPlannedMinutes = 45,
                totalEstimatedBytes = 800_000_000L,
                qualityDefaults = SeasonDownloadDefaults(),
                storageLimitBytes = null,
                remainingStorageLimitBytes = null,
                freeDiskBytes = 50_000_000_000L,
                connectionHoldReason = null,
                stoppedReason = TripPlanStopReason.TargetReached,
            )

            var enqueueCalls = 0
            val runner = TripFillRunner(
                scope = scope,
                downloads = downloads,
                prepareStream = { s, _ ->
                    // User or auto-download starts this episode while preparation suspends.
                    downloads.value = DownloadsUiState(
                        items = listOf(dl("manual-dl", "show-1", 1, 1, DownloadStatus.Downloading)),
                    )
                    s
                },
                enqueue = { _, _, _ ->
                    enqueueCalls++
                    DownloadEnqueueResult.MissingUrl
                },
                maxConcurrent = 1,
            )

            assertTrue(runner.start(plan))
            runner.state.first { !it.isRunning && it.doneSummary != null }
            assertEquals(0, enqueueCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `TripFillRunner checks backup candidate size against storage limit during failover`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            val show = TripShowCandidate("series", "show-1", "Andor")
            val smallBad = stream("Andor.S01E01.1080p.small", url = "https://ex/small", cached = true, bytes = 1_000_000_000L)
            val hugeBackup = stream("Andor.S01E01.2160p.huge", url = "https://ex/huge", cached = true, bytes = 8_000_000_000L)
            val planned = TripPlannedEpisode(
                show = show,
                episode = ep("ep-1", 1, 1, runtimeMinutes = 45),
                effectiveRuntimeMinutes = 45,
                runtimeEstimated = false,
                rankedStreams = listOf(smallBad, hugeBackup),
                selectedStream = smallBad,
                isCached = true,
                estimatedBytes = 1_000_000_000L,
                sizeEstimated = false,
            )
            val plan = TripFillPlan(
                requestedHours = 4,
                plannedEpisodes = listOf(planned),
                totalPlannedMinutes = 45,
                totalEstimatedBytes = 1_000_000_000L,
                qualityDefaults = SeasonDownloadDefaults(),
                storageLimitBytes = 2_000_000_000L,
                remainingStorageLimitBytes = 1_000_000_000L,
                freeDiskBytes = 50_000_000_000L,
                connectionHoldReason = null,
                stoppedReason = TripPlanStopReason.TargetReached,
            )

            val enqueuedUrls = mutableListOf<String>()
            val runner = TripFillRunner(
                scope = scope,
                downloads = downloads,
                prepareStream = { s, _ -> s },
                enqueue = { s, ep, chosen ->
                    enqueuedUrls += chosen.url.orEmpty()
                    downloads.value = DownloadsUiState(
                        items = listOf(dl("id-fail", s.parentMetaId, 1, ep.episodeNumber, DownloadStatus.Failed)),
                    )
                    DownloadEnqueueResult.Started
                },
                storageLimitBytes = { plan.storageLimitBytes },
                autoDownloadBytes = { 0L },
                freeDiskBytes = { 50_000_000_000L },
                maxConcurrent = 1,
            )

            assertTrue(runner.start(plan))
            val done = runner.state.first { !it.isRunning && it.doneSummary != null }.doneSummary!!
            assertEquals(listOf("https://ex/small"), enqueuedUrls)
            assertTrue(done.stoppedEarly)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `TripFillRunner preserves resumable failed partials instead of overwriting them`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val partialFailed = dl(
                id = "failed-with-partial",
                parentMetaId = "show-1",
                season = 1,
                episode = 1,
                status = DownloadStatus.Failed,
                totalBytes = 2_000_000_000L,
            ).copy(
                downloadedBytes = 1_500_000_000L,
                failureKind = DownloadFailureKind.Other,
                partialFileKey = "dev:1",
            )
            val downloads = MutableStateFlow(DownloadsUiState(items = listOf(partialFailed)))
            val show = TripShowCandidate("series", "show-1", "Andor")
            val candidate = stream("Andor.S01E01.1080p", url = "https://ex/new", cached = true, bytes = 2_000_000_000L)
            val planned = TripPlannedEpisode(
                show = show,
                episode = ep("ep-1", 1, 1, runtimeMinutes = 45),
                effectiveRuntimeMinutes = 45,
                runtimeEstimated = false,
                rankedStreams = listOf(candidate),
                selectedStream = candidate,
                isCached = true,
                estimatedBytes = 2_000_000_000L,
                sizeEstimated = false,
            )
            val plan = TripFillPlan(
                requestedHours = 4,
                plannedEpisodes = listOf(planned),
                totalPlannedMinutes = 45,
                totalEstimatedBytes = 2_000_000_000L,
                qualityDefaults = SeasonDownloadDefaults(),
                storageLimitBytes = null,
                remainingStorageLimitBytes = null,
                freeDiskBytes = 50_000_000_000L,
                connectionHoldReason = null,
                stoppedReason = TripPlanStopReason.TargetReached,
            )

            var enqueueCalls = 0
            val runner = TripFillRunner(
                scope = scope,
                downloads = downloads,
                prepareStream = { s, _ -> s },
                enqueue = { _, _, _ ->
                    enqueueCalls++
                    DownloadEnqueueResult.Replaced
                },
                maxConcurrent = 1,
            )

            assertTrue(runner.start(plan))
            runner.state.first { !it.isRunning && it.doneSummary != null }
            assertEquals(0, enqueueCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `all approved episodes are saved before preparation even when transfers stall`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            val plan = reviewPlan(episodes = 4)
            val stored = mutableMapOf<Int, String>()
            val queue = TripFillQueue({ stored[it] }, { profile, payload -> stored[profile] = payload })
            var savedBeforePreparing = false
            val runner = TripFillRunner(
                scope, downloads,
                prepareStream = { stream, _ ->
                    savedBeforePreparing = queue.pending(1)?.plannedEpisodes?.size == 4
                    stream
                },
                enqueue = { show, episode, _ ->
                    downloads.update { state -> DownloadsUiState(state.items + dl(
                        "queued-${episode.episodeNumber}", show.parentMetaId, 1, episode.episodeNumber, DownloadStatus.Downloading,
                    )) }
                    DownloadEnqueueResult.Started
                },
                persistPlan = queue::save,
                currentProfileId = { 1 },
                maxConcurrent = 2,
            )
            runner.start(plan.copy(profileId = 1))
            kotlinx.coroutines.withTimeout(30_000) { downloads.first { it.items.size == 2 } }
            assertTrue(savedBeforePreparing)
            val afterRestart = TripFillQueue({ stored[it] }, { profile, payload -> stored[profile] = payload }).pending(1)
            assertEquals(listOf(1, 2, 3, 4), afterRestart?.plannedEpisodes?.map { it.episode.episodeNumber })
            assertEquals(null, queue.pending(2))
            assertTrue(downloads.value.items.all { it.status == DownloadStatus.Downloading })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `switching profiles during preparation stops the old plan`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var profile = 1
            var enqueued = 0
            val runner = TripFillRunner(
                scope, MutableStateFlow(DownloadsUiState()),
                prepareStream = { stream, _ -> profile = 2; stream },
                enqueue = { _, _, _ -> enqueued++; DownloadEnqueueResult.MissingUrl },
                currentProfileId = { profile },
            )
            runner.start(reviewPlan().copy(profileId = 1))
            kotlinx.coroutines.withTimeout(30_000) { runner.state.first { !it.isRunning && it.doneSummary != null } }
            assertEquals(0, enqueued)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `planning measures the show destination rather than the default drive`() = runBlocking {
        val show = TripShowCandidate("series", "show-1", "Show")
        suspend fun plan(defaultFree: Long, destinationFree: Long) = planTripFill(
            targetHours = 1,
            shows = listOf(show), existingDownloads = emptyList(), qualityDefaults = SeasonDownloadDefaults(),
            freeDiskBytes = defaultFree, minFreeBytes = 100_000_000L,
            destinationOf = { TripDestination("external", destinationFree) },
            isEpisodeWatched = { _, _ -> false },
            loadShowMeta = { TripShowMeta("Show", listOf(ep("ep-1", 1, 1, 60))) },
            fetchStreams = { _, _, _ -> listOf(stream("Show.1080p", "https://ex/one", true, 800_000_000L)) },
        )
        assertEquals(TripPlanStopReason.LowDiskSpace, plan(5_000_000_000L, 850_000_000L).stoppedReason)
        assertEquals(1, plan(50_000_000L, 5_000_000_000L).plannedEpisodes.size)
    }

    @Test
    fun `a failed transfer with partial bytes does not fail over to a fresh download`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            var enqueued = 0
            val plan = reviewPlan().let { it.copy(plannedEpisodes = it.plannedEpisodes.map { item ->
                item.copy(rankedStreams = item.rankedStreams + item.selectedStream.copy(url = "https://ex/backup"))
            }) }
            val runner = TripFillRunner(scope, downloads,
                prepareStream = { stream, _ -> stream },
                enqueue = { _, _, _ ->
                    enqueued++
                    downloads.value = DownloadsUiState(listOf(dl("partial", "show-1", 1, 1, DownloadStatus.Failed)
                        .copy(downloadedBytes = 300_000_000L, partialFileKey = "owned-partial")))
                    DownloadEnqueueResult.Started
                },
            )
            runner.start(plan)
            kotlinx.coroutines.withTimeout(30_000) { runner.state.first { !it.isRunning && it.doneSummary != null } }
            assertEquals(1, enqueued)
            assertEquals(300_000_000L, downloads.value.items.single().downloadedBytes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a recovered plan skips persisted transfers and queues the remaining approved episodes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stored = mutableMapOf<Int, String>()
            val queue = TripFillQueue({ stored[it] }, { profile, payload -> stored[profile] = payload })
            val plan = reviewPlan(4).copy(profileId = 1)
            queue.save(plan)
            val recovered = TripFillQueue({ stored[it] }, { profile, payload -> stored[profile] = payload }).pending(1)!!
            val downloads = MutableStateFlow(DownloadsUiState(listOf(
                dl("done-1", "show-1", 1, 1, DownloadStatus.Completed),
                dl("done-2", "show-1", 1, 2, DownloadStatus.Completed),
            )))
            val queued = mutableListOf<Int>()
            val runner = TripFillRunner(scope, downloads,
                prepareStream = { stream, _ -> stream },
                enqueue = { _, episode, _ ->
                    synchronized(queued) { queued += episode.episodeNumber }
                    downloads.value = DownloadsUiState(downloads.value.items + dl(
                        "restored-${episode.episodeNumber}", "show-1", 1, episode.episodeNumber, DownloadStatus.Completed,
                    ))
                    DownloadEnqueueResult.Started
                },
                currentProfileId = { 1 }, persistPlan = queue::save, clearPlan = queue::clear,
                reloadCandidates = { _, _ -> listOf(plan.plannedEpisodes.first().selectedStream) },
                maxConcurrent = 1,
            )
            runner.start(recovered)
            kotlinx.coroutines.withTimeout(30_000) { runner.state.first { !it.isRunning && it.doneSummary != null } }
            assertEquals(listOf(3, 4), queued.sorted())
            assertEquals(listOf(1, 2, 3, 4), downloads.value.items.map { it.episodeNumber }.sortedBy { it })
            assertEquals(null, queue.pending(1))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `concurrent reservations on different drives do not use each others free space`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            val firstEnqueue = kotlinx.coroutines.CompletableDeferred<Unit>()
            val secondEnqueue = java.util.concurrent.CountDownLatch(1)
            val plan = reviewPlan(2).let { original -> original.copy(plannedEpisodes = original.plannedEpisodes.mapIndexed { index, item ->
                item.copy(show = item.show.copy(parentMetaId = "show-$index"))
            }) }
            val runner = TripFillRunner(scope, downloads,
                prepareStream = { stream, episode ->
                    if (episode.episodeNumber == 2) firstEnqueue.await()
                    stream
                },
                enqueue = { show, episode, _ ->
                    if (episode.episodeNumber == 1) {
                        firstEnqueue.complete(Unit)
                        check(secondEnqueue.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    downloads.update { state -> DownloadsUiState(state.items + dl(
                        "drive-${episode.episodeNumber}", show.parentMetaId, 1, episode.episodeNumber, DownloadStatus.Completed,
                    )) }
                    if (episode.episodeNumber == 2) secondEnqueue.countDown()
                    DownloadEnqueueResult.Started
                },
                destinationOf = { TripDestination(it.parentMetaId, 11_000_000_000L) },
                maxConcurrent = 2,
            )
            runner.start(plan)
            val done = kotlinx.coroutines.withTimeout(40_000) {
                runner.state.first { !it.isRunning && it.doneSummary != null }.doneSummary!!
            }
            assertEquals(2, done.queuedCount)
            assertFalse(done.stoppedEarly)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a flagged completed copy is kept while the runner tries a backup`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            val plan = reviewPlan().let { original -> original.copy(plannedEpisodes = original.plannedEpisodes.map { item ->
                item.copy(rankedStreams = item.rankedStreams + item.selectedStream.copy(url = "https://ex/backup"))
            }) }
            var attempts = 0
            val runner = TripFillRunner(scope, downloads,
                prepareStream = { stream, _ -> stream },
                enqueue = { _, _, _ -> DownloadEnqueueResult.MissingUrl },
                enqueueOwned = { _, _, _, _, _ ->
                    attempts++
                    val id = "copy-$attempts"
                    val copy = dl(id, "show-1", 1, 1, DownloadStatus.Completed).copy(
                        framesCheck = if (attempts == 1) DownloadFramesCheck.NotTheRealVideo else DownloadFramesCheck.Passed,
                    )
                    downloads.update { state -> DownloadsUiState(listOf(copy) + state.items) }
                    id
                },
                maxConcurrent = 1,
            )
            runner.start(plan)
            val done = kotlinx.coroutines.withTimeout(30_000) {
                runner.state.first { !it.isRunning && it.doneSummary != null }.doneSummary!!
            }
            assertEquals(2, attempts)
            assertEquals(1, done.queuedCount)
            assertEquals(setOf("copy-1", "copy-2"), downloads.value.items.map { it.id }.toSet())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a row pause leaves the remaining approved episodes queueing`() = runBlocking {
        assertRowChangeKeepsApprovedEpisodes { item -> item.copy(status = DownloadStatus.Paused) }
    }

    @Test
    fun `a storage limit pause leaves room for smaller approved episodes`() = runBlocking {
        assertRowChangeKeepsApprovedEpisodes(storageLimit = 3_300_000_000L) { item ->
            item.copy(status = DownloadStatus.Paused, autoDownloadStop = AutoDownloadStop.StorageLimit)
        }
    }

    @Test
    fun `a source replacement leaves the remaining approved episodes queueing`() = runBlocking {
        assertRowChangeKeepsApprovedEpisodes { item ->
            item.copy(id = "user-replacement", sourceUrl = "https://ex/replacement", tripFill = false)
        }
    }

    @Test
    fun `a storage pause that blocks the rest keeps the approved plan for recovery`() = runBlocking {
        assertRowChangeKeepsApprovedEpisodes(storageLimit = 1_000_000_000L, expectBudgetStop = true) { item ->
            item.copy(status = DownloadStatus.Paused, autoDownloadStop = AutoDownloadStop.StorageLimit)
        }
    }

    private suspend fun assertRowChangeKeepsApprovedEpisodes(
        storageLimit: Long? = null,
        expectBudgetStop: Boolean = false,
        changeRow: (DownloadItem) -> DownloadItem,
    ) = kotlinx.coroutines.withTimeout(30_000) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloads = MutableStateFlow(DownloadsUiState())
            val stored = mutableMapOf<Int, String>()
            val queue = TripFillQueue({ stored[it] }, { profile, payload -> stored[profile] = payload })
            val plan = reviewPlan(3).copy(profileId = 1)
            val enqueued = mutableListOf<Int>()
            val discarded = mutableListOf<String>()
            var liveLimit = storageLimit
            fun newRunner() = TripFillRunner(
                scope, downloads,
                prepareStream = { stream, _ -> stream },
                enqueue = { _, _, _ -> DownloadEnqueueResult.MissingUrl },
                enqueueOwned = { show, episode, _, _, _ ->
                    enqueued += episode.episodeNumber
                    val first = enqueued.size == 1
                    downloads.update { state -> DownloadsUiState(state.items + dl(
                        "owned-${episode.episodeNumber}", show.parentMetaId, 1, episode.episodeNumber,
                        if (first) DownloadStatus.Downloading else DownloadStatus.Completed,
                        totalBytes = 800_000_000L,
                    ).copy(
                        tripFill = true,
                        downloadedBytes = if (first) 100_000_000L else 800_000_000L,
                        partialFileKey = if (first) "saved-partial" else null,
                    )) }
                    "owned-${episode.episodeNumber}"
                },
                discard = { discarded += it.id },
                currentProfileId = { 1 }, persistPlan = queue::save, clearPlan = queue::clear,
                storageLimitBytes = { liveLimit },
                autoDownloadBytes = { downloads.value.items.filter { it.tripFill }.sumOf { it.totalBytes ?: it.downloadedBytes } },
                reloadCandidates = { _, _ -> listOf(plan.plannedEpisodes.first().selectedStream) },
                maxConcurrent = 1,
            )
            val runner = newRunner()
            assertTrue(runner.start(plan))
            val first = downloads.first { it.items.isNotEmpty() }.items.single()
            val changed = changeRow(first)
            downloads.value = DownloadsUiState(listOf(changed))
            val done = runner.state.first { !it.isRunning && it.doneSummary != null }.doneSummary!!

            if (expectBudgetStop) {
                assertEquals(listOf(first.episodeNumber), enqueued)
                assertTrue(done.stoppedEarly)
                val recovered = queue.pending(1)
                assertEquals(listOf(1, 2, 3), recovered?.plannedEpisodes?.map { it.episode.episodeNumber })
                liveLimit = 3_300_000_000L
                val recovery = newRunner()
                assertTrue(recovery.start(recovered!!))
                recovery.state.first { !it.isRunning && it.doneSummary != null }
            } else {
                assertFalse(done.stoppedEarly)
                assertEquals(if (changed.id == first.id) 3 else 2, done.queuedCount)
                assertEquals(0, done.waitingOnHoldCount)
                assertEquals(0, done.failedCount)
            }
            assertEquals(listOf(1, 2, 3), enqueued.sorted())
            assertEquals(changed, downloads.value.items.single { it.id == changed.id })
            assertEquals(emptyList(), discarded)
            assertEquals(null, queue.pending(1))
        } finally {
            scope.cancel()
        }
    }

    private fun reviewPlan(episodes: Int = 1): TripFillPlan {
        val candidate = stream("Show.1080p", "https://ex/one", true, 800_000_000L)
        val planned = (1..episodes).map { number -> TripPlannedEpisode(
            TripShowCandidate("series", "show-1", "Show"), ep("ep-$number", 1, number, 45),
            45, false, listOf(candidate), candidate, true, 800_000_000L, false,
        ) }
        return TripFillPlan(4, planned, 45 * episodes, 800_000_000L * episodes, SeasonDownloadDefaults(),
            null, null, 50_000_000_000L, null, TripPlanStopReason.TargetReached)
    }

    private fun ep(videoId: String, season: Int, episode: Int, runtimeMinutes: Int?): SeasonBatchEpisode =
        SeasonBatchEpisode(
            videoId = videoId,
            seasonNumber = season,
            episodeNumber = episode,
            title = "Episode $episode",
            thumbnail = null,
            runtimeMinutes = runtimeMinutes,
        )

    private fun dl(
        id: String,
        parentMetaId: String,
        season: Int,
        episode: Int,
        status: DownloadStatus,
        pausedByConnection: Boolean = false,
        totalBytes: Long? = 500_000_000L,
    ): DownloadItem = DownloadItem(
        id = id,
        contentType = "series",
        parentMetaId = parentMetaId,
        parentMetaType = "series",
        videoId = "$parentMetaId:$season:$episode",
        title = "Andor",
        seasonNumber = season,
        episodeNumber = episode,
        streamTitle = "S${season}E${episode}",
        providerName = "Addon",
        sourceUrl = "https://ex/$id",
        localFileUri = if (status == DownloadStatus.Completed) "file:///dl/$id.mkv" else null,
        fileName = "$id.mkv",
        status = status,
        downloadedBytes = if (status == DownloadStatus.Completed) 500_000_000L else 0L,
        totalBytes = totalBytes,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 2_000L,
        pausedByConnection = pausedByConnection,
    )

    private fun stream(name: String, url: String, cached: Boolean, bytes: Long): StreamItem = StreamItem(
        name = name,
        title = name,
        url = url,
        addonName = "Addon",
        addonId = "addon:test",
        behaviorHints = StreamBehaviorHints(videoSize = bytes, filename = "$name.mkv"),
        debridCacheStatus = StreamDebridCacheStatus(
            providerId = "rd",
            providerName = "RD",
            state = if (cached) StreamDebridCacheState.CACHED else StreamDebridCacheState.NOT_CACHED,
            cachedSize = bytes,
        ),
    )
}
