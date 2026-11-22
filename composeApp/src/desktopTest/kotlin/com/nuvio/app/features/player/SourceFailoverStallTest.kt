package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.core.network.NetworkCondition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the planner treats a source that stopped responding after playback had started. */
class SourceFailoverStallTest {

    private fun decide(
        settingEnabled: Boolean = true,
        manualMode: Boolean = false,
        chosenByApp: Boolean = true,
        userCancelled: Boolean = false,
        network: NetworkCondition = NetworkCondition.Online,
        attempts: Int = 0,
        maxAttempts: Int = 3,
        failed: Set<String> = setOf("a"),
    ) = decideSourceFailover(
        failure = SourceFailure.StalledDuringPlayback,
        settingEnabled = settingEnabled,
        manualMode = manualMode,
        failedSourceChosenByApp = chosenByApp,
        userCancelled = userCancelled,
        network = network,
        attempts = attempts,
        maxAttempts = maxAttempts,
        ranked = listOf("a", "b", "c"),
        failedKeys = failed,
        keyOf = { it },
    )

    @Test
    fun aStallMovesOnLikeASourceThatDied() {
        assertEquals(SourceFailoverDecision.TryNext("b"), decide())
    }

    @Test
    fun aStallAppliesToAHandPickedSourceInManualMode() {
        assertEquals(SourceFailoverDecision.TryNext("b"), decide(manualMode = true, chosenByApp = false))
    }

    @Test
    fun aStallObeysTheSettingTheLimitTheFailedSetOfflineAndCancellation() {
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.Disabled), decide(settingEnabled = false))
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.LimitReached), decide(attempts = 3))
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.Exhausted), decide(failed = setOf("a", "b", "c")))
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.Offline), decide(network = NetworkCondition.NoInternet))
        assertEquals(SourceFailoverDecision.Stop(SourceFailoverStop.UserCancelled), decide(userCancelled = true))
    }

    @Test
    fun aStallResumesALittleBeforeWhereItStopped() {
        assertEquals(58_000L, sourceFailoverResumePositionMs(positionMs = 60_000L, durationMs = 3_600_000L))
    }

    private class RecordingController : PlayerEngineController {
        val seekOffsets = mutableListOf<Long>()
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) {
            seekOffsets += offsetMs
        }
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun getAudioTracks(): List<AudioTrack> = emptyList()
        override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }

    @Test
    fun keyboardAndGamepadSeeksRecordTheSeekRequestForStallProtection() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = RecordingController()
            val runtime = PlayerScreenRuntime(
                PlayerScreenArgs(
                    profileId = 1,
                    title = "Example",
                    sourceUrl = "https://cdn.example/video.mkv",
                    sourceAudioUrl = null,
                    sourceHeaders = emptyMap(),
                    sourceResponseHeaders = emptyMap(),
                    streamType = null,
                    providerName = "Addon",
                    streamTitle = "1080p",
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
                    seasonNumber = null,
                    episodeNumber = null,
                    episodeTitle = null,
                    episodeThumbnail = null,
                    contentType = "movie",
                    videoId = "tt1",
                    parentMetaId = "tt1",
                    parentMetaType = "movie",
                    providerAddonId = "addon",
                    torrentInfoHash = null,
                    torrentFileIdx = null,
                    torrentFilename = null,
                    torrentTrackers = emptyList(),
                    initialPositionMs = 0L,
                    initialProgressFraction = null,
                ),
            ).apply {
                this.scope = scope
                playerController = controller
                playbackSnapshot = PlayerPlaybackSnapshot(
                    isLoading = false,
                    isPlaying = true,
                    durationMs = 3_600_000L,
                    positionMs = 60_000L,
                )
            }

            assertEquals(0L, runtime.seekRequestCount)
            assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.SeekForward))
            assertEquals(1L, runtime.seekRequestCount)
            assertNull(runtime.lastSeekTargetMs)
            assertEquals(listOf(10_000L), controller.seekOffsets)

            // Feeding the recorded seek request into PlaybackStallDetector grants the seek allowance past STALL_DEADLINE_MS.
            var stallState = PlaybackStallDetector.initial(0L, 60_000L, 70_000L)
            val firstStep = PlaybackStallDetector.step(
                stallState,
                PlaybackStallDetector.Sample(
                    nowMs = 1_000L,
                    positionMs = 60_000L,
                    bufferedPositionMs = 70_000L,
                    durationMs = 3_600_000L,
                    isLoading = true,
                    isPlaying = false,
                    wantsToPlay = true,
                    isEnded = false,
                    isUserSeeking = false,
                    isOffline = false,
                    seekRequest = PlaybackStallDetector.SeekRequest(runtime.lastSeekTargetMs),
                ),
            )
            stallState = assertIs<PlaybackStallDetector.Step.Continue>(firstStep).state
            val afterOrdinaryDeadline = PlaybackStallDetector.step(
                stallState,
                PlaybackStallDetector.Sample(
                    nowMs = PlaybackStallDetector.STALL_DEADLINE_MS + 10_000L,
                    positionMs = 60_000L,
                    bufferedPositionMs = 70_000L,
                    durationMs = 3_600_000L,
                    isLoading = true,
                    isPlaying = false,
                    wantsToPlay = true,
                    isEnded = false,
                    isUserSeeking = false,
                    isOffline = false,
                    seekRequest = null,
                ),
            )
            assertIs<PlaybackStallDetector.Step.Continue>(afterOrdinaryDeadline)

            assertTrue(runtime.handlePlayerControllerCommand(PlayerCommand.SeekBack))
            assertEquals(2L, runtime.seekRequestCount)
            assertEquals(listOf(10_000L, -10_000L), controller.seekOffsets)
        } finally {
            scope.cancel()
        }
    }
}
