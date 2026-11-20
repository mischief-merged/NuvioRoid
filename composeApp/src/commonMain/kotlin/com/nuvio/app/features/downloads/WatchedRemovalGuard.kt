package com.nuvio.app.features.downloads

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Lock order: video, download state, removal settings, stored list. */
internal object WatchedRemovalGuard {
    val lock = SynchronizedObject()
    private val revision = atomic(0L)
    private val pendingChanges = atomic(0)

    /** Signal cancellation before waiting for the removal's lock. */
    fun <T> change(block: () -> T): T {
        pendingChanges.incrementAndGet()
        revision.incrementAndGet()
        try {
            return synchronized(lock) { block() }
        } finally {
            pendingChanges.decrementAndGet()
        }
    }

    fun check(stillWanted: () -> Boolean): () -> Boolean {
        val startedAt = revision.value
        return {
            pendingChanges.value == 0 && revision.value == startedAt && stillWanted() &&
                pendingChanges.value == 0 && revision.value == startedAt
        }
    }
}
