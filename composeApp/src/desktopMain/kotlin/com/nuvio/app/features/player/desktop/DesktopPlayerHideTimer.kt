package com.nuvio.app.features.player.desktop

import javax.swing.Timer

internal const val DESKTOP_PLAYER_HIDE_DELAY_MS: Long = 3_000L

internal interface DesktopScheduledHandle {
    fun cancel()
}

internal fun interface DesktopTimerScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): DesktopScheduledHandle
}

internal object SwingDesktopTimerScheduler : DesktopTimerScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): DesktopScheduledHandle {
        val clampedDelay = delayMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val timer = Timer(clampedDelay) { action() }.apply {
            isRepeats = false
            start()
        }
        return object : DesktopScheduledHandle {
            override fun cancel() {
                timer.stop()
            }
        }
    }
}

internal data class DesktopPlayerHideState(
    val controlsVisible: Boolean = true,
    val cursorVisible: Boolean = true,
    val isPointerDown: Boolean = false,
    val isScrubbing: Boolean = false,
    val isModalOpen: Boolean = false,
    val isTextInputFocused: Boolean = false,
    val isLoading: Boolean = false,
    val isOpeningOverlay: Boolean = false,
    val hasError: Boolean = false,
    val isLocked: Boolean = false,
    val isPlaying: Boolean = false,
)

/**
 * Coordinates desktop controls and pointer visibility so idle playback hides
 * both after [hideDelayMs] without mouse movement while avoiding states that
 * leave chrome or the cursor stuck visible or stuck hidden.
 */
