package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeClockScheduler : DesktopTimerScheduler {
    var nowMs: Long = 10_000L
        private set

    private data class ScheduledTask(
        val id: Long,
        val runAtMs: Long,
        var cancelled: Boolean = false,
        val action: () -> Unit,
    )

    private var nextId = 1L
    private val tasks = mutableListOf<ScheduledTask>()

    override fun schedule(delayMs: Long, action: () -> Unit): DesktopScheduledHandle {
        val task = ScheduledTask(
            id = nextId++,
            runAtMs = nowMs + delayMs.coerceAtLeast(1L),
            action = action,
        )
        tasks += task
        return object : DesktopScheduledHandle {
            override fun cancel() {
                task.cancelled = true
            }
        }
    }

    fun advanceBy(ms: Long) {
        val target = nowMs + ms
        while (true) {
            val next = tasks
                .filter { !it.cancelled && it.runAtMs <= target }
                .minByOrNull { it.runAtMs }
                ?: break
            tasks.remove(next)
            nowMs = next.runAtMs
            next.action()
        }
        nowMs = target
    }

    fun activeTaskCount(): Int = tasks.count { !it.cancelled }
}

class DesktopPlayerHideTimerTest {
    @Test
    fun `hide delay is 3000 ms and hides both controls and cursor cleanly`() {
        val clock = FakeClockScheduler()
        val timer = DesktopPlayerHideTimer(
            nowMs = { clock.nowMs },
            scheduler = clock,
        )
        timer.onPlaybackStateChanged(isPlaying = true, isLoading = false, isOpeningOverlay = false, hasError = false)
        timer.onMouseMoved()

        assertTrue(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)
        assertEquals(3_000L, DESKTOP_PLAYER_HIDE_DELAY_MS)

        clock.advanceBy(2_999L)
        assertTrue(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)

        clock.advanceBy(1L)
        assertFalse(timer.currentState().controlsVisible)
        assertFalse(timer.currentState().cursorVisible)
    }

    @Test
    fun `mouse movement resets the 3 second timer and wakes hidden controls and cursor`() {
        val clock = FakeClockScheduler()
        val timer = DesktopPlayerHideTimer(
            nowMs = { clock.nowMs },
            scheduler = clock,
        )
        timer.onPlaybackStateChanged(isPlaying = true)
        timer.onMouseMoved()

        clock.advanceBy(2_500L)
        assertTrue(timer.currentState().controlsVisible)
        timer.onMouseMoved()

        clock.advanceBy(2_500L)
        assertTrue(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)

        clock.advanceBy(500L)
        assertFalse(timer.currentState().controlsVisible)
        assertFalse(timer.currentState().cursorVisible)

        timer.onMouseMoved()
        assertTrue(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)

        clock.advanceBy(3_000L)
        assertFalse(timer.currentState().controlsVisible)
        assertFalse(timer.currentState().cursorVisible)
    }

    @Test
    fun `controls and cursor also hide after 3 seconds when paused unless blocked`() {
        val clock = FakeClockScheduler()
        val timer = DesktopPlayerHideTimer(
            nowMs = { clock.nowMs },
            scheduler = clock,
        )
        timer.onPlaybackStateChanged(isPlaying = false, isLoading = false, hasError = false)
        timer.onMouseMoved()

        clock.advanceBy(3_000L)
        assertFalse(timer.currentState().controlsVisible)
        assertFalse(timer.currentState().cursorVisible)
    }

    @Test
    fun `pointer interaction and scrubbing prevent hiding then resume cleanly on release`() {
        val clock = FakeClockScheduler()
        val timer = DesktopPlayerHideTimer(
            nowMs = { clock.nowMs },
            scheduler = clock,
        )
        timer.onPlaybackStateChanged(isPlaying = true)
        timer.onInteractionStarted()
        clock.advanceBy(5_000L)
        assertTrue(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)

        // Mouse jitter during an active pointer press must not schedule a hide timer either.
        timer.onMouseMoved()
        assertEquals(0, clock.activeTaskCount())
        clock.advanceBy(5_000L)
        assertTrue(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)

        timer.onInteractionEnded()
        clock.advanceBy(2_999L)
        assertTrue(timer.currentState().controlsVisible)
        clock.advanceBy(1L)
        assertFalse(timer.currentState().controlsVisible)
        assertFalse(timer.currentState().cursorVisible)

        timer.onScrubbingChanged(true)
        clock.advanceBy(5_000L)
        assertTrue(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)

        timer.onScrubbingChanged(false)
        clock.advanceBy(3_000L)
        assertFalse(timer.currentState().controlsVisible)
        assertFalse(timer.currentState().cursorVisible)
    }

