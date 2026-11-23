package com.nuvio.app.features.downloads

import com.nuvio.app.core.memory.BoundedCache
import com.nuvio.app.core.network.NetworkStatusRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Files saved beside a downloaded video so that it is complete offline, all inside the one sibling
 * folder `<video file name>.subtitles/`: the subtitles, skip times, next-episode details and
 * artwork. The rule that code moving a download between folders relies on: everything that belongs
 * to a video is in the folder named after its file plus `.subtitles`, next to it, and nowhere else.
 */
internal enum class CompanionOutcome {
    /** Something was saved. */
    Saved,

    /** The sources were asked and have nothing. */
    NothingFound,

    /** The sources could not be asked. Says nothing about the title. */
    Failed,

    /** The same file is being handled already. */
    Busy,
}

/** One kind of file saved beside a video. */
internal interface DownloadCompanion {
    val name: String

    /** Whether a lookup is worth making for this video now. Reads local files only. */
    fun isDue(item: DownloadItem, localVideoUri: String): Boolean

    /** Looks it up and saves it. Must never remove or damage what is saved. */
    suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome
}

/** What one background pass over the finished downloads did. */
internal data class CompanionBackfillSummary(
    val looked: Int = 0,
    val saved: Int = 0,
    val nothingFound: Int = 0,
    val failed: Int = 0,
    val unavailable: Int = 0,
    /** The pass ended early: the connection was lost, or the services kept failing. */
    val stoppedEarly: Boolean = false,
)

/** What the row of a finished download says about what is saved beside it. */
internal data class DownloadCompanionsNote(
    val skipTimes: SkipTimesNote? = null,
    val subtitleCount: Int = 0,
)

internal fun DownloadCompanions.Companion.noteFor(localVideoUri: String) = DownloadCompanionsNote(
    skipTimes = OfflineSkipTimes.app.note(localVideoUri),
    subtitleCount = DownloadSubtitles.savedCount(localVideoUri),
)

