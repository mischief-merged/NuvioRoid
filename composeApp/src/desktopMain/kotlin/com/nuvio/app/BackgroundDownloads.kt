package com.nuvio.app

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_tray_progress
import nuvio.composeapp.generated.resources.downloads_tray_running
import nuvio.composeapp.generated.resources.downloads_tray_paused
import nuvio.composeapp.generated.resources.downloads_tray_idle
import org.jetbrains.compose.resources.getString

internal enum class DownloadCloseDecision { HideWindow, AskToQuit, QuitNow }

internal fun downloadCloseDecision(
    running: Int,
    keepAfterClose: Boolean,
    isMac: Boolean,
    explicitQuit: Boolean,
    backgroundWork: Boolean = false,
): DownloadCloseDecision = when {
    running <= 0 && !backgroundWork -> DownloadCloseDecision.QuitNow
    !explicitQuit && isMac && keepAfterClose -> DownloadCloseDecision.HideWindow
    else -> DownloadCloseDecision.AskToQuit
}

internal data class DownloadTrayProgress(val running: Int, val paused: Int, val percent: Int?)

internal fun downloadTrayProgress(items: List<DownloadItem>): DownloadTrayProgress {
    val running = items.filter { it.status == DownloadStatus.Downloading }
    val known = running.isNotEmpty() && running.all { (it.totalBytes ?: 0) > 0 }
    val percent = if (known) {
        val total = running.sumOf { it.totalBytes!!.toDouble() }
        val received = running.sumOf { it.downloadedBytes.coerceIn(0, it.totalBytes!!).toDouble() }
        (received / total * 100).toInt().coerceIn(0, 100)
    } else null
    return DownloadTrayProgress(running.size, items.count { it.status == DownloadStatus.Paused }, percent)
}

internal suspend fun downloadTrayText(progress: DownloadTrayProgress): String = when {
    progress.running > 0 && progress.percent != null -> getString(Res.string.downloads_tray_progress, progress.running, progress.percent)
    progress.running > 0 -> getString(Res.string.downloads_tray_running, progress.running)
    progress.paused > 0 -> getString(Res.string.downloads_tray_paused, progress.paused)
    else -> getString(Res.string.downloads_tray_idle)
}
