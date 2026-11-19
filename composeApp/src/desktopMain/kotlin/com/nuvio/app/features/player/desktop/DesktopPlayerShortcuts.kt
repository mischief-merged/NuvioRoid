package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlShortcutItem

internal enum class DesktopPlayerShortcutCommand {
    PLAY_PAUSE,
    SEEK_BACK_10S,
    SEEK_FORWARD_10S,
    FINE_SEEK_BACK_1S,
    FINE_SEEK_FORWARD_1S,
    VOLUME_UP,
    VOLUME_DOWN,
    TOGGLE_MUTE,
    TOGGLE_FULLSCREEN,
    EXIT_FULLSCREEN_OR_BACK,
    SKIP_INTRO,
    NEXT_EPISODE,
    TOGGLE_SHORTCUTS_OVERLAY,
    CLOSE_OVERLAY,
    TOGGLE_SUBTITLES_MODAL,
    TOGGLE_AUDIO_MODAL,
    TOGGLE_SPEED_MODAL,
    TOGGLE_EPISODES_MODAL,
    TOGGLE_SOURCES_MODAL,
    TOGGLE_PIP,
    RESET_SPEED,
    SPEED_DOWN,
    SPEED_UP,
    CYCLE_AUDIO,
    TOGGLE_SUBTITLE_VISIBILITY,
    SUBTITLE_DELAY_EARLIER,
    SUBTITLE_DELAY_LATER,
}

internal enum class DesktopPlayerShortcutGroup {
    PLAYBACK,
    NAVIGATION,
}

internal data class DesktopPlayerKeyInput(
    val code: String,
    val key: String = "",
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val alt: Boolean = false,
    val isTextInputFocused: Boolean = false,
)

internal data class DesktopPlayerShortcutContext(
    val hasOpenModal: Boolean = false,
    val activeModal: String = "",
    val isConfirmDialogOpen: Boolean = false,
    val hasPlaybackError: Boolean = false,
    val isFullscreen: Boolean = false,
    val isInPip: Boolean = false,
    val skipPromptVisible: Boolean = false,
    val hasNextEpisode: Boolean = true,
    val canEnterSkipWithEnter: Boolean = false,
)

internal data class DesktopPlayerShortcutEntry(
    val keys: String,
    val label: String,
    val group: DesktopPlayerShortcutGroup,
)

internal fun resolveDesktopPlayerShortcut(
    input: DesktopPlayerKeyInput,
    context: DesktopPlayerShortcutContext = DesktopPlayerShortcutContext(),
): DesktopPlayerShortcutCommand? {
    if (input.key == "Escape" || input.code == "Escape") {
        if (input.ctrl || input.meta || input.alt) return null
        return if (context.isConfirmDialogOpen || context.hasOpenModal) {
            DesktopPlayerShortcutCommand.CLOSE_OVERLAY
        } else {
            DesktopPlayerShortcutCommand.EXIT_FULLSCREEN_OR_BACK
        }
    }

    if (context.hasPlaybackError) return null
    if (input.isTextInputFocused) return null

    val isMacFullscreenShortcut =
        input.code == "KeyF" && input.meta && input.ctrl && !input.alt
    val isPlainFullscreenShortcut =
        input.code == "KeyF" && !input.meta && !input.ctrl && !input.alt
    if (input.code == "F11" || isMacFullscreenShortcut || isPlainFullscreenShortcut) {
        return DesktopPlayerShortcutCommand.TOGGLE_FULLSCREEN
    }

    if (input.meta || input.ctrl || input.alt) return null

    val isQuestionMark = input.key == "?" || (input.code == "Slash" && input.shift)
    if (isQuestionMark) {
        return if (context.hasOpenModal && context.activeModal == "shortcuts") {
            DesktopPlayerShortcutCommand.CLOSE_OVERLAY
        } else {
            DesktopPlayerShortcutCommand.TOGGLE_SHORTCUTS_OVERLAY
        }
    }

    when (input.code) {
        "Backquote" -> return DesktopPlayerShortcutCommand.TOGGLE_SPEED_MODAL
        "KeyA" -> return DesktopPlayerShortcutCommand.TOGGLE_AUDIO_MODAL
        "KeyC" -> return DesktopPlayerShortcutCommand.TOGGLE_SUBTITLES_MODAL
        "KeyE" -> return DesktopPlayerShortcutCommand.TOGGLE_EPISODES_MODAL
        "KeyQ" -> return DesktopPlayerShortcutCommand.TOGGLE_SOURCES_MODAL
    }

    if (context.hasOpenModal) return null

    return when (input.code) {
        "Space", "KeyK" -> DesktopPlayerShortcutCommand.PLAY_PAUSE
        "ArrowLeft", "KeyJ" -> if (input.shift) {
            DesktopPlayerShortcutCommand.FINE_SEEK_BACK_1S
        } else {
            DesktopPlayerShortcutCommand.SEEK_BACK_10S
        }
        "ArrowRight", "KeyL" -> if (input.shift) {
            DesktopPlayerShortcutCommand.FINE_SEEK_FORWARD_1S
        } else {
            DesktopPlayerShortcutCommand.SEEK_FORWARD_10S
        }
        "ArrowUp" -> DesktopPlayerShortcutCommand.VOLUME_UP
        "ArrowDown" -> DesktopPlayerShortcutCommand.VOLUME_DOWN
        "KeyM" -> DesktopPlayerShortcutCommand.TOGGLE_MUTE
        "KeyS" -> if (context.skipPromptVisible) {
            DesktopPlayerShortcutCommand.SKIP_INTRO
        } else {
            null
        }
        "KeyN" -> if (context.hasNextEpisode) {
            DesktopPlayerShortcutCommand.NEXT_EPISODE
        } else {
            null
        }
        "Enter" -> if (context.skipPromptVisible && context.canEnterSkipWithEnter) {
            DesktopPlayerShortcutCommand.SKIP_INTRO
        } else {
            null
        }
        "Slash" -> if (!input.shift) DesktopPlayerShortcutCommand.RESET_SPEED else null
        "Comma" -> if (input.shift) DesktopPlayerShortcutCommand.SPEED_DOWN else null
        "Period" -> if (input.shift) DesktopPlayerShortcutCommand.SPEED_UP else null
        "KeyB" -> DesktopPlayerShortcutCommand.CYCLE_AUDIO
        "KeyV" -> DesktopPlayerShortcutCommand.TOGGLE_SUBTITLE_VISIBILITY
        "KeyG" -> DesktopPlayerShortcutCommand.SUBTITLE_DELAY_EARLIER
        "KeyH" -> DesktopPlayerShortcutCommand.SUBTITLE_DELAY_LATER
        "KeyP" -> DesktopPlayerShortcutCommand.TOGGLE_PIP
        else -> null
    }
}

