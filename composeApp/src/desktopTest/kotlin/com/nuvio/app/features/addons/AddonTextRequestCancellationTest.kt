package com.nuvio.app.features.addons

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddonTextRequestCancellationTest {
    @Test
    fun cancelledRequestAcceptsLateFailureCallback() = runTest {
        checkLateCallback(Result.failure(IOException("Canceled")))
    }

    @Test
    fun cancelledRequestAcceptsLateResponseCallback() = runTest {
        checkLateCallback(Result.success("[]"))
    }

    private suspend fun checkLateCallback(result: Result<String>) = kotlinx.coroutines.coroutineScope {
        val pending = CompletableDeferred<CancellableContinuation<String>>()
        var cancellationCalls = 0
        var returnedPayload = false
        val request = async {
            suspendCancellableCoroutine<String> { continuation ->
                continuation.invokeOnCancellation { cancellationCalls++ }
                pending.complete(continuation)
            }
            returnedPayload = true
        }
        val continuation = pending.await()
        request.cancelAndJoin()
        assertTrue(continuation.isCancelled)
        assertEquals(1, cancellationCalls)
        // These are the same resume calls used by the text request callbacks.
        withContext(Dispatchers.IO) { continuation.resumeWith(result) }
        assertTrue(request.isCancelled)
        assertEquals(false, returnedPayload)
    }
}
