package com.nuvio.app.features.plugins

import com.dokar.quickjs.ExperimentalQuickJsApi
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.nuvio.app.core.network.TrustedLocalServerTest
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.plugins.runtime.PluginRuntime
import com.nuvio.app.features.plugins.runtime.js.JsRuntime
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class PluginRuntimeDesktopTest(private val round: Int) : TrustedLocalServerTest() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "run {0}")
        fun runs(): List<Array<Int>> = (1..20).map { arrayOf(it) }
    }

    @OptIn(ExperimentalQuickJsApi::class)
    @Test
    fun `engine stays on its owning thread across host callbacks`() = runBlocking {
        val workers = List(2) { Executors.newSingleThreadExecutor() }
        val next = AtomicInteger()
        val movingCaller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                workers[next.getAndIncrement() % workers.size].execute(block)
            }
        }
        try {
            val threads = ConcurrentHashMap.newKeySet<Long>()
            var owner = -1L
            withContext(movingCaller) {
                JsRuntime().use {
                    owner = Thread.currentThread().id
                    threads += owner
                    onNativeClose { threads += Thread.currentThread().id }
                    function("recordThread") {
                        threads += Thread.currentThread().id
                        null
                    }
                    asyncFunction("hostWait") {
                        withContext(Dispatchers.IO) { delay(2) }
                        threads += Thread.currentThread().id
                        null
                    }
                    repeat(20) {
                        evaluate<Any?>("recordThread(); hostWait().then(() => recordThread());")
                    }
                }
            }
            assertEquals(setOf(owner), threads, "Engine and callbacks must keep one owner")
        } finally {
            workers.forEach { it.shutdown() }
        }
    }

    @Test
    fun `timeout stops a queue that keeps adding pending jobs`() = runBlocking {
        JsRuntime().use {
            val started = System.nanoTime()
            function("shouldStop") { System.nanoTime() - started > 3_000_000_000L }
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) {
                    evaluate<Any?>("""
                        function again() {
                            if (shouldStop()) return;
                            Promise.resolve().then(again);
                            while (!shouldStop()) {}
                        }
                        again();
                    """.trimIndent())
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 1_500, "Pending jobs ignored cancellation for $elapsedMs ms")
        }
    }

    @Test
    fun `desktop runtime executes scraper code`() = runBlocking {
        val results = PluginRuntime.executePlugin(
            code = """
                module.exports.getStreams = async function(tmdbId, mediaType) {
                    return [{
                        title: "Desktop stream " + tmdbId + " " + mediaType,
                        url: "https://example.test/movie.mp4",
                        quality: "1080p",
                        provider: "Desktop Test"
                    }];
                };
            """.trimIndent(),
            tmdbId = "603",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "desktop-runtime-test",
        )

        assertEquals(1, results.size)
        assertEquals("Desktop stream 603 movie", results.single().title)
        assertEquals("https://example.test/movie.mp4", results.single().url)
        assertEquals("1080p", results.single().quality)
        assertEquals("Desktop Test", results.single().provider)
    }

    @Test
    fun `desktop runtime handles concurrent scraper executions`() = runBlocking {
        val results = coroutineScope {
            (0 until 32).map { index ->
                async(Dispatchers.Default) {
                    PluginRuntime.executePlugin(
                        code = """
                            module.exports.getStreams = async function(tmdbId, mediaType) {
                                await new Promise(resolve => setTimeout(resolve, 2));
                                await Promise.resolve();
                                return [{
                                    title: "Concurrent stream " + tmdbId + " " + mediaType,
                                    url: "https://example.test/" + tmdbId + ".mp4",
                                    provider: "Desktop Stress Test"
                                }];
                            };
                        """.trimIndent(),
                        tmdbId = index.toString(),
                        mediaType = "movie",
                        season = null,
                        episode = null,
                        scraperId = "desktop-runtime-stress-$index",
                    )
                }
            }.awaitAll()
        }

        assertEquals(32, results.size)
        results.forEachIndexed { index, streams ->
            assertEquals(1, streams.size)
            assertEquals("https://example.test/$index.mp4", streams.single().url)
        }
    }
    @Test
    fun `busy script is interrupted by cancellation`() = runBlocking {
        JsRuntime().use {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(100) { evaluate<Any?>("while (true) {}") }
            }
            Unit
        }
    }

    @Test
    fun `native deadline interrupts busy script without a coroutine timer`() = runBlocking {
        JsRuntime().use {
            evaluationTimeoutMillis = 100
            assertFailsWith<QuickJsInterruptedException> {
                evaluate<Any?>("while (true) {}")
            }
            Unit
        }
    }

    @OptIn(ExperimentalQuickJsApi::class)
    @Test
    fun `late host callback finishes before its thread is released`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Long>()
        val returned = CompletableDeferred<Long>()
        val scriptContinued = AtomicInteger()
        var owner = -1L
        val run = launch(Dispatchers.Default) {
            JsRuntime().use {
                owner = Thread.currentThread().id
                onNativeClose { closed.complete(Thread.currentThread().id) }
                asyncFunction("hostWait") {
                    try {
                        withContext(NonCancellable + Dispatchers.IO) {
                            entered.complete(Unit)
                            release.await()
                        }
                    } finally {
                        returned.complete(Thread.currentThread().id)
                    }
                }
                function("afterCallback") { scriptContinued.incrementAndGet() }
                evaluate<Any?>("hostWait().then(() => afterCallback());")
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            run.cancel()
            assertEquals(owner, withTimeout(5_000) { closed.await() })
            val finished = withTimeoutOrNull(50) { run.join(); true }
            assertEquals(null, finished, "A suspended host callback still owns its worker")
        } finally {
            release.complete(Unit)
            withTimeout(5_000) { run.join() }
        }
        assertEquals(owner, returned.await())
        assertEquals(0, scriptContinued.get())
    }

    @Test
    fun `separate engines execute native callbacks in parallel`() = runBlocking {
        val barrier = CyclicBarrier(2)
        val owners = (0 until 2).map {
            async(Dispatchers.Default) {
                JsRuntime().use {
                    function("meet") {
                        barrier.await(5, TimeUnit.SECONDS)
                        Thread.currentThread().id.toString()
                    }
                    evaluate<String>("meet()")
                }
            }
        }.awaitAll()
        assertEquals(2, owners.toSet().size)
    }

    @Test
    fun `fetch timer and crypto callbacks use the real scraper path`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stream") { exchange ->
            val body = "https://example.test/fetched.mp4".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val streams = execute("""
                module.exports.getStreams = async function() {
                    const response = await fetch("http://127.0.0.1:${server.address.port}/stream");
                    const url = await response.text();
                    await new Promise(resolve => setTimeout(resolve, 2));
                    const hash = await crypto.subtle.digest("SHA-256", new Uint8Array([1, 2, 3]));
                    return [{ title: "Hash bytes " + hash.byteLength, url: url }];
                };
            """.trimIndent())
            assertEquals("https://example.test/fetched.mp4", streams.single().url)
            assertEquals("Hash bytes 32", streams.single().title)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cancelled fetch can finish after its engine has closed`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val replied = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { exchange ->
            try {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("late".toByteArray()) }
            } catch (_: IOException) {
                // The cancelled client may have closed its socket already.
            } finally {
                exchange.close()
                replied.complete(Unit)
            }
        }
        server.createContext("/ready") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        // Initialize the HTTP client and system trust store before gating the request.
        try {
            assertEquals(204, httpRequestRaw("GET", "http://127.0.0.1:${server.address.port}/ready", emptyMap(), "").status)
        } catch (error: Throwable) {
            server.stop(0)
            throw error
        }
        val run = launch(Dispatchers.Default) {
            execute("""
                module.exports.getStreams = async function() {
                    await fetch("http://127.0.0.1:${server.address.port}/slow");
                    return [{ title: "Late stream", url: "https://example.test/late.mp4" }];
                };
            """.trimIndent())
        }
        try {
            withTimeout(5_000) { entered.await() }
            run.cancel()
            withTimeout(5_000) { run.join() }
            release.countDown()
            withTimeout(5_000) { replied.await() }
            val good = execute("module.exports.getStreams = async function() { return [{title:'Stream',url:'https://example.test/good.mp4'}]; };")
            assertEquals("https://example.test/good.mp4", good.single().url)
        } finally {
            release.countDown()
            run.cancel()
            withTimeout(5_000) { run.join() }
            server.stop(0)
        }
    }

    @Test
    fun `settings evaluations keep separate engines and bytecode`() = runBlocking {
        val layouts = (0 until 16).map { index ->
            async(Dispatchers.Default) {
                PluginRuntime.getPluginSettingsLayout(
                    code = """
                        module.exports.onSettings = async function() {
                            await new Promise(resolve => setTimeout(resolve, 2));
                            return [{ id: "setting-$index" }];
                        };
                    """.trimIndent(),
                    scraperId = "desktop-settings-$round-$index",
                )
            }
        }.awaitAll()
        layouts.forEachIndexed { index, layout ->
            assertEquals("[{\"id\":\"setting-$index\"}]", layout)
        }
    }

    @Test
    fun `failed and cancelled sources leave other sources working`() = runBlocking {
        val outcomes = (0 until 30).map { index ->
            async(Dispatchers.Default) {
                runCatching {
                    when (index % 3) {
                        0 -> execute("module.exports.getStreams = async function() { throw Error('Source failed'); };")
                        1 -> withTimeout(100) {
                            execute("module.exports.getStreams = async function() { await new Promise(r => setTimeout(r, 10000)); return []; };")
                        }
                        else -> execute("module.exports.getStreams = async function() { return [{title:'Stream',url:'https://example.test/good.mp4'}]; };")
                    }
                }
            }
        }.awaitAll()
        outcomes.forEachIndexed { index, outcome ->
            if (index % 3 == 2) {
                assertEquals("https://example.test/good.mp4", outcome.getOrThrow().single().url)
            } else if (index % 3 == 0) {
                assertTrue(outcome.getOrThrow().isEmpty())
            } else {
                assertTrue(outcome.exceptionOrNull() is TimeoutCancellationException)
            }
        }
    }

    @OptIn(ExperimentalQuickJsApi::class)
    @Test
    fun `many successful failed and cancelled engines release every owner`() = runBlocking {
        val owners = ConcurrentHashMap.newKeySet<Thread>()
        val created = AtomicInteger()
        val closed = AtomicInteger()
        repeat(8) {
            (0 until 8).map { index ->
                async(Dispatchers.Default) {
                    JsRuntime().use {
                        val owner = Thread.currentThread()
                        owners += owner
                        created.incrementAndGet()
                        onNativeClose {
                            assertEquals(owner, Thread.currentThread())
                            closed.incrementAndGet()
                        }
                        when (index % 3) {
                            0 -> assertFailsWith<TimeoutCancellationException> {
                                withTimeout(10) {
                                    asyncFunction("hostWait") { delay(1_000); null }
                                    evaluate<Any?>("hostWait()")
                                }
                            }
                            1 -> assertFailsWith<Exception> { evaluate<Any?>("throw Error('Failed');") }
                            else -> assertEquals(2, evaluate<Int>("1 + 1"))
                        }
                    }
                }
            }.awaitAll()
        }
        assertEquals(64, created.get())
        assertEquals(created.get(), closed.get())
        withTimeout(5_000) {
            while (owners.any { it.isAlive }) delay(1)
        }
    }

    private suspend fun execute(code: String): List<PluginRuntimeResult> = PluginRuntime.executePlugin(
        code = code,
        tmdbId = "603",
        mediaType = "movie",
        season = null,
        episode = null,
        scraperId = "desktop-runtime-lifecycle-test-$round",
        respectSearchPause = false,
    )

}
