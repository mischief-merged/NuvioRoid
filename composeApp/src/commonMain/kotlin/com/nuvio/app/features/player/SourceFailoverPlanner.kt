package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition

/** How far before the point where a source died the next one resumes, so nothing is missed. */
internal const val SOURCE_FAILOVER_RESUME_BACKOFF_MS = 2_000L

/** The resume point stays at least this far before the end, so a source that died at the end still plays something. */
internal const val SOURCE_FAILOVER_END_MARGIN_MS = 1_000L

/**
 * A source the user picked by hand that never starts is not replaced silently: the error screen
 * stays, because the user chose that source on purpose and can pick another one from the list.
 * Set to `true` to treat a start failure in Manual mode like one in an auto-play mode.
 */
internal const val SOURCE_FAILOVER_ON_START_IN_MANUAL_MODE = false

internal enum class SourceFailure {
    /** The source errored, or showed no first frame in time. */
    FailedToStart,

    /** The source errored after playback had started. */
    DiedDuringPlayback,

    /**
     * The source stopped responding after playback had started: the position stood still, without an
     * error, while the user wanted playback. It is handled like [DiedDuringPlayback] (any source,
     * resuming a little before where it stopped) and has its own message.
     */
    StalledDuringPlayback,

    /**
     * The source plays without an error, but what plays is a short placeholder clip, such as a notice that
     * the file is not ready, and not the video. It applies in every mode: the user never chose a placeholder.
     */
    PlaceholderClip,
}

internal enum class SourceFailoverStop {
    /** The setting is off. */
    Disabled,

    /** Moving on does not apply to this failure, for example a source picked by hand that never started. */
    NotApplicable,

    /** The user pressed Back or picked a source themselves. */
    UserCancelled,

    /** The device is offline, so the failure says nothing about the source. */
    Offline,

    /** The fixed number of sources was already tried. */
    LimitReached,

    /** Every source in the list failed already, or none is left. */
    Exhausted,
}

internal sealed interface SourceFailoverDecision<out T> {
    data class TryNext<out T>(val candidate: T) : SourceFailoverDecision<T>

    data class Stop(val reason: SourceFailoverStop) : SourceFailoverDecision<Nothing>
}

/**
 * Decides what to do after a source failed. It looks at nothing but its arguments.
 *
 * [failedSourceChosenByApp] is true when the app picked the failed source and not the user.
 * [ranked] is the list in the order the user's mode produced it. [failedKeys] holds the identity of
 * every source that already failed in this playback session, [keyOf] gives that identity (a source
 * without one is never treated as failed, the [attempts] limit still bounds it).
 */
internal fun <T> decideSourceFailover(
    failure: SourceFailure,
    settingEnabled: Boolean,
    manualMode: Boolean,
    failedSourceChosenByApp: Boolean,
    userCancelled: Boolean,
    network: NetworkCondition,
    attempts: Int,
    maxAttempts: Int,
    ranked: List<T>,
    failedKeys: Set<String>,
    keyOf: (T) -> String?,
): SourceFailoverDecision<T> {
    if (userCancelled) return SourceFailoverDecision.Stop(SourceFailoverStop.UserCancelled)
    if (!settingEnabled) return SourceFailoverDecision.Stop(SourceFailoverStop.Disabled)
    if (failure == SourceFailure.FailedToStart) {
        // A source the app chose (auto-play, or an earlier move to the next source) may be replaced. A source
        // the user picked by hand may not, unless the constant above says so for Manual mode.
        val appliesToStart = failedSourceChosenByApp || (manualMode && SOURCE_FAILOVER_ON_START_IN_MANUAL_MODE)
        if (!appliesToStart) return SourceFailoverDecision.Stop(SourceFailoverStop.NotApplicable)
    }
    if (network == NetworkCondition.NoInternet || network == NetworkCondition.ServersUnreachable) {
        return SourceFailoverDecision.Stop(SourceFailoverStop.Offline)
    }
    // A placeholder is recognised for certain and costs no wait, so moving on from one is bounded only by
    // the most sources the setting allows, not by the number the user set for failures.
    val limit = if (failure == SourceFailure.PlaceholderClip) {
        SOURCE_FALLBACK_MAX_SOURCES_RANGE.last
    } else {
        maxAttempts.coerceIn(1, SOURCE_FALLBACK_MAX_SOURCES_RANGE.last)
    }
    if (attempts >= limit) return SourceFailoverDecision.Stop(SourceFailoverStop.LimitReached)
    val next = ranked.firstOrNull { candidate ->
        val key = keyOf(candidate)
        key == null || key !in failedKeys
    } ?: return SourceFailoverDecision.Stop(SourceFailoverStop.Exhausted)
    return SourceFailoverDecision.TryNext(next)
}

/**
 * Checked right before a replacement is loaded, after its link was resolved, which can take a
 * while: the device may have gone offline or the user may have left in the meantime. Returns the
 * reason to stop, or null when loading the replacement is fine.
 */
