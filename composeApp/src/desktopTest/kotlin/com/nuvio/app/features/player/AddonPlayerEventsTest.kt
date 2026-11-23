package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.addons.AddonPlayerAction
import com.nuvio.app.features.addons.AddonPlayerEventTarget
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonPlayerEventsTest {
    private data class RecordedEvent(
        val target: AddonPlayerEventTarget,
        val action: AddonPlayerAction,
        val positionMs: Long,
        val durationMs: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = mutableListOf<RecordedEvent>()
    private var clockNowMs = 0L

    @AfterTest
    fun cleanup() = scope.cancel()

    private fun deliverSnapshot(
        runtime: PlayerScreenRuntime,
        nowMs: Long,
        durationMs: Long,
        positionMs: Long,
        isPlaying: Boolean = true,
        isLoading: Boolean = false,
        isEnded: Boolean = false,
    ) {
        clockNowMs = nowMs
        val snapshot = PlayerPlaybackSnapshot(
            isLoading = isLoading,
            isPlaying = isPlaying,
            isEnded = isEnded,
            durationMs = durationMs,
            positionMs = positionMs,
        )
        runtime.playbackSnapshot = snapshot
        if (!isLoading) {
            runtime.initialLoadCompleted = true
            runtime.announceSourceFailoverIfPending()
        }
        runtime.checkForPlaceholderSource(snapshot)
        runtime.syncAddonPlayerStartForPlayback()
        if (!isLoading) {
            runtime.previousIsPlaying = isPlaying
        }
    }

    private fun settlePlayback(
        runtime: PlayerScreenRuntime,
        durationMs: Long,
        startPositionMs: Long = 0L,
        startClockMs: Long = clockNowMs,
    ) {
        deliverSnapshot(runtime, nowMs = startClockMs, durationMs = durationMs, positionMs = startPositionMs)
        deliverSnapshot(runtime, nowMs = startClockMs + 1_500L, durationMs = durationMs, positionMs = startPositionMs + 1_500L)
        deliverSnapshot(runtime, nowMs = startClockMs + 3_000L, durationMs = durationMs, positionMs = startPositionMs + 3_000L)
    }

    private class ListSearch(private val streams: List<StreamItem>) : FailoverSourceSearch {
        override fun start(type: String, videoId: String, season: Int?, episode: Int?) = Unit
        override fun state() = StreamsUiState(
            groups = listOf(AddonStreamGroup(addonName = "Comet", addonId = "comet", streams = streams, isLoading = false)),
            isAnyLoading = false,
        )
        override fun stop() = Unit
    }

    private fun stream(name: String, url: String?) = StreamItem(
        name = name,
        url = url,
        addonName = "Comet",
        addonId = "comet",
    )

    private fun runtime(
        sourceUrl: String = "https://cdn.example/show/s01e02.mkv",
        runtimeMinutes: Int? = 45,
        activeStream: StreamItem? = stream("[TB⚡] Comet 1080p", sourceUrl),
        search: FailoverSourceSearch = ListSearch(emptyList()),
    ): PlayerScreenRuntime = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Example Show",
            sourceUrl = sourceUrl,
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Comet",
            streamTitle = activeStream?.name ?: "1080p",
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
            episodeTitle = "Episode 2",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt1234567:1:2",
            parentMetaId = "tt1234567",
            parentMetaType = "series",
            providerAddonId = "comet",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@AddonPlayerEventsTest.scope
        playerSettingsUiState = PlayerSettingsUiState()
        this.activeStream = activeStream
        failoverSourceSearch = search
        placeholderNowMs = { clockNowMs }
        dispatchAddonPlayerEvent = { target, action, positionMs, durationMs ->
            events += RecordedEvent(target, action, positionMs, durationMs)
        }
        if (runtimeMinutes != null) {
            playerMeta = MetaDetails(
                id = "tt1234567",
                type = "series",
                name = "Example Show",
                videos = listOf(
                    MetaVideo(
                        id = "tt1234567:1:2",
                        title = "Episode 2",
                        season = 1,
                        episode = 2,
                        runtime = runtimeMinutes,
                    ),
                ),
            )
        }
    }

    @Test
    fun three_minute_placeholder_rejected_by_runtime_comparison_sends_no_events() {
        val runtime = runtime(runtimeMinutes = 45)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 180_000L,
            positionMs = 5_000L,
        )

        runtime.syncAddonPlayerStartForPlayback()
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertTrue(events.isEmpty(), "expected no events for 3-minute placeholder, got $events")
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun static_status_clip_url_sends_no_events_even_without_runtime() {
        val statusUrl = "https://provider.example/static/downloading.mp4"
        val runtime = runtime(
            sourceUrl = statusUrl,
            runtimeMinutes = null,
            activeStream = stream("Addon 1080p", statusUrl),
        )
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 300_000L,
            positionMs = 2_000L,
        )

        runtime.syncAddonPlayerStartForPlayback()
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertTrue(events.isEmpty(), "expected no events for static status clip, got $events")
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun uncached_suspect_clip_under_five_minutes_sends_no_events() {
        val url = "https://debrid.example/clip.mkv"
        val runtime = runtime(
            sourceUrl = url,
            runtimeMinutes = null,
            activeStream = stream("[TB⬇️] Comet 1080p", url),
        )
        runtime.checkForPlaceholderBySourceSignals()
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 240_000L,
            positionMs = 1_000L,
        )

        runtime.syncAddonPlayerStartForPlayback()
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertTrue(events.isEmpty(), "expected no events for uncached suspect clip, got $events")
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun failed_clip_sends_no_events() {
        val runtime = runtime(runtimeMinutes = 45)
        runtime.errorMessage = "Playback failed"
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 2_700_000L,
            positionMs = 1_000L,
        )

        runtime.syncAddonPlayerStartForPlayback()
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertTrue(events.isEmpty())
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun zero_duration_start_followed_by_short_placeholder_duration_sends_no_events() {
        val runtime = runtime(runtimeMinutes = 45)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 0L,
            positionMs = 0L,
        )
        runtime.syncAddonPlayerStartForPlayback()
        runtime.previousIsPlaying = true

        assertTrue(events.isEmpty(), "must not send START before duration is known")

        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            durationMs = 8_000L,
            positionMs = 2_000L,
        )
        runtime.syncAddonPlayerStartForPlayback()
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertTrue(events.isEmpty(), "placeholder must never send START or STOP")
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun provisional_full_length_followed_by_short_placeholder_sends_no_events() {
        val runtime = runtime(runtimeMinutes = 45)
        // Engine initially reports a provisional full-length duration before settling.
        deliverSnapshot(runtime, nowMs = 0L, durationMs = 2_700_000L, positionMs = 0L)
        deliverSnapshot(runtime, nowMs = 1_000L, durationMs = 2_700_000L, positionMs = 1_000L)
        assertTrue(events.isEmpty(), "must not send START before the duration check settles, got $events")

        // Duration drops to a short placeholder clip and settles there.
        deliverSnapshot(runtime, nowMs = 2_000L, durationMs = 15_000L, positionMs = 2_000L)
        deliverSnapshot(runtime, nowMs = 3_500L, durationMs = 15_000L, positionMs = 3_500L)
        deliverSnapshot(runtime, nowMs = 5_000L, durationMs = 15_000L, positionMs = 5_000L)
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertTrue(events.isEmpty(), "placeholder must never send START or STOP, got $events")
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun late_duration_for_real_episode_defers_start_until_settled_then_sends_one_stop() {
        val runtime = runtime(runtimeMinutes = 45)
        deliverSnapshot(runtime, nowMs = 0L, durationMs = 0L, positionMs = 0L)
        assertTrue(events.isEmpty())

        // Real duration arrives while already playing; START waits until 3 stable snapshots span 3 seconds.
        deliverSnapshot(runtime, nowMs = 1_000L, durationMs = 2_700_000L, positionMs = 1_000L)
        deliverSnapshot(runtime, nowMs = 2_500L, durationMs = 2_700_000L, positionMs = 2_500L)
        assertTrue(events.isEmpty(), "must not send START before duration settles")

        deliverSnapshot(runtime, nowMs = 4_000L, durationMs = 2_700_000L, positionMs = 4_000L)
        // Repeated tick does not send a second START.
        deliverSnapshot(runtime, nowMs = 5_000L, durationMs = 2_700_000L, positionMs = 5_000L)

        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })
        assertEquals(AddonPlayerEventTarget("series", "tt1234567:1:2"), runtime.activeAddonPlayerEventTarget)

        // End of clip flushes STOP, then leaving the player flushes again: only one STOP is sent.
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = false,
            isEnded = true,
            durationMs = 2_700_000L,
            positionMs = 2_700_000L,
        )
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.STOP), events.map { it.action })
        assertNull(runtime.activeAddonPlayerEventTarget)
        assertNull(runtime.lastAddonPlayerAction)
    }

    @Test
    fun active_start_still_receives_stop_even_if_duration_later_reports_short_or_zero() {
        val runtime = runtime(runtimeMinutes = 45)
        settlePlayback(runtime, durationMs = 2_700_000L, startPositionMs = 60_000L)
        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })

        // Engine reports short duration during teardown before STOP.
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = false,
            durationMs = 8_000L,
            positionMs = 8_000L,
        )
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
        runtime.emitAddonPlayerEvent(AddonPlayerAction.STOP)

        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.STOP), events.map { it.action })
        assertNull(runtime.activeAddonPlayerEventTarget)
        assertNull(runtime.lastAddonPlayerAction)
    }

    @Test
    fun mid_playback_failover_pairs_each_source_start_with_one_stop() {
        val firstUrl = "https://cdn.example/show/first.mkv"
        val secondUrl = "https://cdn.example/show/second.mkv"
        val first = stream("[TB⚡] Comet First", firstUrl)
        val second = stream("[TB⚡] Comet Second", secondUrl)
        val runtime = runtime(
            sourceUrl = firstUrl,
            runtimeMinutes = 45,
            activeStream = first,
            search = ListSearch(listOf(first, second)),
        )
        settlePlayback(runtime, durationMs = 2_700_000L, startPositionMs = 120_000L)
        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })

        // First source stalls/errors and fails over to the second source.
        assertTrue(runtime.tryFallbackToNextSourceAfterError("Source stalled", SourceFailure.StalledDuringPlayback))
        val deadline = System.currentTimeMillis() + 10_000
        while (runtime.activeSourceUrl == firstUrl && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(secondUrl, runtime.activeSourceUrl)

        // Exactly one STOP for the failed source, even though both failover start and replacement flush ran.
        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.STOP), events.map { it.action })
        assertNull(runtime.activeAddonPlayerEventTarget)

        // Replacement source settles, plays, and then user leaves the player.
        runtime.previousIsPlaying = false
        settlePlayback(runtime, durationMs = 2_700_000L, startPositionMs = 115_000L, startClockMs = 10_000L)
        runtime.cancelSourceFailoverOnExit()
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertEquals(
            listOf(AddonPlayerAction.START, AddonPlayerAction.STOP, AddonPlayerAction.START, AddonPlayerAction.STOP),
            events.map { it.action },
        )
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun real_short_episode_matching_expected_runtime_is_accepted() {
        val runtime = runtime(runtimeMinutes = 3)
        settlePlayback(runtime, durationMs = 180_000L, startPositionMs = 5_000L)
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.STOP), events.map { it.action })
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun placeholder_failover_clears_placeholder_flag_and_tracks_healthy_replacement() {
        val placeholderUrl = "https://provider.example/static/downloading.mp4"
        val healthyUrl = "https://cdn.example/show/healthy.mkv"
        val placeholder = stream("[TB⚡] Comet Placeholder", placeholderUrl)
        val healthy = stream("[TB⚡] Comet Healthy", healthyUrl)
        val runtime = runtime(
            sourceUrl = placeholderUrl,
            runtimeMinutes = 45,
            activeStream = placeholder,
            search = ListSearch(listOf(placeholder, healthy)),
        )
        runtime.autoSelectedSourceUrl = placeholderUrl
        runtime.checkForPlaceholderBySourceSignals()

        val deadline = System.currentTimeMillis() + 10_000
        while (runtime.activeSourceUrl == placeholderUrl && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(healthyUrl, runtime.activeSourceUrl)
        assertTrue(events.isEmpty(), "placeholder must never send tracking events, got $events")

        // Replacement settles and emits START, then one STOP on exit.
        runtime.previousIsPlaying = false
        settlePlayback(runtime, durationMs = 2_700_000L, startPositionMs = 2_000L, startClockMs = 10_000L)
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.STOP), events.map { it.action })
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun provisional_full_length_with_seek_then_short_placeholder_sends_no_events() {
        val runtime = runtime(runtimeMinutes = 45)
        deliverSnapshot(runtime, nowMs = 0L, durationMs = 2_700_000L, positionMs = 0L)
        // User seeks ahead before 3s: placeholder failover check finishes, but tracking START still waits for settled duration.
        deliverSnapshot(runtime, nowMs = 1_000L, durationMs = 2_700_000L, positionMs = 600_000L)
        assertTrue(events.isEmpty(), "seek must not send START before duration settles, got $events")

        // Duration drops to a short placeholder clip and settles there.
        deliverSnapshot(runtime, nowMs = 2_000L, durationMs = 15_000L, positionMs = 2_000L)
        deliverSnapshot(runtime, nowMs = 3_500L, durationMs = 15_000L, positionMs = 3_500L)
        deliverSnapshot(runtime, nowMs = 5_000L, durationMs = 15_000L, positionMs = 5_000L)
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertTrue(events.isEmpty(), "placeholder after seek must never send START or STOP, got $events")
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun provisional_full_length_past_30s_window_then_short_placeholder_sends_no_events() {
        val runtime = runtime(runtimeMinutes = 45)
        deliverSnapshot(runtime, nowMs = 0L, durationMs = 2_700_000L, positionMs = 0L)
        // Buffering lasts past the 30-second placeholder window: placeholder failover gives up, but tracking START still waits.
        deliverSnapshot(runtime, nowMs = 10_000L, durationMs = 2_700_000L, positionMs = 0L, isLoading = true)
        deliverSnapshot(runtime, nowMs = 31_000L, durationMs = 2_700_000L, positionMs = 500L, isLoading = false)
        assertTrue(events.isEmpty(), "30s window expiry must not send START before duration settles, got $events")

        // Duration drops to a short placeholder and settles there.
        deliverSnapshot(runtime, nowMs = 32_000L, durationMs = 15_000L, positionMs = 1_500L)
        deliverSnapshot(runtime, nowMs = 33_500L, durationMs = 15_000L, positionMs = 3_000L)
        deliverSnapshot(runtime, nowMs = 35_000L, durationMs = 15_000L, positionMs = 4_500L)
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertTrue(events.isEmpty(), "placeholder after 30s window must never send START or STOP, got $events")
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun healthy_stream_after_early_seek_still_sends_start_once_duration_settles() {
        val runtime = runtime(runtimeMinutes = 45)
        deliverSnapshot(runtime, nowMs = 0L, durationMs = 2_700_000L, positionMs = 0L)
        // User seeks immediately; placeholder failover gives up (Finished), but tracking settle continues.
        deliverSnapshot(runtime, nowMs = 1_000L, durationMs = 2_700_000L, positionMs = 600_000L)
        assertTrue(events.isEmpty(), "must still wait for 3s stability after seek")

        deliverSnapshot(runtime, nowMs = 3_000L, durationMs = 2_700_000L, positionMs = 602_000L)
        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })

        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.STOP), events.map { it.action })
    }

    @Test
    fun offline_local_file_sends_start_and_stop_even_for_brief_playback() {
        val localUrl = "file:///Users/test/Downloads/episode.mkv"
        val runtime = runtime(
            sourceUrl = localUrl,
            runtimeMinutes = 45,
            activeStream = null,
        ).apply {
            placeholderNetworkCondition = { com.nuvio.app.core.network.NetworkCondition.NoInternet }
        }

        // Single brief snapshot while offline: local container duration is final and sends START immediately.
        deliverSnapshot(runtime, nowMs = 0L, durationMs = 2_700_000L, positionMs = 500L)
        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })

        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.STOP), events.map { it.action })
        assertNull(runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun sleep_timer_end_of_episode_at_eof_sends_pause_not_stop_and_exit_does_not_stop() {
        val runtime = runtime(runtimeMinutes = 45)
        settlePlayback(runtime, durationMs = 2_700_000L, startPositionMs = 2_600_000L)
        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })

        runtime.selectSleepTimerOption(SleepTimerOption.EndOfEpisode)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = false,
            isEnded = true,
            durationMs = 2_700_000L,
            positionMs = 2_700_000L,
        )
        runtime.advanceSleepTimer(clockNowMs + 5_000L)
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)
        // Player disposal runs DisposableEffect(Unit) then DisposableEffect(videoId, ...).
        runtime.emitAddonPlayerProgressEvent()
        runtime.flushWatchProgress(TrackingScrobbleAction.STOP)

        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.PAUSE), events.map { it.action })
        assertEquals(AddonPlayerEventTarget("series", "tt1234567:1:2"), runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun sleep_timer_hold_on_exit_before_eof_sends_pause_not_stop() {
        val runtime = runtime(runtimeMinutes = 45)
        settlePlayback(runtime, durationMs = 2_700_000L, startPositionMs = 2_500_000L)
        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })

        runtime.selectSleepTimerOption(SleepTimerOption.EndOfEpisode)
        // Exit while still playing: DisposableEffect(Unit) runs before the flush effect.
        runtime.emitAddonPlayerProgressEvent()
        runtime.flushWatchProgress()

        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.PAUSE), events.map { it.action })
        assertEquals(AddonPlayerEventTarget("series", "tt1234567:1:2"), runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun sleep_timer_stopped_with_zero_duration_teardown_never_sends_stop() {
        val runtime = runtime(runtimeMinutes = 45)
        settlePlayback(runtime, durationMs = 2_700_000L, startPositionMs = 2_500_000L)
        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })

        runtime.sleepTimer = SleepTimerState(
            phase = SleepTimerPhase.Stopped,
            holdIncomplete = true,
            holdPositionMs = 2_500_000L,
        )
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = false,
            isEnded = false,
            durationMs = 0L,
            positionMs = 2_500_000L,
        )
        runtime.emitAddonPlayerProgressEvent()
        runtime.flushWatchProgress()

        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })
        assertEquals(AddonPlayerEventTarget("series", "tt1234567:1:2"), runtime.activeAddonPlayerEventTarget)
    }

    @Test
    fun normal_exit_without_sleep_hold_sends_stop() {
        val runtime = runtime(runtimeMinutes = 45)
        settlePlayback(runtime, durationMs = 2_700_000L, startPositionMs = 60_000L)
        assertEquals(listOf(AddonPlayerAction.START), events.map { it.action })

        runtime.emitAddonPlayerProgressEvent()
        runtime.flushWatchProgress()

        assertEquals(listOf(AddonPlayerAction.START, AddonPlayerAction.STOP), events.map { it.action })
        assertNull(runtime.activeAddonPlayerEventTarget)
    }
}
