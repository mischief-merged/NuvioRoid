package com.nuvio.app.features.player

/**
 * Detects a source that stopped responding after playback had started: the picture freezes or the
 * player buffers and the position stops moving, without an error. Sibling of [SourceStartMonitor],
 * which covers the start. The caller arms it once the start watchdog says playback began.
 *
 * What counts as a stall: the player wants to play (the user did not pause), is not at the end, the
 * user is not scrubbing, the device is online, and the position has not moved for
 * [STALL_DEADLINE_MS] while the buffer has not grown either, or for [TRICKLE_DEADLINE_MS] even when
 * the buffer creeps forward. Anything that is not the source's fault clears the clocks instead, so
 * a pause, a seek, going offline or a sleeping machine never adds up to a stall.
 *
 * A seek the player was asked for stays exempt from the moment it is requested until the player shows it
 * settled (playing again near the target, or the buffer growing again), because a distant seek into a large
 * remote file can wait a long time with nothing moving. A seek that never settles counts as a stall after
 * [SEEK_NEVER_SETTLES_MS].
 *
 * Pure and clock-free: the caller passes a monotonic time with each sample.
 */
internal object PlaybackStallDetector {

    /** The position and the buffer both stood still this long: the source stopped responding. */
    const val STALL_DEADLINE_MS = 20_000L

    /** The position stood still this long although the buffer grew a little: too slow to ever play. */
    const val TRICKLE_DEADLINE_MS = 60_000L

    /** A position or buffer must move by this much to count as moving; smaller changes are jitter. */
    const val MIN_MOVE_MS = 100L

    /** Two samples further apart than this mean the app was suspended or hung, so the clocks start over. */
    const val MAX_SAMPLE_GAP_MS = 5_000L

    /** A requested seek that has not settled after this long is a stall. Far past [STALL_DEADLINE_MS] on purpose. */
    const val SEEK_NEVER_SETTLES_MS = 60_000L

    /** Playing this close to a seek's target counts as the seek having settled. */
    const val SEEK_SETTLE_TOLERANCE_MS = 3_000L

    /** A position this close to the end is the end, even if the player has not said so. */
    const val END_MARGIN_MS = 1_000L

    /** A seek the runtime asked the player for. [targetMs] is null for a relative seek whose target is not known. */
    data class SeekRequest(val targetMs: Long?)

    /** One reading of the player. [nowMs] is on a monotonic clock. */
    data class Sample(
        val nowMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val durationMs: Long,
        val isLoading: Boolean,
        val isPlaying: Boolean,
        /** The user wants playback running, so it is false while they have paused. */
        val wantsToPlay: Boolean,
        val isEnded: Boolean,
        /** The user is dragging the timeline or a seek they asked for has not settled. */
        val isUserSeeking: Boolean,
        val isOffline: Boolean,
        /** Set on the reading taken after a new seek was requested, otherwise null. */
        val seekRequest: SeekRequest? = null,
    )

    /**
     * What the detector remembers. The two reference values only move when the figure moved by
     * [MIN_MOVE_MS] or more, so slow drift still adds up while jitter does not.
     */
    data class State(
        val lastNowMs: Long,
        val referencePositionMs: Long,
        val referenceBufferedMs: Long,
        /** Since when the position has stood still, or null while it moves or the check is paused. */
        val positionStillSinceMs: Long? = null,
        /** Since when the buffer has not grown, or null while it grows or the check is paused. */
        val bufferStillSinceMs: Long? = null,
        /** Since when a requested seek has waited to settle, or null when none is pending. */
        val pendingSeekSinceMs: Long? = null,
        val pendingSeekTargetMs: Long? = null,
    )

    sealed interface Step {
        data class Continue(val state: State) : Step

        /** The source stalled: the position stood still for [stillForMs]. */
        data class Stalled(val stillForMs: Long) : Step
    }

    /** The state to start from when the detector is armed, at the current reading. */
    fun initial(nowMs: Long, positionMs: Long, bufferedPositionMs: Long): State = State(
        lastNowMs = nowMs,
        referencePositionMs = positionMs,
        referenceBufferedMs = bufferedPositionMs,
    )

