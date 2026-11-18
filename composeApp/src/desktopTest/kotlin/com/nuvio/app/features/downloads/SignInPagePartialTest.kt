package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** A network sign-in page that arrives where the rest of a video was asked for never replaces the partial file. */
class SignInPagePartialTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private val freeBefore = DownloadsPlatformDownloader.freeBytesProvider
    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        DownloadsPlatformDownloader.freeBytesProvider = freeBefore
        files.forEach { it.delete() }
    }

    @Test
    fun a_sign_in_page_answering_a_resume_keeps_the_partial_file_as_it_was() {
        DownloadsPlatformDownloader.freeBytesProvider = { null }
        val page = "<!DOCTYPE html><html><body>Sign in to the in-flight Wi-Fi</body></html>".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // A captive portal answers every request with its own page and a 200, whatever was asked for.
        server.createContext("/v.mkv") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, page.size.toLong())
            exchange.responseBody.use { it.write(page) }
        }
        server.start()
        val partial = File.createTempFile("nuvio-portal", ".part").also { files += it }
        val kept = ByteArray(300_000) { (it % 251).toByte() }
        partial.writeBytes(kept)
        try {
            val request = DownloadPlatformRequest(
                DownloadItem(
                    id = "portal", contentType = "movie", parentMetaId = "tt0", parentMetaType = "movie", videoId = "tt0",
                    title = "t", streamTitle = "t", providerName = "p", sourceUrl = "http://127.0.0.1:${server.address.port}/v.mkv",
                    fileName = partial.name.removeSuffix(".part"), status = DownloadStatus.Downloading, createdAtEpochMs = 0, updatedAtEpochMs = 0,
                ),
            )
            val outcome = runCatching {
                runBlocking { DownloadsPlatformDownloader.transferToTempFile(request, partial, { _, _ -> }, DownloadSpeedLimiter({ System.nanoTime() })) }
            }
            assertTrue(outcome.isFailure, "a web page was taken for the rest of the video")
            assertContentEquals(kept, partial.readBytes(), "the partial file was replaced or cut")
        } finally {
            server.stop(0)
        }
    }
}
