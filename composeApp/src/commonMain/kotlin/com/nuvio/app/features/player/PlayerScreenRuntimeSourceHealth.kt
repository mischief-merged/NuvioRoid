package com.nuvio.app.features.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_source_stalled
import nuvio.composeapp.generated.resources.player_error_source_startup_timeout
import org.jetbrains.compose.resources.getString

private val log = Logger.withTag("PlayerSourceHealth")

private val sourceHealthClock = kotlin.time.TimeSource.Monotonic.markNow()

private fun sourceHealthNowMs(): Long = sourceHealthClock.elapsedNow().inWholeMilliseconds

private fun isDeviceOffline(): Boolean {
    val condition = NetworkStatusRepository.uiState.value.condition
    return condition == NetworkCondition.NoInternet || condition == NetworkCondition.ServersUnreachable
}

internal var sourceHealthTickObserverForTest: (() -> Unit)? = null

private fun PlayerScreenRuntime.shouldMonitorSourceHealthNow(): Boolean {
    if (!shouldPlay) return false
    if (errorMessage != null) return false
    if (!playerSettingsUiState.streamAutoPlayFallbackActive) return false
    if (sourceFallbackJob?.isActive == true) return false
    if (playbackSnapshot.isEnded) return false
    if (args.isMinimized && initialLoadCompleted) return false
    return true
}

/**
 * Watches the source that was just handed to the player until it fails, the user leaves, or it is replaced.
 * One path decides, so the start and the stall never both fire:
 *
 *  1. Until playback has advanced, [SourceStartMonitor] judges the start. Its no-progress deadline is the
 *     user's "time for a source to start" setting. Its clock stands still while the user has paused or the
 *     device is offline.
 *  2. After that, [PlaybackStallDetector] judges a stall: the position standing still while the user wants
 *     playback. It is a failure the planner handles like a source that died, resuming a little before where it stopped.
 *
 * Both read the player's latest snapshot once a second. They apply only while the setting to try the next source
 * is on, and the planner still decides whether a replacement may be tried (offline, the limit, the failed set).
 * Desktop only: other platforms keep the flat start timeout.
 */
