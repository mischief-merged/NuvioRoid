package com.nuvio.app.features.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PlayerPresentationMode {
    Full,
    Mini,
    PictureInPicture,
}

internal data class MiniPlayerPlaybackState(
    val title: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val videoId: String? = null,
    val parentMetaId: String = "",
    val parentMetaType: String = "",
    val sourceUrl: String = "",
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val nextEpisodePlayable: Boolean = false,
    val hasError: Boolean = false,
) {
    val progressFraction: Float
        get() {
            if (durationMs <= 0L) return 0f
            return (positionMs.coerceAtLeast(0L).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        }
}

internal data class PlayerSessionControls(
    val togglePlayback: () -> Unit,
    val resumePlayback: () -> Unit = {},
    val seekForward: () -> Unit,
    val playNextEpisode: (() -> Unit)? = null,
    val requestClose: (onClosed: () -> Unit) -> Unit,
    val requestBack: () -> Unit,
)

internal data class ActivePlayerSession(
    val launchId: Long,
    val launch: PlayerLaunch,
    val mode: PlayerPresentationMode = PlayerPresentationMode.Full,
    val initialFullscreen: Boolean = false,
)

/**
 * Process-wide signal telling background surfaces (such as detail hero trailers) whether an
 * in-app player session is currently running so they stay paused while media plays.
 */
internal object ActivePlayerSessionRegistry {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun setActive(active: Boolean) {
        _isActive.value = active
    }
}

internal fun shouldMinimizePlayerOnBack(
    miniPlayerSupported: Boolean,
    hasError: Boolean,
    isEnded: Boolean,
    isPlaying: Boolean,
    shouldPlay: Boolean,
    initialLoadCompleted: Boolean,
): Boolean =
    miniPlayerSupported &&
        !hasError &&
        !isEnded &&
        (isPlaying || (shouldPlay && initialLoadCompleted))

internal fun matchesActivePlaybackTarget(
    activeState: MiniPlayerPlaybackState?,
    activeLaunch: PlayerLaunch?,
    videoId: String?,
    parentMetaId: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    sourceUrl: String? = null,
): Boolean {
    if (activeState == null && activeLaunch == null) return false
    val currentSourceUrl = activeState?.sourceUrl?.takeIf { it.isNotBlank() }
        ?: activeLaunch?.sourceUrl?.takeIf { it.isNotBlank() }
    if (!sourceUrl.isNullOrBlank() && sourceUrl == currentSourceUrl) {
        return true
    }

    val currentVideoId = activeState?.videoId?.takeIf { it.isNotBlank() }
        ?: activeLaunch?.videoId?.takeIf { it.isNotBlank() }
    val candidateVideoId = videoId?.takeIf { it.isNotBlank() }
    if (currentVideoId != null && candidateVideoId != null && currentVideoId == candidateVideoId) {
        return true
    }

    val currentParentId = activeState?.parentMetaId?.takeIf { it.isNotBlank() }
        ?: activeLaunch?.parentMetaId?.takeIf { it.isNotBlank() }
    val candidateParentId = parentMetaId?.takeIf { it.isNotBlank() }
    if (currentParentId != null && currentParentId == candidateParentId) {
        val currentSeason = activeState?.seasonNumber ?: activeLaunch?.seasonNumber
        val currentEpisode = activeState?.episodeNumber ?: activeLaunch?.episodeNumber
        if (currentSeason != null && currentEpisode != null) {
            return seasonNumber == currentSeason && episodeNumber == currentEpisode
        }
        if (currentSeason == null && currentEpisode == null && seasonNumber == null && episodeNumber == null) {
            return true
        }
    }
    return false
}

/**
 * Owns the active in-app playback session outside route navigation so minimizing or entering
 * picture in picture keeps the player mounted while the main window navigates freely.
 */
@Stable
internal class PlayerSessionController(
    private val onExitPictureInPicture: () -> Unit = ::exitPlayerPictureInPicture,
) {
    var activeSession by mutableStateOf<ActivePlayerSession?>(null)
        private set

    var playbackState by mutableStateOf<MiniPlayerPlaybackState?>(null)
        private set

    var pendingReplacement by mutableStateOf<PendingPlaybackReplacement?>(null)
        private set

    private var boundLaunchId: Long? = null
    private var boundControls by mutableStateOf<PlayerSessionControls?>(null)

    val hasActiveSession: Boolean
        get() = activeSession != null

    val isFullScreen: Boolean
        get() = activeSession?.mode == PlayerPresentationMode.Full

    val isMinimized: Boolean
        get() = activeSession?.mode == PlayerPresentationMode.Mini

    val isPictureInPicture: Boolean
        get() = activeSession?.mode == PlayerPresentationMode.PictureInPicture

    val canPlayNextEpisode: Boolean
        get() = playbackState?.nextEpisodePlayable == true && boundControls?.playNextEpisode != null

    fun open(
        launch: PlayerLaunch,
        launchId: Long = PlayerLaunchStore.put(launch),
        initialFullscreen: Boolean = false,
    ): ActivePlayerSession {
        val session = ActivePlayerSession(
            launchId = launchId,
            launch = launch,
            mode = PlayerPresentationMode.Full,
            initialFullscreen = initialFullscreen,
        )
        boundLaunchId = null
        boundControls = null
        activeSession = session
        playbackState = MiniPlayerPlaybackState(
            title = launch.title,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
            episodeTitle = launch.episodeTitle,
            videoId = launch.videoId,
            parentMetaId = launch.parentMetaId,
            parentMetaType = launch.parentMetaType,
            sourceUrl = launch.sourceUrl,
            isPlaying = true,
            isLoading = true,
            positionMs = launch.initialPositionMs.coerceAtLeast(0L),
            durationMs = 0L,
        )
        ActivePlayerSessionRegistry.setActive(true)
        return session
    }

    fun minimize(): Boolean {
        val current = activeSession ?: return false
        val wasPip = current.mode == PlayerPresentationMode.PictureInPicture
        if (current.mode != PlayerPresentationMode.Mini) {
            activeSession = current.copy(mode = PlayerPresentationMode.Mini)
        }
        if (wasPip) {
            onExitPictureInPicture()
        }
        return true
    }

    fun restoreFullScreen(): Boolean {
        val current = activeSession ?: return false
        val wasPip = current.mode == PlayerPresentationMode.PictureInPicture
        if (current.mode != PlayerPresentationMode.Full) {
            activeSession = current.copy(mode = PlayerPresentationMode.Full)
        }
        if (wasPip) {
            onExitPictureInPicture()
        }
        return true
    }

    fun setPictureInPicture(enabled: Boolean): Boolean {
        val current = activeSession ?: return false
        if (enabled) {
            if (current.mode == PlayerPresentationMode.PictureInPicture) return true
            activeSession = current.copy(mode = PlayerPresentationMode.PictureInPicture)
            return true
        }
        if (current.mode == PlayerPresentationMode.PictureInPicture) {
            activeSession = current.copy(mode = PlayerPresentationMode.Full)
            return true
        }
        return false
    }

    fun matchesActiveTarget(
        videoId: String?,
        parentMetaId: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        sourceUrl: String? = null,
    ): Boolean {
        val current = activeSession ?: return false
        return matchesActivePlaybackTarget(
            activeState = playbackState,
            activeLaunch = current.launch,
            videoId = videoId,
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            sourceUrl = sourceUrl,
        )
    }

    fun reopenIfMatching(
        videoId: String?,
        parentMetaId: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        sourceUrl: String? = null,
    ): Boolean {
        val current = activeSession ?: return false
        if (!matchesActiveTarget(videoId, parentMetaId, seasonNumber, episodeNumber, sourceUrl)) {
            return false
        }
        restoreFullScreen()
        if (playbackState?.isPlaying == false && boundLaunchId == current.launchId) {
            boundControls?.resumePlayback?.invoke()
        }
        return true
    }

    fun launchOrAskToReplace(
        incomingTitle: String,
        videoId: String?,
        parentMetaId: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        sourceUrl: String? = null,
        allowSameTargetReopen: Boolean = true,
        onProceed: () -> Unit,
    ) {
        val current = activeSession
        when (
            val decision = decidePlaybackLaunch(
                activeState = playbackState,
                activeLaunch = current?.launch,
                incomingTitle = incomingTitle,
                videoId = videoId,
                parentMetaId = parentMetaId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                sourceUrl = sourceUrl,
                allowSameTargetReopen = allowSameTargetReopen,
            )
        ) {
            ReplacePlaybackDecision.ProceedNow -> onProceed()
            ReplacePlaybackDecision.ReopenActive -> {
                reopenIfMatching(
                    videoId = videoId,
                    parentMetaId = parentMetaId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    sourceUrl = sourceUrl,
                )
            }
            is ReplacePlaybackDecision.AskFirst -> {
                pendingReplacement = PendingPlaybackReplacement(
                    currentTitle = decision.currentTitle,
                    incomingTitle = decision.incomingTitle,
                    onConfirm = onProceed,
                )
            }
        }
    }

    fun confirmPendingReplacement() {
        val pending = pendingReplacement ?: return
        pendingReplacement = null
        closeActiveSession {
            pending.onConfirm()
        }
    }

    fun cancelPendingReplacement() {
        pendingReplacement = null
    }

    fun updatePlaybackState(launchId: Long, state: MiniPlayerPlaybackState) {
        if (activeSession?.launchId != launchId) return
        playbackState = state
    }

    fun bindControls(launchId: Long, controls: PlayerSessionControls?) {
        if (controls == null) {
            if (boundLaunchId == launchId) {
                boundLaunchId = null
                boundControls = null
            }
            return
        }
        if (activeSession?.launchId != launchId) return
        boundLaunchId = launchId
        boundControls = controls
    }

    fun togglePlayback() {
        val current = activeSession ?: return
        if (boundLaunchId == current.launchId) {
            boundControls?.togglePlayback?.invoke()
        }
    }

    fun seekForward() {
        val current = activeSession ?: return
        if (boundLaunchId == current.launchId) {
            boundControls?.seekForward?.invoke()
        }
    }

    fun playNextEpisode() {
        val current = activeSession ?: return
        if (boundLaunchId == current.launchId) {
            boundControls?.playNextEpisode?.invoke()
        }
    }

    fun dispatchSystemBack() {
        val current = activeSession ?: return
        if (boundLaunchId == current.launchId) {
            boundControls?.requestBack?.invoke()
        }
    }

    fun closeActiveSession(onClosed: () -> Unit = {}) {
        val current = activeSession ?: run {
            onClosed()
            return
        }
        val controls = boundControls.takeIf { boundLaunchId == current.launchId }
        if (controls != null) {
            controls.requestClose(onClosed)
        } else {
            ResumePromptRepository.markPlayerExitedNormally()
            PlayerLaunchStore.remove(current.launchId)
            clearSession(current.launchId)
            onClosed()
        }
    }

    fun clearSession(launchId: Long): Boolean {
        if (activeSession?.launchId != launchId) return false
        activeSession = null
        playbackState = null
        pendingReplacement = null
        if (boundLaunchId == launchId) {
            boundLaunchId = null
            boundControls = null
        }
        ActivePlayerSessionRegistry.setActive(false)
        return true
    }
}
