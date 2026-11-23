package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The desktop downloader over loopback: how often it reports, how fast it stops, and the speed limit. */
class DownloadSpeedAndCancelTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private val payload = ByteArray(3 * 1024 * 1024) { (it * 31).toByte() }
    private val servers = mutableListOf<HttpServer>()
    private val files = mutableListOf<File>()
    private val before = DownloadsPlatformDownloader.freeBytesProvider
    private val intervalBefore = DownloadsPlatformDownloader.progressIntervalMs
    private val stallBefore = DownloadsPlatformDownloader.stallTimeoutMs

    @BeforeTest
    fun no_free_space_check() {
        DownloadsPlatformDownloader.freeBytesProvider = { null }
    }

    @AfterTest
    fun clean_up() {
        DownloadsPlatformDownloader.freeBytesProvider = before
        DownloadsPlatformDownloader.progressIntervalMs = intervalBefore
        DownloadsPlatformDownloader.stallTimeoutMs = stallBefore
        servers.forEach { it.stop(0) }
        files.forEach { it.delete() }
    }

    private fun serve(chunk: Int = 16 * 1024, pauseMs: Long = 0, stallAfterBytes: Int? = null, stallMs: Long = 0): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v.mkv") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { out ->
                var sent = 0
                while (sent < payload.size) {
                    val n = minOf(chunk, payload.size - sent)
                    out.write(payload, sent, n)
                    out.flush()
                    sent += n
                    if (pauseMs > 0) Thread.sleep(pauseMs)
                    if (stallAfterBytes != null && sent >= stallAfterBytes && stallMs > 0) Thread.sleep(stallMs)
                }
            }
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/v.mkv"
    }

    private fun request(url: String) = DownloadPlatformRequest(
        DownloadItem(
            id = "speed", contentType = "movie", parentMetaId = "tt0", parentMetaType = "movie", videoId = "tt0",
            title = "t", streamTitle = "t", providerName = "p", sourceUrl = url, fileName = "speed-${System.nanoTime()}.mkv",
            status = DownloadStatus.Downloading, createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ),
    )

    private fun temp(): File = File.createTempFile("nuvio-speed", ".part").also { files += it; it.delete() }

    private fun limiter(bytesPerSecond: Long?) = DownloadSpeedLimiter(nanoTime = System::nanoTime).also { it.bytesPerSecond = bytesPerSecond }

    @Test
    fun progress_reaches_the_list_a_few_times_a_second_and_the_last_value_is_exact() {
        DownloadsPlatformDownloader.progressIntervalMs = 100
        val reports = mutableListOf<Long>()
        val destination = temp()
        val started = System.nanoTime()

        val result = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            DownloadsPlatformDownloader.transferToTempFile(request(serve(chunk = 16 * 1024, pauseMs = 2)), destination, { bytes, _ -> reports += bytes }, limiter(null))
        }

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(payload.size.toLong(), reports.last(), "the final value is not exact")
        assertTrue(reports.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(reports.size <= elapsedMs / 100 + 4, "${reports.size} reports in $elapsedMs ms")
        assertTrue(reports.size >= 2)
        assertContentEquals(payload, destination.readBytes())
        assertEquals(payload.size.toLong(), result.totalBytes)
    }

    @Test
    fun the_last_value_is_reported_when_the_transfer_fails_too() {
        DownloadsPlatformDownloader.progressIntervalMs = 60_000 // nothing but the first and the last
        val reports = mutableListOf<Long>()
        val url = serveAndDrop()

        assertFailsWith<IOException> {
            runBlocking(kotlinx.coroutines.Dispatchers.IO) { DownloadsPlatformDownloader.transferToTempFile(request(url), temp(), { bytes, _ -> reports += bytes }, limiter(null)) }
        }

        assertTrue(reports.last() > 0L, "the bytes that arrived were not reported")
        assertEquals(reports.last(), reports.max())
    }

    private fun serveAndDrop(): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v.mkv") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload, 0, 300_000)
            exchange.responseBody.flush()
            exchange.close()
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/v.mkv"
    }

    @Test
    fun throttled_progress_unit_reports_the_first_at_once_then_by_interval_and_the_last_once() {
        var now = 0L
        val seen = mutableListOf<Long>()
        val progress = ThrottledProgress(250, { now }) { seen += it }

        progress.offer(10)
        now += 100_000_000
        progress.offer(20)
        now += 100_000_000
        progress.offer(30)
        now += 60_000_000
        progress.offer(40)
        progress.flush(55)
        progress.flush(55)

        assertEquals(listOf(10L, 40L, 55L), seen)
    }

    @Test
    fun cancelling_stops_the_writer_promptly_even_while_a_read_is_blocked() {
        val url = serve(chunk = 64 * 1024, stallAfterBytes = 128 * 1024, stallMs = 30_000)
        val firstBytes = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val request = request(url)
        val app = DesktopStorage.rootDir.resolve("downloads").toFile().also { it.mkdirs() }
        files += File(app, request.destinationFileName)
        files += File(app, request.destinationFileName + ".part")

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { bytes, _ -> if (bytes > 0) firstBytes.countDown() },
            onSuccess = { _, _ -> },
            onFailure = { },
            onPaused = { paused.countDown() },
        )
        assertTrue(firstBytes.await(10, TimeUnit.SECONDS))
        Thread.sleep(300)
        val started = System.nanoTime()

        val stopped = handle.cancelAndWait(10_000)

        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(stopped, "the transfer did not stop")
        assertTrue(ms < 3_000, "cancel took $ms ms with a blocked read")
        assertTrue(paused.await(5, TimeUnit.SECONDS))
        // The file is closed: it can be moved.
        assertTrue(File(app, request.destinationFileName + ".part").renameTo(File(app, request.destinationFileName + ".part2").also { files += it }))
    }

    @Test
    fun a_low_limit_is_held_smoothly_and_the_bytes_are_unchanged() {
        val destination = temp()
        val started = System.nanoTime()

        runBlocking(kotlinx.coroutines.Dispatchers.IO) { DownloadsPlatformDownloader.transferToTempFile(request(serve()), destination, { _, _ -> }, limiter(1_500_000)) }

        val seconds = (System.nanoTime() - started) / 1e9
        // 3 MiB at 1.5 MB/s is about 2.1 seconds.
        assertTrue(seconds in 1.7..3.2, "took $seconds s")
        assertContentEquals(payload, destination.readBytes())
    }

    @Test
    fun a_high_limit_does_not_hold_a_transfer_back_and_a_very_low_one_still_moves() {
        val fast = temp()
        val started = System.nanoTime()
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { DownloadsPlatformDownloader.transferToTempFile(request(serve()), fast, { _, _ -> }, limiter(500_000_000)) }
        assertTrue((System.nanoTime() - started) / 1e9 < 3.0)
        assertContentEquals(payload, fast.readBytes())

        // At 400 KB/s the first 400 KB take about a second, in small reads.
        val slow = temp()
        val slowStart = System.nanoTime()
        val progressed = AtomicInteger()
        val job = Thread {
            runCatching {
                runBlocking(kotlinx.coroutines.Dispatchers.IO) { DownloadsPlatformDownloader.transferToTempFile(request(serve()), slow, { _, _ -> progressed.incrementAndGet() }, limiter(400_000)) }
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(1_500)
        val got = slow.length()
        job.interrupt()
        assertTrue(got in 300_000..900_000, "$got bytes in 1.5 s at 400 KB/s after ${(System.nanoTime() - slowStart) / 1_000_000} ms")
    }

    @Test
    fun the_read_size_is_capped_by_a_low_limit_and_is_large_without_one() {
        assertEquals(ReadBufferBytes, readChunkSize(null))
        assertEquals(10_000, readChunkSize(100_000))
        assertEquals(4096, readChunkSize(1_000))
        assertEquals(ReadBufferBytes, readChunkSize(500_000_000))
        assertTrue(ReadBufferBytes in (128 * 1024)..(256 * 1024))
        assertEquals(250L, ProgressIntervalMs)
    }

    @Test
    fun a_steady_stream_is_not_taken_for_a_stalled_one_and_a_silent_one_still_is() {
        DownloadsPlatformDownloader.stallTimeoutMs = 400
        runBlocking(kotlinx.coroutines.Dispatchers.IO) { DownloadsPlatformDownloader.transferToTempFile(request(serve(chunk = 256 * 1024, pauseMs = 40)), temp(), { _, _ -> }, limiter(null)) }

        val error = assertFailsWith<IOException> {
            runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                DownloadsPlatformDownloader.transferToTempFile(
                    request(serve(chunk = 64 * 1024, stallAfterBytes = 128 * 1024, stallMs = 3_000)), temp(), { _, _ -> }, limiter(null),
                )
            }
        }
        assertTrue(error.message.orEmpty().contains("stalled", ignoreCase = true), error.message)
    }
}
