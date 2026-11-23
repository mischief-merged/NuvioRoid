package com.nuvio.app.core.network

import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PluginDeliveryTlsTest {
    @Test fun `HTTPS code delivery validates certificates and refuses a downgrade redirect`() {
        LocalTlsServer().use { tls ->
            val hits = AtomicInteger()
            val plain = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            plain.createContext("/code") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }
            plain.start()
            try {
                tls.server.createContext("/code") { exchange -> exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("ok".toByteArray()) } }
                tls.server.createContext("/redirect") { exchange ->
                    exchange.responseHeaders.add("Location", "http://127.0.0.1:${plain.address.port}/code")
                    exchange.sendResponseHeaders(302, -1); exchange.close()
                }
                val client = guardedHttpClient(true, DestinationPolicy({ true })).build()
                assertFailsWith<IOException> { client.newCall(Request.Builder().url("${tls.url}/code").build()).execute().close() }
                val trusted = guardedHttpClient(true, DestinationPolicy({ true }), tlsTrust = tls.trustManager).sslSocketFactory(tls.context.socketFactory, tls.trustManager).build()
                trusted.newCall(Request.Builder().url("${tls.url}/code").build()).execute().use { assertEquals("ok", it.body!!.string()) }
                assertFailsWith<IOException> { trusted.newCall(Request.Builder().url("${tls.url}/redirect").build()).execute().close() }
                assertEquals(0, hits.get())
            } finally { plain.stop(0) }
        }
    }
}
