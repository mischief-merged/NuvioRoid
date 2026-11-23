package com.nuvio.app.features.downloads

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDownloadsTrashTest {
    private val day = 24L * 60 * 60 * 1000
    private var now = 1_700_000_000_000L
    private val trashedBySystem = mutableListOf<File>()
    private var systemTrashWorks = true
    private lateinit var folder: Path
    private lateinit var downloadsDir: Path
    private lateinit var outside: Path
    private lateinit var systemTrashDir: Path

    @BeforeTest
    fun create_a_temporary_downloads_folder() {
        folder = Files.createTempDirectory("nuvio-trash-test").toRealPath()
        downloadsDir = folder.resolve("downloads").createDirectories()
        outside = folder.resolve("outside").createDirectories()
        systemTrashDir = folder.resolve("system-trash").createDirectories()
    }

    @AfterTest
    fun remove_the_temporary_folder() {
        folder.toFile().deleteRecursively()
    }

    private fun withSystemTrash() = DesktopDownloadsTrash(
        downloadsDir = { downloadsDir },
        systemTrash = { file ->
            if (systemTrashWorks) {
                trashedBySystem += file
                val source = file.toPath()
                Files.move(source, systemTrashDir.resolve("${trashedBySystem.size}-${source.fileName}"))
                true
            } else {
                false
            }
        },
        nowEpochMs = { now },
    )

    private fun withoutSystemTrash() = DesktopDownloadsTrash(downloadsDir = { downloadsDir }, systemTrash = null, nowEpochMs = { now })

    private fun video(name: String = "episode.mkv") = downloadsDir.resolve(name).also { it.writeText("video") }

    private fun subtitlesOf(video: Path) = downloadsDir.resolve("${video.fileName}.subtitles").createDirectories()
        .also { it.resolve("en.srt").writeText("subtitles") }

    private val holding get() = downloadsDir.resolve(DesktopDownloadsTrash.HoldingFolderName)

    private fun held() = holding.toFile().list().orEmpty().filter { it != DesktopDownloadsTrash.IndexFileName }.sorted()

    private fun Path.uri() = toUri().toString()

    @Test
    fun a_download_and_its_subtitles_go_to_the_system_trash() {
        val video = video()
        val subtitles = subtitlesOf(video)

        assertTrue(withSystemTrash().moveAway(video.uri()))

        assertEquals(listOf(video.toFile(), subtitles.toFile()), trashedBySystem)
        assertFalse(holding.exists())
    }

    @Test
    fun a_system_trash_that_fails_leaves_the_file_where_it_is() {
        val video = video()
        systemTrashWorks = false

        assertFalse(withSystemTrash().moveAway(video.uri()))

        assertTrue(video.exists())
        assertFalse(holding.exists())
    }

    @Test
    fun without_a_system_trash_the_file_stays_archived_until_system_trash_is_available() {
        val video = video()
        subtitlesOf(video)
        val trashWithoutSystem = withoutSystemTrash()
        assertFalse(trashWithoutSystem.usesSystemTrash)

        assertTrue(trashWithoutSystem.moveAway(video.uri()))

        assertFalse(video.exists())
        assertEquals(listOf("$now-episode.mkv", "$now-episode.mkv.subtitles"), held())

        // Without system Trash, archived files are never permanently deleted even after 7 or 30 days.
        assertEquals(0, trashWithoutSystem.purgeHeldFiles(now + 7 * day))
        assertEquals(0, trashWithoutSystem.purgeHeldFiles(now + 30 * day))
        assertEquals(listOf("$now-episode.mkv", "$now-episode.mkv.subtitles"), held())

        // Once system Trash is available, aged archived files move to system Trash after 7 days.
        val trashWithSystem = withSystemTrash()
        assertEquals(0, trashWithSystem.purgeHeldFiles(now + 7 * day - 1))
        assertEquals(2, held().size)
        assertEquals(2, trashWithSystem.purgeHeldFiles(now + 7 * day))
        assertEquals(emptyList(), held())
        assertEquals(
            listOf(holding.resolve("$now-episode.mkv").toFile(), holding.resolve("$now-episode.mkv.subtitles").toFile()),
            trashedBySystem,
        )
        assertEquals(0, trashWithSystem.purgeHeldFiles(now + 8 * day))
    }

    @Test
    fun a_file_counts_as_moved_when_only_its_subtitles_could_not_follow() {
        val video = video()
        subtitlesOf(video)
        holding.createDirectories().resolve("$now-episode.mkv.subtitles").createDirectories()

        val trash = withoutSystemTrash()
        assertTrue(trash.moveAway(video.uri()))

        assertFalse(video.exists())
        assertTrue(downloadsDir.resolve("episode.mkv.subtitles").exists())
        assertEquals(0, trash.purgeHeldFiles(now + 30 * day))
        assertEquals(1, withSystemTrash().purgeHeldFiles(now + 30 * day))
        assertTrue(holding.resolve("$now-episode.mkv.subtitles").exists(), "something the app did not hold was moved")
    }

    @Test
    fun a_file_whose_place_in_the_holding_folder_is_taken_stays_and_what_is_there_is_never_deleted() {
        val video = video()
        val taken = holding.createDirectories().resolve("$now-episode.mkv").also { it.writeText("not ours") }
        val trash = withoutSystemTrash()

        assertFalse(trash.moveAway(video.uri()))

        assertTrue(video.exists())
        assertEquals(0, trash.purgeHeldFiles(now + 30 * day))
        assertTrue(taken.exists())
    }

    @Test
    fun two_spellings_of_one_file_are_the_same_file_and_two_files_are_not() {
        val video = video()
        val other = video("other.mkv")
        Files.createSymbolicLink(folder.resolve("alias"), downloadsDir)
        val trash = withoutSystemTrash()

        assertTrue(trash.isSameFile(video.uri(), video.uri()))
        assertTrue(trash.isSameFile(video.uri(), downloadsDir.resolve("sub/../episode.mkv").uri()))
        assertTrue(trash.isSameFile(video.uri(), folder.resolve("alias").resolve("episode.mkv").uri()))
        assertFalse(trash.isSameFile(video.uri(), other.uri()))
        assertFalse(trash.isSameFile(video.uri(), "not a file address"))
    }

    @Test
    fun a_file_that_is_already_gone_counts_as_moved() {
        assertTrue(withoutSystemTrash().moveAway(downloadsDir.resolve("gone.mkv").uri()))
    }

    @Test
    fun a_link_that_points_outside_the_downloads_folder_is_refused_and_not_followed() {
        val target = outside.resolve("private.mkv").also { it.writeText("private") }
        val link = downloadsDir.resolve("episode.mkv")
        Files.createSymbolicLink(link, target)

        assertFalse(withoutSystemTrash().moveAway(link.uri()))
        assertFalse(withSystemTrash().moveAway(link.uri()))

        assertTrue(target.exists())
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(emptyList(), trashedBySystem)
    }

    @Test
    fun a_file_outside_the_downloads_folder_or_below_a_subfolder_is_refused() {
        val elsewhere = outside.resolve("other.mkv").also { it.writeText("other") }
        val nested = downloadsDir.resolve("sub").createDirectories().resolve("nested.mkv").also { it.writeText("nested") }
        val throughLinkedFolder = downloadsDir.resolve("linked")
        Files.createSymbolicLink(throughLinkedFolder, outside)

        val trash = withoutSystemTrash()
        assertFalse(trash.moveAway(elsewhere.uri()))
        assertFalse(trash.moveAway(nested.uri()))
        assertFalse(trash.moveAway(throughLinkedFolder.resolve("other.mkv").uri()))
        assertFalse(trash.moveAway(downloadsDir.uri()))
        assertFalse(trash.moveAway("https://example.com/a.mkv"))

        assertTrue(elsewhere.exists())
        assertTrue(nested.exists())
    }

    @Test
    fun a_subtitles_folder_that_is_a_link_is_left_alone() {
        val video = video()
        val linkedSubtitles = downloadsDir.resolve("episode.mkv.subtitles")
        Files.createSymbolicLink(linkedSubtitles, outside)
        outside.resolve("keep.txt").writeText("keep")

        assertTrue(withSystemTrash().moveAway(video.uri()))

        assertEquals(listOf(video.toFile()), trashedBySystem)
        assertTrue(Files.isSymbolicLink(linkedSubtitles))
        assertTrue(outside.resolve("keep.txt").exists())
    }

    @Test
    fun only_what_was_held_by_the_app_is_moved_to_system_trash_from_the_holding_folder() {
        val trash = withoutSystemTrash()
        trash.moveAway(video().uri())
        val stray = holding.resolve("${now - 100 * day}-not-ours.mkv").also { it.writeText("stray") }
        val strayFolder = holding.resolve("1-folder").createDirectories()

        assertEquals(0, trash.purgeHeldFiles(now + 30 * day))
        assertEquals(1, withSystemTrash().purgeHeldFiles(now + 30 * day))

        assertTrue(stray.exists())
        assertTrue(strayFolder.exists())
    }

    @Test
    fun trashing_held_subtitles_does_not_touch_target_outside_downloads_folder() {
        val video = video()
        val subtitles = subtitlesOf(video)
        val target = outside.resolve("keep.txt").also { it.writeText("keep") }
        Files.createSymbolicLink(subtitles.resolve("link-to-outside"), outside)
        val trash = withoutSystemTrash()
        trash.moveAway(video.uri())

        assertEquals(0, trash.purgeHeldFiles(now + 7 * day))
        assertEquals(2, withSystemTrash().purgeHeldFiles(now + 7 * day))

        assertTrue(target.exists())
        assertEquals(emptyList(), held())
    }

    @Test
    fun an_index_line_that_names_something_outside_the_holding_folder_touches_nothing() {
        val trash = withoutSystemTrash()
        trash.moveAway(video().uri())
        val target = outside.resolve("keep.txt").also { it.writeText("keep") }
        val index = holding.resolve(DesktopDownloadsTrash.IndexFileName)
        Files.write(index, Files.readAllLines(index) + listOf("1\t../../outside/keep.txt", "1\t..", "1\t/etc/hosts"))

        withSystemTrash().purgeHeldFiles(now + 30 * day)

        assertTrue(target.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun a_holding_folder_that_is_a_link_is_not_used() {
        Files.createSymbolicLink(holding, outside)
        val video = video()
        val trash = withoutSystemTrash()

        assertFalse(trash.moveAway(video.uri()))
        assertEquals(0, trash.purgeHeldFiles(now + 30 * day))

        assertTrue(video.exists())
        assertEquals(emptyList(), outside.toFile().list().orEmpty().toList())
    }
}
