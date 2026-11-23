package com.nuvio.app.core.memory

import com.nuvio.app.core.network.TrustedLocalServerTest
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.DesktopHttpCalls
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.player.ParentalGuideRepository
import com.nuvio.app.features.player.ParentalGuideResult
import com.nuvio.app.features.streams.BingeGroupCacheStorage
import com.nuvio.app.features.streams.StreamLinkCacheStorage
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.Request
import kotlin.test.*

class MemoryBoundaryRegressionTest : TrustedLocalServerTest() {
    private val guidePayload = """{"parentsGuide":[{"category":"VIOLENCE","severityBreakdowns":[{"severityLevel":"mild","voteCount":4}]}]}"""

    @Test fun parentalGuideHitsStayConsistentDuringPressureClears() = runBlocking {
        val oldRequest = ParentalGuideRepository.requestGuide
        val cache = guideCache()
        val stop = AtomicBoolean()
        val started = CountDownLatch(1)
        val trimmer = Thread({
            started.countDown()
            while (!stop.get()) { cache.clear(); Thread.yield() }
        }, "cache-pressure-check").apply { isDaemon = true }
        ParentalGuideRepository.requestGuide = { response(guidePayload) }
        cache.clear()
        try {
            trimmer.start()
            started.await()
            var missing = 0
            repeat(50_000) {
                if (ParentalGuideRepository.getParentalGuide("tt1234567")?.violence != "mild") missing++
            }
            assertEquals(0, missing, "Pressure must yield a cached result or a fetch, never a false empty answer")
        } finally {
            stop.set(true)
            trimmer.join()
            ParentalGuideRepository.requestGuide = oldRequest
            cache.clear()
        }
    }

    @Test fun parentalGuideCachesPositiveAndEmptyHttpResultsUntilTrimmed() = runBlocking {
        val requests = AtomicInteger()
        withServer { server ->
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                val bytes = (if (exchange.requestURI.path.endsWith("tt1234567")) guidePayload else "{\"parentsGuide\":[]}").encodeToByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val oldRequest = ParentalGuideRepository.requestGuide
            val cache = guideCache()
            ParentalGuideRepository.requestGuide = { id -> httpRequestRaw("GET", base(server) + "/$id", emptyMap(), "") }
            cache.clear()
            try {
                repeat(2) {
                    assertEquals("mild", ParentalGuideRepository.getParentalGuide("tt1234567")?.violence)
                    assertNull(ParentalGuideRepository.getParentalGuide("tt7654321"))
                }
                assertEquals(2, requests.get())
                DesktopMemoryCaches.trim()
                assertEquals("mild", ParentalGuideRepository.getParentalGuide("tt1234567")?.violence)
                assertNull(ParentalGuideRepository.getParentalGuide("tt7654321"))
                assertEquals(4, requests.get())
            } finally { ParentalGuideRepository.requestGuide = oldRequest; cache.clear() }
        }
    }

    @Test fun cancellingAParentalGuideRequestDoesNotPublishAnEmptyCacheEntry() = runBlocking {
        val oldRequest = ParentalGuideRepository.requestGuide
        val cache = guideCache()
        val started = CompletableDeferred<Unit>()
        var requests = 0
        ParentalGuideRepository.requestGuide = {
            if (++requests == 1) { started.complete(Unit); awaitCancellation() }
            response(guidePayload)
        }
        cache.clear()
        try {
            val request = launch(start = CoroutineStart.UNDISPATCHED) { ParentalGuideRepository.getParentalGuide("tt1234567") }
            started.await()
            request.cancelAndJoin()
            assertFalse(cache.containsKey("tt1234567"))
            assertEquals("mild", ParentalGuideRepository.getParentalGuide("tt1234567")?.violence)
            assertEquals(2, requests)
        } finally { ParentalGuideRepository.requestGuide = oldRequest; cache.clear() }
    }

    @Test fun streamLinkStorageHasExactlyItsProductionCapacity() = checkProductionStore(StreamLinkCacheStorage, 128)

    @Test fun bingeGroupStorageHasExactlyItsProductionCapacity() = checkProductionStore(BingeGroupCacheStorage, 384)

