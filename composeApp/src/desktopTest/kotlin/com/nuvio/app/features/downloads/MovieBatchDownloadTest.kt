package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MovieBatchDownloadTest {
    private val movie = MetaDetails(id = "tt100", type = "movie", name = "Movie", poster = "poster", runtime = "2h 5m")

    private fun stream(label: String, url: String, sizeBytes: Long? = null) = StreamItem(
        name = label,
        // Alternative releases have different listings even when their quality is the same.
        description = url.substringAfterLast('/'),
        url = url,
        addonName = "A",
        addonId = "addon:A",
        behaviorHints = StreamBehaviorHints(videoSize = sizeBytes),
    )

    /** Downloads in memory: queueing adds a Downloading item and the test then settles it. */
    private class Fake(initial: List<DownloadItem> = emptyList()) {
        val state = MutableStateFlow(DownloadsUiState(initial))
        val queuedUrls = mutableListOf<String>()
        val summaries = mutableListOf<SeasonBatchSummary>()

        fun enqueue(request: SeasonBatchRequest, item: SeasonBatchEpisode, stream: StreamItem): DownloadEnqueueResult {
            val url = stream.playableDirectUrl ?: return DownloadEnqueueResult.MissingUrl
            val movie = item.movie!!
            queuedUrls += url
            state.value = DownloadsUiState(
                state.value.items.filterNot { it.parentMetaId == movie.metaId && !it.isEpisode } + movieItem(movie.metaId, DownloadStatus.Downloading, url),
            )
            return DownloadEnqueueResult.Started
        }

        fun settle(metaId: String, status: DownloadStatus) {
            state.value = DownloadsUiState(
                state.value.items.map {
                    if (it.parentMetaId == metaId && !it.isEpisode) {
                        it.copy(status = status, downloadedBytes = if (status == DownloadStatus.Completed) 2_000_000_000 else 0)
                    } else {
                        it
                    }
                },
            )
        }
    }

    private fun TestScope.downloader(fake: Fake, sources: suspend (SeasonBatchEpisode) -> List<StreamItem>) = SeasonBatchDownloader(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        downloads = fake.state,
        fetchStreams = sources,
        prepareStream = { stream, _ -> stream },
        enqueue = fake::enqueue,
        onFinished = { _, summary -> fake.summaries += summary },
        discard = { item -> fake.state.value = DownloadsUiState(fake.state.value.items.filterNot { it.id == item.id }) },
    )

    private fun Fake.result() = summaries.single().results.values.single()

    @Test
    fun a_movie_request_is_a_batch_of_one_movie_with_the_choices_and_the_runtime_in_minutes() {
        val request = movie.movieBatchRequest(SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN))

        val item = request.episodes.single()
        assertTrue(request.isSingleMovie)
        assertEquals("tt100", request.parentMetaId)
        assertEquals(MovieBatchSeasonNumber, request.seasonNumber)
        assertEquals("tt100", item.videoId)
        assertEquals(125, item.runtimeMinutes)
        assertEquals(SeasonBatchMovie("tt100", "movie", "Movie", poster = "poster"), item.movie)
        assertEquals(BatchQuality.P1080, request.quality)
        assertEquals(10, request.maxBitrateMbps)
        assertEquals(DebridStreamLanguage.EN, request.audioLanguage)
    }

    @Test
    fun a_movie_without_a_readable_runtime_has_none() {
        assertNull(movie.copy(runtime = null).movieBatchRequest(SeasonDownloadDefaults()).episodes.single().runtimeMinutes)
        assertNull(movie.copy(runtime = "soon").movieBatchRequest(SeasonDownloadDefaults()).episodes.single().runtimeMinutes)
    }

    @Test
    fun a_season_request_is_not_a_single_movie() {
        val season = movie.movieBatchRequest(SeasonDownloadDefaults()).copy(episodes = listOf(SeasonBatchEpisode("tt1:1:1", 1, 1, null, null)))

        assertEquals(false, season.isSingleMovie)
    }

    @Test
    fun a_movie_is_queued_from_the_best_matching_source() = runTest {
        val fake = Fake()
        val downloader = downloader(fake) { listOf(stream("720p", "https://x/720.mp4"), stream("1080p", "https://x/1080.mp4")) }

        assertEquals(SeasonBatchStart.Started, downloader.start(movie.movieBatchRequest(SeasonDownloadDefaults())))
        advanceUntilIdle()

        assertEquals(listOf("https://x/1080.mp4"), fake.queuedUrls)
        assertEquals(mapOf(seasonBatchKey("tt100", MovieBatchSeasonNumber) to SeasonBatchProgress(1, 0)), downloader.running.value)
        fake.settle("tt100", DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(SeasonBatchEpisodeResult.Completed, fake.result())
        assertEquals(emptyMap(), downloader.running.value)
    }

    @Test
    fun the_bitrate_limit_uses_the_runtime_of_the_movie() = runTest {
        val fake = Fake()
        // 125 minutes at 10 Mbps are 9,375,000,000 bytes.
        val downloader = downloader(fake) {
            listOf(
                stream("1080p", "https://x/big.mp4", sizeBytes = 9_375_000_001),
                stream("1080p", "https://x/fits.mp4", sizeBytes = 9_375_000_000),
            )
        }

        downloader.start(movie.movieBatchRequest(SeasonDownloadDefaults(maxBitrateMbps = 10)))
        advanceUntilIdle()

        assertEquals(listOf("https://x/fits.mp4"), fake.queuedUrls)
    }

    @Test
    fun a_source_that_fails_is_replaced_by_the_next_matching_one_up_to_three_sources() = runTest {
        val fake = Fake()
        val downloader = downloader(fake) { (1..5).map { stream("1080p", "https://x/$it.mp4") } }

        downloader.start(movie.movieBatchRequest(SeasonDownloadDefaults()))
        repeat(3) {
            advanceUntilIdle()
            fake.settle("tt100", DownloadStatus.Failed)
        }
        advanceUntilIdle()

        assertEquals(listOf("https://x/1.mp4", "https://x/2.mp4", "https://x/3.mp4"), fake.queuedUrls)
        assertEquals(SeasonBatchEpisodeResult.Failed, fake.result())
    }

    @Test
    fun the_same_listing_with_a_changed_address_is_not_transferred_twice() = runTest {
        val fake = Fake()
        val first = stream("1080p", "https://x/first.mp4")
        val sameListing = first.copy(url = "https://x/rotated.mp4")
        val next = stream("1080p", "https://x/next.mp4")
        val downloader = downloader(fake) { listOf(first, sameListing, next) }

        downloader.start(movie.movieBatchRequest(SeasonDownloadDefaults()))
        advanceUntilIdle()
        fake.settle("tt100", DownloadStatus.Failed)
        advanceUntilIdle()

        assertEquals(listOf("https://x/first.mp4", "https://x/next.mp4"), fake.queuedUrls)
        fake.settle("tt100", DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(SeasonBatchEpisodeResult.Completed, fake.result())
    }

    @Test
    fun a_movie_without_a_matching_source_is_reported_as_not_found() = runTest {
        val fake = Fake()
        val downloader = downloader(fake) { listOf(stream("720p", "https://x/720.mp4")) }

        downloader.start(movie.movieBatchRequest(SeasonDownloadDefaults(BatchQuality.P2160)))
        advanceUntilIdle()

        assertEquals(emptyList(), fake.queuedUrls)
        assertEquals(SeasonBatchEpisodeResult.NoMatchingSource, fake.result())
    }

    @Test
    fun a_movie_that_is_downloaded_downloading_or_paused_is_skipped() = runTest {
        listOf(DownloadStatus.Completed, DownloadStatus.Downloading, DownloadStatus.Paused).forEach { status ->
            val fake = Fake(listOf(movieItem("tt100", status)))
            val downloader = downloader(fake) { listOf(stream("1080p", "https://x/1080.mp4")) }

            downloader.start(movie.movieBatchRequest(SeasonDownloadDefaults()))
            advanceUntilIdle()

            assertEquals(emptyList(), fake.queuedUrls, status.name)
            assertEquals(SeasonBatchEpisodeResult.AlreadyDownloaded, fake.result(), status.name)
        }
    }

    @Test
    fun a_clip_far_too_small_for_the_runtime_of_the_movie_is_a_failed_source_and_the_next_one_is_taken() = runTest {
        val fake = Fake()
        val downloader = downloader(fake) { listOf(stream("1080p", "https://x/1.mp4"), stream("1080p", "https://x/2.mp4")) }

        downloader.start(movie.movieBatchRequest(SeasonDownloadDefaults()))
        advanceUntilIdle()
        // 125 minutes at 50 kbit/s are 46,875,000 bytes.
        fake.state.value = DownloadsUiState(fake.state.value.items.map { it.copy(status = DownloadStatus.Completed, downloadedBytes = 46_874_999) })
        advanceUntilIdle()

        assertEquals(listOf("https://x/1.mp4", "https://x/2.mp4"), fake.queuedUrls)
    }

    @Test
    fun a_movie_whose_download_failed_before_is_queued_again() = runTest {
        val fake = Fake(listOf(movieItem("tt100", DownloadStatus.Failed)))
        val downloader = downloader(fake) { listOf(stream("1080p", "https://x/1080.mp4")) }

        downloader.start(movie.movieBatchRequest(SeasonDownloadDefaults()))
        advanceUntilIdle()

        assertEquals(listOf("https://x/1080.mp4"), fake.queuedUrls)
    }

    @Test
    fun an_episode_download_with_the_same_id_is_not_taken_for_the_movie() = runTest {
        val episode = movieItem("tt100", DownloadStatus.Completed).copy(seasonNumber = 0, episodeNumber = 1)
        val fake = Fake(listOf(episode))
        val downloader = downloader(fake) { listOf(stream("1080p", "https://x/1080.mp4")) }

        downloader.start(movie.movieBatchRequest(SeasonDownloadDefaults()))
        advanceUntilIdle()

        assertEquals(listOf("https://x/1080.mp4"), fake.queuedUrls)
    }
}

internal fun movieItem(metaId: String, status: DownloadStatus, url: String = "https://x/old.mp4") = DownloadItem(
    id = "movie-$metaId-${status.name}",
    contentType = "movie",
    parentMetaId = metaId,
    parentMetaType = "movie",
    videoId = metaId,
    title = "Movie",
    streamTitle = "s",
    providerName = "A",
    sourceUrl = url,
    fileName = "$metaId.mp4",
    status = status,
    downloadedBytes = if (status == DownloadStatus.Completed) 2_000_000_000 else 0,
    createdAtEpochMs = 0,
    updatedAtEpochMs = 0,
)
