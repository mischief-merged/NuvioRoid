package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerControlsState
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class TestTimerScheduler : DesktopTimerScheduler {
    var nowMs: Long = 10_000L
        private set

    private data class ScheduledTask(
        val runAtMs: Long,
        var cancelled: Boolean = false,
        val action: () -> Unit,
    )

    private val tasks = mutableListOf<ScheduledTask>()

    override fun schedule(delayMs: Long, action: () -> Unit): DesktopScheduledHandle {
        val task = ScheduledTask(
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

class NativePlayerControllerFallbackHideTimerTest {
    @Test
    fun `fallback hide timer keeps controls visible during active chrome button press and hides after release`() {
        val clock = TestTimerScheduler()
        val host = NativePlayerHost()
        val cursorVisibility = mutableListOf<Boolean>()
        val controller = NativePlayerController(
            host = host,
            nativeCreate = { _, _, _, _, _, _, _, _, _ -> error("unused") },
            nativeDispose = {},
            nativeUpdateControls = { _, _ -> },
            hideTimerNowMs = { clock.nowMs },
            hideTimerScheduler = clock,
            setHostControlsVisible = { cursorVisibility += it },
        )
        controller.setNativeHandleForTest(42L)
        controller.setControlCallbacks(
            onAction = { false },
            onEvent = { _, _ -> false },
            onScrubChange = { false },
            onScrubFinished = { false },
        )

        // Simulate pointerdown on a toolbar button (e.g. TrailerPlayer where onEvent returns false).
        controller.handlePlayerEventForTest("chromeInteraction", 1.0)
        controller.handlePlayerEventForTest("keepChromeVisible", 1.0)

        clock.advanceBy(5_000L)
        SwingUtilities.invokeAndWait {}

        assertTrue(controller.controlsStateForTest().controlsVisible)
        assertTrue(controller.fallbackHideStateForTest()?.isPointerDown == true)
        assertTrue(cursorVisibility.last())
        assertEquals(0, clock.activeTaskCount())

        // Even if cursorActivity arrives during the press, it must not schedule a premature hide.
        controller.handlePlayerEventForTest("cursorActivity", 0.0)
        clock.advanceBy(5_000L)
        SwingUtilities.invokeAndWait {}
        assertTrue(controller.controlsStateForTest().controlsVisible)

        // Releasing the button resumes the 3 second auto-hide timer.
        controller.handlePlayerEventForTest("chromeInteraction", 0.0)
        controller.handlePlayerEventForTest("keepChromeVisible", 0.0)

        clock.advanceBy(2_999L)
        SwingUtilities.invokeAndWait {}
        assertTrue(controller.controlsStateForTest().controlsVisible)

        clock.advanceBy(1L)
        SwingUtilities.invokeAndWait {}
        assertFalse(controller.controlsStateForTest().controlsVisible)
        assertFalse(cursorVisibility.last())
    }

    @Test
    fun `keepChromeVisible with interaction value 1 alone also prevents fallback hide while held`() {
        val clock = TestTimerScheduler()
        val host = NativePlayerHost()
        val cursorVisibility = mutableListOf<Boolean>()
        val controller = NativePlayerController(
            host = host,
            nativeCreate = { _, _, _, _, _, _, _, _, _ -> error("unused") },
            nativeDispose = {},
            nativeUpdateControls = { _, _ -> },
            hideTimerNowMs = { clock.nowMs },
            hideTimerScheduler = clock,
            setHostControlsVisible = { cursorVisibility += it },
        )
        controller.setNativeHandleForTest(42L)
        controller.setControlCallbacks(
            onAction = { false },
            onEvent = { _, _ -> false },
            onScrubChange = { false },
            onScrubFinished = { false },
        )

        controller.handlePlayerEventForTest("keepChromeVisible", 1.0)
        clock.advanceBy(5_000L)
        SwingUtilities.invokeAndWait {}
        assertTrue(controller.controlsStateForTest().controlsVisible)

        controller.handlePlayerEventForTest("keepChromeVisible", 0.0)
        clock.advanceBy(3_000L)
        SwingUtilities.invokeAndWait {}
        assertFalse(controller.controlsStateForTest().controlsVisible)
        assertFalse(cursorVisibility.last())
    }
}

private fun NativePlayerController.setNativeHandleForTest(value: Long) {
    javaClass.getDeclaredField("handle").also { field ->
        field.isAccessible = true
        field.setLong(this, value)
    }
}

private fun NativePlayerController.handlePlayerEventForTest(type: String, value: Double) {
    javaClass.getDeclaredMethod("handlePlayerEvent", String::class.java, Double::class.javaPrimitiveType).also { method ->
        method.isAccessible = true
        method.invoke(this, type, value)
    }
}

private fun NativePlayerController.controlsStateForTest(): PlayerControlsState =
    javaClass.getDeclaredField("controlsState").let { field ->
        field.isAccessible = true
        field.get(this) as PlayerControlsState
    }

private fun NativePlayerController.fallbackHideStateForTest(): DesktopPlayerHideState? =
    javaClass.getDeclaredField("fallbackHideTimer").let { field ->
        field.isAccessible = true
        (field.get(this) as? DesktopPlayerHideTimer)?.currentState()
    }
