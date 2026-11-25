package com.nuvio.app.features.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.network.TrustedLocalServerTest
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.settings.TotalStorageLimitSetting
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_none
import nuvio.composeapp.generated.resources.downloads_auto_storage_limit_value
import nuvio.composeapp.generated.resources.downloads_error_not_enough_space
import nuvio.composeapp.generated.resources.downloads_storage_all
import nuvio.composeapp.generated.resources.downloads_storage_all_no_limit
import nuvio.composeapp.generated.resources.downloads_storage_auto
import nuvio.composeapp.generated.resources.downloads_storage_change_limit
import nuvio.composeapp.generated.resources.downloads_storage_change_total_limit
import nuvio.composeapp.generated.resources.downloads_total_storage_limit
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TotalDownloadStorageLimitTest {
    private class Transfer(
        val request: DownloadPlatformRequest,
        val onProgress: (Long, Long?) -> Unit,
        val onSuccess: (String, Long?) -> Unit,
        val onFailure: (String) -> Unit,
    )

    private inner class FakeBackend : DownloadsBackend {
        var payload: String? = null
        var removedPartials = 0
        val trashed = mutableListOf<String>()
        val disconnectedLocations = mutableSetOf<String>()
        var cancelledIds = mutableListOf<String>()

        override fun loadPayload(): String? = payload

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun isConnected(item: DownloadItem): Boolean = item.locationId !in disconnectedLocations

        override fun locationConnected(locationId: String?): Boolean = locationId !in disconnectedLocations

        override fun trashFile(localFileUri: String): Boolean {
            trashed += localFileUri
            return true
        }

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            transfers += Transfer(request, onProgress, onSuccess, onFailure)
            return object : DownloadsTaskHandle {
                override fun cancel() {
                    cancelledIds += request.item.id
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

    private class FakeLimits(
        var autoLimitBytes: Long? = null,
        var totalLimitBytes: Long? = null,
    ) : AutoDownloadLimits {
        override fun storageLimitBytes(): Long? = autoLimitBytes
        override fun totalStorageLimitBytes(): Long? = totalLimitBytes
        override val minFreeBytes: Long = 50
    }

    private val showId = "total-limit-test"
    private val ruleKey = autoDownloadRuleKey("series", showId)
    private val transfers = mutableListOf<Transfer>()
    private val backend = FakeBackend()
    private val limits = FakeLimits()
    private val realBackend = DownloadsRepository.backend
    private val realLimits = DownloadsRepository.autoDownloadLimits
    private val realOnProfileReadyForTrip = DownloadsRepository.onProfileReadyForTrip
    private val realOnBlocked = DownloadsRepository.onStorageLimitBlocked

    @BeforeTest
    fun set_up() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.autoDownloadLimits = limits
        DownloadsRepository.onProfileReadyForTrip = {}
        DownloadsRepository.onStorageLimitBlocked = {}
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun tear_down() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.autoDownloadLimits = realLimits
        DownloadsRepository.onProfileReadyForTrip = realOnProfileReadyForTrip
        DownloadsRepository.onStorageLimitBlocked = realOnBlocked
    }

    private fun enqueueEpisode(
        episode: Int,
        automatic: Boolean = false,
        claimedBytes: Long? = null,
    ) {
        val stream = StreamItem(
            name = "1080p",
            url = "https://example.com/$episode.mp4",
            addonName = "A",
            addonId = "addon:A",
            behaviorHints = StreamBehaviorHints(videoSize = claimedBytes),
        )
        DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "$showId:1:$episode",
            parentMetaId = showId,
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = episode,
            episodeTitle = "Ep $episode",
            episodeThumbnail = null,
            stream = stream,
            autoDownloadRuleKey = ruleKey.takeIf { automatic },
        )
    }

    private fun item(episode: Int) = DownloadsRepository.uiState.value.items.first { it.episodeNumber == episode }

    @Test
    fun combined_limit_counts_hand_started_and_automatic_downloads_together_and_ignores_unplugged_locations() {
        limits.totalLimitBytes = 1_000_000_000L

        enqueueEpisode(1, automatic = false)
        transfers.last().onSuccess("file:/downloads/1.mp4", 400_000_000L)

        enqueueEpisode(2, automatic = true)
        transfers.last().onSuccess("file:/downloads/2.mp4", 350_000_000L)

        // Put an item on an unplugged drive: it must not count against the limit.
        val awayMovie = DownloadItem(
            id = "away-1",
            contentType = "movie",
            parentMetaId = "tt-away",
            parentMetaType = "movie",
            videoId = "tt-away",
            title = "Away Film",
            streamTitle = "1080p",
            providerName = "A",
            sourceUrl = "https://example.com/away.mp4",
            localFileUri = "file:/away/away.mp4",
            fileName = "away.mp4",
            locationId = "drive-away",
            status = DownloadStatus.Completed,
            downloadedBytes = 9_000_000_000L,
            totalBytes = 9_000_000_000L,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        backend.disconnectedLocations += "drive-away"
        backend.payload = DownloadsCodec.encode(DownloadsRepository.uiState.value.items + awayMovie, null, 1L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        assertEquals(750_000_000L, DownloadsRepository.allDownloadBytes())

        val transfersBefore = transfers.size
        enqueueEpisode(3, automatic = false, claimedBytes = 300_000_000L)

        assertEquals(transfersBefore, transfers.size, "download that exceeds the combined limit started a transfer")
        val waiting = item(3)
        assertEquals(DownloadStatus.Paused, waiting.status)
        assertEquals(AutoDownloadStop.TotalStorageLimit, waiting.autoDownloadStop)
        assertEquals(300_000_000L, waiting.waitingSizeBytes)
        val rowStatus = downloadRowStatus(waiting, nowEpochMs = 0L, offline = false)
        assertEquals(DownloadPhase.WaitingStorageLimit, rowStatus.phase)
    }

    @Test
    fun waiting_downloads_start_automatically_in_order_when_room_appears_and_skip_larger_ones() {
        limits.totalLimitBytes = 1_000_000_000L

        enqueueEpisode(1, automatic = false)
        transfers.last().onSuccess("file:/downloads/1.mp4", 800_000_000L)

        // Ep 2 needs 600 MB, Ep 3 needs 350 MB: both wait while 800 MB is used.
        enqueueEpisode(2, automatic = false, claimedBytes = 600_000_000L)
        enqueueEpisode(3, automatic = true, claimedBytes = 350_000_000L)
        assertEquals(DownloadStatus.Paused, item(2).status)
        assertEquals(DownloadStatus.Paused, item(3).status)
        assertEquals(1, transfers.size)

        // Raise limit to 1.2 GB (400 MB free): Ep 2 (600 MB) still cannot fit, so Ep 3 (350 MB) starts first.
        limits.totalLimitBytes = 1_200_000_000L
        DownloadsRepository.onStorageLimitsChanged()

        assertEquals(DownloadStatus.Paused, item(2).status)
        assertEquals(AutoDownloadStop.TotalStorageLimit, item(2).autoDownloadStop)
        assertEquals(DownloadStatus.Downloading, item(3).status)
        assertNull(item(3).autoDownloadStop)
        assertEquals(2, transfers.size)

        // Deleting Ep 1 (800 MB) frees enough room for Ep 2 (600 MB + 350 MB <= 1.2 GB), so Ep 2 starts now.
        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item(1).id))

        assertEquals(DownloadStatus.Downloading, item(2).status)
        assertNull(item(2).autoDownloadStop)
        assertEquals(3, transfers.size)
    }

    @Test
    fun announced_size_over_the_combined_limit_waits_and_auto_resumes_only_when_enough_room_is_freed() {
        limits.totalLimitBytes = 1_000L

        enqueueEpisode(1, automatic = false)
        transfers.last().onSuccess("file:/downloads/1.mp4", 500L)
        enqueueEpisode(2, automatic = false)
        transfers.last().onSuccess("file:/downloads/2.mp4", 200L)

        // Ep 3 has no upfront size claim; server announces 700 bytes before body.
        enqueueEpisode(3, automatic = false)
        val guard = assertNotNull(transfers.last().request.limits)
        assertEquals(AutoDownloadStop.TotalStorageLimit, guard.stopBeforeBody(0L, totalBytes = 700L, freeBytes = 50_000L))
        guard.onStopped(AutoDownloadStop.TotalStorageLimit)

        assertEquals(DownloadStatus.Paused, item(3).status)
        assertEquals(AutoDownloadStop.TotalStorageLimit, item(3).autoDownloadStop)
        assertEquals(700L, item(3).waitingSizeBytes)
        assertNull(item(3).totalBytes)

        // Deleting Ep 2 (200 bytes) leaves 500 bytes used -> 500 + 700 > 1,000, so Ep 3 stays waiting without oscillating.
        val countBeforeSmallDelete = transfers.size
        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item(2).id))
        assertEquals(DownloadStatus.Paused, item(3).status)
        assertEquals(countBeforeSmallDelete, transfers.size)

        // Deleting Ep 1 (500 bytes) leaves 0 bytes used -> 0 + 700 <= 1,000, so Ep 3 starts automatically.
        assertEquals(DownloadDeleteResult.Removed, DownloadsRepository.deleteDownload(item(1).id))
        assertEquals(DownloadStatus.Downloading, item(3).status)
        assertNull(item(3).autoDownloadStop)
        assertEquals(countBeforeSmallDelete + 1, transfers.size)
    }

    @Test
    fun pausing_a_waiting_download_by_hand_keeps_it_paused_when_room_appears() {
        limits.totalLimitBytes = 500_000_000L
        enqueueEpisode(1, automatic = false, claimedBytes = 700_000_000L)
        assertEquals(DownloadStatus.Paused, item(1).status)
        assertEquals(AutoDownloadStop.TotalStorageLimit, item(1).autoDownloadStop)

        DownloadsRepository.pauseDownload(item(1).id)
        assertEquals(DownloadStatus.Paused, item(1).status)
        assertNull(item(1).autoDownloadStop)
        assertNull(item(1).waitingSizeBytes)

        limits.totalLimitBytes = null
        DownloadsRepository.onStorageLimitsChanged()
        assertEquals(DownloadStatus.Paused, item(1).status)
        assertEquals(0, transfers.size)
    }

    @Test
    fun turning_on_the_combined_limit_while_a_hand_started_download_runs_holds_it_and_keeps_its_partial() {
        limits.totalLimitBytes = null
        enqueueEpisode(1, automatic = false)
        val running = transfers.last()
        assertNull(running.request.limits, "hand-started download without total limit must have no transfer guard")

        running.onProgress(400L, 900L)
        limits.totalLimitBytes = 600L
        DownloadsRepository.onStorageLimitsChanged()

        assertEquals(DownloadStatus.Paused, item(1).status)
        assertEquals(AutoDownloadStop.TotalStorageLimit, item(1).autoDownloadStop)
        assertEquals(400L, item(1).downloadedBytes)
        assertEquals(0, backend.removedPartials)
        assertTrue(item(1).id in backend.cancelledIds)
    }

    @Test
    fun hand_started_downloads_do_not_inherit_the_ten_gigabyte_auto_free_space_reserve() {
        limits.totalLimitBytes = 5_000L
        enqueueEpisode(1, automatic = false)
        val guard = assertNotNull(transfers.last().request.limits)

        // Free space is 10 bytes (< limits.minFreeBytes = 50), which would stop an auto-download,
        // but a hand-started download with no user free-space floor must be allowed.
        assertNull(guard.stopBeforeBody(downloadedBytes = 0L, totalBytes = 100L, freeBytes = 10L))
    }

    @Test
    fun total_storage_limit_persists_per_profile_and_codec_preserves_unknown_fields() {
        val payloads = MemoryAutoDownloadPayloads()
        payloads.save(1, """{"rules":[],"storageLimitGb":50,"futureField":"keep-me"}""")
        var activeProfile = 1
        val store = AutoDownloadStore(payloads) { activeProfile }

        store.currentRules()
        assertTrue(payloads.unreadable.isEmpty())
        assertNull(store.totalStorageLimitGb.value)
        assertEquals(50, store.storageLimitGb.value)

        assertTrue(store.setTotalStorageLimit(1, 100))
        assertEquals(100, store.totalStorageLimitGb.value)
        assertEquals(100L * BytesPerGigabyte, store.totalStorageLimitBytes())

        activeProfile = 2
        store.currentRules()
        assertNull(store.totalStorageLimitGb.value)
        assertNull(store.totalStorageLimitBytes())

        activeProfile = 1
        store.currentRules()
        assertEquals(100, store.totalStorageLimitGb.value)

        val stored = """{"items":[{"id":"d1","contentType":"movie","parentMetaId":"tt1","parentMetaType":"movie","videoId":"tt1","title":"Film","streamTitle":"1080p","providerName":"A","sourceUrl":"https://example.com/1.mp4","fileName":"1.mp4","status":"Paused","createdAtEpochMs":1,"updatedAtEpochMs":1,"autoDownloadStop":"TotalStorageLimit","waitingSizeBytes":555,"futureItemField":"kept"}]}"""
        val decoded = assertNotNull(DownloadsCodec.decode(stored))
        assertEquals(AutoDownloadStop.TotalStorageLimit, decoded.items.single().autoDownloadStop)
        assertEquals(555L, decoded.items.single().waitingSizeBytes)
        val reencoded = DownloadsCodec.encode(decoded.items, decoded, 2L)
        assertTrue("\"futureItemField\":\"kept\"" in reencoded)
    }

    @Test
    fun preserved_partials_count_against_combined_and_auto_limits_including_current_transfers_own_kept_partial() {
        limits.totalLimitBytes = 10_000L
        limits.autoLimitBytes = 10_000L

        enqueueEpisode(1, automatic = true)
        val first = transfers.single()
        first.onProgress(8_000L, 9_000L)
        val ep1Id = DownloadsRepository.uiState.value.items.single().id

        // Switch to a fresh partial while keeping the 8,000 B partial untouched.
        first.request.onSwitchedToNewPartial("1 (2).mp4.part", KeptPartialFile("1.mp4.part", "kept-key-1"))

        val afterSwitch = DownloadsRepository.uiState.value.items.single()
        assertEquals(0L, afterSwitch.downloadedBytes)
        assertEquals(8_000L, afterSwitch.supersededPartials.single().bytes)
        assertEquals(8_000L, DownloadsRepository.allDownloadBytes())
        assertEquals(8_000L, DownloadsRepository.allDownloadBytes(exceptDownloadId = ep1Id))
        assertEquals(8_000L, DownloadsRepository.autoDownloadBytes(exceptDownloadId = ep1Id))

        // A 6,000 B replacement would put total on-disk bytes at 8,000 + 6,000 = 14,000 > 10,000 -> must stop!
        val guard = assertNotNull(first.request.limits)
        assertEquals(
            AutoDownloadStop.TotalStorageLimit,
            guard.stopBeforeBody(downloadedBytes = 0L, totalBytes = 6_000L, freeBytes = 100_000L),
        )
        // A 2,000 B replacement fits (8,000 + 2,000 <= 10,000).
        assertNull(guard.stopBeforeBody(downloadedBytes = 0L, totalBytes = 2_000L, freeBytes = 100_000L))
    }

    @Test
    fun paused_trip_fill_counts_only_partial_bytes_and_does_not_block_new_downloads() {
        limits.totalLimitBytes = 10_000L

        DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "$showId:1:1",
            parentMetaId = showId,
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Ep 1",
            episodeThumbnail = null,
            stream = StreamItem(
                name = "1080p",
                url = "https://example.com/trip1.mp4",
                addonName = "A",
                addonId = "addon:A",
                behaviorHints = StreamBehaviorHints(videoSize = 8_000L),
            ),
            tripFill = true,
        )
        val tripTransfer = transfers.single()
        tripTransfer.onProgress(1_000L, 8_000L)
        val tripId = DownloadsRepository.uiState.value.items.single().id

        // User pauses the trip download: only the 1,000 B partial on disk should count.
        DownloadsRepository.pauseDownload(tripId)
        assertEquals(1_000L, DownloadsRepository.allDownloadBytes())
        assertEquals(1_000L, DownloadsRepository.autoDownloadBytes())

        // A 5,000 B hand-started download fits (1,000 + 5,000 <= 10,000) and starts right away.
        enqueueEpisode(2, automatic = false, claimedBytes = 5_000L)
        val second = DownloadsRepository.uiState.value.items.first { it.episodeNumber == 2 }
        assertEquals(DownloadStatus.Downloading, second.status)
        assertEquals(2, transfers.size)
    }

    @Test
    fun free_space_hold_on_one_drive_wakes_storage_limit_waiter_on_another_healthy_drive() {
        val realHold = DownloadsRepository.freeSpaceHold
        val hold = FreeSpaceHold()
        try {
            DownloadsRepository.freeSpaceHold = hold
            hold.setFloorGb(value = 5)
            hold.reportLocationFreeBytes(
                mapOf(
                    "drive-a" to 20L * BytesPerGigabyte,
                    "drive-b" to 20L * BytesPerGigabyte,
                ),
            )
            limits.totalLimitBytes = 10_000L

            val runningOnA = testDownload(1, DownloadStatus.Downloading, showId = showId, id = "dl-a").copy(
                locationId = "drive-a",
                downloadedBytes = 1_000L,
                totalBytes = 8_000L,
                createdAtEpochMs = 1L,
            )
            val waitingOnB = testDownload(2, DownloadStatus.Paused, showId = showId, id = "dl-b").copy(
                locationId = "drive-b",
                downloadedBytes = 0L,
                totalBytes = 3_000L,
                waitingSizeBytes = 3_000L,
                autoDownloadStop = AutoDownloadStop.TotalStorageLimit,
                createdAtEpochMs = 2L,
            )
            backend.payload = DownloadsCodec.encode(listOf(runningOnA, waitingOnB), null, 1L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            // Initially 8,000 + 3,000 = 11,000 > 10,000 -> dl-a is running and dl-b is still waiting.
            assertEquals(DownloadStatus.Downloading, DownloadsRepository.uiState.value.items.first { it.id == "dl-a" }.status)
            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.first { it.id == "dl-b" }.status)

            // Drive A drops below the 5 GB floor while Drive B stays at 20 GB free.
            hold.reportLocationFreeBytes(
                mapOf(
                    "drive-a" to 2L * BytesPerGigabyte,
                    "drive-b" to 20L * BytesPerGigabyte,
                ),
            )

            // dl-a pauses (releasing its 8,000 B reservation down to 1,000 B partial), waking dl-b (1,000 + 3,000 <= 10,000)!
            val itemA = DownloadsRepository.uiState.value.items.first { it.id == "dl-a" }
            val itemB = DownloadsRepository.uiState.value.items.first { it.id == "dl-b" }
            assertEquals(DownloadStatus.Paused, itemA.status)
            assertTrue(itemA.pausedByFreeSpace)
            assertEquals(DownloadStatus.Downloading, itemB.status)
        } finally {
            DownloadsRepository.freeSpaceHold = realHold
        }
    }

    @Test
    fun storage_waiter_skipped_while_drive_below_floor_starts_when_free_space_returns_or_floor_disabled() {
        val realHold = DownloadsRepository.freeSpaceHold
        val hold = FreeSpaceHold()
        try {
            DownloadsRepository.freeSpaceHold = hold
            hold.setFloorGb(value = 5)
            hold.reportLocationFreeBytes(mapOf("drive-a" to 20L * BytesPerGigabyte))
            limits.totalLimitBytes = 10L * BytesPerGigabyte

            // 1. Recovery via free space returning on the drive:
            val completed1 = testDownload(1, DownloadStatus.Completed, showId = showId, id = "done-1").copy(
                locationId = "drive-a",
                downloadedBytes = 9L * BytesPerGigabyte,
                totalBytes = 9L * BytesPerGigabyte,
                localFileUri = "file:/done-1.mkv",
                createdAtEpochMs = 1L,
            )
            val waiter1 = testDownload(2, DownloadStatus.Paused, showId = showId, id = "wait-1").copy(
                locationId = "drive-a",
                downloadedBytes = 0L,
                totalBytes = 3L * BytesPerGigabyte,
                waitingSizeBytes = 3L * BytesPerGigabyte,
                autoDownloadStop = AutoDownloadStop.TotalStorageLimit,
                createdAtEpochMs = 2L,
            )
            backend.payload = DownloadsCodec.encode(listOf(completed1, waiter1), null, 1L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()
            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.first { it.id == "wait-1" }.status)
            assertFalse(DownloadsRepository.uiState.value.items.first { it.id == "wait-1" }.pausedByFreeSpace)

            // Drive drops below the 5 GB floor; waiter1 stays a TotalStorageLimit waiter (not pausedByFreeSpace).
            hold.reportLocationFreeBytes(mapOf("drive-a" to 2L * BytesPerGigabyte))
            assertFalse(DownloadsRepository.uiState.value.items.first { it.id == "wait-1" }.pausedByFreeSpace)

            // Storage quota becomes available during the free-space hold -> waiter1 is skipped because drive-a is below floor.
            DownloadsRepository.deleteDownload("done-1")
            val skipped1 = DownloadsRepository.uiState.value.items.first { it.id == "wait-1" }
            assertEquals(DownloadStatus.Paused, skipped1.status)
            assertEquals(AutoDownloadStop.TotalStorageLimit, skipped1.autoDownloadStop)
            assertFalse(skipped1.pausedByFreeSpace)

            // Free space returns (12 GB - 3 GB = 9 GB >= 5 GB floor) -> waiter1 starts without needing pausedByFreeSpace!
            hold.reportLocationFreeBytes(mapOf("drive-a" to 12L * BytesPerGigabyte))
            assertEquals(DownloadStatus.Downloading, DownloadsRepository.uiState.value.items.first { it.id == "wait-1" }.status)

            // 2. Recovery via disabling the floor setting:
            transfers.clear()
            val completed2 = completed1.copy(id = "done-2", localFileUri = "file:/done-2.mkv")
            val waiter2 = waiter1.copy(id = "wait-2")
            backend.payload = DownloadsCodec.encode(listOf(completed2, waiter2), null, 2L)
            DownloadsRepository.clearLocalState()
            DownloadsRepository.ensureLoaded()

            hold.reportLocationFreeBytes(mapOf("drive-a" to 2L * BytesPerGigabyte))
            DownloadsRepository.deleteDownload("done-2")
            assertEquals(DownloadStatus.Paused, DownloadsRepository.uiState.value.items.first { it.id == "wait-2" }.status)

            hold.setFloorGb(value = null)
            assertEquals(DownloadStatus.Downloading, DownloadsRepository.uiState.value.items.first { it.id == "wait-2" }.status)
        } finally {
            DownloadsRepository.freeSpaceHold = realHold
        }
    }
}

