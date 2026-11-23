package com.nuvio.app.features.addons

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The raw response carries the body exactly as it came, not the decoded text encoded again. */
class DesktopRawBytesTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private lateinit var server: HttpServer
    private val binary = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x80.toByte(), 0xC0.toByte(), 0x00, 0x7F, 0xFE.toByte()) +
        ByteArray(256) { it.toByte() }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/bin") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, binary.size.toLong())
            exchange.responseBody.use { it.write(binary) }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun bytes_that_are_not_valid_text_arrive_unchanged() = runBlocking {
        val response = httpRequestRaw("GET", "http://127.0.0.1:${server.address.port}/bin", emptyMap(), "")
        assertEquals(200, response.status)
        assertContentEquals(binary, response.bodyBytes)
    }

    @Test
    fun a_limit_cuts_the_bytes_without_adding_to_them() = runBlocking {
        val response = httpRequestRaw("GET", "http://127.0.0.1:${server.address.port}/bin", emptyMap(), "", maxResponseBodyBytes = 10)
        assertEquals(10, response.bodyBytes.size)
        assertContentEquals(binary.copyOf(10), response.bodyBytes)
        assertTrue(response.body.endsWith("[truncated]"))
    }
}
