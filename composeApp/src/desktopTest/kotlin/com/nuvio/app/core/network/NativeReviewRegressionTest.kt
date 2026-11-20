package com.nuvio.app.core.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NativeReviewRegressionTest {
    companion object {
        private val directory = Files.createTempDirectory("native-review-test")
        private val binaries = mutableMapOf<String, Path>()
        private fun run(command: List<String>): String {
            val log = Files.createTempFile(directory, "probe-", ".log")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "probe timed out: $log")
            val output = Files.readString(log)
            assertEquals(0, process.exitValue(), output)
            return output
        }
        private fun probe(source: String, vararg args: String) {
            if (!System.getProperty("os.name").contains("Mac", true)) return
            val binary = binaries.getOrPut(source) {
                val here = Path.of("").toAbsolutePath()
                val app = if (Files.exists(here.resolve("src/desktopMain/native/macos"))) here else here.resolve("composeApp")
                val native = app.resolve("src/desktopMain/native/macos")
                val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x86_64"
                val runtime = native.resolve("runtime/$arch")
                val java = Path.of(System.getProperty("java.home"))
                val output = directory.resolve(source)
                val frameworks = listOf("Foundation", "Security", "AppKit", "IOKit", "OpenGL", "QuartzCore", "WebKit", "Metal", "MediaPlayer", "Network")
                run(listOf("xcrun", "clang++", "-std=c++17", "-fobjc-arc", "-Wno-deprecated-declarations", "-I$native", "-I${native.resolve("include")}", "-I${java.resolve("include")}", "-I${java.resolve("include/darwin")}",
                    app.resolve("src/desktopTest/native/$source.mm").toString(), runtime.resolve("libmpv.2.dylib").toString(), "-Wl,-rpath,$runtime") +
                    frameworks.flatMap { listOf("-framework", it) } + listOf("-o", output.toString()))
                output
            }
            run(listOf(binary.toString()) + args)
        }
    }
    @Test fun `unconditional trust survives scoped sibling settings`() = probe("TrustSettingsProbe", "siblings")
    @Test fun `CA signing key usage permits an inspection anchor`() = probe("TrustSettingsProbe", "usage")
    @Test fun `SSL server policy permits an inspection anchor`() = probe("TrustSettingsProbe", "ssl-server")
    @Test fun `denials and host only trust are not broadened into global anchors`() = probe("TrustSettingsProbe", "deny")
    @Test fun `system evaluator checks the supplied chain and hostname without installing a CA`() {
        LocalTlsServer().use { probe("TrustSettingsProbe", "evaluate", it.leafFile.toString(), it.certFile.toString()) }
    }
    @Test fun `no path monitor means no playback bypass identity`() = probe("PlayerTrustLifecycleProbe", "monitor")
    @Test fun `download monitor shutdown leaves playback monitoring active`() = probe("PlayerTrustLifecycleProbe", "download-monitor-stop")
    @Test fun `playback monitor cancellation revokes the bypass identity`() = probe("PlayerTrustLifecycleProbe", "playback-monitor-cancel")
    @Test fun `a queued network change restores verification even after returning home`() = probe("PlayerTrustLifecycleProbe", "queued-network-change")
    @Test fun `startup failure cleans resources before the wakeup callback`() = probe("PlayerTrustLifecycleProbe", "before-wakeup")
    @Test fun `startup failure cleans resources after the wakeup callback`() = probe("PlayerTrustLifecycleProbe", "after-wakeup")
    @Test fun `native now playing artwork cannot open remote addresses`() {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet(); exchange.sendResponseHeaders(200, 1); exchange.responseBody.use { it.write(0) }
        }
        server.start()
        try {
            probe("PlayerTrustLifecycleProbe", "artwork", "http://127.0.0.1:${server.address.port}/poster")
            assertEquals(0, hits.get())
        } finally { server.stop(0) }
    }
}
