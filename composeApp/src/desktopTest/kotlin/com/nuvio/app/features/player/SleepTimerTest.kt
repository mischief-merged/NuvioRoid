package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.resolvePlaybackCompletion
import com.nuvio.app.features.watchprogress.shouldCascadeCompletedProgressToWatchedHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sleep timer, the one-time stop after this episode, and the progress row it leaves behind.
 * These tests do not touch the watch-progress store.
 */
class SleepTimerTest {
    @Test
    fun `a fifteen minute timer fades for the last ten seconds then pauses and saves`() {
        val now = 1_700_000_000_000L
        val position = 120_000L
        val duration = 1_000_000L
        val armed = SleepTimerState().selectOption(SleepTimerOption.Minutes15, now)
        assertEquals(SleepTimerPhase.Armed, armed.phase)
        assertEquals(now + 15 * 60_000L, armed.deadlineEpochMs)
        assertFalse(armed.blocksAutomaticNextEpisode())

        val early = armed.step(now + 1_000L, position, duration, isPlaying = true, isEnded = false)
        assertEquals(15 * 60_000L - 1_000L, early.remainingMs)
        assertFalse(early.pause)
        assertFalse(early.saveProgress)
        assertNull(early.volumeFraction)
        assertFalse(early.state.suppressesCompletion(now + 1_000L, position, isPlaying = true))

        val fadeStart = armed.deadlineEpochMs!! - SleepTimerFadeDurationMs
        assertTrue(armed.suppressesCompletion(fadeStart, position, isPlaying = true))
        val entered = armed.step(fadeStart, position, duration, isPlaying = true, isEnded = false)
        assertEquals(SleepTimerPhase.Fading, entered.state.phase)
        assertEquals(1f, entered.volumeFraction)
        assertTrue(entered.state.holdIncomplete)
        assertFalse(entered.pause)
        assertFalse(entered.saveProgress)
        assertTrue(entered.state.blocksAutomaticNextEpisode())

        val midway = entered.state.step(fadeStart + 5_000L, position, duration, isPlaying = true, isEnded = false)
        assertEquals(0.5f, midway.volumeFraction)
        assertEquals(5_000L, midway.remainingMs)
        assertFalse(midway.pause)

        val done = entered.state.step(fadeStart + SleepTimerFadeDurationMs, position, duration, isPlaying = true, isEnded = false)
        assertTrue(done.pause)
        assertTrue(done.saveProgress)
        assertEquals(1f, done.volumeFraction)
        assertEquals(0L, done.remainingMs)
        assertEquals(SleepTimerPhase.Stopped, done.state.phase)
        assertEquals(SleepTimerOption.Off, done.state.option)
        assertEquals(position, done.state.holdPositionMs)
        assertTrue(done.state.blocksAutomaticNextEpisode())
        assertEquals(
            TrackingScrobbleAction.PAUSE,
            scrobbleActionForProgressSave(TrackingScrobbleAction.STOP, holdIncomplete = true),
        )
        assertEquals(
            TrackingScrobbleAction.STOP,
            scrobbleActionForProgressSave(TrackingScrobbleAction.STOP, holdIncomplete = false),
        )
    }

    @Test
    fun `a timer that is already due or already paused stops without a fade`() {
        val now = 5_000L
        val armed = SleepTimerState().selectOption(SleepTimerOption.Minutes15, now)
        val late = armed.step(armed.deadlineEpochMs!! + 1L, 40_000L, 1_000_000L, isPlaying = true, isEnded = false)
        assertTrue(late.pause)
        assertTrue(late.saveProgress)
        assertEquals(SleepTimerPhase.Stopped, late.state.phase)

        val pausedInWindow = armed.step(
            armed.deadlineEpochMs!! - 5_000L,
            40_000L,
            1_000_000L,
            isPlaying = false,
            isEnded = false,
        )
        assertTrue(pausedInWindow.pause)
        assertTrue(pausedInWindow.saveProgress)
        assertEquals(1f, pausedInWindow.volumeFraction)
    }

