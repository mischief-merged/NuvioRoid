package com.nuvio.app.features.player

/**
 * Judges a source that was just handed to the player and has not started playing yet. It looks at
 * progress, not at the clock alone: data that keeps arriving (a buffer or a position moving past the
 * resume point) means the source works, however long the first frame takes.
 *
 * A source that shows nothing at all gets [Limits.noSignalMs]. One that has shown a sign of life (a known
 * length, buffered data) gets the longer [Limits.signOfLifeMs]. One that moved and then stood still gets
 * up on after [Limits.idleAfterProgressMs] without a new advance. [Limits.overallMs] caps the whole start,
 * for a source whose buffer creeps forever and never plays.
 *
 * Pure: the caller passes the time spent since the source was handed over with every reading, so how
 * often the player is polled does not change the meaning of the limits.
 */
internal object SourceStartMonitor {

    const val DEFAULT_NO_SIGNAL_MS = 20_000L
    const val DEFAULT_SIGN_OF_LIFE_MS = 35_000L
    const val DEFAULT_IDLE_AFTER_PROGRESS_MS = 12_000L
    const val DEFAULT_OVERALL_MS = 60_000L

    /** How often the caller should read the player. */
    const val POLL_INTERVAL_MS = 1_000L

    /** Two reads further apart than this mean the app was suspended or hung, so the start clocks begin again. */
    const val MAX_READING_GAP_MS = 5_000L

    data class Limits(
        val noSignalMs: Long = DEFAULT_NO_SIGNAL_MS,
        val signOfLifeMs: Long = DEFAULT_SIGN_OF_LIFE_MS,
        val idleAfterProgressMs: Long = DEFAULT_IDLE_AFTER_PROGRESS_MS,
        val overallMs: Long = DEFAULT_OVERALL_MS,
    ) {
        companion object {
            /**
             * The limits for the user's "time for a source to start" setting, which is how long a source that
             * shows nothing gets. The longer limits never drop below their defaults and stay past it.
             */
            fun fromSettingSeconds(seconds: Int): Limits {
                val noSignal = seconds.coerceAtLeast(1) * 1_000L
                val signOfLife = maxOf(DEFAULT_SIGN_OF_LIFE_MS, noSignal)
                return Limits(
                    noSignalMs = noSignal,
                    signOfLifeMs = signOfLife,
                    overallMs = maxOf(DEFAULT_OVERALL_MS, signOfLife + 15_000L),
                )
            }
        }
    }

    /** One reading of the player. [resumeFromMs] is where this play began: the resume point, or 0. */
    data class Reading(
        val elapsedMs: Long,
        val playing: Boolean,
        val positionMs: Long,
        val bufferedMs: Long,
        val durationMs: Long,
        val resumeFromMs: Long = 0L,
    ) {
        /**
         * How far the play got past where it began. A player reports its seek target as its position before
         * it fetched anything, so progress is always measured from the resume point.
         */
        val advancedMs: Long get() = (maxOf(positionMs, bufferedMs) - resumeFromMs).coerceAtLeast(0L)

        /** A known length or buffered data: the source answered, even if nothing moved yet. */
        val showsLife: Boolean get() = advancedMs > 0L || durationMs > 0L || bufferedMs > 0L
    }

    /** What has been seen so far. Start from [Progress] and pass each step's state back in. */
    data class Progress(
        val furthestMs: Long = 0L,
        val lastAdvanceAtMs: Long = 0L,
        val sawLife: Boolean = false,
    )

    enum class GiveUpCause {
        /** Nothing moved before the no-signal (or sign-of-life) limit. */
        NothingMoved,

        /** It moved, then stood still for the idle limit. */
        WentIdle,

        /** It was still creeping at the overall cap. */
        TooSlow,
    }

    sealed interface Step {
        data class Pending(val progress: Progress) : Step

        /** Playing and past the resume point: the start is over. */
        data object Playing : Step

        data class GiveUp(val cause: GiveUpCause) : Step
    }

    fun next(progress: Progress, reading: Reading, limits: Limits = Limits()): Step {
        // Playing counts only with real progress: a player may claim to play at its resume point with nothing buffered.
        if (reading.playing && reading.advancedMs > 0L) return Step.Playing
        val moved = reading.advancedMs > progress.furthestMs
        val seen = Progress(
            furthestMs = maxOf(progress.furthestMs, reading.advancedMs),
            lastAdvanceAtMs = if (moved) reading.elapsedMs else progress.lastAdvanceAtMs,
            sawLife = progress.sawLife || reading.showsLife,
        )
        if (reading.elapsedMs >= limits.overallMs) return Step.GiveUp(GiveUpCause.TooSlow)
        if (seen.furthestMs == 0L) {
            val limit = if (seen.sawLife) limits.signOfLifeMs else limits.noSignalMs
            return if (reading.elapsedMs >= limit) Step.GiveUp(GiveUpCause.NothingMoved) else Step.Pending(seen)
        }
        val idleFor = reading.elapsedMs - seen.lastAdvanceAtMs
        return if (idleFor >= limits.idleAfterProgressMs) Step.GiveUp(GiveUpCause.WentIdle) else Step.Pending(seen)
    }
}

/**
 * The time spent starting one source, measured between reads. It stands still while [held] (the user paused,
 * the device is offline) and starts over when two reads are further apart than
 * [SourceStartMonitor.MAX_READING_GAP_MS], which is a sleeping or hung machine and says nothing about the source.
 */
internal class SourceStartClock(nowMs: Long) {
    private var startedAtMs = nowMs
    private var lastReadMs = nowMs

    /** Whether the last [read] found a gap and started over. */
    var restarted = false
        private set

    /** Time spent starting as of [nowMs]. */
    fun read(nowMs: Long, held: Boolean): Long {
        val gap = nowMs - lastReadMs
        restarted = gap < 0L || gap > SourceStartMonitor.MAX_READING_GAP_MS
        if (restarted) startedAtMs = nowMs else if (held) startedAtMs += gap
        lastReadMs = nowMs
        return nowMs - startedAtMs
    }

    fun restart(nowMs: Long) {
        startedAtMs = nowMs
        lastReadMs = nowMs
        restarted = false
    }

    fun holdUntil(nowMs: Long) {
        val gap = nowMs - lastReadMs
        if (gap > 0L) {
            startedAtMs += gap
        } else if (gap < 0L) {
            startedAtMs = nowMs
        }
        lastReadMs = nowMs
        restarted = false
    }
}