internal suspend fun PlayerScreenRuntime.monitorSourceHealth() {
    var startup = SourceStartMonitor.Progress()
    var stall: PlaybackStallDetector.State? = null
    val baselineMs = activeInitialPositionMs.coerceAtLeast(0L)
    val clock = SourceStartClock(sourceHealthNowMs())
    var seenSeekRequests = seekRequestCount
    // The start was given up on but moving on did not apply (a source picked by hand): the source stays, and a
    // stall is watched for once it does start playing.
    var startGivenUp = false

    var finishedForSource by mutableStateOf(false)
    var wasSuspended = false

    fun restartClocks(nowMs: Long) {
        startup = SourceStartMonitor.Progress()
        stall = null
        startGivenUp = false
        clock.restart(nowMs)
    }

    snapshotFlow {
        if (sourceFailoverCancelled || finishedForSource) {
            null
        } else {
            shouldMonitorSourceHealthNow()
        }
    }.takeWhile { it != null }.collectLatest { active ->
        if (active != true) {
            wasSuspended = true
            val nowMs = sourceHealthNowMs()
            if (
                errorMessage != null ||
                !playerSettingsUiState.streamAutoPlayFallbackActive ||
                sourceFallbackJob?.isActive == true ||
                playbackSnapshot.isEnded
            ) {
                restartClocks(nowMs)
            }
            return@collectLatest
        }

        if (wasSuspended) {
            wasSuspended = false
            val resumeNowMs = sourceHealthNowMs()
            val resumeSnapshot = playbackSnapshot
            if (stall != null || (initialLoadCompleted && resumeSnapshot.positionMs > baselineMs)) {
                stall = PlaybackStallDetector.initial(
                    resumeNowMs,
                    resumeSnapshot.positionMs,
                    resumeSnapshot.bufferedPositionMs,
                )
                startGivenUp = false
            } else {
                clock.holdUntil(resumeNowMs)
            }
        }

        while (!sourceFailoverCancelled && !finishedForSource) {
            delay(SourceStartMonitor.POLL_INTERVAL_MS)
            if (sourceFailoverCancelled || !shouldMonitorSourceHealthNow()) break
            sourceHealthTickObserverForTest?.invoke()
            val nowMs = sourceHealthNowMs()
            val settings = playerSettingsUiState
            val offline = isDeviceOffline()
            // The time of the start stands still while the user has paused or the device is offline, and starts
            // over after a gap between reads (a sleeping machine), before anything is judged.
            val startElapsedMs = clock.read(nowMs, held = !shouldPlay || offline)
            if (clock.restarted) {
                restartClocks(nowMs)
                continue
            }
            val snapshot = playbackSnapshot
            val currentStall = stall
            if (currentStall == null && startGivenUp) {
                if (snapshot.isPlaying && snapshot.positionMs > baselineMs) {
                    stall = PlaybackStallDetector.initial(nowMs, snapshot.positionMs, snapshot.bufferedPositionMs)
                    startGivenUp = false
                }
            } else if (currentStall == null) {
                val limits = SourceStartMonitor.Limits.fromSettingSeconds(settings.streamAutoPlayFallbackStartupTimeoutSeconds)
                val step = SourceStartMonitor.next(
                    progress = startup,
                    reading = SourceStartMonitor.Reading(
                        elapsedMs = startElapsedMs,
                        playing = snapshot.isPlaying,
                        positionMs = snapshot.positionMs,
                        bufferedMs = snapshot.bufferedPositionMs,
                        durationMs = snapshot.durationMs,
                        resumeFromMs = baselineMs,
                    ),
                    limits = limits,
                )
                when (step) {
                    is SourceStartMonitor.Step.Pending -> startup = step.progress
                    SourceStartMonitor.Step.Playing ->
                        stall = PlaybackStallDetector.initial(nowMs, snapshot.positionMs, snapshot.bufferedPositionMs)
                    is SourceStartMonitor.Step.GiveUp -> {
                        val elapsedSeconds = (startElapsedMs / 1_000L).toInt().coerceAtLeast(1)
                        log.i { "Source did not start: ${step.cause.name} after $startElapsedMs ms" }
                        val handled = tryFallbackToNextSourceAfterError(
                            message = getString(Res.string.player_error_source_startup_timeout, elapsedSeconds),
                            failureOverride = SourceFailure.FailedToStart,
                        )
                        if (handled) {
                            finishedForSource = true
                            return@collectLatest
                        }
                        // Not taken care of: a source picked by hand stays on the screen as it always did. When only
                        // the device went offline in the meantime, wait and look again instead.
                        if (isDeviceOffline()) restartClocks(nowMs) else startGivenUp = true
                    }
                }
            } else {
                val seekRequest = if (seekRequestCount != seenSeekRequests) {
                    seenSeekRequests = seekRequestCount
                    PlaybackStallDetector.SeekRequest(lastSeekTargetMs)
                } else {
                    null
                }
                val step = PlaybackStallDetector.step(
                    state = currentStall,
                    sample = PlaybackStallDetector.Sample(
                        nowMs = nowMs,
                        positionMs = snapshot.positionMs,
                        bufferedPositionMs = snapshot.bufferedPositionMs,
                        durationMs = snapshot.durationMs,
                        isLoading = snapshot.isLoading,
                        isPlaying = snapshot.isPlaying,
                        wantsToPlay = shouldPlay,
                        isEnded = snapshot.isEnded,
                        isUserSeeking = isScrubbingTimeline || scrubbingPositionMs != null,
                        isOffline = offline,
                        seekRequest = seekRequest,
                    ),
                )
                when (step) {
                    is PlaybackStallDetector.Step.Continue -> stall = step.state
                    is PlaybackStallDetector.Step.Stalled -> {
                        log.i { "Source stopped responding: position still for ${step.stillForMs} ms" }
                        val handled = tryFallbackToNextSourceAfterError(
                            message = getString(Res.string.player_error_source_stalled),
                            failureOverride = SourceFailure.StalledDuringPlayback,
                        )
                        if (handled) {
                            finishedForSource = true
                            return@collectLatest
                        }
                        // Moving on does not apply right now (offline, the limit, an engine switch): look again later.
                        stall = PlaybackStallDetector.initial(nowMs, snapshot.positionMs, snapshot.bufferedPositionMs)
                    }
                }
            }
        }
    }
}

/** Records that a seek is being requested, so the stall watch does not count its wait as a stall. */
internal fun PlayerScreenRuntime.noteSeekRequested(targetMs: Long?) {
    seekRequestCount += 1
    lastSeekTargetMs = targetMs
}

/** Seeks [controller] to [targetMs] and records the request. */
internal fun PlayerScreenRuntime.seekWithNote(controller: PlayerEngineController?, targetMs: Long) {
    noteSeekRequested(targetMs)
    controller?.seekTo(targetMs)
}

/** Like [PlayerEngineController.trySeekTo] and records the request when it is accepted. */
internal fun PlayerScreenRuntime.trySeekWithNote(controller: PlayerEngineController, targetMs: Long): Boolean {
    val accepted = controller.trySeekTo(targetMs)
    if (accepted) noteSeekRequested(targetMs)
    return accepted
}

/** Seeks [controller] by [offsetMs] and records the request; the target is unknown. */
internal fun PlayerScreenRuntime.seekByWithNote(controller: PlayerEngineController?, offsetMs: Long) {
    noteSeekRequested(null)
    controller?.seekBy(offsetMs)
}
