package com.nuvio.app.features.downloads

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What must still hold after the offline work (subtitles, skip times, episode lists, artwork, placeholder
 * detection) met the folder move: one sidecar folder per video that travels with it, background writes
 * that keep off a download being moved, and flagged files that are never removed automatically.
 */
class MergedOfflineInvariantsTest {
    private val world = FolderWorld()
    private val realBackend = DownloadsRepository.backend

    /** A backend whose held items are the ones a move or a delete has the item lock of. */
    private inner class HeldBackend : DownloadsBackend by LocationsBackend(world) {
        val inner = LocationsBackend(world)
        override fun loadPayload() = inner.payload
        override fun loadPayload(profileId: Int) = loadPayload()
        override fun savePayload(payload: String) { inner.payload = payload }
        override fun savePayload(profileId: Int, payload: String) = savePayload(payload)
        override fun heldByFolder(item: DownloadItem): Boolean = ItemLocks.isHeld(item.id)
        override fun trashFile(localFileUri: String): Boolean = inner.trashFile(localFileUri)
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
            localFileUri?.let(DownloadLocations::fileOfUri)?.takeIf { it.isFile }?.toURI()?.toString()
    }

    private val backend = HeldBackend()

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

    @Test
    fun every_offline_companion_lives_in_the_sidecar_folder_and_travels_with_a_move() {
        val video = world.appFolder.createDirectories().resolve("show.mp4").writeVideo(1)
        val sidecar = video.resolveSibling(DownloadSidecarFiles.sidecarFolderName("show.mp4")).createDirectories()
        val names = listOf(SkipTimesSidecar.FILE_NAME, EpisodeListSidecar.FILE_NAME, "${ArtworkRole.Poster.fileStem}.jpg", "manifest.json", "0.srt")
        names.forEach { Files.writeString(sidecar.resolve(it), "saved $it") }
        val item = movieItem("show", null, video, Files.size(video))
        val drive = world.driveLocation("Archive")

        val report = DesktopDownloadFolders(world.locations, world.validator, world.mover(), ProfileHooks(listOf(item))).move(setOf(item.id), drive.path.toString())

        assertEquals(1, report.moved)
        names.forEach { assertEquals("saved $it", Files.readString(drive.path.resolve("show.mp4.subtitles").resolve(it))) }
        assertFalse(sidecar.exists())
        assertTrue(world.trash.files().any { it.toString().endsWith("show.mp4.subtitles/${SkipTimesSidecar.FILE_NAME}") })
    }

    @Test
    fun a_sidecar_write_and_the_background_passes_keep_off_a_download_a_move_holds() {
        val video = world.appFolder.createDirectories().resolve("show.mp4").writeVideo(1)
        val item = movieItem("show", null, video, Files.size(video))
        backend.inner.payload = storedPayload(listOf(item))
        DownloadsRepository.ensureLoaded()
        val uri = DownloadsRepository.uiState.value.items.single().localFileUri!!

        assertTrue(DownloadCompanionGuard.stillWanted(uri))
        assertTrue(ItemLocks.tryAcquire(item.id))
        try {
            assertFalse(DownloadCompanionGuard.stillWanted(uri), "a sidecar write was allowed while a move held the download")
            assertTrue(DownloadsRepository.isBusyOrAway(DownloadsRepository.uiState.value.items.single()))
        } finally {
            ItemLocks.release(item.id)
        }
        assertTrue(DownloadCompanionGuard.stillWanted(uri))
    }

    @Test
    fun a_flagged_placeholder_is_never_removed_after_watching() {
        val video = world.appFolder.createDirectories().resolve("show.mp4").writeVideo(1)
        val flagged = movieItem("show", null, video, Files.size(video))
            .copy(autoDownloadRuleKey = "rule", framesCheck = DownloadFramesCheck.NotTheRealVideo)
        backend.inner.payload = storedPayload(listOf(flagged))
        DownloadsRepository.ensureLoaded()

        assertEquals(AutoDownloadRemovalResult.Changed, DownloadsRepository.removeWatchedAutoDownload(flagged.id, "rule"))

        assertTrue(backend.inner.trashed.isEmpty())
        assertTrue(video.exists())
    }

    @Test
    fun a_flagged_file_kept_beside_a_new_copy_is_owned_by_its_record_so_a_move_or_delete_of_the_new_one_leaves_it() {
        val app = world.appFolder.createDirectories()
        val flaggedFile = app.resolve("show.mp4").writeVideo(1)
        val newFile = app.resolve("show (2).mp4").writeVideo(2)
        val flagged = movieItem("show", null, flaggedFile, Files.size(flaggedFile)).copy(framesCheck = DownloadFramesCheck.NotTheRealVideo)
        val fresh = movieItem("show", null, newFile, Files.size(newFile)).copy(id = "fresh", fileName = "show (2).mp4")
        val hooks = ProfileHooks(listOf(flagged, fresh))
        val folders = DesktopDownloadFolders(world.locations, world.validator, world.mover(), hooks, trashPath = world.trash::send)

        assertEquals(FileOwnership.Owned, folders.ownershipOf(fresh, flaggedFile.toUri().toString()))
        assertEquals(1, folders.move(setOf(fresh.id), world.driveLocation("Archive").path.toString()).moved)
        assertTrue(flaggedFile.exists())
    }
}
