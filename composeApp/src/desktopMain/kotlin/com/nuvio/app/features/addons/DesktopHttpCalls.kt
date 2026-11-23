package com.nuvio.app.features.addons

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_requests_busy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jetbrains.compose.resources.getString

internal class DesktopHttpCalls(val maximumCalls: Int = 8, val maximumPending: Int = 128) : AutoCloseable {
    private val workers = ThreadPoolExecutor(
        maximumCalls, maximumCalls, 30, TimeUnit.SECONDS, ArrayBlockingQueue(maximumPending),
        { work -> Thread(work, "metadata-request").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    val pendingCount: Int get() = workers.queue.size
    val activeCount: Int get() = workers.activeCount

    suspend fun <T> execute(client: OkHttpClient, request: Request, read: (Response) -> T): T = try {
        suspendCancellableCoroutine { pending ->
            val call = client.newCall(request)
            val work = Runnable {
                // Close the response before publishing copied values or a failure.
                val result = runCatching { call.execute().use(read) }
                result.fold({ pending.resume(it) }, { pending.resumeWithException(it) })
            }
            pending.invokeOnCancellation {
                call.cancel()
                workers.remove(work)
            }
            try {
                if (pending.isActive) {
                    workers.execute(work)
                    // Cancellation can race the gap between the check and queue insertion.
                    if (!pending.isActive) workers.remove(work)
                }
            } catch (error: RejectedExecutionException) {
                call.cancel()
                pending.resumeWithException(error)
            }
        }
    } catch (error: RejectedExecutionException) {
        throw IOException(getString(Res.string.network_requests_busy), error)
    }

    override fun close() { workers.shutdown() }
}
