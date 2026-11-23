package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonBatchDownloaderTest {
    private fun stream(label: String, url: String?, addon: String = "A", magnet: Boolean = false) = StreamItem(
        name = label,
        url = if (magnet) "magnet:?xt=urn:btih:abc" else url,
        addonName = addon,
        addonId = "addon:$addon",
        behaviorHints = StreamBehaviorHints(),
    )

    private val episodes = (1..5).map { SeasonBatchEpisode("tt1:1:$it", 1, it, "Ep $it", null) }

    private fun request(
        eps: List<SeasonBatchEpisode> = episodes,
        quality: BatchQuality = BatchQuality.BestAvailable,
    ) = SeasonBatchRequest(
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        title = "Show",
        logo = null,
        poster = null,
        background = null,
        seasonNumber = 1,
        episodes = eps,
        quality = quality,
    )

    private fun item(ep: Int, status: DownloadStatus, url: String = "http://x/$ep.mp4") = DownloadItem(
        id = "id$ep-${status.name}",
        contentType = "series",
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = "tt1:1:$ep",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = ep,
        streamTitle = "s",
        providerName = "A",
        sourceUrl = url,
        fileName = "f$ep.mp4",
        status = status,
        downloadedBytes = if (status == DownloadStatus.Completed) 700_000_000 else 0,
        createdAtEpochMs = 0,
        updatedAtEpochMs = 0,
    )

    /** Runs [onRead] every time the current downloads are read, to act between two steps of the downloader. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ObservedDownloads(
        private val inner: StateFlow<DownloadsUiState>,
        private val onRead: () -> Unit,
    ) : StateFlow<DownloadsUiState> by inner {
        override val value: DownloadsUiState
            get() {
                onRead()
                return inner.value
            }
    }

    /** Fake downloads store: enqueue adds a Downloading item; the test then drives it to a terminal state. */
    private class Fake(initial: List<DownloadItem> = emptyList()) {
        val state = MutableStateFlow(DownloadsUiState(initial))
        val enqueued = mutableListOf<Pair<Int, String>>()
        val discarded = mutableListOf<String>()

        /** The size a download has when the test lets it finish. */
        var finishedBytes = 700_000_000L

        fun discard(item: DownloadItem) {
            discarded += item.sourceUrl
            state.value = DownloadsUiState(state.value.items.filterNot { it.id == item.id })
        }

        /** The server announces [totalBytes] for the running download of [ep]. */
        fun announce(ep: Int, totalBytes: Long) {
            state.value = DownloadsUiState(
                state.value.items.map { if (it.episodeNumber == ep && it.status == DownloadStatus.Downloading) it.copy(totalBytes = totalBytes) else it },
            )
        }
        var maxActive = 0

        fun enqueue(req: SeasonBatchRequest, ep: SeasonBatchEpisode, stream: StreamItem): DownloadEnqueueResult {
            val url = stream.playableDirectUrl ?: return DownloadEnqueueResult.MissingUrl
            if (url.endsWith(".m3u8")) return DownloadEnqueueResult.UnsupportedFormat
            enqueued += ep.episodeNumber to url
            val others = state.value.items.filterNot {
                it.parentMetaId == req.parentMetaId && it.seasonNumber == ep.seasonNumber && it.episodeNumber == ep.episodeNumber
            }
            state.value = DownloadsUiState(
                others + DownloadItem(
                    id = "d${enqueued.size}", contentType = req.contentType, parentMetaId = req.parentMetaId,
                    parentMetaType = req.parentMetaType, videoId = ep.videoId, title = req.title,
                    seasonNumber = ep.seasonNumber, episodeNumber = ep.episodeNumber, streamTitle = stream.streamLabel,
                    providerName = stream.addonName, sourceUrl = url, fileName = "f.mp4",
                    status = DownloadStatus.Downloading, createdAtEpochMs = 0, updatedAtEpochMs = 0,
                ),
            )
            maxActive = maxOf(maxActive, state.value.items.count { it.status == DownloadStatus.Downloading })
            return DownloadEnqueueResult.Started
        }

        fun setStatus(ep: Int, status: DownloadStatus?, parent: String = "tt1") {
            state.value = DownloadsUiState(
                state.value.items.mapNotNull {
                    if (it.episodeNumber != ep || it.parentMetaId != parent) {
                        it
                    } else {
                        status?.let { s ->
                            it.copy(status = s, downloadedBytes = if (s == DownloadStatus.Completed) finishedBytes else it.downloadedBytes)
                        }
                    }
                },
            )
        }

        fun downloading() = state.value.items.filter { it.status == DownloadStatus.Downloading }.map { it.episodeNumber!! }
    }

    private fun TestScope.downloader(
        fake: Fake,
        fetch: suspend (SeasonBatchEpisode) -> List<StreamItem> = { ep -> listOf(stream("1080p", "http://x/${ep.episodeNumber}.mp4")) },
        onFinished: suspend (SeasonBatchRequest, SeasonBatchSummary) -> Unit = { _, _ -> },
        max: Int = 2,
        fallback: (suspend (SeasonBatchEpisode) -> List<StreamItem>)? = null,
        prepare: suspend (StreamItem, SeasonBatchEpisode) -> StreamItem? = { s, _ -> s },
        downloads: StateFlow<DownloadsUiState> = fake.state,
        enqueue: (SeasonBatchRequest, SeasonBatchEpisode, StreamItem) -> DownloadEnqueueResult = fake::enqueue,
        minVideoBytes: (SeasonBatchEpisode) -> Long = { minPlausibleVideoBytes(it.runtimeMinutes) },
        verifyCompleted: (DownloadItem) -> Boolean = { true },
        inspect: (DownloadItem) -> DownloadedFileFacts? = { null },
        preferAudioLanguage: (DownloadItem, String) -> Unit = { _, _ -> },
    ) = SeasonBatchDownloader(
        // Not backgroundScope: advanceUntilIdle() ignores tasks that belong to background work.
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        downloads = downloads,
        fetchStreams = fetch,
        fetchFallbackStreams = fallback,
        prepareStream = prepare,
        enqueue = enqueue,
        onFinished = onFinished,
        maxConcurrent = max,
        discard = fake::discard,
        verifyCompleted = verifyCompleted,
        inspect = inspect,
        preferAudioLanguage = preferAudioLanguage,
        minVideoBytes = minVideoBytes,
    )

    // ---- pure helpers ----

    @Test
    fun quality_filter_keeps_only_sources_at_the_chosen_resolution() {
        val s2160 = stream("Show 2160p HDR", "http://x/a.mkv")
        val s4k = stream("Show 4K", "http://x/b.mkv")
        val s1080 = stream("Show 1080p", "http://x/c.mkv")
        val s720 = stream("Show 720p", "http://x/d.mkv")
        val all = listOf(s720, s1080, s2160, s4k)
        assertEquals(listOf(s1080), rankBatchCandidates(all, BatchQuality.P1080))
        assertEquals(listOf(s2160, s4k), rankBatchCandidates(all, BatchQuality.P2160))
        assertEquals(listOf(s720), rankBatchCandidates(all, BatchQuality.P720))
    }

    @Test
    fun best_available_keeps_every_source_and_tries_the_highest_resolution_first() {
        val unknown = stream("Show WEB-DL", "http://x/u.mkv")
        val s720 = stream("Show 720p", "http://x/d.mkv")
        val s1080 = stream("Show 1080p", "http://x/c.mkv")
        val s2160 = stream("Show 2160p HDR", "http://x/a.mkv")
        val s4k = stream("Show 4K", "http://x/b.mkv")
        assertEquals(
            listOf(s2160, s4k, s1080, s720, unknown),
            rankBatchCandidates(listOf(unknown, s720, s1080, s2160, s4k), BatchQuality.BestAvailable),
        )
    }

    @Test
    fun best_available_prefers_a_higher_resolution_that_needs_resolving_over_a_lower_direct_link() {
        val direct720 = stream("Show 720p", "http://x/d.mkv")
        val resolve1080 = StreamItem(
            name = "Show 1080p", addonName = "D", addonId = "addon:D",
            clientResolve = StreamClientResolve(type = "debrid", service = "realdebrid", isCached = true),
        )
        val direct1080 = stream("Show 1080p", "http://x/c.mkv")
        assertEquals(
            listOf(direct1080, resolve1080, direct720),
            rankBatchCandidates(listOf(direct720, resolve1080, direct1080), BatchQuality.BestAvailable),
        )
    }

    @Test
    fun sources_of_one_resolution_keep_their_order_whatever_their_size() {
        fun sized(label: String, bytes: Long, direct: Boolean = true) = StreamItem(
            name = label,
            url = if (direct) "http://x/${label.replace(' ', '-')}.mkv" else null,
            addonName = "A",
            addonId = "addon:A",
            behaviorHints = StreamBehaviorHints(videoSize = bytes),
            clientResolve = if (direct) null else StreamClientResolve(type = "debrid", service = "realdebrid", isCached = true),
        )
        val smallDirect = sized("1080p small direct", 1_000_000_000L)
        val largeResolve = sized("1080p large resolve", 9_000_000_000L, direct = false)
        val largeDirect = sized("1080p large direct", 8_000_000_000L)
        val small720 = sized("720p small", 500_000_000L)
        val huge2160 = sized("2160p huge resolve", 40_000_000_000L, direct = false)
        assertEquals(
            listOf(huge2160, smallDirect, largeDirect, largeResolve, small720),
            rankBatchCandidates(
                listOf(small720, largeResolve, smallDirect, huge2160, largeDirect),
                BatchQuality.BestAvailable,
            ),
        )
    }

    @Test
    fun the_resolution_is_read_from_the_file_name_when_the_label_does_not_say() {
        val byFileName = StreamItem(
            name = "Fixture", url = "http://x/v.mkv", addonName = "A", addonId = "addon:A",
            behaviorHints = StreamBehaviorHints(filename = "Show.S01E01.1080p.WEB-DL.mkv"),
        )
        assertEquals(listOf(byFileName), rankBatchCandidates(listOf(byFileName), BatchQuality.P1080))
        assertEquals(emptyList(), rankBatchCandidates(listOf(byFileName), BatchQuality.P720))
    }

    @Test
    fun the_summary_lists_only_outcomes_that_happened_with_failures_last() {
        fun summary(vararg results: SeasonBatchEpisodeResult) = SeasonBatchSummary(
            results = results.mapIndexed { index, result ->
                SeasonBatchEpisode("tt1:1:${index + 1}", 1, index + 1, null, null) to result
            }.toMap(),
            wasCancelled = false,
        )
        assertEquals(
            listOf(
                SeasonBatchEpisodeResult.Completed to 2,
                SeasonBatchEpisodeResult.NoMatchingSource to 1,
                SeasonBatchEpisodeResult.Failed to 1,
            ),
            summary(
                SeasonBatchEpisodeResult.Failed,
                SeasonBatchEpisodeResult.Completed,
                SeasonBatchEpisodeResult.NoMatchingSource,
                SeasonBatchEpisodeResult.Completed,
                SeasonBatchEpisodeResult.Cancelled,
            ).nonZeroCounts(),
        )
        assertEquals(
            listOf(SeasonBatchEpisodeResult.AlreadyDownloaded to 1, SeasonBatchEpisodeResult.Paused to 1),
            summary(SeasonBatchEpisodeResult.Paused, SeasonBatchEpisodeResult.AlreadyDownloaded).nonZeroCounts(),
        )
        assertEquals(emptyList(), summary(SeasonBatchEpisodeResult.Cancelled).nonZeroCounts())
    }

    @Test
    fun ranking_drops_streaming_manifests_and_puts_ready_direct_urls_before_ones_that_need_resolving() {
        val magnet = stream("1080p torrent", null, magnet = true)
        val hls = stream("1080p hls", "http://x/master.m3u8")
        val direct = stream("1080p direct", "https://x/v.mp4")
        val needsResolve = StreamItem(
            name = "1080p debrid", addonName = "D", addonId = "addon:D",
            clientResolve = StreamClientResolve(type = "debrid", service = "realdebrid", isCached = true),
        )
        val ranked = rankBatchCandidates(listOf(magnet, hls, needsResolve, direct), BatchQuality.BestAvailable)
        // The magnet only survives ranking because a configured debrid service could resolve it to a link;
        // the production prepare step drops it when none is configured.
        assertEquals(listOf(direct, magnet, needsResolve), ranked)
    }

    @Test
    fun season_episodes_skip_unaired_episodes_and_episodes_without_numbers() {
        val meta = MetaDetails(
            id = "tt1", type = "series", name = "Show",
            videos = listOf(
                MetaVideo(id = "tt1:1:1", title = "Aired", released = "2020-01-01T00:00:00Z", season = 1, episode = 1),
                MetaVideo(id = "tt1:1:2", title = "Future", released = "2999-01-01T00:00:00Z", season = 1, episode = 2),
                MetaVideo(id = "tt1:2:1", title = "Other season", released = "2020-01-01T00:00:00Z", season = 2, episode = 1),
            ),
        )
        val result = meta.seasonBatchEpisodes(seasonNumber = 1, todayIsoDate = "2026-11-09")
        assertEquals(listOf("tt1:1:1"), result.map { it.videoId })
    }

    // ---- orchestration ----

    @Test
    fun never_more_than_the_concurrency_limit_is_active_and_every_episode_finishes() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(fake, onFinished = { _, s -> summary = s })
        assertEquals(SeasonBatchStart.Started, d.start(request()))
        advanceUntilIdle()
        assertEquals(listOf(1, 2), fake.downloading())
        // finish one at a time; the next queued episode starts only when a slot frees up
        for (ep in 1..5) {
            fake.setStatus(ep, DownloadStatus.Completed)
            advanceUntilIdle()
            assertTrue(fake.downloading().size <= 2)
        }
        assertEquals(2, fake.maxActive)
        assertEquals(5, fake.enqueued.size)
        assertEquals(5, assertNotNull(summary).count(SeasonBatchEpisodeResult.Completed))
        assertTrue(d.running.value.isEmpty())
    }

    @Test
    fun episodes_already_downloaded_in_progress_or_paused_are_skipped_without_resolving() = runTest {
        val fake = Fake(
            listOf(
                item(1, DownloadStatus.Completed),
                item(2, DownloadStatus.Downloading),
                item(3, DownloadStatus.Paused),
            ),
        )
        val fetched = mutableListOf<Int>()
        var summary: SeasonBatchSummary? = null
        val d = downloader(
            fake,
            fetch = { ep -> fetched += ep.episodeNumber; listOf(stream("1080p", "http://x/${ep.episodeNumber}.mp4")) },
            onFinished = { _, s -> summary = s },
            max = 5,
        )
        d.start(request())
        advanceUntilIdle()
        fake.setStatus(4, DownloadStatus.Completed)
        fake.setStatus(5, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(listOf(4, 5), fetched)
        assertEquals(3, assertNotNull(summary).count(SeasonBatchEpisodeResult.AlreadyDownloaded))
        assertEquals(2, summary.count(SeasonBatchEpisodeResult.Completed))
    }

    @Test
    fun a_failed_source_falls_back_to_the_next_matching_source() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val one = request(episodes.take(1))
        val d = downloader(
            fake,
            fetch = { listOf(stream("1080p A", "http://a/1.mp4"), stream("1080p B", "http://b/1.mp4", addon = "B")) },
            onFinished = { _, s -> summary = s },
        )
        d.start(one)
        advanceUntilIdle()
        assertEquals(listOf(1 to "http://a/1.mp4"), fake.enqueued)
        fake.setStatus(1, DownloadStatus.Failed)
        advanceUntilIdle()
        assertEquals(listOf(1 to "http://a/1.mp4", 1 to "http://b/1.mp4"), fake.enqueued)
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(1, assertNotNull(summary).count(SeasonBatchEpisodeResult.Completed))
    }

    @Test
    fun when_every_source_fails_the_episode_is_reported_failed_and_the_rest_carry_on() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(
            fake,
            fetch = { ep -> listOf(stream("1080p", "http://a/${ep.episodeNumber}.mp4")) },
            onFinished = { _, s -> summary = s },
        )
        d.start(request(episodes.take(2)))
        advanceUntilIdle()
        fake.setStatus(1, DownloadStatus.Failed)
        advanceUntilIdle()
        fake.setStatus(2, DownloadStatus.Completed)
        advanceUntilIdle()
        val s = assertNotNull(summary)
        assertEquals(1, s.count(SeasonBatchEpisodeResult.Failed))
        assertEquals(1, s.count(SeasonBatchEpisodeResult.Completed))
    }

    @Test
    fun no_sources_or_only_unsupported_sources_means_no_matching_source_and_nothing_is_enqueued() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(
            fake,
            fetch = { ep -> if (ep.episodeNumber == 1) emptyList() else listOf(stream("1080p", null, magnet = true)) },
            onFinished = { _, s -> summary = s },
        )
        d.start(request(episodes.take(2)))
        advanceUntilIdle()
        assertTrue(fake.enqueued.isEmpty())
        assertEquals(2, assertNotNull(summary).count(SeasonBatchEpisodeResult.NoMatchingSource))
    }

    @Test
    fun a_fetch_error_fails_only_that_episode() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(
            fake,
            fetch = { ep -> if (ep.episodeNumber == 1) error("addon down") else listOf(stream("1080p", "http://a/2.mp4")) },
            onFinished = { _, s -> summary = s },
        )
        d.start(request(episodes.take(2)))
        advanceUntilIdle()
        fake.setStatus(2, DownloadStatus.Completed)
        advanceUntilIdle()
        val s = assertNotNull(summary)
        assertEquals(SeasonBatchEpisodeResult.Failed, s.results.getValue(episodes[0]))
        assertEquals(SeasonBatchEpisodeResult.Completed, s.results.getValue(episodes[1]))
    }

    @Test
    fun cancelling_while_sources_are_still_resolving_enqueues_nothing() = runTest {
        val fake = Fake()
        val gate = CompletableDeferred<Unit>()
        var summary: SeasonBatchSummary? = null
        val d = downloader(
            fake,
            fetch = { gate.await(); listOf(stream("1080p", "http://a/1.mp4")) },
            onFinished = { _, s -> summary = s },
        )
        d.start(request())
        advanceUntilIdle()
        assertTrue(d.running.value.isNotEmpty())
        d.cancel("tt1", 1)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(fake.enqueued.isEmpty())
        assertTrue(d.running.value.isEmpty())
        assertTrue(assertNotNull(summary).wasCancelled)
    }

    @Test
    fun removing_a_download_from_the_downloads_screen_cancels_that_episode_without_fallback() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(
            fake,
            fetch = { listOf(stream("1080p A", "http://a/1.mp4"), stream("1080p B", "http://b/1.mp4")) },
            onFinished = { _, s -> summary = s },
        )
        d.start(request(episodes.take(1)))
        advanceUntilIdle()
        fake.setStatus(1, null)
        advanceUntilIdle()
        assertEquals(1, fake.enqueued.size)
        assertEquals(1, assertNotNull(summary).count(SeasonBatchEpisodeResult.Cancelled))
    }

    @Test
    fun a_second_start_for_the_same_season_is_rejected_while_the_first_is_running() = runTest {
        val fake = Fake()
        val d = downloader(fake)
        assertEquals(SeasonBatchStart.Started, d.start(request()))
        advanceUntilIdle()
        assertEquals(SeasonBatchStart.AlreadyRunning, d.start(request()))
    }

    @Test
    fun a_season_without_episodes_is_not_started() = runTest {
        val d = downloader(Fake())
        assertEquals(SeasonBatchStart.NothingToDownload, d.start(request(emptyList())))
        assertTrue(d.running.value.isEmpty())
    }

    @Test
    fun a_finished_season_can_be_started_again() = runTest {
        val fake = Fake()
        val d = downloader(fake)
        assertEquals(SeasonBatchStart.Started, d.start(request(episodes.take(1))))
        advanceUntilIdle()
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        assertTrue(d.running.value.isEmpty())
        assertEquals(SeasonBatchStart.Started, d.start(request(episodes.take(1))))
    }

    @Test
    fun start_and_stop_from_several_threads_run_one_batch_and_leave_nothing_behind() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        val summaries = AtomicInteger(0)
        val d = SeasonBatchDownloader(
            scope = scope,
            downloads = MutableStateFlow(DownloadsUiState()),
            fetchStreams = { gate.await(); emptyList() },
            prepareStream = { s, _ -> s },
            enqueue = { _, _, _ -> DownloadEnqueueResult.MissingUrl },
            onFinished = { _, _ -> summaries.incrementAndGet() },
        )
        try {
            val results = ConcurrentLinkedQueue<SeasonBatchStart>()
            runOnThreads(8) { results += d.start(request()) }
            assertEquals(1, results.count { it == SeasonBatchStart.Started })
            assertEquals(7, results.count { it == SeasonBatchStart.AlreadyRunning })

            runOnThreads(8) { d.cancel("tt1", 1) }
            runBlocking {
                withTimeout(60_000) { scope.coroutineContext[Job]!!.children.forEach { it.join() } }
            }
            assertTrue(d.running.value.isEmpty())
            assertEquals(1, summaries.get())
            assertEquals(SeasonBatchStart.Started, d.start(request()))
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    private fun runOnThreads(count: Int, block: () -> Unit) {
        val barrier = CyclicBarrier(count)
        val done = CountDownLatch(count)
        val failure = AtomicReference<Throwable?>(null)
        val workers = (1..count).map {
            thread(isDaemon = true) {
                try {
                    barrier.await(60, TimeUnit.SECONDS)
                    block()
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                } finally {
                    done.countDown()
                }
            }
        }
        val finished = done.await(60, TimeUnit.SECONDS)
        if (!finished) {
            workers.forEach { it.interrupt() }
            workers.forEach { it.join(10_000) }
        }
        assertTrue(finished, "worker threads did not finish")
        failure.get()?.let { throw AssertionError("a worker thread failed", it) }
    }

    @Test
    fun the_quality_choice_is_applied_to_every_episode() = runTest {
        val fake = Fake()
        val d = downloader(
            fake,
            fetch = { ep ->
                listOf(
                    stream("720p", "http://a/${ep.episodeNumber}-720.mp4"),
                    stream("1080p", "http://a/${ep.episodeNumber}-1080.mp4"),
                )
            },
        )
        d.start(request(episodes.take(2), BatchQuality.P1080))
        advanceUntilIdle()
        assertEquals(listOf(1 to "http://a/1-1080.mp4", 2 to "http://a/2-1080.mp4"), fake.enqueued)
    }

    @Test
    fun two_seasons_running_together_share_one_concurrency_limit() = runTest {
        val fake = Fake()
        val d = downloader(fake)
        fun downloading(parent: String) =
            fake.state.value.items.count { it.parentMetaId == parent && it.status == DownloadStatus.Downloading }
        val seasonOne = request(episodes.take(2))
        val seasonTwo = request(episodes.take(2)).copy(parentMetaId = "tt2")

        assertEquals(SeasonBatchStart.Started, d.start(seasonOne))
        assertEquals(SeasonBatchStart.Started, d.start(seasonTwo))
        advanceUntilIdle()
        // The first season holds both slots, so the second one has to wait for them.
        assertEquals(2, downloading("tt1"))
        assertEquals(0, downloading("tt2"))

        fake.setStatus(1, DownloadStatus.Completed, parent = "tt1")
        advanceUntilIdle()
        assertEquals(1, downloading("tt1"))
        assertEquals(1, downloading("tt2"))

        fake.setStatus(2, DownloadStatus.Completed, parent = "tt1")
        advanceUntilIdle()
        assertEquals(0, downloading("tt1"))
        assertEquals(2, downloading("tt2"))
        assertEquals(2, fake.maxActive)
    }

    @Test
    fun stopping_right_after_a_source_was_prepared_queues_nothing() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        lateinit var d: SeasonBatchDownloader
        d = downloader(
            fake,
            prepare = { stream, _ ->
                d.cancel("tt1", 1)
                stream
            },
            onFinished = { _, s -> summary = s },
        )

        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        assertTrue(fake.enqueued.isEmpty())
        assertTrue(assertNotNull(summary).wasCancelled)
    }

    @Test
    fun stopping_before_the_batch_has_done_anything_still_reports_once() = runTest {
        val fake = Fake()
        val summaries = mutableListOf<SeasonBatchSummary>()
        val d = downloader(fake, onFinished = { _, s -> summaries += s })

        assertEquals(SeasonBatchStart.Started, d.start(request()))
        d.cancel("tt1", 1)
        advanceUntilIdle()

        assertEquals(1, summaries.size)
        assertTrue(summaries.single().wasCancelled)
        assertEquals(5, summaries.single().count(SeasonBatchEpisodeResult.Cancelled))
        assertTrue(fake.enqueued.isEmpty())
        assertTrue(d.running.value.isEmpty())
    }

    @Test
    fun no_more_than_the_source_limit_is_looked_up_when_sources_cannot_be_prepared() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val prepared = mutableListOf<String?>()
        val d = downloader(
            fake,
            fetch = { (1..50).map { stream("1080p source $it", "http://a/$it.mp4") } },
            fallback = { (51..100).map { stream("1080p source $it", "http://a/$it.mp4") } },
            prepare = { stream, _ ->
                prepared += stream.name
                if (prepared.size == 2) error("lookup failed") else null
            },
            onFinished = { _, s -> summary = s },
        )

        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        assertEquals(listOf<String?>("1080p source 1", "1080p source 2", "1080p source 3"), prepared)
        assertTrue(fake.enqueued.isEmpty())
        assertEquals(1, assertNotNull(summary).count(SeasonBatchEpisodeResult.Failed))
    }

    @Test
    fun sources_that_cannot_be_used_are_reported_as_not_found_but_a_lookup_error_as_failed() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(
            fake,
            fetch = { ep -> listOf(stream("1080p ${ep.episodeNumber}", "http://a/${ep.episodeNumber}.mp4")) },
            prepare = { _, ep -> if (ep.episodeNumber == 1) null else error("resolver unavailable") },
            onFinished = { _, s -> summary = s },
        )

        d.start(request(episodes.take(2)))
        advanceUntilIdle()

        val s = assertNotNull(summary)
        assertEquals(SeasonBatchEpisodeResult.NoMatchingSource, s.results.getValue(episodes[0]))
        assertEquals(SeasonBatchEpisodeResult.Failed, s.results.getValue(episodes[1]))
    }

    @Test
    fun an_error_while_queueing_fails_that_source_and_the_season_carries_on() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val attempts = mutableListOf<String?>()
        val d = downloader(
            fake,
            fetch = { ep ->
                listOf(
                    stream("1080p A", "http://a/${ep.episodeNumber}.mp4"),
                    stream("1080p B", "http://b/${ep.episodeNumber}.mp4", addon = "B"),
                )
            },
            enqueue = { request, ep, stream ->
                if (ep.episodeNumber == 1) {
                    attempts += stream.url
                    error("storage unavailable")
                }
                fake.enqueue(request, ep, stream)
            },
            onFinished = { _, s -> summary = s },
        )

        d.start(request(episodes.take(2)))
        advanceUntilIdle()
        fake.setStatus(2, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(listOf<String?>("http://a/1.mp4", "http://b/1.mp4"), attempts)
        val s = assertNotNull(summary)
        assertFalse(s.wasCancelled)
        assertEquals(SeasonBatchEpisodeResult.Failed, s.results.getValue(episodes[0]))
        assertEquals(SeasonBatchEpisodeResult.Completed, s.results.getValue(episodes[1]))
    }

    @Test
    fun a_stop_that_lands_just_before_an_episode_is_queued_still_prevents_it() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        var reads = 0
        lateinit var d: SeasonBatchDownloader
        // The downloader reads the downloads when the episode gets a slot, when it starts on the
        // episode, and a last time when it is about to queue it. The stop is made on that last read.
        val downloads = ObservedDownloads(fake.state) {
            reads++
            if (reads == 3) d.cancel("tt1", 1)
        }
        d = downloader(fake, downloads = downloads, onFinished = { _, s -> summary = s })

        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        assertTrue(reads >= 3, "the stop was never made")
        assertTrue(fake.enqueued.isEmpty())
        assertTrue(assertNotNull(summary).wasCancelled)
    }

    @Test
    fun nothing_is_queued_after_stop_has_returned_however_the_threads_interleave() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val queuedAfterStop = AtomicInteger(0)
        val queued = AtomicInteger(0)
        try {
            repeat(300) { round ->
                val stopReturned = AtomicBoolean(false)
                val d = SeasonBatchDownloader(
                    scope = scope,
                    downloads = MutableStateFlow(DownloadsUiState()),
                    fetchStreams = { ep -> (1..3).map { stream("1080p $it", "http://a/${ep.episodeNumber}-$it.mp4") } },
                    prepareStream = { s, _ -> s },
                    enqueue = { _, _, _ ->
                        queued.incrementAndGet()
                        if (stopReturned.get()) queuedAfterStop.incrementAndGet()
                        DownloadEnqueueResult.MissingUrl
                    },
                    maxConcurrent = 4,
                )
                assertEquals(SeasonBatchStart.Started, d.start(request()))
                // Let a different amount of the season run each round before stopping it.
                repeat(round % 7) { Thread.yield() }
                runOnThreads(2) { d.cancel("tt1", 1) }
                stopReturned.set(true)
                runBlocking {
                    withTimeout(60_000) { scope.coroutineContext[Job]!!.children.forEach { it.join() } }
                }
            }
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        assertEquals(0, queuedAfterStop.get(), "episodes were queued after stop returned (${queued.get()} queued in all)")
    }

    @Test
    fun a_pause_is_noticed_by_an_episode_that_is_about_to_be_queued_before_anyone_else_reacted_to_it() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val gate = CompletableDeferred<Unit>()
        val d = downloader(
            fake,
            fetch = { ep ->
                if (ep.episodeNumber == 2) gate.await()
                listOf(stream("1080p", "http://a/${ep.episodeNumber}.mp4"))
            },
            onFinished = { _, s -> summary = s },
        )
        d.start(request(episodes.take(2)))
        advanceUntilIdle()

        // Episode 2 is released first, so it reaches the queueing step before the coroutine that
        // waits on episode 1 has seen the pause.
        gate.complete(Unit)
        fake.setStatus(1, DownloadStatus.Paused)
        advanceUntilIdle()

        assertEquals(listOf(1 to "http://a/1.mp4"), fake.enqueued)
        assertEquals(SeasonBatchEpisodeResult.Paused, assertNotNull(summary).results.getValue(episodes[1]))
    }

    @Test
    fun once_a_pause_was_seen_resuming_that_download_does_not_restart_the_season() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(fake, onFinished = { _, s -> summary = s }, max = 1)
        d.start(request(episodes.take(3)))
        advanceUntilIdle()

        fake.setStatus(1, DownloadStatus.Paused)
        advanceUntilIdle()
        fake.setStatus(1, DownloadStatus.Downloading)
        advanceUntilIdle()

        assertEquals(listOf(1 to "http://a/1.mp4").map { it.first }, fake.enqueued.map { it.first })
        assertEquals(3, assertNotNull(summary).count(SeasonBatchEpisodeResult.Paused))
    }

    @Test
    fun an_episode_still_looking_for_sources_is_not_queued_after_a_pause() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val gate = CompletableDeferred<Unit>()
        val d = downloader(
            fake,
            fetch = { ep ->
                if (ep.episodeNumber == 2) gate.await()
                listOf(stream("1080p", "http://a/${ep.episodeNumber}.mp4"))
            },
            onFinished = { _, s -> summary = s },
        )
        d.start(request(episodes.take(2)))
        advanceUntilIdle()
        assertEquals(listOf(1), fake.downloading())

        fake.setStatus(1, DownloadStatus.Paused)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1 to "http://a/1.mp4"), fake.enqueued)
        val s = assertNotNull(summary)
        assertEquals(SeasonBatchEpisodeResult.Paused, s.results.getValue(episodes[0]))
        assertEquals(SeasonBatchEpisodeResult.Paused, s.results.getValue(episodes[1]))
    }

    @Test
    fun stopping_a_batch_keeps_its_running_downloads_counted_against_the_limit() = runTest {
        val fake = Fake()
        val d = downloader(fake)
        val seasonTwo = request().copy(parentMetaId = "tt2", seasonNumber = 1)
        assertEquals(SeasonBatchStart.Started, d.start(request()))
        advanceUntilIdle()
        assertEquals(2, fake.state.value.items.count { it.status == DownloadStatus.Downloading })

        // Stop queueing: the two running downloads stay in the Downloads screen and keep their slots.
        d.cancel("tt1", 1)
        advanceUntilIdle()
        assertEquals(SeasonBatchStart.Started, d.start(seasonTwo))
        advanceUntilIdle()
        assertEquals(2, fake.state.value.items.count { it.status == DownloadStatus.Downloading })
        assertEquals(2, fake.maxActive)

        // When one of the stopped batch's downloads finishes, its slot goes to the other season.
        fake.setStatus(1, DownloadStatus.Completed, parent = "tt1")
        advanceUntilIdle()
        assertEquals(2, fake.state.value.items.count { it.status == DownloadStatus.Downloading })
        assertEquals(1, fake.state.value.items.count { it.parentMetaId == "tt2" && it.status == DownloadStatus.Downloading })
        assertEquals(2, fake.maxActive)
    }

    @Test
    fun a_paused_download_releases_its_slot_without_failing_or_trying_another_source() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(
            fake,
            fetch = { ep -> listOf(stream("1080p A", "http://a/${ep.episodeNumber}.mp4"), stream("1080p B", "http://b/${ep.episodeNumber}.mp4", addon = "B")) },
            onFinished = { _, s -> summary = s },
        )
        d.start(request(episodes.take(3)))
        advanceUntilIdle()
        assertEquals(listOf(1, 2), fake.downloading())
        fake.setStatus(1, DownloadStatus.Paused)
        advanceUntilIdle()
        // episode 1 is not retried from the second source, and episode 3 is not started behind a pause
        assertEquals(listOf(1 to "http://a/1.mp4", 2 to "http://a/2.mp4"), fake.enqueued)
        fake.setStatus(2, DownloadStatus.Completed)
        advanceUntilIdle()
        val s = assertNotNull(summary)
        assertEquals(SeasonBatchEpisodeResult.Paused, s.results.getValue(episodes[0]))
        assertEquals(SeasonBatchEpisodeResult.Completed, s.results.getValue(episodes[1]))
        assertEquals(SeasonBatchEpisodeResult.Paused, s.results.getValue(episodes[2]))
        assertEquals(0, s.count(SeasonBatchEpisodeResult.Failed))
        assertTrue(d.running.value.isEmpty())
    }

    @Test
    fun sources_with_the_same_url_but_different_required_headers_are_both_kept() {
        fun withHeaders(url: String, headers: Map<String, String>?) = StreamItem(
            name = "1080p", url = url, addonName = "A", addonId = "addon:A",
            behaviorHints = StreamBehaviorHints(proxyHeaders = headers?.let { StreamProxyHeaders(request = it) }),
        )
        val plain = withHeaders("http://x/v.mp4", null)
        val referer = withHeaders("http://x/v.mp4", mapOf("Referer" to "http://a/"))
        val otherReferer = withHeaders("http://x/v.mp4", mapOf("Referer" to "http://b/"))
        val sameRefererAgain = withHeaders("http://x/v.mp4", mapOf("referer" to "http://a/"))
        assertEquals(
            listOf(plain, referer, otherReferer),
            rankBatchCandidates(listOf(plain, referer, otherReferer, sameRefererAgain, plain), BatchQuality.BestAvailable),
        )
    }

    @Test
    fun header_values_that_contain_separators_are_not_mistaken_for_several_headers() {
        fun withHeaders(headers: Map<String, String>) = StreamItem(
            name = "1080p", url = "http://x/v.mp4", addonName = "A", addonId = "addon:A",
            behaviorHints = StreamBehaviorHints(proxyHeaders = StreamProxyHeaders(request = headers)),
        )
        val oneHeader = withHeaders(mapOf("X" to "a&y=b"))
        val twoHeaders = withHeaders(mapOf("X" to "a", "Y" to "b"))
        assertEquals(
            listOf(oneHeader, twoHeaders),
            rankBatchCandidates(listOf(oneHeader, twoHeaders), BatchQuality.BestAvailable),
        )
    }

    @Test
    fun the_fallback_lookup_is_used_when_the_first_lookup_has_nothing_at_the_chosen_quality() = runTest {
        val fake = Fake()
        val d = downloader(
            fake,
            fetch = { listOf(stream("720p embedded", "http://e/1-720.mp4")) },
            fallback = { listOf(stream("1080p addon", "http://a/1-1080.mp4")) },
        )
        d.start(request(episodes.take(1), BatchQuality.P1080))
        advanceUntilIdle()
        assertEquals(listOf(1 to "http://a/1-1080.mp4"), fake.enqueued)
    }

    @Test
    fun the_fallback_lookup_is_not_asked_when_the_first_lookup_succeeds() = runTest {
        val fake = Fake()
        var asked = false
        val d = downloader(fake, fallback = { asked = true; emptyList() })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        assertFalse(asked)
    }

    @Test
    fun a_failed_embedded_source_is_not_downloaded_again_from_the_fallback_lookup() = runTest {
        val fake = Fake()
        val repeated = stream("1080p b", "http://x/b.mp4")
        val next = stream("1080p c", "http://x/c.mp4")
        val d = downloader(
            fake,
            fetch = { listOf(repeated) },
            fallback = { listOf(repeated, next) },
        )
        d.start(request(episodes.take(1)))
        advanceUntilIdle()
        fake.finishedBytes = 73_615
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(listOf(1 to "http://x/b.mp4", 1 to "http://x/c.mp4"), fake.enqueued)
        fake.finishedBytes = 700_000_000
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        assertTrue(d.running.value.isEmpty())
    }

    @Test
    fun different_listings_that_resolve_to_the_same_address_are_only_downloaded_once() = runTest {
        val fake = Fake()
        val first = stream("1080p a", "http://x/a.mp4")
        val alias = stream("1080p b", "http://x/b.mp4")
        val next = stream("1080p c", "http://x/c.mp4")
        val d = downloader(
            fake,
            fetch = { listOf(first, alias, next) },
            prepare = { candidate, _ ->
                if (candidate == next) candidate else candidate.copy(url = "http://x/resolved.mp4")
            },
        )
        d.start(request(episodes.take(1)))
        advanceUntilIdle()
        fake.setStatus(1, DownloadStatus.Failed)
        advanceUntilIdle()

        assertEquals(listOf(1 to "http://x/resolved.mp4", 1 to "http://x/c.mp4"), fake.enqueued)
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        assertTrue(d.running.value.isEmpty())
    }

    // ---- placeholder clips ----

    private fun threeSources() = listOf(stream("1080p a", "http://x/a.mp4"), stream("1080p b", "http://x/b.mp4"), stream("1080p c", "http://x/c.mp4"))

    @Test
    fun a_download_that_arrives_as_a_tiny_clip_is_a_failed_source_and_the_next_source_is_taken() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(fake, fetch = { threeSources() }, onFinished = { _, s -> summary = s })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        fake.finishedBytes = 73_615
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(listOf("http://x/a.mp4"), fake.discarded)
        assertEquals(listOf(1 to "http://x/a.mp4", 1 to "http://x/b.mp4"), fake.enqueued)
        fake.finishedBytes = 700_000_000
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(SeasonBatchEpisodeResult.Completed, summary?.results?.values?.single())
        assertEquals(1, fake.state.value.items.size)
    }

    @Test
    fun an_episode_whose_every_source_arrives_as_a_tiny_clip_is_failed_and_no_clip_is_kept() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(fake, fetch = { threeSources() + stream("1080p d", "http://x/d.mp4") }, onFinished = { _, s -> summary = s })
        d.start(request(episodes.take(1)))
        fake.finishedBytes = 421_667
        repeat(3) {
            advanceUntilIdle()
            fake.setStatus(1, DownloadStatus.Completed)
        }
        advanceUntilIdle()

        assertEquals(listOf("http://x/a.mp4", "http://x/b.mp4", "http://x/c.mp4"), fake.discarded)
        assertEquals(SeasonBatchEpisodeResult.Failed, summary?.results?.values?.single())
        assertEquals(emptyList(), fake.state.value.items)
    }

    @Test
    fun a_source_that_announces_a_tiny_size_itself_is_not_downloaded() = runTest {
        val fake = Fake()
        val tiny = stream("1080p tiny", "http://x/tiny.mp4").copy(behaviorHints = StreamBehaviorHints(videoSize = 73_615))
        val d = downloader(fake, fetch = { listOf(tiny, stream("1080p real", "http://x/real.mp4")) })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        assertEquals(listOf(1 to "http://x/real.mp4"), fake.enqueued)
        assertEquals(emptyList(), fake.discarded)
    }

    @Test
    fun an_episode_whose_only_source_announces_a_tiny_size_is_not_found() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val tiny = stream("1080p tiny", "http://x/tiny.mp4").copy(behaviorHints = StreamBehaviorHints(videoSize = 421_667))
        val d = downloader(fake, fetch = { listOf(tiny) }, onFinished = { _, s -> summary = s })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        assertEquals(emptyList(), fake.enqueued)
        assertEquals(SeasonBatchEpisodeResult.NoMatchingSource, summary?.results?.values?.single())
    }

    @Test
    fun a_transfer_whose_server_announces_a_tiny_length_is_thrown_away_at_once_and_the_next_source_is_taken() = runTest {
        val fake = Fake()
        val d = downloader(fake, fetch = { threeSources() })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        fake.announce(1, totalBytes = 73_615)
        advanceUntilIdle()

        assertEquals(listOf("http://x/a.mp4"), fake.discarded)
        assertEquals(listOf(1 to "http://x/a.mp4", 1 to "http://x/b.mp4"), fake.enqueued)
        assertEquals(listOf(1), fake.downloading())
    }

    @Test
    fun a_server_that_announces_a_plausible_length_is_left_to_finish() = runTest {
        val fake = Fake()
        val d = downloader(fake, fetch = { threeSources() })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        fake.announce(1, totalBytes = MinPlausibleVideoBytes)
        advanceUntilIdle()

        assertEquals(emptyList(), fake.discarded)
        assertEquals(1, fake.enqueued.size)
    }

    @Test
    fun a_small_file_that_is_still_a_plausible_video_is_kept() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(fake, fetch = { threeSources() }, onFinished = { _, s -> summary = s })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        fake.finishedBytes = MinPlausibleVideoBytes
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(emptyList(), fake.discarded)
        assertEquals(SeasonBatchEpisodeResult.Completed, summary?.results?.values?.single())
    }

    @Test
    fun a_file_far_too_small_for_the_runtime_of_its_episode_is_a_failed_source() = runTest {
        val fake = Fake()
        val d = downloader(fake, fetch = { threeSources() })
        d.start(request(listOf(SeasonBatchEpisode("tt1:1:1", 1, 1, "Ep 1", null, runtimeMinutes = 45))))
        advanceUntilIdle()

        fake.finishedBytes = 5_000_000
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(listOf("http://x/a.mp4"), fake.discarded)
        assertEquals(2, fake.enqueued.size)
    }

    @Test
    fun a_short_item_may_be_smaller_than_two_megabytes() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(fake, fetch = { threeSources() }, onFinished = { _, s -> summary = s })
        d.start(request(listOf(SeasonBatchEpisode("tt1:1:1", 1, 1, "Ep 1", null, runtimeMinutes = 2))))
        advanceUntilIdle()

        fake.finishedBytes = 1_000_000
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(emptyList(), fake.discarded)
        assertEquals(SeasonBatchEpisodeResult.Completed, summary?.results?.values?.single())
    }

    private fun clip(durationSeconds: Long?) =
        DownloadedFileFacts(exists = true, sizeBytes = 40_000_000, isVideoContainer = true, durationMs = durationSeconds?.let { it * 1_000 })

    private suspend fun kotlinx.coroutines.test.TestScope.finishWith(durationSeconds: Long?, runtimeMinutes: Int?): Fake {
        val fake = Fake()
        val d = downloader(fake, fetch = { threeSources() }, inspect = { clip(durationSeconds) })
        d.start(request(listOf(SeasonBatchEpisode("tt1:1:1", 1, 1, "Ep 1", null, runtimeMinutes = runtimeMinutes))))
        advanceUntilIdle()
        fake.finishedBytes = 40_000_000
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        return fake
    }

    @Test
    fun a_valid_video_that_lasts_a_few_seconds_in_place_of_a_long_episode_is_a_failed_source() = runTest {
        val fake = finishWith(durationSeconds = 30, runtimeMinutes = 45)

        assertEquals(listOf("http://x/a.mp4"), fake.discarded)
        assertEquals(2, fake.enqueued.size)
    }

    @Test
    fun a_video_that_is_shorter_than_its_stated_runtime_but_not_a_fraction_of_it_is_kept() = runTest {
        val fake = finishWith(durationSeconds = 6 * 60, runtimeMinutes = 45)

        assertEquals(emptyList(), fake.discarded)
        assertEquals(1, fake.enqueued.size)
    }

    @Test
    fun a_video_is_kept_when_its_duration_or_the_runtime_is_not_known() = runTest {
        assertEquals(emptyList(), finishWith(durationSeconds = null, runtimeMinutes = 45).discarded)
        assertEquals(emptyList(), finishWith(durationSeconds = 5, runtimeMinutes = null).discarded)
    }

    private val preferredAudio = mutableListOf<Pair<String, String>>()

    /** Downloads episode 1 with [chosen] as the language; each finished file has [audioLanguages]. */
    private suspend fun kotlinx.coroutines.test.TestScope.finishWithAudio(audioLanguages: List<String>?, chosen: DebridStreamLanguage?): Fake {
        val fake = Fake()
        val d = downloader(
            fake,
            fetch = { threeSources() },
            inspect = { clip(durationSeconds = null).copy(audioLanguages = audioLanguages) },
            preferAudioLanguage = { item, language -> preferredAudio += item.sourceUrl to language },
        )
        d.start(request(episodes.take(1)).copy(audioLanguage = chosen))
        advanceUntilIdle()
        fake.finishedBytes = 40_000_000
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        return fake
    }

    @Test
    fun a_file_with_audio_only_in_another_language_than_the_chosen_one_is_a_failed_source() = runTest {
        val fake = finishWithAudio(listOf("rus"), chosen = DebridStreamLanguage.EN)

        assertEquals(listOf("http://x/a.mp4"), fake.discarded)
        assertEquals(2, fake.enqueued.size)
        assertEquals(emptyList(), preferredAudio)
    }

    @Test
    fun a_file_with_the_chosen_language_on_its_second_audio_track_is_kept_and_starts_on_that_language() = runTest {
        val fake = finishWithAudio(listOf("lit", "eng"), chosen = DebridStreamLanguage.EN)

        assertEquals(emptyList(), fake.discarded)
        assertEquals(1, fake.enqueued.size)
        assertEquals(listOf("http://x/a.mp4" to "en"), preferredAudio)
    }

    @Test
    fun a_file_whose_first_audio_track_is_in_the_chosen_language_is_kept_as_it_is() = runTest {
        val fake = finishWithAudio(listOf("eng", "lit"), chosen = DebridStreamLanguage.EN)

        assertEquals(emptyList(), fake.discarded)
        assertEquals(emptyList(), preferredAudio)
    }

    @Test
    fun a_file_is_kept_when_its_audio_tracks_cannot_be_read_or_carry_no_language() = runTest {
        assertEquals(emptyList(), finishWithAudio(null, chosen = DebridStreamLanguage.EN).discarded)
        assertEquals(emptyList(), finishWithAudio(listOf(""), chosen = DebridStreamLanguage.EN).discarded)
    }

    @Test
    fun the_audio_tracks_of_a_file_are_not_held_against_it_when_no_language_was_chosen() = runTest {
        assertEquals(emptyList(), finishWithAudio(listOf("rus"), chosen = null).discarded)
    }

    @Test
    fun a_finished_download_that_is_a_short_clip_on_disk_does_not_count_as_downloaded() = runTest {
        val existing = item(1, DownloadStatus.Completed)
        val fake = Fake(listOf(existing))
        val d = downloader(fake, inspect = { clip(durationSeconds = 30) })
        d.start(request(listOf(SeasonBatchEpisode("tt1:1:1", 1, 1, "Ep 1", null, runtimeMinutes = 45))))
        advanceUntilIdle()

        assertEquals(listOf(1), fake.enqueued.map { it.first })
    }

    @Test
    fun a_finished_download_whose_file_has_shrunk_on_disk_does_not_count_as_downloaded() = runTest {
        val fake = Fake(listOf(item(1, DownloadStatus.Completed)))
        val d = downloader(fake, inspect = { DownloadedFileFacts(exists = true, sizeBytes = 73_615, isVideoContainer = true) })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        assertEquals(listOf(1), fake.enqueued.map { it.first })
    }

    @Test
    fun a_finished_download_that_is_a_real_video_on_disk_counts_as_downloaded() = runTest {
        val fake = Fake(listOf(item(1, DownloadStatus.Completed)))
        val d = downloader(fake, inspect = { clip(durationSeconds = 44 * 60) })
        d.start(request(listOf(SeasonBatchEpisode("tt1:1:1", 1, 1, "Ep 1", null, runtimeMinutes = 45))))
        advanceUntilIdle()

        assertEquals(emptyList(), fake.enqueued)
    }

    @Test
    fun a_duration_counts_as_a_placeholder_below_a_tenth_of_the_runtime() {
        assertEquals(true, isFarShorterThanRuntime(durationMs = 269_999, runtimeMinutes = 45))
        assertEquals(false, isFarShorterThanRuntime(durationMs = 270_000, runtimeMinutes = 45))
        assertEquals(false, isFarShorterThanRuntime(durationMs = null, runtimeMinutes = 45))
        assertEquals(false, isFarShorterThanRuntime(durationMs = 1_000, runtimeMinutes = null))
        assertEquals(false, isFarShorterThanRuntime(durationMs = 1_000, runtimeMinutes = 0))
    }

    @Test
    fun the_floor_is_two_megabytes_without_a_runtime_and_follows_the_runtime_when_it_is_known() {
        assertEquals(2_000_000L, minPlausibleVideoBytes())
        assertEquals(2_000_000L, minPlausibleVideoBytes(runtimeMinutes = 0))
        assertEquals(750_000L, minPlausibleVideoBytes(runtimeMinutes = 2))
        assertEquals(16_875_000L, minPlausibleVideoBytes(runtimeMinutes = 45))
        assertEquals(45_000_000L, minPlausibleVideoBytes(runtimeMinutes = 120))
    }

    @Test
    fun a_tiny_file_that_was_already_there_does_not_count_as_downloaded_and_is_fetched_again() = runTest {
        val fake = Fake(listOf(item(1, DownloadStatus.Completed).copy(downloadedBytes = 73_615)))
        var summary: SeasonBatchSummary? = null
        val d = downloader(fake, onFinished = { _, s -> summary = s })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        assertEquals(listOf(1 to "http://x/1.mp4"), fake.enqueued)
        assertEquals(emptyList(), fake.discarded)
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(SeasonBatchEpisodeResult.Completed, summary?.results?.values?.single())
    }

    @Test
    fun a_tiny_file_that_was_already_there_is_left_in_the_list_when_no_source_is_found() = runTest {
        val fake = Fake(listOf(item(1, DownloadStatus.Completed).copy(downloadedBytes = 73_615)))
        val d = downloader(fake, fetch = { emptyList() })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        assertEquals(1, fake.state.value.items.size)
        assertEquals(emptyList(), fake.discarded)
    }

    @Test
    fun a_finished_download_whose_file_is_gone_does_not_count_as_downloaded_and_is_fetched_again() = runTest {
        val fake = Fake(listOf(item(1, DownloadStatus.Completed).copy(downloadedBytes = 700_000_000), item(2, DownloadStatus.Completed).copy(downloadedBytes = 700_000_000)))
        val d = downloader(fake, verifyCompleted = { it.episodeNumber != 1 })
        d.start(request(episodes.take(2)))
        advanceUntilIdle()

        assertEquals(listOf(1 to "http://x/1.mp4"), fake.enqueued)
    }

    @Test
    fun downloads_that_went_on_at_launch_take_slots_until_they_settle() = runTest {
        val resumedA = item(8, DownloadStatus.Downloading).copy(id = "resumed-a")
        val resumedB = item(9, DownloadStatus.Downloading).copy(id = "resumed-b")
        val fake = Fake(listOf(resumedA, resumedB))
        val d = downloader(fake)

        d.adoptRunning(listOf("resumed-a", "resumed-b", "unknown"))
        d.start(request(episodes.take(3)))
        advanceUntilIdle()
        assertEquals(emptyList(), fake.enqueued, "an episode was queued beside two running downloads")

        fake.setStatus(8, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(listOf(1), fake.enqueued.map { it.first })

        fake.setStatus(9, DownloadStatus.Paused)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), fake.enqueued.map { it.first })
        assertEquals(2, fake.maxActive)
    }

    @Test
    fun no_episode_is_queued_while_more_downloads_from_the_last_launch_run_than_there_are_slots() = runTest {
        val resumed = listOf(7, 8, 9).map { item(it, DownloadStatus.Downloading).copy(id = "resumed-$it") }
        val fake = Fake(resumed)
        val d = downloader(fake)

        d.adoptRunning(resumed.map { it.id })
        d.start(request(episodes.take(2)))
        advanceUntilIdle()
        fake.setStatus(7, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(emptyList(), fake.enqueued, "an episode was queued while two earlier downloads were still running")

        fake.setStatus(8, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(listOf(1), fake.enqueued.map { it.first })
    }

    @Test
    fun a_finished_clip_is_judged_by_the_bytes_that_arrived_not_by_the_size_that_was_announced() = runTest {
        val fake = Fake()
        val d = downloader(fake, fetch = { threeSources() })
        d.start(request(episodes.take(1)))
        advanceUntilIdle()

        fake.state.value = DownloadsUiState(
            fake.state.value.items.map { it.copy(status = DownloadStatus.Completed, downloadedBytes = 400_000, totalBytes = 700_000_000) },
        )
        advanceUntilIdle()

        assertEquals(listOf("http://x/a.mp4"), fake.discarded)
    }

    @Test
    fun a_download_that_is_no_longer_running_takes_no_slot() = runTest {
        val fake = Fake(listOf(item(8, DownloadStatus.Paused).copy(id = "paused"), item(9, DownloadStatus.Completed).copy(id = "done", downloadedBytes = 700_000_000)))
        val d = downloader(fake)

        d.adoptRunning(listOf("paused", "done"))
        d.start(request(episodes.take(2)))
        advanceUntilIdle()

        assertEquals(listOf(1, 2), fake.enqueued.map { it.first })
    }

    @Test
    fun a_failed_check_waits_for_a_transfer_slot_before_trying_another_source() = runTest {
        val fake = Fake()
        var summary: SeasonBatchSummary? = null
        val d = downloader(fake, max = 1, fetch = { ep -> listOf(
            stream("1080p", "http://x/${ep.episodeNumber}-first.mp4"),
            stream("720p", "http://x/${ep.episodeNumber}-next.mp4"),
        ) }, onFinished = { _, result -> summary = result })
        d.start(request(episodes.take(2)))
        advanceUntilIdle()
        assertEquals(listOf(1), fake.enqueued.map { it.first })
        val first = fake.state.value.items.single().id
        fake.state.value = fake.state.value.copy(checkingIds = setOf(first))
        advanceUntilIdle()
        assertEquals(listOf(1, 2), fake.enqueued.map { it.first })
        fake.setStatus(1, DownloadStatus.Failed)
        advanceUntilIdle()
        // The second transfer holds the only slot. The failed file cannot start another yet.
        assertEquals(2, fake.enqueued.size)
        fake.setStatus(2, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 1), fake.enqueued.map { it.first })
        assertTrue(fake.enqueued.last().second.endsWith("-next.mp4"))
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(2, summary?.count(SeasonBatchEpisodeResult.Completed))
    }

    @Test
    fun an_adopted_transfer_releases_its_slot_when_its_file_is_being_checked() = runTest {
        val old = item(9, DownloadStatus.Downloading)
        val fake = Fake(listOf(old))
        val d = downloader(fake, max = 1)
        d.adoptRunning(listOf(old.id))
        d.start(request(episodes.take(1)))
        advanceUntilIdle()
        assertEquals(0, fake.enqueued.size)
        fake.state.value = fake.state.value.copy(checkingIds = setOf(old.id))
        advanceUntilIdle()
        assertEquals(listOf(1), fake.enqueued.map { it.first })
        assertEquals(DownloadStatus.Downloading, fake.state.value.items.first { it.id == old.id }.status)
        fake.setStatus(1, DownloadStatus.Completed)
        advanceUntilIdle()
    }

    @Test
    fun a_stopped_batch_hands_over_its_slot_only_until_the_network_transfer_ends() = runTest {
        val fake = Fake()
        val d = downloader(fake, max = 1)
        d.start(request(episodes.take(1)))
        advanceUntilIdle()
        val first = fake.state.value.items.single().id
        d.cancel("tt1", 1)
        advanceUntilIdle()
        d.start(request(episodes.drop(1).take(1)))
        advanceUntilIdle()
        assertEquals(listOf(1), fake.enqueued.map { it.first })
        fake.state.value = fake.state.value.copy(checkingIds = setOf(first))
        advanceUntilIdle()
        assertEquals(listOf(1, 2), fake.enqueued.map { it.first })
        fake.setStatus(2, DownloadStatus.Completed)
        advanceUntilIdle()
    }

}
