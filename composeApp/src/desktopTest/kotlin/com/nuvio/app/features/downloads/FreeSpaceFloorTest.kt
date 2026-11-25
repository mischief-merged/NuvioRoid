package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.settings.FreeSpaceFloorSetting
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_free_space_floor_off
import nuvio.composeapp.generated.resources.settings_free_space_floor_title
import nuvio.composeapp.generated.resources.settings_free_space_floor_value
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreeSpaceFloorTest {
    private inner class FakeBackend : DownloadsBackend {
        var payload: String? = null
        var removedPartials = 0
        val startedIds = mutableListOf<String>()
        val cancelledIds = mutableListOf<String>()
        val progressCallbacks = mutableMapOf<String, (Long, Long?) -> Unit>()
        val partialCallbacks = mutableMapOf<String, (String) -> Unit>()
        val requestLimits = mutableMapOf<String, DownloadTransferLimits?>()
        val disconnectedLocations = mutableSetOf<String>()
        var nextPlacementLocationId: String? = null

        override fun loadPayload(): String? = payload

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun isConnected(item: DownloadItem): Boolean =
            normalizeFloorLocationId(item.locationId) !in disconnectedLocations

        override fun locationConnected(locationId: String?): Boolean =
            normalizeFloorLocationId(locationId) !in disconnectedLocations

        override fun placementFor(items: List<DownloadItem>, parentMetaId: String, isEpisode: Boolean): String? =
            nextPlacementLocationId

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            val id = request.item.id
            startedIds += id
            progressCallbacks[id] = onProgress
            partialCallbacks[id] = request.onPartialCreated
            requestLimits[id] = request.limits
            return object : DownloadsTaskHandle {
                override fun cancel() {
                    cancelledIds += id
                }
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean {
            removedPartials++
            return true
        }

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private val backend = FakeBackend()
    private var savedFloor: Int? = null
    private lateinit var hold: FreeSpaceHold

    private val realBackend = DownloadsRepository.backend
    private val realLimits = DownloadsRepository.autoDownloadLimits
    private val realFreeSpaceHold = DownloadsRepository.freeSpaceHold
    private val realOnTrip = DownloadsRepository.onProfileReadyForTrip
    private val realOnBlocked = DownloadsRepository.onStorageLimitBlocked

    @BeforeTest
    fun set_up() {
        savedFloor = null
        hold = FreeSpaceHold(
            loadFloorGb = { savedFloor },
            saveFloorGb = { savedFloor = it },
        )
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.freeSpaceHold = hold
        DownloadsRepository.autoDownloadLimits = object : AutoDownloadLimits {
            override fun storageLimitBytes(): Long? = null
            override fun totalStorageLimitBytes(): Long? = null
            override val minFreeBytes: Long = 10L * BytesPerGigabyte
            override fun userFreeSpaceFloorBytes(): Long? = hold.floorBytes
        }
        DownloadsRepository.onProfileReadyForTrip = {}
        DownloadsRepository.onStorageLimitBlocked = {}
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun tear_down() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.freeSpaceHold = realFreeSpaceHold
        DownloadsRepository.autoDownloadLimits = realLimits
        DownloadsRepository.onProfileReadyForTrip = realOnTrip
        DownloadsRepository.onStorageLimitBlocked = realOnBlocked
    }

    private fun enqueueMovie(id: String, locationId: String?) {
        backend.nextPlacementLocationId = locationId
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = id,
            parentMetaId = id,
            parentMetaType = "movie",
            title = "Movie $id",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = testStream("https://example.com/$id.mkv"),
        )
    }

    private fun itemOf(parentMetaId: String): DownloadItem =
        DownloadsRepository.uiState.value.items.first { it.parentMetaId == parentMetaId }

    @Test
    fun floor_holds_new_and_running_downloads_per_location_keeps_partials_and_resumes_when_space_returns() {
        hold.reportLocationFreeBytes(
            mapOf(
                "app" to 40L * BytesPerGigabyte,
                "drive-b" to 30L * BytesPerGigabyte,
            ),
        )
        hold.setFloorGb(20)

        enqueueMovie("on-app", locationId = "app")
        enqueueMovie("on-drive", locationId = "drive-b")
        val appItem = itemOf("on-app")
        val driveItem = itemOf("on-drive")
        assertEquals(DownloadStatus.Downloading, appItem.status)
        assertEquals(DownloadStatus.Downloading, driveItem.status)

        backend.partialCallbacks.getValue(driveItem.id).invoke("partial-key-drive")
        backend.progressCallbacks.getValue(driveItem.id).invoke(500_000_000L, 2_000_000_000L)

        // Drive B drops to 15 GB (< 20 GB floor) while App stays at 40 GB: only Drive B pauses!
        hold.reportLocationFreeBytes(
            mapOf(
                "app" to 40L * BytesPerGigabyte,
                "drive-b" to 15L * BytesPerGigabyte,
            ),
        )

        assertEquals(DownloadStatus.Downloading, itemOf("on-app").status, "location with enough free space must keep running")
        val heldDrive = itemOf("on-drive")
        assertEquals(DownloadStatus.Paused, heldDrive.status)
        assertTrue(heldDrive.pausedByFreeSpace)
        assertEquals("partial-key-drive", heldDrive.partialFileKey, "partial identity must be preserved")
        assertEquals(500_000_000L, heldDrive.downloadedBytes, "partial bytes must be preserved")
        assertEquals(0, backend.removedPartials, "partial file must never be discarded")
        assertEquals(DownloadPhase.WaitingForFreeSpace, downloadRowStatus(heldDrive, nowEpochMs = 0L).phase)

        // New download routed to Drive B waits immediately, while new download on App starts.
        val startsBeforeNew = backend.startedIds.size
        enqueueMovie("new-on-drive", locationId = "drive-b")
        assertEquals(startsBeforeNew, backend.startedIds.size)
        assertEquals(DownloadStatus.Paused, itemOf("new-on-drive").status)
        assertTrue(itemOf("new-on-drive").pausedByFreeSpace)

        enqueueMovie("new-on-app", locationId = "app")
        assertEquals(startsBeforeNew + 1, backend.startedIds.size)
        assertEquals(DownloadStatus.Downloading, itemOf("new-on-app").status)

        // Unplugging Drive B (freeBytes = null and location disconnected) must NOT falsely clear pausedByFreeSpace!
        backend.disconnectedLocations += "drive-b"
        hold.reportLocationFreeBytes(mapOf("app" to 40L * BytesPerGigabyte, "drive-b" to null))
        assertEquals(DownloadStatus.Paused, itemOf("on-drive").status)
        assertTrue(itemOf("on-drive").pausedByFreeSpace)
        assertEquals(startsBeforeNew + 1, backend.startedIds.size)

        // Replugging Drive B with 25 GB free (>= 20 GB floor) resumes both held downloads on Drive B!
        backend.disconnectedLocations.remove("drive-b")
        hold.reportLocationFreeBytes(
            mapOf(
                "app" to 40L * BytesPerGigabyte,
                "drive-b" to 25L * BytesPerGigabyte,
            ),
        )

        assertEquals(DownloadStatus.Downloading, itemOf("on-drive").status)
        assertFalse(itemOf("on-drive").pausedByFreeSpace)
        assertEquals("partial-key-drive", itemOf("on-drive").partialFileKey)
        assertEquals(DownloadStatus.Downloading, itemOf("new-on-drive").status)
        assertFalse(itemOf("new-on-drive").pausedByFreeSpace)
    }

    @Test
    fun manual_pause_keeps_download_paused_and_turning_floor_off_resumes_held_ones() {
        hold.setFloorGb(20)
        hold.reportLocationFreeBytes(mapOf("app" to 10L * BytesPerGigabyte))

        enqueueMovie("m1", locationId = "app")
        enqueueMovie("m2", locationId = "app")
        assertTrue(itemOf("m1").pausedByFreeSpace)
        assertTrue(itemOf("m2").pausedByFreeSpace)

        // User pauses m1 manually while waiting -> clears pausedByFreeSpace.
        DownloadsRepository.pauseDownload(itemOf("m1").id)
        assertFalse(itemOf("m1").pausedByFreeSpace)
        assertEquals(DownloadStatus.Paused, itemOf("m1").status)

        // Turning the floor Off resumes m2 while m1 stays paused by the user.
        hold.setFloorGb(null)
        assertEquals(DownloadStatus.Paused, itemOf("m1").status)
        assertEquals(DownloadStatus.Downloading, itemOf("m2").status)
        assertNull(savedFloor)
    }

    @Test
    fun desktop_download_folders_monitor_enforces_and_resumes_per_location_even_when_screen_is_closed() {
        val world = FolderWorld()
        try {
            world.appFolder.createDirectories()
            val vault = world.driveLocation("Vault")
            val vaultReal = vault.path.toRealPath()

            var appFree = 50L * BytesPerGigabyte
            var vaultFree = 30L * BytesPerGigabyte
            val localHold = FreeSpaceHold()
            localHold.setFloorGb(20)

            val folders = DesktopDownloadFolders(
                locations = world.locations,
                validator = world.validator,
                mover = world.mover(),
                hooks = RepositoryDownloadsHooks,
                freeSpaceOf = { file ->
                    val real: Path? = runCatching { file.toPath().toRealPath() }.getOrNull()
                    if (real != null && real.startsWith(vaultReal)) vaultFree else appFree
                },
                freeSpaceHold = localHold,
            )

            // Wire repository to use this real DesktopDownloadFolders for location Connected/Id lookups.
            val folderAwareBackend = object : DownloadsBackend by backend {
                override fun isConnected(item: DownloadItem): Boolean = folders.isConnected(item)
                override fun locationConnected(locationId: String?): Boolean = folders.locationConnected(locationId)
                override fun locationIdOf(item: DownloadItem): String = folders.locationIdOf(item)
                override fun heldByFolder(item: DownloadItem): Boolean = folders.heldByFolder(item)
            }
            DownloadsRepository.backend = folderAwareBackend
            DownloadsRepository.freeSpaceHold = localHold

            enqueueMovie("app-film", locationId = DownloadLocations.AppId)
            enqueueMovie("vault-film", locationId = vault.id)
            assertEquals(DownloadStatus.Downloading, itemOf("app-film").status)
            assertEquals(DownloadStatus.Downloading, itemOf("vault-film").status)

            // Initial refresh establishes baseline signature; usageWatched remains false (minimized / closed screen).
            folders.poll()
            awaitConnectionUpdates()

            // Vault drops below 20 GB floor -> background poll() via noteReachability() pauses vault-film!
            vaultFree = 12L * BytesPerGigabyte
            folders.poll()
            awaitConnectionUpdates()

            assertEquals(DownloadStatus.Downloading, itemOf("app-film").status)
            assertEquals(DownloadStatus.Paused, itemOf("vault-film").status)
            assertTrue(itemOf("vault-film").pausedByFreeSpace)

            // Vault recovers to 28 GB -> background poll() via noteReachability() resumes vault-film!
            vaultFree = 28L * BytesPerGigabyte
            folders.poll()
            awaitConnectionUpdates()

            assertEquals(DownloadStatus.Downloading, itemOf("vault-film").status)
            assertFalse(itemOf("vault-film").pausedByFreeSpace)
        } finally {
            world.close()
        }
    }

    private fun awaitConnectionUpdates() {
        val latch = CountDownLatch(1)
        ConnectionUpdates.submit { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }
}

@OptIn(ExperimentalTestApi::class)
class FreeSpaceFloorSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    @Test
    fun setting_defaults_to_off_and_saves_chosen_floor_and_turns_back_off() {
        var stored: Int? = null
        val hold = FreeSpaceHold(loadFloorGb = { stored }, saveFloorGb = { stored = it })

        compose.setContent {
            NuvioTheme {
                FreeSpaceFloorSetting(isTablet = false, hold = hold)
            }
        }

        compose.onNodeWithText(text(Res.string.settings_free_space_floor_title)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.settings_free_space_floor_off)).assertIsDisplayed()

        compose.onNodeWithText(text(Res.string.settings_free_space_floor_title)).performClick()
        compose.onNode(hasText(text(Res.string.settings_free_space_floor_value, 20)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()

        val latch1 = CountDownLatch(1)
        ConnectionUpdates.submit { latch1.countDown() }
        assertTrue(latch1.await(5, TimeUnit.SECONDS))
        assertEquals(20, stored)
        assertEquals(20L * BytesPerGigabyte, hold.floorBytes)
        compose.onNodeWithText(text(Res.string.settings_free_space_floor_value, 20)).assertIsDisplayed()

        compose.onNodeWithText(text(Res.string.settings_free_space_floor_title)).performClick()
        compose.onNode(hasText(text(Res.string.settings_free_space_floor_off)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()

        val latch2 = CountDownLatch(1)
        ConnectionUpdates.submit { latch2.countDown() }
        assertTrue(latch2.await(5, TimeUnit.SECONDS))
        assertNull(stored)
        assertNull(hold.floorBytes)
    }
}
