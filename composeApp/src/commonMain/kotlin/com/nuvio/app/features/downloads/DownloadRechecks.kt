package com.nuvio.app.features.downloads

import com.nuvio.app.core.memory.BoundedCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What looking again at one finished download came to. */
internal enum class RecheckOutcome {
    /** Marked as not the real video. */
    Flagged,

    /** Compared with the claim and the running time, and in line with them. */
    LooksRight,

    /** There was nothing, or too little, to compare. The file is left as it is. */
    CouldNotCheck,

    /** The file could not be looked at, or is broken in another way that other checks deal with. */
    CouldNotLook,

    /** The file is not there, such as on a drive that is not connected. */
    Unavailable,

    /** The download is gone or no longer finished. */
    Skipped,
}

/** What one pass over finished downloads did. */
internal data class RecheckSummary(
    val looked: Int = 0,
    val flagged: Int = 0,
    val looksRight: Int = 0,
    val couldNotCheck: Int = 0,
    val unavailable: Int = 0,
    /** Left for a later pass because the file was open in the player. */
    val playing: Int = 0,
    /** The pass ended with finished downloads still waiting: it takes only so many at a time. */
    val moreWaiting: Boolean = false,
)

/** The local file the player has open, if any. Set by the player's hooks; passes that must leave a playing file alone read it. */
internal object PlayingLocalFile {
    val uri = kotlinx.atomicfu.atomic<String?>(null)
}

/**
 * Whether the background look is due for this download: a finished download that has no recorded
 * check at all (made before checks were recorded), or whose source's text names a size that was
 * never compared with the file. A download already marked is decided; "Check again" revisits it.
 */
internal fun DownloadItem.needsRecheck(): Boolean =
    status == DownloadStatus.Completed && !isPlaceholder &&
        (verifiedBy == null || (expectedSizeBytes == null && claimedSizeFromSourceText(streamTitle, streamSubtitle) != null))

/**
 * Looks again at finished downloads, quietly and a few at a time, with the limits of the pass that
 * saves the files beside a download ([DownloadCompanions]): the same number at the same time, the
 * same wait between groups, the same wait before a download that was looked at is looked at again,
 * and the same wait after the app starts. Everything it reads is on this computer; it runs off the
 * main thread, skips a file that is not there, and never looks at a file while it is playing. Only
 * one pass runs at a time. It never changes or removes a file.
 */
