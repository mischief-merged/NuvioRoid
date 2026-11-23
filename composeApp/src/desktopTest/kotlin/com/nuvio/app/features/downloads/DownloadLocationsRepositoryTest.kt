package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The list of downloads with several locations. The backend looks at real temporary folders; nothing
 * is fetched and no real downloads folder is touched.
 */
class DownloadLocationsRepositoryTest {
    private val world = FolderWorld()
    private val backend = LocationsBackend(world)
    private val realBackend = DownloadsRepository.backend
    private val realLimits = DownloadsRepository.autoDownloadLimits

    @BeforeTest
    fun use_temporary_locations() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.autoDownloadLimits = realLimits
        world.close()
    }

    private fun load(vararg items: DownloadItem) {
        backend.payload = storedPayload(items.toList())
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
    }

    private fun items() = DownloadsRepository.uiState.value.items

    /** An episode with a real file in [location] (null: the app folder). */
    private fun episodeIn(location: DesktopDownloadLocation?, show: String, episode: Int, ruleKey: String? = null, bytes: Long = 100): DownloadItem {
        val base = episodeItem(show, episode, locationId = location?.id, bytes = bytes, ruleKey = ruleKey)
        val folder = location?.path ?: world.appFolder
        val file = folder.resolve(base.fileName).writeVideo(episode)
        return base.copy(localFileUri = file.toUri().toString())
    }

    @Test
    fun an_unplugged_drive_never_purges_or_fails_a_record_and_everything_recovers_when_it_is_back() {
        val drive = world.driveLocation("Archive")
        val onDrive = episodeIn(drive, "tt1", 1)
        val inApp = episodeIn(null, "tt2", 1)
        world.unplug("Archive")

        load(onDrive, inApp)

        val loaded = items().associateBy { it.id }
        assertEquals(DownloadStatus.Completed, loaded.getValue(onDrive.id).status)
        assertEquals(onDrive.localFileUri, loaded.getValue(onDrive.id).localFileUri, "the address was cleared")
        assertNull(loaded.getValue(onDrive.id).errorMessage)
        assertEquals(DownloadStatus.Completed, loaded.getValue(inApp.id).status)
        assertTrue(backend.trashed.isEmpty())
        assertTrue(backend.started.isEmpty(), "something was fetched again")
        // Not playable while away, which is not the same as lost.
        assertNull(DownloadsRepository.findPlayableDownload("tt1", 1, 1))
        assertNotNull(DownloadsRepository.findPlayableDownload("tt2", 1, 1))
        // The record is stored as it was.
        assertTrue(backend.payload!!.contains(onDrive.id))

        world.replug("Archive")

        assertTrue(DownloadsRepository.verifyCompletedDownload(onDrive.id))
        assertNotNull(DownloadsRepository.findPlayableDownload("tt1", 1, 1))
        assertEquals(DownloadStatus.Completed, items().first { it.id == onDrive.id }.status)
    }

    @Test
    fun a_file_that_is_really_gone_from_a_connected_location_still_becomes_a_failed_download() {
        val drive = world.driveLocation("Archive")
        val item = episodeIn(drive, "tt1", 1)
        java.nio.file.Files.delete(java.nio.file.Paths.get(java.net.URI(item.localFileUri!!)))

        load(item)

        assertEquals(DownloadStatus.Failed, items().single().status)
    }

    @Test
    fun deleting_a_download_on_an_unplugged_drive_changes_nothing_and_says_why() {
        val drive = world.driveLocation("Archive")
        val onDrive = episodeIn(drive, "tt1", 1)
        load(onDrive)
        world.unplug("Archive")

        val result = DownloadsRepository.deleteDownload(onDrive.id)

        assertEquals(DownloadDeleteResult.NotReachable, result)
        assertEquals(listOf(onDrive.id), items().map { it.id })
        assertTrue(backend.trashed.isEmpty())
        assertTrue(backend.removedOutright.isEmpty())
    }

    @Test
    fun removal_after_watching_leaves_an_item_on_an_unplugged_drive_alone_and_scheduled() {
        val drive = world.driveLocation("Archive")
        val rule = autoDownloadRuleKey("series", "tt1")
        val onDrive = episodeIn(drive, "tt1", 1, ruleKey = rule)
        load(onDrive)
        world.unplug("Archive")

        val result = DownloadsRepository.removeWatchedAutoDownload(onDrive.id, rule)

        assertEquals(AutoDownloadRemovalResult.NotReachable, result)
        assertEquals(listOf(onDrive.id), items().map { it.id })
        assertTrue(backend.trashed.isEmpty())

        world.replug("Archive")

        assertEquals(AutoDownloadRemovalResult.Removed, DownloadsRepository.removeWatchedAutoDownload(onDrive.id, rule))
    }

    @Test
    fun the_storage_limit_counts_automatic_downloads_in_connected_locations_and_never_deletes_to_make_room() {
        val drive = world.driveLocation("Archive")
        val rule = autoDownloadRuleKey("series", "tt1")
        load(
            episodeIn(null, "tt1", 1, ruleKey = rule, bytes = 100),
            episodeIn(drive, "tt1", 2, ruleKey = rule, bytes = 500),
            episodeIn(null, "tt9", 1, ruleKey = null, bytes = 7_000), // started by hand: not counted
        )

        assertEquals(600L, DownloadsRepository.autoDownloadBytes())

        world.unplug("Archive")
        assertEquals(100L, DownloadsRepository.autoDownloadBytes(), "a drive that is away is not counted")

        world.replug("Archive")
        assertEquals(600L, DownloadsRepository.autoDownloadBytes())
        // Nothing was removed by any of this.
        assertEquals(3, items().size)
        assertTrue(backend.trashed.isEmpty())
    }

    @Test
    fun a_new_episode_of_a_show_goes_to_the_location_of_its_other_episodes_and_a_movie_to_the_default() {
        val drive = world.driveLocation("Archive")
        load(episodeIn(drive, "tt1", 1))

        enqueue("tt1", episode = 2)
        enqueue("tt5", episode = 1)
        enqueueMovie("tt7")

        val byId = items().associateBy { it.videoId }
        assertEquals(drive.id, byId.getValue("tt1:1:2").locationId, "the show was split")
        assertEquals(DownloadLocations.AppId, byId.getValue("tt5:1:1").locationId)
        assertEquals(DownloadLocations.AppId, byId.getValue("tt7").locationId)
    }

    @Test
    fun a_changed_default_takes_new_shows_and_movies_but_not_shows_that_are_already_somewhere() {
        val drive = world.driveLocation("Archive")
        load(episodeIn(null, "tt1", 1))
        world.locations.setDefaultId(drive.id)

        enqueue("tt1", episode = 2)
        enqueue("tt5", episode = 1)
        enqueueMovie("tt7")

        val byId = items().associateBy { it.videoId }
        assertEquals(DownloadLocations.AppId, byId.getValue("tt1:1:2").locationId)
        assertEquals(drive.id, byId.getValue("tt5:1:1").locationId)
        assertEquals(drive.id, byId.getValue("tt7").locationId)
    }

    @Test
    fun a_show_whose_location_is_away_is_reported_so_that_nothing_is_queued_elsewhere() {
        val drive = world.driveLocation("Archive")
        load(episodeIn(drive, "tt1", 1))

        assertFalse(DownloadsRepository.showLocationIsAway("tt1"))
        world.unplug("Archive")

        assertTrue(DownloadsRepository.showLocationIsAway("tt1"))
        assertFalse(DownloadsRepository.showLocationIsAway("tt-other"), "a show somewhere else is not affected")
    }

    @Test
    fun a_download_waiting_for_its_drive_can_be_given_another_location_and_then_starts() {
        val drive = world.driveLocation("Archive")
        val waiting = episodeItem("tt1", 3, status = DownloadStatus.Paused, locationId = drive.id).copy(pausedByFolder = true)
        load(waiting)

        DownloadsRepository.chooseLocationForWaiting(waiting.id, DownloadLocations.AppId)

        val now = items().single()
        assertEquals(DownloadLocations.AppId, now.locationId)
        assertEquals(DownloadStatus.Downloading, now.status)
        assertFalse(now.pausedByFolder)
        assertEquals(listOf(waiting.id), backend.started)
    }

    @Test
    fun a_download_that_waited_for_its_drive_resumes_by_itself_when_it_is_back() {
        val drive = world.driveLocation("Archive")
        val waiting = episodeItem("tt1", 3, status = DownloadStatus.Paused, locationId = drive.id).copy(pausedByFolder = true)
        load(waiting)
        world.unplug("Archive")

        DownloadsRepository.resumeHeldByFolder()
        assertEquals(DownloadStatus.Paused, items().single().status, "it started with the drive away")

        world.replug("Archive")
        DownloadsRepository.resumeHeldByFolder()

        assertEquals(DownloadStatus.Downloading, items().single().status)
    }

    @Test
    fun deleting_a_partial_download_on_an_unplugged_drive_keeps_its_record_and_its_partial_file() {
        val drive = world.driveLocation("Archive")
        val partial = episodeItem("tt1", 1, status = DownloadStatus.Paused, locationId = drive.id)
        val file = drive.path.resolve(partial.fileName + ".part").also { java.nio.file.Files.write(it, ByteArray(10)) }
        load(partial)
        world.unplug("Archive")

        assertEquals(DownloadDeleteResult.NotReachable, DownloadsRepository.deleteDownload(partial.id))
        DownloadsRepository.cancelDownload(partial.id)

        assertEquals(1, items().size)
        world.replug("Archive")
        assertTrue(file.toFile().exists())
    }

    @Test
    fun clearing_failed_downloads_leaves_the_ones_on_an_unplugged_drive() {
        val drive = world.driveLocation("Archive")
        val onDrive = episodeItem("tt1", 1, status = DownloadStatus.Failed, locationId = drive.id)
        val inApp = episodeItem("tt2", 1, status = DownloadStatus.Failed)
        load(onDrive, inApp)
        world.unplug("Archive")

        assertEquals(1, DownloadsRepository.clearFailed())

        assertEquals(listOf(onDrive.id), items().map { it.id })
    }

    @Test
    fun a_delete_a_removal_after_watching_and_a_file_check_do_nothing_while_a_move_has_the_item() {
        val rule = autoDownloadRuleKey("series", "tt1")
        val item = episodeIn(null, "tt1", 1, ruleKey = rule)
        load(item)
        assertTrue(ItemLocks.tryAcquire(item.id))
        try {
            assertEquals(DownloadDeleteResult.Moving, DownloadsRepository.deleteDownload(item.id))
            assertEquals(AutoDownloadRemovalResult.Busy, DownloadsRepository.removeWatchedAutoDownload(item.id, rule))
            // A file in the middle of changing places is not a lost file, and a busy check is not a pass.
            java.nio.file.Files.delete(java.nio.file.Paths.get(java.net.URI(item.localFileUri!!)))
            assertFalse(DownloadsRepository.verifyCompletedDownload(item.id))
            DownloadsRepository.cancelDownload(item.id)
        } finally {
            ItemLocks.release(item.id)
        }

        assertEquals(1, items().size)
        assertEquals(DownloadStatus.Completed, items().single().status)
        assertTrue(backend.trashed.isEmpty())
    }

    @Test
    fun a_delete_removes_only_the_partial_file_in_the_items_own_location() {
        val drive = world.driveLocation("Archive")
        val item = episodeItem("tt1", 1, status = DownloadStatus.Paused, locationId = null)
        val elsewhere = drive.path.resolve(item.fileName + ".part").also { java.nio.file.Files.write(it, ByteArray(3)) }
        val partials = mutableListOf<String>()
        val recording = object : DownloadsBackend by backend {
            override fun removePartialOf(item: DownloadItem): Boolean {
                partials += item.fileName
                return backend.removePartialOf(item)
            }
        }
        DownloadsRepository.backend = recording
        backend.payload = storedPayload(listOf(item))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        DownloadsRepository.deleteDownload(item.id)

        assertEquals(listOf(item.fileName), partials)
        assertTrue(elsewhere.toFile().exists(), "a partial file of the same name in another location was removed")
    }

    @Test
    fun file_checks_cancel_and_clear_failed_acquire_the_item_lock_instead_of_only_looking_at_it() {
        val seen = mutableListOf<String>()
        val watching = object : DownloadsBackend by backend {
            override fun locate(item: DownloadItem): FileLookup {
                if (ItemLocks.isHeld(item.id)) seen += "locate"
                return backend.locate(item)
            }
            override fun removePartialOf(item: DownloadItem): Boolean {
                if (ItemLocks.isHeld(item.id)) seen += "partial"
                return backend.removePartialOf(item)
            }
        }
        DownloadsRepository.backend = watching
        val done = episodeIn(null, "tt1", 1)
        val failed = episodeItem("tt2", 1, status = DownloadStatus.Failed)
        backend.payload = storedPayload(listOf(done, failed))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        seen.clear()

        DownloadsRepository.verifyCompletedDownload(done.id)
        DownloadsRepository.clearFailed()
        DownloadsRepository.cancelDownload(done.id)

        assertTrue("locate" in seen, "the file check did not hold the lock")
        assertTrue("partial" in seen, "clear or cancel did not hold the lock")
        assertFalse(ItemLocks.isHeld(done.id) || ItemLocks.isHeld(failed.id), "a lock was kept")
    }

    @Test
    fun the_list_stays_readable_by_a_build_without_locations() {
        val drive = world.driveLocation("Archive")
        val item = episodeIn(drive, "tt1", 1)
        load(item)
        // Any change saves the list again, as this build writes it.
        DownloadsRepository.setPreferredAudioLanguage(item.id, "en")

        // An older build knows neither field. It reads the record by the fields it has, keeping the address.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val root = json.parseToJsonElement(backend.payload!!) as kotlinx.serialization.json.JsonObject
        val record = (root.getValue("items") as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
        val oldFields = record.filterKeys { it != "locationId" && it != "pausedByFolder" }
        val readByOldBuild = json.decodeFromJsonElement(DownloadItem.serializer(), kotlinx.serialization.json.JsonObject(oldFields))

        assertEquals(DownloadLocations.fileOfUri(item.localFileUri!!), DownloadLocations.fileOfUri(readByOldBuild.localFileUri!!))
        assertEquals(DownloadStatus.Completed, readByOldBuild.status)
        assertTrue(record.containsKey("locationId"))
    }

    private fun enqueue(show: String, episode: Int) {
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "$show:1:$episode", parentMetaId = show, parentMetaType = "series",
            title = "Show $show", logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = episode,
            episodeTitle = null, episodeThumbnail = null, stream = testStream("https://example.com/$show/$episode.mp4"),
        )
    }

    private fun enqueueMovie(id: String) {
        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = id, parentMetaId = id, parentMetaType = "movie",
            title = "Film $id", logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null,
            episodeTitle = null, episodeThumbnail = null, stream = testStream("https://example.com/$id.mp4"),
        )
    }
}
