package com.nuvio.app.core.network

import com.nuvio.app.core.ui.BoundedArtworkClient
import com.nuvio.app.features.addons.httpGetSubtitleText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.autosync.AutoSyncHttpClientKind
import com.nuvio.app.features.autosync.autoSyncHttpGet
import coil3.network.NetworkRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.get
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ReviewRequestPathsTest : UntrustedLocalServerTest() {
    private fun server(block: (String, AtomicInteger) -> Unit) {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/redirect") {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/target")
                exchange.sendResponseHeaders(302, -1)
            } else {
                hits.incrementAndGet()
                exchange.sendResponseHeaders(200, 1)
                exchange.responseBody.use { it.write(1) }
            }
            exchange.close()
        }
        server.start()
        try { block("http://localhost:${server.address.port}", hits) } finally { server.stop(0) }
    }

    @Test fun `animated collections block direct and redirected private destinations`() = server { url, hits ->
        runBlocking {
            assertFailsWith<NetworkPolicyException> {
                BoundedArtworkClient().executeRequest(NetworkRequest(url = url)) { }
            }
            TrustedHomeServers.add("localhost")
            assertFailsWith<NetworkPolicyException> {
                BoundedArtworkClient().executeRequest(NetworkRequest(url = "$url/redirect")) { }
            }
            assertEquals(0, hits.get())
        }
    }

    @Test fun `API requests reject private destinations and keep redirects disabled`() = server { url, hits ->
        runBlocking {
            val client = createApiHttpClient()
            try {
                assertFailsWith<NetworkPolicyException> { client.get(url) }
                TrustedHomeServers.add("localhost")
                assertEquals(302, client.get("$url/redirect").status.value)
                assertEquals(0, hits.get())
            } finally { client.close() }
        }
    }

    @Test fun `now playing image preparation blocks private destinations and redirects`() = server { url, hits ->
        fun denied(address: String) {
            assertFailsWith<NetworkPolicyException> {
                com.nuvio.app.features.player.desktop.WebviewImages.encode(address)
            }
        }
        denied(url)
        TrustedHomeServers.add("localhost")
        denied("$url/redirect")
        assertEquals(0, hits.get())
    }

    @Test fun `container range reads block direct and redirected private destinations`() = server { url, hits ->
        runBlocking {
            suspend fun fetch(path: String) = autoSyncHttpGet(path, mapOf("Range" to "bytes=0-7"), AutoSyncHttpClientKind.CONTAINER_INDEX, 3000) { it.status }
            assertFailsWith<NetworkPolicyException> { fetch(url) }
            TrustedHomeServers.add("localhost")
            assertFailsWith<NetworkPolicyException> { fetch("$url/redirect") }
            assertEquals(0, hits.get())
        }
    }
}

class ReviewResponseLimitsTest : TrustedLocalServerTest() {
    private fun response(size: Int, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            runCatching { exchange.responseBody.use { it.write(ByteArray(size) { 65 }) } }
            exchange.close()
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/subtitle.ass") } finally { server.stop(0) }
    }

    @Test fun `library and cloud responses honor explicit eight and sixteen MiB limits`() {
        for (limit in listOf(8, 16).map { it * 1024 * 1024 }) response(limit - 1) { url ->
            runBlocking {
                val result = httpRequestRaw("GET", url, emptyMap(), "", maxResponseBodyBytes = limit)
                assertEquals(limit - 1, result.bodyBytes.size)
                assertEquals(limit - 1, result.body.length)
            }
        }
    }

    @Test fun `external subtitles can contain more than eight MiB of font attachments`() = response(9 * 1024 * 1024) { url ->
        runBlocking { assertEquals(9 * 1024 * 1024, httpGetSubtitleText(url, emptyMap()).length) }
    }
}
