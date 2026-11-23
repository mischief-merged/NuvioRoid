package com.nuvio.app.core.network

import com.nuvio.app.core.ui.BoundedArtworkClient
import com.nuvio.app.features.addons.httpGetSubtitleText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.autosync.AutoSyncHttpClientKind
import com.nuvio.app.features.autosync.autoSyncHttpGet
import com.nuvio.app.features.player.desktop.WebviewImages
import coil3.network.NetworkRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.get
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.abs
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

class ReviewArtworkShapeTest : TrustedLocalServerTest() {
    private fun picture(width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.RED
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color.BLUE
            graphics.fillRect(width - maxOf(1, width / 8), 0, width, height)
        } finally { graphics.dispose() }
        return image
    }

    private fun serve(image: BufferedImage, block: (String) -> Unit) {
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
            exchange.close()
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/art.png") } finally { server.stop(0) }
    }

    private fun prepared(url: String): BufferedImage {
        val encoded = WebviewImages.encode(url)
        assertNotNull(encoded)
        assertTrue(encoded.startsWith("data:image/png;base64,"))
        return ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(encoded.substringAfter(','))))
    }

    @Test fun `a wide remote picture keeps both edges and fits the longest side`() = serve(picture(1800, 300)) { url ->
        val image = prepared(url)
        assertEquals(1024, image.width)
        assertTrue(image.height in 168..174)
        assertTrue(abs(image.width.toDouble() / image.height - 6.0) < 0.08)
        val left = image.getRGB(0, image.height / 2)
        val right = image.getRGB(image.width - 1, image.height / 2)
        assertTrue((left shr 16) and 0xFF > 200 && (left shr 8) and 0xFF < 30)
        assertTrue(right and 0xFF > 200 && (right shr 16) and 0xFF < 30)
    }

    @Test fun `a remote picture smaller than the bound stays the same size`() = serve(picture(80, 40)) { url ->
        val image = prepared(url)
        assertEquals(80, image.width)
        assertEquals(40, image.height)
    }
}
