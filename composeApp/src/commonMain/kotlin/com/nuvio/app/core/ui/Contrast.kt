package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

internal const val MinimumTextContrast = 4.5f

internal fun contrastRatio(foreground: Color, background: Color): Float {
    val lighter = max(foreground.luminance(), background.luminance())
    val darker = min(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** Light click feedback on the app's dark surfaces. A black ripple disappears there. */
internal fun darkSurfaceRippleColor(): Color = Color.White

/** An enabled control stays at full strength. Dimmed ink is reserved for disabled controls. */
internal fun activeControlContentAlpha(): Float = 1f
