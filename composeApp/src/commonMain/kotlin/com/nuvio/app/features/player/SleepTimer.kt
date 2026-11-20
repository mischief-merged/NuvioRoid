package com.nuvio.app.features.player

import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.watching.domain.isProgressComplete

/** How long the audio takes to fade out once a sleep timer fires. */
internal const val SleepTimerFadeDurationMs = 10_000L

/** Playback must move this far past a sleep stop before the episode can count as watched again. */
internal const val SleepTimerResumeReleaseMs = 2_000L

internal enum class SleepTimerOption(val wire: String, val durationMs: Long?) {
    Off("off", null),
    Minutes15("15", 15 * 60_000L),
    Minutes30("30", 30 * 60_000L),
    Minutes45("45", 45 * 60_000L),
    Minutes60("60", 60 * 60_000L),
    Minutes90("90", 90 * 60_000L),
    EndOfEpisode("end", null),
}

internal fun sleepTimerOptionFromControl(value: Double): SleepTimerOption? = when (value.toInt()) {
    0 -> SleepTimerOption.Off
    15 -> SleepTimerOption.Minutes15
    30 -> SleepTimerOption.Minutes30
    45 -> SleepTimerOption.Minutes45
    60 -> SleepTimerOption.Minutes60
    90 -> SleepTimerOption.Minutes90
    -1 -> SleepTimerOption.EndOfEpisode
    else -> null
}

internal enum class SleepTimerPhase {
    Idle,
    Armed,
    Fading,
    Stopped,
}

internal data class SleepTimerState(
    val option: SleepTimerOption = SleepTimerOption.Off,
    val phase: SleepTimerPhase = SleepTimerPhase.Idle,
    val deadlineEpochMs: Long? = null,
    val fadeStartedEpochMs: Long? = null,
    val fadeDurationMs: Long = SleepTimerFadeDurationMs,
    val stopAfterEpisode: Boolean = false,
    val holdIncomplete: Boolean = false,
    val holdPositionMs: Long? = null,
)

internal data class SleepTimerStep(
    val state: SleepTimerState,
    /** 1 is the listener's own volume. Null leaves the volume alone. */
    val volumeFraction: Float?,
    val pause: Boolean,
    val saveProgress: Boolean,
    val remainingMs: Long?,
)

internal fun SleepTimerState.selectOption(option: SleepTimerOption, nowEpochMs: Long): SleepTimerState {
    if (option == SleepTimerOption.Off || option == this.option) {
        val cancellingFade = phase == SleepTimerPhase.Fading
        return copy(
            option = SleepTimerOption.Off,
            phase = if (phase == SleepTimerPhase.Stopped) SleepTimerPhase.Stopped else SleepTimerPhase.Idle,
            deadlineEpochMs = null,
            fadeStartedEpochMs = null,
            holdIncomplete = if (cancellingFade) false else holdIncomplete,
            holdPositionMs = if (cancellingFade) null else holdPositionMs,
        )
    }
    // A fade that has not paused yet is not a finished stop. A new choice drops that hold.
    // A hold left by a pause that already happened stays until playback moves past it.
    val fadeNotStopped = phase == SleepTimerPhase.Fading && holdPositionMs == null
    return copy(
        option = option,
        phase = SleepTimerPhase.Armed,
        deadlineEpochMs = option.durationMs?.let { nowEpochMs + it },
        fadeStartedEpochMs = null,
        fadeDurationMs = SleepTimerFadeDurationMs,
        holdIncomplete = if (fadeNotStopped) false else holdIncomplete,
        holdPositionMs = if (fadeNotStopped) null else holdPositionMs,
    )
}

internal fun SleepTimerState.toggleStopAfterEpisode(): SleepTimerState =
    copy(stopAfterEpisode = !stopAfterEpisode)

/** A fired timer and "stop after this episode" apply to one episode. */
internal fun SleepTimerState.onEpisodeChanged(): SleepTimerState {
    val fired = phase == SleepTimerPhase.Fading || phase == SleepTimerPhase.Stopped
    return copy(
        stopAfterEpisode = false,
        option = if (fired) SleepTimerOption.Off else option,
        phase = if (fired) SleepTimerPhase.Idle else phase,
        deadlineEpochMs = if (fired) null else deadlineEpochMs,
        fadeStartedEpochMs = null,
        holdIncomplete = false,
        holdPositionMs = null,
    )
}

internal fun SleepTimerState.blocksAutomaticNextEpisode(): Boolean =
    stopAfterEpisode ||
        option == SleepTimerOption.EndOfEpisode ||
        phase == SleepTimerPhase.Fading ||
        phase == SleepTimerPhase.Stopped

internal fun SleepTimerState.holdsProgressIncomplete(positionMs: Long, isPlaying: Boolean): Boolean {
    if (!holdIncomplete) return false
    val heldAt = holdPositionMs ?: return true
    if (isPlaying && positionMs > heldAt + SleepTimerResumeReleaseMs) return false
    return true
}

