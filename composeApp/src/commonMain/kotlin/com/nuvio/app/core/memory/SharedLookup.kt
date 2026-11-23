package com.nuvio.app.core.memory

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only running work is retained. Cancelling one owner never cancels another caller. */
internal class SharedLookup<K, V> {
    private sealed interface Outcome<out T> {
        data class Value<T>(val value: T) : Outcome<T>
        data class Failure(val error: Throwable) : Outcome<Nothing>
        data object Retry : Outcome<Nothing>
    }
    private val guard = SynchronizedObject()
    private val flights = mutableMapOf<K, CompletableDeferred<Outcome<V>>>()
    val size: Int get() = synchronized(guard) { flights.size }

    suspend fun get(key: K, load: suspend () -> V): V = run(key, true, load)

    /** Each caller gets its own turn, so a later request can require stronger work. */
    suspend fun sequential(key: K, load: suspend () -> V): V = run(key, false, load)

    private suspend fun run(key: K, shareResult: Boolean, load: suspend () -> V): V {
        while (true) {
            currentCoroutineContext().ensureActive()
            var owner = false
            val pending = synchronized(guard) {
                flights[key] ?: CompletableDeferred<Outcome<V>>().also { flights[key] = it; owner = true }
            }
            if (!owner) {
                val outcome = pending.await()
                currentCoroutineContext().ensureActive()
                when (outcome) {
                    is Outcome.Value -> if (shareResult) return outcome.value
                    is Outcome.Failure -> if (shareResult) throw outcome.error
                    Outcome.Retry -> Unit
                }
                continue
            }
            try {
                val value = load()
                currentCoroutineContext().ensureActive()
                release(key, pending)
                pending.complete(Outcome.Value(value))
                return value
            } catch (error: CancellationException) {
                // Remove before waking waiters so an active waiter can take ownership immediately.
                release(key, pending)
                pending.complete(Outcome.Retry)
                throw error
            } catch (error: Throwable) {
                release(key, pending)
                pending.complete(Outcome.Failure(error))
                throw error
            } finally {
                release(key, pending)
            }
        }
    }

    private fun release(key: K, pending: CompletableDeferred<Outcome<V>>) = synchronized(guard) {
        if (flights[key] === pending) flights.remove(key)
        Unit
    }
}
