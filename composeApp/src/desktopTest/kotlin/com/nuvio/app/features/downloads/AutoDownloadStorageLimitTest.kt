package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AutoDownloadStorageLimitTest {
    private class Transfer(
        val request: DownloadPlatformRequest,
        val onProgress: (Long, Long?) -> Unit,
        val onSuccess: (String, Long?) -> Unit,
    )

    /** Keeps the stored payload in memory and never touches a file or the network. */
    private inner class FakeBackend : DownloadsBackend {
        var payload: String? = null
        var removedPartials = 0

        override fun loadPayload(): String? = payload

        override fun savePayload(payload: String) {
            this.payload = payload
        }

        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            transfers += Transfer(request, onProgress, onSuccess)
            return object : DownloadsTaskHandle {
                override fun cancel() = Unit
            }
        }

        override fun removeFile(localFileUri: String?): Boolean = true

        override fun removePartialFile(destinationFileName: String): Boolean {
            removedPartials++
            return true
        }

        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? = localFileUri
    }

    private class FakeLimits(var limitBytes: Long?) : AutoDownloadLimits {
        override fun storageLimitBytes(): Long? = limitBytes
        override val minFreeBytes: Long = 50
    }

    private val showId = "storage-limit-test"
    private val ruleKey = autoDownloadRuleKey("series", showId)
    private val transfers = mutableListOf<Transfer>()
    private val backend = FakeBackend()
    private val limits = FakeLimits(limitBytes = 1_000)
    private val realBackend = DownloadsRepository.backend
    private val realLimits = DownloadsRepository.autoDownloadLimits

    @BeforeTest
    fun use_an_empty_in_memory_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.autoDownloadLimits = limits
        DownloadsRepository.ensureLoaded()
    }

    @AfterTest
    fun restore_the_real_repository() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.autoDownloadLimits = realLimits
    }

    private fun enqueue(episode: Int, automatic: Boolean, trip: Boolean = false): Transfer {
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
            episodeTitle = null,
            episodeThumbnail = null,
            stream = testStream("https://example.com/$episode.mp4"),
            autoDownloadRuleKey = ruleKey.takeIf { automatic },
            tripFill = trip,
        )
        return transfers.last()
    }

    private fun item(episode: Int) = DownloadsRepository.uiState.value.items.first { it.episodeNumber == episode }

    private fun Transfer.stopFor(totalBytes: Long?, downloadedBytes: Long = 0) =
        assertNotNull(request.limits).stopFor(downloadedBytes, totalBytes, freeBytes = null)

    @Test
    fun a_failed_partial_is_resumed_with_the_same_identity_and_trip_limits() {
        enqueue(1, automatic = false).onProgress(200, 900)
        DownloadsRepository.pauseDownload(item(1).id)
        val failed = item(1).copy(status = DownloadStatus.Failed, partialFileKey = "partial-identity")
        backend.payload = storedPayload(listOf(failed))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.resumeTripEpisode(failed, com.nuvio.app.features.profiles.ProfileRepository.activeProfileId)
        assertEquals(failed.id, item(1).id)
        assertEquals(200, item(1).downloadedBytes)
        assertEquals("partial-identity", item(1).partialFileKey)
        assertEquals(DownloadStatus.Downloading, item(1).status)
        assertEquals(true, item(1).tripFill)
        assertNotNull(transfers.last().request.limits)
    }

    @Test
    fun a_resumed_trip_partial_is_kept_when_the_source_announces_a_bad_file() {
        enqueue(1, automatic = false, trip = true).onProgress(300_000_000, 800_000_000)
        DownloadsRepository.pauseDownload(item(1).id)
        val failed = item(1).copy(status = DownloadStatus.Failed, partialFileKey = "partial-identity", expectedSizeBytes = 800_000_000)
        backend.payload = storedPayload(listOf(failed))
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.resumeTripEpisode(failed, com.nuvio.app.features.profiles.ProfileRepository.activeProfileId)
        transfers.last().onProgress(300_000_000, 1_000)
        assertEquals(DownloadStatus.Failed, item(1).status)
        assertEquals(DownloadFailureKind.BadFile, item(1).failureKind)
        assertEquals(300_000_000, item(1).downloadedBytes)
        assertEquals(800_000_000, item(1).totalBytes)
        assertEquals("partial-identity", item(1).partialFileKey)
        assertEquals(0, backend.removedPartials)
    }

    @Test
    fun backup_sources_can_keep_all_completed_copies_owned_by_the_run() {
        val show = TripShowCandidate("series", showId, "Show")
        val episode = SeasonBatchEpisode("$showId:1:1", 1, 1, null, null, 45)
        val owned = mutableSetOf<String>()
        repeat(3) { number ->
            val id = assertNotNull(DownloadsRepository.enqueueTripEpisode(
                show, episode, testStream("https://example.com/source-$number.mp4"),
                com.nuvio.app.features.profiles.ProfileRepository.activeProfileId, owned.toSet(),
            ))
            owned += id
            transfers.last().onSuccess("file:/downloads/$id.mp4", 300)
        }
        assertEquals(3, DownloadsRepository.uiState.value.items.size)
        assertEquals(owned, DownloadsRepository.uiState.value.items.map { it.id }.toSet())
        assertEquals(true, DownloadsRepository.uiState.value.items.all { it.status == DownloadStatus.Completed })
        assertEquals(0, backend.removedPartials)
    }

    @Test
    fun trip_transfers_enforce_announced_sizes_and_free_space_after_enqueue() {
        val transfer = enqueue(1, automatic = false, trip = true)
        val guard = assertNotNull(transfer.request.limits)
        assertEquals(AutoDownloadStop.StorageLimit, guard.stopBeforeBody(0, 8_000, 50_000))
        assertEquals(AutoDownloadStop.LowDiskSpace, guard.stopBeforeBody(0, 800, 849))
        assertNull(guard.stopBeforeBody(0, 800, 850))
        assertEquals(AutoDownloadStop.StorageLimit, guard.stopFor(1_001, null, 50_000))
    }

    @Test
    fun previous_trip_downloads_remain_in_the_budget_after_reload() {
        enqueue(1, automatic = false, trip = true).onSuccess("file:/downloads/1.mp4", 800)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        assertEquals(800, DownloadsRepository.autoDownloadBytes())
        assertEquals(AutoDownloadStop.StorageLimit, enqueue(2, automatic = false, trip = true).stopFor(300))
    }

    @Test
    fun guarded_trip_enqueue_cannot_replace_an_intervening_download() {
        enqueue(1, automatic = false).onProgress(200, 900)
        val original = item(1)
        DownloadsRepository.enqueueFromStream(
            "series", "$showId:1:1", showId, "series", "Show", null, null, null, 1, 1, null, null,
            testStream("https://example.com/replacement.mp4"), tripFill = true, onlyIfAbsent = true,
        )
        assertEquals(original.id, item(1).id)
        assertEquals(200, item(1).downloadedBytes)
        assertEquals(1, transfers.size)
    }

    @Test
    fun guarded_trip_enqueue_rejects_another_profile() {
        DownloadsRepository.enqueueFromStream(
            "series", "$showId:1:1", showId, "series", "Show", null, null, null, 1, 1, null, null,
            testStream("https://example.com/one.mp4"), tripFill = true,
            expectedProfileId = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId + 1,
        )
        assertEquals(0, transfers.size)
    }

    @Test
    fun a_download_the_user_starts_has_no_limits() {
        limits.limitBytes = 0

        assertNull(enqueue(1, automatic = false).request.limits)
    }

    @Test
    fun an_automatic_download_may_take_what_the_other_automatic_downloads_leave_of_the_limit() {
        enqueue(1, automatic = true).onSuccess("file:/downloads/1.mp4", 400)
        enqueue(2, automatic = true).onProgress(10, 300)
        enqueue(3, automatic = false).onSuccess("file:/downloads/3.mp4", 5_000)
        val transfer = enqueue(4, automatic = true)

        assertEquals(700, DownloadsRepository.autoDownloadBytes())
        assertNull(transfer.stopFor(totalBytes = 300))
        assertEquals(AutoDownloadStop.StorageLimit, transfer.stopFor(totalBytes = 301))
        assertEquals(AutoDownloadStop.StorageLimit, transfer.stopFor(totalBytes = null, downloadedBytes = 301))
    }

    @Test
    fun an_announced_size_that_is_allowed_is_reserved_before_the_next_transfer_asks() {
        val first = enqueue(1, automatic = true)
        val second = enqueue(2, automatic = true)

        assertNull(assertNotNull(first.request.limits).stopBeforeBody(0, totalBytes = 750, freeBytes = null))

        assertEquals(750, item(1).totalBytes)
        assertEquals(
            AutoDownloadStop.StorageLimit,
            assertNotNull(second.request.limits).stopBeforeBody(0, totalBytes = 750, freeBytes = null),
        )
        assertNull(item(2).totalBytes)
        assertNull(assertNotNull(second.request.limits).stopBeforeBody(0, totalBytes = 250, freeBytes = null))
    }

    @Test
    fun the_bytes_of_the_transfer_itself_are_not_counted_twice() {
        val transfer = enqueue(1, automatic = true)
        transfer.onProgress(600, 1_000)

        assertNull(transfer.stopFor(totalBytes = 1_000, downloadedBytes = 600))
    }

    @Test
    fun a_changed_limit_applies_to_a_transfer_that_is_already_running() {
        val transfer = enqueue(1, automatic = true)
        assertNull(transfer.stopFor(totalBytes = 900))

        limits.limitBytes = 500
        assertEquals(AutoDownloadStop.StorageLimit, transfer.stopFor(totalBytes = 900))

        limits.limitBytes = null
        assertNull(transfer.stopFor(totalBytes = Long.MAX_VALUE))
    }

    @Test
    fun a_transfer_stopped_by_a_limit_is_paused_with_the_reason_and_resuming_clears_it() {
        val transfer = enqueue(1, automatic = true)

        assertNotNull(transfer.request.limits).onStopped(AutoDownloadStop.StorageLimit)

        assertEquals(DownloadStatus.Paused, item(1).status)
        assertEquals(AutoDownloadStop.StorageLimit, item(1).autoDownloadStop)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        assertEquals(AutoDownloadStop.StorageLimit, item(1).autoDownloadStop)

        DownloadsRepository.resumeDownload(item(1).id)

        assertEquals(DownloadStatus.Downloading, item(1).status)
        assertNull(item(1).autoDownloadStop)
        assertEquals(2, transfers.size)
    }

    @Test
    fun a_stop_reported_by_a_transfer_that_was_replaced_is_ignored() {
        val first = enqueue(1, automatic = true)
        DownloadsRepository.pauseDownload(item(1).id)
        DownloadsRepository.resumeDownload(item(1).id)

        assertNotNull(first.request.limits).onStopped(AutoDownloadStop.LowDiskSpace)

        assertEquals(DownloadStatus.Downloading, item(1).status)
        assertNull(item(1).autoDownloadStop)
    }

    @Test
    fun the_free_space_floor_is_handed_to_the_transfer() {
        val transfer = enqueue(1, automatic = true)

        val limits = assertNotNull(transfer.request.limits)

        assertNull(limits.stopFor(downloadedBytes = 0, totalBytes = 100, freeBytes = 150))
        assertEquals(AutoDownloadStop.LowDiskSpace, limits.stopFor(downloadedBytes = 0, totalBytes = 100, freeBytes = 149))
    }
}
