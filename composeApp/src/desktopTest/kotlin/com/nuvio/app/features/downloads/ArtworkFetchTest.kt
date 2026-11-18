package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real fetch path (the platform HTTP client) against a loopback server: bytes must arrive
 * exactly, through a redirect, for formats whose bytes are not valid text. Nothing leaves the machine.
 */
class ArtworkFetchTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private lateinit var server: HttpServer
    private lateinit var folder: File
    private var now = 1_700_000_000_000L
    private val realGuard = DownloadCompanionGuard.stillWanted
    private val requests = mutableListOf<Pair<String, String?>>()

    /** Every byte value, so that nothing survives being read as text and written back. */
    private val allBytes = ByteArray(256) { it.toByte() }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16) + allBytes + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) + allBytes
    private val webp = "RIFF".encodeToByteArray() + byteArrayOf(0x40, 1, 0, 0) + "WEBP".encodeToByteArray() + "VP8 ".encodeToByteArray() + allBytes

    private fun respond(exchange: HttpExchange, status: Int, type: String, body: ByteArray, location: String? = null) {
        requests += exchange.requestURI.path to exchange.requestHeaders.getFirst("Accept")
        exchange.responseHeaders.add("Content-Type", type)
        location?.let { exchange.responseHeaders.add("Location", it) }
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) } else exchange.close()
    }

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("nuvio-art-fetch").toFile()
        DownloadCompanionGuard.stillWanted = { true }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/p.jpg") { respond(it, 200, "image/jpeg", jpeg) }
        server.createContext("/p.png") { respond(it, 200, "image/png", png) }
        server.createContext("/p.webp") { respond(it, 200, "image/webp", webp) }
        server.createContext("/old") { respond(it, 302, "text/plain", ByteArray(0), location = "/p.png") }
        server.createContext("/missing") { respond(it, 404, "text/plain", "no".encodeToByteArray()) }
        server.createContext("/boom") { respond(it, 503, "text/plain", "busy".encodeToByteArray()) }
        server.createContext("/limited") { respond(it, 429, "text/plain", "slow".encodeToByteArray()) }
        server.createContext("/html") { respond(it, 200, "text/html", "<html>sign in</html>".encodeToByteArray()) }
        server.createContext("/huge") { respond(it, 200, "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(SavedArtwork.MaxBytes + 10)) }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        DownloadCompanionGuard.stillWanted = realGuard
        folder.deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    private fun uri() = File(folder, "a.mkv").also { it.writeText("v") }.toURI().toString()
    private val dir get() = File(folder, "a.mkv.subtitles")

    private fun item(poster: String?) = DownloadItem(
        id = "d1", contentType = "movie", parentMetaId = "tt1", parentMetaType = "movie", videoId = "tt1", title = "Film", poster = poster,
        streamTitle = "s", providerName = "p", sourceUrl = "https://example.com/a.mkv", fileName = "a.mkv", status = DownloadStatus.Completed,
        createdAtEpochMs = 1, updatedAtEpochMs = 2,
    )

    private fun companion() = ArtworkCompanion(nowEpochMs = { now })

    @Test
    fun jpeg_png_and_webp_bytes_arrive_exactly_through_the_real_http_path() = runBlocking {
        for ((path, expected) in listOf("/p.jpg" to jpeg, "/p.png" to png, "/p.webp" to webp)) {
            val fetched = assertIs<ImageFetch.Bytes>(fetchImageFromServer(url(path)), path)
            assertContentEquals(expected, fetched.bytes, path)
            assertNotNull(SavedArtwork.extensionOf(fetched.bytes), path)
        }
    }

    @Test
    fun a_redirect_is_followed_and_the_bytes_are_the_target_image() = runBlocking {
        val fetched = assertIs<ImageFetch.Bytes>(fetchImageFromServer(url("/old")))
        assertContentEquals(png, fetched.bytes)
    }

    @Test
    fun the_request_asks_for_images() = runBlocking {
        fetchImageFromServer(url("/p.jpg"))
        assertTrue("image/" in requests.single().second.orEmpty())
    }

    @Test
    fun the_companion_saves_the_exact_bytes_of_a_poster_reached_through_a_redirect() = runBlocking {
        val uri = uri()

        assertEquals(CompanionOutcome.Saved, companion().run(item(url("/old")), uri))

        assertContentEquals(png, File(dir, "poster.png").readBytes())
        assertFalse(File(dir, "poster.none").exists())
    }

    @Test
    fun a_webp_and_a_jpeg_poster_are_saved_with_their_own_extension_and_bytes() = runBlocking {
        val uri = uri()
        companion().run(item(url("/p.webp")), uri)
        assertContentEquals(webp, File(dir, "poster.webp").readBytes())

        dir.deleteRecursively()
        companion().run(item(url("/p.jpg")), uri)
        assertContentEquals(jpeg, File(dir, "poster.jpg").readBytes())
    }

    // ---- markers ----

    @Test
    fun a_marker_written_by_the_earlier_code_is_ignored_and_the_image_is_tried_once_more() = runBlocking {
        val uri = uri()
        DownloadSubtitleStorage(uri).write("poster.none", now.toString()) // the old format: just a time
        assertNull(ArtworkMarker.read(uri, ArtworkRole.Poster))
        assertTrue(companion().isDue(item(url("/p.jpg")), uri))

        companion().run(item(url("/p.jpg")), uri)

        assertContentEquals(jpeg, File(dir, "poster.jpg").readBytes())
    }

    @Test
    fun a_server_that_says_no_is_a_settled_answer_rechecked_only_after_the_long_age() = runBlocking {
        val uri = uri()
        val item = item(url("/missing"))
        assertEquals(CompanionOutcome.NothingFound, companion().run(item, uri))
        val marker = ArtworkMarker.read(uri, ArtworkRole.Poster)!!
        assertEquals(ArtworkMarker.Reason.Refused, marker.reason)
        assertTrue(marker.answered)

        assertFalse(companion().isDue(item, uri))
        now += SavedArtwork.RecheckAgeMs - 1
        assertFalse(companion().isDue(item, uri))
        now += 1
        assertTrue(companion().isDue(item, uri))
    }

    @Test
    fun a_page_that_is_not_an_image_and_one_that_is_too_large_are_settled_answers() = runBlocking {
        val uri = uri()
        companion().run(item(url("/html")), uri)
        assertEquals(ArtworkMarker.Reason.NotAnImage, ArtworkMarker.read(uri, ArtworkRole.Poster)!!.reason)

        dir.deleteRecursively()
        companion().run(item(url("/huge")), uri)
        assertEquals(ArtworkMarker.Reason.TooLarge, ArtworkMarker.read(uri, ArtworkRole.Poster)!!.reason)
        assertFalse(companion().isDue(item(url("/huge")), uri))
    }

    @Test
    fun a_server_error_or_a_rate_limit_is_a_failure_retried_soon_with_a_growing_cooldown() = runBlocking {
        val uri = uri()
        for (path in listOf("/boom", "/limited")) {
            dir.deleteRecursively()
            val item = item(url(path))
            assertEquals(CompanionOutcome.Failed, companion().run(item, uri), path)
            val marker = ArtworkMarker.read(uri, ArtworkRole.Poster)!!
            assertEquals(ArtworkMarker.Reason.Unreachable, marker.reason)
            assertFalse(marker.answered)

            assertFalse(companion().isDue(item, uri), "not before the cooldown")
            now += DownloadSubtitles.RETRY_COOLDOWN_MS
            assertTrue(companion().isDue(item, uri), "after the cooldown")

            // A second failure waits longer.
            companion().run(item, uri)
            assertEquals(2, ArtworkMarker.read(uri, ArtworkRole.Poster)!!.attempts)
            now += DownloadSubtitles.RETRY_COOLDOWN_MS
            assertFalse(companion().isDue(item, uri), "the second cooldown is longer")
            now += DownloadSubtitles.cooldownAfter(2)
            assertTrue(companion().isDue(item, uri))
        }
    }

    @Test
    fun a_connection_that_cannot_be_made_is_unreachable() = runBlocking {
        val closed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).let { val port = it.address.port; it.stop(0); port }
        assertIs<ImageFetch.Unreachable>(fetchImageFromServer("http://127.0.0.1:$closed/p.jpg"))
        Unit
    }

    @Test
    fun a_marker_that_is_damaged_or_of_another_version_is_ignored() {
        val uri = uri()
        val storage = DownloadSubtitleStorage(uri)
        for (text in listOf("{", "[]", "{\"v\":3,\"reason\":\"refused\",\"at\":5}", "{\"v\":2,\"reason\":\"bogus\",\"at\":5}", "{\"v\":2,\"reason\":\"refused\"}")) {
            storage.write("poster.none", text)
            assertNull(ArtworkMarker.read(uri, ArtworkRole.Poster), text)
        }
    }
}
