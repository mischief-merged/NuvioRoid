package com.nuvio.app.features.player.desktop

internal const val DESKTOP_PLAYER_MAX_VOLUME_LEVEL = 2f

internal fun Float.coerceDesktopPlayerVolumeLevel(): Float =
    coerceIn(0f, DESKTOP_PLAYER_MAX_VOLUME_LEVEL)

internal fun resolveDesktopPlayerVolumeLevel(
    requestedLevel: Float?,
    currentLevel: Float?,
    rememberedLevel: Float,
): Float =
    (requestedLevel ?: currentLevel ?: rememberedLevel).coerceDesktopPlayerVolumeLevel()

/**
 * Output level for one step of a sleep-timer fade.
 * Scales [audibleLevel], the level being heard now. A mute is zero and stays zero.
 * [rememberedLevel] is the slider, including the level from before a mute, and is not the fade base.
 */
internal fun sleepTimerFadeOutputLevel(
    audibleLevel: Float,
    rememberedLevel: Float,
    fraction: Float,
): Float = (audibleLevel * fraction.coerceIn(0f, 1f)).coerceDesktopPlayerVolumeLevel()