internal class DownloadRechecks(
    private val items: () -> List<DownloadItem>,
    /** Where the file of a download is now, or null when it is not there. */
    private val resolveLocalFileUri: (DownloadItem) -> String?,
    private val isPlaying: (DownloadItem) -> Boolean,
    /** Looks at one download and saves what it finds. Blocking. */
    private val recheck: (downloadId: String) -> RecheckOutcome,
    private val nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val running = Mutex()
    private val lookedAt = BoundedCache<String, Long>()
    private val lookedAtLock = kotlinx.atomicfu.locks.SynchronizedObject()

    /**
     * One pass over [candidates]: at most [maxItems] downloads, [DownloadCompanions.BackfillConcurrency]
     * at a time, with [pauseMs] between groups. A pass that is not [force]d takes only the downloads
     * that [needsRecheck] and were not looked at in the last [DownloadCompanions.BackfillRetryAfterMs].
     */
    suspend fun pass(
        candidates: List<DownloadItem> = items(),
        maxItems: Int = MaxPerPass,
        force: Boolean = false,
        pauseMs: Long = DownloadCompanions.BackfillPauseMs,
    ): RecheckSummary = running.withLock { withContext(io) { runPass(candidates, maxItems, force, pauseMs) } }

    private suspend fun runPass(candidates: List<DownloadItem>, maxItems: Int, force: Boolean, pauseMs: Long): RecheckSummary {
        var summary = RecheckSummary()
        val pending = ArrayDeque<DownloadItem>()
        for (item in candidates) {
            if (pending.size >= maxItems) {
                summary = summary.copy(moreWaiting = true)
                break
            }
            if (item.status != DownloadStatus.Completed) continue
            // A download a move or a delete holds, or whose location is away, waits for a later pass.
            if (runCatching { DownloadsRepository.isBusyOrAway(item) }.getOrDefault(true)) continue
            if (!force) {
                if (!item.needsRecheck()) continue
                val last = kotlinx.atomicfu.locks.synchronized(lookedAtLock) { lookedAt[item.id] }
                if (last != null && nowEpochMs() - last < DownloadCompanions.BackfillRetryAfterMs) continue
            }
            if (runCatching { resolveLocalFileUri(item) }.getOrNull() == null) {
                summary = summary.copy(unavailable = summary.unavailable + 1)
                continue
            }
            if (runCatching { isPlaying(item) }.getOrDefault(false)) {
                summary = summary.copy(playing = summary.playing + 1)
                continue
            }
            pending += item
        }
        while (pending.isNotEmpty()) {
            val batch = List(minOf(DownloadCompanions.BackfillConcurrency, pending.size)) { pending.removeFirst() }
            val outcomes = coroutineScope {
                batch.map { item ->
                    async {
                        // The file may have been opened since the scan: it is left for a later pass.
                        if (runCatching { isPlaying(item) }.getOrDefault(false)) {
                            item to null
                        } else {
                            item to runCatching { recheck(item.id) }
                                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                                .getOrDefault(RecheckOutcome.CouldNotLook)
                        }
                    }
                }.awaitAll()
            }
            for ((item, outcome) in outcomes) {
                when (outcome) {
                    null -> summary = summary.copy(playing = summary.playing + 1)
                    RecheckOutcome.Skipped -> Unit
                    RecheckOutcome.Unavailable -> summary = summary.copy(unavailable = summary.unavailable + 1)
                    else -> {
                        kotlinx.atomicfu.locks.synchronized(lookedAtLock) { lookedAt[item.id] = nowEpochMs() }
                        summary = summary.copy(
                            looked = summary.looked + 1,
                            flagged = summary.flagged + if (outcome == RecheckOutcome.Flagged) 1 else 0,
                            looksRight = summary.looksRight + if (outcome == RecheckOutcome.LooksRight) 1 else 0,
                            couldNotCheck = summary.couldNotCheck +
                                if (outcome == RecheckOutcome.CouldNotCheck || outcome == RecheckOutcome.CouldNotLook) 1 else 0,
                        )
                    }
                }
            }
            if (pending.isNotEmpty() && pauseMs > 0L) pause(pauseMs)
        }
        return summary
    }

    /** The user asked to check [item] again: looked at now, whatever was recorded before. */
    suspend fun checkAgain(item: DownloadItem): RecheckSummary =
        pass(listOf(item), maxItems = 1, force = true, pauseMs = 0L)

    /** The user asked to check every finished download, the marked ones too. */
    suspend fun checkAll(): RecheckSummary =
        pass(items().filter { it.status == DownloadStatus.Completed }, maxItems = Int.MAX_VALUE, force = true, pauseMs = 0L)

    /** The Downloads screen was opened: the downloads that are due and are on it now are looked at at once. */
    suspend fun checkOnScreen(shown: List<DownloadItem>): RecheckSummary =
        pass(shown, maxItems = ScreenMaxItems, pauseMs = 0L)

    /**
     * Waits [DownloadCompanions.BackfillStartDelayMs] so that startup and playback come first, then
     * works through the downloads that are due, [MaxPerPass] a pass, until none is left to look at.
     */
    suspend fun recheckWhenIdle() {
        delay(DownloadCompanions.BackfillStartDelayMs)
        repeat(MaxPasses) {
            val summary = pass()
            if (summary.looked == 0) return
            pause(PassIntervalMs)
        }
    }

    companion object {
        /** Most downloads one background pass looks at. A few: each may take seconds of decoding. */
        const val MaxPerPass = 6

        /** Most downloads looked at when the Downloads screen is opened. */
        const val ScreenMaxItems = 12

        /** Wait between one background pass and the next. */
        const val PassIntervalMs = 30_000L

        /** A bound on the passes of one start of the app. */
        const val MaxPasses = 200

        /** The one the app uses. */
        val app: DownloadRechecks by lazy {
            DownloadRechecks(
                items = { DownloadsRepository.uiState.value.items },
                resolveLocalFileUri = DownloadsRepository::playableLocalFileUri,
                isPlaying = { item ->
                    PlayingLocalFile.uri.value?.let { DownloadsRepository.findCompletedDownloadByLocalFile(it)?.id == item.id } == true
                },
                recheck = DownloadsRepository::recheckCompleted,
            )
        }
    }
}