/**
 * True while a sleep stop must not mark the episode watched or send a completion scrobble.
 * An armed "end of episode" timer holds the whole play, so crossing the usual watched line
 * does not finish the episode before the fade. A minute timer does the same once playback
 * is on that line and the timer will pause before the episode ends.
 * Moving on to another episode is not that pause. The save for the one being left can
 * count as watched. A fade, an end-of-episode timer, and a stop that already happened still hold.
 */
internal fun SleepTimerState.suppressesCompletion(
    nowEpochMs: Long,
    positionMs: Long,
    durationMs: Long = 0L,
    isPlaying: Boolean,
    leavingEpisode: Boolean = false,
): Boolean {
    if (option == SleepTimerOption.EndOfEpisode || phase == SleepTimerPhase.Fading) return true
    if (holdsProgressIncomplete(positionMs, isPlaying)) return true
    val deadline = deadlineEpochMs ?: return false
    if (phase != SleepTimerPhase.Armed) return false
    if (nowEpochMs >= deadline - SleepTimerFadeDurationMs) return true
    if (leavingEpisode) return false
    return minuteTimerPausesBeforeTheEnd(nowEpochMs, positionMs, durationMs, isPlaying)
}

internal fun SleepTimerState.step(
    nowEpochMs: Long,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isEnded: Boolean,
): SleepTimerStep {
    val state = releaseHoldIfResumed(positionMs, durationMs, isPlaying)
    if (state.phase == SleepTimerPhase.Fading) {
        return state.stepFade(nowEpochMs, positionMs, durationMs, isEnded)
    }
    if (state.phase != SleepTimerPhase.Armed) {
        return SleepTimerStep(state, null, false, false, null)
    }
    return when (state.option) {
        SleepTimerOption.Off -> SleepTimerStep(
            state.copy(phase = SleepTimerPhase.Idle),
            null,
            false,
            false,
            null,
        )
        SleepTimerOption.EndOfEpisode -> state.stepEndOfEpisode(
            nowEpochMs = nowEpochMs,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = isPlaying,
            isEnded = isEnded,
        )
        else -> state.stepDeadline(
            nowEpochMs = nowEpochMs,
            positionMs = positionMs,
            isPlaying = isPlaying,
        )
    }
}

internal fun sleepTimerFadeFraction(elapsedMs: Long, fadeDurationMs: Long): Float {
    if (fadeDurationMs <= 0L) return 0f
    val elapsed = elapsedMs.coerceIn(0L, fadeDurationMs)
    return (1f - elapsed.toFloat() / fadeDurationMs.toFloat()).coerceIn(0f, 1f)
}

/** Clock text for the quiet countdown. Hours appear only after an hour is left. */
internal fun formatSleepTimerClock(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    val minuteText = minutes.toString().padStart(if (hours > 0L) 2 else 1, '0')
    val secondText = seconds.toString().padStart(2, '0')
    return if (hours > 0L) "$hours:$minuteText:$secondText" else "$minuteText:$secondText"
}

internal fun scrobbleActionForProgressSave(
    requested: TrackingScrobbleAction,
    holdIncomplete: Boolean,
): TrackingScrobbleAction =
    if (holdIncomplete && requested == TrackingScrobbleAction.STOP) {
        TrackingScrobbleAction.PAUSE
    } else {
        requested
    }

private fun SleepTimerState.releaseHoldIfResumed(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
): SleepTimerState {
    if (!holdIncomplete || !isPlaying) return this
    val heldAt = holdPositionMs ?: return this
    if (positionMs > heldAt + SleepTimerResumeReleaseMs) {
        return copy(holdIncomplete = false, holdPositionMs = null)
    }
    // A stop on the last moments can never be passed by two seconds. Starting
    // again from before the watched line is a new play, so the hold ends.
    // Rewinding inside that line keeps the episode unfinished.
    val releaseIsPastTheEnd = durationMs > 0L && heldAt + SleepTimerResumeReleaseMs >= durationMs
    val restartedBeforeTheWatchedLine = positionMs < heldAt &&
        !isProgressComplete(positionMs, durationMs, isEnded = false)
    if (releaseIsPastTheEnd && restartedBeforeTheWatchedLine) {
        return copy(holdIncomplete = false, holdPositionMs = null)
    }
    return this
}

private fun SleepTimerState.stepEndOfEpisode(
    nowEpochMs: Long,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isEnded: Boolean,
): SleepTimerStep {
    if (isEnded) return stopNow(positionMs)
    val remaining = if (durationMs > 0L) (durationMs - positionMs).coerceAtLeast(0L) else null
    if (remaining != null && remaining <= SleepTimerFadeDurationMs && isPlaying) {
        val fadeFor = remaining.coerceAtLeast(1L)
        return SleepTimerStep(
            copy(
                phase = SleepTimerPhase.Fading,
                fadeStartedEpochMs = nowEpochMs,
                fadeDurationMs = fadeFor,
                holdIncomplete = true,
            ),
            volumeFraction = 1f,
            pause = false,
            saveProgress = false,
            remainingMs = fadeFor,
        )
    }
    return SleepTimerStep(this, null, false, false, remaining)
}

