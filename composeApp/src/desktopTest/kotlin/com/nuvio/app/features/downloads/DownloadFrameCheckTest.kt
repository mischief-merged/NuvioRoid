package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_frames_blank
import nuvio.composeapp.generated.resources.downloads_error_frames_broken_end
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadFrameCheckTest {
    /** Keeps the stored payload in memory. Files and frames are what the test says they are. */
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val files = mutableMapOf<String, DownloadedFileFacts>()
        val frames = mutableMapOf<String, FrameSamples?>()
        var confirming: FrameSamples? = null
        val confirmed = CopyOnWriteArrayList<String>()
        val removed = CopyOnWriteArrayList<String?>()
        val sampled = CopyOnWriteArrayList<String>()
        val started = CopyOnWriteArrayList<String>()
        var finish: ((String, Long?) -> Unit)? = null
        var finishOnItsOwn = false
        var whileSampling: (() -> Unit)? = null

        override fun loadPayload(): String? = payload

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            started += request.item.sourceUrl
            finish = onSuccess
            if (finishOnItsOwn) {
                // Reports from a thread of its own, as a real transfer does, after start has returned.
                val url = request.item.sourceUrl
                thread { Thread.sleep(50); onSuccess(url, 300_000_000) }
            }
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean {
            removed += localFileUri
            files.remove(localFileUri)
            return true
        }

        override fun removePartialFile(destinationFileName: String): Boolean = true

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            localFileUri?.takeIf { it in files }

        override fun inspectFile(localFileUri: String): DownloadedFileFacts = files[localFileUri] ?: DownloadedFileFacts(exists = false)

        override fun sampleFrames(localFileUri: String): FrameSamples? {
            sampled += localFileUri
            whileSampling?.invoke()
            return frames[localFileUri]
        }

        override fun sampleConfirmingFrames(localFileUri: String, durationMs: Long): FrameSamples? {
            confirmed += localFileUri
            return confirming
        }
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val realRuntime = downloadRuntimeLookup
    private val uri = "file:/downloads/episode.mkv"
    private val video = DownloadedFileFacts(exists = true, sizeBytes = 300_000_000, isVideoContainer = true)

    @BeforeTest
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        downloadRuntimeLookup = { 45 }
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        downloadRuntimeLookup = realRuntime
    }

    private fun text(resource: StringResource) = runBlocking { getString(resource) }

    private fun item() = DownloadsRepository.uiState.value.items.single()

    private fun picture(seed: Int) = ByteArray(FramePixelCount) { ((it * 3 + seed * 40) % 256).toByte() }

    private fun frames(title: String? = null, pixels: (Int, FrameWindow) -> ByteArray?) = FrameSamples(
        60_000,
        FrameSamplePositions.mapIndexed { index, (fraction, window) -> FrameSample((60_000 * fraction).toLong(), window, pixels(index, window)) },
        title,
    )

    private val good = frames { index, _ -> picture(index) }
    private val cutOffAtTheEnd = frames { index, window -> if (window == FrameWindow.End) null else picture(index) }
    private val blank = frames { _, _ -> ByteArray(FramePixelCount) }

    private fun stream(url: String) = StreamItem(name = "1080p", url = url, addonName = "A", addonId = "a")

    /** Queues an episode (or a movie) and lets the transfer report that it finished. */
    private fun download(samples: FrameSamples?, file: DownloadedFileFacts = video, movie: Boolean = false) {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = if (movie) "movie" else "series", videoId = "tt1:1:1", parentMetaId = "tt1",
            parentMetaType = if (movie) "movie" else "series", title = "Show",
            logo = null, poster = null, background = null,
            seasonNumber = 1.takeUnless { movie }, episodeNumber = 1.takeUnless { movie }, episodeTitle = null,
            episodeThumbnail = null, stream = stream("https://example.com/e.mkv"),
        )
        backend.files[uri] = file
        backend.frames[uri] = samples
        backend.finish!!(uri, 300_000_000)
    }

    @Test
    fun a_file_whose_frames_are_healthy_is_finished_and_recorded_as_checked() {
        download(good)

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(uri, item().localFileUri)
        assertEquals(DownloadFramesCheck.Passed, item().framesCheck)
        assertEquals(listOf(uri), backend.sampled)
        assertEquals(emptyList(), backend.removed)
    }

    @Test
    fun a_file_that_breaks_off_before_its_end_is_a_failed_download_without_a_file() {
        download(cutOffAtTheEnd)

        assertEquals(DownloadStatus.Failed, item().status)
        assertEquals(text(Res.string.downloads_error_frames_broken_end), item().errorMessage)
        assertNull(item().localFileUri)
        assertEquals(DownloadFramesCheck.NotChecked, item().framesCheck)
        assertEquals(listOf<String?>(uri), backend.removed)
    }

    private fun more(pixels: (Int) -> ByteArray?) = FrameSamples(60_000, ConfirmingFramePositions.mapIndexed { index, fraction ->
        FrameSample((60_000 * fraction).toLong(), FrameWindow.Middle, pixels(index))
    })

    @Test
    fun a_blank_video_that_more_frames_confirm_is_a_failed_download_and_is_removed() {
        backend.confirming = more { ByteArray(FramePixelCount) }

        download(blank)

        assertEquals(DownloadStatus.Failed, item().status)
        assertEquals(text(Res.string.downloads_error_frames_blank), item().errorMessage)
        assertEquals(listOf<String?>(uri), backend.removed)
        assertEquals(listOf(uri), backend.confirmed)
    }

    @Test
    fun a_video_that_looked_blank_but_shows_a_picture_in_more_frames_is_finished_and_checked() {
        backend.confirming = more { picture(it) }

        download(blank)

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(DownloadFramesCheck.Passed, item().framesCheck)
        assertEquals(emptyList(), backend.removed)
    }

    @Test
    fun a_video_that_looked_blank_is_finished_but_not_recorded_as_checked_when_more_frames_cannot_be_taken() {
        backend.confirming = null

        download(blank)

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(DownloadFramesCheck.NotChecked, item().framesCheck)
    }

    @Test
    fun a_healthy_video_is_not_asked_for_more_frames() {
        download(good)

        assertEquals(emptyList(), backend.confirmed)
    }

    @Test
    fun an_episode_whose_title_names_another_episode_is_kept_and_marked_as_suspect() {
        download(frames("Show.S01E02.1080p") { index, _ -> picture(index) })

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(uri, item().localFileUri)
        assertEquals(DownloadFramesCheck.Suspect, item().framesCheck)
        assertEquals(emptyList(), backend.removed)
    }

    @Test
    fun a_movie_is_not_held_against_an_episode_marker() {
        download(frames("Show.S01E02.1080p") { index, _ -> picture(index) }, movie = true)

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(DownloadFramesCheck.Passed, item().framesCheck)
    }

    @Test
    fun a_platform_that_cannot_look_at_frames_finishes_the_download_unchecked() {
        download(samples = null)

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(DownloadFramesCheck.NotChecked, item().framesCheck)
        assertEquals(emptyList(), backend.removed)
    }

    @Test
    fun frames_are_not_looked_at_for_a_file_that_failed_an_earlier_check() {
        download(good, file = video.copy(isVideoContainer = false))

        assertEquals(DownloadStatus.Failed, item().status)
        assertEquals(emptyList(), backend.sampled)
    }

    @Test
    fun frames_are_not_looked_at_when_the_list_is_loaded_or_a_finished_download_is_verified() {
        backend.files[uri] = video
        backend.frames[uri] = cutOffAtTheEnd
        backend.payload = """{"items":[{"id":"a","contentType":"series","parentMetaId":"tt1","parentMetaType":"series","videoId":"tt1:1:1","title":"Show",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/a.mkv","fileName":"episode.mkv",""" +
            """"localFileUri":"$uri","status":"Completed","downloadedBytes":1000,"totalBytes":1000,""" +
            """"createdAtEpochMs":1,"updatedAtEpochMs":2}]}"""

        DownloadsRepository.ensureLoaded()
        assertTrue(DownloadsRepository.verifyCompletedDownload("a"))

        assertEquals(DownloadStatus.Completed, item().status)
        assertEquals(emptyList(), backend.sampled)
        assertEquals(DownloadFramesCheck.NotChecked, item().framesCheck)
    }

    @Test
    fun the_state_lock_is_not_held_while_frames_are_looked_at() {
        var lockWasFree: Boolean? = null
        backend.whileSampling = {
            // Anything that takes the repository's state lock from another thread waits if this one holds it.
            val other = thread { DownloadsRepository.nextDownloadId(0L) }
            other.join(3_000)
            lockWasFree = !other.isAlive
            if (other.isAlive) other.interrupt()
        }

        download(good)

        assertEquals(true, lockWasFree)
        assertEquals(DownloadStatus.Completed, item().status)
    }

    @Test
    fun a_download_cancelled_while_its_frames_are_looked_at_stays_cancelled() {
        backend.whileSampling = { DownloadsRepository.cancelDownload(DownloadsRepository.uiState.value.items.single().id) }

        download(good)

        assertEquals(emptyList(), DownloadsRepository.uiState.value.items)
    }

    @Test
    fun a_batch_that_gets_a_bad_file_from_its_first_source_goes_on_to_the_next_source() = runBlocking {
        val badUrl = "https://example.com/bad-1080.mkv"
        val goodUrl = "https://example.com/good-720.mkv"
        backend.finishOnItsOwn = true
        backend.files[badUrl] = video
        backend.files[goodUrl] = video
        backend.frames[badUrl] = cutOffAtTheEnd
        backend.frames[goodUrl] = good
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val finished = CompletableDeferred<SeasonBatchSummary>()
        val episode = SeasonBatchEpisode("tt9:1:1", 1, 1, "Episode 1", null)
        DownloadsRepository.ensureLoaded()
        try {
            SeasonBatchDownloader(
                minVideoBytes = { 1 },
                scope = scope,
                downloads = DownloadsRepository.uiState,
                fetchStreams = {
                    listOf(
                        StreamItem(name = "720p", url = goodUrl, addonName = "A", addonId = "a"),
                        StreamItem(name = "1080p", url = badUrl, addonName = "A", addonId = "a"),
                    )
                },
                prepareStream = { stream, _ -> stream },
                enqueue = { request, ep, stream ->
                    DownloadsRepository.enqueueFromStream(
                        contentType = request.contentType, videoId = ep.videoId, parentMetaId = request.parentMetaId,
                        parentMetaType = request.parentMetaType, title = request.title, logo = null, poster = null,
                        background = null, seasonNumber = ep.seasonNumber, episodeNumber = ep.episodeNumber,
                        episodeTitle = ep.title, episodeThumbnail = null, stream = stream,
                    )
                },
                onFinished = { _, summary -> finished.complete(summary) },
                discard = { DownloadsRepository.cancelDownload(it.id) },
            ).start(
                SeasonBatchRequest(
                    contentType = "series", parentMetaId = "tt9", parentMetaType = "series", title = "Show",
                    logo = null, poster = null, background = null, seasonNumber = 1, episodes = listOf(episode),
                    quality = BatchQuality.BestAvailable,
                ),
            )
            val summary = withTimeout(60_000) { finished.await() }

            assertEquals(1, summary.count(SeasonBatchEpisodeResult.Completed), summary.results.toString())
            assertEquals(listOf(badUrl, goodUrl), backend.started.toList())
            assertEquals(listOf<String?>(badUrl), backend.removed.filterNotNull())
            val kept = DownloadsRepository.uiState.value.items.single { it.status == DownloadStatus.Completed }
            assertEquals(goodUrl, kept.localFileUri)
            assertEquals(DownloadFramesCheck.Passed, kept.framesCheck)
        } finally {
            scope.cancel()
        }
    }
}
