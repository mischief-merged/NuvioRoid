package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_not_enough_space
import org.jetbrains.compose.resources.getString
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A download the user started checks the room on the disk against the size the server announces. Loopback only. */
class DesktopDownloadFreeSpaceTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private val payload = ByteArray(2 * 1024 * 1024) { it.toByte() }

    private class Outcome(val success: Boolean, val message: String?)

    private fun download(freeBytes: Long?, announce: Boolean = true): Outcome {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mkv") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, if (announce) payload.size.toLong() else 0L)
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        val fileName = "nuvio-space-test-${System.nanoTime()}.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile()
        val done = CountDownLatch(1)
        var success = false
        var message: String? = null
        val before = DownloadsPlatformDownloader.freeBytesProvider
        DownloadsPlatformDownloader.freeBytesProvider = { freeBytes }
        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    DownloadItem(
                        id = "space-test", contentType = "movie", parentMetaId = "tt0", parentMetaType = "movie", videoId = "tt0",
                        title = "Space test", streamTitle = "Space test", providerName = "test",
                        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mkv", fileName = fileName,
                        status = DownloadStatus.Downloading, createdAtEpochMs = 0L, updatedAtEpochMs = 0L,
                    ),
                ),
                onProgress = { _, _ -> },
                onSuccess = { _, _ ->
                    success = true
                    done.countDown()
                },
                onFailure = {
                    message = it
                    done.countDown()
                },
                onPaused = { done.countDown() },
            )
            assertTrue(done.await(60, TimeUnit.SECONDS), "the download did not end")
            if (!success) assertFalse(File(downloadsDir, "$fileName.part").exists(), "a partial file was left")
            assertEquals(1, requests.get(), "the source was asked more than once")
            return Outcome(success, message)
        } finally {
            DownloadsPlatformDownloader.freeBytesProvider = before
            server.stop(0)
            File(downloadsDir, fileName).delete()
            File(downloadsDir, "$fileName.part").delete()
        }
    }

    @Test
    fun a_file_announced_as_larger_than_the_free_space_fails_with_a_plain_message_before_anything_is_written() {
        val outcome = download(freeBytes = payload.size - 1L)

        assertFalse(outcome.success)
        assertEquals(runBlocking { getString(Res.string.downloads_error_not_enough_space) }, outcome.message)
        assertEquals(DownloadFailureKind.NotEnoughSpace, classifyDownloadFailure(outcome.message!!))
    }

    @Test
    fun a_file_that_fits_exactly_is_downloaded() {
        val outcome = download(freeBytes = payload.size.toLong())

        assertTrue(outcome.success, outcome.message)
        assertNull(outcome.message)
    }

    @Test
    fun a_disk_that_does_not_say_how_much_room_it_has_is_not_a_reason_to_refuse() {
        assertTrue(download(freeBytes = null).success)
    }
}
