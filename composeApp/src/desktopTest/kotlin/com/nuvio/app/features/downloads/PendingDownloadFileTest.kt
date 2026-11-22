package com.nuvio.app.features.downloads

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PendingDownloadFileTest {
    private val world = FolderWorld()
    private val location = world.driveLocation("Drive")
    private val file = location.path.resolve("episode.mkv")
    private val uri get() = file.toFile().toURI().toString()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val checks = AtomicInteger()
    private val starts = AtomicInteger()
    private val partials = AtomicInteger()
    private val outright = AtomicInteger()
    private val removals = AtomicInteger()
    private val ownershipChecks = AtomicInteger()
    private val syncs = AtomicInteger()
    private var ownership: FileOwnership? = null
    private var syncSucceeds = true
    private var onTrash: () -> Unit = {}
    private val listLock = java.util.concurrent.locks.ReentrantLock()
    private var blockCheck = false
    private var payload: String? = null
    private var savedFileKey: String? = null
    private val savedBackend = DownloadsRepository.backend
    private val savedRuntime = downloadRuntimeLookup
    private val savedCompanions = DownloadsRepository.companions
    private val backend = object : DownloadsBackend {
        override fun <T> withListLock(block: () -> T): T = listLock.withLock { block() }
        override fun loadPayload() = payload
        override fun savePayload(payload: String) { this@PendingDownloadFileTest.payload = payload }
        override fun restoreItem(item: DownloadItem) = item
        override fun syncPayload(): Boolean { syncs.incrementAndGet(); return syncSucceeds }
        override fun ownershipOf(item: DownloadItem, fileUri: String): FileOwnership? {
            ownershipChecks.incrementAndGet(); return ownership
        }
        override fun start(request: DownloadPlatformRequest, onProgress: (Long, Long?) -> Unit,
            onSuccess: (String, Long?) -> Unit, onFailure: (String) -> Unit, onPaused: () -> Unit): DownloadsTaskHandle {
            starts.incrementAndGet()
            return object : DownloadsTaskHandle { override fun cancel() = Unit }
        }
        override fun removeFile(localFileUri: String?): Boolean { outright.incrementAndGet(); return false }
        override fun removePartialFile(destinationFileName: String): Boolean { partials.incrementAndGet(); return true }
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String) =
            (world.locations.locate(null, localFileUri, destinationFileName) as? FileLookup.Found)?.uri
        override fun locate(item: DownloadItem) = world.locations.locate(item.locationId, item.localFileUri, item.fileName)
        override fun isConnected(item: DownloadItem) =
            world.locations.availability(world.locations.locationOfRecord(item.locationId, item.localFileUri)) == FolderAvailability.Available
        override fun heldByFolder(item: DownloadItem) = ItemLocks.isHeld(item.id) || !isConnected(item)
        override fun inspectFile(localFileUri: String) = inspectDownloadedFile(localFileUri)?.let {
            if (blockCheck) it.copy(durationMs = null) else it
        }
        override fun sampleFrames(localFileUri: String): FrameSamples? {
            checks.incrementAndGet(); entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            return null
        }
        override fun trashFile(localFileUri: String) = world.trash.send(Path.of(java.net.URI(localFileUri)))
        override fun removeJustPublished(localFileUri: String, fileKey: String?): Boolean = try {
            DownloadsPlatformDownloader.removeJustPublished(localFileUri, fileKey) { path -> onTrash(); world.trash.send(path) }
        } finally { removals.incrementAndGet() }
    }

    @BeforeTest fun setup() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        downloadRuntimeLookup = { 45 }
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
        RandomAccessFile(file.toFile(), "rw").use { it.write(matroskaEpisode()); it.setLength(300_000_000L) }
    }

    @AfterTest fun cleanup() {
        release.countDown()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = savedBackend
        downloadRuntimeLookup = savedRuntime
        DownloadsRepository.companions = savedCompanions
        world.close()
    }

    private fun item() = DownloadsRepository.uiState.value.items.single()
    private fun pending(status: DownloadStatus = DownloadStatus.Paused) =
        episodeItem("show", 1, status, location.id, file, 300_000_000L).copy(fileName = file.fileName.toString(), awaitingCheck = true)
    private fun load(item: DownloadItem = pending(), key: String? = DownloadMover.identityOf(file)) {
        savedFileKey = key
        val fields = Json.parseToJsonElement(Json.encodeToString(DownloadItem.serializer(), item)).jsonObject - "publishedFileKey"
        val record = JsonObject(if (key == null) fields else fields + ("publishedFileKey" to JsonPrimitive(key)))
        payload = JsonObject(mapOf("items" to JsonArray(listOf(record)))).toString()
        DownloadsRepository.ensureLoaded()
    }
    private fun await(block: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!block() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(block(), "the saved state did not settle")
        DownloadsRepository.holdStateLockForTest {}
    }
    private fun assertArchived() {
        assertFalse(Files.exists(file))
        val archived = world.trash.sent.single().second
        assertEquals(file, world.trash.sent.single().first)
        assertEquals(savedFileKey, DownloadMover.identityOf(archived))
        assertEquals(300_000_000L, Files.size(archived))
        assertEquals(0, starts.get())
        assertEquals(0, outright.get())
        assertEquals(0, partials.get())
    }

    @Test fun deleting_a_paused_pending_file_moves_the_final_file_to_trash() {
        load()
        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item().id))
        assertArchived()
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
    }

    @Test fun deleting_a_file_during_its_check_does_not_leave_a_copy_or_resurrect_its_row() {
        blockCheck = true; load(pending(DownloadStatus.Downloading))
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item().id))
        release.countDown()
        assertArchived()
        assertTrue(DownloadsRepository.uiState.value.items.isEmpty())
        assertTrue(DownloadsRepository.uiState.value.checkingIds.isEmpty())
    }

    @Test fun a_refused_delete_keeps_the_pending_record_and_final_file() {
        load(); world.trash.fails = true
        assertEquals(DownloadDeleteResult.NotMoved, DownloadsRepository.deleteDownload(item().id))
        assertEquals(uri, item().localFileUri)
        assertTrue(item().awaitingCheck)
        assertEquals(uri, DownloadsCodec.decode(payload!!)!!.items.single().localFileUri)
        assertTrue(Files.exists(file))
        assertEquals(0, partials.get())
    }

    @Test fun deleting_a_pending_file_on_an_unavailable_drive_keeps_the_record() {
        world.unplug("Drive"); load()
        assertEquals(DownloadDeleteResult.NotReachable, DownloadsRepository.deleteDownload(item().id))
        assertEquals(uri, item().localFileUri)
        assertTrue(item().awaitingCheck)
        assertTrue(world.trash.sent.isEmpty())
    }

    @Test fun choosing_another_location_cannot_detach_a_published_file_from_its_drive() {
        val target = world.driveLocation("Other")
        world.unplug("Drive")
        load(pending().copy(pausedByFolder = true))
        val before = item()
        DownloadsRepository.chooseLocationForWaiting(before.id, target.id)
        assertEquals(before, item())
        assertEquals(before, DownloadsCodec.decode(payload!!)!!.items.single())
        assertEquals(0, starts.get())
        assertEquals(0, partials.get())
        world.replug("Drive")
        DownloadsRepository.resumeHeldByFolder()
        await { item().status == DownloadStatus.Completed }
        assertEquals(location.id, item().locationId)
        assertEquals(uri, item().localFileUri)
        assertEquals(0, starts.get())
    }

    @Test fun a_rejecting_check_after_restart_can_trash_the_recorded_publication() {
        RandomAccessFile(file.toFile(), "rw").use { it.write("not a video".toByteArray()) }
        load(pending(DownloadStatus.Downloading))
        await { item().status == DownloadStatus.Failed && item().localFileUri == null && !Files.exists(file) && !ItemLocks.isHeld(item().id) }
        assertArchived()
        assertEquals(null, item().localFileUri)
        assertFalse(item().awaitingCheck)
    }

    @Test fun a_replaced_file_is_kept_with_its_record_when_the_old_identity_does_not_match() {
        val key = assertNotNull(DownloadMover.identityOf(file))
        Files.move(file, world.root.resolve("original.mkv"))
        Files.writeString(file, "another file")
        load(pending(DownloadStatus.Downloading), key)
        await { item().status == DownloadStatus.Failed && removals.get() >= 1 && !ItemLocks.isHeld(item().id) }
        assertEquals(uri, item().localFileUri)
        assertTrue(item().awaitingCheck)
        assertTrue(world.trash.sent.isEmpty())
        assertEquals("another file", Files.readString(file))
        DownloadsRepository.retryDownload(item().id)
        await { item().status == DownloadStatus.Failed && removals.get() == 2 && !ItemLocks.isHeld(item().id) }
        assertEquals(uri, item().localFileUri)
        assertEquals(0, starts.get())
    }

    @Test fun a_refused_rejection_move_keeps_the_file_address_and_does_not_start_another_transfer() {
        RandomAccessFile(file.toFile(), "rw").use { it.write("not a video".toByteArray()) }
        world.trash.fails = true
        load(pending(DownloadStatus.Downloading))
        await { item().status == DownloadStatus.Failed && removals.get() >= 1 && !ItemLocks.isHeld(item().id) }
        assertEquals(uri, item().localFileUri)
        assertTrue(item().awaitingCheck)
        assertEquals(uri, DownloadsCodec.decode(payload!!)!!.items.single().localFileUri)
        assertTrue(Files.exists(file))
        assertEquals(0, starts.get())
        assertEquals(0, outright.get())
    }

    @Test fun a_refused_delete_during_a_check_leaves_a_paused_resumable_record() {
        blockCheck = true; load(pending(DownloadStatus.Downloading))
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        world.trash.fails = true
        assertEquals(DownloadDeleteResult.NotMoved, DownloadsRepository.deleteDownload(item().id))
        release.countDown()
        assertEquals(DownloadStatus.Paused, item().status)
        assertEquals(uri, item().localFileUri)
        assertTrue(item().awaitingCheck)
        assertTrue(Files.exists(file))
        assertEquals(0, starts.get())
        assertEquals(0, partials.get())
    }

    @Test fun a_pending_file_uses_trash_even_when_direct_removal_was_requested() {
        load()
        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item().id, permanently = true))
        assertArchived()
    }

    @Test fun a_legacy_pending_file_without_identity_keeps_its_address_if_rejected() {
        RandomAccessFile(file.toFile(), "rw").use { it.write("not a video".toByteArray()) }
        load(pending(DownloadStatus.Downloading), key = null)
        await { item().status == DownloadStatus.Failed && removals.get() == 1 && !ItemLocks.isHeld(item().id) }
        assertEquals(uri, item().localFileUri)
        assertTrue(item().awaitingCheck)
        assertTrue(world.trash.sent.isEmpty())
        assertTrue(Files.exists(file))
        assertEquals(0, starts.get())
    }

    @Test fun rejection_keeps_shared_files_and_files_whose_ownership_is_unknown() {
        RandomAccessFile(file.toFile(), "rw").use { it.write("not a video".toByteArray()) }
        for (owner in listOf(FileOwnership.Owned, FileOwnership.Unknown)) {
            ownership = owner; ownershipChecks.set(0)
            load(pending(DownloadStatus.Downloading))
            await { item().status == DownloadStatus.Failed && ownershipChecks.get() > 0 && !ItemLocks.isHeld(item().id) }
            assertEquals(uri, item().localFileUri)
            assertTrue(item().awaitingCheck)
            assertTrue(Files.exists(file))
            assertEquals(0, removals.get())
            DownloadsRepository.clearLocalState()
        }
        assertTrue(world.trash.sent.isEmpty())
    }

    @Test fun an_unconfirmed_save_never_trashes_the_published_file() {
        RandomAccessFile(file.toFile(), "rw").use { it.write("not a video".toByteArray()) }
        syncSucceeds = false
        load(pending(DownloadStatus.Downloading))
        await { item().status == DownloadStatus.Failed && syncs.get() > 0 }
        assertEquals(uri, item().localFileUri)
        assertEquals(0, removals.get())
        assertTrue(Files.exists(file))
        assertEquals(300_000_000L, item().downloadedBytes)
    }

    @Test fun clearing_failures_and_choosing_another_source_keep_a_file_that_trash_refused() {
        RandomAccessFile(file.toFile(), "rw").use { it.write("not a video".toByteArray()) }
        world.trash.fails = true
        load(pending(DownloadStatus.Downloading))
        await { item().status == DownloadStatus.Failed && removals.get() == 1 && !ItemLocks.isHeld(item().id) }
        val retained = item()
        assertEquals(0, DownloadsRepository.clearFailed())
        DownloadsRepository.enqueueFromStream(
            contentType = "series", videoId = "show:1:1", parentMetaId = "show", parentMetaType = "series", title = "Show",
            logo = null, poster = null, background = null, seasonNumber = 1, episodeNumber = 1,
            episodeTitle = null, episodeThumbnail = null,
            stream = com.nuvio.app.features.streams.StreamItem(name = "1080p", url = "https://example.com/other.mkv", addonName = "test", addonId = "test"),
            userChoseReplacement = true,
        )
        assertEquals(2, DownloadsRepository.uiState.value.items.size)
        assertEquals(retained, DownloadsRepository.uiState.value.items.single { it.id == retained.id })
        assertEquals(1, starts.get())
        assertTrue(Files.exists(file))
        assertEquals(0, partials.get())
        assertEquals(0, outright.get())
    }


    @Test fun another_download_can_save_its_pause_while_a_rejected_file_goes_to_trash() {
        RandomAccessFile(file.toFile(), "rw").use { it.write("not a video".toByteArray()) }
        load()
        val pendingId = item().id
        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = "other", parentMetaId = "other", parentMetaType = "movie", title = "Other",
            logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null,
            episodeTitle = null, episodeThumbnail = null,
            stream = com.nuvio.app.features.streams.StreamItem(name = "1080p", url = "https://example.com/other.mkv", addonName = "test", addonId = "test"),
        )
        val other = DownloadsRepository.uiState.value.items.single { it.id != pendingId }
        val paused = CountDownLatch(1)
        onTrash = {
            val worker = thread(isDaemon = true) { DownloadsRepository.pauseDownload(other.id); paused.countDown() }
            assertTrue(paused.await(10, TimeUnit.SECONDS), "the pause could not be saved while Trash waited")
            worker.join(10_000)
        }
        DownloadsRepository.resumeDownload(pendingId)
        await {
            DownloadsRepository.uiState.value.items.single { it.id == pendingId }.let {
                it.status == DownloadStatus.Failed && !it.awaitingCheck && !ItemLocks.isHeld(it.id)
            }
        }
        assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.single { it.id == other.id }.status)
        assertEquals(1, world.trash.sent.size)
        assertFalse(Files.exists(file))
    }


    @Test fun a_pending_record_claims_its_actual_published_name_and_sidecar_in_every_profile() {
        val published = file.resolveSibling("episode (2).mkv")
        Files.move(file, published)
        for (status in listOf(DownloadStatus.Downloading, DownloadStatus.Paused, DownloadStatus.Failed)) {
            val record = pending(status).copy(localFileUri = published.toFile().toURI().toString())
            val owners = DownloadOwnership(world.locations, listOf(RecordRef.of(1, record)))
            assertEquals(record.id, owners.otherOwner(2, "other", published)?.id)
            val sidecar = published.resolveSibling(DownloadSidecarFiles.sidecarFolderName(published.fileName.toString()))
            assertEquals(record.id, owners.otherOwner(2, "other", sidecar)?.id)
        }
    }


    @Test fun a_paused_pending_file_resolves_its_drive_after_the_mount_name_changes() {
        load()
        val id = item().id
        val mount = world.volumes.resolve("Drive returned")
        Files.move(world.volumes.resolve("Drive"), mount)
        assertNotNull(world.locations.rediscover(location))
        val returned = mount.resolve("Movies").resolve(file.fileName)
        DownloadsRepository.resumeDownload(id)
        await { item().status == DownloadStatus.Completed }
        assertEquals(returned.toFile().toURI().toString(), item().localFileUri)
        assertEquals(location.id, item().locationId)
        assertEquals(300_000_000L, Files.size(returned))
        assertEquals(0, starts.get())
        assertTrue(world.trash.sent.isEmpty())
    }

    @Test fun loading_a_pending_file_resolves_its_current_mount_before_checking() {
        val record = pending(DownloadStatus.Downloading)
        val key = DownloadMover.identityOf(file)
        val mount = world.volumes.resolve("Drive returned")
        Files.move(world.volumes.resolve("Drive"), mount)
        assertNotNull(world.locations.rediscover(location))
        val returned = mount.resolve("Movies").resolve(file.fileName)
        load(record, key)
        await { item().status == DownloadStatus.Completed }
        assertEquals(returned.toFile().toURI().toString(), item().localFileUri)
        assertEquals(location.id, item().locationId)
        assertEquals(300_000_000L, Files.size(returned))
        assertEquals(0, starts.get())
        assertTrue(world.trash.sent.isEmpty())
    }

}
