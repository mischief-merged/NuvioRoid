package com.nuvio.app.features.downloads

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review 4, finding 5: delete, removal after watching and cancel protect every profile's files and
 * never delete outright what they may only send to the Trash; a finished download never replaces a
 * file it did not create.
 */
class DeleteAcrossProfilesTest {
    private val world = FolderWorld()
    private val realBackend = DownloadsRepository.backend

    private class Backend(world: FolderWorld) : DownloadsBackend by LocationsBackend(world) {
        val inner = LocationsBackend(world)
        var owners: FileOwnership? = FileOwnership.Free
        val discarded = mutableListOf<String?>()
        val events = mutableListOf<String>()
        var syncWorks = true
        override fun loadPayload() = inner.payload
        override fun loadPayload(profileId: Int) = loadPayload()
        override fun savePayload(payload: String) { inner.payload = payload; events += "save ${decodeStoredDownloads(payload)!!.map { it.id }}" }
        override fun savePayload(profileId: Int, payload: String) = savePayload(payload)
        override fun syncPayload(): Boolean { events += "sync $syncWorks"; return syncWorks }
        override fun trashFile(localFileUri: String): Boolean { events += "trash"; return inner.trashFile(localFileUri) }
        override fun removeFile(localFileUri: String?): Boolean = inner.removeFile(localFileUri)
        override fun ownershipOf(item: DownloadItem, fileUri: String): FileOwnership? = owners
        override fun discardFile(localFileUri: String?): Boolean {
            discarded += localFileUri
            return true
        }
        override fun locate(item: DownloadItem): FileLookup = inner.locate(item)
        override fun isConnected(item: DownloadItem): Boolean = inner.isConnected(item)
    }

    private val backend = Backend(world)