class DesktopTotalStorageLimitTransferTest : TrustedLocalServerTest() {
    private val megabyte = 1024 * 1024
    private val bodyBytes = 4 * megabyte
    private val downloadsDir = DesktopStorage.rootDir.resolve("downloads").toFile()
    private val fileName = "nuvio-total-limit-${System.nanoTime()}.mkv"
    private lateinit var server: HttpServer

    @BeforeTest
    fun start_server() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun serve(path: String, announceSize: Boolean) = server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, if (announceSize) bodyBytes.toLong() else 0L)
            val chunk = ByteArray(64 * 1024) { 9 }
            runCatching {
                exchange.responseBody.use { out ->
                    repeat(bodyBytes / chunk.size) { out.write(chunk) }
                }
            }
        }
        serve("/sized.mkv", announceSize = true)
        serve("/unsized.mkv", announceSize = false)
        server.start()
    }

    @AfterTest
    fun stop_server() {
        server.stop(0)
        File(downloadsDir, fileName).delete()
        File(downloadsDir, "$fileName.part").delete()
    }

    @Test
    fun real_downloader_stops_for_total_limit_before_body_when_sized_and_keeps_partial_when_unsized() {
        // 1. Sized response larger than maxAllDownloadsBytes stops before writing a single byte.
        val doneSized = CountDownLatch(1)
        var sizedStop: AutoDownloadStop? = null
        var sizedFailure: String? = null
        val sizedGuard = DownloadTransferLimits(
            maxTotalBytes = { null },
            maxAllDownloadsBytes = { bodyBytes - 1L },
            minFreeBytes = 0L,
            onStopped = {
                sizedStop = it
                doneSized.countDown()
            },
        )
        DownloadsPlatformDownloader.start(
            request = DownloadPlatformRequest(
                item = testDownload(1, DownloadStatus.Downloading).copy(
                    sourceUrl = "http://127.0.0.1:${server.address.port}/sized.mkv",
                    fileName = fileName,
                ),
                limits = sizedGuard,
            ),
            onProgress = { _, _ -> },
            onSuccess = { _, _ -> doneSized.countDown() },
            onFailure = {
                sizedFailure = it
                doneSized.countDown()
            },
            onPaused = { doneSized.countDown() },
        )
        assertTrue(doneSized.await(60, TimeUnit.SECONDS))
        assertEquals(AutoDownloadStop.TotalStorageLimit, sizedStop)
        assertEquals(bodyBytes.toLong(), sizedGuard.lastRefusedSizeBytes)
        assertNull(sizedFailure)
        assertFalse(File(downloadsDir, "$fileName.part").exists())

        // 2. Unsized stream stops when bytes reach maxAllDownloadsBytes, keeping its partial file intact.
        val cap = 2L * megabyte
        val doneUnsized = CountDownLatch(1)
        var unsizedStop: AutoDownloadStop? = null
        var unsizedFailure: String? = null
        val unsizedGuard = DownloadTransferLimits(
            maxTotalBytes = { null },
            maxAllDownloadsBytes = { cap },
            minFreeBytes = 0L,
            onStopped = {
                unsizedStop = it
                doneUnsized.countDown()
            },
        )
        DownloadsPlatformDownloader.start(
            request = DownloadPlatformRequest(
                item = testDownload(2, DownloadStatus.Downloading).copy(
                    sourceUrl = "http://127.0.0.1:${server.address.port}/unsized.mkv",
                    fileName = fileName,
                ),
                limits = unsizedGuard,
            ),
            onProgress = { _, _ -> },
            onSuccess = { _, _ -> doneUnsized.countDown() },
            onFailure = {
                unsizedFailure = it
                doneUnsized.countDown()
            },
            onPaused = { doneUnsized.countDown() },
        )
        assertTrue(doneUnsized.await(60, TimeUnit.SECONDS))
        assertEquals(AutoDownloadStop.TotalStorageLimit, unsizedStop)
        assertNull(unsizedFailure, "stream over total limit must wait, never fail part way")
        val partial = File(downloadsDir, "$fileName.part")
        assertTrue(partial.exists() && partial.length() >= cap, "partial file must be preserved when total limit is reached")
    }

    @Test
    fun real_downloader_evaluates_combined_limit_and_floor_before_hard_disk_space_failure() {
        // Server announces 4 MB (bodyBytes) with no upfront size on the DownloadItem, while the drive has only 2 MB free.
        val realFreeProvider = DownloadsPlatformDownloader.freeBytesProvider
        val realBackend = DownloadsRepository.backend
        val realLimits = DownloadsRepository.autoDownloadLimits
        val realHold = DownloadsRepository.freeSpaceHold
        var storedPayload: String? = null
        var totalLimitBytes: Long? = null
        val hold = FreeSpaceHold()
        val liveBackend = object : DownloadsBackend {
            override fun loadPayload(): String? = storedPayload
            override fun savePayload(payload: String) {
                storedPayload = payload
            }
            override fun restoreItem(item: DownloadItem): DownloadItem = item
            override fun removeFile(localFileUri: String?): Boolean = true
            override fun removePartialFile(destinationFileName: String): Boolean = true
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
                onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
                onFailure: (message: String) -> Unit,
                onPaused: () -> Unit,
            ): DownloadsTaskHandle = DownloadsPlatformDownloader.start(request, onProgress, onSuccess, onFailure, onPaused)
        }
        val liveLimits = object : AutoDownloadLimits {
            override fun storageLimitBytes(): Long? = null
            override fun totalStorageLimitBytes(): Long? = totalLimitBytes
            override val minFreeBytes: Long = 0L
            override fun userFreeSpaceFloorBytes(): Long? = hold.floorBytes
        }

        fun awaitStatus(downloadId: String, target: DownloadStatus): DownloadItem {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                val current = DownloadsRepository.uiState.value.items.firstOrNull { it.id == downloadId }
                if (current != null && current.status == target) return current
                Thread.sleep(20)
            }
            error("Timed out waiting for $downloadId to reach $target: ${DownloadsRepository.uiState.value.items}")
        }

        try {
            DownloadsPlatformDownloader.freeBytesProvider = { 2L * megabyte }
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = liveBackend
            DownloadsRepository.autoDownloadLimits = liveLimits
            DownloadsRepository.freeSpaceHold = hold

            val unsizedStream = StreamItem(
                url = "http://127.0.0.1:${server.address.port}/sized.mkv",
                title = "Unsized Announced",
                addonName = "test",
                addonId = "addon:test",
            )

            fun enqueueMovie(movieId: String, title: String) {
                DownloadsRepository.enqueueFromStream(
                    contentType = "movie",
                    videoId = movieId,
                    parentMetaId = movieId,
                    parentMetaType = "movie",
                    title = title,
                    logo = null,
                    poster = null,
                    background = null,
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = null,
                    episodeThumbnail = null,
                    stream = unsizedStream,
                )
            }

            // 1. Combined limit (3 MB) < announced size (4 MB) on a drive with 2 MB free -> waits for TotalStorageLimit, NOT Failed!
            totalLimitBytes = 3L * megabyte
            hold.setFloorGb(null)
            enqueueMovie("m-limit", "Limit Movie")
            val limitId = DownloadsRepository.uiState.value.items.single().id
            val heldByLimit = awaitStatus(limitId, DownloadStatus.Paused)
            assertEquals(AutoDownloadStop.TotalStorageLimit, heldByLimit.autoDownloadStop)
            assertFalse(heldByLimit.pausedByFreeSpace)
            assertEquals(bodyBytes.toLong(), heldByLimit.waitingSizeBytes)
            assertNull(heldByLimit.errorMessage)
            DownloadsRepository.deleteDownload(limitId)

            // 2. User free-space floor (5 GB) with no combined limit on a drive with 2 MB free -> waits for free space, NOT Failed!
            totalLimitBytes = null
            hold.setFloorGb(5)
            enqueueMovie("m-floor", "Floor Movie")
            val floorId = DownloadsRepository.uiState.value.items.single().id
            val heldByFloor = awaitStatus(floorId, DownloadStatus.Paused)
            assertTrue(heldByFloor.pausedByFreeSpace)
            assertNull(heldByFloor.autoDownloadStop)
            assertEquals(bodyBytes.toLong(), heldByFloor.waitingSizeBytes)
            assertNull(heldByFloor.errorMessage)
            DownloadsRepository.deleteDownload(floorId)

            // 3. With neither combined limit nor floor, the announced 4 MB > 2 MB free still fails with NotEnoughSpace.
            hold.setFloorGb(null)
            enqueueMovie("m-plain", "Plain Movie")
            val plainId = DownloadsRepository.uiState.value.items.single().id
            val failedPlain = awaitStatus(plainId, DownloadStatus.Failed)
            assertEquals(runBlocking { getString(Res.string.downloads_error_not_enough_space) }, failedPlain.errorMessage)
        } finally {
            DownloadsRepository.clearLocalState()
            DownloadsRepository.backend = realBackend
            DownloadsRepository.autoDownloadLimits = realLimits
            DownloadsRepository.freeSpaceHold = realHold
            DownloadsPlatformDownloader.freeBytesProvider = realFreeProvider
        }
    }
}

