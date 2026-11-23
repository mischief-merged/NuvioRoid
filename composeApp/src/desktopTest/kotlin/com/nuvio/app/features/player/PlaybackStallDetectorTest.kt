package com.nuvio.app.features.player

import com.nuvio.app.features.player.PlaybackStallDetector.Sample
import com.nuvio.app.features.player.PlaybackStallDetector.State
import com.nuvio.app.features.player.PlaybackStallDetector.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackStallDetectorTest {

    private fun sample(
        nowMs: Long,
        positionMs: Long = 60_000L,
        bufferedPositionMs: Long = 70_000L,
        durationMs: Long = 3_600_000L,
        isLoading: Boolean = true,
        isPlaying: Boolean = false,
        wantsToPlay: Boolean = true,
        isEnded: Boolean = false,
        isUserSeeking: Boolean = false,
        isOffline: Boolean = false,
        seekRequest: PlaybackStallDetector.SeekRequest? = null,
    ) = Sample(
        nowMs = nowMs,
        positionMs = positionMs,
        bufferedPositionMs = bufferedPositionMs,
        durationMs = durationMs,
        isLoading = isLoading,
        isPlaying = isPlaying,
        wantsToPlay = wantsToPlay,
        isEnded = isEnded,
        isUserSeeking = isUserSeeking,
        isOffline = isOffline,
        seekRequest = seekRequest,
    )

    /** Feeds one sample per second, from the second after [fromMs], until a stall or [untilMs]. */
    private fun run(
        initial: State,
        fromMs: Long,
        untilMs: Long,
        make: (Long) -> Sample,
    ): Pair<State, Step.Stalled?> {
        var state = initial
        var now = fromMs
        while (now < untilMs) {
            now += 1_000L
            when (val step = PlaybackStallDetector.step(state, make(now))) {
                is Step.Continue -> state = step.state
                is Step.Stalled -> return state to step
            }
        }
        return state to null
    }

    private fun armed(nowMs: Long = 0L, positionMs: Long = 60_000L, buffered: Long = 70_000L) =
        PlaybackStallDetector.initial(nowMs, positionMs, buffered)

    @Test
    fun `a frozen buffering source stalls after the deadline and not before`() {
        val (_, before) = run(armed(), 0L, PlaybackStallDetector.STALL_DEADLINE_MS - 1_000L) { now -> sample(now) }
        assertEquals(null, before)
        val (_, stalled) = run(armed(), 0L, PlaybackStallDetector.STALL_DEADLINE_MS + 2_000L) { now -> sample(now) }
        assertIs<Step.Stalled>(stalled)
        assertEquals(PlaybackStallDetector.STALL_DEADLINE_MS, stalled.stillForMs)
    }

    @Test
    fun `a frozen frame while the player claims to play also stalls`() {
        val (_, stalled) = run(armed(), 0L, 30_000L) { now -> sample(now, isLoading = false, isPlaying = true) }
        assertIs<Step.Stalled>(stalled)
    }

    @Test
    fun `slow but advancing playback never stalls`() {
        // Half a second of media every four seconds: far too slow to watch, but the source is alive.
        val (_, stalled) = run(armed(), 0L, 300_000L) { now ->
            sample(now, positionMs = 60_000L + (now / 4_000L) * 500L, bufferedPositionMs = 70_000L)
        }
        assertEquals(null, stalled)
    }

    @Test
    fun `playback that advances normally never stalls`() {
        val (_, stalled) = run(armed(), 0L, 600_000L) { now ->
            sample(now, positionMs = 60_000L + now, bufferedPositionMs = 70_000L + now, isLoading = false, isPlaying = true)
        }
        assertEquals(null, stalled)
    }

    @Test
    fun `a paused player never stalls and the pause leaves no time behind`() {
        val (state, stalled) = run(armed(), 0L, 600_000L) { now -> sample(now, wantsToPlay = false, isLoading = false) }
        assertEquals(null, stalled)
        // Resuming starts the clocks from nothing: a stall needs the whole deadline again.
        val (_, early) = run(state, 600_000L, 600_000L + PlaybackStallDetector.STALL_DEADLINE_MS - 2_000L) { now -> sample(now) }
        assertEquals(null, early)
    }

    @Test
    fun `a player that is not trying to play is not stalled even if the user did not pause`() {
        val (_, stalled) = run(armed(), 0L, 120_000L) { now -> sample(now, isLoading = false, isPlaying = false) }
        assertEquals(null, stalled)
    }

    @Test
    fun `a user seek never stalls`() {
        val (_, stalled) = run(armed(), 0L, 120_000L) { now -> sample(now, isUserSeeking = true) }
        assertEquals(null, stalled)
    }

    @Test
    fun `a seek by position jump restarts the clocks`() {
        var state = armed()
        val (s1, stalled1) = run(state, 0L, 15_000L) { now -> sample(now) }
        assertEquals(null, stalled1)
        // The position jumps 10 minutes ahead (the user seeked): the 15 s already spent do not count.
        val step = PlaybackStallDetector.step(s1, sample(16_000L, positionMs = 660_000L, bufferedPositionMs = 670_000L))
        assertIs<Step.Continue>(step)
        state = step.state
        val (_, early) = run(state, 16_000L, 16_000L + PlaybackStallDetector.STALL_DEADLINE_MS - 2_000L) { now ->
            sample(now, positionMs = 660_000L, bufferedPositionMs = 670_000L)
        }
        assertEquals(null, early)
    }

    @Test
    fun `a seek backwards also restarts the clocks`() {
        val (s1, _) = run(armed(), 0L, 15_000L) { now -> sample(now) }
        val step = PlaybackStallDetector.step(s1, sample(16_000L, positionMs = 10_000L))
        assertIs<Step.Continue>(step)
        assertEquals(null, step.state.positionStillSinceMs)
    }

    @Test
    fun `an offline device never stalls and coming back restarts the clocks`() {
        val (state, stalled) = run(armed(), 0L, 300_000L) { now -> sample(now, isOffline = true) }
        assertEquals(null, stalled)
        val (_, early) = run(state, 300_000L, 300_000L + PlaybackStallDetector.STALL_DEADLINE_MS - 2_000L) { now -> sample(now) }
        assertEquals(null, early)
    }

    @Test
    fun `the end of the file is not a stall`() {
        val ended = run(armed(), 0L, 120_000L) { now -> sample(now, isEnded = true) }
        assertEquals(null, ended.second)
        val nearEnd = run(armed(positionMs = 3_599_500L), 0L, 120_000L) { now ->
            sample(now, positionMs = 3_599_500L, bufferedPositionMs = 3_600_000L, durationMs = 3_600_000L)
        }
        assertEquals(null, nearEnd.second)
    }

    @Test
    fun `a suspended app is not a stall`() {
        val (s1, _) = run(armed(), 0L, 15_000L) { now -> sample(now) }
        // The machine slept: the next sample is ten minutes later and nothing moved.
        val step = PlaybackStallDetector.step(s1, sample(15_000L + 600_000L))
        assertIs<Step.Continue>(step)
        assertEquals(null, step.state.positionStillSinceMs)
    }

    @Test
    fun `a buffer that creeps forward holds off the short deadline but not the long one`() {
        // The buffer grows 200 ms every poll, the position never moves.
        val make = { now: Long -> sample(now, bufferedPositionMs = 70_000L + (now / 1_000L) * 200L) }
        val (_, afterShort) = run(armed(), 0L, PlaybackStallDetector.TRICKLE_DEADLINE_MS - 2_000L, make)
        assertEquals(null, afterShort)
        val (_, stalled) = run(armed(), 0L, PlaybackStallDetector.TRICKLE_DEADLINE_MS + 2_000L, make)
        assertIs<Step.Stalled>(stalled)
        assertEquals(PlaybackStallDetector.TRICKLE_DEADLINE_MS, stalled.stillForMs)
    }

    @Test
    fun `jitter in the position does not hide a stall`() {
        val (_, stalled) = run(armed(), 0L, 40_000L) { now -> sample(now, positionMs = 60_000L + (now % 2L) * 30L) }
        assertIs<Step.Stalled>(stalled)
    }

    @Test
    fun `the deadlines are ordered`() {
        assertTrue(PlaybackStallDetector.STALL_DEADLINE_MS < PlaybackStallDetector.TRICKLE_DEADLINE_MS)
        assertTrue(PlaybackStallDetector.MAX_SAMPLE_GAP_MS < PlaybackStallDetector.STALL_DEADLINE_MS)
    }

    // A requested seek stays exempt until it settles

    private val seekTo = PlaybackStallDetector.SeekRequest(targetMs = 3_000_000L)

    /** A distant seek requested at 5 s that the player answers only at [settleAtMs]. */
    private fun distantSeek(settleAtMs: Long, untilMs: Long): Pair<State, Step.Stalled?> {
        val first = PlaybackStallDetector.step(
            armed(),
            sample(5_000L, positionMs = 3_000_000L, bufferedPositionMs = 3_000_000L, seekRequest = seekTo),
        )
        var state = (first as Step.Continue).state
        return run(state, 5_000L, untilMs) { now ->
            if (now < settleAtMs) {
                // Position at the target, loading, nothing buffered, nothing moving.
                sample(now, positionMs = 3_000_000L, bufferedPositionMs = 3_000_000L)
            } else {
                sample(now, positionMs = 3_000_000L + (now - settleAtMs), bufferedPositionMs = 3_030_000L + now, isLoading = false, isPlaying = true)
            }
        }
    }

    @Test
    fun aDistantSeekThatTakesThirtySecondsDoesNotFailOver() {
        val (_, stalled) = distantSeek(settleAtMs = 35_000L, untilMs = 300_000L)
        assertEquals(null, stalled)
    }

    @Test
    fun aSeekThatNeverSettlesFailsOverAtTheLongerLimit() {
        val (_, early) = distantSeek(settleAtMs = Long.MAX_VALUE, untilMs = 5_000L + PlaybackStallDetector.SEEK_NEVER_SETTLES_MS - 2_000L)
        assertEquals(null, early)
        val (_, stalled) = distantSeek(settleAtMs = Long.MAX_VALUE, untilMs = 5_000L + PlaybackStallDetector.SEEK_NEVER_SETTLES_MS + 2_000L)
        assertIs<Step.Stalled>(stalled)
        assertEquals(PlaybackStallDetector.SEEK_NEVER_SETTLES_MS, stalled.stillForMs)
    }

    @Test
    fun theBufferGrowingAgainSettlesASeekAndOrdinaryRulesResume() {
        val first = PlaybackStallDetector.step(armed(), sample(5_000L, positionMs = 3_000_000L, bufferedPositionMs = 3_000_000L, seekRequest = seekTo))
        var state = (first as Step.Continue).state
        // Buffer grows once while still loading: the seek settled.
        state = (PlaybackStallDetector.step(state, sample(6_000L, positionMs = 3_000_000L, bufferedPositionMs = 3_005_000L)) as Step.Continue).state
        assertEquals(null, state.pendingSeekSinceMs)
        // Now a freeze counts again after the ordinary deadline.
        val (_, stalled) = run(state, 6_000L, 6_000L + PlaybackStallDetector.STALL_DEADLINE_MS + 2_000L) { now ->
            sample(now, positionMs = 3_000_000L, bufferedPositionMs = 3_005_000L)
        }
        assertIs<Step.Stalled>(stalled)
    }

    @Test
    fun playingNearTheTargetSettlesASeekWithoutABufferFigure() {
        val first = PlaybackStallDetector.step(armed(), sample(5_000L, positionMs = 3_000_000L, bufferedPositionMs = 0L, seekRequest = seekTo))
        val state = (first as Step.Continue).state
        val settled = PlaybackStallDetector.step(state, sample(7_000L, positionMs = 3_001_000L, bufferedPositionMs = 0L, isLoading = false, isPlaying = true))
        assertEquals(null, (settled as Step.Continue).state.pendingSeekSinceMs)
    }

    @Test
    fun aRelativeSeekWithNoKnownTargetSettlesWhenPlayingAgain() {
        val first = PlaybackStallDetector.step(armed(), sample(5_000L, positionMs = 70_000L, seekRequest = PlaybackStallDetector.SeekRequest(null)))
        assertEquals(5_000L, (first as Step.Continue).state.pendingSeekSinceMs)
        val settled = PlaybackStallDetector.step(first.state, sample(6_000L, positionMs = 71_000L, isLoading = false, isPlaying = true))
        assertEquals(null, (settled as Step.Continue).state.pendingSeekSinceMs)
    }

    @Test
    fun aPendingSeekDoesNotAgeWhilePausedOrOffline() {
        val first = PlaybackStallDetector.step(armed(), sample(5_000L, seekRequest = seekTo))
        val (state, stalled) = run((first as Step.Continue).state, 5_000L, 400_000L) { now -> sample(now, wantsToPlay = false) }
        assertEquals(null, stalled)
        // After resuming it still has most of its limit left.
        val (_, early) = run(state, 400_000L, 400_000L + 40_000L) { now -> sample(now) }
        assertEquals(null, early)
        val (_, offline) = run((first).state, 5_000L, 400_000L) { now -> sample(now, isOffline = true) }
        assertEquals(null, offline)
    }

    @Test
    fun aSuspendedAppDuringAPendingSeekRestartsItsAge() {
        val first = PlaybackStallDetector.step(armed(), sample(5_000L, seekRequest = seekTo))
        val step = PlaybackStallDetector.step((first as Step.Continue).state, sample(5_000L + 600_000L))
        assertEquals(5_000L + 600_000L, (step as Step.Continue).state.pendingSeekSinceMs)
    }

    @Test
    fun eventDrivenDeadlinesMatchStallTricklePendingSeekAndExemptionRules() {
        assertEquals(null, PlaybackStallDetector.nextDeadlineDelayMs(armed(), sample(0L, wantsToPlay = false)))
        assertEquals(null, PlaybackStallDetector.nextDeadlineDelayMs(armed(), sample(0L, isOffline = true)))
        assertEquals(null, PlaybackStallDetector.nextDeadlineDelayMs(armed(), sample(0L, isEnded = true)))
        assertEquals(null, PlaybackStallDetector.nextDeadlineDelayMs(armed(), sample(0L, isLoading = false, isPlaying = false)))

        // Single event-driven jump across STALL_DEADLINE_MS fires Stalled immediately without 1-second polling.
        val initialStep = PlaybackStallDetector.step(
            armed(0L),
            sample(0L, isLoading = false, isPlaying = true),
            discontinuityOverride = false,
        ) as Step.Continue
        val armedState = PlaybackStallDetector.armForDeadline(initialStep.state, sample(0L, isLoading = false, isPlaying = true))
        val waitMs = PlaybackStallDetector.nextDeadlineDelayMs(armedState, sample(0L, isLoading = false, isPlaying = true))
        assertEquals(PlaybackStallDetector.STALL_DEADLINE_MS, waitMs)

        val woken = PlaybackStallDetector.step(
            armedState,
            sample(waitMs!!, isLoading = false, isPlaying = true),
            discontinuityOverride = false,
        )
        assertIs<Step.Stalled>(woken)
        assertEquals(PlaybackStallDetector.STALL_DEADLINE_MS, woken.stillForMs)

        // Machine sleep during a scheduled deadline resets the clocks instead of stalling.
        val slept = PlaybackStallDetector.step(
            armedState,
            sample(600_000L, isLoading = false, isPlaying = true),
            discontinuityOverride = true,
        )
        assertIs<Step.Continue>(slept)
        assertEquals(null, slept.state.positionStillSinceMs)

        // Buffer trickle computes exact remaining time to TRICKLE_DEADLINE_MS when < STALL_DEADLINE_MS remains.
        val trickling = State(
            lastNowMs = 50_000L,
            referencePositionMs = 60_000L,
            referenceBufferedMs = 80_000L,
            positionStillSinceMs = 0L,
            bufferStillSinceMs = 50_000L,
        )
        val trickleWaitMs = PlaybackStallDetector.nextDeadlineDelayMs(trickling, sample(50_000L, bufferedPositionMs = 80_000L))
        assertEquals(PlaybackStallDetector.TRICKLE_DEADLINE_MS - 50_000L, trickleWaitMs)
        val trickleStalled = PlaybackStallDetector.step(
            trickling,
            sample(50_000L + trickleWaitMs!!, bufferedPositionMs = 80_000L),
            discontinuityOverride = false,
        )
        assertIs<Step.Stalled>(trickleStalled)
        assertEquals(PlaybackStallDetector.TRICKLE_DEADLINE_MS, trickleStalled.stillForMs)

        // Pending seek computes remaining time to SEEK_NEVER_SETTLES_MS.
        val seekStarted = (PlaybackStallDetector.step(armed(5_000L), sample(5_000L, seekRequest = seekTo)) as Step.Continue).state
        assertEquals(
            PlaybackStallDetector.SEEK_NEVER_SETTLES_MS - 12_000L,
            PlaybackStallDetector.nextDeadlineDelayMs(seekStarted, sample(17_000L, positionMs = 3_000_000L, bufferedPositionMs = 3_000_000L)),
        )
    }
}