/** Saves the subtitles of a download. */
internal class SubtitlesCompanion(
    private val nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
) : DownloadCompanion {
    override val name: String get() = "subtitles"

    override fun isDue(item: DownloadItem, localVideoUri: String): Boolean =
        DownloadSubtitles.isDue(item, localVideoUri, nowEpochMs())

    override suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome {
        if (!DownloadCompanionGuard.stillWanted(localVideoUri)) return CompanionOutcome.Busy
        if (!isDue(item, localVideoUri)) return CompanionOutcome.Busy
        // The lock is taken around each write only, never while waiting for the network. Before every
        // write the video is checked again; when it is gone the save stops and nothing is created.
        val guarded: (() -> Unit) -> Boolean = { action ->
            SidecarLocks.withLock(localVideoUri) {
                if (DownloadCompanionGuard.stillWanted(localVideoUri)) { action(); true } else false
            }
        }
        val result = withTimeoutOrNull(DownloadCompanions.SubtitlesTimeoutMs) {
            runCatching { DownloadSubtitles.prepare(item, localVideoUri, recheck = true, nowEpochMs = nowEpochMs(), guardedWrite = guarded) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
                ?: SubtitleSaveResult(0, 0, 1)
        } ?: SubtitleSaveResult(0, 0, 1)
        return when {
            result.newlySaved > 0 -> CompanionOutcome.Saved
            result.unreachable > 0 -> CompanionOutcome.Failed
            else -> CompanionOutcome.NothingFound
        }
    }
}

/**
 * Runs the [companions] of a download: when it finishes, when it is played for the first time
 * online, and in a quiet background pass over the downloads that lack them. Every lookup needs a
 * connection; none ever removes what is saved.
 */
internal class DownloadCompanions(
    private val companions: List<DownloadCompanion>,
    private val isOnline: () -> Boolean = { NetworkStatusRepository.uiState.value.isOnline },
    private val nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = SynchronizedObject()
    private val failedAt = BoundedCache<String, Long>()
    private var backfillRunning = false

    /** Starts saving what a download that just finished lacks. Returns at once. */
    fun saveAfterDownload(item: DownloadItem, localVideoUri: String): Job =
        scope.launch { ensure(item, localVideoUri) }

    /** Saves what this video lacks, one kind after another. Does nothing offline. Returns the outcome of each that was due. */
    suspend fun ensure(item: DownloadItem, localVideoUri: String): Map<String, CompanionOutcome> = withContext(io) {
        if (!isOnline()) return@withContext emptyMap()
        val outcomes = linkedMapOf<String, CompanionOutcome>()
        for (companion in companions) {
            if (!isOnline()) break
            if (!companion.isDue(item, localVideoUri)) continue
            outcomes[companion.name] = runCatching { companion.run(item, localVideoUri) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(CompanionOutcome.Failed)
        }
        outcomes
    }

    /**
     * One quiet pass over finished downloads that lack something, a few at a time. Skips downloads
     * whose file is not there, such as one on a drive that is not connected. Stops when the
     * connection is lost, after [BackfillMaxPerPass] downloads, or when [BackfillStopAfterFailures]
     * in a row fail. A download that failed is not tried again for [BackfillRetryAfterMs]. Only one
     * pass runs at a time. One download is one lookup, however many kinds it lacks.
     */
    suspend fun backfill(items: List<DownloadItem>, resolveLocalFileUri: (DownloadItem) -> String?): CompanionBackfillSummary {
        val started = synchronized(lock) { if (backfillRunning) false else { backfillRunning = true; true } }
        if (!started) return CompanionBackfillSummary()
        try {
            // Every look at the disk happens off the main thread.
            return withContext(io) { runBackfill(items, resolveLocalFileUri) }
        } finally {
            synchronized(lock) { backfillRunning = false }
        }
    }

    private suspend fun runBackfill(items: List<DownloadItem>, resolveLocalFileUri: (DownloadItem) -> String?): CompanionBackfillSummary {
        var summary = CompanionBackfillSummary()
        var consecutiveFailures = 0
        val pending = ArrayDeque<Pair<DownloadItem, String>>()
        var scanStopped = false
        for (item in items) {
            // The scan stops once a pass has all it may do; the rest waits for the next pass.
            if (pending.size >= BackfillMaxPerPass) { scanStopped = true; break }
            if (item.status != DownloadStatus.Completed) continue
            // A download a move or a delete holds, or whose location is away, waits for a later pass.
            if (runCatching { DownloadsRepository.isBusyOrAway(item) }.getOrDefault(true)) continue
            val uri = runCatching { resolveLocalFileUri(item) }.getOrNull()
            if (uri == null) {
                summary = summary.copy(unavailable = summary.unavailable + 1)
                continue
            }
            if (companions.none { runCatching { it.isDue(item, uri) }.getOrDefault(false) }) continue
            val lastFailure = synchronized(lock) { failedAt[item.id] }
            if (lastFailure != null && nowEpochMs() - lastFailure < BackfillRetryAfterMs) continue
            pending += item to uri
        }
        while (pending.isNotEmpty()) {
            if (!isOnline() || summary.looked >= BackfillMaxPerPass || consecutiveFailures >= BackfillStopAfterFailures) {
                return summary.copy(stoppedEarly = true)
            }
            val room = BackfillMaxPerPass - summary.looked
            val batch = List(minOf(BackfillConcurrency, room, pending.size)) { pending.removeFirst() }
            val outcomes = coroutineScope {
                batch.map { (item, uri) -> async { item to ensure(item, uri).values } }.awaitAll()
            }
            for ((item, results) in outcomes) {
                if (results.isEmpty() || results.all { it == CompanionOutcome.Busy }) continue
                summary = summary.copy(looked = summary.looked + 1)
                when {
                    CompanionOutcome.Saved in results -> { summary = summary.copy(saved = summary.saved + 1); consecutiveFailures = 0 }
                    results.all { it == CompanionOutcome.Failed } -> {
                        summary = summary.copy(failed = summary.failed + 1)
                        consecutiveFailures++
                        synchronized(lock) { failedAt[item.id] = nowEpochMs() }
                    }
                    else -> { summary = summary.copy(nothingFound = summary.nothingFound + 1); consecutiveFailures = 0 }
                }
            }
            if (pending.isNotEmpty()) pause(BackfillPauseMs)
        }
        return summary.copy(stoppedEarly = summary.stoppedEarly || scanStopped)
    }

    /**
     * Passes one after another while some download still failed or the scan was cut short, waiting
     * [BackfillRetryAfterMs] between them, at most [BackfillMaxRounds]. A pass can fail only because
     * the details of a title were not ready yet at start-up; the next one finds them.
     */
    suspend fun backfillUntilDone(items: () -> List<DownloadItem>, resolveLocalFileUri: (DownloadItem) -> String?): CompanionBackfillSummary {
        var total = CompanionBackfillSummary()
        repeat(BackfillMaxRounds) { round ->
            val pass = backfill(items(), resolveLocalFileUri)
            total = CompanionBackfillSummary(
                looked = total.looked + pass.looked, saved = total.saved + pass.saved, nothingFound = total.nothingFound + pass.nothingFound,
                failed = pass.failed, unavailable = pass.unavailable, stoppedEarly = pass.stoppedEarly,
            )
            if (pass.failed == 0 && !pass.stoppedEarly) return total
            if (!isOnline() || round == BackfillMaxRounds - 1) return total
            pause(BackfillRetryAfterMs)
        }
        return total
    }

    companion object {
        /** Passes in a row at most, see [backfillUntilDone]. */
        const val BackfillMaxRounds = 4

        /** Most downloads one background pass looks at. */
        const val BackfillMaxPerPass = 30

        /** Downloads handled at the same time. */
        const val BackfillConcurrency = 2

        /** Wait between one group and the next. */
        const val BackfillPauseMs = 2_000L

        /** Wait after the app is online before the first pass, so that startup and playback come first. */
        const val BackfillStartDelayMs = 20_000L

        /** A pass ends after this many downloads in a row whose every lookup failed. */
        const val BackfillStopAfterFailures = 3

        /** A download whose lookups failed is not tried again by a pass for this long. */
        const val BackfillRetryAfterMs = 30L * 60 * 1000

        /** Saving the subtitles of one video is given up after this long. What was saved stays. */
        const val SubtitlesTimeoutMs = 120_000L

        /** The one the app uses. */
        val app: DownloadCompanions by lazy {
            DownloadCompanions(listOf(OfflineSkipTimes.app, SubtitlesCompanion(), EpisodeListCompanion(), ArtworkCompanion()))
        }
    }
}

/** Waits [DownloadCompanions.BackfillStartDelayMs], then fills in what finished downloads lack. */
internal suspend fun DownloadCompanions.backfillWhenIdle(): CompanionBackfillSummary {
    delay(DownloadCompanions.BackfillStartDelayMs)
    return backfillUntilDone({ DownloadsRepository.uiState.value.items }, DownloadsRepository::playableLocalFileUri)
}