@OptIn(ExperimentalTestApi::class)
class TotalDownloadStorageLimitUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun text(resource: StringResource, vararg arguments: Any) = runBlocking { getString(resource, *arguments) }

    @Test
    fun storage_summary_shows_both_total_and_auto_limits_and_saves_total_limit_for_profile() {
        val savedTotal = mutableListOf<Pair<Int, Int?>>()
        val savedAuto = mutableListOf<Pair<Int, Int?>>()
        var shownProfile by mutableIntStateOf(41)
        val state = DownloadFolderState(
            locations = listOf(
                DownloadLocationInfo(
                    id = "app",
                    path = "/downloads",
                    name = "downloads",
                    isDefault = true,
                    isAppFolder = true,
                    availability = FolderAvailability.Available,
                    itemCount = 2,
                    totalBytes = 30_000_000_000L,
                    freeBytes = 90_000_000_000L,
                    usedBytes = 30_000_000_000L,
                ),
            ),
            usageMeasured = true,
            totalFreeBytes = 90_000_000_000L,
            autoDownloadUsedBytes = 12_000_000_000L,
        )

        compose.setContent {
            NuvioTheme {
                DownloadStorageSummary(
                    state = state,
                    autoLimitGb = 25,
                    onManageLocations = null,
                    onChangeStorageLimit = { p, l -> savedAuto += p to l },
                    profileId = shownProfile,
                    totalLimitGb = 100,
                    onChangeTotalStorageLimit = { p, l -> savedTotal += p to l },
                )
            }
        }

        compose.onNodeWithText(
            text(Res.string.downloads_storage_all, formatBytes(30_000_000_000L), formatBytes(100L * BytesPerGigabyte)),
        ).assertIsDisplayed()
        compose.onNodeWithText(
            text(Res.string.downloads_storage_auto, formatBytes(12_000_000_000L), formatBytes(25L * BytesPerGigabyte)),
        ).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_storage_change_total_limit)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_storage_change_limit)).assertIsDisplayed()

        compose.onNodeWithContentDescription(text(Res.string.downloads_total_storage_limit))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle { shownProfile = 42 }
        compose.waitForIdle()
        compose.onNode(hasText(text(Res.string.downloads_auto_storage_limit_value, 250)) and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf<Pair<Int, Int?>>(41 to 250), savedTotal)
        assertEquals(emptyList(), savedAuto)
    }

    @Test
    fun total_storage_limit_setting_shows_no_limit_by_default_and_updates_controller() {
        val controller = object : AutoDownloadController {
            override val rules = MutableStateFlow(emptyList<AutoDownloadRule>())
            override val statuses = MutableStateFlow(emptyMap<String, AutoDownloadStatus>())
            override val storageLimitGb = MutableStateFlow<Int?>(null)
            override val totalStorageLimitGb = MutableStateFlow<Int?>(null)
            override val removals = MutableStateFlow(emptyList<AutoDownloadRemoval>())
            override val usesSystemTrash = true
            val changes = mutableListOf<Pair<Int, Int?>>()
            override fun setTotalStorageLimit(profileId: Int, limitGb: Int?) {
                changes += profileId to limitGb
                totalStorageLimitGb.value = limitGb
            }
            override fun save(profileId: Int, meta: MetaDetails, keepCount: Int, scope: AutoDownloadScope, deleteAfterWatching: Boolean) = Unit
            override fun turnOff(profileId: Int, ruleKey: String) = Unit
            override fun setStorageLimit(profileId: Int, limitGb: Int?) = Unit
            override fun keepDownload(profileId: Int, downloadId: String) = Unit
            override fun deleteDownloadNow(profileId: Int, downloadId: String) = Unit
            override fun checkNow() = Unit
        }

        compose.setContent {
            NuvioTheme {
                TotalStorageLimitSetting(isTablet = false, controller = controller, profileId = 7)
            }
        }

        compose.onNodeWithText(text(Res.string.downloads_total_storage_limit)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_none)).assertIsDisplayed()
        compose.onNodeWithText(text(Res.string.downloads_total_storage_limit)).performClick()
        compose.onNode(hasText(text(Res.string.downloads_auto_storage_limit_value, 50)) and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf<Pair<Int, Int?>>(7 to 50), controller.changes)
        compose.onNodeWithText(text(Res.string.downloads_auto_storage_limit_value, 50)).assertIsDisplayed()
    }
}
