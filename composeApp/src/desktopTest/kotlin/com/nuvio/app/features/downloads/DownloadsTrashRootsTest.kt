package com.nuvio.app.features.downloads

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadsTrashRootsTest {
    private val world = FolderWorld()
    private val realParent = world.root.resolve("real-parent").createDirectories()
    private val realRoot = realParent.resolve("downloads").createDirectories()
    private val systemFolder = world.root.resolve("system-trash").createDirectories()
    private val moved = mutableListOf<Pair<Path, Path>>()
    private lateinit var location: DesktopDownloadLocation

    @AfterTest fun clean_up() {
        DesktopDownloads.replaceForTest(null)
        world.close()
    }

    private fun register(linkAtRoot: Boolean = false): Path {
        val configured = if (linkAtRoot) {
            world.root.resolve("chosen-folder").also { Files.createSymbolicLink(it, realRoot) }
        } else {
            world.root.resolve("chosen-parent").also { Files.createSymbolicLink(it, realParent) }.resolve("downloads")
        }
        location = world.locations.register(configured)
        assertEquals(configured, location.path)
        return location.path
    }

    private fun trash(system: Boolean = false) = DesktopDownloadsTrash(
        downloadsDir = { world.appFolder },
        systemTrash = if (system) ({ file ->
            val destination = systemFolder.resolve("${moved.size}-${file.name}")
            Files.move(file.toPath(), destination)
            moved += file.toPath() to destination
            true
        }) else null,
        nowEpochMs = { 23L },
        otherDirs = { listOf(location.path) },
    )

    private fun held(name: String) = realRoot.resolve(DesktopDownloadsTrash.HoldingFolderName).resolve("23-$name")

    private fun video(configured: Path): Path = configured.resolve("episode.mkv").also { Files.writeString(it, "video bytes") }

    private fun subtitles(configured: Path): Path = configured.resolve("episode.mkv.subtitles").createDirectories().also {
        Files.writeString(it.resolve("en.srt"), "subtitles")
    }

    private fun ordinaryRemoval(linkAtRoot: Boolean) {
        val configured = register(linkAtRoot)
        val video = video(configured)
        subtitles(configured)
        assertTrue(trash(system = true).moveAway(video.toUri().toString()))
        assertFalse(Files.exists(video, LinkOption.NOFOLLOW_LINKS))
        assertEquals(listOf(realRoot.resolve("episode.mkv"), realRoot.resolve("episode.mkv.subtitles")), moved.map { it.first })
        assertEquals("video bytes", Files.readString(moved[0].second))
        assertEquals("subtitles", Files.readString(moved[1].second.resolve("en.srt")))
    }

    @Test fun ordinary_removal_accepts_a_registered_folder_below_a_linked_parent() = ordinaryRemoval(false)
    @Test fun ordinary_removal_accepts_a_registered_folder_that_is_a_link() = ordinaryRemoval(true)

    private fun watchedRemoval(linkAtRoot: Boolean) {
        val configured = register(linkAtRoot)
        val video = video(configured)
        subtitles(configured)
        assertEquals(AutoDownloadRemovalResult.Removed, trash(system = true).moveWatchedAway(video.toUri().toString()) { true })
        assertFalse(Files.exists(video, LinkOption.NOFOLLOW_LINKS))
        assertEquals("video bytes", Files.readString(moved[0].second))
        assertEquals("subtitles", Files.readString(moved[1].second.resolve("en.srt")))
        assertTrue(Files.list(realRoot).use { entries -> entries.noneMatch { it.fileName.toString().startsWith(".watched-removal-") } })
    }

    @Test fun watched_removal_accepts_a_registered_folder_below_a_linked_parent() = watchedRemoval(false)
    @Test fun watched_removal_accepts_a_registered_folder_that_is_a_link() = watchedRemoval(true)

    @Test fun cancelled_watched_removal_restores_files_through_the_registered_alias() {
        val configured = register()
        val video = video(configured)
        val subtitles = subtitles(configured)
        var wanted = true
        val trash = DesktopDownloadsTrash(
            downloadsDir = { configured },
            systemTrash = { file ->
                Files.move(file.toPath(), systemFolder.resolve(file.name))
                if (file.name.endsWith(".subtitles")) wanted = false
                true
            },
        )
        assertEquals(AutoDownloadRemovalResult.Changed, trash.moveWatchedAway(video.toUri().toString()) { wanted })
        assertEquals("video bytes", Files.readString(video))
        assertEquals("subtitles", Files.readString(subtitles.resolve("en.srt")))
        assertTrue(Files.list(realRoot).use { entries -> entries.noneMatch { it.fileName.toString().startsWith(".watched-removal-") } })
    }

    private fun partialRemoval(linkAtRoot: Boolean) {
        val configured = register(linkAtRoot)
        val partial = configured.resolve("episode.mkv.part").also { Files.writeString(it, "partial bytes") }
        assertTrue(trash().moveToTrash(partial))
        assertFalse(Files.exists(partial, LinkOption.NOFOLLOW_LINKS))
        assertEquals("partial bytes", Files.readString(held("episode.mkv.part")))
    }

    @Test fun partial_retirement_accepts_a_registered_folder_below_a_linked_parent() = partialRemoval(false)
    @Test fun partial_retirement_accepts_a_registered_folder_that_is_a_link() = partialRemoval(true)

    @Test fun publication_and_rejection_keep_bytes_in_a_registered_folder_below_a_linked_parent() {
        val configured = register()
        val bytes = videoFile(37)
        val partial = configured.resolve("episode.mkv.part").also { Files.write(it, bytes) }
        val trash = trash()
        val published = publishDurably(partial, configured.resolve("episode.mkv"), retirePartial = trash::moveToTrash,
            beforeRemovingPartial = { final ->
                assertContentEquals(bytes, Files.readAllBytes(partial))
                assertContentEquals(bytes, Files.readAllBytes(final))
                true
            },
        )
        assertContentEquals(bytes, Files.readAllBytes(held("episode.mkv.part")))
        val key = assertNotNull(DownloadsPlatformDownloader.rememberPublishedFile(published))
        assertTrue(DownloadsPlatformDownloader.removeJustPublished(published.toFile().toURI().toString(), key, trash::moveToTrash))
        assertFalse(Files.exists(published, LinkOption.NOFOLLOW_LINKS))
        assertContentEquals(bytes, Files.readAllBytes(held("episode.mkv")))
    }

    @Test fun an_owned_sidecar_is_held_when_its_registered_folder_is_a_link() {
        val configured = register(linkAtRoot = true)
        val video = video(configured)
        val trash = trash()
        DesktopDownloads.replaceForTest(DesktopDownloadFolders(
            world.locations, world.validator, world.mover(), trashPath = trash::moveToTrash,
        ))
        val storage = DownloadSubtitleStorage(video.toUri().toString())
        storage.write("en.srt", "subtitles")
        storage.remove()
        assertFalse(Files.exists(configured.resolve("episode.mkv.subtitles"), LinkOption.NOFOLLOW_LINKS))
        assertEquals("subtitles", Files.readString(held("episode.mkv.subtitles").resolve("en.srt")))
        assertEquals("video bytes", Files.readString(video))
    }

    @Test fun held_files_in_a_registered_folder_below_a_linked_parent_can_later_reach_system_trash() {
        val configured = register()
        val video = video(configured)
        subtitles(configured)
        assertTrue(trash().moveAway(video.toUri().toString()))
        assertEquals("video bytes", Files.readString(held("episode.mkv")))
        assertEquals(2, trash(system = true).purgeHeldFiles(23L + 8 * 24L * 60 * 60 * 1000))
        assertEquals("video bytes", Files.readString(moved[0].second))
        assertEquals("subtitles", Files.readString(moved[1].second.resolve("en.srt")))
    }

    @Test fun the_real_address_of_a_registered_alias_is_also_accepted() {
        register()
        val partial = realRoot.resolve("episode.mkv.part").also { Files.writeString(it, "partial bytes") }
        assertTrue(trash().moveToTrash(partial))
        assertEquals("partial bytes", Files.readString(held("episode.mkv.part")))
    }

    @Test fun unregistered_aliases_and_links_below_a_registered_folder_are_refused() {
        val configured = register()
        val original = video(configured)
        val alias = world.root.resolve("unregistered-folder").also { Files.createSymbolicLink(it, realRoot) }
        val nested = realRoot.resolve("nested").createDirectories()
        val partial = nested.resolve("saved.part").also { Files.writeString(it, "partial bytes") }
        Files.createSymbolicLink(configured.resolve("nested-link"), nested)
        val trash = trash(system = true)
        assertFalse(trash.moveAway(alias.resolve(original.fileName).toUri().toString()))
        assertEquals(AutoDownloadRemovalResult.NotMoved, trash.moveWatchedAway(alias.resolve(original.fileName).toUri().toString()) { true })
        assertFalse(trash.moveToTrash(alias.resolve(original.fileName)))
        assertFalse(trash.moveToTrash(configured.resolve("nested-link/saved.part")))
        assertFalse(trash.moveToTrash(configured.resolve("nested-link/../episode.mkv")))
        assertFalse(trash.moveToTrash(configured))
        assertFalse(trash.moveToTrash(realRoot))
        Files.createDirectories(realRoot.resolve(DesktopDownloadsTrash.HoldingFolderName))
        assertFalse(trash.moveToTrash(configured.resolve(DesktopDownloadsTrash.HoldingFolderName)))
        assertTrue(moved.isEmpty())
        assertEquals("video bytes", Files.readString(original))
        assertEquals("partial bytes", Files.readString(partial))
    }

    @Test fun a_changed_registered_alias_cannot_redirect_holding_after_a_failed_system_call() {
        val configured = register(linkAtRoot = true)
        val partial = configured.resolve("episode.mkv.part").also { Files.writeString(it, "partial bytes") }
        val other = world.root.resolve("another-folder").createDirectories()
        Files.writeString(other.resolve(partial.fileName), "another file")
        val trash = DesktopDownloadsTrash(
            downloadsDir = { configured },
            systemTrash = { file ->
                assertEquals(realRoot.resolve(partial.fileName), file.toPath())
                Files.delete(configured)
                Files.createSymbolicLink(configured, other)
                false
            },
            nowEpochMs = { 23L },
        )
        assertTrue(trash.moveToTrash(partial))
        assertEquals("partial bytes", Files.readString(held("episode.mkv.part")))
        assertEquals("another file", Files.readString(other.resolve(partial.fileName)))
        assertFalse(Files.exists(other.resolve(DesktopDownloadsTrash.HoldingFolderName)))
    }
}
