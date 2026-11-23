package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_placeholder_length_film
import nuvio.composeapp.generated.resources.downloads_error_placeholder_size
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The repository against a backend that is a list in memory: no network, no folder, no real file. */
class PlaceholderDownloadsTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val files = mutableMapOf<String, DownloadedFileFacts>()
        var frames: FrameSamples? = null
        val sampled = CopyOnWriteArrayList<String>()
        val started = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        val removed = CopyOnWriteArrayList<String?>()
        val trashed = CopyOnWriteArrayList<String>()
        val partialsRemoved = CopyOnWriteArrayList<String>()

        /** What a transfer of this url does as soon as it starts. */
        var onStart: (url: String, progress: (Long, Long?) -> Unit, success: (String, Long?) -> Unit) -> Unit = { _, _, _ -> }

        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) { this.payload = payload }
        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            started += request.item.sourceUrl
            onStart(request.item.sourceUrl, onProgress, onSuccess)
            return object : DownloadsTaskHandle {
                override fun cancel() { cancelled += request.item.sourceUrl }
            }
        }

        override fun removeFile(localFileUri: String?): Boolean {
            removed += localFileUri
            files.remove(localFileUri)
            return true
        }

        override fun removePartialFile(destinationFileName: String): Boolean {
            partialsRemoved += destinationFileName
            return true
        }

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            localFileUri?.takeIf { it in files }

        override fun inspectFile(localFileUri: String): DownloadedFileFacts = files[localFileUri] ?: DownloadedFileFacts(exists = false)

        override fun sampleFrames(localFileUri: String): FrameSamples? {
            sampled += localFileUri
            return frames
        }

        override fun sampleConfirmingFrames(localFileUri: String, durationMs: Long): FrameSamples? = frames

        override fun trashFile(localFileUri: String): Boolean {
            trashed += localFileUri
            files.remove(localFileUri)
            return true
        }
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val realRuntimeLookup = downloadRuntimeLookup
    private val uri = "file:/downloads/film.mp4"

    // The real case: a film whose source said 9.53 GB in its text; 8,509,628 bytes arrived, 300.9 seconds long.
    private val claimText = "[💾 9.53 GB] Some.Title.2026.1080p.WEB-DL.mkv"
    private val clipBytes = 8_509_628L
    private val clip = DownloadedFileFacts(exists = true, sizeBytes = clipBytes, isVideoContainer = true, durationMs = 300_900L)

    @BeforeTest
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        downloadRuntimeLookup = { null }
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        downloadRuntimeLookup = realRuntimeLookup
    }

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    private fun items() = DownloadsRepository.uiState.value.items

    private fun only() = items().single()

    private fun stream(url: String, description: String? = claimText, videoSize: Long? = null) = StreamItem(
        name = "RD+ 1080p", description = description, url = url, addonName = "A", addonId = "a",
        behaviorHints = StreamBehaviorHints(videoSize = videoSize),
    )

    private fun enqueueMovie(stream: StreamItem, userChoseReplacement: Boolean = false) {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = "tt1", parentMetaId = "tt1", parentMetaType = "movie", title = "Some Title",
            logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null, episodeTitle = null,
            episodeThumbnail = null, stream = stream,
            userChoseReplacement = userChoseReplacement,
        )
    }

    /** One finished download as an older build left it: no check recorded. */
    private fun oldRecord(extra: String = "", subtitle: String? = claimText, framesCheck: String = "NotChecked", totalBytes: Long = clipBytes): String {
        val sub = subtitle?.let { ""","streamSubtitle":"$it"""" }.orEmpty()
        return """{"items":[{"id":"a","contentType":"movie","parentMetaType":"movie","videoId":"tt1","parentMetaId":"tt1",""" +
            """"title":"Some Title","streamTitle":"RD+ 1080p"$sub,"providerName":"A","sourceUrl":"https://example.com/a.mkv",""" +
            """"fileName":"film.mp4","localFileUri":"$uri","status":"Completed","downloadedBytes":$totalBytes,"totalBytes":$totalBytes,""" +
            """"framesCheck":"$framesCheck","createdAtEpochMs":1,"updatedAtEpochMs":1$extra}]}"""
    }

    private fun loadOld(
        extra: String = "",
        subtitle: String? = claimText,
        file: DownloadedFileFacts? = clip,
        framesCheck: String = "NotChecked",
        totalBytes: Long = clipBytes,
    ) {
        backend.payload = oldRecord(extra, subtitle, framesCheck, totalBytes)
        file?.let { backend.files[uri] = it }
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
    }

    private val blankFrames = FrameSamples(
        300_900,
        FrameSamplePositions.mapIndexed { index, (fraction, window) -> FrameSample((300_900 * fraction).toLong(), window, ByteArray(FramePixelCount)) },
    )

    private val goodFrames = FrameSamples(
        300_900,
        FrameSamplePositions.mapIndexed { index, (fraction, window) ->
            FrameSample((300_900 * fraction).toLong(), window, ByteArray(FramePixelCount) { ((it * 3 + index * 40) % 256).toByte() })
        },
    )

    // Enqueue

    @Test
    fun the_size_in_the_text_of_a_source_fills_the_expected_size_when_the_source_states_none() {
        enqueueMovie(stream("https://example.com/a.mkv"))

        assertEquals(9_530_000_000L, only().expectedSizeBytes)
    }

    @Test
    fun a_size_the_source_states_is_kept_over_the_text() {
        enqueueMovie(stream("https://example.com/a.mkv", videoSize = 9_400_000_000L))

        assertEquals(9_400_000_000L, only().expectedSizeBytes)
    }

    @Test
    fun text_that_names_no_size_or_a_pack_leaves_the_expected_size_empty() {
        enqueueMovie(stream("https://example.com/a.mkv", description = "Some.Title.2026.1080p.mkv"))
        assertNull(only().expectedSizeBytes)
        DownloadsRepository.clearLocalState()
        backend.payload = null
        enqueueMovie(stream("https://example.com/b.mkv", description = "[💾 41 GB] Collection Complete"))
        assertNull(only().expectedSizeBytes)
    }

    // Early failure

    @Test
    fun a_total_far_below_the_claim_at_the_start_ends_the_transfer_and_fails_the_source() {
        backend.onStart = { _, progress, _ -> progress(0L, clipBytes) }

        enqueueMovie(stream("https://example.com/a.mkv"))

        assertEquals(DownloadStatus.Failed, only().status)
        assertEquals(DownloadFailureKind.BadFile, only().failureKind)
        assertEquals("Not the real video: 8.5 MB arrived, the source said 9.5 GB", only().errorMessage)
        assertEquals(text(Res.string.downloads_error_placeholder_size, "8.5 MB", "9.5 GB"), only().errorMessage)
        assertEquals(listOf("https://example.com/a.mkv"), backend.cancelled)
        assertTrue(backend.partialsRemoved.isNotEmpty())
        assertNull(only().localFileUri)
        assertTrue(only().needsAnotherSource, "the row offers Choose another source")
        // Not tried again: the same source gives the same clip.
        assertEquals(1, backend.started.size)
    }

    @Test
    fun a_total_in_line_with_the_claim_goes_on_downloading() {
        backend.onStart = { _, progress, _ -> progress(0L, 9_500_000_000L) }

        enqueueMovie(stream("https://example.com/a.mkv"))

        assertEquals(DownloadStatus.Downloading, only().status)
        assertEquals(9_500_000_000L, only().totalBytes)
        assertEquals(emptyList(), backend.cancelled)
    }

    @Test
    fun a_small_total_against_a_small_claim_goes_on_downloading() {
        backend.onStart = { _, progress, _ -> progress(0L, 4_000_000L) }

        enqueueMovie(stream("https://example.com/a.mkv", description = "[💾 150 MB] Short.mkv"))

        assertEquals(DownloadStatus.Downloading, only().status)
    }

    @Test
    fun a_source_with_no_claim_and_no_known_runtime_is_not_judged_at_the_start() {
        backend.onStart = { _, progress, _ -> progress(0L, clipBytes) }

        enqueueMovie(stream("https://example.com/a.mkv", description = null))

        assertEquals(DownloadStatus.Downloading, only().status)
    }

    @Test
    fun a_total_that_is_far_too_small_for_the_runtime_ends_the_transfer_with_no_claim() {
        downloadRuntimeLookup = { 120 }
        backend.onStart = { _, progress, _ -> progress(0L, clipBytes) }

        enqueueMovie(stream("https://example.com/a.mkv", description = null))

        assertEquals(DownloadStatus.Failed, only().status)
        assertEquals(DownloadFailureKind.BadFile, only().failureKind)
        assertEquals("Not the real video: 8.5 MB is far too small for a film of about 2 hours", only().errorMessage)
        assertEquals(1, backend.cancelled.size)
        assertEquals(1, backend.started.size)
    }

    @Test
    fun a_total_in_line_with_the_runtime_goes_on_downloading() {
        downloadRuntimeLookup = { 120 }
        backend.onStart = { _, progress, _ -> progress(0L, 1_500_000_000L) }

        enqueueMovie(stream("https://example.com/a.mkv", description = null))

        assertEquals(DownloadStatus.Downloading, only().status)
    }

    @Test
    fun a_download_still_in_progress_is_not_judged_by_what_has_arrived_so_far() {
        downloadRuntimeLookup = { 120 }
        backend.onStart = { _, progress, _ -> progress(1_000L, 1_500_000_000L) }

        enqueueMovie(stream("https://example.com/a.mkv", description = null))

        assertEquals(DownloadStatus.Downloading, only().status)
    }

    @Test
    fun a_file_that_arrives_without_an_announced_size_is_judged_when_it_is_complete() {
        backend.onStart = { _, _, success ->
            backend.files[uri] = clip
            success(uri, null)
        }

        enqueueMovie(stream("https://example.com/a.mkv"))

        assertEquals(DownloadStatus.Failed, only().status)
        assertEquals(DownloadFailureKind.BadFile, only().failureKind)
        assertEquals("Not the real video: 8.5 MB arrived, the source said 9.5 GB", only().errorMessage)
        // A file that was just fetched and failed its check is removed, as for every other bad file.
        assertEquals(listOf<String?>(uri), backend.removed)
    }

    @Test
    fun a_file_in_line_with_the_claim_is_finished() {
        backend.onStart = { _, _, success ->
            backend.files[uri] = DownloadedFileFacts(exists = true, sizeBytes = 9_400_000_000L, isVideoContainer = true)
            success(uri, 9_400_000_000L)
        }

        enqueueMovie(stream("https://example.com/a.mkv"))

        assertEquals(DownloadStatus.Completed, only().status)
        assertFalse(only().isPlaceholder)
    }

    // Season download moves on

    @Test
    fun a_season_download_moves_on_to_the_next_source_when_the_first_is_a_placeholder() = runBlocking {
        backend.onStart = { url, progress, success ->
            if (url.contains("clip")) {
                progress(0L, clipBytes)
            } else {
                progress(0L, 1_200_000_000L)
                backend.files[uri] = DownloadedFileFacts(exists = true, sizeBytes = 1_200_000_000L, isVideoContainer = true)
                success(uri, 1_200_000_000L)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val finished = kotlinx.coroutines.CompletableDeferred<SeasonBatchSummary>()
        val episode = SeasonBatchEpisode("tt9:1:1", 1, 1, "Pilot", null)
        val request = SeasonBatchRequest(
            contentType = "series", parentMetaId = "tt9", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodes = listOf(episode),
            quality = BatchQuality.BestAvailable,
        )
        DownloadsRepository.ensureLoaded()
        try {
            SeasonBatchDownloader(
                scope = scope,
                downloads = DownloadsRepository.uiState,
                fetchStreams = {
                    listOf(
                        stream("https://example.com/clip.mkv", description = "[💾 1.2 GB] Show.S01E01.1080p.mkv"),
                        stream("https://example.com/real.mkv", description = "[💾 1.2 GB] Show.S01E01.720p.mkv"),
                    )
                },
                prepareStream = { stream, _ -> stream },
                enqueue = { req, ep, stream ->
                    DownloadsRepository.enqueueFromStream(
                        contentType = req.contentType, videoId = ep.videoId, parentMetaId = req.parentMetaId,
                        parentMetaType = req.parentMetaType, title = req.title, logo = null, poster = null, background = null,
                        seasonNumber = ep.seasonNumber, episodeNumber = ep.episodeNumber, episodeTitle = ep.title,
                        episodeThumbnail = null, stream = stream,
                    )
                },
                onFinished = { _, summary -> finished.complete(summary) },
            ).start(request)
            val summary = withTimeout(30_000) { finished.await() }

            assertEquals(1, summary.count(SeasonBatchEpisodeResult.Completed), summary.results.toString())
            assertEquals(2, backend.started.size, backend.started.toString())
            assertTrue(backend.started.first().endsWith("clip.mkv") || backend.started.first().endsWith("real.mkv"))
            assertEquals(DownloadStatus.Completed, only().status)
        } finally {
            scope.cancel()
        }
    }

    // Looking again at a download from before the checks

    @Test
    fun the_real_case_is_marked_by_its_size_against_the_claim_in_the_text_of_its_source() {
        loadOld()
        assertNull(only().verifiedBy)
        assertEquals(DownloadFramesCheck.NotChecked, only().framesCheck)

        val outcome = DownloadsRepository.recheckCompleted("a")

        assertEquals(RecheckOutcome.Flagged, outcome)
        val item = only()
        assertEquals(DownloadStatus.Completed, item.status)
        assertEquals(DownloadFramesCheck.NotTheRealVideo, item.framesCheck)
        assertTrue(item.isPlaceholder)
        assertEquals("Not the real video: 8.5 MB arrived, the source said 9.5 GB", item.errorMessage)
        // The result is saved like a fresh download's, and the claim is kept with the record.
        assertEquals(DownloadVerification.Size, item.verifiedBy)
        assertEquals(9_530_000_000L, item.expectedSizeBytes)
        assertTrue(backend.payload!!.contains("NotTheRealVideo"))
        // The file is marked, never touched.
        assertEquals(uri, item.localFileUri)
        assertEquals(emptyList(), backend.removed)
        assertEquals(emptyList(), backend.trashed)
        assertTrue(uri in backend.files)
    }

    @Test
    fun a_marked_download_is_not_counted_as_downloaded_or_available_offline() {
        loadOld()
        DownloadsRepository.recheckCompleted("a")

        val state = DownloadsRepository.uiState.value
        assertEquals(emptyList(), state.completedItems)
        assertEquals(listOf("a"), state.activeItems.map { it.id })
        assertFalse(only().isPlayable)
        assertNull(DownloadsRepository.findPlayableDownload("tt1"))
        assertNull(DownloadsRepository.findPlayableDownloadByVideoId("tt1"))
    }

    @Test
    fun a_marked_download_is_still_deleted_to_the_trash_like_any_other() {
        loadOld()
        DownloadsRepository.recheckCompleted("a")

        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload("a"))

        assertEquals(listOf(uri), backend.trashed)
        assertEquals(emptyList(), items())
    }

    @Test
    fun a_file_large_enough_for_its_runtime_is_marked_by_its_running_time_and_picture() {
        downloadRuntimeLookup = { 120 }
        backend.frames = blankFrames
        // 300.9 seconds and 90 MB: above the bitrate floor, so only the length and the picture speak.
        loadOld(subtitle = null, file = clip.copy(sizeBytes = 90_000_000L), totalBytes = 90_000_000L)

        val outcome = DownloadsRepository.recheckCompleted("a")

        assertEquals(RecheckOutcome.Flagged, outcome)
        assertEquals(
            "Not the real video: it is 5 minutes long, the film runs about 2 hours",
            only().errorMessage,
        )
        assertEquals(text(Res.string.downloads_error_placeholder_length_film, "5 minutes", "2 hours"), only().errorMessage)
        assertEquals(emptyList(), backend.removed)
    }

    @Test
    fun a_finished_download_is_marked_by_its_bitrate_when_the_source_claimed_no_size() {
        downloadRuntimeLookup = { 120 }
        loadOld(subtitle = null)

        assertEquals(RecheckOutcome.Flagged, DownloadsRepository.recheckCompleted("a"))

        assertEquals("Not the real video: 8.5 MB is far too small for a film of about 2 hours", only().errorMessage)
        assertEquals(DownloadFramesCheck.NotTheRealVideo, only().framesCheck)
        assertEquals(emptyList(), backend.removed)
        assertEquals(emptyList(), backend.sampled)
    }

    @Test
    fun a_short_run_alone_is_not_marked_and_its_picture_is_looked_at_only_when_that_can_matter() {
        downloadRuntimeLookup = { 120 }
        backend.frames = goodFrames
        loadOld(subtitle = null, file = clip.copy(sizeBytes = 90_000_000L), totalBytes = 90_000_000L)

        val outcome = DownloadsRepository.recheckCompleted("a")

        assertEquals(RecheckOutcome.CouldNotCheck, outcome)
        assertFalse(only().isPlaceholder)
        // The picture looked fine, so it is recorded as the picture of a fresh download is.
        assertEquals(DownloadFramesCheck.Passed, only().framesCheck)
        assertEquals(DownloadVerification.Size, only().verifiedBy)
    }

    @Test
    fun a_short_film_with_a_still_picture_is_not_marked() {
        downloadRuntimeLookup = { 5 }
        backend.frames = blankFrames
        loadOld(subtitle = null, file = clip.copy(sizeBytes = 90_000_000L), totalBytes = 90_000_000L)

        val outcome = DownloadsRepository.recheckCompleted("a")

        assertFalse(outcome == RecheckOutcome.Flagged)
        assertFalse(only().isPlaceholder)
        // Its running time is five minutes: the file is not short for it, so no picture is taken.
        assertEquals(emptyList(), backend.sampled)
    }

    @Test
    fun a_download_with_nothing_to_compare_is_left_as_it_is() {
        loadOld(subtitle = null)

        val outcome = DownloadsRepository.recheckCompleted("a")

        assertEquals(RecheckOutcome.CouldNotCheck, outcome)
        assertFalse(only().isPlaceholder)
        assertEquals(DownloadFramesCheck.NotChecked, only().framesCheck)
        assertEquals(emptyList(), backend.sampled)
    }

    @Test
    fun a_small_file_in_line_with_its_claim_is_not_marked() {
        loadOld(
            subtitle = "[💾 9 MB] Short.mkv",
            file = clip,
        )

        assertFalse(DownloadsRepository.recheckCompleted("a") == RecheckOutcome.Flagged)
        assertFalse(only().isPlaceholder)
    }

    @Test
    fun a_file_that_is_not_there_is_skipped_without_changing_the_record() {
        loadOld()
        // The drive is gone after the list was loaded.
        backend.files.remove(uri)
        val before = only()

        assertEquals(RecheckOutcome.Unavailable, DownloadsRepository.recheckCompleted("a"))

        assertEquals(before, only())
    }

    @Test
    fun checking_a_marked_download_again_clears_the_mark_when_it_no_longer_applies() {
        // The file at the place of the marked one is now the film.
        loadOld(
            extra = ""","expectedSizeBytes":9530000000""", framesCheck = "NotTheRealVideo", totalBytes = 9_400_000_000L,
            file = DownloadedFileFacts(exists = true, sizeBytes = 9_400_000_000L, isVideoContainer = true),
        )
        assertTrue(only().isPlaceholder)

        val outcome = DownloadsRepository.recheckCompleted("a")

        assertEquals(RecheckOutcome.LooksRight, outcome)
        assertFalse(only().isPlaceholder)
        assertNull(only().errorMessage)
        assertEquals(1, DownloadsRepository.uiState.value.completedItems.size)
    }

    // Replacing a marked download

    @Test
    fun choosing_another_source_moves_the_marked_file_to_the_trash_and_does_not_delete_it() {
        loadOld()
        DownloadsRepository.recheckCompleted("a")

        // The user picked a source for the title: that is a request to replace.
        enqueueMovie(stream("https://example.com/other.mkv", description = null), userChoseReplacement = true)

        assertEquals(listOf(uri), backend.trashed)
        assertEquals(emptyList(), backend.removed)
        assertEquals(listOf("https://example.com/other.mkv"), backend.started)
        assertEquals(DownloadStatus.Downloading, only().status)
    }

    // Automatic downloads and delete-after-watching

    @Test
    fun the_keep_rule_fetches_a_real_copy_and_does_not_count_a_marked_file() {
        val marked = testDownload(1, DownloadStatus.Completed, localFileUri = uri).copy(
            downloadedBytes = 3_000_000_000L,
            framesCheck = DownloadFramesCheck.NotTheRealVideo,
        )
        val fine = testDownload(2, DownloadStatus.Completed, localFileUri = uri)

        val plan = planAutoDownload(testRule(keepCount = 2), listOf(testEpisode(1), testEpisode(2)), { false }, listOf(marked, fine))

        assertEquals(listOf(1), plan.toDownload.map { it.episodeNumber })
        assertEquals(listOf(1, 2), plan.window.map { it.episodeNumber })
    }

    @Test
    fun a_marked_file_is_not_recorded_as_an_episode_the_rule_owns() {
        val store = AutoDownloadStore(MemoryAutoDownloadPayloads()) { 1 }
        val rule = testRule(keepCount = 2)
        store.upsert(1, rule)
        val engine = AutoDownloadEngine(
            store = store,
            loadEpisodes = { emptyList() },
            isWatched = { _, _ -> false },
            downloads = { emptyList() },
            seasonDefaults = { SeasonDownloadDefaults() },
            startBatch = { SeasonBatchStart.NothingToDownload },
            cancelBatch = { _, _ -> },
            nowEpochMs = { 0L },
        )
        val marked = testDownload(1, DownloadStatus.Completed, ruleKey = rule.key).copy(framesCheck = DownloadFramesCheck.NotTheRealVideo)
        val fine = testDownload(2, DownloadStatus.Completed, ruleKey = rule.key)

        engine.recordOwnedEpisodes(1, listOf(marked, fine))

        assertEquals(setOf("1|2"), store.rule(1, rule.key)?.ownedEpisodeKeys)
    }

    @Test
    fun a_movie_set_does_not_count_a_marked_movie_as_in_downloads() {
        val marked = testDownload(1, DownloadStatus.Completed).copy(
            seasonNumber = null, episodeNumber = null, parentMetaId = "tt5", downloadedBytes = 3_000_000_000L,
            framesCheck = DownloadFramesCheck.NotTheRealVideo,
        )
        val movies = listOf(com.nuvio.app.features.home.MetaPreview(id = "tt5", type = "movie", name = "Film"))

        val entries = movieSetEntries(movies, listOf(marked), todayIsoDate = "2026-11-12", nowEpochMs = 0L)

        assertEquals(MovieSetEntryState.Available, entries.single().state)
    }

    // Findings of the review: a stored file is never removed or trashed by anything automatic

    @Test
    fun an_automatic_fetch_for_a_marked_episode_keeps_the_marked_file_and_its_record() {
        loadOld()
        DownloadsRepository.recheckCompleted("a")

        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = "tt1", parentMetaId = "tt1", parentMetaType = "movie", title = "Some Title",
            logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null, episodeTitle = null,
            episodeThumbnail = null, stream = stream("https://example.com/other.mkv", description = null),
            autoDownloadRuleKey = "movie|tt1",
        )

        assertEquals(emptyList(), backend.trashed)
        assertEquals(emptyList(), backend.removed)
        assertTrue(uri in backend.files)
        assertEquals(2, items().size)
        val marked = items().single { it.id == "a" }
        assertTrue(marked.isPlaceholder)
        assertEquals(uri, marked.localFileUri)
        val fresh = items().single { it.id != "a" }
        assertTrue(fresh.fileName != marked.fileName, "the new copy has its own file name")
        assertEquals(DownloadStatus.Downloading, fresh.status)
    }

    @Test
    fun the_keep_rule_does_not_fetch_again_once_the_new_copy_is_there() {
        val marked = testDownload(1, DownloadStatus.Completed, localFileUri = uri, id = "old").copy(
            framesCheck = DownloadFramesCheck.NotTheRealVideo,
        )
        val fresh = testDownload(1, DownloadStatus.Completed, localFileUri = "file:/new.mp4", id = "new")

        val plan = planAutoDownload(testRule(keepCount = 1), listOf(testEpisode(1)), { false }, listOf(fresh, marked))

        assertEquals(emptyList(), plan.toDownload.map { it.episodeNumber })
    }

    @Test
    fun the_keep_rule_sees_the_new_copy_whatever_the_order_of_the_list() {
        val marked = testDownload(1, DownloadStatus.Completed, localFileUri = uri, id = "old").copy(
            framesCheck = DownloadFramesCheck.NotTheRealVideo,
        )
        val downloading = testDownload(1, DownloadStatus.Downloading, id = "new")

        val plan = planAutoDownload(testRule(keepCount = 1), listOf(testEpisode(1)), { false }, listOf(downloading, marked))
        val reversed = planAutoDownload(testRule(keepCount = 1), listOf(testEpisode(1)), { false }, listOf(marked, downloading))

        assertEquals(emptyList(), plan.toDownload.map { it.episodeNumber })
        assertEquals(emptyList(), reversed.toDownload.map { it.episodeNumber })
    }

    // Findings of the review: a claim must describe the selected file

    @Test
    fun a_structured_size_of_a_whole_torrent_is_not_a_claim_for_the_selected_file() {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:1", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 1, episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "RD+ 1080p", description = "Show.S01.1080p", url = "https://example.com/e1.mkv", addonName = "A", addonId = "a",
                infoHash = "abcdef0123456789abcdef0123456789abcdef01", fileIdx = 3,
                behaviorHints = StreamBehaviorHints(videoSize = 30_000_000_000L),
            ),
        )

        assertNull(only().expectedSizeBytes)
    }

    @Test
    fun a_genuine_single_file_claim_on_an_episode_is_kept() {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:1", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 1, episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "RD+ 1080p", description = "Show.S01E01.1080p.mkv", url = "https://example.com/e1.mkv", addonName = "A", addonId = "a",
                behaviorHints = StreamBehaviorHints(videoSize = 1_000_000_000L),
            ),
        )

        assertEquals(1_000_000_000L, only().expectedSizeBytes)
    }

    @Test
    fun a_season_sized_claim_does_not_fail_a_genuine_episode_at_the_start() {
        backend.onStart = { _, progress, _ -> progress(0L, 1_000_000_000L) }
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "tt1:1:1", parentMetaId = "tt1", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 1, episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "RD+ 1080p", description = "Show Season 1 \u2014 30 GB", url = "https://example.com/e1.mkv", addonName = "A", addonId = "a",
                behaviorHints = StreamBehaviorHints(videoSize = 30_000_000_000L),
            ),
        )

        assertEquals(DownloadStatus.Downloading, only().status)
    }

    // Findings of the review: a complete video of a low bitrate is not a placeholder

    @Test
    fun a_complete_low_bitrate_video_is_not_marked_by_its_bitrate_on_a_recheck() {
        downloadRuntimeLookup = { 60 }
        // 60 minutes in 40 MB is about 89 kbit/s, and the file's own length matches the title.
        loadOld(
            subtitle = null,
            file = DownloadedFileFacts(exists = true, sizeBytes = 40_000_000L, isVideoContainer = true, durationMs = 3_600_000L),
            totalBytes = 40_000_000L,
        )

        assertFalse(DownloadsRepository.recheckCompleted("a") == RecheckOutcome.Flagged)
        assertFalse(only().isPlaceholder)
    }

    @Test
    fun a_total_of_a_low_bitrate_video_does_not_end_the_transfer_at_the_start() {
        downloadRuntimeLookup = { 60 }
        backend.onStart = { _, progress, _ -> progress(0L, 40_000_000L) }

        enqueueMovie(stream("https://example.com/a.mkv", description = null))

        assertEquals(DownloadStatus.Downloading, only().status)
    }

    @Test
    fun the_new_copy_replaces_the_marked_file_only_when_the_user_deletes_it_to_the_trash() {
        loadOld()
        DownloadsRepository.recheckCompleted("a")
        enqueueMovie(stream("https://example.com/other.mkv", description = null))
        assertEquals(emptyList(), backend.trashed)

        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload("a"))

        assertEquals(listOf(uri), backend.trashed)
        assertEquals(1, items().size)
    }

    @Test
    fun a_season_sized_claim_is_not_plausible_for_one_episode_and_a_genuine_one_is() {
        assertFalse(isPlausibleForOneEpisode(30_000_000_000L, 45))
        assertTrue(isPlausibleForOneEpisode(1_000_000_000L, 45))
        assertTrue(isPlausibleForOneEpisode(10_000_000_000L, 45))
        assertFalse(isPlausibleForOneEpisode(30_000_000_000L, null))
        assertTrue(isPlausibleForOneEpisode(1_000_000_000L, null))
    }

    @Test
    fun a_size_kept_by_an_earlier_build_that_is_too_large_for_an_episode_is_not_a_claim() {
        val episode = testDownload(1, DownloadStatus.Completed).copy(expectedSizeBytes = 30_000_000_000L)

        assertNull(episode.claimedSizeBytes(45))
        assertEquals(1_000_000_000L, episode.copy(expectedSizeBytes = 1_000_000_000L).claimedSizeBytes(45))
        assertNull(episode.copy(expectedSizeBytes = 1_000_000_000L, streamSubtitle = "Show Season 1").claimedSizeBytes(45))
    }
}
