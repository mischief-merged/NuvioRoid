package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAutoDownloadLimitTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private val megabyte = 1024 * 1024
    private val bodyBytes = 6 * megabyte
    private val shortBodyBytes = megabyte / 2

    // The transfer checks its limits every 256 KiB and reads at most 8 KiB at a time.
    private val checkDistance = 264L * 1024
    private val bytesSent = AtomicLong(0)
    private val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile()
    private val fileName = "nuvio-limit-test-${System.nanoTime()}.mkv"
    private lateinit var server: HttpServer

    private class Outcome(val success: String?, val failure: String?, val stop: AutoDownloadStop?, val paused: Boolean)

    @BeforeTest
    fun start_a_server_that_serves_the_file_with_and_without_its_size() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun serve(path: String, announceSize: Boolean) = server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, if (announceSize) bodyBytes.toLong() else 0L)
            val chunk = ByteArray(64 * 1024) { 7 }
            runCatching {
                exchange.responseBody.use { output ->
                    repeat(bodyBytes / chunk.size) {
                        output.write(chunk)
                        bytesSent.addAndGet(chunk.size.toLong())
                    }
                }
            }
        }
        serve("/sized.mkv", announceSize = true)
        serve("/unsized.mkv", announceSize = false)
        server.createContext("/short-unsized.mkv") { exchange ->
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.use { it.write(ByteArray(shortBodyBytes) { 7 }) }
        }
        server.start()
    }

    @AfterTest
    fun stop_the_server_and_remove_the_files() {
        server.stop(0)
        File(downloadsDir, fileName).delete()
        File(downloadsDir, "$fileName.part").delete()
    }

    private fun transfer(path: String, maxTotalBytes: Long?, minFreeBytes: Long = 0): Outcome {
        val done = CountDownLatch(1)
        var success: String? = null
        var failure: String? = null
        var stop: AutoDownloadStop? = null
        var paused = false
        val limits = DownloadTransferLimits(
            maxTotalBytes = { maxTotalBytes },
            minFreeBytes = minFreeBytes,
            onStopped = { reason ->
                stop = reason
                done.countDown()
            },
        )
        DownloadsPlatformDownloader.start(
            request = DownloadPlatformRequest(
                item = testDownload(1, DownloadStatus.Downloading).copy(
                    sourceUrl = "http://127.0.0.1:${server.address.port}$path",
                    fileName = fileName,
                ),
                limits = limits.takeIf { maxTotalBytes != null || minFreeBytes > 0 },
            ),
            onProgress = { _, _ -> },
            onSuccess = { uri, _ ->
                success = uri
                done.countDown()
            },
            onFailure = { message ->
                failure = message
                done.countDown()
            },
            onPaused = {
                paused = true
                done.countDown()
            },
        )
        assertTrue(done.await(60, TimeUnit.SECONDS), "transfer did not end")
        return Outcome(success, failure, stop, paused)
    }

    private fun partialBytes() = File(downloadsDir, "$fileName.part").takeIf { it.exists() }?.length() ?: 0L

    @Test
    fun a_file_announced_as_larger_than_the_limit_is_refused_before_a_byte_is_written() {
        val outcome = transfer("/sized.mkv", maxTotalBytes = bodyBytes - 1L)

        assertEquals(AutoDownloadStop.StorageLimit, outcome.stop)
        assertNull(outcome.success)
        assertNull(outcome.failure)
        assertFalse(outcome.paused)
        assertEquals(0L, partialBytes())
        assertFalse(File(downloadsDir, fileName).exists())
    }

    @Test
    fun a_file_that_fits_the_limit_exactly_is_downloaded() {
        val outcome = transfer("/sized.mkv", maxTotalBytes = bodyBytes.toLong())

        assertNull(outcome.stop)
        assertNotNull(outcome.success)
        assertEquals(bodyBytes.toLong(), File(downloadsDir, fileName).length())
    }

    @Test
    fun a_body_of_unknown_size_is_stopped_once_it_has_grown_past_the_limit() {
        val limit = 2L * megabyte

        val outcome = transfer("/unsized.mkv", maxTotalBytes = limit)

        assertEquals(AutoDownloadStop.StorageLimit, outcome.stop)
        assertNull(outcome.success)
        assertNull(outcome.failure)
        assertFalse(File(downloadsDir, fileName).exists())
        assertTrue(partialBytes() > limit, "stopped before the limit was crossed")
        assertTrue(partialBytes() <= limit + checkDistance, "wrote ${partialBytes()} bytes with a limit of $limit")
    }

    @Test
    fun a_short_body_of_unknown_size_is_stopped_too() {
        val limit = 300L * 1024

        val outcome = transfer("/short-unsized.mkv", maxTotalBytes = limit)

        assertEquals(AutoDownloadStop.StorageLimit, outcome.stop)
        assertNull(outcome.success)
        assertFalse(File(downloadsDir, fileName).exists())
        assertTrue(partialBytes() <= limit + checkDistance, "wrote ${partialBytes()} bytes with a limit of $limit")
    }

    @Test
    fun a_short_body_of_unknown_size_that_fits_is_completed() {
        val outcome = transfer("/short-unsized.mkv", maxTotalBytes = shortBodyBytes.toLong())

        assertNull(outcome.stop)
        assertNotNull(outcome.success)
    }

    @Test
    fun a_file_that_would_leave_too_little_free_space_is_refused_before_a_byte_is_written() {
        val outcome = transfer("/sized.mkv", maxTotalBytes = null, minFreeBytes = Long.MAX_VALUE)

        assertEquals(AutoDownloadStop.LowDiskSpace, outcome.stop)
        assertNull(outcome.success)
        assertEquals(0L, partialBytes())
    }

    @Test
    fun a_transfer_without_limits_downloads_the_same_files_in_full() {
        val outcome = transfer("/unsized.mkv", maxTotalBytes = null)

        assertNull(outcome.stop)
        assertNotNull(outcome.success)
        assertEquals(bodyBytes.toLong(), File(downloadsDir, fileName).length())
    }
}
