package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex

/**
 * When enabled (off by default) and downloads are over or waiting on a storage limit, moves
 * watched completed downloads to the Trash oldest-watched first until there is room.
 *
 * Never touches unwatched titles, mid-rewatch titles, titles marked Keep, playing titles,
 * downloading/paused/failed items, flagged placeholders, moving items, or unplugged locations.
 */
internal class MakeRoomAutomatically(
    private val store: AutoDownloadStore,
    private val downloads: () -> List<DownloadItem>,
    private val allDownloadBytes: () -> Long,
    private val autoDownloadBytes: () -> Long,
    private val totalStorageLimitBytes: () -> Long?,
    private val autoStorageLimitBytes: () -> Long?,
    private val watchStatusOf: (DownloadItem) -> DownloadWatchStatus,
    private val isPlaying: (DownloadItem) -> Boolean,
    private val isConnected: (DownloadItem) -> Boolean = { true },
    private val isBusyOrMoving: (DownloadItem) -> Boolean = { false },
    private val usesSystemTrash: () -> Boolean = { true },
    private val hasRuleWaitingForStorage: () -> Boolean = { false },
    private val removeDownload: (
        profileId: Int,
        downloadId: String,
        stillWanted: () -> Boolean,
        onCommitted: (DownloadItem, TrashedWatchedFile?) -> Boolean,
    ) -> DownloadsRepository.RemovedWatchedDownload = { profileId, id, stillWanted, onCommitted ->
        DownloadsRepository.cleanUpWatchedDownload(
            downloadId = id,
            allowHoldingWhenNoSystemTrash = false,
            expectedProfileId = profileId,
            stillWanted = stillWanted,
            onCommitted = onCommitted,
        )
    },
    private val resumeWaiting: () -> Unit = DownloadsRepository::resumeWaitingForStorageLimit,
    private val resumeDownload: (String) -> Unit = DownloadsRepository::resumeDownload,
    private val nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
) {
    private val mutex = Mutex()
    private val dirtyLock = SynchronizedObject()
    private var dirty = false

    /** Serialized pass: concurrent triggers coalesce instead of running in parallel. */
    suspend fun runIfNeeded(profileId: Int): Int {
        synchronized(dirtyLock) { dirty = true }
        if (!mutex.tryLock()) return 0
        var totalMoved = 0
        try {
            while (true) {
                synchronized(dirtyLock) { dirty = false }
                totalMoved += runOnce(profileId)
                val runAgain = synchronized(dirtyLock) { dirty }
                if (!runAgain) break
            }
        } finally {
            mutex.unlock()
        }
        return totalMoved
    }

    /** Direct synchronous pass for unit tests or callers already holding serialization. */
    fun runOnce(profileId: Int): Int {
        if (!store.isMakeRoomEnabled(profileId) || !usesSystemTrash()) return 0
        if (totalStorageLimitBytes() == null && autoStorageLimitBytes() == null) return 0

        // Start anything that already fits before moving any watched file to the Trash.
        resumeAllFittingWaitingDownloads()

        val candidates = downloads()
            .mapNotNull { item ->
                if (item.status != DownloadStatus.Completed || item.isPlaceholder || item.awaitingCheck) return@mapNotNull null
                if (!isConnected(item) || isBusyOrMoving(item) || isPlaying(item)) return@mapNotNull null
                if (store.isKept(profileId, item.id) || store.hasPendingKeep(profileId, item.id)) return@mapNotNull null
                val watch = watchStatusOf(item)
                if (!watch.isWatched) return@mapNotNull null
                CleanableWatchedDownload(
                    item = item,
                    sizeBytes = maxOf(item.downloadedBytes, item.totalBytes ?: 0L),
                    watchedAtEpochMs = watch.watchedAtEpochMs,
                )
            }
            .sortedWith(
                compareBy<CleanableWatchedDownload> { it.watchedAtEpochMs ?: Long.MAX_VALUE }
                    .thenBy { it.item.updatedAtEpochMs }
                    .thenBy { it.item.id },
            )
        if (candidates.isEmpty()) return 0

        var movedCount = 0
        for (index in candidates.indices) {
            if (!store.isMakeRoomEnabled(profileId) || !usesSystemTrash()) break
            val remaining = candidates.subList(index, candidates.size)
            val need = evaluateRoomNeeded(remaining)
            if (!need.needAll && !need.needAuto) break

            val candidate = candidates[index]
            val item = candidate.item
            // If only the automatic-download limit needs room, manual downloads cannot help free auto budget.
            if (!need.needAll && !item.isAutoOrTripItem()) continue

            val stillWanted = {
                store.isMakeRoomEnabled(profileId) &&
                    !isPlaying(item) &&
                    !store.hasPendingKeep(profileId, item.id) &&
                    !store.isKept(profileId, item.id) &&
                    watchStatusOf(item).isWatched
            }
            val commitUndo: (DownloadItem, TrashedWatchedFile?) -> Boolean = { removedItem, trashed ->
                val ruleKey = removedItem.autoDownloadRuleKey
                val season = removedItem.seasonNumber
                val episode = removedItem.episodeNumber
                store.commitWatchedRemoval(
                    profileId = profileId,
                    deleted = AutoDownloadDeleted(
                        title = removedItem.title,
                        seasonNumber = season,
                        episodeNumber = episode,
                        atEpochMs = nowEpochMs(),
                        downloadId = removedItem.id,
                        reason = DeletedDownloadReason.MadeRoom,
                        restorableItem = removedItem,
                        trashed = trashed,
                    ),
                    ruleKey = ruleKey,
                    ownedEpisodeKey = if (ruleKey != null && season != null && episode != null) {
                        autoDownloadEpisodeKey(season, episode)
                    } else {
                        null
                    },
                    droppedDownloadId = removedItem.id,
                )
            }
            val removed = removeDownload(profileId, item.id, stillWanted, commitUndo)
            if (removed.outcome.result == AutoDownloadRemovalResult.Removed) {
                movedCount++
                // Resume any waiting downloads that now fit so live byte counts reflect them before the next check.
                resumeAllFittingWaitingDownloads()
            }
        }

        return movedCount
    }

    private data class RoomNeed(
        val needAll: Boolean,
        val needAuto: Boolean,
    )

    private fun evaluateRoomNeeded(remainingCandidates: List<CleanableWatchedDownload>): RoomNeed {
        val totalLimit = totalStorageLimitBytes()
        val autoLimit = autoStorageLimitBytes()
        if (totalLimit == null && autoLimit == null) return RoomNeed(needAll = false, needAuto = false)

        val liveItems = downloads()
        val liveIds = liveItems.mapTo(HashSet()) { it.id }
        val activeCleanable = remainingCandidates.filter { it.item.id in liveIds }
        val freeableAll = activeCleanable.sumOf { it.sizeBytes }
        val freeableAuto = activeCleanable.filter { it.item.isAutoOrTripItem() }.sumOf { it.sizeBytes }

        val usedAll = allDownloadBytes()
        val usedAuto = autoDownloadBytes()
        val pausedTripReservation = liveItems
            .filter { it.tripFill && it.status == DownloadStatus.Paused && it.pausedByConnection && isConnected(it) }
            .sumOf { (it.requiredBytes() - it.downloadedBytes).coerceAtLeast(0L) }
        val onDiskOrRunningAuto = (usedAuto - pausedTripReservation).coerceAtLeast(0L)
        val ruleWaiting = hasRuleWaitingForStorage()

        val waitingItems = liveItems
            .filter {
                it.status == DownloadStatus.Paused &&
                    (it.autoDownloadStop == AutoDownloadStop.TotalStorageLimit || it.autoDownloadStop == AutoDownloadStop.StorageLimit) &&
                    !it.pausedByFolder &&
                    !it.pausedByConnection &&
                    !it.pausedByFreeSpace &&
                    isConnected(it) &&
                    !isBusyOrMoving(it)
            }
            .sortedWith(compareBy<DownloadItem> { it.createdAtEpochMs }.thenBy { it.id })

        var waitingNeedsAll = false
        var waitingNeedsAuto = false
        for (waiting in waitingItems) {
            val req = waiting.requiredBytes()
            val countedAll = waiting.countedInAllBytes()
            val countedAuto = waiting.countedInAutoBytes()
            val usedAllWithout = (usedAll - countedAll).coerceAtLeast(0L)
            val usedAutoWithout = (usedAuto - countedAuto).coerceAtLeast(0L)

            val blockedByTotal = totalLimit != null && (usedAllWithout >= totalLimit || usedAllWithout + req > totalLimit)
            val blockedByAuto = waiting.isAutoOrTripItem() && autoLimit != null &&
                (usedAutoWithout >= autoLimit || usedAutoWithout + req > autoLimit)
            if (!blockedByTotal && !blockedByAuto) continue

            val minAllAfterCleanup = (usedAllWithout - freeableAll).coerceAtLeast(0L)
            val minAutoAfterCleanup = (usedAutoWithout - freeableAuto).coerceAtLeast(0L)
            val canFitTotal = totalLimit == null || (minAllAfterCleanup < totalLimit && minAllAfterCleanup + req <= totalLimit)
            val canFitAuto = !waiting.isAutoOrTripItem() || autoLimit == null ||
                (minAutoAfterCleanup < autoLimit && minAutoAfterCleanup + req <= autoLimit)

            if (canFitTotal && canFitAuto) {
                if (blockedByTotal) waitingNeedsAll = true
                if (blockedByAuto) waitingNeedsAuto = true
            }
        }

        val needAll = totalLimit != null && freeableAll > 0L && (
            usedAll > totalLimit ||
                (ruleWaiting && usedAll >= totalLimit) ||
                waitingNeedsAll
            )
        val needAuto = autoLimit != null && freeableAuto > 0L && (
            onDiskOrRunningAuto > autoLimit ||
                (ruleWaiting && onDiskOrRunningAuto >= autoLimit) ||
                waitingNeedsAuto
            )
        return RoomNeed(needAll = needAll, needAuto = needAuto)
    }

    private fun resumeAllFittingWaitingDownloads() {
        resumeWaiting()
        val totalLimit = totalStorageLimitBytes()
        val autoLimit = autoStorageLimitBytes()
        val waitingAuto = downloads()
            .filter {
                it.status == DownloadStatus.Paused &&
                    it.autoDownloadStop == AutoDownloadStop.StorageLimit &&
                    it.isAutoOrTripItem() &&
                    !it.pausedByFolder &&
                    !it.pausedByConnection &&
                    !it.pausedByFreeSpace &&
                    isConnected(it) &&
                    !isBusyOrMoving(it)
            }
            .sortedWith(compareBy<DownloadItem> { it.createdAtEpochMs }.thenBy { it.id })
        for (candidate in waitingAuto) {
            val req = candidate.requiredBytes()
            val usedAllWithout = (allDownloadBytes() - candidate.countedInAllBytes()).coerceAtLeast(0L)
            val usedAutoWithout = (autoDownloadBytes() - candidate.countedInAutoBytes()).coerceAtLeast(0L)
            val fitsTotal = totalLimit == null || (usedAllWithout < totalLimit && usedAllWithout + req <= totalLimit)
            val fitsAuto = autoLimit == null || (usedAutoWithout < autoLimit && usedAutoWithout + req <= autoLimit)
            if (fitsTotal && fitsAuto) {
                resumeDownload(candidate.id)
            }
        }
    }

    private fun DownloadItem.isAutoOrTripItem(): Boolean = autoDownloadRuleKey != null || tripFill

    private fun DownloadItem.requiredBytes(): Long =
        maxOf(totalBytes ?: waitingSizeBytes ?: expectedSizeBytes ?: 0L, downloadedBytes)

    private fun DownloadItem.countedInAllBytes(): Long = downloadedBytes

    private fun DownloadItem.countedInAutoBytes(): Long =
        if (isAutoOrTripItem()) {
            if (tripFill && status == DownloadStatus.Paused && pausedByConnection) requiredBytes() else downloadedBytes
        } else {
            0L
        }
}
