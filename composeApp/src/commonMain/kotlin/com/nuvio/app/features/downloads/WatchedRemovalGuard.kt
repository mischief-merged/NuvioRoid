package com.nuvio.app.features.downloads

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lock order: video, download state, removal settings, stored list. */
internal object WatchedRemovalGuard {
    val lock = SynchronizedObject()
    private val revision = atomic(0L)
    private val pendingChanges = atomic(0)

    /** Signal cancellation before waiting for the removal's lock. */
    fun <T> change(block: () -> T): T = signalChange { synchronized(lock) { block() } }

    /** Publish an immediate change without waiting for disk work under the removal lock. */
    fun <T> signalChange(block: () -> T): T {
        pendingChanges.incrementAndGet()
        revision.incrementAndGet()
        try {
            return block()
        } finally {
            pendingChanges.decrementAndGet()
        }
    }

    /** A cancelled move must restore its file before playback reads it. Never wait on the UI thread. */
    suspend fun awaitRemovalFinished() = withContext(Dispatchers.Default) {
        synchronized(lock) { Unit }
    }

    fun check(stillWanted: () -> Boolean): () -> Boolean {
        val startedAt = revision.value
        return {
            pendingChanges.value == 0 && revision.value == startedAt && stillWanted() &&
                pendingChanges.value == 0 && revision.value == startedAt
        }
    }
}
