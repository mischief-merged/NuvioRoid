package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.RandomAccessFile
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadProbeBudgetTest {
    @get:Rule val folder = TemporaryFolder()
    private class MemoryBackend : DownloadsBackend {
        var payload: String? = null
        val writes = AtomicInteger()
        val facts = ConcurrentHashMap<String, DownloadedFileFacts>()
        val finishes = ConcurrentHashMap<Int, (String, Long?) -> Unit>()
        val requests = CopyOnWriteArrayList<DownloadPlatformRequest>()
        val sampled = CopyOnWriteArrayList<String>()
        val confirming = CopyOnWriteArrayList<String>()
        val probeThreads = CopyOnWriteArrayList<Thread>()
        val removed = CopyOnWriteArrayList<String?>()
        val partialRemovals = AtomicInteger()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        var duringSample: () -> Unit = {}
        var duringConfirm: () -> Unit = {}
        var away = false
        override fun heldByFolder(item: DownloadItem) = away
        var blank = false
        var throws = false
        var onInspect: () -> Unit = {}
        var onRemove: () -> Unit = {}
        override fun loadPayload() = payload
        override fun savePayload(payload: String) { writes.incrementAndGet(); this.payload = payload }
        override fun restoreItem(item: DownloadItem) = item
        override fun removeFile(localFileUri: String?): Boolean { onRemove(); removed += localFileUri; return true }
        override fun removePartialFile(destinationFileName: String): Boolean { partialRemovals.incrementAndGet(); return true }
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String) = localFileUri
        override fun inspectFile(localFileUri: String): DownloadedFileFacts? { onInspect(); return facts[localFileUri] }
        override fun start(request: DownloadPlatformRequest, onProgress: (Long, Long?) -> Unit,
            onSuccess: (String, Long?) -> Unit, onFailure: (String) -> Unit, onPaused: () -> Unit): DownloadsTaskHandle {
            requests += request
            finishes[request.item.episodeNumber!!] = onSuccess
            onProgress(150_000_000L, 300_000_000L)
            return object : DownloadsTaskHandle { override fun cancel() = Unit }
        }
        private fun look(action: () -> Unit): FrameSamples {
            probeThreads += Thread.currentThread()
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
                action()
                if (throws) error("could not look")
                return FrameSamples(2_700_000L, FrameSamplePositions.mapIndexed { index, (fraction, window) ->
                    FrameSample((2_700_000 * fraction).toLong(), window,
                        ByteArray(FramePixelCount) { if (blank) 0 else ((it * 3 + index * 40) % 256).toByte() })
                })
            } finally { active.decrementAndGet() }
        }
        override fun sampleFrames(localFileUri: String): FrameSamples {
            sampled += localFileUri
            return look(duringSample)
        }
        override fun sampleConfirmingFrames(localFileUri: String, durationMs: Long): FrameSamples {
            confirming += localFileUri
            return look(duringConfirm)
        }
    }

    private val backend = MemoryBackend()
    private val savedBackend = DownloadsRepository.backend
    private val savedRuntime = downloadRuntimeLookup
    private val workers = CopyOnWriteArrayList<Thread>()
    private val release = CountDownLatch(1)
    private val video = DownloadedFileFacts(exists = true, sizeBytes = 300_000_000L, isVideoContainer = true, durationMs = 2_700_000L)
    private fun uri(episode: Int) = "file:/downloads/e$episode.mkv"
    private fun item(episode: Int) = DownloadsRepository.uiState.value.items.single { it.episodeNumber == episode }
    private fun stream(episode: Int) = StreamItem(name = "1080p", url = "https://example.com/e$episode.mkv", addonName = "test", addonId = "test")
    private fun enqueue(episode: Int) = DownloadsRepository.enqueueFromStream(
        contentType = "series", videoId = "show:1:$episode", parentMetaId = "show", parentMetaType = "series", title = "Show",
        logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = episode,
        episodeTitle = null, episodeThumbnail = null, stream = stream(episode),
    )
    private fun notPresent(episode: Int) = !backend.finishes.containsKey(episode)
    private fun finish(episode: Int) = backend.finishes.getValue(episode)(uri(episode), video.sizeBytes)
    private fun worker(block: () -> Unit): Thread = thread(isDaemon = true, block = block).also { workers += it }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(10, TimeUnit.SECONDS), "work did not reach the checkpoint")

    @BeforeTest fun setup() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        downloadRuntimeLookup = { 45 }
        DownloadsRepository.ensureLoaded()
    }
    @AfterTest fun restore() {
        release.countDown()
        workers.forEach { it.join(10_000) }
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = savedBackend
        downloadRuntimeLookup = savedRuntime
        assertTrue(workers.none { it.isAlive })
    }


    @Test fun a_matroska_episode_with_segment_duration_finishes_without_a_probe() {
        val file = folder.newFile("episode.mkv")
        RandomAccessFile(file, "rw").use { it.write(matroskaEpisode()); it.setLength(300_000_000L) }
        val facts = inspectDownloadedFile(file.toURI().toString())!!
        assertEquals(2_700_000L, facts.durationMs)
        enqueue(1); backend.facts[uri(1)] = facts; finish(1)
        assertEquals(0, backend.sampled.size)
        assertEquals(DownloadStatus.Completed, item(1).status)
    }

    @Test fun pausing_a_check_clears_its_marker_and_resume_reuses_the_published_file() {
        enqueue(1); backend.facts[uri(1)] = video.copy(durationMs = null)
        val entered = CountDownLatch(1)
        backend.duringSample = { entered.countDown(); await(release) }
        val first = worker { finish(1) }
        await(entered)
        val id = item(1).id
        DownloadsRepository.pauseDownload(id)
        assertTrue(DownloadsRepository.uiState.value.checkingIds.isEmpty())
        assertEquals(uri(1), item(1).localFileUri)
        DownloadsRepository.resumeDownload(id)
        release.countDown(); first.join(10_000)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (item(1).status != DownloadStatus.Completed && System.nanoTime() < deadline) Thread.yield()
        assertEquals(DownloadStatus.Completed, item(1).status)
        assertEquals(1, backend.requests.size)
        assertEquals(300_000_000L, item(1).downloadedBytes)
        assertEquals(0, backend.partialRemovals.get())
    }

    @Test fun rejecting_a_source_during_its_check_clears_the_marker_and_keeps_the_published_file() {
        enqueue(1); backend.facts[uri(1)] = video.copy(durationMs = null)
        val entered = CountDownLatch(1)
        backend.duringSample = { entered.countDown(); await(release) }
        val first = worker { finish(1) }
        await(entered)
        val id = item(1).id
        assertTrue(id in DownloadsRepository.uiState.value.checkingIds)

        DownloadsRepository.rejectBatchSource(id)

        assertTrue(DownloadsRepository.uiState.value.checkingIds.isEmpty())
        assertEquals(DownloadStatus.Failed, item(1).status)
        assertEquals(DownloadFailureKind.BadFile, item(1).failureKind)
        assertEquals(uri(1), item(1).localFileUri)
        val rejected = item(1)
        release.countDown(); first.join(10_000)
        assertFalse(first.isAlive)
        assertEquals(rejected, item(1), "a late check changed the rejected source")
        assertEquals(rejected, DownloadsCodec.decode(backend.payload!!)!!.items.single())
        assertEquals(emptyList(), backend.removed)
        assertEquals(0, backend.partialRemovals.get())
    }

    @Test fun a_published_file_waiting_for_a_probe_survives_reload_without_a_transfer() {
        enqueue(1)
        backend.requests.single().onDigestVerified()
        backend.facts[uri(1)] = video.copy(durationMs = null)
        val entered = CountDownLatch(1)
        backend.duringSample = { entered.countDown(); await(release) }
        val first = worker { finish(1) }
        await(entered)
        val stored = DownloadsCodec.decode(backend.payload!!)!!.items.single()
        assertEquals(uri(1), stored.localFileUri)
        assertTrue(stored.awaitingCheck)
        assertTrue(stored.publishedDigestVerified)
        DownloadsRepository.clearLocalState()
        backend.duringSample = {}
        DownloadsRepository.ensureLoaded()
        release.countDown(); first.join(10_000)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (item(1).status != DownloadStatus.Completed && System.nanoTime() < deadline) Thread.yield()
        assertEquals(DownloadStatus.Completed, item(1).status)
        assertEquals(DownloadVerification.Digest, item(1).verifiedBy)
        assertFalse(item(1).awaitingCheck)
        assertEquals(1, backend.requests.size)
        assertEquals(300_000_000L, item(1).downloadedBytes)
        assertTrue(backend.probeThreads.none { it === Thread.currentThread() })
        assertEquals(0, backend.partialRemovals.get())
    }

    @Test fun a_published_file_on_an_unavailable_drive_waits_without_losing_its_address() {
        enqueue(1); backend.facts[uri(1)] = video.copy(durationMs = null)
        val entered = CountDownLatch(1)
        backend.duringSample = { entered.countDown(); await(release) }
        val first = worker { finish(1) }; await(entered)
        val id = item(1).id
        DownloadsRepository.pauseDownload(id)
        release.countDown(); first.join(10_000)
        backend.away = true
        backend.facts[uri(1)] = DownloadedFileFacts(exists = false)
        DownloadsRepository.resumeDownload(id)
        assertEquals(DownloadStatus.Paused, item(1).status)
        assertTrue(item(1).pausedByFolder)
        assertTrue(item(1).awaitingCheck)
        assertEquals(uri(1), item(1).localFileUri)
        assertEquals(300_000_000L, item(1).downloadedBytes)
        assertEquals(1, backend.sampled.size)
        assertEquals(emptyList(), backend.removed)
        backend.away = false
        backend.facts[uri(1)] = video.copy(durationMs = null)
        DownloadsRepository.resumeHeldByFolder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (item(1).status != DownloadStatus.Completed && System.nanoTime() < deadline) Thread.yield()
        assertEquals(DownloadStatus.Completed, item(1).status)
        assertEquals(1, backend.requests.size)
        assertEquals(2, backend.sampled.size)
        assertEquals(0, backend.partialRemovals.get())
    }

    @Test fun moving_a_rejected_file_does_not_hold_the_state_lock() {
        enqueue(1); backend.facts[uri(1)] = video.copy(isVideoContainer = false)
        backend.onRemove = {
            val acquired = CountDownLatch(1)
            worker { DownloadsRepository.holdStateLockForTest { acquired.countDown() } }
            await(acquired)
        }
        finish(1)
        assertEquals(DownloadStatus.Failed, item(1).status)
        assertEquals(listOf<String?>(uri(1)), backend.removed)
    }

    @Test fun cancelling_a_waiting_check_frees_its_worker_before_the_active_probe_finishes() {
        for (ep in 1..2) { enqueue(ep); backend.facts[uri(ep)] = video.copy(durationMs = null) }
        val entered = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        backend.duringSample = { entered.countDown(); await(release) }
        val first = worker { finish(1) }; await(entered)
        backend.onInspect = { waiting.countDown() }
        val second = worker { finish(2) }; await(waiting)
        DownloadsRepository.pauseDownload(item(2).id)
        second.join(10_000)
        assertFalse(second.isAlive, "a cancelled waiter kept its worker")
        assertFalse(item(2).id in DownloadsRepository.uiState.value.checkingIds)
        assertEquals(1, backend.sampled.size)
        assertEquals(uri(2), item(2).localFileUri)
        release.countDown(); first.join(10_000)
    }

    @Test fun an_earlier_finish_gets_the_permit_before_a_later_recheck() {
        for (ep in 1..3) {
            enqueue(ep); backend.facts[uri(ep)] = video
            if (ep == 3) finish(ep)
            backend.facts[uri(ep)] = video.copy(durationMs = null)
        }
        val entered = CountDownLatch(1)
        backend.duringSample = { entered.countDown(); await(release) }
        val first = worker { finish(1) }; await(entered)
        val inspected = CountDownLatch(1)
        backend.onInspect = { inspected.countDown() }
        val second = worker { finish(2) }; await(inspected)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (second.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING) && System.nanoTime() < deadline) Thread.yield()
        assertTrue(second.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING))
        backend.onInspect = {}
        val third = worker { DownloadsRepository.recheckCompleted(item(3).id) }
        release.countDown()
        listOf(first, second, third).forEach { it.join(10_000) }
        assertEquals(listOf(uri(1), uri(2), uri(3)), backend.sampled)
        assertEquals(1, backend.peak.get())
    }

    @Test fun a_matroska_title_can_flag_the_wrong_episode_without_decoding_pictures() {
        val file = folder.newFile("other.mkv")
        RandomAccessFile(file, "rw").use { it.write(matroskaEpisode("Show S01E02")); it.setLength(300_000_000L) }
        enqueue(1); backend.facts[uri(1)] = inspectDownloadedFile(file.toURI().toString())!!; finish(1)
        assertEquals(0, backend.sampled.size)
        assertEquals(DownloadFramesCheck.Suspect, item(1).framesCheck)
        assertEquals(DownloadStatus.Completed, item(1).status)
        DownloadsRepository.recheckCompleted(item(1).id)
        assertEquals(DownloadFramesCheck.Suspect, item(1).framesCheck)
        assertEquals(0, backend.sampled.size)
    }

    @Test fun publication_saves_its_identity_before_the_finish_check() {
        enqueue(1); backend.facts[uri(1)] = video
        val writesBefore = backend.writes.get()
        assertTrue(backend.requests.single().onPublished(uri(1), video.sizeBytes, "published identity"))
        val stored = DownloadsCodec.decode(backend.payload!!)!!.items.single()
        assertEquals("published identity", stored.publishedFileKey)
        assertEquals(uri(1), stored.localFileUri)
        assertTrue(stored.awaitingCheck)
        finish(1)
        assertEquals(2, backend.writes.get() - writesBefore)
        assertEquals(DownloadStatus.Completed, item(1).status)
        assertEquals(null, item(1).publishedFileKey)
        assertEquals(0, backend.sampled.size)
    }

    @Test fun a_normal_episode_finishes_with_zero_frame_probes() {
        enqueue(1); backend.facts[uri(1)] = video
        val writesBefore = backend.writes.get()
        finish(1)
        // No frame look, so the completed row and its file address are one save, after the row is shown.
        assertEquals(1, backend.writes.get() - writesBefore)
        val stored = DownloadsCodec.decode(backend.payload!!)!!.items.single()
        assertEquals(DownloadStatus.Completed, stored.status)
        assertEquals(uri(1), stored.localFileUri)
        assertEquals(DownloadStatus.Completed, item(1).status)
        assertEquals(0, backend.sampled.size)
        assertEquals(DownloadFramesCheck.NotChecked, item(1).framesCheck)
        assertEquals(0, backend.confirming.size)
        assertEquals(DownloadVerification.Size, item(1).verifiedBy)
        assertEquals(300_000_000L, item(1).downloadedBytes)
        assertEquals(0, backend.partialRemovals.get())
        assertEquals(emptyList(), backend.removed)
    }

    @Test fun an_unknown_runtime_does_not_spend_a_probe() {
        downloadRuntimeLookup = { null }
        enqueue(1); backend.facts[uri(1)] = video.copy(durationMs = null); finish(1)
        assertEquals(0, backend.sampled.size)
        assertEquals(DownloadStatus.Completed, item(1).status)
    }

    @Test fun a_decisively_small_file_does_not_spend_a_probe() {
        enqueue(1); backend.facts[uri(1)] = video.copy(sizeBytes = 100_000L, durationMs = null)
        backend.finishes.getValue(1)(uri(1), 100_000L)
        assertEquals(DownloadStatus.Failed, item(1).status)
        assertEquals(0, backend.sampled.size)
    }

    @Test fun a_missing_duration_spends_one_probe_and_records_a_pass() {
        enqueue(1); backend.facts[uri(1)] = video.copy(durationMs = null); finish(1)
        assertEquals(1, backend.sampled.size)
        assertEquals(0, backend.confirming.size)
        assertEquals(DownloadFramesCheck.Passed, item(1).framesCheck)
    }

    @Test fun probe_failure_keeps_the_file_and_releases_the_permit() {
        backend.throws = true
        enqueue(1); backend.facts[uri(1)] = video.copy(durationMs = null); finish(1)
        backend.throws = false
        enqueue(2); backend.facts[uri(2)] = video.copy(durationMs = null); finish(2)
        assertEquals(2, backend.sampled.size)
        assertEquals(DownloadFramesCheck.NotChecked, item(1).framesCheck)
        assertEquals(DownloadFramesCheck.Passed, item(2).framesCheck)
        assertEquals(emptyList(), backend.removed)
    }

    @Test fun an_old_finish_callback_does_not_clear_a_new_transfers_pending_check() {
        enqueue(1)
        val oldFinish = backend.finishes.getValue(1)
        DownloadsRepository.pauseDownload(item(1).id)
        DownloadsRepository.resumeDownload(item(1).id)
        val entered = CountDownLatch(1)
        backend.duringSample = { entered.countDown(); await(release) }
        backend.facts[uri(1)] = video.copy(durationMs = null)
        val current = worker { finish(1) }
        await(entered)
        oldFinish(uri(1), video.sizeBytes)
        assertTrue(item(1).id in DownloadsRepository.uiState.value.checkingIds)
        assertEquals(300_000_000L, item(1).downloadedBytes)
        assertFalse(backend.payload.orEmpty().contains("checkingIds"))
        release.countDown()
        current.join(10_000)
        assertTrue(DownloadsRepository.uiState.value.checkingIds.isEmpty())
        assertEquals(300_000_000L, item(1).downloadedBytes)
        assertEquals(DownloadStatus.Completed, item(1).status)
        assertEquals(1, backend.sampled.size)
        assertEquals(0, backend.partialRemovals.get())
    }

    @Test fun a_normal_episode_also_rechecks_with_zero_probes() {
        enqueue(1); backend.facts[uri(1)] = video; finish(1)
        assertEquals(RecheckOutcome.LooksRight, DownloadsRepository.recheckCompleted(item(1).id))
        assertEquals(0, backend.sampled.size)
        assertEquals(emptyList(), backend.removed)
    }

    @Test fun a_previously_passed_picture_is_not_probed_again_when_duration_is_missing() {
        enqueue(1); backend.facts[uri(1)] = video.copy(durationMs = null); finish(1)
        assertEquals(DownloadFramesCheck.Passed, item(1).framesCheck)
        DownloadsRepository.recheckCompleted(item(1).id)
        assertEquals(1, backend.sampled.size)
        assertEquals(0, backend.confirming.size)
    }

    @Test fun a_suspiciously_short_duration_still_gets_one_probe() {
        enqueue(1); backend.facts[uri(1)] = video.copy(durationMs = 60_000L); finish(1)
        assertEquals(1, backend.sampled.size)
        assertEquals(DownloadFramesCheck.Passed, item(1).framesCheck)
        assertEquals(DownloadStatus.Completed, item(1).status)
    }

    @Test fun finishes_and_rechecks_share_one_probe_including_confirmation() {
        // Two completed files need another look, while two new transfers finish.
        for (episode in 1..4) {
            enqueue(episode); backend.facts[uri(episode)] = video
            if (episode <= 2) {
                backend.throws = true
                finish(episode)
                backend.throws = false
            }
            backend.facts[uri(episode)] = video.copy(durationMs = null)
        }
        backend.sampled.clear()
        backend.probeThreads.clear()
        backend.peak.set(0)
        backend.blank = true
        val firstConfirm = CountDownLatch(1)
        backend.duringConfirm = { firstConfirm.countDown(); await(release) }
        val first = worker { finish(3) }
        await(firstConfirm)
        val inspected = CountDownLatch(3)
        backend.onInspect = { inspected.countDown() }
        val others = listOf(worker { finish(4) }, worker { DownloadsRepository.recheckCompleted(item(1).id) },
            worker { DownloadsRepository.recheckCompleted(item(2).id) })
        await(inspected)
        // Every contender has either reached the shared permit or entered the fake probe.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (others.any { it.state != Thread.State.BLOCKED && it.state != Thread.State.WAITING && it.state != Thread.State.TIMED_WAITING } && System.nanoTime() < deadline) Thread.yield()
        release.countDown()
        (others + first).forEach { it.join(10_000) }
        assertEquals(4, backend.sampled.size)
        assertEquals(4, backend.confirming.size)
        assertEquals(1, backend.peak.get())
        assertEquals(0, backend.active.get())
        assertEquals(4, backend.probeThreads.toSet().size)
        assertTrue(backend.probeThreads.none { it === Thread.currentThread() })
    }

    @Test fun a_season_starts_the_next_transfer_while_the_first_file_is_being_checked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        val streamLookups = AtomicInteger()
        val summary = CompletableDeferred<SeasonBatchSummary>()
        backend.duringSample = { entered.countDown(); await(release) }
        val downloader = SeasonBatchDownloader(
            scope = scope, downloads = DownloadsRepository.uiState, maxConcurrent = 1,
            fetchStreams = { if (streamLookups.incrementAndGet() == 2) nextStarted.countDown(); listOf(stream(it.episodeNumber)) },
            prepareStream = { stream, _ -> stream }, enqueue = { _, ep, _ -> enqueue(ep.episodeNumber) },
            minVideoBytes = { 1 }, onFinished = { _, result -> summary.complete(result) },
        )
        try {
            downloader.start(SeasonBatchRequest(contentType = "series", parentMetaId = "show", parentMetaType = "series", title = "Show",
                logo = null, poster = null, background = null, seasonNumber = 1,
                episodes = (1..2).map { SeasonBatchEpisode("show:1:$it", 1, it, "Episode $it", null) }, quality = BatchQuality.BestAvailable))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (backend.requests.isEmpty() && System.nanoTime() < deadline) Thread.yield()
            val firstEpisode = backend.requests.first().item.episodeNumber!!
            val secondEpisode = 3 - firstEpisode
            while (notPresent(firstEpisode) && System.nanoTime() < deadline) Thread.yield()
            backend.facts[uri(firstEpisode)] = video.copy(durationMs = null)
            worker { finish(firstEpisode) }
            await(entered)
            await(nextStarted)
            assertEquals(DownloadStatus.Downloading, item(firstEpisode).status)
            assertFalse(item(firstEpisode).isPlayable)
            assertFalse(summary.isCompleted)
            // Releasing transfer capacity must not let another search take over the checked title.
            val titleKey = item(firstEpisode).logicalContentKey
            assertTrue(titleKey in downloader.titlesInFlight.value)
            assertTrue(sourceReplacementSteps(
                listOf(item(firstEpisode).copy(status = DownloadStatus.Failed, failureKind = DownloadFailureKind.BadFile)),
                busyTitles = downloader.titlesInFlight.value,
            ).isEmpty())
            release.countDown()
            val queued = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (notPresent(secondEpisode) && System.nanoTime() < queued) Thread.yield()
            backend.facts[uri(secondEpisode)] = video; finish(secondEpisode)
            val result = withTimeout(10_000) { summary.await() }
            assertEquals(2, result.count(SeasonBatchEpisodeResult.Completed))
            assertEquals(2, backend.requests.size)
            assertEquals(1, backend.sampled.size)
            assertEquals(emptyList(), backend.removed)
            val settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (downloader.titlesInFlight.value.isNotEmpty() && System.nanoTime() < settled) Thread.yield()
            assertTrue(downloader.titlesInFlight.value.isEmpty())
        } finally { release.countDown(); scope.cancel() }
    }
}
