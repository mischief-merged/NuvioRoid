package com.nuvio.app.core.auth

import kotlinx.atomicfu.atomic

/**
 * A stored session is removed only for an explicit sign-out or a definitive rejection.
 * Anything else that asks the session store to delete leaves the saved session where it is.
 */
internal object AuthSessionRetention {
    private val permits = atomic(0)

    fun permitStoredClear() {
        permits.incrementAndGet()
    }

    fun consumeStoredClear(): Boolean {
        while (true) {
            val current = permits.value
            if (current <= 0) return false
            if (permits.compareAndSet(current, current - 1)) return true
        }
    }
}

/** Explicit sign-out and account deletion. Not set for a lost session. */
internal object SessionClearMode {
    private val depth = atomic(0)

    val explicit: Boolean
        get() = depth.value > 0

    suspend fun <T> explicit(block: suspend () -> T): T {
        depth.incrementAndGet()
        try {
            return block()
        } finally {
            depth.decrementAndGet()
        }
    }
}
