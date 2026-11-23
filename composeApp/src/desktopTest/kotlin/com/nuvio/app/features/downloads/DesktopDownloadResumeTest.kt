package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDownloadResumeTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    @Test
    fun dropped_connections_resume_from_the_partial_file_instead_of_failing() {
        val payload = Random(42).nextBytes(3 * 1024 * 1024)
        val dropAfterBytes = 512 * 1024
        val droppedResponses = 2
        val requestCount = AtomicInteger(0)
        val rangeHeaders = mutableListOf<String?>()

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mkv") { exchange ->
            val requestIndex = requestCount.getAndIncrement()
            val range = exchange.requestHeaders.getFirst("Range")
            synchronized(rangeHeaders) { rangeHeaders += range }
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val body = payload.copyOfRange(start, payload.size)
            if (start > 0) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            }
            exchange.sendResponseHeaders(if (start > 0) 206 else 200, body.size.toLong())
            exchange.responseBody.use { output ->
                if (requestIndex < droppedResponses) {
                    output.write(body, 0, dropAfterBytes)
                    output.flush()
                    // Close the socket mid-body, like a CDN or flaky network dropping the connection.
                    exchange.close()
                } else {
                    output.write(body)
                }
            }
        }
        server.start()

        val fileName = "nuvio-resume-test-${System.nanoTime()}.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile()
        val completed = CountDownLatch(1)
        var successUri: String? = null
        var failureMessage: String? = null

        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    testItem(
                        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mkv",
                        fileName = fileName,
                    ),
                ),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ ->
                    successUri = uri
                    completed.countDown()
                },
                onFailure = { message ->
                    failureMessage = message
                    completed.countDown()
                },
                onPaused = { completed.countDown() },
            )

            assertTrue(completed.await(60, TimeUnit.SECONDS), "download did not finish")
            assertNull(failureMessage, "download failed instead of resuming")
            val downloaded = File(URI(successUri!!))
            assertContentEquals(payload, downloaded.readBytes())
            assertEquals(droppedResponses + 1, requestCount.get())
            synchronized(rangeHeaders) {
                assertNull(rangeHeaders.first())
                assertTrue(rangeHeaders.drop(1).all { it != null && it.startsWith("bytes=") && it != "bytes=0-" })
            }
        } finally {
            server.stop(0)
            File(downloadsDir, fileName).delete()
            File(downloadsDir, "$fileName.part").delete()
        }
    }

    @Test
    fun a_download_cut_off_when_the_app_was_closed_stays_downloading_and_one_the_user_paused_stays_paused() {
        val running = testItem("https://example.com/a.mkv", "a.mkv")
        val paused = running.copy(status = DownloadStatus.Paused)

        assertEquals(DownloadStatus.Downloading, DownloadsPlatformDownloader.restoreItem(running).status)
        assertEquals(DownloadStatus.Paused, DownloadsPlatformDownloader.restoreItem(paused).status)
    }

    @Test
    fun a_download_that_is_started_again_goes_on_from_its_partial_file_with_a_range_request() {
        val payload = Random(7).nextBytes(2 * 1024 * 1024)
        val alreadyThere = 700 * 1024
        val rangeHeaders = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mkv") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            synchronized(rangeHeaders) { rangeHeaders += range }
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val body = payload.copyOfRange(start, payload.size)
            if (start > 0) {
                exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            }
            exchange.sendResponseHeaders(if (start > 0) 206 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val fileName = "nuvio-relaunch-test-${System.nanoTime()}.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile().also { it.mkdirs() }
        File(downloadsDir, "$fileName.part").writeBytes(payload.copyOfRange(0, alreadyThere))
        // The download created this partial file in an earlier run and recorded its identity then.
        val partialKey = DownloadMover.identityOf(File(downloadsDir, "$fileName.part").toPath())
        val completed = CountDownLatch(1)
        var successUri: String? = null

        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(testItem("http://127.0.0.1:${server.address.port}/video.mkv", fileName).copy(partialFileKey = partialKey)),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ ->
                    successUri = uri
                    completed.countDown()
                },
                onFailure = { completed.countDown() },
                onPaused = { completed.countDown() },
            )

            assertTrue(completed.await(60, TimeUnit.SECONDS), "download did not finish")
            assertContentEquals(payload, File(URI(successUri!!)).readBytes())
            assertEquals(listOf<String?>("bytes=$alreadyThere-"), synchronized(rangeHeaders) { rangeHeaders.toList() })
        } finally {
            server.stop(0)
            File(downloadsDir, fileName).delete()
            File(downloadsDir, "$fileName.part").delete()
        }
    }

    /**
     * Downloads [payload] from a server whose first [failingRequests] requests are cut off before
     * any of the body arrives, without waiting between attempts.
     */
    private fun downloadFromFailingServer(payload: ByteArray, failingRequests: Int, offlineWhile: (requests: Int) -> Boolean): Outcome {
        val requests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mkv") { exchange ->
            val failing = requests.getAndIncrement() < failingRequests
            exchange.sendResponseHeaders(200, payload.size.toLong())
            if (failing) exchange.close() else exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        val fileName = "nuvio-offline-test-${System.nanoTime()}.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile().also { it.mkdirs() }
        val done = CountDownLatch(1)
        var successUri: String? = null
        var failure: String? = null
        val isOffline = DownloadsPlatformDownloader.isOffline
        val waitBeforeRetry = DownloadsPlatformDownloader.waitBeforeRetry
        DownloadsPlatformDownloader.isOffline = { offlineWhile(requests.get()) }
        DownloadsPlatformDownloader.waitBeforeRetry = {}
        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(testItem("http://127.0.0.1:${server.address.port}/video.mkv", fileName)),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ ->
                    successUri = uri
                    done.countDown()
                },
                onFailure = { message ->
                    failure = message
                    done.countDown()
                },
                onPaused = { done.countDown() },
            )
            assertTrue(done.await(60, TimeUnit.SECONDS), "download did not finish")
            if (successUri != null) assertContentEquals(payload, File(URI(successUri!!)).readBytes())
            return Outcome(successUri, failure, digestVerified = false, requests = requests.get())
        } finally {
            DownloadsPlatformDownloader.isOffline = isOffline
            DownloadsPlatformDownloader.waitBeforeRetry = waitBeforeRetry
            server.stop(0)
            File(downloadsDir, fileName).delete()
            File(downloadsDir, "$fileName.part").delete()
        }
    }

    @Test
    fun a_download_cut_off_while_the_internet_is_gone_is_not_failed_and_finishes_once_it_is_back() {
        val outcome = downloadFromFailingServer(Random(3).nextBytes(256 * 1024), failingRequests = 25) { requests -> requests < 25 }

        assertNull(outcome.failure)
        assertEquals(26, outcome.requests)
    }

    @Test
    fun a_source_that_keeps_failing_while_the_internet_is_there_fails_after_its_attempts() {
        val outcome = downloadFromFailingServer(Random(3).nextBytes(256 * 1024), failingRequests = 25) { false }

        assertNull(outcome.successUri)
        assertEquals(11, outcome.requests)
    }

    @Test
    fun a_transfer_that_goes_silent_is_cut_off_and_goes_on_from_what_it_had_received() {
        val payload = Random(5).nextBytes(1024 * 1024)
        val sentBeforeSilence = 256 * 1024
        val rangeHeaders = mutableListOf<String?>()
        val silence = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/video.mkv") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            synchronized(rangeHeaders) { rangeHeaders += range }
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            if (start > 0) exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            exchange.sendResponseHeaders(if (start > 0) 206 else 200, (payload.size - start).toLong())
            if (start == 0) {
                exchange.responseBody.write(payload, 0, sentBeforeSilence)
                exchange.responseBody.flush()
                silence.await()
                exchange.close()
            } else {
                exchange.responseBody.use { it.write(payload, start, payload.size - start) }
            }
        }
        server.start()
        val fileName = "nuvio-stall-test-${System.nanoTime()}.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile().also { it.mkdirs() }
        val done = CountDownLatch(1)
        var successUri: String? = null
        val stallTimeoutMs = DownloadsPlatformDownloader.stallTimeoutMs
        val waitBeforeRetry = DownloadsPlatformDownloader.waitBeforeRetry
        DownloadsPlatformDownloader.stallTimeoutMs = 400L
        DownloadsPlatformDownloader.waitBeforeRetry = {}
        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(testItem("http://127.0.0.1:${server.address.port}/video.mkv", fileName)),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ ->
                    successUri = uri
                    done.countDown()
                },
                onFailure = { done.countDown() },
                onPaused = { done.countDown() },
            )

            assertTrue(done.await(60, TimeUnit.SECONDS), "download did not finish")
            assertContentEquals(payload, File(URI(successUri!!)).readBytes())
            val ranges = synchronized(rangeHeaders) { rangeHeaders.toList() }
            assertEquals(2, ranges.size)
            assertNull(ranges[0])
            assertTrue(ranges[1]!!.removePrefix("bytes=").removeSuffix("-").toInt() in 1..sentBeforeSilence)
        } finally {
            DownloadsPlatformDownloader.stallTimeoutMs = stallTimeoutMs
            DownloadsPlatformDownloader.waitBeforeRetry = waitBeforeRetry
            silence.countDown()
            server.stop(0)
            File(downloadsDir, fileName).delete()
            File(downloadsDir, "$fileName.part").delete()
        }
    }

    private class Outcome(val successUri: String?, val failure: String?, val digestVerified: Boolean, val requests: Int)

    /** Serves [body] with [headers] and downloads it, optionally on top of a partial file. */
    private fun downloadWithHeaders(body: ByteArray, headers: Map<String, String>, partialBytes: Int = 0): Outcome {
        val requests = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video.mkv") { exchange ->
            requests.incrementAndGet()
            val start = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            if (start > 0) exchange.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
            exchange.sendResponseHeaders(if (start > 0) 206 else 200, (body.size - start).toLong())
            exchange.responseBody.use { it.write(body, start, body.size - start) }
        }
        server.start()
        val fileName = "nuvio-digest-test-${System.nanoTime()}.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile().also { it.mkdirs() }
        if (partialBytes > 0) File(downloadsDir, "$fileName.part").writeBytes(body.copyOfRange(0, partialBytes))
        // The download created this partial file in an earlier run and recorded its identity then.
        val partialKey = if (partialBytes > 0) DownloadMover.identityOf(File(downloadsDir, "$fileName.part").toPath()) else null
        val done = CountDownLatch(1)
        var successUri: String? = null
        var failure: String? = null
        var digestVerified = false
        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    testItem("http://127.0.0.1:${server.address.port}/video.mkv", fileName).copy(partialFileKey = partialKey),
                    onDigestVerified = { digestVerified = true },
                ),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ ->
                    successUri = uri
                    done.countDown()
                },
                onFailure = { message ->
                    failure = message
                    done.countDown()
                },
                onPaused = { done.countDown() },
            )
            assertTrue(done.await(60, TimeUnit.SECONDS), "download did not finish")
            if (successUri != null) assertContentEquals(body, File(URI(successUri!!)).readBytes())
            val leftBehind = File(downloadsDir, "$fileName.part").exists() || (failure != null && File(downloadsDir, fileName).exists())
            assertEquals(false, leftBehind && failure != null, "a failed download left a file behind")
            return Outcome(successUri, failure, digestVerified, requests.get())
        } finally {
            server.stop(0)
            File(downloadsDir, fileName).delete()
            File(downloadsDir, "$fileName.part").delete()
        }
    }

    private fun md5Base64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(bytes))

    private fun md5Hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun a_body_that_matches_the_content_md5_of_its_server_is_reported_as_verified() {
        val body = Random(11).nextBytes(300 * 1024)

        val outcome = downloadWithHeaders(body, mapOf("Content-MD5" to md5Base64(body)))

        assertTrue(outcome.successUri != null)
        assertTrue(outcome.digestVerified)
    }

    @Test
    fun a_body_that_does_not_match_the_content_md5_of_its_server_fails_and_leaves_no_file() {
        val body = Random(12).nextBytes(300 * 1024)

        val outcome = downloadWithHeaders(body, mapOf("Content-MD5" to md5Base64(body + 1)))

        assertNull(outcome.successUri)
        assertTrue(outcome.failure != null)
        assertEquals(false, outcome.digestVerified)
        assertEquals(1, outcome.requests)
    }

    @Test
    fun the_etag_of_a_multipart_upload_is_not_taken_for_a_digest() {
        val body = Random(13).nextBytes(300 * 1024)

        val outcome = downloadWithHeaders(body, mapOf("ETag" to "\"${md5Hex(body + 1)}-4\"", "x-amz-request-id" to "ABC"))

        assertTrue(outcome.successUri != null)
        assertEquals(false, outcome.digestVerified)
    }

    @Test
    fun a_body_without_a_digest_is_downloaded_and_not_reported_as_verified() {
        val body = Random(14).nextBytes(300 * 1024)

        val outcome = downloadWithHeaders(body, emptyMap())

        assertTrue(outcome.successUri != null)
        assertEquals(false, outcome.digestVerified)
    }

    @Test
    fun a_download_that_goes_on_from_a_partial_file_is_not_checked_against_a_digest() {
        val body = Random(15).nextBytes(300 * 1024)

        val outcome = downloadWithHeaders(body, mapOf("Content-MD5" to md5Base64(body + 1)), partialBytes = 100 * 1024)

        assertTrue(outcome.successUri != null, outcome.failure)
        assertEquals(false, outcome.digestVerified)
    }

    private fun testItem(sourceUrl: String, fileName: String) = DownloadItem(
        id = "resume-test",
        contentType = "movie",
        parentMetaId = "tt0000000",
        parentMetaType = "movie",
        videoId = "tt0000000",
        title = "Resume test",
        streamTitle = "Resume test",
        providerName = "test",
        sourceUrl = sourceUrl,
        fileName = fileName,
        status = DownloadStatus.Downloading,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )
}
