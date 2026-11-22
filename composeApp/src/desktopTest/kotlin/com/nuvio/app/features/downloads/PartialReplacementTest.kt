package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A partial that cannot be continued stays on disk while its replacement is fetched, and is moved
 * to Trash only after that replacement is accepted.
 */
class PartialReplacementTest {
    private val trashBefore = DownloadsPlatformDownloader.trashPartial
    private val waitBefore = DownloadsPlatformDownloader.waitBeforeRetry
    private val holding = Files.createTempDirectory("nuvio-partial-replacement-trash")
    private val trashed = CopyOnWriteArrayList<Path>()
    private val servers = mutableListOf<HttpServer>()
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    @BeforeTest
    fun setUp() {
        DownloadsRepository.clearLocalState()
        trashed.clear()
        DownloadsPlatformDownloader.waitBeforeRetry = {}
        DownloadsPlatformDownloader.trashPartial = { path ->
            val dest = holding.resolve(path.fileName)
            Files.move(path, dest)
            trashed.add(path)
            true
        }
    }

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it.stop(0) } }
        executors.forEach { it.shutdownNow() }
        DownloadsPlatformDownloader.trashPartial = trashBefore
        DownloadsPlatformDownloader.waitBeforeRetry = waitBefore
        DownloadsRepository.clearLocalState()
        holding.toFile().deleteRecursively()
    }

    @Test
    fun a_range_the_server_rejects_keeps_the_partial_until_the_replacement_is_accepted() {
        replacePartial(firstResponse = FirstResponse.RangeRejected)
    }

    @Test
    fun a_server_that_ignores_a_range_keeps_the_partial_until_the_replacement_is_accepted() {
        replacePartial(firstResponse = FirstResponse.IgnoresRange)
    }

    @Test
    fun a_digest_mismatch_keeps_the_partial_until_the_replacement_is_accepted() {
        replacePartial(firstResponse = FirstResponse.DigestMismatch)
    }

    private enum class FirstResponse { RangeRejected, IgnoresRange, DigestMismatch }

    private fun replacePartial(firstResponse: FirstResponse) {
        val oldBytes = ByteArray(8 * 1024) { (it * 3 + 9).toByte() }
        val replacement = ByteArray(48 * 1024) { (it * 5 + 1).toByte() }
        val stem = "replace-${firstResponse.name.lowercase()}-${System.nanoTime()}"
        val fileName = "$stem.mkv"
        val downloadsDir = DesktopStorage.rootDir.resolve("downloads").also { Files.createDirectories(it) }
        val oldPartial = downloadsDir.resolve("$fileName.part")
        val duringReplacement = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val duringSuccess = AtomicReference<String?>(null)
        val failure = AtomicReference<String?>(null)
        val successUri = AtomicReference<String?>(null)

        if (firstResponse != FirstResponse.DigestMismatch) {
            Files.write(oldPartial, oldBytes)
        }
        val partialKey = if (Files.exists(oldPartial)) DownloadMover.identityOf(oldPartial) else null

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        executors += executor
        server.executor = executor
        server.createContext("/video.mkv") { exchange ->
            val call = calls.incrementAndGet()
            val range = exchange.requestHeaders.getFirst("Range")
            try {
                if (call == 1 && firstResponse == FirstResponse.RangeRejected) {
                    check(range != null) { "the first request did not ask for a range" }
                    exchange.sendResponseHeaders(416, -1)
                    exchange.close()
                    return@createContext
                }
                if (call == 1 && firstResponse == FirstResponse.IgnoresRange) {
                    check(range != null) { "the first request did not ask for a range" }
                    exchange.sendResponseHeaders(200, replacement.size.toLong())
                    exchange.responseBody.use { it.write(replacement) }
                    return@createContext
                }
                val body = replacement
                val headers = if (firstResponse == FirstResponse.DigestMismatch && call == 1) {
                    mapOf("Content-MD5" to md5Base64(body + 1))
                } else if (firstResponse == FirstResponse.DigestMismatch) {
                    mapOf("Content-MD5" to md5Base64(body))
                } else {
                    emptyMap()
                }
                headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { output ->
                    val head = 1024
                    output.write(body, 0, head)
                    output.flush()
                    if (call > 1 || firstResponse == FirstResponse.DigestMismatch && call == 1) {
                        if (call > 1) {
                            duringReplacement.countDown()
                            check(releaseReplacement.await(20, TimeUnit.SECONDS))
                        }
                    }
                    output.write(body, head, body.size - head)
                }
            } catch (_: java.io.IOException) {
                // The first ignored-range body is closed once the partial is known not to continue.
            }
        }
        server.start()
        servers += server

        try {
            DownloadsPlatformDownloader.start(
                request = DownloadPlatformRequest(
                    item = DownloadItem(
                        id = "replace-${System.nanoTime()}",
                        contentType = "movie",
                        parentMetaId = "tt1",
                        parentMetaType = "movie",
                        videoId = "tt1",
                        title = "Replacement",
                        streamTitle = "Replacement",
                        providerName = "test",
                        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mkv",
                        fileName = fileName,
                        status = DownloadStatus.Downloading,
                        createdAtEpochMs = 0L,
                        updatedAtEpochMs = 0L,
                        partialFileKey = partialKey,
                    ),
                    onSupersededPartialsRetired = { finished.countDown() },
                ),
                onProgress = { _, _ -> },
                onSuccess = { uri, _ ->
                    val exists = Files.exists(oldPartial)
                    val same = exists && Files.readAllBytes(oldPartial).contentEquals(expectedOld(firstResponse, oldBytes, replacement))
                    duringSuccess.set("exists=$exists same=$same trashEmpty=${trashed.isEmpty()}")
                    successUri.set(uri)
                },
                onFailure = { message ->
                    failure.set(message)
                    finished.countDown()
                },
                onPaused = { finished.countDown() },
            )

            assertTrue(duringReplacement.await(20, TimeUnit.SECONDS), "replacement body did not start")
            assertTrue(Files.exists(oldPartial), "old partial was removed while the replacement was still downloading")
            assertContentEquals(expectedOld(firstResponse, oldBytes, replacement), Files.readAllBytes(oldPartial))
            assertTrue(trashed.isEmpty(), "old partial was trashed before the replacement was accepted")
            val otherDeadline = System.nanoTime() + 5_000_000_000L
            var otherPartial: Path? = null
            while (otherPartial == null && System.nanoTime() < otherDeadline) {
                otherPartial = Files.list(downloadsDir).use { stream ->
                    stream.filter { it != oldPartial && it.fileName.toString().startsWith(stem) && it.fileName.toString().endsWith(".part") }
                        .findFirst().orElse(null)
                }
                if (otherPartial == null) Thread.sleep(50)
            }
            assertNotNull(otherPartial, "replacement partial was not a separate file")
            releaseReplacement.countDown()
            assertTrue(finished.await(30, TimeUnit.SECONDS), "download did not finish: ${failure.get()}")
            assertEquals(null, failure.get())
            assertEquals("exists=true same=true trashEmpty=true", duringSuccess.get())
            assertNotNull(successUri.get())
            assertContentEquals(replacement, File(URI(successUri.get()!!)).readBytes())
            assertEquals(listOf(oldPartial.toAbsolutePath().normalize()), trashed.map { it.toAbsolutePath().normalize() })
            assertFalse(Files.exists(oldPartial))
            assertContentEquals(expectedOld(firstResponse, oldBytes, replacement), Files.readAllBytes(holding.resolve(oldPartial.fileName)))
        } finally {
            releaseReplacement.countDown()
            downloadsDir.toFile().listFiles()?.filter { it.name.startsWith(stem) }?.forEach { it.delete() }
        }
    }

    private fun expectedOld(firstResponse: FirstResponse, oldBytes: ByteArray, replacement: ByteArray): ByteArray =
        if (firstResponse == FirstResponse.DigestMismatch) replacement else oldBytes

    private fun md5Base64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(bytes))
}
