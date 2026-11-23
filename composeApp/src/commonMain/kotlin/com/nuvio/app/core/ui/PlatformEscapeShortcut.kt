package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

/**
 * Registers the handler invoked when the user presses Escape.
 *
 * On desktop this is wired to the AWT shortcut host so Escape reaches navigation even while a
 * card, list, or player surface holds focus. Other platforms ignore it.
 */
@Composable
internal expect fun PlatformEscapeShortcut(handler: () -> Unit)

/**
 * Registers an open modal dialog ahead of page-level back handlers on desktop so Escape and
 * gamepad Back dismiss (or are consumed by) the dialog instead of navigating the page behind it.
 */
@Composable
internal expect fun PlatformModalBackHandler(
    dismissOnBackPress: Boolean = true,
    onDismiss: () -> Unit,
)

/** Runs the innermost active modal [PlatformModalBackHandler] on desktop; returns false when none is active. */
internal expect fun dispatchPlatformModalBack(): Boolean

/** Runs the innermost active modal or local [PlatformBackHandler] on desktop; returns false when none is active. */
internal expect fun dispatchPlatformLocalBack(): Boolean

/** True on desktop when a modal registered via [PlatformModalBackHandler] is currently open. */
internal expect fun hasOpenPlatformModal(): Boolean
