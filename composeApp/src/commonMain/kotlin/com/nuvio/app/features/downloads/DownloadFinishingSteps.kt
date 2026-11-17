package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Tracks publishing through the last save, without taking the repository or file locks to wait. */
internal class DownloadFinishingSteps {
    private val lock = SynchronizedObject()
    private val pending = MutableStateFlow<Set<Any>>(emptySet())
    private var closing = false

    val isClosing: Boolean get() = synchronized(lock) { closing }

    fun begin(token: Any): Boolean = synchronized(lock) {
        if (token in pending.value) return true
        if (closing) return false
        pending.value = pending.value + token
        true
    }

    fun end(token: Any): Unit = synchronized(lock) {
        pending.value = pending.value - token
    }

    suspend fun awaitForQuit(timeoutMs: Long): Boolean {
        synchronized(lock) { closing = true }
        return withTimeoutOrNull(timeoutMs) { pending.first { it.isEmpty() }; true } ?: false
    }
}