    fun step(state: State, sample: Sample): Step {
        val gapMs = sample.nowMs - state.lastNowMs
        val discontinuity = gapMs < 0L || gapMs > MAX_SAMPLE_GAP_MS
        val atEnd = sample.isEnded ||
            (sample.durationMs > 0L && sample.positionMs >= sample.durationMs - END_MARGIN_MS)
        // The player is not trying to play on its own account (paused by something other than the user, or
        // between states): that says nothing about the source either.
        val notTrying = !sample.isLoading && !sample.isPlaying
        val exempt = !sample.wantsToPlay || atEnd || sample.isUserSeeking || sample.isOffline || notTrying

        var pendingSince = state.pendingSeekSinceMs
        var pendingTarget = state.pendingSeekTargetMs
        sample.seekRequest?.let {
            pendingSince = sample.nowMs
            pendingTarget = it.targetMs
        }
        fun fresh(sinceMs: Long?, targetMs: Long?) = initial(sample.nowMs, sample.positionMs, sample.bufferedPositionMs)
            .copy(pendingSeekSinceMs = sinceMs, pendingSeekTargetMs = targetMs)

        // The reading that carries a new request only starts the wait: what it shows is still the old buffer's.
        if (sample.seekRequest != null) return Step.Continue(fresh(pendingSince, pendingTarget))
        if (pendingSince != null) {
            // The age of a pending seek stands still while something else exempts the check or the app was suspended.
            if (discontinuity || exempt) {
                val frozenSince = if (discontinuity) sample.nowMs else pendingSince + gapMs.coerceAtLeast(0L)
                return Step.Continue(fresh(frozenSince, pendingTarget))
            }
            val target = pendingTarget
            val bufferGrew = sample.bufferedPositionMs - state.referenceBufferedMs >= MIN_MOVE_MS
            val playingNearTarget = !sample.isLoading && sample.isPlaying &&
                (target == null || kotlin.math.abs(sample.positionMs - target) <= SEEK_SETTLE_TOLERANCE_MS)
            if (bufferGrew || playingNearTarget) return Step.Continue(fresh(null, null))
            val ageMs = sample.nowMs - pendingSince
            if (ageMs >= SEEK_NEVER_SETTLES_MS) return Step.Stalled(ageMs)
            return Step.Continue(fresh(pendingSince, target))
        }

        if (discontinuity || exempt) return Step.Continue(fresh(null, null))

        val positionMoved = kotlin.math.abs(sample.positionMs - state.referencePositionMs) >= MIN_MOVE_MS
        if (positionMoved) {
            // Forward is playback, backward is a seek: either way the source is alive and the clocks start over.
            return Step.Continue(fresh(null, null))
        }
        val bufferMoved = kotlin.math.abs(sample.bufferedPositionMs - state.referenceBufferedMs) >= MIN_MOVE_MS
        val referenceBufferedMs = if (bufferMoved) sample.bufferedPositionMs else state.referenceBufferedMs
        val positionStillSince = state.positionStillSinceMs ?: sample.nowMs
        val bufferStillSince = if (bufferMoved) {
            // Growth restarts the buffer clock; so does a shrink (a seek flushed it), which is not growth.
            sample.nowMs
        } else {
            state.bufferStillSinceMs ?: sample.nowMs
        }
        val positionStillForMs = sample.nowMs - positionStillSince
        val bufferStillForMs = sample.nowMs - bufferStillSince
        if (positionStillForMs >= TRICKLE_DEADLINE_MS ||
            (positionStillForMs >= STALL_DEADLINE_MS && bufferStillForMs >= STALL_DEADLINE_MS)
        ) {
            return Step.Stalled(positionStillForMs)
        }
        return Step.Continue(
            state.copy(
                lastNowMs = sample.nowMs,
                referenceBufferedMs = referenceBufferedMs,
                positionStillSinceMs = positionStillSince,
                bufferStillSinceMs = bufferStillSince,
            ),
        )
    }
}
