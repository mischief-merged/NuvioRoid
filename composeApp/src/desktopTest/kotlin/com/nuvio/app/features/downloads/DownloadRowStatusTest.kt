package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRowStatusTest {
    private val now = 1_000_000L

    private fun item(
        status: DownloadStatus,
        downloaded: Long = 10L,
        total: Long? = 100L,
        updatedAt: Long = now,
        stop: AutoDownloadStop? = null,
        rule: String? = null,
        verifiedBy: DownloadVerification? = null,
        frames: DownloadFramesCheck = DownloadFramesCheck.NotChecked,
    ) = DownloadItem(
        id = "a", contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = "tt1:1:1",
        title = "Show", streamTitle = "1080p", providerName = "Addon", sourceUrl = "https://example.com/a.mkv",
        fileName = "a.mkv", status = status, downloadedBytes = downloaded, totalBytes = total,
        createdAtEpochMs = 1, updatedAtEpochMs = updatedAt, autoDownloadStop = stop, autoDownloadRuleKey = rule,
        verifiedBy = verifiedBy, framesCheck = frames,
    )

    private fun phase(item: DownloadItem, offline: Boolean = false, at: Long = now, receivedRecently: Boolean = false) =
        downloadRowStatus(item, at, offline, receivedBytesRecently = receivedRecently).phase

    @Test
    fun a_transfer_that_receives_data_is_downloading() {
        assertEquals(DownloadPhase.Downloading, phase(item(DownloadStatus.Downloading)))
        assertEquals(DownloadPhase.Downloading, phase(item(DownloadStatus.Downloading, total = null)))
    }

    @Test
    fun a_transfer_without_internet_waits_for_it() {
        assertEquals(DownloadPhase.WaitingForNetwork, phase(item(DownloadStatus.Downloading), offline = true))
    }

    @Test
    fun a_transfer_that_went_silent_while_online_is_reconnecting() {
        val silent = item(DownloadStatus.Downloading, updatedAt = now - DownloadStalledAfterMs - 1)
        assertEquals(DownloadPhase.Reconnecting, phase(silent))
        assertEquals(DownloadPhase.Downloading, phase(item(DownloadStatus.Downloading, updatedAt = now - DownloadStalledAfterMs)))
    }

    @Test
    fun without_internet_the_wait_is_named_before_the_silence() {
        val silent = item(DownloadStatus.Downloading, updatedAt = 0)
        assertEquals(DownloadPhase.WaitingForNetwork, phase(silent, offline = true))
    }

    @Test
    fun a_transfer_that_received_bytes_recently_is_downloading_even_while_marked_offline() {
        val active = item(DownloadStatus.Downloading, downloaded = 125_000_000L, total = 1_400_000_000L, updatedAt = now)
        val tracker = DownloadRateTracker()
        tracker.recordStart(active.id, 100_000_000L, now - 6_000L)
        assertFalse(tracker.hasRecentProgress(active.id, now))
        tracker.recordProgress(active.id, 125_000_000L, now - 5_000L)
        assertTrue(tracker.hasRecentProgress(active.id, now))

        assertEquals(DownloadPhase.Downloading, phase(active, offline = true, receivedRecently = tracker.hasRecentProgress(active.id, now)))
        assertEquals(
            128L,
            estimateDownloadEtaSeconds(active, bytesPerSecond = 10_000_000L, nowEpochMs = now, offline = true, receivedBytesRecently = true),
        )

        val afterSilence = now + DownloadStalledAfterMs
        assertFalse(tracker.hasRecentProgress(active.id, afterSilence))
        assertEquals(
            DownloadPhase.WaitingForNetwork,
            phase(active, offline = true, at = afterSilence, receivedRecently = tracker.hasRecentProgress(active.id, afterSilence)),
        )
        assertNull(
            estimateDownloadEtaSeconds(active, bytesPerSecond = 10_000_000L, nowEpochMs = afterSilence, offline = true, receivedBytesRecently = false),
        )
    }

    @Test
    fun a_transfer_with_all_its_bytes_is_checking_the_file_even_when_quiet_or_offline() {
        val full = item(DownloadStatus.Downloading, downloaded = 100, total = 100, updatedAt = 0)
        assertEquals(DownloadPhase.CheckingFile, phase(full))
        assertEquals(DownloadPhase.CheckingFile, phase(full, offline = true))
    }

    @Test
    fun an_unknown_size_is_never_checking_the_file() {
        assertEquals(DownloadPhase.Downloading, phase(item(DownloadStatus.Downloading, downloaded = 500, total = null)))
        assertEquals(DownloadPhase.Downloading, phase(item(DownloadStatus.Downloading, downloaded = 0, total = 0)))
    }

    @Test
    fun a_paused_download_says_why_when_a_limit_stopped_it() {
        assertEquals(DownloadPhase.Paused, phase(item(DownloadStatus.Paused)))
        assertEquals(DownloadPhase.PausedStorageLimit, phase(item(DownloadStatus.Paused, stop = AutoDownloadStop.StorageLimit)))
        assertEquals(DownloadPhase.PausedLowDiskSpace, phase(item(DownloadStatus.Paused, stop = AutoDownloadStop.LowDiskSpace)))
    }

    @Test
    fun silence_and_offline_do_not_change_a_paused_or_failed_row() {
        assertEquals(DownloadPhase.Paused, phase(item(DownloadStatus.Paused, updatedAt = 0), offline = true))
        assertEquals(DownloadPhase.Failed, phase(item(DownloadStatus.Failed, updatedAt = 0), offline = true))
    }

    @Test
    fun an_automatic_download_is_marked_whatever_it_does() {
        DownloadStatus.entries.forEach { status ->
            assertTrue(downloadRowStatus(item(status, rule = "rule"), now).isAuto, "$status")
            assertFalse(downloadRowStatus(item(status), now).isAuto, "$status")
        }
    }

    private fun result(verifiedBy: DownloadVerification?, frames: DownloadFramesCheck = DownloadFramesCheck.NotChecked) =
        downloadRowStatus(item(DownloadStatus.Completed, verifiedBy = verifiedBy, frames = frames), now)

    @Test
    fun a_finished_file_says_what_it_was_checked_against() {
        assertEquals(DownloadResult.VerifiedBySource, result(DownloadVerification.Hash).result)
        assertEquals(DownloadResult.VerifiedByServer, result(DownloadVerification.Digest).result)
        assertEquals(DownloadResult.VerifiedBySize, result(DownloadVerification.Size).result)
        assertEquals(DownloadResult.BasicCheck, result(DownloadVerification.None).result)
        assertEquals(DownloadResult.NotChecked, result(null).result)
    }

    @Test
    fun a_suspect_file_is_suspect_whatever_else_matched() {
        DownloadVerification.entries.forEach { verification ->
            assertEquals(DownloadResult.Suspect, result(verification, DownloadFramesCheck.Suspect).result)
        }
        assertEquals(DownloadResult.Suspect, result(null, DownloadFramesCheck.Suspect).result)
    }

    @Test
    fun the_picture_check_is_reported_only_when_it_passed() {
        assertTrue(result(DownloadVerification.Size, DownloadFramesCheck.Passed).pictureChecked)
        assertFalse(result(DownloadVerification.Size, DownloadFramesCheck.NotChecked).pictureChecked)
        assertFalse(result(DownloadVerification.Size, DownloadFramesCheck.Suspect).pictureChecked)
    }

    @Test
    fun only_finished_downloads_have_a_result() {
        listOf(DownloadStatus.Downloading, DownloadStatus.Paused, DownloadStatus.Failed).forEach { status ->
            assertNull(downloadRowStatus(item(status), now).result, "$status")
        }
    }

    private fun text(resource: org.jetbrains.compose.resources.StringResource) = runBlocking { getString(resource) }

    @Test
    fun every_phase_and_result_has_its_own_plain_wording() {
        val phases = DownloadPhase.entries.map { text(it.message) }
        assertEquals(phases.size, phases.toSet().size, "two phases share a text")
        val results = DownloadResult.entries.map { text(it.message) }
        assertEquals(results.size, results.toSet().size, "two results share a text")
    }

    @Test
    fun the_wording_has_no_technical_terms() {
        val jargon = listOf("checksum", "decode", "digest", "hash", "sha", "md5", "codec", "frame", "ffmpeg", "crc")
        (DownloadPhase.entries.map { it.message } + DownloadResult.entries.map { it.message }).forEach { resource ->
            val message = text(resource).lowercase()
            jargon.forEach { term -> assertFalse(term in message, "'$term' is in \"$message\"") }
        }
    }

    @Test
    fun messages_with_a_size_have_a_place_for_it_and_the_others_do_not() {
        DownloadPhase.entries.forEach { phase ->
            val message = text(phase.message)
            assertEquals(phase.showsSize, "%1\$s" in message, "$phase: \"$message\"")
        }
    }

    @Test
    fun the_stored_list_of_an_earlier_build_still_reads_and_the_status_follows_from_it() {
        val stored = """{"id":"a","contentType":"movie","parentMetaId":"tt1","parentMetaType":"movie","videoId":"tt1","title":"Film",""" +
            """"streamTitle":"1080p","providerName":"Addon","sourceUrl":"https://example.com/a.mkv","fileName":"a.mkv",""" +
            """"status":"Completed","createdAtEpochMs":1,"updatedAtEpochMs":2}"""
        val item = Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromString<DownloadItem>(stored)
        assertEquals(DownloadResult.NotChecked, downloadRowStatus(item, now).result)
    }

    @Test
    fun what_a_screen_reader_says_has_no_sizes_and_differs_per_phase() {
        val announcements = DownloadPhase.entries.map { text(it.announcement) }
        assertEquals(announcements.size, announcements.toSet().size, "two phases share an announcement")
        announcements.forEach { assertFalse('%' in it, "\"$it\" has a place for a value") }
    }

    @Test
    fun what_a_screen_reader_says_has_no_technical_terms() {
        val jargon = listOf("checksum", "decode", "digest", "hash", "codec", "frame", "ffmpeg")
        DownloadPhase.entries.map { text(it.announcement).lowercase() }.forEach { announcement ->
            jargon.forEach { term -> assertFalse(term in announcement, "'$term' is in \"$announcement\"") }
        }
    }

    @Test
    fun the_name_of_a_download_in_a_label_carries_the_episode_or_is_the_movie_title() {
        assertEquals("Show S01E01", item(DownloadStatus.Paused).copy(title = "Show", seasonNumber = 1, episodeNumber = 1).nameForLabels())
        assertEquals("Pantheon S02E13", item(DownloadStatus.Paused).copy(title = "Pantheon", seasonNumber = 2, episodeNumber = 13).nameForLabels())
        assertEquals("Film", item(DownloadStatus.Paused).copy(title = "Film", seasonNumber = null, episodeNumber = null).nameForLabels())
        assertEquals("Film", item(DownloadStatus.Paused).copy(title = "Film", seasonNumber = 1, episodeNumber = null).nameForLabels())
    }

    @Test
    fun a_finished_file_marked_as_not_the_real_video_is_a_problem_row_without_a_result() {
        val marked = item(DownloadStatus.Completed, frames = DownloadFramesCheck.NotTheRealVideo, verifiedBy = DownloadVerification.Size)

        val status = downloadRowStatus(marked, now)

        assertEquals(DownloadPhase.NotTheRealVideo, status.phase)
        assertNull(status.result)
        assertFalse(status.pictureChecked)
        assertTrue(marked.isPlaceholder)
        assertFalse(marked.isPlayable)
    }

    @Test
    fun a_download_with_nothing_to_compare_says_so_only_when_asked_with_a_runtime_lookup() {
        val old = item(DownloadStatus.Completed)
        assertEquals(DownloadResult.NotChecked, downloadRowStatus(old, now).result)
        assertEquals(DownloadResult.CouldNotCompare, downloadRowStatus(old, now, runtimeMinutes = { null }).result)
        assertEquals(DownloadResult.NotChecked, downloadRowStatus(old, now, runtimeMinutes = { 120 }).result)
        val claimed = old.copy(streamSubtitle = "[\uD83D\uDCBE 9.53 GB] Film.mkv")
        assertEquals(DownloadResult.NotChecked, downloadRowStatus(claimed, now, runtimeMinutes = { null }).result)
        val verified = item(DownloadStatus.Completed, verifiedBy = DownloadVerification.Size)
        assertEquals(DownloadResult.VerifiedBySize, downloadRowStatus(verified, now, runtimeMinutes = { null }).result)
    }
}
