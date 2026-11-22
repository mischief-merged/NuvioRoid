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

/**
 * Owns the active in-app playback session outside route navigation so minimizing or entering
 * picture in picture keeps the player mounted while the main window navigates freely.
 */
@Stable
internal class PlayerSessionController {
    var activeSession by mutableStateOf<ActivePlayerSession?>(null)
        private set

    var playbackState by mutableStateOf<MiniPlayerPlaybackState?>(null)
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
        if (current.mode == PlayerPresentationMode.Mini) return true
        activeSession = current.copy(mode = PlayerPresentationMode.Mini)
        return true
    }

    fun restoreFullScreen(): Boolean {
        val current = activeSession ?: return false
        if (current.mode == PlayerPresentationMode.Full) return true
        activeSession = current.copy(mode = PlayerPresentationMode.Full)
        return true
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
        if (boundLaunchId == launchId) {
            boundLaunchId = null
            boundControls = null
        }
        ActivePlayerSessionRegistry.setActive(false)
        return true
    }
}