internal fun defaultDesktopPlayerShortcuts(): List<DesktopPlayerShortcutEntry> = listOf(
    DesktopPlayerShortcutEntry("Space / K", "Play or pause", DesktopPlayerShortcutGroup.PLAYBACK),
    DesktopPlayerShortcutEntry("Left / Right", "Seek back or forward 10 s", DesktopPlayerShortcutGroup.PLAYBACK),
    DesktopPlayerShortcutEntry("Up / Down", "Turn volume up or down", DesktopPlayerShortcutGroup.PLAYBACK),
    DesktopPlayerShortcutEntry("M", "Mute or unmute", DesktopPlayerShortcutGroup.PLAYBACK),
    DesktopPlayerShortcutEntry("F", "Enter or exit fullscreen", DesktopPlayerShortcutGroup.NAVIGATION),
    DesktopPlayerShortcutEntry("Esc", "Exit fullscreen or close panel", DesktopPlayerShortcutGroup.NAVIGATION),
    DesktopPlayerShortcutEntry("S", "Skip intro when shown", DesktopPlayerShortcutGroup.PLAYBACK),
    DesktopPlayerShortcutEntry("N", "Next episode", DesktopPlayerShortcutGroup.NAVIGATION),
    DesktopPlayerShortcutEntry("?", "Show or hide shortcuts", DesktopPlayerShortcutGroup.NAVIGATION),
    DesktopPlayerShortcutEntry("C", "Subtitles", DesktopPlayerShortcutGroup.NAVIGATION),
    DesktopPlayerShortcutEntry("A", "Audio tracks", DesktopPlayerShortcutGroup.NAVIGATION),
    DesktopPlayerShortcutEntry("`", "Playback speed", DesktopPlayerShortcutGroup.PLAYBACK),
    DesktopPlayerShortcutEntry("/", "Reset speed to 1x", DesktopPlayerShortcutGroup.PLAYBACK),
    DesktopPlayerShortcutEntry("E", "Episodes", DesktopPlayerShortcutGroup.NAVIGATION),
    DesktopPlayerShortcutEntry("Q", "Sources", DesktopPlayerShortcutGroup.NAVIGATION),
    DesktopPlayerShortcutEntry("P", "Picture-in-Picture", DesktopPlayerShortcutGroup.NAVIGATION),
)

internal fun List<DesktopPlayerShortcutEntry>.toControlShortcutItems(): List<PlayerControlShortcutItem> =
    map { entry ->
        PlayerControlShortcutItem(
            keys = entry.keys,
            label = entry.label,
            group = when (entry.group) {
                DesktopPlayerShortcutGroup.PLAYBACK -> "playback"
                DesktopPlayerShortcutGroup.NAVIGATION -> "navigation"
            },
        )
    }
