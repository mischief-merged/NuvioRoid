package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.nuvio.app.core.storage.DesktopStorage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exactly one writer per download, and no adoption of files by name. */
class WriterTokenTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private val servers = mutableListOf<HttpServer>()
    private val files = mutableListOf<File>()
    private val freeBefore = DownloadsPlatformDownloader.freeBytesProvider

    @BeforeTest
    fun no_free_space_check() {
        DownloadsPlatformDownloader.freeBytesProvider = { null }
    }

    @AfterTest
    fun clean_up() {
        DownloadsPlatformDownloader.freeBytesProvider = freeBefore
        servers.forEach { it.stop(0) }
        files.forEach { it.delete() }
        WriterTokens.release("tok")
        WriterTokens.release("one-writer")
    }

    @Test
    fun a_token_has_one_owner_and_a_mover_is_never_waited_for_by_a_writer() {
        assertTrue(WriterTokens.tryAcquire("tok", WriterTokens.Kind.Mover))
        assertFalse(WriterTokens.tryAcquire("tok", WriterTokens.Kind.Writer))
        assertFalse(WriterTokens.tryAcquire("tok", WriterTokens.Kind.Mover))
        assertEquals(WriterTokens.Kind.Mover, WriterTokens.kindOf("tok"))
        assertFalse(kotlinx.coroutines.runBlocking { WriterTokens.acquire("tok", WriterTokens.Kind.Writer, 5_000) })
        WriterTokens.release("tok")
        assertTrue(kotlinx.coroutines.runBlocking { WriterTokens.acquire("tok", WriterTokens.Kind.Writer, 100) })
        assertEquals(WriterTokens.Kind.Writer, WriterTokens.kindOf("tok"))
    }

    private fun serve(blocks: Boolean): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v.mkv") { exchange ->
            exchange.sendResponseHeaders(200, 400_000)
            exchange.responseBody.use { out ->
                out.write(ByteArray(100_000))
                out.flush()
                if (blocks) Thread.sleep(30_000)
                out.write(ByteArray(300_000))
            }
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/v.mkv"
    }

    private fun request(url: String, name: String) = DownloadPlatformRequest(
        DownloadItem(
            id = "one-writer", contentType = "movie", parentMetaId = "tt0", parentMetaType = "movie", videoId = "tt0",
            title = "t", streamTitle = "t", providerName = "p", sourceUrl = url, fileName = name,
            status = DownloadStatus.Downloading, createdAtEpochMs = 0, updatedAtEpochMs = 0,
        ),
    )

    @Test
    fun a_pause_followed_at_once_by_a_resume_never_has_two_writers() {
        val events = CopyOnWriteArrayList<String>()
        val name = "one-writer-${System.nanoTime()}.mkv"
        val app = DesktopStorage.rootDir.resolve("downloads").toFile().also { it.mkdirs() }
        files += File(app, name)
        files += File(app, "$name.part")
        val firstBytes = CountDownLatch(1)
        val finished = CountDownLatch(1)

        val a = DownloadsPlatformDownloader.start(
            request(serve(blocks = true), name),
            onProgress = { b, _ -> if (b > 0) firstBytes.countDown() },
            onSuccess = { _, _ -> }, onFailure = { events += "A-failed" }, onPaused = { events += "A-exited" },
        )
        assertTrue(firstBytes.await(10, TimeUnit.SECONDS))
        // Pause (a request only) and resume at once.
        a.cancel()
        DownloadsPlatformDownloader.start(
            request(serve(blocks = false), name),
            onProgress = { b, _ -> if (b > 0 && "B-writing" !in events) events += "B-writing" },
            onSuccess = { _, _ -> events += "B-done"; finished.countDown() },
            onFailure = { events += "B-failed: $it"; finished.countDown() }, onPaused = { finished.countDown() },
        )

        assertTrue(finished.await(20, TimeUnit.SECONDS), events.toString())
        assertTrue(events.indexOf("A-exited") in 0 until events.indexOf("B-writing"), "the second writer started before the first one had exited: $events")
        assertEquals(null, WriterTokens.kindOf("one-writer"), "the token was kept")
    }

    @Test
    fun an_unrelated_partial_file_of_the_same_name_in_another_location_is_never_adopted() {
        val world = FolderWorld()
        try {
            val drive = world.driveLocation("Archive")
            val item = episodeItem("tt1", 1, status = DownloadStatus.Paused, locationId = null)
            drive.path.resolve(item.fileName + ".part").toFile().writeBytes(ByteArray(10))

            val restored = DownloadsPlatformDownloader.restoreItem(item)

            assertEquals(item, restored, "a record was pointed at a file of another location")
            assertEquals(null, restored.locationId)
        } finally {
            world.close()
        }
    }
}
