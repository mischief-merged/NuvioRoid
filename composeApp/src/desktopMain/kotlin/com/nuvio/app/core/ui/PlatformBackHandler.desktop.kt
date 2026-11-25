package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

// Registers with both Compose's window back dispatcher and the desktop Escape shortcut stack,
// so local back handlers (sheets, settings sub-pages, Downloads show page) run before root
// navigation back, while modal dialogs registered via PlatformModalBackHandler keep top priority.
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )

    // Escape is observed by an AWT dispatcher before Compose sees it, so register the same local
    // handler there. The most recently enabled handler wins (sheets, settings sub-pages, local
    // views). A stable lambda is pushed so recompositions do not leave stale handlers from disposed
    // compositions in the stack.
    val currentOnBack by rememberUpdatedState(onBack)
    val escapeHandler = remember { { currentOnBack() } }
    DisposableEffect(enabled, escapeHandler) {
        if (!enabled) {
            return@DisposableEffect onDispose {}
        }
        DesktopEscapeShortcut.registerLocal(escapeHandler)
        onDispose { DesktopEscapeShortcut.unregisterLocal(escapeHandler) }
    }
}
