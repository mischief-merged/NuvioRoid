package com.nuvio.app

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.quit_downloads_keep_open
import nuvio.composeapp.generated.resources.quit_downloads_message
import nuvio.composeapp.generated.resources.quit_downloads_quit
import nuvio.composeapp.generated.resources.quit_downloads_title
import nuvio.composeapp.generated.resources.quit_downloads_background_message
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What closing the window does. */
internal sealed interface QuitDecision {
    /** Nothing is running: the app closes at once. */
    data object QuitNow : QuitDecision

    /** [activeDownloads] transfers are running: the user is asked first. */
    data class AskFirst(val activeDownloads: Int) : QuitDecision
}

/** The transfers that are running: what is downloading, including one that waits for the network or is being checked. */
internal fun activeDownloadCount(items: List<DownloadItem>): Int = items.count { it.status == DownloadStatus.Downloading }

internal fun quitDecision(activeDownloads: Int): QuitDecision =
    if (activeDownloads > 0) QuitDecision.AskFirst(activeDownloads) else QuitDecision.QuitNow

/**
 * Asks whether to quit while downloads are running. Dismissing the dialog keeps the app open, as
 * does the second button; only the Quit button quits.
 */
@Composable
internal fun QuitWithDownloadsDialog(
    activeDownloads: Int,
    onQuit: () -> Unit,
    onKeepOpen: () -> Unit,
) {
    NuvioStatusModal(
        title = stringResource(Res.string.quit_downloads_title),
        message = if (activeDownloads > 0) pluralStringResource(Res.plurals.quit_downloads_message, activeDownloads, activeDownloads)
            else stringResource(Res.string.quit_downloads_background_message),
        isVisible = true,
        destructive = true,
        confirmText = stringResource(Res.string.quit_downloads_quit),
        dismissText = stringResource(Res.string.quit_downloads_keep_open),
        onConfirm = onQuit,
        onDismiss = onKeepOpen,
    )
}