internal fun sourceFailoverStopBeforeLoading(userCancelled: Boolean, network: NetworkCondition): SourceFailoverStop? =
    when {
        userCancelled -> SourceFailoverStop.UserCancelled
        network == NetworkCondition.NoInternet || network == NetworkCondition.ServersUnreachable -> SourceFailoverStop.Offline
        else -> null
    }

/**
 * Where the next source starts after one died at [positionMs]: a couple of seconds earlier, never
 * before the start, and when the length is known never past [SOURCE_FAILOVER_END_MARGIN_MS] before the end.
 */
internal fun sourceFailoverResumePositionMs(positionMs: Long, durationMs: Long): Long {
    val back = (positionMs - SOURCE_FAILOVER_RESUME_BACKOFF_MS).coerceAtLeast(0L)
    return if (durationMs > SOURCE_FAILOVER_END_MARGIN_MS) {
        back.coerceAtMost(durationMs - SOURCE_FAILOVER_END_MARGIN_MS)
    } else {
        back
    }
}

/**
 * A file shorter than this can be a placeholder. Ten minutes, because a provider's "not ready" clip
 * can run to a little over five (one was 300.9 seconds), and the other limit below keeps a short
 * episode or a short film out.
 */
internal const val PLACEHOLDER_MAX_DURATION_MS = 10L * 60_000L

/** A file shorter than this part of the expected running time can be a placeholder. */
internal const val PLACEHOLDER_MAX_FRACTION_OF_RUNTIME = 0.20

/**
 * A suspect source (see assessSourceLink) needs only a short stable length to be confirmed. That
 * limit stays at five minutes: the wider limit above works together with the running time, which a
 * suspect source does not need to have.
 */
internal const val PLACEHOLDER_SUSPECT_MAX_DURATION_MS = 5L * 60_000L

/**
 * A file shorter than this is a placeholder for a film or an episode whatever its running time is said
 * to be, so it needs no running time. One minute: the debrid notices seen run from about 5 to 30 seconds
 * (TorBox's "not cached yet" clip is 8), while the shortest episodes of regular series, anime shorts
 * included, run a couple of minutes. The two minute "being prepared" clip and the five minute one are
 * left to the rules made for them. A title whose known running time is short ([PLACEHOLDER_FLOOR_MIN_RUNTIME_MINUTES])
 * is not judged by this floor, so a one minute short with its running time in the metadata still plays.
 */
internal const val PLACEHOLDER_FLOOR_MS = 60_000L

/** The floor above applies when the running time is unknown, or known to be at least this many minutes. */
internal const val PLACEHOLDER_FLOOR_MIN_RUNTIME_MINUTES = 5

/** Whether a file of [durationMs] is too short to be any film or episode, by [PLACEHOLDER_FLOOR_MS]. */
internal fun isBelowPlaceholderFloor(durationMs: Long, expectedRuntimeMinutes: Int?): Boolean {
    if (durationMs !in 1 until PLACEHOLDER_FLOOR_MS) return false
    val runtime = expectedRuntimeMinutes?.takeIf { it > 0 } ?: return true
    return runtime >= PLACEHOLDER_FLOOR_MIN_RUNTIME_MINUTES
}

/** The placeholder check stops this long after the first frame of a source, whatever the position does. */
internal const val PLACEHOLDER_CHECK_WINDOW_MS = 30_000L

/** The check stops once the user has watched this much of the source: they have seen the content. */
internal const val PLACEHOLDER_WATCHED_LIMIT_MS = 20_000L

/** A position this far before the first frame, or this far ahead of the time that passed, is a seek by the user. */
internal const val PLACEHOLDER_SEEK_TOLERANCE_MS = 2_000L

/** Two reports of the length this close count as the same value. */
internal const val PLACEHOLDER_DURATION_TOLERANCE_MS = 1_000L

/** The length must be unchanged over at least this many snapshots... */
internal const val PLACEHOLDER_MIN_STABLE_SNAPSHOTS = 3

/** ...spanning at least this much time, before it is taken as final. */
internal const val PLACEHOLDER_MIN_STABLE_MS = 3_000L

/**
 * Whether the playing file is a placeholder rather than the video. Both the length of the file
 * and the expected running time (in minutes, from the metadata) must be known, and the file must
 * be both under [PLACEHOLDER_MAX_DURATION_MS] and under [PLACEHOLDER_MAX_FRACTION_OF_RUNTIME] of the
 * running time. With either unknown, nothing is claimed. A still picture on a file of full length
 * is never a placeholder by this rule.
 */
internal fun looksLikePlaceholder(durationMs: Long, expectedRuntimeMinutes: Int?): Boolean {
    if (durationMs <= 0L) return false
    val expectedMs = (expectedRuntimeMinutes ?: return false).takeIf { it > 0 }?.let { it * 60_000L } ?: return false
    return durationMs < PLACEHOLDER_MAX_DURATION_MS && durationMs < expectedMs * PLACEHOLDER_MAX_FRACTION_OF_RUNTIME
}

/**
 * What the placeholder check knows about one source. Times are on a monotonic clock, in milliseconds.
 * [stableDurationMs] is the length of the current run of reports that agree with each other.
 */
