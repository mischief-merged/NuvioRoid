package com.nuvio.app

import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.testDownload
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundDownloadsTest {
    @Test
    fun window_close_hides_on_mac_only_when_enabled_and_work_runs() {
        assertEquals(DownloadCloseDecision.HideWindow, downloadCloseDecision(2, true, true, false))
        assertEquals(DownloadCloseDecision.AskToQuit, downloadCloseDecision(2, false, true, false))
        assertEquals(DownloadCloseDecision.AskToQuit, downloadCloseDecision(2, true, false, false))
        assertEquals(DownloadCloseDecision.QuitNow, downloadCloseDecision(0, true, true, false))
        assertEquals(DownloadCloseDecision.HideWindow, downloadCloseDecision(0, true, true, false, backgroundWork = true))
    }

    @Test
    fun explicit_quit_always_asks_while_work_runs_even_with_background_downloads_enabled() {
        for (enabled in listOf(true, false)) {
            assertEquals(DownloadCloseDecision.AskToQuit, downloadCloseDecision(2, enabled, true, true))
            assertEquals(DownloadCloseDecision.QuitNow, downloadCloseDecision(0, enabled, true, true))
            assertEquals(DownloadCloseDecision.AskToQuit, downloadCloseDecision(0, enabled, true, true, backgroundWork = true))
        }
    }

    @Test
    fun tray_uses_total_bytes_of_running_downloads_and_ignores_finished_files() = runBlocking {
        val progress = downloadTrayProgress(listOf(
            testDownload(1, DownloadStatus.Downloading).copy(downloadedBytes = 10, totalBytes = 100),
            testDownload(2, DownloadStatus.Downloading).copy(downloadedBytes = 154, totalBytes = 300),
            testDownload(3, DownloadStatus.Completed).copy(downloadedBytes = 1000, totalBytes = 1000),
            testDownload(4, DownloadStatus.Paused),
        ))
        assertEquals(DownloadTrayProgress(2, 1, 41), progress)
        assertEquals("2 downloading · 41%", downloadTrayText(progress))
    }

    @Test
    fun unknown_totals_never_show_a_misleading_percentage() = runBlocking {
        val unknown = downloadTrayProgress(listOf(testDownload(1, DownloadStatus.Downloading).copy(totalBytes = null)))
        assertEquals("1 downloading", downloadTrayText(unknown))
        assertEquals(null, unknown.percent)
        val mixed = downloadTrayProgress(listOf(
            testDownload(1, DownloadStatus.Downloading).copy(totalBytes = 100),
            testDownload(2, DownloadStatus.Downloading).copy(totalBytes = 0),
        ))
        assertEquals(null, mixed.percent)
        assertEquals("No downloads running", downloadTrayText(downloadTrayProgress(emptyList())))
        assertEquals("1 paused", downloadTrayText(downloadTrayProgress(listOf(testDownload(1, DownloadStatus.Paused)))))
    }

    @Test
    fun reported_bytes_are_clamped_and_large_totals_do_not_overflow() {
        assertEquals(100, downloadTrayProgress(listOf(testDownload(1, DownloadStatus.Downloading).copy(downloadedBytes = 200, totalBytes = 100))).percent)
        assertEquals(0, downloadTrayProgress(listOf(testDownload(1, DownloadStatus.Downloading).copy(downloadedBytes = -1, totalBytes = 100))).percent)
        assertEquals(50, downloadTrayProgress((1..2).map {
            testDownload(it, DownloadStatus.Downloading).copy(downloadedBytes = Long.MAX_VALUE / 2, totalBytes = Long.MAX_VALUE)
        }).percent)
    }
}