    @BeforeTest
    fun start() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @AfterTest
    fun clean_up() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        world.close()
    }

    private fun load(vararg items: DownloadItem) {
        backend.inner.payload = storedPayload(items.toList())
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
    }

    private fun film(id: String) = movieItem(id, file = world.appFolder.createDirectories().resolve("$id.mp4").writeVideo(1))

    @Test
    fun a_delete_saves_the_record_removal_durably_before_the_file_goes_and_keeps_both_when_that_save_fails() {
        val item = film("a")
        load(item)
        backend.events.clear()

        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item.id))
        assertEquals(listOf("save []", "sync true", "trash"), backend.events.filter { it.startsWith("save") || it.startsWith("sync") || it == "trash" }.take(3))

        load(item)
        backend.events.clear()
        backend.syncWorks = false
        assertEquals(DownloadDeleteResult.NotMoved, DownloadsRepository.deleteDownload(item.id))
        assertTrue("trash" !in backend.events, "the file went although the record removal was not on disk")
        assertEquals(listOf(item.id), DownloadsRepository.uiState.value.items.map { it.id })
    }

    @Test
    fun a_trash_that_fails_after_the_record_removal_puts_the_record_back() {
        val item = film("a")
        load(item)
        backend.inner.trashSucceeds = false

        assertEquals(DownloadDeleteResult.NotMoved, DownloadsRepository.deleteDownload(item.id))

        assertEquals(listOf(item.id), DownloadsRepository.uiState.value.items.map { it.id })
        assertEquals(listOf(item.id), decodeStoredDownloads(backend.inner.payload!!)!!.map { it.id })
    }

    @Test
    fun a_file_another_profile_uses_is_kept_on_delete_and_on_permanent_delete() {
        val item = film("a")
        load(item)
        backend.owners = FileOwnership.Owned

        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item.id))
        load(item)
        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item.id, permanently = true))

        assertTrue(backend.inner.trashed.isEmpty())
        assertTrue(backend.inner.removedOutright.isEmpty())
    }

    @Test
    fun when_the_lists_of_other_profiles_cannot_be_read_nothing_is_deleted_and_the_record_stays() {
        val item = film("a").copy(autoDownloadRuleKey = "rule")
        load(item)
        backend.owners = FileOwnership.Unknown

        assertEquals(DownloadDeleteResult.NotMoved, DownloadsRepository.deleteDownload(item.id))
        assertEquals(AutoDownloadRemovalResult.NotMoved, DownloadsRepository.removeWatchedAutoDownload(item.id, "rule"))

        assertEquals(listOf(item.id), DownloadsRepository.uiState.value.items.map { it.id })
        assertTrue(backend.inner.trashed.isEmpty())
    }

    @Test
    fun cancelling_a_download_discards_its_file_to_the_trash_and_never_deletes_it_outright() {
        val item = film("a")
        load(item)

        DownloadsRepository.cancelDownload(item.id)

        assertTrue(backend.inner.removedOutright.isEmpty())
        assertEquals(1, backend.discarded.size)
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test
    fun downloading_the_same_content_again_never_deletes_the_old_file_outright_or_one_another_profile_uses() {
        val item = film("a")
        load(item)
        backend.owners = FileOwnership.Owned
        enqueueAgain(item)
        assertTrue(backend.discarded.isEmpty() && backend.inner.removedOutright.isEmpty())

        load(item)
        backend.owners = FileOwnership.Free
        enqueueAgain(item)
        assertEquals(listOf(item.localFileUri), backend.discarded)
        assertTrue(backend.inner.removedOutright.isEmpty())
    }

    private fun enqueueAgain(item: DownloadItem) {
        DownloadsRepository.enqueueFromStream(
            contentType = item.contentType, videoId = item.videoId, parentMetaId = item.parentMetaId, parentMetaType = item.parentMetaType,
            title = item.title, logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null,
            episodeTitle = null, episodeThumbnail = null, stream = testStream(item.sourceUrl),
            // A finished file is replaced only when the user chose to.
            userChoseReplacement = true,
        )
    }

    @Test
    fun the_desktop_tells_a_file_another_profile_or_its_sidecar_uses_and_fails_closed_on_unreadable_lists() {
        val mine = film("a")
        val hooks = ProfileHooks(mapOf(1 to listOf(mine), 2 to listOf(mine.copy(id = "theirs"))))
        val folders = DesktopDownloadFolders(world.locations, world.validator, world.mover(), hooks, trashPath = world.trash::send)

        assertEquals(FileOwnership.Owned, folders.ownershipOf(mine, mine.localFileUri!!))
        hooks.setList(2, emptyList())
        assertEquals(FileOwnership.Free, folders.ownershipOf(mine, mine.localFileUri!!))
        // A record whose file is inside this download's sidecar folder.
        val inside = world.appFolder.resolve("a.mp4.subtitles/x.mp4").also { it.parent.createDirectories() }.writeVideo(3)
        hooks.setList(2, listOf(movieItem("x", file = inside)))
        assertEquals(FileOwnership.Owned, folders.ownershipOf(mine, mine.localFileUri!!))
        hooks.unreadable = true
        assertEquals(FileOwnership.Unknown, folders.ownershipOf(mine, mine.localFileUri!!))
    }

    @Test
    fun the_desktop_sends_a_partial_file_to_the_trash_only_when_no_other_record_uses_it() {
        val app = world.appFolder.createDirectories()
        val part = app.resolve("ep.mp4.part").also { Files.write(it, ByteArray(100) { 1 }) }
        val mine = movieItem("ep").copy(status = DownloadStatus.Paused, localFileUri = null, partialFileKey = DownloadMover.identityOf(part))
        val hooks = ProfileHooks(mapOf(1 to listOf(mine), 2 to listOf(mine.copy(id = "theirs"))))
        val folders = DesktopDownloadFolders(world.locations, world.validator, world.mover(), hooks, trashPath = world.trash::send)

        assertEquals(false, folders.removePartialOf(mine))
        assertTrue(part.exists())
        hooks.setList(2, emptyList())
        assertEquals(true, folders.removePartialOf(mine))
        assertFalse(part.exists())
        assertTrue(world.trash.holds(ByteArray(100) { 1 }))
    }

    @Test
    fun a_finished_download_never_replaces_a_file_already_at_its_name_with_or_without_links() {
        val folder = world.root.resolve("dl").createDirectories()
        val existing = folder.resolve("film.mp4").also { Files.writeString(it, "someone's film") }
        val part = folder.resolve("film.mp4.part").also { Files.writeString(it, "the new download") }

        val published = publishDurably(part, existing, flushFolder = { true })

        assertEquals(folder.resolve("film (2).mp4"), published)
        assertEquals("someone's film", Files.readString(existing))
        assertEquals("the new download", Files.readString(published))
        assertFalse(part.exists())

        val part2 = folder.resolve("film.mp4.part").also { Files.writeString(it, "a third one") }
        val noLinks = publishDurably(part2, existing, flushFolder = { true }, link = { _, _ -> throw UnsupportedOperationException() })
        assertEquals(folder.resolve("film (3).mp4"), noLinks)
        assertContentEquals("a third one".toByteArray(), Files.readAllBytes(noLinks))
        assertEquals("someone's film", Files.readString(existing))
    }
}