    @Test
    fun `window focus loss unsticks trapped pointer or scrub state so chrome cannot get stuck visible`() {
        val clock = FakeClockScheduler()
        val timer = DesktopPlayerHideTimer(
            nowMs = { clock.nowMs },
            scheduler = clock,
        )
        timer.onPlaybackStateChanged(isPlaying = true)
        timer.onInteractionStarted()
        timer.onScrubbingChanged(true)
        timer.onTextInputFocusChanged(true)
        clock.advanceBy(4_000L)
        assertTrue(timer.currentState().controlsVisible)

        // Simulate window deactivated without receiving pointerup/change.
        timer.onWindowFocusLost()
        assertFalse(timer.currentState().isPointerDown)
        assertFalse(timer.currentState().isScrubbing)
        assertFalse(timer.currentState().isTextInputFocused)

        clock.advanceBy(3_000L)
        assertFalse(timer.currentState().controlsVisible)
        assertFalse(timer.currentState().cursorVisible)
    }

    @Test
    fun `modals and errors keep cursor visible even when controls were hidden and resume cleanly on close`() {
        val clock = FakeClockScheduler()
        val timer = DesktopPlayerHideTimer(
            nowMs = { clock.nowMs },
            scheduler = clock,
        )
        timer.onPlaybackStateChanged(isPlaying = true)
        timer.onMouseMoved()
        clock.advanceBy(3_000L)
        assertFalse(timer.currentState().controlsVisible)
        assertFalse(timer.currentState().cursorVisible)

        timer.onModalOpenChanged(true)
        assertTrue(timer.currentState().cursorVisible)
        clock.advanceBy(10_000L)
        assertTrue(timer.currentState().cursorVisible)

        timer.onModalOpenChanged(false)
        assertFalse(timer.currentState().cursorVisible)

        timer.onMouseMoved()
        assertTrue(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)

        timer.onPlaybackStateChanged(hasError = true)
        timer.onControlsHidden()
        assertTrue(timer.currentState().cursorVisible)
    }

    @Test
    fun `locked mode hides controls and auto-hides awakened cursor after 3 seconds`() {
        val clock = FakeClockScheduler()
        val timer = DesktopPlayerHideTimer(
            nowMs = { clock.nowMs },
            scheduler = clock,
        )
        timer.onPlaybackStateChanged(isPlaying = true)
        timer.onLockedChanged(true)
        assertFalse(timer.currentState().controlsVisible)

        timer.onMouseMoved()
        assertFalse(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)

        clock.advanceBy(2_999L)
        assertTrue(timer.currentState().cursorVisible)

        clock.advanceBy(1L)
        assertFalse(timer.currentState().cursorVisible)

        timer.onLockedChanged(false)
        assertTrue(timer.currentState().controlsVisible)
        assertTrue(timer.currentState().cursorVisible)
    }

    @Test
    fun `dispose cancels pending timers and restores cursor visibility`() {
        val clock = FakeClockScheduler()
        val timer = DesktopPlayerHideTimer(
            nowMs = { clock.nowMs },
            scheduler = clock,
        )
        timer.onPlaybackStateChanged(isPlaying = true)
        timer.onMouseMoved()
        clock.advanceBy(3_000L)
        assertFalse(timer.currentState().cursorVisible)

        timer.onMouseMoved()
        assertTrue(clock.activeTaskCount() > 0)
        timer.dispose()
        assertEquals(0, clock.activeTaskCount())
        assertTrue(timer.currentState().cursorVisible)
    }
}
