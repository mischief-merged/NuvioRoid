package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.TrustedHomeServers
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DownloadDestinationTrustTest : com.nuvio.app.core.network.UntrustedLocalServerTest() {
    @Test fun `cancellation before the first body byte closes the connection promptly`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sentHeaders = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        server.createContext("/silent") { exchange ->
            exchange.sendResponseHeaders(200, 100_000)
            sentHeaders.countDown()
            finish.await(20, java.util.concurrent.TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val wasTrusted = TrustedHomeServers.contains("127.0.0.1")
        TrustedHomeServers.add("127.0.0.1")
        try {
            val item = testDownload(1, DownloadStatus.Downloading).copy(
                sourceUrl = "http://127.0.0.1:${server.address.port}/silent",
                fileName = "silent-${System.nanoTime()}.mkv",
            )
            val handle = DownloadsPlatformDownloader.start(DownloadPlatformRequest(item), { _, _ -> }, { _, _ -> }, { }, { })
            assertTrue(sentHeaders.await(10, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(100)
            assertTrue(handle.cancelAndWait(2_000), "a silent response kept the transfer open")
        } finally {
            finish.countDown()
            if (!wasTrusted) TrustedHomeServers.remove("127.0.0.1")
            server.stop(0)
        }
    }

    @Test fun `manual and automatic redirect failures preserve the existing partial file`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val hits = AtomicInteger()
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/private")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/private") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.start()
        val wasTrusted = TrustedHomeServers.contains("127.0.0.1")
        TrustedHomeServers.add("127.0.0.1")
        try {
            for (automatic in listOf(false, true)) {
                val part = Files.createTempFile("redirect-partial", ".part").toFile()
                val bytes = byteArrayOf(1, 2, 3, 4)
                part.writeBytes(bytes)
                val item = testDownload(1, DownloadStatus.Downloading, ruleKey = if (automatic) "series|tt1" else null)
                    .copy(sourceUrl = "http://127.0.0.1:${server.address.port}/start")
                assertFailsWith<IOException> { DownloadsPlatformDownloader.transferToTempFile(DownloadPlatformRequest(item), part, { _, _ -> }) }
                assertContentEquals(bytes, part.readBytes())
            }
            assertEquals(0, hits.get())
        } finally {
            if (!wasTrusted) TrustedHomeServers.remove("127.0.0.1")
            server.stop(0)
        }
    }
}
