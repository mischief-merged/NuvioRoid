package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review 5 follow-up, 2b: the downloader resumes, cuts back or removes only a partial file it created, proven
 * by the identity it recorded then; and removes a just-published file only while it is still that file.
 */
class PartialOwnershipTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private val dir = Files.createTempDirectory("nuvio-partial-own").toRealPath()
    private val payload = Random(7).nextBytes(64 * 1024)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v.mkv") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        start()
    }

    @AfterTest
    fun clean_up() {
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    private fun request(name: String) = DownloadPlatformRequest(
        DownloadItem(
            id = "own", contentType = "movie", parentMetaId = "tt0", parentMetaType = "movie", videoId = "tt0",
            title = "t", streamTitle = "t", providerName = "p", sourceUrl = "http://127.0.0.1:${server.address.port}/v.mkv", fileName = name,
            status = DownloadStatus.Downloading, createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ),
    )

    private fun limiter() = DownloadSpeedLimiter(nanoTime = System::nanoTime).also { it.bytesPerSecond = null }

    @Test
    fun a_users_file_at_the_partial_name_is_never_resumed_cut_back_or_removed() {
        val users = dir.resolve("Film.mkv.part").also { Files.writeString(it, "a file of the user's") }

        assertFailsWith<PartialNotOurs> {
            runBlocking { DownloadsPlatformDownloader.transferToTempFile(request("Film.mkv"), users.toFile(), { _, _ -> }, limiter(), OwnedPartial(null)) }
        }
        assertFailsWith<PartialNotOurs> {
            runBlocking { DownloadsPlatformDownloader.transferToTempFile(request("Film.mkv"), users.toFile(), { _, _ -> }, limiter(), OwnedPartial("(dev=1,ino=2)")) }
        }

        assertEquals("a file of the user's", Files.readString(users))
        assertEquals("Film (2).mkv", freeDownloadName(dir, "Film.mkv"))
    }

    @Test
    fun a_partial_that_cannot_be_continued_is_kept_when_the_fresh_download_is_refused_for_lack_of_room() {
        // The source answers a resume request with the whole file, so the partial would be started over.
        val part = dir.resolve("Film.mkv.part").also { Files.write(it, ByteArray(10) { 1 }) }
        val before = DownloadsPlatformDownloader.freeBytesProvider
        DownloadsPlatformDownloader.freeBytesProvider = { 100L }
        try {
            assertFailsWith<IllegalStateException> {
                runBlocking { DownloadsPlatformDownloader.transferToTempFile(request("Film.mkv"), part.toFile(), { _, _ -> }, limiter()) }
            }
        } finally {
            DownloadsPlatformDownloader.freeBytesProvider = before
        }

        assertContentEquals(ByteArray(10) { 1 }, Files.readAllBytes(part))
    }

    @Test
    fun the_room_a_discarded_partial_gives_back_counts_towards_the_fresh_download() {
        val part = dir.resolve("Film.mkv.part").also { Files.write(it, ByteArray(1024) { 1 }) }
        val before = DownloadsPlatformDownloader.freeBytesProvider
        DownloadsPlatformDownloader.freeBytesProvider = { payload.size - 1024L }
        try {
            runBlocking { DownloadsPlatformDownloader.transferToTempFile(request("Film.mkv"), part.toFile(), { _, _ -> }, limiter()) }
        } finally {
            DownloadsPlatformDownloader.freeBytesProvider = before
        }

        assertContentEquals(payload, Files.readAllBytes(part))
    }

    @Test
    fun a_partial_this_download_created_is_recorded_and_can_be_started_again() {
        val part = dir.resolve("Film.mkv.part")
        val recorded = mutableListOf<String>()
        val partial = OwnedPartial(null, onCreated = { recorded += it })

        runBlocking { DownloadsPlatformDownloader.transferToTempFile(request("Film.mkv"), part.toFile(), { _, _ -> }, limiter(), partial) }

        assertContentEquals(payload, Files.readAllBytes(part))
        assertEquals(listOf(DownloadMover.identityOf(part)), recorded)
        // A restart from nothing removes only that file, proven by the recorded identity.
        val again = OwnedPartial(recorded.single())
        runBlocking { DownloadsPlatformDownloader.transferToTempFile(request("Film.mkv"), part.toFile(), { _, _ -> }, limiter(), again) }
        assertContentEquals(payload, Files.readAllBytes(part))
    }

    @Test
    fun a_just_published_file_judged_bad_is_removed_only_while_it_is_still_that_file() {
        val file = dir.resolve("published.mkv").also { Files.writeString(it, "bad") }
        val other = dir.resolve("other.mkv").also { Files.writeString(it, "never published here") }

        assertFalse(DownloadsPlatformDownloader.removeJustPublished(other.toUri().toString()), "a file never published was removed")
        assertTrue(other.toFile().exists())

        val partial = OwnedPartial(null)
        val part = dir.resolve("Next.mkv.part")
        runBlocking { DownloadsPlatformDownloader.transferToTempFile(request("Next.mkv"), part.toFile(), { _, _ -> }, limiter(), partial) }
        assertNotNull(partial.key)
        assertTrue(file.toFile().exists())
    }
}
