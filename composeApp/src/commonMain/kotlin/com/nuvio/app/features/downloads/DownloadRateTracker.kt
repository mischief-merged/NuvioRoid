package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Tracks recent transfer throughput per download over a sliding window so ETA stays accurate
 * across pauses, resumes and speed caps without disturbing [DownloadTraffic].
 */
internal class DownloadRateTracker(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minSpanMs: Long = DEFAULT_MIN_SPAN_MS,
    private val stallTimeoutMs: Long = DownloadStalledAfterMs,
) {
    private data class Sample(val timestampMs: Long, val bytes: Long)

    private val lock = SynchronizedObject()
    private val samplesById = mutableMapOf<String, ArrayDeque<Sample>>()

    fun recordStart(downloadId: String, initialDownloadedBytes: Long, nowEpochMs: Long) {
        synchronized(lock) {
            val deque = ArrayDeque<Sample>()
            deque.addLast(Sample(nowEpochMs, initialDownloadedBytes.coerceAtLeast(0L)))
            samplesById[downloadId] = deque
        }
    }

    fun recordProgress(downloadId: String, downloadedBytes: Long, nowEpochMs: Long) {
        val bytes = downloadedBytes.coerceAtLeast(0L)
        synchronized(lock) {
            val deque = samplesById.getOrPut(downloadId) { ArrayDeque() }
            val last = deque.lastOrNull()
            if (last == null) {
                deque.addLast(Sample(nowEpochMs, bytes))
                return
            }
            if (nowEpochMs < last.timestampMs) return
            if (bytes < last.bytes) {
                deque.clear()
                deque.addLast(Sample(nowEpochMs, bytes))
                return
            }
            if (nowEpochMs == last.timestampMs) {
                deque.removeLast()
            }
            deque.addLast(Sample(nowEpochMs, bytes))
            val cutoff = nowEpochMs - windowMs
            while (deque.size > 2 && deque.first().timestampMs < cutoff) {
                val second = deque.elementAt(1)
                if (nowEpochMs - second.timestampMs >= minSpanMs) {
                    deque.removeFirst()
                } else {
                    break
                }
            }
        }
    }

    fun clear(downloadId: String) {
        synchronized(lock) {
            samplesById.remove(downloadId)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            samplesById.clear()
        }
    }

    fun bytesPerSecond(downloadId: String, nowEpochMs: Long): Long? = synchronized(lock) {
        val deque = samplesById[downloadId] ?: return@synchronized null
        if (deque.size < 2) return@synchronized null
        val newest = deque.last()
        if (nowEpochMs - newest.timestampMs > stallTimeoutMs) return@synchronized null
        val oldest = deque.first()
        val spanMs = newest.timestampMs - oldest.timestampMs
        if (spanMs < minSpanMs) return@synchronized null
        val deltaBytes = newest.bytes - oldest.bytes
        if (deltaBytes <= 0L) return@synchronized null
        (deltaBytes * 1_000L / spanMs).takeIf { it > 0L }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 15_000L
        const val DEFAULT_MIN_SPAN_MS = 1_000L
    }
}

/**
 * Estimated remaining seconds for a single actively downloading item, or null when unknown
 * (no total size, offline, stalled, checking file, or no measured transfer speed yet).
 */
internal fun estimateDownloadEtaSeconds(
    item: DownloadItem,
    bytesPerSecond: Long?,
    nowEpochMs: Long,
    offline: Boolean,
): Long? {
    if (item.status != DownloadStatus.Downloading || offline) return null
    val total = item.totalBytes?.takeIf { it > 0L } ?: return null
    val downloaded = item.downloadedBytes.coerceAtLeast(0L)
    if (downloaded >= total) return 0L
    if (nowEpochMs - item.updatedAtEpochMs > DownloadStalledAfterMs) return null
    val rate = bytesPerSecond?.takeIf { it > 0L } ?: return null
    val remaining = (total - downloaded).coerceAtLeast(0L)
    return (remaining + rate - 1L) / rate
}

/** Overall remaining time across concurrent active transfers. */
internal fun estimateTotalDownloadingEtaSeconds(entries: List<TripDownloadingEntry>): Long? {
    val active = entries.filter { it.phase == DownloadPhase.Downloading }
    if (active.isEmpty()) return null
    val known = active.mapNotNull { it.etaSeconds }
    if (known.size != active.size) return null
    return known.maxOrNull()
}
