package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlayerShortcutsTest {
    @Test
    fun `core playback and volume shortcuts map consistently`() {
        assertEquals(
            DesktopPlayerShortcutCommand.PLAY_PAUSE,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "Space", key = " ")),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.PLAY_PAUSE,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "KeyK", key = "k")),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.SEEK_BACK_10S,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "ArrowLeft", key = "ArrowLeft")),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.SEEK_FORWARD_10S,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "ArrowRight", key = "ArrowRight")),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.FINE_SEEK_BACK_1S,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "ArrowLeft", key = "ArrowLeft", shift = true)),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.FINE_SEEK_FORWARD_1S,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "ArrowRight", key = "ArrowRight", shift = true)),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.VOLUME_UP,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "ArrowUp", key = "ArrowUp")),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.VOLUME_DOWN,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "ArrowDown", key = "ArrowDown")),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.TOGGLE_MUTE,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "KeyM", key = "m")),
        )
    }

    @Test
    fun `fullscreen and escape respect modals, fullscreen, and playback error`() {
        assertEquals(
            DesktopPlayerShortcutCommand.TOGGLE_FULLSCREEN,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "KeyF", key = "f")),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.TOGGLE_FULLSCREEN,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "F11", key = "F11")),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.TOGGLE_FULLSCREEN,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "KeyF", key = "f", meta = true, ctrl = true)),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.EXIT_FULLSCREEN_OR_BACK,
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "Escape", key = "Escape"),
                DesktopPlayerShortcutContext(isFullscreen = true),
            ),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.CLOSE_OVERLAY,
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "Escape", key = "Escape"),
                DesktopPlayerShortcutContext(hasOpenModal = true, activeModal = "shortcuts", isFullscreen = true),
            ),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.EXIT_FULLSCREEN_OR_BACK,
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "Escape", key = "Escape"),
                DesktopPlayerShortcutContext(hasPlaybackError = true),
            ),
        )
        assertNull(
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "Space", key = " "),
                DesktopPlayerShortcutContext(hasPlaybackError = true),
            ),
        )
    }

    @Test
    fun `S skips intro only when shown and does not open subtitles`() {
        assertEquals(
            DesktopPlayerShortcutCommand.SKIP_INTRO,
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "KeyS", key = "s"),
                DesktopPlayerShortcutContext(skipPromptVisible = true),
            ),
        )
        assertNull(
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "KeyS", key = "s"),
                DesktopPlayerShortcutContext(skipPromptVisible = false),
            ),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.TOGGLE_SUBTITLES_MODAL,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "KeyC", key = "c")),
        )
    }

    @Test
    fun `N triggers next episode with or without shift when available`() {
        assertEquals(
            DesktopPlayerShortcutCommand.NEXT_EPISODE,
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "KeyN", key = "n"),
                DesktopPlayerShortcutContext(hasNextEpisode = true),
            ),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.NEXT_EPISODE,
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "KeyN", key = "N", shift = true),
                DesktopPlayerShortcutContext(hasNextEpisode = true),
            ),
        )
        assertNull(
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "KeyN", key = "n"),
                DesktopPlayerShortcutContext(hasNextEpisode = false),
            ),
        )
    }

    @Test
    fun `question mark toggles shortcuts overlay without colliding with slash speed reset`() {
        assertEquals(
            DesktopPlayerShortcutCommand.TOGGLE_SHORTCUTS_OVERLAY,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "Slash", key = "?", shift = true)),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.TOGGLE_SHORTCUTS_OVERLAY,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "KeyQuote", key = "?")),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.CLOSE_OVERLAY,
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "Slash", key = "?", shift = true),
                DesktopPlayerShortcutContext(hasOpenModal = true, activeModal = "shortcuts"),
            ),
        )
        assertEquals(
            DesktopPlayerShortcutCommand.RESET_SPEED,
            resolveDesktopPlayerShortcut(DesktopPlayerKeyInput(code = "Slash", key = "/")),
        )
    }

    @Test
    fun `text inputs, modifiers, and open modals guard playback shortcuts`() {
        assertNull(
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "Space", key = " ", isTextInputFocused = true),
            ),
        )
        assertNull(
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "Slash", key = "?", isTextInputFocused = true),
            ),
        )
        assertNull(
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "KeyN", key = "n", meta = true),
            ),
        )
        assertNull(
            resolveDesktopPlayerShortcut(
                DesktopPlayerKeyInput(code = "KeyM", key = "m"),
                DesktopPlayerShortcutContext(hasOpenModal = true, activeModal = "audio"),
            ),
        )
    }

    @Test
    fun `default shortcut overlay catalog lists every primary desktop shortcut`() {
        val entries = defaultDesktopPlayerShortcuts()
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.keys.isNotBlank() && it.label.isNotBlank() })

        val keyBindings = entries.map { it.keys }
        listOf("Space / K", "Left / Right", "Up / Down", "M", "F", "Esc", "S", "N", "?").forEach { expected ->
            assertTrue(expected in keyBindings, "Missing shortcut entry for $expected")
        }
    }
}
