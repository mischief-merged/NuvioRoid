package com.nuvio.app.features.downloads

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review 5, items 2 and 8: a download is reported finished only after its file is on disk under its final
 * name; until then the partial file keeps its name, and nothing half-written is left beside it.
 */
class DurablePublishTest {
    private val dir = Files.createTempDirectory("nuvio-publish").toRealPath()

    @AfterTest
    fun clean_up() {
        dir.toFile().deleteRecursively()
    }

    private fun part(text: String) = dir.resolve("film.mp4.part").also { Files.writeString(it, text) }

    @Test fun the_published_address_is_saved_before_the_partial_name_is_removed() {
        val part = part("every byte")
        var saves = 0
        val published = publishDurably(part, dir.resolve("film.mp4"), flushFolder = { true }, beforeRemovingPartial = { path ->
            saves++
            assertTrue(part.exists())
            assertEquals("every byte", Files.readString(path))
            true
        })
        assertEquals(1, saves)
        assertFalse(part.exists())
        assertEquals("every byte", Files.readString(published))
    }

    @Test fun an_unsaved_published_address_keeps_both_files_intact() {
        val part = part("every byte")
        assertFailsWith<DownloadNotDurable> {
            publishDurably(part, dir.resolve("film.mp4"), flushFolder = { true }, beforeRemovingPartial = { false })
        }
        assertEquals("every byte", Files.readString(part))
        assertEquals("every byte", Files.readString(dir.resolve("film.mp4")))
    }

    @Test
    fun the_final_name_appears_and_the_partial_name_goes_only_after_the_folder_was_flushed() {
        val part = part("the film")
        val events = mutableListOf<String>()

        val published = publishDurably(part, dir.resolve("film.mp4"), flushFolder = { events += "flush ${part.exists()}"; true })

        assertEquals(dir.resolve("film.mp4"), published)
        assertEquals("the film", Files.readString(published))
        assertFalse(part.exists())
        // The folder was flushed while the partial name was still there.
        assertEquals("flush true", events.first())
    }

    @Test
    fun a_folder_that_cannot_be_flushed_keeps_the_partial_and_leaves_no_final_name() {
        val part = part("the film")

        assertFailsWith<DownloadNotDurable> { publishDurably(part, dir.resolve("film.mp4"), flushFolder = { false }) }
        assertFailsWith<DownloadNotDurable> {
            publishDurably(part, dir.resolve("film.mp4"), flushFolder = { false }, link = { _, _ -> throw UnsupportedOperationException() })
        }

        assertEquals("the film", Files.readString(part))
        assertEquals(listOf("film.mp4.part"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun a_copy_that_fails_half_way_on_a_volume_without_links_leaves_nothing_beside_the_partial_and_never_piles_up() {
        val part = part("the film")
        repeat(3) {
            assertFailsWith<java.io.IOException> {
                publishDurably(
                    part, dir.resolve("film.mp4"), flushFolder = { true },
                    link = { _, _ -> throw UnsupportedOperationException() },
                    copy = { _, out -> out.write(java.nio.ByteBuffer.wrap("half".toByteArray())); throw java.io.IOException("disk error") },
                )
            }
        }
        assertEquals(listOf("film.mp4.part"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })

        val published = publishDurably(part, dir.resolve("film.mp4"), flushFolder = { true }, link = { _, _ -> throw UnsupportedOperationException() })
        assertEquals("the film", Files.readString(published))
        assertTrue(!part.exists())
    }

    @Test
    fun an_existing_file_at_the_final_name_is_never_replaced() {
        val existing = dir.resolve("film.mp4").also { Files.writeString(it, "someone's") }
        val published = publishDurably(part("new"), existing, flushFolder = { true })

        assertEquals(dir.resolve("film (2).mp4"), published)
        assertEquals("someone's", Files.readString(existing))
    }
}
