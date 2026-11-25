package com.nuvio.app.features.downloads

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.io.path.readText
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
    fun archive_message_wait_matches_when_files_can_move_to_trash() {
        val strings = listOf(
            File("src/commonMain/composeResources/values/strings.xml"),
            File("composeApp/src/commonMain/composeResources/values/strings.xml"),
        ).first { it.isFile }.readText()
        val message = requireNotNull(
            Regex("<string name=\"downloads_delete_message_held\">([^<]+)</string>").find(strings),
        ).groupValues[1]
        val days = requireNotNull(Regex("for (\\d+) days").find(message)).groupValues[1].toLong()
        assertEquals(HeldFileDays.toLong(), days)
        assertTrue(message.contains("then moves to the Trash when available"))

        val video = video()
        assertTrue(withoutSystemTrash().moveAway(video.uri()))
        val archived = holding.resolve("$now-episode.mkv")
        val trash = withSystemTrash()

        assertEquals(0, trash.purgeHeldFiles(now + days * day - 1))
        assertTrue(trashedBySystem.isEmpty())
        assertEquals("video", archived.readText())
        assertEquals(0, withoutSystemTrash().purgeHeldFiles(now + days * day))
        assertEquals("video", archived.readText())
        assertEquals(1, trash.purgeHeldFiles(now + days * day))
        assertEquals(listOf(archived.toFile()), trashedBySystem)
        assertEquals("video", systemTrashDir.resolve("1-${archived.fileName}").readText())
    }

    @Test
    fun cancellation_after_subtitles_move_restores_the_video_and_subtitles() {
        val video = video()
        val subtitles = subtitlesOf(video)
        val systemTrash = outside.resolve("trash").createDirectories()
        var wanted = true
        val trash = DesktopDownloadsTrash({ downloadsDir }, systemTrash = { file ->
            Files.move(file.toPath(), systemTrash.resolve(file.name))
            if (file.toPath() == subtitles) wanted = false
            true
        })
        assertEquals(AutoDownloadRemovalResult.Changed, trash.moveWatchedAway(video.uri()) { wanted })
        assertEquals("video", video.readText())
        assertEquals("subtitles", subtitles.resolve("en.srt").readText())
        assertFalse(holding.exists())
        assertTrue(downloadsDir.toFile().list().orEmpty().none { it.startsWith(".watched-removal-") })
    }

    @Test
    fun a_trash_move_that_throws_after_moving_restores_the_original_file() {
        val video = video()
        val trash = DesktopDownloadsTrash({ downloadsDir }, systemTrash = { file ->
            Files.move(file.toPath(), outside.resolve(file.name))
            error("Move interrupted")
        })
        assertEquals(AutoDownloadRemovalResult.NotMoved, trash.moveWatchedAway(video.uri()) { true })
        assertEquals("video", video.readText())
        assertFalse(holding.exists())
    }

    @Test
    fun watched_removal_leaves_the_file_when_there_is_no_system_trash() {
        val video = video()
        assertEquals(AutoDownloadRemovalResult.NotMoved, withoutSystemTrash().moveWatchedAway(video.uri()) { true })
        assertEquals("video", video.readText())
        assertFalse(holding.exists())
    }

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
    fun a_rejected_file_is_held_when_the_system_trash_refuses_it() {
        val video = video()
        systemTrashWorks = false
        val key = requireNotNull(DownloadMover.identityOf(video))

        assertTrue(trashPublishedFile(video, key, withSystemTrash()::moveToTrash))

        assertFalse(video.exists())
        assertEquals("video", holding.resolve("$now-episode.mkv").readText())
    }

    @Test
    fun a_partial_is_held_when_the_system_trash_throws() {
        val partial = video("episode.mkv.part")
        val trash = DesktopDownloadsTrash({ downloadsDir }, systemTrash = { error("Trash unavailable") }, nowEpochMs = { now })

        assertTrue(trash.moveToTrash(partial))

        assertEquals("video", holding.resolve("$now-episode.mkv.part").readText())
        assertFalse(partial.exists())
    }

    @Test
    fun a_file_changed_during_a_failed_trash_call_is_not_held() {
        val original = video()
        val saved = outside.resolve("saved.mkv")
        val trash = DesktopDownloadsTrash({ downloadsDir }, systemTrash = { file ->
            Files.move(file.toPath(), saved)
            file.writeText("another file")
            false
        })

        assertFalse(trash.moveToTrash(original))

        assertEquals("another file", original.readText())
        assertEquals("video", saved.readText())
        assertFalse(holding.exists())
    }

    @Test
    fun regular_removal_refuses_a_linked_downloads_folder() {
        val original = video()
        val link = outside.resolve("download-alias")
        Files.createSymbolicLink(link, downloadsDir)

        assertFalse(withSystemTrash().moveAway(link.resolve(original.fileName).uri()))

        assertEquals("video", original.readText())
        assertTrue(trashedBySystem.isEmpty())
    }

    @Test
    fun watched_removal_refuses_a_linked_downloads_folder() {
        val original = video()
        val link = outside.resolve("download-alias")
        Files.createSymbolicLink(link, downloadsDir)

        assertEquals(AutoDownloadRemovalResult.NotMoved,
            withSystemTrash().moveWatchedAway(link.resolve(original.fileName).uri()) { true })

        assertEquals("video", original.readText())
        assertTrue(trashedBySystem.isEmpty())
    }

    @Test
    fun moving_to_trash_never_follows_a_linked_parent_inside_the_downloads_folder() {
        val target = downloadsDir.resolve("target").createDirectories()
        val original = target.resolve("saved.part").also { it.writeText("partial bytes") }
        val link = downloadsDir.resolve("alias")
        Files.createSymbolicLink(link, target)

        assertFalse(withoutSystemTrash().moveToTrash(link.resolve(original.fileName)))

        assertEquals("partial bytes", original.readText())
        assertFalse(holding.exists())
    }

    @Test
    fun without_a_system_trash_the_file_and_partial_stay_archived_until_system_trash_is_available() {
        val video = video()
        subtitlesOf(video)
        val partial = downloadsDir.resolve("episode.mkv.part").also { it.writeText("partial-bytes") }
        val trashWithoutSystem = withoutSystemTrash()
        assertFalse(trashWithoutSystem.usesSystemTrash)

        assertTrue(trashWithoutSystem.moveAway(video.uri()))
        assertTrue(trashWithoutSystem.moveToTrash(partial))

        val heldVideo = holding.resolve("$now-episode.mkv")
        val heldPartial = holding.resolve("$now-episode.mkv.part")
        assertFalse(video.exists())
        assertFalse(partial.exists())
        assertEquals("video", heldVideo.readText())
        assertEquals("partial-bytes", heldPartial.readText())
        assertTrue(holding.resolve("$now-episode.mkv.subtitles").resolve("en.srt").exists())

        // Without system Trash, archived files are never permanently deleted even after 7 or 30 days.
        assertEquals(0, trashWithoutSystem.purgeHeldFiles(now + 7 * day - 1))
        assertEquals(0, trashWithoutSystem.purgeHeldFiles(now + 7 * day))
        assertEquals(0, trashWithoutSystem.purgeHeldFiles(now + 30 * day))
        assertEquals(listOf("$now-episode.mkv", "$now-episode.mkv.part", "$now-episode.mkv.subtitles"), held())
        assertEquals("video", heldVideo.readText())
        assertEquals("partial-bytes", heldPartial.readText())
        assertEquals("subtitles", holding.resolve("$now-episode.mkv.subtitles").resolve("en.srt").readText())

        // Once system Trash is available, aged archived files move to system Trash after 7 days.
        val trashWithSystem = withSystemTrash()
        assertEquals(0, trashWithSystem.purgeHeldFiles(now + 7 * day - 1))
        assertEquals(3, held().size)
        assertEquals(3, trashWithSystem.purgeHeldFiles(now + 7 * day))
        assertEquals(emptyList(), held())
        assertEquals(
            listOf(
                holding.resolve("$now-episode.mkv").toFile(),
                holding.resolve("$now-episode.mkv.subtitles").toFile(),
                holding.resolve("$now-episode.mkv.part").toFile(),
            ),
            trashedBySystem,
        )
        assertEquals(0, trashWithSystem.purgeHeldFiles(now + 8 * day))
    }

    @Test
    fun a_finished_watched_removal_removes_its_temporary_recovery_links() {
        val video = video()
        subtitlesOf(video)
        val systemTrash = outside.resolve("trash").createDirectories()
        val trash = DesktopDownloadsTrash({ downloadsDir }, systemTrash = { file ->
            Files.move(file.toPath(), systemTrash.resolve(file.name))
            true
        })

        assertEquals(AutoDownloadRemovalResult.Removed, trash.moveWatchedAway(video.uri()) { true })

        assertFalse(video.exists())
        assertEquals("video", systemTrash.resolve("episode.mkv").readText())
        assertEquals("subtitles", systemTrash.resolve("episode.mkv.subtitles").resolve("en.srt").readText())
        assertTrue(downloadsDir.toFile().list().orEmpty().none { it.startsWith(".watched-removal-") })
    }

    @Test
    fun a_subtitle_link_stops_watched_removal_and_its_recovery_links_are_removed() {
        val video = video()
        val subtitles = subtitlesOf(video)
        val target = outside.resolve("keep.txt").also { it.writeText("keep") }
        Files.createSymbolicLink(subtitles.resolve("link-to-outside"), target)
        val trash = DesktopDownloadsTrash({ downloadsDir }, systemTrash = { file ->
            Files.move(file.toPath(), outside.resolve("trash").createDirectories().resolve(file.name))
            true
        })

        assertEquals(AutoDownloadRemovalResult.NotMoved, trash.moveWatchedAway(video.uri()) { true })

        assertEquals("video", video.readText())
        assertEquals("keep", target.readText())
        assertEquals("subtitles", subtitles.resolve("en.srt").readText())
        assertTrue(downloadsDir.toFile().list().orEmpty().none { it.startsWith(".watched-removal-") })
    }

    @Test
    fun when_the_system_trash_is_available_again_aged_held_files_move_there_and_strays_stay() {
        val holdingTrash = withoutSystemTrash()
        val video = video()
        val partial = downloadsDir.resolve("clip.mkv.part").also { it.writeText("part") }
        assertTrue(holdingTrash.moveAway(video.uri()))
        assertTrue(holdingTrash.moveToTrash(partial))
        val stray = holding.resolve("not-ours.mkv").also { it.writeText("stray") }
        val target = outside.resolve("keep.txt").also { it.writeText("keep") }
        val index = holding.resolve(DesktopDownloadsTrash.IndexFileName)
        Files.write(index, Files.readAllLines(index) + listOf("1\t../../outside/keep.txt", "1\t..", "1\t/etc/hosts"))

        val later = withSystemTrash()
        assertEquals(0, later.purgeHeldFiles(now))
        assertEquals(2, later.purgeHeldFiles(now + 7 * day))
        assertEquals(0, later.purgeHeldFiles(now + 30 * day))

        assertEquals(listOf("not-ours.mkv"), held())
        assertEquals("stray", stray.readText())
        assertEquals("keep", target.readText())
        assertEquals(2, trashedBySystem.size)
        assertTrue(trashedBySystem.all { it.name.endsWith("episode.mkv") || it.name.endsWith("clip.mkv.part") })
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
        assertEquals("video", holding.resolve("$now-episode.mkv").readText())
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
        assertEquals("video", holding.resolve("$now-episode.mkv").readText())
        assertEquals(1, withSystemTrash().purgeHeldFiles(now + 30 * day))

        assertEquals("stray", stray.readText())
        assertTrue(strayFolder.exists())
    }

    @Test
    fun trashing_held_subtitles_does_not_touch_target_outside_downloads_folder() {
        val video = video()
        val subtitles = subtitlesOf(video)
        val target = outside.resolve("keep.txt").also { it.writeText("keep") }
        Files.createSymbolicLink(subtitles.resolve("link-to-outside"), target)
        val trash = withoutSystemTrash()
        trash.moveAway(video.uri())

        assertEquals(0, trash.purgeHeldFiles(now + 7 * day))
        assertEquals("keep", target.readText())
        assertEquals("video", holding.resolve("$now-episode.mkv").readText())
        assertTrue(Files.isSymbolicLink(holding.resolve("$now-episode.mkv.subtitles").resolve("link-to-outside")))

        assertEquals(2, withSystemTrash().purgeHeldFiles(now + 7 * day))

        assertEquals("keep", target.readText())
        assertEquals(emptyList(), held())
    }

    @Test
    fun an_index_line_that_names_something_outside_the_holding_folder_touches_nothing() {
        val trash = withoutSystemTrash()
        trash.moveAway(video().uri())
        val target = outside.resolve("keep.txt").also { it.writeText("keep") }
        val index = holding.resolve(DesktopDownloadsTrash.IndexFileName)
        Files.write(index, Files.readAllLines(index) + listOf("1\t../../outside/keep.txt", "1\t..", "1\t/etc/hosts"))

        assertEquals(0, trash.purgeHeldFiles(now + 30 * day))
        assertEquals("video", holding.resolve("$now-episode.mkv").readText())

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
