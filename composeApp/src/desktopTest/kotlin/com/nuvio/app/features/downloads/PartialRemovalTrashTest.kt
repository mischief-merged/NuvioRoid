package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A partial file and a finished download are moved to the Trash. Neither is deleted outright.
 */
class PartialRemovalTrashTest {
    private val store = DesktopStorage.store("nuvio_downloads")
    private val storeKey = ProfileScopedKey.of("downloads")
    private lateinit var holding: Path
    private var previousPayload: String? = null
    private var previousPartialTrash: ((Path) -> Boolean)? = null
    private var previousFileTrash: ((String) -> Boolean)? = null
    private lateinit var previousController: DownloadFolderController
    private val created = mutableListOf<Path>()

    @BeforeTest
    fun setUp() {
        holding = Files.createTempDirectory("nuvio-partial-removal")
        previousPayload = store.getString(storeKey)
        previousPartialTrash = DownloadsPlatformDownloader.trashPartialFile
        previousFileTrash = DownloadsPlatformDownloader.trashRemovedFile
        previousController = DownloadFolders.controller
        DownloadFolders.controller = NoDownloadFolders
        DownloadsPlatformDownloader.trashRemovedFile = null
        DownloadsPlatformDownloader.trashPartialFile = { path ->
            Files.move(path, holding.resolve(path.fileName.toString()))
            true
        }
        DownloadsRepository.clearLocalState()
    }

    @AfterTest
    fun tearDown() {
        DownloadsPlatformDownloader.trashPartialFile = previousPartialTrash
        DownloadsPlatformDownloader.trashRemovedFile = previousFileTrash
        DownloadFolders.controller = previousController
        store.putString(storeKey, previousPayload)
        DownloadsRepository.clearLocalState()
        created.forEach { path -> runCatching { Files.deleteIfExists(path) } }
        holding.toFile().deleteRecursively()
    }

    @Test
    fun removing_a_listed_partial_sends_it_to_the_trash() {
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        val item = pausedPartial(bytes)
        val partial = partialPath(item)
        load(item)

        assertTrue(DownloadsPlatformDownloader.removePartialFile(item.fileName))

        assertFalse(Files.exists(partial, LinkOption.NOFOLLOW_LINKS))
        assertContentEquals(bytes, Files.readAllBytes(holding.resolve(partial.fileName.toString())))
    }

    @Test
    fun deleting_a_download_sends_its_partial_to_the_trash_after_the_record_is_gone() {
        val bytes = byteArrayOf(3, 1, 4, 1, 5)
        val item = pausedPartial(bytes)
        val partial = partialPath(item)
        load(item)

        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item.id))

        assertFalse(Files.exists(partial, LinkOption.NOFOLLOW_LINKS))
        assertContentEquals(bytes, Files.readAllBytes(holding.resolve(partial.fileName.toString())))
        assertTrue(DownloadsRepository.uiState.value.items.none { it.id == item.id })
    }

    @Test
    fun a_partial_whose_identity_does_not_match_is_left_in_place() {
        val bytes = byteArrayOf(4, 5, 6)
        val item = pausedPartial(bytes, key = "not-this-file")
        val partial = partialPath(item)
        load(item)

        assertFalse(DownloadsPlatformDownloader.removePartialFile(item.fileName))

        assertContentEquals(bytes, Files.readAllBytes(partial))
    }

    @Test
    fun a_partial_with_no_record_is_left_in_place() {
        val bytes = byteArrayOf(1, 2, 3)
        val name = "orphan-${System.nanoTime()}.mp4"
        val partial = downloadsDir().resolve("$name.part")
        Files.write(partial, bytes)
        created.add(partial)
        store.putString(storeKey, DownloadsCodec.encode(emptyList(), null))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        assertFalse(DownloadsPlatformDownloader.removePartialFile(name))

        assertContentEquals(bytes, Files.readAllBytes(partial))
    }

    @Test
    fun removing_a_file_outside_a_downloads_folder_leaves_it_in_place() {
        val outside = Files.createTempFile("nuvio-not-a-download", ".mp4")
        val bytes = byteArrayOf(1, 1, 1)
        Files.write(outside, bytes)
        try {
            assertFalse(DownloadsPlatformDownloader.removeFile(outside.toUri().toString()))
            assertContentEquals(bytes, Files.readAllBytes(outside))
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun removing_a_download_file_sends_it_to_the_trash() {
        val bytes = byteArrayOf(2, 2, 2, 2)
        val video = downloadsDir().resolve("finished-${System.nanoTime()}.mp4")
        Files.write(video, bytes)
        created.add(video)
        DownloadsPlatformDownloader.trashRemovedFile = { uri ->
            val path = Path.of(URI(uri))
            Files.move(path, holding.resolve(path.fileName.toString()))
            true
        }

        assertTrue(DownloadsPlatformDownloader.removeFile(video.toUri().toString()))

        assertFalse(Files.exists(video, LinkOption.NOFOLLOW_LINKS))
        assertContentEquals(bytes, Files.readAllBytes(holding.resolve(video.fileName.toString())))
    }

    private fun pausedPartial(bytes: ByteArray, key: String? = null): DownloadItem {
        val name = "partial-${System.nanoTime()}.mp4"
        val partial = downloadsDir().resolve("$name.part")
        Files.write(partial, bytes)
        created.add(partial)
        return testDownload(episode = 1, status = DownloadStatus.Paused, id = "partial-${System.nanoTime()}").copy(
            fileName = name,
            localFileUri = null,
            downloadedBytes = bytes.size.toLong(),
            partialFileKey = key ?: DownloadMover.identityOf(partial),
        )
    }

    private fun load(item: DownloadItem) {
        store.putString(storeKey, DownloadsCodec.encode(listOf(item), null))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
    }

    private fun partialPath(item: DownloadItem) = downloadsDir().resolve(item.activePartialFileName)

    private fun downloadsDir(): Path =
        DesktopStorage.rootDir.resolve("downloads").also { Files.createDirectories(it) }
}
