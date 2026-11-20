package com.nuvio.app.core.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NativePlaybackTrustTest {
    companion object {
        private val directory = Files.createTempDirectory("native-network-test")
        private val probe: Path by lazy {
            val here = Path.of("").toAbsolutePath()
            val app = if (Files.exists(here.resolve("src/desktopMain/native/macos"))) here else here.resolve("composeApp")
            val native = app.resolve("src/desktopMain/native/macos")
            val architecture = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x86_64"
            val runtime = native.resolve("runtime/$architecture")
            val output = directory.resolve("network-probe")
            run(listOf("xcrun", "clang++", "-std=c++17", "-fobjc-arc", "-Wno-deprecated-declarations", "-I${native}", "-I${native.resolve("include")}",
                app.resolve("src/desktopTest/native/NetworkTrustProbe.mm").toString(), runtime.resolve("libmpv.2.dylib").toString(),
                "-Wl,-rpath,$runtime", "-framework", "Foundation", "-framework", "Security", "-o", output.toString()))
            output
        }
        private fun run(command: List<String>): String {
            val log = Files.createTempFile(directory, "probe-", ".log")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "probe timed out: $log")
            val text = Files.readString(log)
            assertEquals(0, process.exitValue(), text)
            return text
        }
        private fun probe(vararg args: String) = run(listOf(probe.toString()) + args)
    }

    @Test fun `native mpv verifies certificates and hostnames and only an explicit override bypasses trust`() {
        if (!System.getProperty("os.name").contains("Mac", true)) return
        LocalTlsServer().use { tls ->
            val wav = ByteBuffer.allocate(44 + 16_000).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + 16_000); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(16_000)
            }.array()
            tls.server.createContext("/video.wav") { exchange ->
                exchange.responseHeaders.add("Content-Type", "audio/wav")
                exchange.sendResponseHeaders(200, wav.size.toLong()); exchange.responseBody.use { it.write(wav) }
            }
            val system = directory.resolve("system.pem")
            probe("bundle", system.toString())
            assertTrue(Files.readString(system).contains("BEGIN CERTIFICATE"))
            assertTrue(probe("${tls.url}/video.wav", system.toString(), "verify").contains("loaded=0 certificate=1"))
            assertTrue(probe("${tls.url}/video.wav", tls.certFile.toString(), "verify").contains("loaded=1 certificate=0"))
            assertTrue(probe("https://127.0.0.1:${tls.server.address.port}/video.wav", tls.certFile.toString(), "verify").contains("loaded=0 certificate=1"))
            assertTrue(probe("${tls.url}/video.wav", system.toString(), "allow").contains("loaded=1 certificate=0"))
            assertTrue(probe("${tls.url}/video.wav", system.toString(), "retry").contains("loaded=1 certificate=1"))
            probe("headers")
        }
    }

    @Test fun `native artwork accepts bounded encoded images and never fetches URLs`() {
        if (!System.getProperty("os.name").contains("Mac", true)) return
        val input = directory.resolve("artwork.txt")
        for (size in listOf(16, ArtworkMaxBytes + 1)) {
            Files.writeString(input, "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(ByteArray(size) { 1 }))
            assertTrue(probe("artwork", input.toString()).contains(if (size == 16) "received=1" else "received=0"))
        }
        Files.writeString(input, "http://127.0.0.1/poster")
        assertTrue(probe("artwork", input.toString()).contains("received=0"))
    }
}
