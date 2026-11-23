package com.nuvio.app.core.network

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.core.ui.BoundedArtworkClient
import coil3.network.NetworkRequest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.io.IOException
import kotlin.test.*

class DesktopPayloadLimitsTest : TrustedLocalServerTest() {
    @Test fun `metadata plugin and artwork paths enforce their own body limits`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val size = if (exchange.requestURI.path == "/plugin") PluginMaxBytes + 1 else MetadataMaxBytes + 1
            exchange.sendResponseHeaders(200, 0)
            runCatching { exchange.responseBody.use { it.write(ByteArray(size) { 65 }) } }
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            assertFailsWith<IOException> { httpGetText("$url/metadata") }
            val preview = httpRequestRaw("GET", "$url/plugin", emptyMap(), "")
            assertEquals(1024 * 1024, preview.bodyBytes.size)
            assertTrue(preview.body.endsWith("[truncated]"))
            assertFailsWith<IOException> { BoundedArtworkClient().executeRequest(NetworkRequest(url = "$url/artwork")) { } }
        } finally { server.stop(0) }
        Unit
    }
}
