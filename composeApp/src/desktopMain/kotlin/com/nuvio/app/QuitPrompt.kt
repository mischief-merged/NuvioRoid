package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.nuvio.app.core.storage.DesktopAccountDataLifecycle
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

internal class DesktopQuitCoordinator(
    val askedAboutDownloads: Int?,
    val isQuitting: Boolean,
    val requestClose: () -> Unit,
    val confirmQuitNow: () -> Unit,
    val keepOpen: () -> Unit,
    val askAboutQuit: (Int) -> Unit,
)

@Composable
internal fun rememberDesktopQuitCoordinator(
    appScope: CoroutineScope,
    onExitApplication: () -> Unit,
): DesktopQuitCoordinator {
    var askedAboutDownloads by remember { mutableStateOf<Int?>(null) }
    var isQuitting by remember { mutableStateOf(false) }
    val currentOnExitApplication by rememberUpdatedState(onExitApplication)

    val closeApplication: () -> Unit = {
        if (DesktopAccountDataLifecycle.tryScheduleConfirmedQuit()) {
            isQuitting = true
            askedAboutDownloads = null
            appScope.launch {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        DesktopAccountDataLifecycle.executeConfirmedQuit {
                            currentOnExitApplication()
                        }
                    }
                } finally {
                    // Follow the account latch. The exit callback returns once native teardown
                    // has started, and a second close must not run the gates again. A quit the
                    // gates refuse clears the latch, so this opens again and the user can retry.
                    if (!DesktopAccountDataLifecycle.isConfirmedQuitLatched()) {
                        isQuitting = false
                    }
                }
            }
        }
    }
    // Closing the window, or quitting from the menu, asks first only while downloads are running.
    val requestClose: () -> Unit = {
        when (val decision = quitDecision(activeDownloadCount(DownloadsRepository.uiState.value.items))) {
            QuitDecision.QuitNow -> closeApplication()
            is QuitDecision.AskFirst -> askedAboutDownloads = decision.activeDownloads
        }
    }
    val keepOpen: () -> Unit = {
        askedAboutDownloads = null
        appScope.launch(Dispatchers.IO) {
            DesktopAccountDataLifecycle.cancelPendingQuit()
        }
    }
    val askAboutQuit: (Int) -> Unit = { count ->
        if (!isQuitting) askedAboutDownloads = count
    }
    val currentRequestLifecycleQuit by rememberUpdatedState<(Boolean) -> Unit> { allowCancel ->
        if (!allowCancel) {
            closeApplication()
        } else {
            requestClose()
        }
    }

    DisposableEffect(Unit) {
        val unregister = DesktopAccountDataLifecycle.registerWindowRequestQuitHandler { allowCancel ->
            currentRequestLifecycleQuit(allowCancel)
        }
        onDispose(unregister)
    }

    return DesktopQuitCoordinator(
        askedAboutDownloads = askedAboutDownloads,
        isQuitting = isQuitting,
        requestClose = requestClose,
        confirmQuitNow = closeApplication,
        keepOpen = keepOpen,
        askAboutQuit = askAboutQuit,
    )
}

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
