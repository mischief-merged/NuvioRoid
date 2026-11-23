package com.nuvio.app.features.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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
    @Volatile
    private var boundController: PlayerSessionController? = null

    fun setActive(active: Boolean) {
        _isActive.value = active
    }

    fun bindController(controller: PlayerSessionController) {
        boundController = controller
    }

    fun unbindController(controller: PlayerSessionController) {
        if (boundController === controller) {
            boundController = null
        }
    }

    fun closeActiveSessionBeforeQuit(onCompleted: () -> Unit) {
        val controller = boundController
        // A launch still preparing subtitles has no in-app session, but it must not
        // open the external player while the app is quitting.
        controller?.cancelPendingExternalPlayback()
        if (controller == null || !controller.hasActiveSession) {
            onCompleted()
            return
        }
        controller.closeActiveSession(
            onClosed = onCompleted,
            onFailed = onCompleted,
        )
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
    if (currentVideoId != null && candidateVideoId != null) {
        return currentVideoId == candidateVideoId
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
    private val previewsAllowed: () -> Boolean = {
        com.nuvio.app.features.downloads.LimitedConnections.hold.state.value.travel.previewsAllowed
    },
) {
    var activeSession by mutableStateOf<ActivePlayerSession?>(null)
        private set

    var playbackState by mutableStateOf<MiniPlayerPlaybackState?>(null)
        private set

    var pendingReplacement by mutableStateOf<PendingPlaybackReplacement?>(null)
        private set

    /**
     * An external launch whose subtitles or skip segments are still being prepared.
     * The in-app session is already gone during that wait, but playback is not.
     */
    var pendingExternalPlayback by mutableStateOf<PlayerLaunch?>(null)
        private set

    private val externalPlaybackLock = SynchronizedObject()
    private var externalPlaybackToken = 0L

    private var boundLaunchId: Long? = null
    private var boundControls by mutableStateOf<PlayerSessionControls?>(null)
    private var closingLaunchId: Long? = null
    private val pendingCloseCallbacks = mutableListOf<() -> Unit>()
    private val pendingCloseFailureCallbacks = mutableListOf<() -> Unit>()
    private var confirmedReplacementCallback: (() -> Unit)? = null
    private var confirmedReplacementRequiresPreview: Boolean = false

    val hasActiveSession: Boolean
        get() = activeSession != null

    /** In-app session, or an external launch still preparing. Replacement prompts use this. */
    val hasActivePlayback: Boolean
        get() = activeSession != null || pendingExternalPlayback != null

    val isFullScreen: Boolean
        get() = activeSession?.mode == PlayerPresentationMode.Full

    val isMinimized: Boolean
        get() = activeSession?.mode == PlayerPresentationMode.Mini

    val isPictureInPicture: Boolean
        get() = activeSession?.mode == PlayerPresentationMode.PictureInPicture

    val canPlayNextEpisode: Boolean
        get() = playbackState?.nextEpisodePlayable == true && boundControls?.playNextEpisode != null

    /**
     * Marks [launch] as the playback in progress. A newer claim retires the previous one,
     * so a subtitle or skip-segment lookup that is still running must not open afterwards.
     */
    fun beginExternalPlayback(launch: PlayerLaunch): Long = synchronized(externalPlaybackLock) {
        val token = ++externalPlaybackToken
        pendingExternalPlayback = launch
        ActivePlayerSessionRegistry.setActive(true)
        token
    }

    fun isCurrentExternalPlayback(token: Long): Boolean = synchronized(externalPlaybackLock) {
        token == externalPlaybackToken && pendingExternalPlayback != null
    }

    /**
     * Preparation may finish while a replacement prompt is still open.
     * Opening is held until that choice is made, and a confirm retires this launch.
     */
    suspend fun awaitExternalOpenAllowed(token: Long): Boolean {
        while (true) {
            val blockedOnPrompt = synchronized(externalPlaybackLock) {
                if (token != externalPlaybackToken || pendingExternalPlayback == null) return false
                pendingReplacement != null
            }
            if (!blockedOnPrompt) return true
            snapshotFlow { pendingReplacement }.first { it == null }
        }
    }

    fun cancelPendingExternalPlayback() {
        synchronized(externalPlaybackLock) {
            val hadPending = pendingExternalPlayback != null
            externalPlaybackToken += 1
            pendingExternalPlayback = null
            if (hadPending && activeSession == null) {
                ActivePlayerSessionRegistry.setActive(false)
            }
        }
    }

    fun finishExternalPlayback(token: Long) {
        synchronized(externalPlaybackLock) {
            if (token != externalPlaybackToken) return
            val hadPending = pendingExternalPlayback != null
            pendingExternalPlayback = null
            if (hadPending && activeSession == null) {
                ActivePlayerSessionRegistry.setActive(false)
            }
        }
    }

    fun open(
        launch: PlayerLaunch,
        launchId: Long = PlayerLaunchStore.put(launch),
        initialFullscreen: Boolean = false,
    ): ActivePlayerSession {
        cancelPendingExternalPlayback()
        val session = ActivePlayerSession(
            launchId = launchId,
            launch = launch,
            mode = PlayerPresentationMode.Full,
            initialFullscreen = initialFullscreen,
        )
        boundLaunchId = null
        boundControls = null
        closingLaunchId = null
        pendingCloseCallbacks.clear()
        confirmedReplacementCallback = null
        confirmedReplacementRequiresPreview = false
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
        requiresPreview: Boolean = false,
        onProceed: () -> Unit,
    ) {
        val current = activeSession
        val pendingExternal = pendingExternalPlayback
        when (
            val decision = decidePlaybackLaunch(
                activeState = if (current != null) playbackState else null,
                activeLaunch = current?.launch ?: pendingExternal,
                incomingTitle = incomingTitle,
                videoId = videoId,
                parentMetaId = parentMetaId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                sourceUrl = sourceUrl,
                allowSameTargetReopen = allowSameTargetReopen,
            )
        ) {
            ReplacePlaybackDecision.ProceedNow -> {
                cancelPendingExternalPlayback()
                onProceed()
            }
            ReplacePlaybackDecision.ReopenActive -> {
                if (current != null) {
                    cancelPendingExternalPlayback()
                    reopenIfMatching(
                        videoId = videoId,
                        parentMetaId = parentMetaId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        sourceUrl = sourceUrl,
                    )
                }
            }
            is ReplacePlaybackDecision.AskFirst -> {
                pendingReplacement = PendingPlaybackReplacement(
                    currentTitle = decision.currentTitle,
                    incomingTitle = decision.incomingTitle,
                    onConfirm = onProceed,
                    requiresPreview = requiresPreview,
                )
            }
        }
    }

    fun launchExternalOrAskToReplace(
        launch: PlayerLaunch,
        allowSameTargetReopen: Boolean = true,
        onProceed: () -> Unit,
    ) {
        launchOrAskToReplace(
            incomingTitle = launch.title,
            videoId = launch.videoId,
            parentMetaId = launch.parentMetaId,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
            sourceUrl = launch.sourceUrl,
            allowSameTargetReopen = allowSameTargetReopen,
            onProceed = onProceed,
        )
    }

    fun requestTrailerOrAskToReplace(
        contentTitle: String,
        trailerTitle: String = "",
        onProceed: () -> Unit,
    ) {
        val cleanContent = contentTitle.trim()
        val cleanTrailer = trailerTitle.trim()
        val label = when {
            cleanContent.isNotEmpty() && cleanTrailer.isNotEmpty() &&
                !cleanTrailer.equals(cleanContent, ignoreCase = true) -> "$cleanContent — $cleanTrailer"
            cleanContent.isNotEmpty() -> cleanContent
            else -> cleanTrailer
        }
        launchOrAskToReplace(
            incomingTitle = label,
            videoId = null,
            parentMetaId = null,
            seasonNumber = null,
            episodeNumber = null,
            sourceUrl = null,
            allowSameTargetReopen = false,
            requiresPreview = true,
            onProceed = {
                // The prompt can outlive the network that allowed the trailer.
                if (previewsAllowed()) onProceed()
            },
        )
    }

    fun confirmPendingReplacement() {
        val pending = pendingReplacement ?: return
        if (pending.requiresPreview && !previewsAllowed()) {
            pendingReplacement = null
            return
        }
        val current = activeSession
        // Retire the external launch before the prompt closes, so a prepare that
        // finished while the prompt was up cannot open in the gap.
        cancelPendingExternalPlayback()
        pendingReplacement = null
        if (current == null) {
            pending.onConfirm()
            return
        }
        prepareClosingState(current.launchId)
        confirmedReplacementCallback = pending.onConfirm
        confirmedReplacementRequiresPreview = pending.requiresPreview
        requestSessionClose(current)
    }

    fun cancelPendingReplacement() {
        pendingReplacement = null
    }

    /** Drops a trailer prompt, and a trailer start already copied out of that prompt, once previews are blocked. */
    fun cancelPendingPreviewReplacement() {
        if (previewsAllowed()) return
        if (pendingReplacement?.requiresPreview == true) {
            pendingReplacement = null
        }
        if (confirmedReplacementRequiresPreview) {
            confirmedReplacementCallback = null
            confirmedReplacementRequiresPreview = false
        }
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
        closeActiveSession(
            onClosed = onClosed,
            onFailed = {},
        )
    }

    fun closeActiveSession(
        onClosed: () -> Unit,
        onFailed: () -> Unit,
    ) {
        val current = activeSession ?: run {
            onClosed()
            return
        }
        prepareClosingState(current.launchId)
        pendingCloseCallbacks += onClosed
        pendingCloseFailureCallbacks += onFailed
        requestSessionClose(current)
    }

    fun notifyCloseFailed(launchId: Long) {
        if (closingLaunchId != launchId) return
        closingLaunchId = null
        pendingCloseCallbacks.clear()
        val failureCallbacks = pendingCloseFailureCallbacks.toList()
        pendingCloseFailureCallbacks.clear()
        confirmedReplacementCallback = null
        confirmedReplacementRequiresPreview = false
        failureCallbacks.forEach { it.invoke() }
    }

    private fun prepareClosingState(launchId: Long) {
        if (closingLaunchId != launchId) {
            closingLaunchId = launchId
            pendingCloseCallbacks.clear()
            pendingCloseFailureCallbacks.clear()
            confirmedReplacementCallback = null
            confirmedReplacementRequiresPreview = false
        }
    }

    private fun requestSessionClose(current: ActivePlayerSession) {
        val controls = boundControls.takeIf { boundLaunchId == current.launchId }
        if (controls != null) {
            controls.requestClose {
                clearSession(current.launchId)
            }
        } else {
            ResumePromptRepository.markPlayerExitedNormally()
            PlayerLaunchStore.remove(current.launchId)
            clearSession(current.launchId)
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
        val closeCallbacks: List<() -> Unit>
        val replacementCallback: (() -> Unit)?
        if (closingLaunchId == launchId) {
            closingLaunchId = null
            closeCallbacks = pendingCloseCallbacks.toList()
            pendingCloseCallbacks.clear()
            pendingCloseFailureCallbacks.clear()
            replacementCallback = confirmedReplacementCallback
            confirmedReplacementCallback = null
            confirmedReplacementRequiresPreview = false
        } else {
            closeCallbacks = emptyList()
            replacementCallback = null
        }
        ActivePlayerSessionRegistry.setActive(false)
        closeCallbacks.forEach { it.invoke() }
        replacementCallback?.invoke()
        return true
    }
}
