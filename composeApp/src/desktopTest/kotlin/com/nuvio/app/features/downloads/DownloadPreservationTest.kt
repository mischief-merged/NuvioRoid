package com.nuvio.app.features.downloads

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadPreservationTest : com.nuvio.app.core.network.TrustedLocalServerTest() {
    private val world = FolderWorld()
    private val trash = DesktopDownloadsTrash({ world.appFolder }, systemTrash = null, nowEpochMs = { 123L })
    private val retired = CopyOnWriteArrayList<Path>()
    private var allowTrash = true
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var respond: (com.sun.net.httpserver.HttpExchange) -> Unit = { exchange ->
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    private val ranges = CopyOnWriteArrayList<String>()
    private val bytes = videoFile(36)
    private val done = CountDownLatch(1)
    private var failure: String? = null
    private var success: String? = null
    private val handles = mutableListOf<DownloadsTaskHandle>()
    private val previousBackend = DownloadsRepository.backend
    private val previousCompanions = DownloadsRepository.companions
    private val previousHold = DownloadsRepository.connectionHold
    private val previousFreeBytes = DownloadsPlatformDownloader.freeBytesProvider
    private val previousPartialTrash = DownloadsPlatformDownloader.trashPartial

    @BeforeTest fun set_up() {
        Files.createDirectories(world.appFolder)
        DesktopDownloads.replaceForTest(DesktopDownloadFolders(
            world.locations, world.validator, world.mover(), trashPath = { path ->
                retired.add(path)
                allowTrash && trash.moveToTrash(path)
            },
        ))
        DownloadsPlatformDownloader.freeBytesProvider = { null }
        DownloadsPlatformDownloader.trashPartial = null
        server.createContext("/video.mp4") { exchange ->
            ranges.add(exchange.requestHeaders.getFirst("Range") ?: "full")
            respond(exchange)
        }
        server.start()
    }

    @AfterTest fun clean_up() {
        handles.forEach { assertTrue(it.cancelAndWait(5_000)) }
        server.stop(0)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = previousBackend
        DownloadsRepository.companions = previousCompanions
        DownloadsRepository.connectionHold = previousHold
        DownloadsPlatformDownloader.freeBytesProvider = previousFreeBytes
        DownloadsPlatformDownloader.trashPartial = previousPartialTrash
        DesktopDownloads.replaceForTest(null)
        world.close()
    }

    private fun item() = movieItem("preserve", bytes = bytes.size.toLong()).copy(
        status = DownloadStatus.Downloading,
        downloadedBytes = 0,
        sourceUrl = "http://127.0.0.1:${server.address.port}/video.mp4",
    )

    private fun part(item: DownloadItem = item()) = world.appFolder.resolve(item.activePartialFileName)
    private fun held(path: Path) = world.appFolder.resolve(DesktopDownloadsTrash.HoldingFolderName).resolve("123-${path.fileName}")

    private fun start(request: DownloadPlatformRequest = DownloadPlatformRequest(item())) {
        handles += DownloadsPlatformDownloader.start(
            request.copy(onFinished = { request.onFinished(); done.countDown() }),
            onProgress = { _, _ -> },
            onSuccess = { uri, _ -> success = uri },
            onFailure = { failure = it },
            onPaused = {},
        )
    }

    private fun awaitDone() = assertTrue(done.await(10, TimeUnit.SECONDS), "transfer did not finish")

    @Test fun a_range_rejection_then_a_failed_full_request_keeps_the_owned_partial() {
        val original = bytes.copyOfRange(0, 100)
        val part = part()
        Files.write(part, original)
        val key = assertNotNull(DownloadMover.identityOf(part))
        val kept = CopyOnWriteArrayList<KeptPartialFile>()
        respond = { exchange ->
            exchange.sendResponseHeaders(if (exchange.requestHeaders.getFirst("Range") != null) 416 else 403, -1)
            exchange.close()
        }
        start(DownloadPlatformRequest(item().copy(partialFileKey = key, downloadedBytes = 100),
            onSwitchedToNewPartial = { _, old -> if (old != null) kept.add(old) },
        ))
        awaitDone()
        assertNull(success)
        assertTrue(failure.orEmpty().contains("403"))
        assertEquals(listOf("bytes=100-", "full"), ranges.toList())
        assertContentEquals(original, Files.readAllBytes(part))
        assertEquals(key, DownloadMover.identityOf(part))
        assertEquals(key, kept.single().partialFileKey)
        assertTrue(retired.isEmpty())
    }

    @Test fun a_completed_partial_goes_to_holding_only_after_the_final_address_is_saved() {
        var saved = false
        start(DownloadPlatformRequest(item(), onPublished = { uri, _, _ ->
            assertContentEquals(bytes, Files.readAllBytes(part()))
            assertContentEquals(bytes, Files.readAllBytes(Path.of(URI(uri))))
            assertTrue(retired.isEmpty())
            saved = true
            true
        }))
        awaitDone()
        assertNull(failure)
        assertTrue(saved)
        assertContentEquals(bytes, Files.readAllBytes(Path.of(URI(assertNotNull(success)))))
        assertFalse(Files.exists(part(), LinkOption.NOFOLLOW_LINKS))
        assertContentEquals(bytes, Files.readAllBytes(held(part())))
        assertEquals(listOf(part()), retired.toList())
    }

    @Test fun a_refused_partial_retirement_keeps_both_complete_files() {
        allowTrash = false
        start()
        awaitDone()
        assertNull(failure)
        assertContentEquals(bytes, Files.readAllBytes(Path.of(URI(assertNotNull(success)))))
        assertContentEquals(bytes, Files.readAllBytes(part()))
        assertEquals(listOf(part()), retired.toList())
    }

    @Test fun a_changed_partial_is_never_retired_after_publication() {
        val saved = world.appFolder.resolve("saved.part")
        start(DownloadPlatformRequest(item(), onPublished = { _, _, _ ->
            Files.move(part(), saved)
            Files.writeString(part(), "another file")
            true
        }))
        awaitDone()
        assertNull(failure)
        assertContentEquals(bytes, Files.readAllBytes(Path.of(URI(assertNotNull(success)))))
        assertContentEquals(bytes, Files.readAllBytes(saved))
        assertEquals("another file", Files.readString(part()))
        assertTrue(retired.isEmpty())
    }

    @Test fun a_rejected_finished_transfer_reaches_the_existing_holding_folder() {
        val bad = ByteArray(bytes.size) { 3 }
        respond = { exchange ->
            exchange.sendResponseHeaders(200, bad.size.toLong())
            exchange.responseBody.use { it.write(bad) }
        }
        var stored = storedPayload(listOf(item()))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.companions = { DownloadCompanions(emptyList(), isOnline = { false }) }
        DownloadsRepository.connectionHold = ConnectionHold()
        DownloadsRepository.backend = object : DownloadsBackend {
            override fun loadPayload(): String = stored
            override fun savePayload(payload: String) { stored = payload }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun start(request: DownloadPlatformRequest, onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit, onFailure: (String) -> Unit, onPaused: () -> Unit,
            ): DownloadsTaskHandle = DownloadsPlatformDownloader.start(
                request.copy(onFinished = { request.onFinished(); done.countDown() }),
                onProgress, onSuccess, onFailure, onPaused,
            ).also { handles += it }
            override fun inspectFile(localFileUri: String) = inspectDownloadedFile(localFileUri)
            override fun removeJustPublished(localFileUri: String, fileKey: String?): Boolean =
                DownloadsPlatformDownloader.removeJustPublished(localFileUri, fileKey, trash::moveToTrash)
            override fun removeFile(localFileUri: String?): Boolean = error("unexpected removal")
            override fun removePartialFile(destinationFileName: String): Boolean = error("unexpected partial removal")
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
        }
        DownloadsRepository.ensureLoaded()
        awaitDone()
        val record = decodeStoredDownloads(stored).orEmpty().single()
        assertEquals(DownloadStatus.Failed, record.status)
        val final = world.appFolder.resolve(item().fileName)
        assertFalse(Files.exists(final, LinkOption.NOFOLLOW_LINKS))
        assertContentEquals(bad, Files.readAllBytes(held(final)))
    }

    @Test fun removing_an_owned_sidecar_keeps_custom_files_and_does_not_follow_links() {
        val video = world.appFolder.resolve(item().fileName)
        Files.write(video, bytes)
        val storage = DownloadSubtitleStorage(video.toUri().toString())
        storage.write("0.srt", "saved subtitles")
        val sidecar = video.resolveSibling("${video.fileName}.subtitles")
        Files.writeString(sidecar.resolve("notes.txt"), "keep these notes")
        val outside = world.root.resolve("outside.txt")
        Files.writeString(outside, "outside bytes")
        Files.createSymbolicLink(sidecar.resolve("linked.txt"), outside)
        storage.remove()
        assertFalse(Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS))
        assertEquals("saved subtitles", Files.readString(held(sidecar).resolve("0.srt")))
        assertEquals("keep these notes", Files.readString(held(sidecar).resolve("notes.txt")))
        assertTrue(Files.isSymbolicLink(held(sidecar).resolve("linked.txt")))
        assertEquals("outside bytes", Files.readString(outside))
        assertContentEquals(bytes, Files.readAllBytes(video))
        storage.remove()
        assertEquals(listOf(sidecar), retired.toList())
    }

    @Test fun a_refused_sidecar_move_keeps_its_files_in_place() {
        val video = world.appFolder.resolve(item().fileName)
        val storage = DownloadSubtitleStorage(video.toUri().toString())
        storage.write("0.srt", "saved subtitles")
        allowTrash = false
        storage.remove()
        assertEquals("saved subtitles", Files.readString(video.resolveSibling("${video.fileName}.subtitles").resolve("0.srt")))
    }

    @Test fun a_link_in_place_of_a_sidecar_is_left_alone() {
        val video = world.appFolder.resolve(item().fileName)
        val target = Files.createDirectory(world.root.resolve("outside"))
        Files.writeString(target.resolve("notes.txt"), "keep")
        val sidecar = video.resolveSibling("${video.fileName}.subtitles")
        Files.createSymbolicLink(sidecar, target)
        DownloadSubtitleStorage(video.toUri().toString()).remove()
        assertTrue(Files.isSymbolicLink(sidecar))
        assertEquals("keep", Files.readString(target.resolve("notes.txt")))
        assertTrue(retired.isEmpty())
    }
}
