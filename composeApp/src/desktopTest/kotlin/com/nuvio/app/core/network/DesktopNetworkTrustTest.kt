package com.nuvio.app.core.network

import com.sun.net.httpserver.HttpServer
import okhttp3.Dns
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream
import kotlin.test.*

class DesktopNetworkTrustTest {
    private fun localServer(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }
    private fun client(trusted: (String) -> Boolean = { false }, dns: Dns = Dns.SYSTEM, https: Boolean = false) =
        guardedHttpClient(https, DestinationPolicy(trusted, dns)).build()
    private fun get(client: okhttp3.OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test fun `private literal never reaches the server unless its exact host is trusted`() = localServer { server, url ->
        val hits = AtomicInteger()
        server.createContext("/") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("ok".toByteArray()) } }
        assertFailsWith<IOException> { get(client(), url) }
        assertEquals(0, hits.get())
        get(client({ it == "127.0.0.1" }), url).use { assertEquals("ok", it.body!!.string()) }
        assertEquals(1, hits.get())
    }

    @Test fun `every redirect is checked and a trusted first host does not trust the next`() = localServer { server, url ->
        val hits = AtomicInteger()
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/secret")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/secret") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }
        assertFailsWith<IOException> { get(client({ it == "127.0.0.1" }), "$url/start") }
        assertEquals(0, hits.get())
        get(client({ it in setOf("127.0.0.1", "localhost") }), "$url/start").use { assertEquals(204, it.code) }
        assertEquals(1, hits.get())
    }

    @Test fun `mixed DNS answers are rejected and a later private answer cannot rebind`() {
        var answers = listOf(InetAddress.getByName("93.184.216.34"))
        val policy = DestinationPolicy({ false }, object : Dns { override fun lookup(hostname: String) = answers })
        assertEquals(answers.single().hostAddress, policy.dns.lookup("source.test").single().hostAddress)
        answers = answers + InetAddress.getByName("127.0.0.1")
        assertFailsWith<IOException> { policy.dns.lookup("source.test") }
        // The socket independently checks the concrete address, without another DNS lookup.
        policy.sockets.createSocket().use { socket ->
            assertFailsWith<IOException> { socket.connect(InetSocketAddress(InetAddress.getByAddress("source.test", byteArrayOf(127, 0, 0, 1)), 1234), 50) }
        }
    }

    @Test fun `removing trust also blocks a pooled connection`() = localServer { server, url ->
        var trusted = true
        val hits = AtomicInteger()
        server.createContext("/") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("ok".toByteArray()) } }
        val client = client({ trusted })
        get(client, url).use { it.body!!.string() }
        trusted = false
        assertFailsWith<IOException> { get(client, url) }
        assertEquals(1, hits.get())
    }

    @Test fun `plugin delivery rejects HTTP even for a trusted home server`() = localServer { _, url ->
        assertFailsWith<IOException> { get(client({ true }, https = true), url) }
    }

    @Test fun `response cap rejects announced chunked and decompressed bodies`() = localServer { server, url ->
        val bytes = ByteArray(4097) { 65 }
        server.createContext("/length") { exchange -> exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
        server.createContext("/chunked") { exchange -> exchange.sendResponseHeaders(200, 0); exchange.responseBody.use { it.write(bytes) } }
        server.createContext("/gzip") { exchange ->
            val compressed = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gzip -> gzip.write(bytes) } }.toByteArray()
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, compressed.size.toLong()); exchange.responseBody.use { it.write(compressed) }
        }
        val client = client({ true })
        for (path in listOf("length", "chunked", "gzip")) {
            get(client, "$url/$path").use { response -> assertFailsWith<IOException> { response.body!!.readBounded(4096) } }
        }
        get(client, "$url/chunked").use { assertContentEquals(bytes, it.body!!.readBounded(bytes.size)) }
    }

    @Test fun `special address ranges and host spelling cannot bypass the policy`() {
        for (host in listOf("0.0.0.0", "127.0.0.2", "10.0.0.2", "172.16.0.1", "192.168.1.2", "169.254.169.254", "100.64.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::")) {
            assertTrue(isLocalDestination(InetAddress.getByName(host)), host)
        }
        assertFalse(isLocalDestination(InetAddress.getByName("1.1.1.1")))
        assertFalse(isLocalDestination(InetAddress.getByName("2606:4700:4700::1111")))
        assertEquals("home.local", canonicalHost("https://HOME.local.:8443/library"))
        assertNull(canonicalHost("https://user:password@home.local"))
    }
}
