package com.nuvio.app.features.downloads

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PublishedFileTrashTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun the_downloader_moves_only_the_file_it_published_and_keeps_bytes_when_refused() {
        val file = folder.newFile("published.mkv").toPath()
        Files.writeString(file, "all the bytes")
        val uri = file.toFile().toURI().toString()
        DownloadsPlatformDownloader.rememberPublishedFile(file)
        var calls = 0
        assertFalse(DownloadsPlatformDownloader.removeJustPublished(uri) { calls++; false })
        assertEquals("all the bytes", Files.readString(file))
        DownloadsPlatformDownloader.rememberPublishedFile(file)
        val archive = folder.root.toPath().resolve("kept.mkv")
        assertTrue(DownloadsPlatformDownloader.removeJustPublished(uri) { calls++; Files.move(it, archive); true })
        assertEquals(2, calls)
        assertEquals("all the bytes", Files.readString(archive))
        assertFalse(DownloadsPlatformDownloader.removeJustPublished(uri) { error("already moved") })
    }

    @Test fun an_old_saved_identity_never_uses_a_newer_publications_identity() {
        val file = folder.newFile("again.mkv").toPath()
        Files.writeString(file, "the original bytes")
        val key = assertNotNull(DownloadMover.identityOf(file))
        Files.move(file, folder.root.toPath().resolve("original.mkv"))
        Files.writeString(file, "the newer bytes")
        DownloadsPlatformDownloader.rememberPublishedFile(file)
        var moves = 0
        assertFalse(DownloadsPlatformDownloader.removeJustPublished(file.toFile().toURI().toString(), key) { moves++; true })
        assertEquals(0, moves)
        assertEquals("the newer bytes", Files.readString(file))
    }

    @Test fun a_rejected_file_is_moved_with_its_bytes_kept() {
        val file = folder.newFile("video.mkv").toPath()
        Files.writeString(file, "the downloaded bytes")
        val key = assertNotNull(DownloadMover.identityOf(file))
        val archive = folder.root.toPath().resolve("archive.mkv")
        var moves = 0
        assertTrue(trashPublishedFile(file, key) { moves++; Files.move(it, archive); true })
        assertEquals(1, moves)
        assertFalse(Files.exists(file))
        assertEquals("the downloaded bytes", Files.readString(archive))
    }

    @Test fun a_refused_move_keeps_the_rejected_file() {
        val file = folder.newFile("video.mkv").toPath()
        Files.writeString(file, "the downloaded bytes")
        val key = assertNotNull(DownloadMover.identityOf(file))
        assertFalse(trashPublishedFile(file, key) { false })
        assertEquals("the downloaded bytes", Files.readString(file))
    }

    @Test fun another_file_is_never_moved() {
        val file = folder.newFile("video.mkv").toPath()
        Files.writeString(file, "the user's bytes")
        var moves = 0
        assertFalse(trashPublishedFile(file, "another identity") { moves++; true })
        assertEquals(0, moves)
        assertEquals("the user's bytes", Files.readString(file))
    }
}
