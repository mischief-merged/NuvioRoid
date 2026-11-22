package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkVerificationCancelTest {
    @Test
    fun pause_during_the_verification_chunk_closes_without_waiting_for_the_socket() {
        val partialBytes = ByteArray(4 * 1024) { 0x11 }
        val totalBytes = 200_000L
        val stem = "chunk-pause-${System.nanoTime()}"
        val fileName = "$stem.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").also { Files.createDirectories(it) }
        val partial = downloadsDir.resolve("$fileName.part")
        Files.write(partial, partialBytes)
        val sniffed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rangeError = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mkv") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val expected = "bytes=0-${partialBytes.size - 1}"
            if (range != expected) {
                rangeError.set(range ?: "no range")
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
                sniffed.countDown()
                return@createContext
            }
            exchange.responseHeaders.add("Content-Range", "bytes 0-${partialBytes.size - 1}/$totalBytes")
            exchange.sendResponseHeaders(206, partialBytes.size.toLong())
            try {
                exchange.responseBody.use { output ->
                    output.write(partialBytes, 0, SniffMinBytes)
                    output.flush()
                    sniffed.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    output.write(partialBytes, SniffMinBytes, partialBytes.size - SniffMinBytes)
                }
            } catch (_: java.io.IOException) {
                sniffed.countDown()
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
                onFailure = { },
                onPaused = { },
            )
            assertTrue(sniffed.await(15, TimeUnit.SECONDS), "verification chunk did not start")
            assertNull(rangeError.get())
            Thread.sleep(200)
            assertTrue(handle.cancelAndWait(5_000), "pausing during the verification chunk waited on the socket")
        } finally {
            release.countDown()
            DownloadsPlatformDownloader.waitBeforeRetry = waitBefore
            server.stop(0)
            downloadsDir.toFile().listFiles()?.filter { it.name.startsWith(stem) }?.forEach { it.delete() }
        }
    }
}
