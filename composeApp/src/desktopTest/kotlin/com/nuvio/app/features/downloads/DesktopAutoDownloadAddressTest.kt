package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAutoDownloadAddressTest : com.nuvio.app.core.network.UntrustedLocalServerTest() {
    private fun address(vararg bytes: Int): InetAddress = InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() })

    private fun address6(first: Int, second: Int, last: Int = 1): InetAddress =
        InetAddress.getByAddress(ByteArray(16).also { it[0] = first.toByte(); it[1] = second.toByte(); it[15] = last.toByte() })

    @Test
    fun loopback_private_link_local_and_shared_addresses_are_private() {
        listOf(
            address(127, 0, 0, 1),
            address(10, 1, 2, 3),
            address(172, 16, 0, 1),
            address(192, 168, 1, 20),
            address(169, 254, 169, 254),
            address(100, 64, 0, 1),
            address(0, 0, 0, 0),
            address6(0, 0),
            address6(0, 0, last = 0),
            address6(0xfe, 0x80),
            address6(0xfd, 0x12),
            address6(0xfc, 0x00),
        ).forEach { assertTrue(isPrivateNetworkAddress(it), it.toString()) }
    }

    @Test
    fun public_addresses_are_not_private() {
        listOf(
            address(93, 184, 216, 34),
            address(172, 32, 0, 1),
            address(100, 128, 0, 1),
            address6(0x26, 0x06),
        ).forEach { assertFalse(isPrivateNetworkAddress(it), it.toString()) }
    }

    @Test
    fun automatic_and_manual_transfers_both_require_explicit_home_server_trust() {
        val requests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mkv") { exchange ->
            requests.incrementAndGet()
            val body = ByteArray(1024) { 7 }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile()
        val fileNames = mutableListOf<String>()

        fun transfer(ruleKey: String?): Pair<String?, String?> {
            val fileName = "nuvio-address-test-${System.nanoTime()}.mkv".also { fileNames += it }
            val done = CountDownLatch(1)
            var success: String? = null
            var failure: String? = null
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    testDownload(1, DownloadStatus.Downloading, ruleKey = ruleKey).copy(
                        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mkv",
                        fileName = fileName,
                    ),
                ),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ ->
                    success = uri
                    done.countDown()
                },
                onFailure = { message ->
                    failure = message
                    done.countDown()
                },
                onPaused = { done.countDown() },
            )
            assertTrue(done.await(60, TimeUnit.SECONDS), "transfer did not end")
            return success to failure
        }

        try {
            val (autoSuccess, autoFailure) = transfer(ruleKey = "series|tt1")
            assertNull(autoSuccess)
            assertNotNull(autoFailure)
            assertEquals(0, requests.get())

            val (manualSuccess, manualFailure) = transfer(ruleKey = null)
            assertNotNull(manualFailure)
            assertNull(manualSuccess)
            assertEquals(0, requests.get())
            com.nuvio.app.core.network.TrustedHomeServers.add("127.0.0.1")
            assertTrue("http://127.0.0.1/video".hasPublicDownloadHost())
            for (rule in listOf(null, "series|tt1")) {
                val (success, failure) = transfer(rule)
                assertNull(failure)
                assertNotNull(success)
            }
            assertEquals(2, requests.get())
        } finally {
            com.nuvio.app.core.network.TrustedHomeServers.remove("127.0.0.1")
            server.stop(0)
            fileNames.forEach {
                File(downloadsDir, it).delete()
                File(downloadsDir, "$it.part").delete()
            }
        }
    }
}
