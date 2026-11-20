package com.nuvio.app.features.player

import com.nuvio.app.features.tracking.TrackingScrobbleAction

/** True while progress must stay unfinished: no watched mark and no completion scrobble. */
internal fun PlayerScreenRuntime.playbackHoldIncomplete(
    nowEpochMs: Long = System.currentTimeMillis(),
    leavingEpisode: Boolean = false,
): Boolean = sleepTimer.suppressesCompletion(
    nowEpochMs = nowEpochMs,
    positionMs = playbackSnapshot.positionMs,
    durationMs = playbackSnapshot.durationMs,
    isPlaying = shouldPlay || playbackSnapshot.isPlaying,
    leavingEpisode = leavingEpisode,
)

internal fun PlayerScreenRuntime.noteSleepTimerEpisode() {
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    val key = "$videoId:$activeSeasonNumber:$activeEpisodeNumber"
    val previous = lastSleepTimerEpisodeKey
    if (previous == null) {
        lastSleepTimerEpisodeKey = key
        return
    }
    if (previous == key) return
    lastSleepTimerEpisodeKey = key
    val wasFading = sleepTimer.phase == SleepTimerPhase.Fading
    sleepTimer = sleepTimer.onEpisodeChanged()
    if (wasFading) {
        playerController?.setVolumeFade(1f)
    }
    if (sleepTimer.phase == SleepTimerPhase.Armed) {
        advanceSleepTimer(System.currentTimeMillis())
    } else {
        sleepTimerRemainingLabel = ""
    }
    if (sleepTimer.blocksAutomaticNextEpisode() && nextEpisodeAutoPlayAutomatic) {
        cancelNextEpisodeAutoPlay()
    }
}

internal fun PlayerScreenRuntime.selectSleepTimerOption(option: SleepTimerOption) {
    val now = System.currentTimeMillis()
    val previous = sleepTimer
    val leavingFade = previous.phase == SleepTimerPhase.Fading
    sleepTimer = previous.selectOption(option, now)
    if (leavingFade) {
        playerController?.setVolumeFade(1f)
    }
    advanceSleepTimer(now)
}

internal fun PlayerScreenRuntime.toggleStopAfterThisEpisode() {
    sleepTimer = sleepTimer.toggleStopAfterEpisode()
    if (sleepTimer.blocksAutomaticNextEpisode() && nextEpisodeAutoPlayAutomatic) {
        cancelNextEpisodeAutoPlay()
    }
}

internal fun PlayerScreenRuntime.advanceSleepTimer(nowEpochMs: Long) {
    val step = sleepTimer.step(
        nowEpochMs = nowEpochMs,
        positionMs = playbackSnapshot.positionMs,
        durationMs = playbackSnapshot.durationMs,
        isPlaying = shouldPlay || playbackSnapshot.isPlaying,
        isEnded = playbackSnapshot.isEnded,
    )
    applySleepTimerStep(step)
}

private fun PlayerScreenRuntime.applySleepTimerStep(step: SleepTimerStep) {
    val wasFading = sleepTimer.phase == SleepTimerPhase.Fading
    if (step.pause && wasFading) {
        // End the fade at silence. The listener's volume goes back after the pause,
        // so the stop does not pop and the next play is not quiet.
        playerController?.setVolumeFade(0f)
    } else if (!step.pause) {
        step.volumeFraction?.let { fraction ->
            playerController?.setVolumeFade(fraction)
        }
    }
    sleepTimer = step.state
    sleepTimerRemainingLabel = step.remainingMs
        ?.takeIf { it > 0L }
        ?.let(::formatSleepTimerClock)
        .orEmpty()
    if (step.pause) {
        shouldPlay = false
        playerController?.pause()
        if (wasFading) {
            playerController?.setVolumeFade(1f)
        }
        if (!isPartyControlLocked) {
            publishPartyPlayback()
        }
    }
    if (step.saveProgress) {
        flushWatchProgress(
            scrobbleAction = TrackingScrobbleAction.PAUSE,
            holdIncomplete = true,
        )
    }
    if (step.state.blocksAutomaticNextEpisode() && nextEpisodeAutoPlayAutomatic) {
        cancelNextEpisodeAutoPlay()
    }
}

/**
 * Shows the Up Next card when the episode is close enough, and starts the next episode
 * only when nothing has asked playback to stop here.
 */
internal fun PlayerScreenRuntime.onNextEpisodeThresholdChanged() {
    val shouldShow = nextEpisodeInfo != null && !nextEpisodeCardDismissed && isAtNextEpisodeThreshold()
    val blocked = sleepTimer.blocksAutomaticNextEpisode()
    if (
        nextEpisodeAutoPlayAutomatic &&
        (!shouldShow || !playerSettingsUiState.streamAutoPlayNextEpisodeEnabled || blocked)
    ) {
        cancelNextEpisodeAutoPlay()
    }
    if (shouldShow && !showNextEpisodeCard) {
        showNextEpisodeCard = true
        if (
            !blocked &&
            playerSettingsUiState.streamAutoPlayNextEpisodeEnabled &&
            nextEpisodeInfo?.hasAired == true &&
            !isPartyControlLocked
        ) {
            playNextEpisode(automatic = true)
        }
    } else if (!shouldShow) {
        showNextEpisodeCard = false
    }
}
