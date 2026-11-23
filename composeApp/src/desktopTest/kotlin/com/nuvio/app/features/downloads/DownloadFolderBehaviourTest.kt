package com.nuvio.app.features.downloads

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the rows say, what the downloader and auto-download do when a location is away, and the trash per location. */
class DownloadFolderBehaviourTest {
    private val world = FolderWorld()

    @AfterTest
    fun clean_up() {
        DesktopDownloads.replaceForTest(null)
        world.close()
    }

    private val item = episodeItem("A", 1, bytes = 1_000L)

    @Test
    fun a_finished_item_in_a_location_that_is_away_says_drive_not_connected_and_not_failed() {
        val away = downloadRowStatus(item, nowEpochMs = 0, location = FolderAvailability.NotConnected)
        val gone = downloadRowStatus(item, nowEpochMs = 0, location = FolderAvailability.NotFound)
        val here = downloadRowStatus(item, nowEpochMs = 0, location = FolderAvailability.Available)

        assertEquals(DownloadPhase.DriveNotConnected, away.phase)
        assertEquals(DownloadPhase.FolderNotFound, gone.phase)
        assertEquals(DownloadPhase.Completed, here.phase)
        assertTrue(away.result == null)
    }

    @Test
    fun a_download_waiting_for_its_drive_says_so_and_a_failed_one_is_not_changed() {
        val waiting = item.copy(status = DownloadStatus.Paused, pausedByFolder = true)
        val failed = item.copy(status = DownloadStatus.Failed)

        assertEquals(DownloadPhase.WaitingForDrive, downloadRowStatus(waiting, 0, location = FolderAvailability.NotConnected).phase)
        assertEquals(DownloadPhase.WaitingForDrive, downloadRowStatus(waiting, 0).phase)
        assertEquals(DownloadPhase.Failed, downloadRowStatus(failed, 0, location = FolderAvailability.NotConnected).phase)
        assertEquals(DownloadPhase.Paused, downloadRowStatus(item.copy(status = DownloadStatus.Paused), 0).phase)
    }

    @Test
    fun the_downloader_pauses_a_download_whose_location_is_away_and_writes_nothing() {
        val drive = world.driveLocation("Archive")
        val folders = DesktopDownloadFolders(world.locations, world.validator, world.mover(), ProfileHooks(emptyList()))
        DesktopDownloads.replaceForTest(folders)
        world.unplug("Archive")
        val paused = CountDownLatch(1)
        var other = false

        DownloadsPlatformDownloader.start(
            request = DownloadPlatformRequest(item.copy(status = DownloadStatus.Downloading, locationId = drive.id)),
            onProgress = { _, _ -> },
            onSuccess = { _, _ -> other = true },
            onFailure = { other = true },
            onPaused = { paused.countDown() },
        )

        assertTrue(paused.await(10, TimeUnit.SECONDS), "the download was not held")
        assertFalse(other)
        // Nothing was created where the drive should be: no phantom folder on the system disk.
        assertFalse(world.volumes.resolve("Archive").exists())
        assertEquals(emptyList(), Files.list(world.appFolder.also { Files.createDirectories(it) }).use { it.toList() })
    }

    @Test
    fun free_space_is_read_on_the_volume_of_the_location_the_download_goes_to() {
        val drive = world.driveLocation("Archive")
        world.appFolder.also { Files.createDirectories(it) }
        // A fixed reading per folder: the real free space changes between two reads.
        val asked = mutableListOf<File>()
        val folders = DesktopDownloadFolders(
            world.locations, world.validator, world.mover(), ProfileHooks(emptyList()),
            freeSpaceOf = { f -> asked += f; if (f.toPath().startsWith(drive.path)) 111L else 222L },
        )

        assertEquals(111L, folders.freeBytesOf(item.copy(locationId = drive.id)))
        assertEquals(222L, folders.freeBytesOf(item))
        assertEquals(listOf(drive.path.toFile(), world.appFolder.toFile()), asked)
    }