    @Test
    fun `end of episode fades across the time that is left and holds the whole play`() {
        val now = 20_000L
        val duration = 1_000_000L
        val armed = SleepTimerState().selectOption(SleepTimerOption.EndOfEpisode, now)
        assertTrue(armed.blocksAutomaticNextEpisode())
        assertTrue(armed.suppressesCompletion(now, positionMs = 100_000L, isPlaying = true))

        val early = armed.step(now, 100_000L, duration, isPlaying = true, isEnded = false)
        assertEquals(900_000L, early.remainingMs)
        assertEquals(SleepTimerPhase.Armed, early.state.phase)
        assertFalse(early.pause)

        val entered = armed.step(now, duration - 8_000L, duration, isPlaying = true, isEnded = false)
        assertEquals(SleepTimerPhase.Fading, entered.state.phase)
        assertEquals(8_000L, entered.state.fadeDurationMs)
        assertEquals(1f, entered.volumeFraction)
        assertFalse(entered.saveProgress)

        val ended = entered.state.step(now + 1_000L, duration - 1_000L, duration, isPlaying = true, isEnded = true)
        assertTrue(ended.pause)
        assertTrue(ended.saveProgress)
        assertEquals(duration - 1_000L, ended.state.holdPositionMs)
        assertEquals(1f, ended.volumeFraction)
    }

    @Test
    fun `the same choice turns the timer off and cancelling a fade clears the hold`() {
        val now = 1_000L
        val armed = SleepTimerState().selectOption(SleepTimerOption.Minutes30, now)
        val off = armed.selectOption(SleepTimerOption.Minutes30, now + 10L)
        assertEquals(SleepTimerOption.Off, off.option)
        assertEquals(SleepTimerPhase.Idle, off.phase)

        val fading = armed.step(armed.deadlineEpochMs!! - 4_000L, 10_000L, 1_000_000L, isPlaying = true, isEnded = false)
        assertEquals(SleepTimerPhase.Fading, fading.state.phase)
        val cancelled = fading.state.selectOption(SleepTimerOption.Off, now)
        assertEquals(SleepTimerPhase.Idle, cancelled.phase)
        assertFalse(cancelled.holdIncomplete)

        val switched = fading.state.selectOption(SleepTimerOption.Minutes15, now)
        assertEquals(SleepTimerOption.Minutes15, switched.option)
        assertEquals(SleepTimerPhase.Armed, switched.phase)
        assertFalse(switched.holdIncomplete)
    }

    @Test
    fun `leaving for the next episode does not hold an armed minute timer`() {
        val durationMs = 20 * 60_000L
        val armedAt = 1_700_000_000_000L
        val positionMs = 18 * 60_000L + 3_000L
        val armed = SleepTimerState().selectOption(SleepTimerOption.Minutes15, armedAt)
        // Armed at 4:00, so Up Next at 18:03 is still a minute short of the fade.
        val now = armedAt + (positionMs - 4 * 60_000L)
        assertTrue(now < armed.deadlineEpochMs!! - SleepTimerFadeDurationMs)
        assertTrue(
            armed.suppressesCompletion(
                nowEpochMs = now,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = true,
            ),
        )
        val leavingHold = armed.suppressesCompletion(
            nowEpochMs = now,
            positionMs = positionMs,
            durationMs = durationMs,
            isPlaying = true,
            leavingEpisode = true,
        )
        assertFalse(leavingHold)
        assertEquals(
            TrackingScrobbleAction.STOP,
            scrobbleActionForProgressSave(TrackingScrobbleAction.STOP, holdIncomplete = leavingHold),
        )
        val finished = resolvePlaybackCompletion(
            positionMs = positionMs,
            durationMs = durationMs,
            isEnded = false,
            holdIncomplete = leavingHold,
        )
        assertTrue(finished.isCompleted)
        assertFalse(finished.completionHeld)

        val runtime = player()
        runtime.sleepTimer = armed
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = durationMs,
            positionMs = positionMs,
        )
        assertTrue(runtime.playbackHoldIncomplete(now))
        assertFalse(runtime.playbackHoldIncomplete(now, leavingEpisode = true))

