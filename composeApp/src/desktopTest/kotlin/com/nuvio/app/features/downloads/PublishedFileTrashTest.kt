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