    @Test
    fun auto_download_for_a_show_whose_location_is_away_waits_with_a_reason_and_queues_nothing_elsewhere() = runTest {
        val store = AutoDownloadStore(MemoryAutoDownloadPayloads()) { 1 }
        store.upsert(1, testRule(keepCount = 2))
        var away = true
        var started = 0
        val engine = AutoDownloadEngine(
            store = store,
            loadEpisodes = { (1..4).map { testEpisode(it) } },
            isWatched = { _, _ -> false },
            downloads = { emptyList() },
            seasonDefaults = { SeasonDownloadDefaults() },
            startBatch = {
                started++
                SeasonBatchStart.Started
            },
            cancelBatch = { _, _ -> },
            nowEpochMs = { 0L },
            locationAway = { away },
        )

        engine.runCheck()

        assertEquals(0, started)
        assertEquals(AutoDownloadStatus.FolderNotConnected, engine.statuses.value[testRule().key])

        away = false
        engine.runCheck(ignoreWaiting = true)

        assertEquals(1, started, "it did not go on when the location came back")
    }

    @Test
    fun a_file_in_a_chosen_location_is_trashed_from_there_and_one_outside_every_location_is_refused() {
        val drive = world.driveLocation("Archive")
        val trashed = mutableListOf<File>()
        val trash = DesktopDownloadsTrash(
            downloadsDir = { world.appFolder.also { Files.createDirectories(it) } },
            systemTrash = { file -> trashed += file; file.delete() },
            otherDirs = { listOf(drive.path) },
        )
        val onDrive = drive.path.resolve("a.mp4").writeVideo(1)
        val outside = world.root.resolve("elsewhere/b.mp4").writeVideo(2)

        assertTrue(trash.moveAway(onDrive.toUri().toString()))
        assertFalse(trash.moveAway(outside.toUri().toString()))

        assertEquals(listOf(onDrive.toFile()), trashed)
        assertTrue(outside.exists())
    }

    @Test
    fun without_a_system_trash_a_file_is_held_inside_its_own_location_until_system_trash_is_available() {
        val drive = world.driveLocation("Archive")
        var now = 1_000L
        val trashWithoutSystem = DesktopDownloadsTrash(
            downloadsDir = { world.appFolder.also { Files.createDirectories(it) } },
            systemTrash = null,
            nowEpochMs = { now },
            otherDirs = { listOf(drive.path) },
        )
        val onDrive = drive.path.resolve("a.mp4").writeVideo(1)

        assertTrue(trashWithoutSystem.moveAway(onDrive.toUri().toString()))
        val archived = drive.path.resolve(".removed/$now-a.mp4")
        assertTrue(archived.exists(), "the file left the drive's volume")
        assertFalse(onDrive.exists())

        // Without system Trash, the archived file is never permanently deleted.
        assertEquals(0, trashWithoutSystem.purgeHeldFiles(now + HeldFileDays * 24L * 60 * 60 * 1000 + 1))
        assertTrue(archived.exists())

        // Once system Trash is available, the aged archived file moves to system Trash.
        val trashed = mutableListOf<File>()
        val trashDir = world.root.resolve("system-trash").also { Files.createDirectories(it) }
        val trashWithSystem = DesktopDownloadsTrash(
            downloadsDir = { world.appFolder.also { Files.createDirectories(it) } },
            systemTrash = { file ->
                trashed += file
                Files.move(file.toPath(), trashDir.resolve(file.name))
                true
            },
            nowEpochMs = { now },
            otherDirs = { listOf(drive.path) },
        )
        assertEquals(1, trashWithSystem.purgeHeldFiles(now + HeldFileDays * 24L * 60 * 60 * 1000 + 1))
        assertEquals(listOf(archived.toFile()), trashed)
        assertFalse(archived.exists())
    }
}
