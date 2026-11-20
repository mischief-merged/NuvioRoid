package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.streams.StreamItem
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeasonBatchDownloaderIntegrationTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    // A Matroska signature followed by filler, so the downloads are accepted as video files.
    private val payload = matroskaFile(mediaBytes = 256 * 1024)
    private val showId = "$ShowIdPrefix${System.nanoTime()}"

    @Test
    fun a_season_downloads_through_the_real_queue_two_at_a_time_with_fallback_and_skips() = runBlocking {
        val transfersInFlight = AtomicInteger(0)
        val peakTransfers = AtomicInteger(0)
        val twoSeasonTransfersStarted = CountDownLatch(2)
        val serverThreads = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverThreads
        server.createContext("/files") { exchange ->
            if (exchange.requestURI.path.contains("e2-1080")) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            val inFlight = transfersInFlight.incrementAndGet()
            peakTransfers.accumulateAndGet(inFlight, ::maxOf)
            var counted = true
            try {
                // Episode 3 is downloaded on its own first. The first transfer of the full season then
                // stays open until a second one has started, so the peak shows both that the limit is
                // used and that it is never exceeded.
                if (!exchange.requestURI.path.contains("e3-")) {
                    twoSeasonTransfersStarted.countDown()
                    twoSeasonTransfersStarted.await(60, TimeUnit.SECONDS)
                }
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { body ->
                    // The transfer stops counting before its last byte is sent. The client cannot finish
                    // it, and so cannot start the next one, until after that.
                    body.write(payload, 0, payload.size - 1)
                    body.flush()
                    transfersInFlight.decrementAndGet()
                    counted = false
                    body.write(payload, payload.size - 1, 1)
                }
            } finally {
                if (counted) transfersInFlight.decrementAndGet()
                exchange.close()
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/files"

        val episodes = (1..5).map { SeasonBatchEpisode("$showId:1:$it", 1, it, "Episode $it", null) }
        val fetched = CopyOnWriteArrayList<Int>()
        val scopes = mutableListOf<CoroutineScope>()

        fun downloader(
            fetchStreams: suspend (SeasonBatchEpisode) -> List<StreamItem>,
            finished: CompletableDeferred<SeasonBatchSummary>,
        ) = SeasonBatchDownloader(
            // The files of this test are 256 KiB, so the floor for a real video is lowered.
            minVideoBytes = { 1_024 },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it },
            downloads = DownloadsRepository.uiState,
            fetchStreams = fetchStreams,
            prepareStream = { stream, _ -> stream },
            enqueue = { request, episode, stream ->
                DownloadsRepository.enqueueFromStream(
                    contentType = request.contentType,
                    videoId = episode.videoId,
                    parentMetaId = request.parentMetaId,
                    parentMetaType = request.parentMetaType,
                    title = request.title,
                    logo = null,
                    poster = null,
                    background = null,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    episodeTitle = episode.title,
                    episodeThumbnail = null,
                    stream = stream,
                )
            },
            onFinished = { _, summary -> finished.complete(summary) },
            maxConcurrent = 2,
        )

        DownloadsRepository.ensureLoaded()
        // Items a run that was interrupted before its cleanup may have left behind.
        removeItems { it.parentMetaId.startsWith(ShowIdPrefix) }
        try {
            val firstRun = CompletableDeferred<SeasonBatchSummary>()
            downloader(
                fetchStreams = { listOf(stream("Fixture 720p", "$base/e3-720.mp4")) },
                finished = firstRun,
            ).start(request(listOf(episodes[2])))
            withTimeout(60_000) { firstRun.await() }

            val fullRun = CompletableDeferred<SeasonBatchSummary>()
            downloader(
                fetchStreams = { episode ->
                    fetched += episode.episodeNumber
                    if (episode.episodeNumber == 5) {
                        emptyList()
                    } else {
                        listOf(
                            stream("Fixture 1080p", "$base/e${episode.episodeNumber}-1080.mp4"),
                            stream("Fixture 720p", "$base/e${episode.episodeNumber}-720.mp4"),
                        )
                    }
                },
                finished = fullRun,
            ).start(request(episodes))
            val summary = withTimeout(120_000) { fullRun.await() }

            assertEquals(3, summary.count(SeasonBatchEpisodeResult.Completed), summary.results.toString())
            assertEquals(1, summary.count(SeasonBatchEpisodeResult.AlreadyDownloaded))
            assertEquals(1, summary.count(SeasonBatchEpisodeResult.NoMatchingSource))
            assertTrue(3 !in fetched, "episode 3 was already downloaded and must not be looked up again: $fetched")
            assertEquals(2, peakTransfers.get(), "peak simultaneous transfers")

            val items = ownItems()
            assertEquals(listOf(1, 2, 3, 4), items.mapNotNull { it.episodeNumber }.sorted())
            assertTrue(items.all { it.status == DownloadStatus.Completed })
            assertTrue(
                items.first { it.episodeNumber == 2 }.sourceUrl.endsWith("e2-720.mp4"),
                "episode 2 should have fallen back to its second source",
            )
            val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile()
            items.forEach { item ->
                assertEquals(payload.size.toLong(), downloadsDir.resolve(item.fileName).length(), item.fileName)
            }
        } finally {
            scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
            removeItems { it.parentMetaId == showId }
            server.stop(0)
            serverThreads.shutdownNow()
        }
    }

    private fun ownItems(): List<DownloadItem> =
        DownloadsRepository.uiState.value.items.filter { it.parentMetaId == showId }

    private fun removeItems(predicate: (DownloadItem) -> Boolean) {
        DownloadsRepository.uiState.value.items.filter(predicate).forEach { DownloadsRepository.cancelDownload(it.id) }
    }

    private fun stream(label: String, url: String) =
        StreamItem(name = label, url = url, addonName = "Fixture", addonId = "addon:fixture")

    private fun request(episodes: List<SeasonBatchEpisode>) = SeasonBatchRequest(
        contentType = "series",
        parentMetaId = showId,
        parentMetaType = "series",
        title = "Fixture Show",
        logo = null,
        poster = null,
        background = null,
        seasonNumber = 1,
        episodes = episodes,
        quality = BatchQuality.BestAvailable,
    )

    private companion object {
        const val ShowIdPrefix = "season-test-"
    }
}