private fun SleepTimerState.stepDeadline(
    nowEpochMs: Long,
    positionMs: Long,
    isPlaying: Boolean,
): SleepTimerStep {
    val deadline = deadlineEpochMs ?: return SleepTimerStep(this, null, false, false, null)
    val fadeStart = deadline - SleepTimerFadeDurationMs
    if (nowEpochMs >= deadline || (nowEpochMs >= fadeStart && !isPlaying)) {
        return stopNow(positionMs)
    }
    if (nowEpochMs >= fadeStart && isPlaying) {
        val fadeFor = (deadline - nowEpochMs).coerceAtLeast(1L)
        return SleepTimerStep(
            copy(
                phase = SleepTimerPhase.Fading,
                fadeStartedEpochMs = nowEpochMs,
                fadeDurationMs = fadeFor,
                holdIncomplete = true,
            ),
            volumeFraction = 1f,
            pause = false,
            saveProgress = false,
            remainingMs = fadeFor,
        )
    }
    return SleepTimerStep(this, null, false, false, (deadline - nowEpochMs).coerceAtLeast(0L))
}

private fun SleepTimerState.stepFade(
    nowEpochMs: Long,
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
): SleepTimerStep {
    if (option == SleepTimerOption.EndOfEpisode) {
        return stepEndOfEpisodeFade(positionMs, durationMs, isEnded)
    }
    val started = fadeStartedEpochMs ?: nowEpochMs
    val duration = fadeDurationMs.coerceAtLeast(1L)
    val elapsed = (nowEpochMs - started).coerceAtLeast(0L)
    if (isEnded || elapsed >= duration) return stopNow(positionMs)
    return SleepTimerStep(
        this,
        volumeFraction = sleepTimerFadeFraction(elapsed, duration),
        pause = false,
        saveProgress = false,
        remainingMs = duration - elapsed,
    )
}

/**
 * The end-of-episode fade follows the time left in the file. A seek backward, a stall,
 * or slow playback must not pause on the wall clock.
 */
private fun SleepTimerState.stepEndOfEpisodeFade(
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
): SleepTimerStep {
    if (isEnded) return stopNow(positionMs)
    val remaining = if (durationMs > 0L) (durationMs - positionMs).coerceAtLeast(0L) else null
    if (remaining == null) {
        return SleepTimerStep(this, null, false, false, null)
    }
    if (remaining > SleepTimerFadeDurationMs) {
        return SleepTimerStep(
            copy(
                phase = SleepTimerPhase.Armed,
                fadeStartedEpochMs = null,
                fadeDurationMs = SleepTimerFadeDurationMs,
                // The fade itself has no saved stop. Dropping that empty hold here
                // means turning the timer off does not keep the episode unfinished forever.
                holdIncomplete = holdPositionMs != null,
            ),
            volumeFraction = 1f,
            pause = false,
            saveProgress = false,
            remainingMs = remaining,
        )
    }
    if (remaining == 0L) return stopNow(positionMs)
    val span = fadeDurationMs.coerceAtLeast(1L)
    val covered = (span - remaining).coerceIn(0L, span)
    return SleepTimerStep(
        this,
        volumeFraction = sleepTimerFadeFraction(covered, span),
        pause = false,
        saveProgress = false,
        remainingMs = remaining,
    )
}

/**
 * True when this armed minute timer will pause before the file ends, and playback is
 * already on the watched line. Earlier saves still sync. Assumes steady 1x playback
 * from here; a pause cannot advance, so the current position is the stop.
 */
private fun SleepTimerState.minuteTimerPausesBeforeTheEnd(
    nowEpochMs: Long,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
): Boolean {
    if (phase != SleepTimerPhase.Armed) return false
    val deadline = deadlineEpochMs ?: return false
    if (option.durationMs == null || durationMs <= 0L) return false
    if (!isProgressComplete(positionMs, durationMs, isEnded = false)) return false
    val wallLeft = (deadline - nowEpochMs).coerceAtLeast(0L)
    val projectedStopMs = if (isPlaying) positionMs + wallLeft else positionMs
    return projectedStopMs < durationMs
}

private fun SleepTimerState.stopNow(positionMs: Long): SleepTimerStep {
    val stopped = copy(
        option = SleepTimerOption.Off,
        phase = SleepTimerPhase.Stopped,
        deadlineEpochMs = null,
        fadeStartedEpochMs = null,
        holdIncomplete = true,
        holdPositionMs = positionMs.coerceAtLeast(0L),
    )
    return SleepTimerStep(
        state = stopped,
        volumeFraction = 1f,
        pause = true,
        saveProgress = true,
        remainingMs = 0L,
    )
}