    @Test fun rawResponsesKeepReadingWhileTheServerMakesProgress() = runBlocking {
        withServer { server ->
            server.createContext("/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "/slow")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/slow") { exchange ->
                try {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        repeat(130) { Thread.sleep(500); output.write('a'.code); output.flush() }
                    }
                } catch (_: java.io.IOException) { exchange.close() }
            }
            server.start()
            coroutineScope {
                listOf(true, false).map { redirects -> async {
                    val path = if (redirects) "/redirect" else "/slow"
                    val result = httpRequestRaw("GET", base(server) + path, emptyMap(), "", followRedirects = redirects)
                    assertEquals(200, result.status)
                    assertEquals("a".repeat(130), result.body)
                } }.awaitAll()
            }
            assertEquals(302, httpRequestRaw("GET", base(server) + "/redirect", emptyMap(), "", followRedirects = false).status)
        }
    }

    @Test fun cancellingResponseOwnersClosesTheirActiveBodies() = runBlocking {
        val requests: List<suspend (String) -> Any> = listOf(
            { url -> httpRequestRaw("GET", url, emptyMap(), "") },
            { url -> httpRequestRaw("GET", url, emptyMap(), "", followRedirects = false) },
            { url -> httpGetText(url) },
        )
        for (request in requests) {
            withServer { server ->
                val entered = CountDownLatch(1)
                val closed = CountDownLatch(1)
                server.createContext("/") { exchange ->
                    try {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.use { output ->
                            repeat(1200) {
                                output.write('a'.code); output.flush(); entered.countDown(); Thread.sleep(10)
                            }
                        }
                    } catch (_: java.io.IOException) { exchange.close() }
                    finally { closed.countDown() }
                }
                server.start()
                val owner = launch { runCatching { request(base(server)) } }
                // Let the event loop dispatch the request before waiting on the server thread.
                yield()
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    assertTrue(owner.isActive)
                    withTimeout(3000) { owner.cancelAndJoin() }
                    assertTrue(closed.await(3, TimeUnit.SECONDS), "The response body must close with its owner")
                } finally { owner.cancel() }
            }
        }
    }

    @Test fun cancelledRequestsDoNotStayQueuedBehindOtherActiveBodies() = runBlocking {
        val dispatcher = com.nuvio.app.features.addons.desktopHttpClient.dispatcher
        val active = dispatcher.maxRequestsPerHost
        withServer(active + 2) { server ->
            val entered = CountDownLatch(active)
            val started = AtomicInteger()
            val finish = CountDownLatch(1)
            server.createContext("/") { exchange ->
                try {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        output.write('a'.code); output.flush()
                        started.incrementAndGet(); entered.countDown()
                        finish.await(10, TimeUnit.SECONDS)
                        output.write('b'.code)
                    }
                } catch (_: java.io.IOException) { exchange.close() }
            }
            server.start()
            val owners = (1..active).map { launch { runCatching { httpRequestRaw("GET", base(server), emptyMap(), "") } } }
            yield()
            var waiting = emptyList<Job>()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                waiting = (1..2).map { launch { runCatching { httpRequestRaw("GET", base(server), emptyMap(), "") } } }
                withTimeout(3000) {
                    while (started.get() + dispatcher.queuedCallsCount() < active + 2) delay(10)
                }
                waiting.forEach { it.cancelAndJoin() }
                assertEquals(0, dispatcher.queuedCallsCount(), "Cancelled owners must not leave queued calls behind")
            } finally {
                finish.countDown()
                (owners + waiting).forEach { it.cancelAndJoin() }
            }
        }
    }

    @Test fun httpWorkersBoundActiveAndWaitingWorkAndReuseCancelledSlots() = runBlocking {
        val defaults = com.nuvio.app.features.addons.desktopHttpCalls
        assertEquals(8, defaults.maximumCalls)
        assertEquals(128, defaults.maximumPending)
        val calls = DesktopHttpCalls(2, 1)
        try {
            withServer(4) { server ->
                val entered = CountDownLatch(2)
                val finish = CountDownLatch(1)
                server.createContext("/") { exchange ->
                    try {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.use { output ->
                            output.write('a'.code); output.flush(); entered.countDown()
                            finish.await(10, TimeUnit.SECONDS)
                            output.write('b'.code)
                        }
                    } catch (_: java.io.IOException) { exchange.close() }
                }
                server.start()
                val client = com.nuvio.app.features.addons.desktopHttpClient
                val request = Request.Builder().url(base(server)).build()
                val owners = (1..2).map { async { runCatching { calls.execute(client, request) { it.body!!.string() } } } }
                yield()
                val waiting = async { calls.execute(client, request) { it.body!!.string() } }
                var replacement: Deferred<String>? = null
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    withTimeout(3000) { while (calls.pendingCount != 1) delay(10) }
                    assertEquals(2, calls.activeCount)
                    assertFailsWith<java.io.IOException> { calls.execute(client, request) { it.body!!.string() } }
                    assertEquals(1, calls.pendingCount)
                    waiting.cancelAndJoin()
                    assertEquals(0, calls.pendingCount)
                    val retried = async { calls.execute(client, request) { it.body!!.string() } }
                    replacement = retried
                    withTimeout(3000) { while (calls.pendingCount != 1) delay(10) }
                    owners.forEach { it.cancelAndJoin() }
                    finish.countDown()
                    assertEquals("ab", withTimeout(3000) { retried.await() })
                    withTimeout(3000) { while (calls.activeCount != 0) delay(10) }
                    assertEquals(0, calls.pendingCount)
                } finally {
                    finish.countDown()
                    owners.forEach { it.cancelAndJoin() }
                    waiting.cancelAndJoin()
                    replacement?.cancelAndJoin()
                }
            }
        } finally { calls.close() }
    }

    private fun checkProductionStore(owner: Any, expected: Int) {
        val production = owner.javaClass.getDeclaredField("store").apply { isAccessible = true }.get(null) as DesktopStorage.Store
        val capacity = production.javaClass.getDeclaredField("cacheEntries").apply { isAccessible = true }.get(production) as Int?
        assertEquals(expected, capacity)
        val root = Files.createTempDirectory("production-cache-capacity")
        try {
            val file = root.resolve("results.properties")
            val store = DesktopStorage.Store(file, capacity)
            repeat(expected) { store.putString("$it", "value") }
            assertEquals("value", store.getString("0"))
            store.putString("$expected", "latest")
            assertEquals(expected, store.keys().size)
            assertEquals("value", store.getString("0"))
            assertNull(store.getString("1"))
            val reopened = DesktopStorage.Store(file, capacity)
            assertEquals(expected, reopened.freshEntries().size)
            assertEquals("latest", reopened.getString("$expected"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun guideCache() = ParentalGuideRepository.javaClass.getDeclaredField("cache").apply { isAccessible = true }
        .get(null) as BoundedCache<String, ParentalGuideResult?>

    private fun response(body: String) = RawHttpResponse(200, "OK", "http://127.0.0.1/guide", body, emptyMap())
    private fun base(server: HttpServer) = "http://127.0.0.1:${server.address.port}"

    private suspend fun withServer(workerCount: Int = 2, block: suspend (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newFixedThreadPool(workerCount) { Thread(it, "response-stream").apply { isDaemon = true } }
        server.executor = workers
        try { block(server) } finally { server.stop(0); workers.shutdown() }
    }
}