internal class DesktopPlayerHideTimer(
    private val hideDelayMs: Long = DESKTOP_PLAYER_HIDE_DELAY_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val scheduler: DesktopTimerScheduler = SwingDesktopTimerScheduler,
    private val onVisibilityChanged: (DesktopPlayerHideState) -> Unit = {},
) {
    private var state = DesktopPlayerHideState()
    private var scheduledHandle: DesktopScheduledHandle? = null
    private var scheduledGeneration: Long = 0L
    private var lastMouseMoveAtMs: Long = nowMs()
    private var cursorTemporarilyVisible: Boolean = false

    fun currentState(): DesktopPlayerHideState = state

    fun onMouseMoved() {
        lastMouseMoveAtMs = nowMs()
        if (state.isLocked || state.hasError) {
            if (state.isLocked && !state.hasError) {
                cursorTemporarilyVisible = true
                updateVisibilityAndTimer()
            } else {
                updateVisibilityAndTimer()
            }
            return
        }
        cursorTemporarilyVisible = false
        updateState(state.copy(controlsVisible = true, cursorVisible = true))
        updateVisibilityAndTimer()
    }

    fun onControlsShown() {
        lastMouseMoveAtMs = nowMs()
        if (state.isLocked) {
            updateVisibilityAndTimer()
            return
        }
        cursorTemporarilyVisible = false
        updateState(state.copy(controlsVisible = true, cursorVisible = true))
        updateVisibilityAndTimer()
    }

    fun onControlsHidden() {
        cancelTimer()
        cursorTemporarilyVisible = false
        val nextCursorVisible = computeCursorVisible(state.copy(controlsVisible = false), cursorTemporarilyVisible)
        updateState(state.copy(controlsVisible = false, cursorVisible = nextCursorVisible))
    }

    fun onInteractionStarted() {
        lastMouseMoveAtMs = nowMs()
        cursorTemporarilyVisible = false
        val next = if (state.isLocked) {
            state.copy(isPointerDown = true, cursorVisible = true)
        } else {
            state.copy(isPointerDown = true, controlsVisible = true, cursorVisible = true)
        }
        updateState(next)
        cancelTimer()
    }

    fun onInteractionEnded() {
        lastMouseMoveAtMs = nowMs()
        updateState(state.copy(isPointerDown = false))
        updateVisibilityAndTimer()
    }

    fun onScrubbingChanged(scrubbing: Boolean) {
        lastMouseMoveAtMs = nowMs()
        val next = if (scrubbing && !state.isLocked) {
            state.copy(isScrubbing = true, controlsVisible = true, cursorVisible = true)
        } else {
            state.copy(isScrubbing = scrubbing)
        }
        updateState(next)
        updateVisibilityAndTimer()
    }

    fun onModalOpenChanged(open: Boolean) {
        lastMouseMoveAtMs = nowMs()
        val next = if (open) {
            cursorTemporarilyVisible = false
            state.copy(isModalOpen = true, cursorVisible = true)
        } else {
            state.copy(isModalOpen = false)
        }
        updateState(next)
        updateVisibilityAndTimer()
    }

    fun onTextInputFocusChanged(focused: Boolean) {
        lastMouseMoveAtMs = nowMs()
        val next = if (focused) {
            state.copy(isTextInputFocused = true, cursorVisible = true)
        } else {
            state.copy(isTextInputFocused = false)
        }
        updateState(next)
        updateVisibilityAndTimer()
    }

    fun onPlaybackStateChanged(
        isPlaying: Boolean = state.isPlaying,
        isLoading: Boolean = state.isLoading,
        isOpeningOverlay: Boolean = state.isOpeningOverlay,
        hasError: Boolean = state.hasError,
    ) {
        val prevError = state.hasError
        val next = state.copy(
            isPlaying = isPlaying,
            isLoading = isLoading,
            isOpeningOverlay = isOpeningOverlay,
            hasError = hasError,
        )
        updateState(next)
        if (!prevError && hasError) {
            lastMouseMoveAtMs = nowMs()
        }
        updateVisibilityAndTimer()
    }

    fun onLockedChanged(locked: Boolean) {
        if (state.isLocked == locked) return
        if (locked) {
            cursorTemporarilyVisible = false
            updateState(
                state.copy(
                    isLocked = true,
                    controlsVisible = false,
                    isPointerDown = false,
                    isScrubbing = false,
                ),
            )
            updateVisibilityAndTimer()
        } else {
            lastMouseMoveAtMs = nowMs()
            cursorTemporarilyVisible = false
            updateState(state.copy(isLocked = false, controlsVisible = true, cursorVisible = true))
            restartHideTimer()
        }
    }

    fun onWindowFocusLost() {
        cursorTemporarilyVisible = false
        updateState(
            state.copy(
                isPointerDown = false,
                isScrubbing = false,
                isTextInputFocused = false,
            ),
        )
        updateVisibilityAndTimer()
    }

    fun dispose() {
        cancelTimer()
        cursorTemporarilyVisible = false
        updateState(state.copy(isPointerDown = false, isScrubbing = false, cursorVisible = true))
    }

    private fun updateVisibilityAndTimer() {
        val cursorVisible = computeCursorVisible(state, cursorTemporarilyVisible)
        updateState(state.copy(cursorVisible = cursorVisible))

        val shouldRunTimer = when {
            state.hasError -> false
            state.isLocked -> cursorTemporarilyVisible && !state.isModalOpen && !state.isLoading && !state.isOpeningOverlay
            else -> canAutoHideControls(state)
        }

        if (shouldRunTimer) {
            val remainingMs = (hideDelayMs - (nowMs() - lastMouseMoveAtMs)).coerceAtLeast(1L)
            restartHideTimer(remainingMs)
        } else {
            cancelTimer()
        }
    }

    private fun restartHideTimer(delayMs: Long = hideDelayMs) {
        cancelTimer()
        val generation = ++scheduledGeneration
        scheduledHandle = scheduler.schedule(delayMs) {
            onTimeoutFired(generation)
        }
    }

    private fun onTimeoutFired(generation: Long) {
        if (generation != scheduledGeneration) return
        scheduledHandle = null
        val elapsed = nowMs() - lastMouseMoveAtMs
        if (elapsed < hideDelayMs) {
            updateVisibilityAndTimer()
            return
        }
        if (state.isLocked) {
            cursorTemporarilyVisible = false
            val nextCursorVisible = computeCursorVisible(state, cursorTemporarilyVisible)
            updateState(state.copy(controlsVisible = false, cursorVisible = nextCursorVisible))
            return
        }
        if (!canAutoHideControls(state)) {
            updateVisibilityAndTimer()
            return
        }
        cursorTemporarilyVisible = false
        val next = state.copy(controlsVisible = false)
        updateState(next.copy(cursorVisible = computeCursorVisible(next, cursorTemporarilyVisible = false)))
    }

    private fun cancelTimer() {
        scheduledGeneration += 1
        scheduledHandle?.cancel()
        scheduledHandle = null
    }

    private fun updateState(next: DesktopPlayerHideState) {
        if (next == state) return
        state = next
        onVisibilityChanged(next)
    }

    companion object {
        fun canAutoHideControls(state: DesktopPlayerHideState): Boolean =
            state.controlsVisible &&
                !state.isPointerDown &&
                !state.isScrubbing &&
                !state.isModalOpen &&
                !state.isTextInputFocused &&
                !state.isLoading &&
                !state.isOpeningOverlay &&
                !state.hasError &&
                !state.isLocked

        fun computeCursorVisible(
            state: DesktopPlayerHideState,
            cursorTemporarilyVisible: Boolean = false,
        ): Boolean =
            state.hasError ||
                state.isModalOpen ||
                state.isLoading ||
                state.isOpeningOverlay ||
                state.controlsVisible ||
                state.isPointerDown ||
                state.isScrubbing ||
                state.isTextInputFocused ||
                cursorTemporarilyVisible

        fun computeDesktopCursorVisible(
            controlsVisible: Boolean,
            hasModal: Boolean = false,
            hasError: Boolean = false,
            isLoading: Boolean = false,
        ): Boolean = controlsVisible || hasModal || hasError || isLoading
    }
}
