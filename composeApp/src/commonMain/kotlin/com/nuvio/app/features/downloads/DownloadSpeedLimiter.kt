package com.nuvio.app.features.downloads

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Paces downloads to one shared rate. Callers report bytes after transferring them and are held
 * until that debt has been paid off; idle time is never banked as credit.
 */
internal class DownloadSpeedLimiter(
    private val nanoTime: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val rate = atomic(0L)
    private val reportedBytes = atomic(0L)
    private val mutex = Mutex()
    private var pendingBytes = 0.0
    private var lastNanos: Long? = null
    private var accountedRate = 0L

    var bytesPerSecond: Long?
        get() = rate.value.takeIf { it > 0L }
        set(value) { rate.value = value?.takeIf { it > 0L } ?: 0L }

    suspend fun throttle(bytes: Int) {
        // Unlimited transfers do not read the clock or take the lock.
        if (rate.value == 0L || bytes <= 0) return
        // Recorded before waiting for the lock, so a caller cancelled at any point still leaves
        // its bytes on the shared debt. The debt only shrinks as time passes.
        reportedBytes.addAndGet(bytes.toLong())
        mutex.withLock {
            while (true) {
                val currentRate = settleDebt()
                // Keep sub-millisecond debt so very small reads still share the same budget.
                val waitMs = if (currentRate > 0L) (pendingBytes * 1000.0 / currentRate).toLong() else 0L
                if (waitMs == 0L) break
                sleep(minOf(waitMs, 50L))
            }
        }
    }

    /** Pays off debt for the time elapsed, then adds the bytes reported since. Returns the rate. */
    private fun settleDebt(): Long {
        val now = nanoTime()
        val currentRate = rate.value
        val elapsed = lastNanos?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        pendingBytes = (pendingBytes - elapsed.toDouble() * accountedRate / 1_000_000_000.0)
            .coerceAtLeast(0.0) + reportedBytes.getAndSet(0L)
        if (currentRate == 0L) pendingBytes = 0.0
        lastNanos = now
        accountedRate = currentRate
        return currentRate
    }
}

internal object DownloadBandwidth {
    private val mark = TimeSource.Monotonic.markNow()
    val limiter = DownloadSpeedLimiter(nanoTime = { mark.elapsedNow().inWholeNanoseconds })
    private val cap = MutableStateFlow<Long?>(null)
    val userCapBytesPerSecond = cap.asStateFlow()
    private val lock = SynchronizedObject()

    // The rate that leaves the link to other traffic (see PoliteDownloads). Null: none.
    private var yieldCap: Long? = null

    /** Applies the stored limit to the shared limiter. */
    fun applyStoredLimit() = synchronized(lock) {
        apply(DownloadSpeedStorage.loadBytesPerSecond())
    }

    fun setUserCap(bytesPerSecond: Long?) = synchronized(lock) {
        DownloadSpeedStorage.saveBytesPerSecond(bytesPerSecond?.takeIf { it > 0L })
        apply(bytesPerSecond)
    }

    /** Sets the rate downloads yield to; the user's limit stays the ceiling. Null removes it. */
    fun setYieldCap(bytesPerSecond: Long?) = synchronized(lock) {
        yieldCap = bytesPerSecond?.takeIf { it > 0L }
        limiter.bytesPerSecond = effective(cap.value)
    }

    private fun apply(bytesPerSecond: Long?) {
        val value = bytesPerSecond?.takeIf { it > 0L }
        cap.value = value
        limiter.bytesPerSecond = effective(value)
    }

    /** The lower of the user's limit and the yield rate. */
    private fun effective(userCap: Long?): Long? {
        val yielding = yieldCap ?: return userCap
        return if (userCap == null) yielding else minOf(userCap, yielding)
    }
}