        assertTrue(
            isLeavingPlaybackEpisode(
                currentVideoId = "tt123:1:2",
                currentSeason = 1,
                currentEpisode = 2,
                nextVideoId = "tt123:1:3",
                nextSeason = 1,
                nextEpisode = 3,
            ),
        )
        assertFalse(
            isLeavingPlaybackEpisode(
                currentVideoId = "tt123:1:2",
                currentSeason = 1,
                currentEpisode = 2,
                nextVideoId = "tt123:1:2",
                nextSeason = 1,
                nextEpisode = 2,
            ),
        )

        val endOfEpisode = SleepTimerState().selectOption(SleepTimerOption.EndOfEpisode, armedAt)
        assertTrue(
            endOfEpisode.suppressesCompletion(
                nowEpochMs = now,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = true,
                leavingEpisode = true,
            ),
        )
        val stopped = SleepTimerState(
            phase = SleepTimerPhase.Stopped,
            holdIncomplete = true,
            holdPositionMs = positionMs,
        )
        assertTrue(
            stopped.suppressesCompletion(
                nowEpochMs = now,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = false,
                leavingEpisode = true,
            ),
        )
    }

    @Test
    fun `a minute progress tick stays on this computer`() {
        assertFalse(playbackProgressTickSyncsRemote(holdIncomplete = false))
        assertFalse(playbackProgressTickSyncsRemote(holdIncomplete = true))
    }

    @Test
    fun `a minute timer that pauses before the end does not mark the watched line`() {
        val durationMs = 20 * 60_000L
        val armedAt = 1_700_000_000_000L
        val watchedAt = 18 * 60_000L
        val armed = SleepTimerState().selectOption(SleepTimerOption.Minutes15, armedAt)
        // Armed at 4:00, so 18:00 still has a minute left, outside the last ten seconds.
        val now = armedAt + (watchedAt - 4 * 60_000L)
        assertTrue(now < armed.deadlineEpochMs!! - SleepTimerFadeDurationMs)
        assertTrue(
            armed.suppressesCompletion(
                nowEpochMs = now,
                positionMs = watchedAt,
                durationMs = durationMs,
                isPlaying = true,
            ),
        )

        val runtime = player()
        runtime.sleepTimer = armed
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = durationMs,
            positionMs = watchedAt,
        )
        assertTrue(runtime.playbackHoldIncomplete(now))

        val held = resolvePlaybackCompletion(
            positionMs = watchedAt,
            durationMs = durationMs,
            isEnded = false,
            holdIncomplete = true,
        )
        assertFalse(held.isCompleted)
        assertTrue(held.completionHeld)
        assertFalse(
            shouldCascadeCompletedProgressToWatchedHistory(
                entry = progressEntry(
                    positionMs = watchedAt,
                    durationMs = durationMs,
                    isCompleted = held.isCompleted,
                    completionHeld = held.completionHeld,
                    progressPercent = 90f,
                ),
                providerOwnsCompletedHistory = false,
            ),
        )

        // Still early in the episode: ordinary progress keeps syncing.
        assertFalse(
            armed.suppressesCompletion(
                nowEpochMs = armedAt,
                positionMs = 4 * 60_000L,
                durationMs = durationMs,
                isPlaying = true,
            ),
        )
        // Plenty of time left to finish, so the watched line still counts.
        val finishesFirst = armed.deadlineEpochMs!! - 5 * 60_000L
        assertFalse(
            armed.suppressesCompletion(
                nowEpochMs = finishesFirst,
                positionMs = watchedAt,
                durationMs = durationMs,
                isPlaying = true,
            ),
        )
        // Paused here, the timer fires before the episode can finish.
        assertTrue(
            armed.suppressesCompletion(
                nowEpochMs = finishesFirst,
                positionMs = watchedAt,
                durationMs = durationMs,
                isPlaying = false,
            ),
        )
        val longTimer = SleepTimerState().selectOption(SleepTimerOption.Minutes90, armedAt)
        assertFalse(
            longTimer.suppressesCompletion(
                nowEpochMs = now,
                positionMs = watchedAt,
                durationMs = durationMs,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun `seeking backward during an end of episode fade keeps playing`() {
        val now = 50_000L
        val duration = 20 * 60_000L
        val armed = SleepTimerState().selectOption(SleepTimerOption.EndOfEpisode, now)
        val entered = armed.step(now, duration - 8_000L, duration, isPlaying = true, isEnded = false)
        assertEquals(SleepTimerPhase.Fading, entered.state.phase)
        assertEquals(8_000L, entered.state.fadeDurationMs)

        val seekPosition = duration - 8_000L - 5 * 60_000L
        val seekBack = entered.state.step(
            nowEpochMs = now + 8_000L,
            positionMs = seekPosition,
            durationMs = duration,
            isPlaying = true,
            isEnded = false,
        )
        assertFalse(seekBack.pause)
        assertFalse(seekBack.saveProgress)
        assertEquals(SleepTimerPhase.Armed, seekBack.state.phase)
        assertEquals(SleepTimerOption.EndOfEpisode, seekBack.state.option)
        assertEquals(1f, seekBack.volumeFraction)
        assertTrue(
            seekBack.state.suppressesCompletion(
                nowEpochMs = now + 8_000L,
                positionMs = seekPosition,
                durationMs = duration,
                isPlaying = true,
            ),
        )

        val later = seekBack.state.step(
            nowEpochMs = now + 16_000L,
            positionMs = seekPosition,
            durationMs = duration,
            isPlaying = true,
            isEnded = false,
        )
        assertFalse(later.pause)
        assertEquals(SleepTimerPhase.Armed, later.state.phase)
    }

    @Test
    fun `an end of episode fade follows playback time when the picture stalls`() {
        val now = 50_000L
        val duration = 20 * 60_000L
        val armed = SleepTimerState().selectOption(SleepTimerOption.EndOfEpisode, now)
        val entered = armed.step(now, duration - 8_000L, duration, isPlaying = true, isEnded = false)
        assertEquals(SleepTimerPhase.Fading, entered.state.phase)

        val stalled = entered.state.step(
            nowEpochMs = now + 30_000L,
            positionMs = duration - 8_000L,
            durationMs = duration,
            isPlaying = true,
            isEnded = false,
        )
        assertFalse(stalled.pause)
        assertFalse(stalled.saveProgress)
        assertEquals(SleepTimerPhase.Fading, stalled.state.phase)
        assertEquals(1f, stalled.volumeFraction)
        assertEquals(8_000L, stalled.remainingMs)

        val slow = stalled.state.step(
            nowEpochMs = now + 38_000L,
            positionMs = duration - 5_000L,
            durationMs = duration,
            isPlaying = true,
            isEnded = false,
        )
        assertFalse(slow.pause)
        assertEquals(0.625f, slow.volumeFraction)
        assertEquals(5_000L, slow.remainingMs)

        val finished = slow.state.step(
            nowEpochMs = now + 39_000L,
            positionMs = duration,
            durationMs = duration,
            isPlaying = true,
            isEnded = false,
        )
        assertTrue(finished.pause)
        assertTrue(finished.saveProgress)
        assertEquals(SleepTimerPhase.Stopped, finished.state.phase)
        assertEquals(duration, finished.state.holdPositionMs)
    }

    @Test
    fun `replaying from before the watched line after a stop at the end can finish`() {
        val duration = 20 * 60_000L
        val stoppedAtEnd = SleepTimerState(
            phase = SleepTimerPhase.Stopped,
            holdIncomplete = true,
            holdPositionMs = duration,
        )
        val replay = stoppedAtEnd.step(
            nowEpochMs = 1L,
            positionMs = 0L,
            durationMs = duration,
            isPlaying = true,
            isEnded = false,
        )
        assertFalse(replay.state.holdIncomplete)
        assertNull(replay.state.holdPositionMs)
        assertEquals(SleepTimerPhase.Stopped, replay.state.phase)
        assertTrue(replay.state.blocksAutomaticNextEpisode())
        val watchedAgain = (duration * 9L) / 10L
        assertFalse(replay.state.suppressesCompletion(2L, watchedAgain, duration, isPlaying = true))
        assertEquals(
            TrackingScrobbleAction.STOP,
            scrobbleActionForProgressSave(TrackingScrobbleAction.STOP, holdIncomplete = replay.state.holdIncomplete),
        )

        val stoppedJustShort = SleepTimerState(
            phase = SleepTimerPhase.Stopped,
            holdIncomplete = true,
            holdPositionMs = duration - 1_000L,
        )
        val replayFromMiddle = stoppedJustShort.step(
            nowEpochMs = 1L,
            positionMs = duration / 2L,
            durationMs = duration,
            isPlaying = true,
            isEnded = false,
        )
        assertFalse(replayFromMiddle.state.holdIncomplete)

        val rewoundInsideTheWatchedLine = stoppedAtEnd.step(
            nowEpochMs = 1L,
            positionMs = duration - 30_000L,
            durationMs = duration,
            isPlaying = true,
            isEnded = false,
        )
        assertTrue(rewoundInsideTheWatchedLine.state.holdIncomplete)
        assertEquals(duration, rewoundInsideTheWatchedLine.state.holdPositionMs)

        val midEpisodeStop = SleepTimerState(
            phase = SleepTimerPhase.Stopped,
            holdIncomplete = true,
            holdPositionMs = 5 * 60_000L,
        )
        val restarted = midEpisodeStop.step(
            nowEpochMs = 1L,
            positionMs = 0L,
            durationMs = duration,
            isPlaying = true,
            isEnded = false,
        )
        assertTrue(restarted.state.holdIncomplete)
        assertEquals(5 * 60_000L, restarted.state.holdPositionMs)
    }

    @Test
    fun `after a stop the episode stays unfinished until playback moves past it`() {
        val stopped = SleepTimerState(
            phase = SleepTimerPhase.Stopped,
            holdIncomplete = true,
            holdPositionMs = 50_000L,
        )
        assertTrue(stopped.suppressesCompletion(0L, 51_000L, isPlaying = true))
        val released = stopped.step(
            nowEpochMs = 1L,
            positionMs = 50_000L + SleepTimerResumeReleaseMs + 1L,
            durationMs = 1_000_000L,
            isPlaying = true,
            isEnded = false,
        )
        assertFalse(released.state.holdIncomplete)
        assertNull(released.state.holdPositionMs)
        assertEquals(SleepTimerPhase.Stopped, released.state.phase)
        assertTrue(released.state.blocksAutomaticNextEpisode())
        assertFalse(released.state.suppressesCompletion(1L, 60_000L, isPlaying = true))
    }

    @Test
    fun `stop after this episode blocks autoplay once and a minute timer does not`() {
        val stopAfter = SleepTimerState(stopAfterEpisode = true)
        assertTrue(stopAfter.blocksAutomaticNextEpisode())
        assertFalse(stopAfter.suppressesCompletion(0L, 900_000L, isPlaying = true))
        val cleared = stopAfter.onEpisodeChanged()
        assertFalse(cleared.stopAfterEpisode)
        assertFalse(cleared.blocksAutomaticNextEpisode())

        val minutes = SleepTimerState().selectOption(SleepTimerOption.Minutes45, 0L)
        val kept = minutes.copy(stopAfterEpisode = true).onEpisodeChanged()
        assertEquals(SleepTimerOption.Minutes45, kept.option)
        assertEquals(minutes.deadlineEpochMs, kept.deadlineEpochMs)
        assertFalse(kept.stopAfterEpisode)
        assertFalse(kept.blocksAutomaticNextEpisode())

        val fading = SleepTimerState(option = SleepTimerOption.EndOfEpisode, phase = SleepTimerPhase.Fading)
        val reset = fading.onEpisodeChanged()
        assertEquals(SleepTimerOption.Off, reset.option)
        assertEquals(SleepTimerPhase.Idle, reset.phase)
        assertFalse(reset.holdIncomplete)
    }

    @Test
    fun `the quiet clock uses hours only after an hour is left`() {
        assertEquals("15:00", formatSleepTimerClock(15 * 60_000L))
        assertEquals("1:05", formatSleepTimerClock(65_000L))
        assertEquals("0:01", formatSleepTimerClock(500L))
        assertEquals("1:30:00", formatSleepTimerClock(90 * 60_000L))
        assertEquals("1:01:00", formatSleepTimerClock(61 * 60_000L))
    }

    @Test
    fun `control values map onto the timer choices`() {
        assertEquals(SleepTimerOption.Off, sleepTimerOptionFromControl(0.0))
        assertEquals(SleepTimerOption.Minutes15, sleepTimerOptionFromControl(15.0))
        assertEquals(SleepTimerOption.Minutes90, sleepTimerOptionFromControl(90.0))
        assertEquals(SleepTimerOption.EndOfEpisode, sleepTimerOptionFromControl(-1.0))
        assertNull(sleepTimerOptionFromControl(12.0))
    }

    @Test
    fun `choosing a timer arms it without pausing`() {
        val runtime = player()
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 1_000_000L,
            positionMs = 10_000L,
        )
        runtime.selectSleepTimerOption(SleepTimerOption.Minutes15)
        assertEquals(SleepTimerPhase.Armed, runtime.sleepTimer.phase)
        assertEquals("15:00", runtime.sleepTimerRemainingLabel)
        assertTrue(runtime.shouldPlay)
        assertFalse(runtime.sleepTimer.holdIncomplete)
    }

    @Test
    fun `the first episode assignment leaves an armed timer alone`() {
        val runtime = player()
        runtime.sleepTimer = SleepTimerState(stopAfterEpisode = true)
        runtime.noteSleepTimerEpisode()
        assertTrue(runtime.sleepTimer.stopAfterEpisode)
        assertEquals("tt123:1:2:1:2", runtime.lastSleepTimerEpisodeKey)

        runtime.noteSleepTimerEpisode()
        assertTrue(runtime.sleepTimer.stopAfterEpisode)

        runtime.activeEpisodeNumber = 3
        runtime.noteSleepTimerEpisode()
        assertFalse(runtime.sleepTimer.stopAfterEpisode)
        assertEquals("tt123:1:2:1:3", runtime.lastSleepTimerEpisodeKey)
    }

    @Test
    fun `stop after this episode shows up next and does not start it`() {
        val runtime = episodeNearTheEnd()
        runtime.sleepTimer = SleepTimerState(stopAfterEpisode = true)
        runtime.nextEpisodeAutoPlayAutomatic = true
        runtime.onNextEpisodeThresholdChanged()
        assertTrue(runtime.showNextEpisodeCard)
        assertFalse(runtime.nextEpisodeAutoPlayAutomatic)
        assertNull(runtime.nextEpisodeAutoPlayJob)
        assertFalse(runtime.nextEpisodeAutoPlaySearching)

        runtime.playNextEpisode(automatic = true)
        assertNull(runtime.nextEpisodeAutoPlayJob)
        assertFalse(runtime.nextEpisodeAutoPlaySearching)
    }

    @Test
    fun `end of episode and a fired timer also refuse automatic play`() {
        listOf(
            SleepTimerState(option = SleepTimerOption.EndOfEpisode, phase = SleepTimerPhase.Armed),
            SleepTimerState(phase = SleepTimerPhase.Fading),
            SleepTimerState(phase = SleepTimerPhase.Stopped),
        ).forEach { timer ->
            val runtime = episodeNearTheEnd()
            runtime.sleepTimer = timer
            runtime.nextEpisodeAutoPlayAutomatic = true
            runtime.onNextEpisodeThresholdChanged()
            assertTrue(runtime.showNextEpisodeCard)
            assertFalse(runtime.nextEpisodeAutoPlayAutomatic)
            assertNull(runtime.nextEpisodeAutoPlayJob)
        }
        assertFalse(
            SleepTimerState().selectOption(SleepTimerOption.Minutes60, 0L).blocksAutomaticNextEpisode(),
        )
    }

    @Test
    fun `a held save keeps the real position and does not count as watched`() {
        val duration = 1_000_000L
        val position = 950_000L
        val held = resolvePlaybackCompletion(
            positionMs = position,
            durationMs = duration,
            isEnded = true,
            holdIncomplete = true,
        )
        assertFalse(held.isCompleted)
        assertTrue(held.completionHeld)
        assertEquals(position, held.storedPositionMs)

        val finished = resolvePlaybackCompletion(
            positionMs = position,
            durationMs = duration,
            isEnded = true,
            holdIncomplete = false,
        )
        assertTrue(finished.isCompleted)
        assertFalse(finished.completionHeld)
        assertEquals(duration, finished.storedPositionMs)

        val entry = progressEntry(
            positionMs = position,
            durationMs = duration,
            isCompleted = true,
            completionHeld = true,
            progressPercent = 95f,
        )
        assertFalse(entry.isEffectivelyCompleted)
        assertFalse(entry.normalizedCompletion().isCompleted)
        assertEquals(position, entry.normalizedCompletion().lastPositionMs)
        assertFalse(
            shouldCascadeCompletedProgressToWatchedHistory(
                entry = entry,
                providerOwnsCompletedHistory = false,
            ),
        )

        val plain = progressEntry(
            positionMs = position,
            durationMs = duration,
            isCompleted = true,
            completionHeld = false,
            progressPercent = 95f,
        )
        assertTrue(plain.isEffectivelyCompleted)
        assertTrue(
            shouldCascadeCompletedProgressToWatchedHistory(
                entry = plain,
                providerOwnsCompletedHistory = false,
            ),
        )
    }

    private fun episodeNearTheEnd(): PlayerScreenRuntime {
        val runtime = player()
        runtime.initialSeekApplied = true
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            isEnded = false,
            durationMs = 1_000_000L,
            positionMs = 995_000L,
        )
        runtime.playbackSnapshotKey = runtime.activePlaybackKey
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(
            streamAutoPlayNextEpisodeEnabled = true,
            preloadNextEpisodeSources = false,
        )
        runtime.nextEpisodeInfo = NextEpisodeInfo(
            videoId = "tt123:1:3",
            season = 1,
            episode = 3,
            title = "Next",
            thumbnail = null,
            overview = null,
            released = "2001-01-01",
            hasAired = true,
            isWatched = false,
            unairedMessage = null,
        )
        return runtime
    }

    private fun player() = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Show",
            sourceUrl = "https://example.com/video.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Provider",
            streamTitle = "Source",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Current",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt123:1:2",
            parentMetaId = "tt123",
            parentMetaType = "series",
            providerAddonId = null,
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    )

    private fun progressEntry(
        positionMs: Long,
        durationMs: Long,
        isCompleted: Boolean,
        completionHeld: Boolean,
        progressPercent: Float,
    ) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "tt123",
        parentMetaType = "series",
        videoId = "tt123:1:2",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = 2,
        lastPositionMs = positionMs,
        durationMs = durationMs,
        lastUpdatedEpochMs = 1L,
        isCompleted = isCompleted,
        completionHeld = completionHeld,
        progressPercent = progressPercent,
    )
}
