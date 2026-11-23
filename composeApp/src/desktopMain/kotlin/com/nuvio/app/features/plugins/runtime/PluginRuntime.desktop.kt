package com.nuvio.app.features.plugins.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

internal val pluginDispatcher: CoroutineDispatcher = Dispatchers.Default

// The native wrapper shares a non-atomic instance count and JNI cache across engines.
private val engineLifecycleLock = Any()

internal fun createPluginEngine(dispatcher: CoroutineDispatcher): QuickJs {
    // Load the library before taking the native lifecycle lock.
    val factory = QuickJs.Companion
    return synchronized(engineLifecycleLock) { factory.create(dispatcher) }
}

internal fun closePluginEngine(engine: QuickJs) {
    synchronized(engineLifecycleLock) { engine.close() }
}

internal suspend fun <T> withPluginDispatcher(block: suspend (CoroutineDispatcher) -> T): T {
    val owner = PluginThread()
    try {
        return withContext(owner) { block(owner) }
    } finally {
        owner.finish()
    }
}

internal fun QuickJs.configurePluginRuntime() {
    evaluationTimeoutMillis = PLUGIN_TIMEOUT_MS
}

private class PluginThread : CoroutineDispatcher() {
    private val stopped = CompletableDeferred<Unit>()
    private val jobsLock = Any()
    private val jobs = mutableSetOf<Job>()
    private val executor = Executors.newSingleThreadExecutor { worker ->
        Thread({
            try {
                worker.run()
            } finally {
                stopped.complete(Unit)
            }
        }, "plugin-runtime-worker").apply { isDaemon = true }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // The wrapper's host jobs have their own scope. Keep their continuations
        // on this owner until close has cancelled them and they have all returned.
        context[Job]?.let { job ->
            if (synchronized(jobsLock) { jobs.add(job) }) {
                job.invokeOnCompletion { synchronized(jobsLock) { jobs.remove(job) } }
            }
        }
        executor.execute(block)
    }

    suspend fun finish() = withContext(NonCancellable) {
        try {
            while (true) {
                val remaining = synchronized(jobsLock) { jobs.toList() }
                if (remaining.isEmpty()) break
                remaining.forEach { it.cancel() }
                remaining.joinAll()
            }
        } finally {
            executor.shutdown()
            if (!executor.isTerminated) stopped.await()
        }
    }
}
