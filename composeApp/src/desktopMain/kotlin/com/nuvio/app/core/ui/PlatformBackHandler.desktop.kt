package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

// Registers with both Compose's window back dispatcher and the desktop Escape shortcut stack,
// so local back handlers (such as the Downloads show page) run before root navigation back.
@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    val currentOnBack = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val handler: () -> Unit = { currentOnBack.value() }
        DesktopEscapeShortcut.registerLocal(handler)
        onDispose { DesktopEscapeShortcut.unregisterLocal(handler) }
    }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}