internal data class PlaceholderCheckState(
    val firstFrameAtMs: Long,
    val firstFramePositionMs: Long,
    val stableDurationMs: Long = 0L,
    val stableSinceMs: Long = 0L,
    val stableSnapshots: Int = 0,
)

internal sealed interface PlaceholderCheckStep {
    /** Keep looking, with this state. */
    data class Continue(val state: PlaceholderCheckState) : PlaceholderCheckStep

    /** The check is over and claims nothing: it ran out of time, or the user seeked or watched on. */
    data object Finished : PlaceholderCheckStep

    /** The length has settled at [durationMs]; the check is over and this is the value to judge. */
    data class Stable(val durationMs: Long) : PlaceholderCheckStep
}

/**
 * One step of the placeholder check, for one snapshot of the player. The length is judged only once
 * it has settled: the same value (within [PLACEHOLDER_DURATION_TOLERANCE_MS] of the first of the run)
 * over [PLACEHOLDER_MIN_STABLE_SNAPSHOTS] snapshots spanning [PLACEHOLDER_MIN_STABLE_MS], never while the
 * player is loading or buffering. A length that changes, grows or is unknown starts the run again. The
 * check ends [PLACEHOLDER_CHECK_WINDOW_MS] after the first frame whatever the position does (a pause
 * does not extend it), and as soon as the position goes back, jumps ahead of the time that passed, or
 * has moved on by [PLACEHOLDER_WATCHED_LIMIT_MS]. While offline no snapshot is counted, but time still runs.
 */
internal fun stepPlaceholderCheck(
    state: PlaceholderCheckState,
    nowMs: Long,
    positionMs: Long,
    durationMs: Long,
    isLoading: Boolean,
    network: NetworkCondition,
): PlaceholderCheckStep {
    val elapsedMs = nowMs - state.firstFrameAtMs
    val movedMs = positionMs - state.firstFramePositionMs
    val seekedBack = movedMs < -PLACEHOLDER_SEEK_TOLERANCE_MS
    val seekedAhead = movedMs > elapsedMs + PLACEHOLDER_SEEK_TOLERANCE_MS
    if (elapsedMs > PLACEHOLDER_CHECK_WINDOW_MS || seekedBack || seekedAhead || movedMs > PLACEHOLDER_WATCHED_LIMIT_MS) {
        return PlaceholderCheckStep.Finished
    }
    val offline = network == NetworkCondition.NoInternet || network == NetworkCondition.ServersUnreachable
    if (offline) return PlaceholderCheckStep.Continue(state)
    if (isLoading || durationMs <= 0L) return PlaceholderCheckStep.Continue(state.copy(stableSnapshots = 0))
    val sameRun = state.stableSnapshots > 0 &&
        kotlin.math.abs(durationMs - state.stableDurationMs) <= PLACEHOLDER_DURATION_TOLERANCE_MS
    val next = if (sameRun) {
        state.copy(stableSnapshots = state.stableSnapshots + 1)
    } else {
        state.copy(stableDurationMs = durationMs, stableSinceMs = nowMs, stableSnapshots = 1)
    }
    return if (next.stableSnapshots >= PLACEHOLDER_MIN_STABLE_SNAPSHOTS && nowMs - next.stableSinceMs >= PLACEHOLDER_MIN_STABLE_MS) {
        PlaceholderCheckStep.Stable(next.stableDurationMs)
    } else {
        PlaceholderCheckStep.Continue(next)
    }
}

/**
 * Duration-stability state used to gate tracking add-on START events on remote desktop streams.
 * Unlike [PlaceholderCheckState], this never gives up on a seek, a 30-second window, or offline
 * network conditions: it settles only once [PLACEHOLDER_MIN_STABLE_SNAPSHOTS] non-loading snapshots
 * agree on a positive duration across at least [PLACEHOLDER_MIN_STABLE_MS].
 */
internal data class TrackingDurationSettleState(
    val stableDurationMs: Long = 0L,
    val stableSinceMs: Long = 0L,
    val stableSnapshots: Int = 0,
)

internal fun stepTrackingDurationSettle(
    state: TrackingDurationSettleState,
    nowMs: Long,
    durationMs: Long,
    isLoading: Boolean,
): Pair<TrackingDurationSettleState, Boolean> {
    if (isLoading || durationMs <= 0L) {
        return state.copy(stableSnapshots = 0) to false
    }
    val sameRun = state.stableSnapshots > 0 &&
        kotlin.math.abs(durationMs - state.stableDurationMs) <= PLACEHOLDER_DURATION_TOLERANCE_MS
    val next = if (sameRun) {
        state.copy(stableSnapshots = state.stableSnapshots + 1)
    } else {
        state.copy(stableDurationMs = durationMs, stableSinceMs = nowMs, stableSnapshots = 1)
    }
    val settled = next.stableSnapshots >= PLACEHOLDER_MIN_STABLE_SNAPSHOTS &&
        nowMs - next.stableSinceMs >= PLACEHOLDER_MIN_STABLE_MS
    return next to settled
}
