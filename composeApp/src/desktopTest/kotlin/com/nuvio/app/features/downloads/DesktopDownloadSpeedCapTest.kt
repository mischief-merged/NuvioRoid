package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDownloadSpeedCapTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    @Test
    fun a_transfer_reports_every_byte_to_its_limiter_and_preserves_the_file() = runBlocking {
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        val delays = mutableListOf<Long>()
        var now = 0L
        val limiter = DownloadSpeedLimiter({ now }) {
            delays += it
            now += it * 1_000_000L
        }
        limiter.bytesPerSecond = 100_000L
        withLocalServer(payload) { url, directory ->
            val file = directory.resolve("download.part")
            val total = DownloadsPlatformDownloader.transferToTempFile(request(url), file, { _, _ -> }, limiter).totalBytes
            assertEquals(payload.size.toLong(), total)
            assertContentEquals(payload, file.readBytes())
            // The total is independent of how the HTTP stream splits the body into reads.
            assertEquals(1000L, delays.sum())
            assertTrue(delays.all { it in 1L..50L })
        }
    }

    @Test
    fun two_transfers_started_with_the_stored_limit_share_one_budget() = runBlocking {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        keepingTheStoredLimit {
            DownloadSpeedStorage.saveBytesPerSecond(400_000L)
            withLocalServer(payload) { url, directory ->
                val files = List(2) { directory.resolve("download-$it.part") }
                val startedNanos = System.nanoTime()
                coroutineScope {
                    files.forEach { file ->
                        launch(Dispatchers.IO) {
                            DownloadsPlatformDownloader.transferToTempFile(request(url), file, { _, _ -> })
                        }
                    }
                }
                val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
                files.forEach { assertContentEquals(payload, it.readBytes()) }
                assertEquals(400_000L, DownloadBandwidth.userCapBytesPerSecond.value)
                // 400 kB at 400 kB/s takes one second on a shared budget. A budget per transfer would
                // need half that, and an ignored setting a few milliseconds.
                assertTrue(elapsedMs >= 900L, "finished after $elapsedMs ms")
            }
        }
    }

    @Test
    fun the_chosen_limit_is_stored_and_applied_to_the_shared_limiter() = keepingTheStoredLimit {
        DownloadBandwidth.setUserCap(2_000_000L)
        assertEquals(2_000_000L, DownloadSpeedStorage.loadBytesPerSecond())
        assertEquals(2_000_000L, DownloadBandwidth.limiter.bytesPerSecond)

        DownloadBandwidth.setUserCap(null)
        assertNull(DownloadSpeedStorage.loadBytesPerSecond())
        assertNull(DownloadBandwidth.limiter.bytesPerSecond)
    }

    private inline fun keepingTheStoredLimit(block: () -> Unit) {
        val stored = DownloadSpeedStorage.loadBytesPerSecond()
        try {
            block()
        } finally {
            DownloadBandwidth.setUserCap(stored)
        }
    }

    private inline fun withLocalServer(payload: ByteArray, block: (url: String, directory: File) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val directory = Files.createTempDirectory("nuvio-speed-test").toFile()
        server.createContext("/video") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/video", directory)
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }

    private fun request(sourceUrl: String) = DownloadPlatformRequest(
        DownloadItem(
            id = "speed-test", contentType = "movie", parentMetaId = "tt0",
            parentMetaType = "movie", videoId = "tt0", title = "Speed test",
            streamTitle = "Speed test", providerName = "test",
            sourceUrl = sourceUrl,
            fileName = "download.bin", status = DownloadStatus.Downloading,
            createdAtEpochMs = 0L, updatedAtEpochMs = 0L,
        ),
    )
}
