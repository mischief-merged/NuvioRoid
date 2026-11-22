package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

@Composable
internal actual fun PlatformEscapeShortcut(handler: () -> Unit) {
    val current = rememberUpdatedState(handler)
    DisposableEffect(Unit) {
        val bridged: () -> Unit = { current.value() }
        DesktopEscapeShortcut.register(bridged)
        onDispose { DesktopEscapeShortcut.unregister(bridged) }
    }
}

@Composable
internal actual fun PlatformModalBackHandler(
    dismissOnBackPress: Boolean,
    onDismiss: () -> Unit,
) {
    val currentDismissOnBackPress = rememberUpdatedState(dismissOnBackPress)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) {
        val handler: () -> Unit = {
            if (currentDismissOnBackPress.value) {
                currentOnDismiss.value()
            }
        }
        DesktopEscapeShortcut.registerModal(handler)
        onDispose { DesktopEscapeShortcut.unregisterModal(handler) }
    }
}

internal actual fun dispatchPlatformLocalBack(): Boolean = DesktopEscapeShortcut.dispatchLocalBack()
