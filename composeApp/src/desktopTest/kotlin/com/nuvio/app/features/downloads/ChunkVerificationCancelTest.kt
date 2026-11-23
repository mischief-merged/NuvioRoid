package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkVerificationCancelTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    @Test
    fun pause_before_any_bytes_arrive_closes_without_waiting_for_the_socket() {
        assertPauseClosesPromptly(sendSniffBytesFirst = false)
    }

    @Test
    fun pause_during_the_verification_chunk_closes_without_waiting_for_the_socket() {
        assertPauseClosesPromptly(sendSniffBytesFirst = true)
    }

    @Test
    fun stall_before_any_bytes_arrive_closes_the_stream_and_retries() {
        val payload = ByteArray(4 * 1024) { (it % 251).toByte() }
        val stem = "sniff-stall-${System.nanoTime()}"
        val fileName = "$stem.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").also { Files.createDirectories(it) }
        val attempts = AtomicInteger(0)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val successUri = AtomicReference<String?>(null)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/video.mkv") { exchange ->
            val attempt = attempts.incrementAndGet()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            try {
                exchange.responseBody.use { output ->
                    if (attempt == 1) {
                        output.flush()
                        release.await(20, TimeUnit.SECONDS)
                    } else {
                        output.write(payload)
                    }
                }
            } catch (_: IOException) {
            }
        }
        server.start()
        val stallBefore = DownloadsPlatformDownloader.stallTimeoutMs
        val waitBefore = DownloadsPlatformDownloader.waitBeforeRetry
        DownloadsPlatformDownloader.stallTimeoutMs = 300L
        DownloadsPlatformDownloader.waitBeforeRetry = {}
        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    testDownload(episode = 1, status = DownloadStatus.Downloading, showId = "sniff-stall").copy(
                        id = "sniff-stall-${System.nanoTime()}",
                        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mkv",
                        fileName = fileName,
                    ),
                ),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ ->
                    successUri.set(uri)
                    done.countDown()
                },
                onFailure = { done.countDown() },
                onPaused = { done.countDown() },
            )
            assertTrue(done.await(10, TimeUnit.SECONDS), "stalled sniff did not cut off and retry")
            val uri = assertNotNull(successUri.get())
            assertContentEquals(payload, File(URI(uri)).readBytes())
            assertEquals(2, attempts.get())
        } finally {
            release.countDown()
            DownloadsPlatformDownloader.stallTimeoutMs = stallBefore
            DownloadsPlatformDownloader.waitBeforeRetry = waitBefore
            server.stop(0)
            executor.shutdownNow()
            downloadsDir.toFile().listFiles()?.filter { it.name.startsWith(stem) }?.forEach { it.delete() }
        }
    }

    @Test
    fun peek_head_closes_the_underlying_stream_without_waiting_on_the_pushback_lock() {
        val stallBefore = DownloadsPlatformDownloader.stallTimeoutMs
        try {
            DownloadsPlatformDownloader.stallTimeoutMs = 200L
            val stallEntered = CountDownLatch(1)
            val stallStream = BlockingStream(stallEntered)
            val stallBody = PushbackInputStream(stallStream, SniffBytes)
            val error = assertFailsWith<IOException> {
                runBlocking {
                    withTimeoutOrNull(3_000) {
                        peekHead(stallBody) { stallStream.close() }
                    } ?: error("stall guard waited behind the pushback lock")
                }
            }
            assertTrue(error.message.orEmpty().contains("stalled", ignoreCase = true))
            assertTrue(stallStream.closed.get())

            DownloadsPlatformDownloader.stallTimeoutMs = 30_000L
            val cancelEntered = CountDownLatch(1)
            val cancelStream = BlockingStream(cancelEntered)
            val cancelBody = PushbackInputStream(cancelStream, SniffBytes)
            runBlocking {
                val job = launch(Dispatchers.IO) {
                    peekHead(cancelBody) { cancelStream.close() }
                }
                assertTrue(cancelEntered.await(5, TimeUnit.SECONDS))
                assertNotNull(
                    withTimeoutOrNull(3_000) { job.cancelAndJoin() },
                    "cancellation waited behind the pushback lock",
                )
            }
            assertTrue(cancelStream.closed.get())
        } finally {
            DownloadsPlatformDownloader.stallTimeoutMs = stallBefore
        }
    }

    private fun assertPauseClosesPromptly(sendSniffBytesFirst: Boolean) {
        val partialBytes = ByteArray(4 * 1024) { 0x11 }
        val totalBytes = 200_000L
        val stem = "chunk-pause-${System.nanoTime()}"
        val fileName = "$stem.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").also { Files.createDirectories(it) }
        val partial = downloadsDir.resolve("$fileName.part")
        Files.write(partial, partialBytes)
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val rangeError = AtomicReference<String?>(null)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/video.mkv") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val expected = "bytes=0-${partialBytes.size - 1}"
            if (range != expected) {
                rangeError.set(range ?: "no range")
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                ready.countDown()
                return@createContext
            }
            exchange.responseHeaders.add("Content-Range", "bytes 0-${partialBytes.size - 1}/$totalBytes")
            exchange.sendResponseHeaders(206, partialBytes.size.toLong())
            try {
                exchange.responseBody.use { output ->
                    if (sendSniffBytesFirst) {
                        output.write(partialBytes, 0, SniffMinBytes)
                    }
                    output.flush()
                    ready.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    val remainingOffset = if (sendSniffBytesFirst) SniffMinBytes else 0
                    output.write(partialBytes, remainingOffset, partialBytes.size - remainingOffset)
                }
            } catch (_: IOException) {
                ready.countDown()
            }
        }
        server.start()
        val waitBefore = DownloadsPlatformDownloader.waitBeforeRetry
        DownloadsPlatformDownloader.waitBeforeRetry = {}
        try {
            val handle = DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    testDownload(episode = 1, status = DownloadStatus.Downloading, showId = "chunk").copy(
                        id = "chunk-${System.nanoTime()}",
                        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mkv",
                        fileName = fileName,
                        partialFileName = partial.fileName.toString(),
                        partialFileKey = DownloadMover.identityOf(partial),
                        totalBytes = totalBytes,
                        downloadedBytes = partialBytes.size.toLong(),
                        pendingLinkVerification = true,
                        sourceETag = null,
                        sourceLastModified = null,
                    ),
                ),
                onProgress = { _, _ -> },
                onSuccess = { _, _ -> },
                onFailure = { message -> failure.set(message) },
                onPaused = { paused.countDown() },
            )
            assertTrue(ready.await(15, TimeUnit.SECONDS), "verification chunk did not start")
            assertNull(rangeError.get())
            Thread.sleep(200)
            assertTrue(handle.cancelAndWait(5_000), "pausing waited on the socket")
            assertTrue(paused.await(5, TimeUnit.SECONDS), "transfer did not report paused")
            assertNull(failure.get())
        } finally {
            release.countDown()
            DownloadsPlatformDownloader.waitBeforeRetry = waitBefore
            server.stop(0)
            executor.shutdownNow()
            downloadsDir.toFile().listFiles()?.filter { it.name.startsWith(stem) }?.forEach { it.delete() }
        }
    }

    private class BlockingStream(
        private val enteredRead: CountDownLatch,
    ) : InputStream() {
        val closed = AtomicBoolean(false)
        private val release = CountDownLatch(1)

        override fun read(): Int {
            enteredRead.countDown()
            release.await(20, TimeUnit.SECONDS)
            if (closed.get()) throw IOException("closed")
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val value = read()
            if (value == -1) return -1
            b[off] = value.toByte()
            return 1
        }

        override fun close() {
            closed.set(true)
            release.countDown()
        }
    }
}
