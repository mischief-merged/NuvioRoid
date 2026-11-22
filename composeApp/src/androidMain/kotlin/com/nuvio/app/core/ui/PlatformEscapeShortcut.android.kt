package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformEscapeShortcut(handler: () -> Unit) {
    // No-op on Android — Back is handled by the platform.
}

@Composable
internal actual fun PlatformModalBackHandler(
    dismissOnBackPress: Boolean,
    onDismiss: () -> Unit,
) {
    // No-op on Android — Compose dialogs handle Back directly in their window.
}

internal actual fun dispatchPlatformLocalBack(): Boolean = false
