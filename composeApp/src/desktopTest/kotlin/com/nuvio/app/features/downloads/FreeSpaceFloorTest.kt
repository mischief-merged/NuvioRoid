package com.nuvio.app.features.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.FreeSpaceFloorSetting
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_delete_setting_save_failed
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
            loadFloorGb = { _ -> savedFloor },
            saveFloorGb = { _, value -> savedFloor = value },
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

    @Test
    fun floor_is_scoped_per_profile_in_desktop_storage_and_switching_profiles_applies_each_profiles_floor() {
        val originalProfile = ProfileRepository.activeProfileId
        val profileA = originalProfile + 31
        val profileB = originalProfile + 32
        val store = DesktopStorage.store("nuvio_limited_connections")
        val payloadsByProfile = mutableMapOf<Int, String?>()
        val profileBackend = object : DownloadsBackend by backend {
            override fun loadPayload(profileId: Int): String? = payloadsByProfile[profileId]
            override fun savePayload(profileId: Int, payload: String) {
                payloadsByProfile[profileId] = payload
            }
        }
        try {
            store.remove(ProfileScopedKey.of("free_space_floor_gb", profileA))
            store.remove(ProfileScopedKey.of("free_space_floor_gb", profileB))

            val storageBackedHold = FreeSpaceHold(
                loadFloorGb = LimitedConnectionStorage::loadFreeSpaceFloorGb,
                saveFloorGb = LimitedConnectionStorage::saveFreeSpaceFloorGb,
            )
            DownloadsRepository.backend = profileBackend
            DownloadsRepository.freeSpaceHold = storageBackedHold
            storageBackedHold.reportLocationFreeBytes(mapOf("app" to 15L * BytesPerGigabyte))

            ProfileRepository.setActiveProfileIdForTest(profileA)
            DownloadsRepository.onProfileChanged()
            assertTrue(storageBackedHold.setFloorGb(value = 20, profileId = profileA))
            assertEquals(20, LimitedConnectionStorage.loadFreeSpaceFloorGb(profileA))
            assertNull(LimitedConnectionStorage.loadFreeSpaceFloorGb(profileB))

            enqueueMovie("profile-a-movie", locationId = "app")
            assertEquals(DownloadStatus.Paused, itemOf("profile-a-movie").status)
            assertTrue(itemOf("profile-a-movie").pausedByFreeSpace)
            assertTrue(backend.startedIds.isEmpty())

            // Switch to profile B (floor is Off), including an early UI floor refresh before catalog reload:
            // Profile A's held download must NEVER start or be overwritten as Downloading in Profile A's stored catalog!
            ProfileRepository.setActiveProfileIdForTest(profileB)
            storageBackedHold.onProfileChanged()
            assertTrue(backend.startedIds.isEmpty(), "early floor refresh on profile switch must not resume outgoing profile's downloads")
            DownloadsRepository.onProfileChanged()
            assertNull(storageBackedHold.floorBytes)
            assertTrue(backend.startedIds.isEmpty(), "switching to an Off-floor profile must never start outgoing profile's held downloads")
            val storedProfileA = DownloadsCodec.decode(assertNotNull(payloadsByProfile[profileA]))!!.items.single()
            assertEquals(DownloadStatus.Paused, storedProfileA.status)
            assertTrue(storedProfileA.pausedByFreeSpace)

            enqueueMovie("profile-b-movie", locationId = "app")
            assertEquals(DownloadStatus.Downloading, itemOf("profile-b-movie").status)
            assertFalse(itemOf("profile-b-movie").pausedByFreeSpace)
            assertEquals(listOf(itemOf("profile-b-movie").id), backend.startedIds)

            // Switch back to profile A -> 20 GB floor reloads automatically!
            ProfileRepository.setActiveProfileIdForTest(profileA)
            DownloadsRepository.onProfileChanged()
            assertEquals(20L * BytesPerGigabyte, storageBackedHold.floorBytes)
            assertEquals(DownloadStatus.Paused, itemOf("profile-a-movie").status)
            assertTrue(itemOf("profile-a-movie").pausedByFreeSpace)
        } finally {
            store.remove(ProfileScopedKey.of("free_space_floor_gb", profileA))
            store.remove(ProfileScopedKey.of("free_space_floor_gb", profileB))
            ProfileRepository.setActiveProfileIdForTest(originalProfile)
            DownloadsRepository.onProfileChanged()
        }
    }

    @Test
    fun resume_requires_free_space_minus_remaining_bytes_at_least_floor_preventing_restart_thrash() {
        hold.setFloorGb(10)
        hold.reportLocationFreeBytes(mapOf("app" to 12L * BytesPerGigabyte))

        // Start a download with 1 GB already on disk; server announces 4 GB total (3 GB remaining -> 12 - 3 = 9 < 10 GB floor).
        val partialItem = testDownload(1, DownloadStatus.Downloading, id = "dl-4gb").copy(
            locationId = "app",
            downloadedBytes = 1L * BytesPerGigabyte,
            createdAtEpochMs = 1L,
        )
        backend.payload = DownloadsCodec.encode(listOf(partialItem), null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        assertEquals(listOf("dl-4gb"), backend.startedIds)

        val guard = assertNotNull(backend.requestLimits["dl-4gb"])
        val stop = guard.stopBeforeBody(
            downloadedBytes = 1L * BytesPerGigabyte,
            totalBytes = 4L * BytesPerGigabyte,
            freeBytes = 12L * BytesPerGigabyte,
        )
        assertEquals(AutoDownloadStop.LowDiskSpace, stop)
        assertTrue(guard.lastStoppedByUserFreeSpaceFloor)
        guard.onStopped(AutoDownloadStop.LowDiskSpace)

        val held = DownloadsRepository.uiState.value.items.single()
        assertEquals(DownloadStatus.Paused, held.status)
        assertTrue(held.pausedByFreeSpace)
        assertEquals(4L * BytesPerGigabyte, held.waitingSizeBytes)

        // Small free-space fluctuations above 10 GB but below 13 GB (10 GB floor + 3 GB remaining) must NOT restart!
        hold.reportLocationFreeBytes(mapOf("app" to 12_100_000_000L))
        hold.reportLocationFreeBytes(mapOf("app" to 12_900_000_000L))
        assertEquals(1, backend.startedIds.size, "must not restart HTTP transfer when remaining bytes would still cross floor")
        assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.single().status)

        // Once 13 GB is free (13 - 3 >= 10), the download resumes cleanly!
        hold.reportLocationFreeBytes(mapOf("app" to 13L * BytesPerGigabyte))
        assertEquals(2, backend.startedIds.size)
        assertEquals(DownloadStatus.Downloading, DownloadsRepository.uiState.value.items.single().status)
        assertFalse(DownloadsRepository.uiState.value.items.single().pausedByFreeSpace)
    }

    @Test
    fun automatic_ten_gigabyte_reserve_does_not_set_paused_by_free_space_or_thrash_with_five_gigabyte_floor() {
        hold.setFloorGb(5)
        hold.reportLocationFreeBytes(mapOf("app" to 8L * BytesPerGigabyte))

        // Queue a 1 GB automatic download with 8 GB free: 8 - 1 = 7 >= 5 GB user floor, but 7 < 10 GB automatic reserve!
        DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "tt-auto:1:1",
            parentMetaId = "tt-auto",
            parentMetaType = "series",
            title = "Auto Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Ep 1",
            episodeThumbnail = null,
            stream = StreamItem(
                name = "1080p",
                url = "https://example.com/auto.mkv",
                addonName = "A",
                addonId = "addon:A",
                behaviorHints = StreamBehaviorHints(videoSize = 1L * BytesPerGigabyte),
            ),
            autoDownloadRuleKey = "series:tt-auto",
        )
        val autoItem = itemOf("tt-auto")
        val guard = assertNotNull(backend.requestLimits[autoItem.id])
        val stop = guard.stopBeforeBody(
            downloadedBytes = 0L,
            totalBytes = 1L * BytesPerGigabyte,
            freeBytes = 8L * BytesPerGigabyte,
        )
        assertEquals(AutoDownloadStop.LowDiskSpace, stop)
        assertFalse(guard.lastStoppedByUserFreeSpaceFloor, "10 GB automatic reserve stop must not claim user floor")
        guard.onStopped(AutoDownloadStop.LowDiskSpace)

        val stoppedAuto = itemOf("tt-auto")
        assertEquals(DownloadStatus.Paused, stoppedAuto.status)
        assertFalse(stoppedAuto.pausedByFreeSpace, "automatic reserve stop must not set pausedByFreeSpace")
        assertEquals(AutoDownloadStop.LowDiskSpace, stoppedAuto.autoDownloadStop)
        assertTrue(downloadRowStatus(stoppedAuto, nowEpochMs = 0L).phase != DownloadPhase.WaitingForFreeSpace)

        // Subsequent free-space updates between 5 and 10 GB must NOT restart the automatic download!
        val startsBefore = backend.startedIds.size
        hold.reportLocationFreeBytes(mapOf("app" to 8_200_000_000L))
        hold.reportLocationFreeBytes(mapOf("app" to 8_800_000_000L))
        assertEquals(startsBefore, backend.startedIds.size)
        assertEquals(DownloadStatus.Paused, itemOf("tt-auto").status)
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
        val storedByProfile = mutableMapOf<Int, Int?>()
        val hold = FreeSpaceHold(
            loadFloorGb = { profile -> storedByProfile[profile] },
            saveFloorGb = { profile, value -> storedByProfile[profile] = value },
            activeProfileId = { 4 },
        )

        compose.setContent {
            NuvioTheme {
                FreeSpaceFloorSetting(isTablet = false, hold = hold, profileId = 4)
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
        assertEquals(20, storedByProfile[4])
        assertNull(storedByProfile[5])
        assertEquals(20L * BytesPerGigabyte, hold.floorBytes)
        compose.onNodeWithText(text(Res.string.settings_free_space_floor_value, 20)).assertIsDisplayed()

        compose.onNodeWithText(text(Res.string.settings_free_space_floor_title)).performClick()
        compose.onNode(hasText(text(Res.string.settings_free_space_floor_off)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()

        val latch2 = CountDownLatch(1)
        ConnectionUpdates.submit { latch2.countDown() }
        assertTrue(latch2.await(5, TimeUnit.SECONDS))
        assertNull(storedByProfile[4])
        assertNull(hold.floorBytes)
    }

    @Test
    fun failed_floor_save_preserves_saved_value_in_ui_and_storage_and_reports_failure() {
        val tempRoot = java.nio.file.Files.createTempDirectory("nuvio-floor-save-fail").toRealPath()
        val storeDir = tempRoot.resolve("store").createDirectories()
        val cacheDir = tempRoot.resolve("cache").createDirectories()
        val profile = 7
        DesktopStorage.setRootDirForTest(storeDir, cacheDir)
        NuvioToastController.dismiss()
        try {
            val hold = FreeSpaceHold(
                loadFloorGb = LimitedConnectionStorage::loadFreeSpaceFloorGb,
                saveFloorGb = LimitedConnectionStorage::saveFreeSpaceFloorGb,
                activeProfileId = { profile },
            )
            assertTrue(hold.setFloorGb(value = 10, profileId = profile))
            assertEquals(10, LimitedConnectionStorage.loadFreeSpaceFloorGb(profile))

            compose.setContent {
                NuvioTheme {
                    FreeSpaceFloorSetting(isTablet = false, hold = hold, profileId = profile)
                }
            }
            compose.onNodeWithText(text(Res.string.settings_free_space_floor_value, 10)).assertIsDisplayed()

            // Block writes on the underlying store file so persist() throws a real IOException.
            val blockingTmpDir = storeDir.resolve("nuvio_limited_connections.properties.tmp").createDirectories()
            assertFalse(hold.setFloorGb(value = 50, profileId = profile))
            assertEquals(10L * BytesPerGigabyte, hold.floorBytes, "in-memory floor must remain at the saved value on write failure")
            assertEquals(10, LimitedConnectionStorage.loadFreeSpaceFloorGb(profile), "stored floor must remain intact on write failure")

            // Attempt selection through the UI dialog -> must keep 10 GB displayed and show failure toast.
            compose.onNodeWithText(text(Res.string.settings_free_space_floor_title)).performClick()
            compose.onNode(hasText(text(Res.string.settings_free_space_floor_value, 50)) and hasAnyAncestor(isDialog())).performClick()
            compose.waitForIdle()

            val latch = CountDownLatch(1)
            ConnectionUpdates.submit { latch.countDown() }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()

            compose.onNodeWithText(text(Res.string.settings_free_space_floor_value, 10)).assertIsDisplayed()
            assertEquals(10, LimitedConnectionStorage.loadFreeSpaceFloorGb(profile))
            assertEquals(
                text(Res.string.downloads_delete_setting_save_failed),
                NuvioToastController.currentToast.value?.message,
            )

            java.nio.file.Files.deleteIfExists(blockingTmpDir)
        } finally {
            NuvioToastController.dismiss()
            DesktopStorage.setRootDirForTest(null, null)
            tempRoot.toFile().deleteRecursively()
        }
    }
}
