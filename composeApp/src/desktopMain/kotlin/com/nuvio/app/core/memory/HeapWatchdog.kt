package com.nuvio.app.core.memory

import co.touchlab.kermit.Logger
import java.lang.management.ManagementFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun imageMemoryBudget(maxHeapBytes: Long): Long = (maxHeapBytes / 8).coerceIn(1L, 256L * 1024 * 1024)

/** Consecutive post-collection samples, with hysteresis and one notice per pressure episode. */
internal class HeapPressurePolicy(
    private val highWater: Double = 0.80,
    private val lowWater: Double = 0.65,
    private val requiredSamples: Int = 3,
    private val trim: () -> Unit,
    private val notice: () -> Unit,
) {
    private var highSamples = 0
    private var trimmed = false

    init { require(lowWater > 0 && lowWater < highWater && highWater < 1 && requiredSamples > 0) }

    fun sample(used: Long, maximum: Long) {
        if (maximum <= 0 || used < 0) return
        val fraction = used.toDouble() / maximum
        if (fraction < lowWater) { highSamples = 0; trimmed = false }
        if (fraction < highWater) { highSamples = 0; return }
        if (trimmed) return
        highSamples++
        if (highSamples < requiredSamples) return
        trimmed = true
        runCatching(trim)
        runCatching(notice)
    }
}

/** Observes completed collections; it never asks the collector to stop playback. */
internal class HeapWatchdog(
    private val intervalMs: Long = 60_000,
    private val collectionCount: () -> Long = { ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) } },
    private val heap: () -> Pair<Long, Long> = {
        val used = ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == java.lang.management.MemoryType.HEAP }
            .mapNotNull { it.collectionUsage?.used?.takeIf { bytes -> bytes >= 0 } }
        (if (used.isEmpty()) -1L else used.sum()) to Runtime.getRuntime().maxMemory()
    },
    private val policy: HeapPressurePolicy = HeapPressurePolicy(
        trim = DesktopMemoryCaches::trim,
        notice = { Logger.withTag("Memory").w { "Heap use stayed high after collection; cleared replaceable caches" } },
    ),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastCollection: Long? = null

    init { require(intervalMs > 0) }

    internal fun poll() {
        runCatching {
            val count = collectionCount()
            val previous = lastCollection
            lastCollection = count
            if (previous != null && count > previous) heap().let { (used, max) -> policy.sample(used, max) }
        }
    }

    private val job = scope.launch {
        while (isActive) {
            delay(intervalMs)
            poll()
        }
    }

    override fun close() { job.cancel(); scope.cancel() }
}
