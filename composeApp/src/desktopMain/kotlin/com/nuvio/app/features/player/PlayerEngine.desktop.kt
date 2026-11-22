package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.LocalNuvioPlatformDensity
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.DesktopPlayerPictureInPicture
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.NativePlayerLifecycleCoordinator
import com.nuvio.app.features.player.desktop.desktopFullscreenChanges
import com.nuvio.app.features.autosync.AutoSyncMpvCoordinator
import com.nuvio.app.features.autosync.DesktopAutoSyncMpvPlatform
import com.nuvio.app.features.autosync.withAutoSyncMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

internal data class DesktopNativePlayerHooks(
    val createController: (NativePlayerHost, Boolean) -> NativePlayerController = { host, enableRemoteCommands ->
        NativePlayerController(
            host = host,
            lifecycleCoordinator = NativePlayerLifecycleCoordinator.Default,
            remoteCommandsEnabledByDefault = enableRemoteCommands,
        )
    },
    val forceHostReady: Boolean = false,
    val renderSwingPanel: Boolean = true,
    val attachDelayMs: Long = 16L,
)

internal const val FULL_PLAYER_SNAPSHOT_POLL_MS = 250L
internal const val MINI_PLAYER_SNAPSHOT_POLL_MS = 1_000L

internal fun desktopSnapshotPollIntervalMs(isInMiniPlayer: Boolean): Long =
    if (isInMiniPlayer) MINI_PLAYER_SNAPSHOT_POLL_MS else FULL_PLAYER_SNAPSHOT_POLL_MS

internal val LocalDesktopNativePlayerHooks = staticCompositionLocalOf { DesktopNativePlayerHooks() }

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    playbackEngine: com.nuvio.app.features.player.AndroidPlaybackEngine?,
    useNativeController: Boolean,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    sourceAvailable: Boolean,
) {
    if (DesktopHostOs.current == DesktopHostOs.MACOS ||
        DesktopHostOs.current == DesktopHostOs.WINDOWS ||
        DesktopHostOs.current == DesktopHostOs.LINUX
    ) {
        NativePlayerSurface(
            sourceUrl = sourceUrl,
            sourceAvailable = sourceAvailable,
            sourceHeaders = sourceHeaders,
            externalSubtitles = externalSubtitles,
            modifier = modifier,
            playWhenReady = playWhenReady,
            resizeMode = resizeMode,
            useNativeController = useNativeController,
            initialPositionMs = initialPositionMs ?: 0L,
            initialPositionRequestKey = initialPositionRequestKey,
            playerControlsState = playerControlsState,
            onPlayerControlsAction = onPlayerControlsAction,
            onPlayerControlsEvent = onPlayerControlsEvent,
            onPlayerControlsScrubChange = onPlayerControlsScrubChange,
            onPlayerControlsScrubFinished = onPlayerControlsScrubFinished,
            onInitialPositionHandled = onInitialPositionHandled,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        return
    }

    DesktopStubPlayerSurface(
        modifier = modifier,
        initialPositionRequestKey = initialPositionRequestKey,
        onInitialPositionHandled = onInitialPositionHandled,
        onControllerReady = onControllerReady,
        onSnapshot = onSnapshot,
    )
}

@Composable
private fun NativePlayerSurface(
    sourceUrl: String,
    sourceAvailable: Boolean,
    sourceHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    initialPositionMs: Long,
    initialPositionRequestKey: String?,
    playerControlsState: PlayerControlsState,
    onPlayerControlsAction: (PlayerControlsAction) -> Boolean,
    onPlayerControlsEvent: (String, Double) -> Boolean,
    onPlayerControlsScrubChange: (Long) -> Boolean,
    onPlayerControlsScrubFinished: (Long) -> Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val platformDensity = LocalNuvioPlatformDensity.current
    val nativeHooks = LocalDesktopNativePlayerHooks.current
    val host = remember { NativePlayerHost() }
    val controller = remember(host, nativeHooks) { nativeHooks.createController(host, useNativeController) }
    val hostFirstPaintComplete = remember { mutableStateOf(nativeHooks.forceHostReady) }
    val hostFirstFullSizePaintComplete = remember { mutableStateOf(nativeHooks.forceHostReady) }
    val playbackHeaders = remember(sourceHeaders) { sanitizePlaybackHeaders(sourceHeaders) }
    val latestExternalSubtitles = rememberUpdatedState(externalSubtitles)
    val latestPlayerControlsState = rememberUpdatedState(playerControlsState)
    val autoSyncScope = rememberCoroutineScope()
    // Kept across sources so its message count, part of the overlay's notification token, only grows.
    val autoSyncPlatform = remember {
        DesktopAutoSyncMpvPlatform(
            currentNotificationToken = { latestPlayerControlsState.value.notificationToken },
        )
    }
    val autoSyncCoordinator = remember(sourceUrl, playbackHeaders, autoSyncScope) {
        AutoSyncMpvCoordinator(
            scope = autoSyncScope,
            sourceUrl = sourceUrl,
            sourceHeaders = playbackHeaders,
            getSubtitleHeaders = { subtitleUrl ->
                latestExternalSubtitles.value
                    .firstOrNull { it.url == subtitleUrl }
                    ?.headers
                    .orEmpty()
            },
            getPreferredLanguage = { PlayerSettingsRepository.uiState.value.preferredSubtitleLanguage },
            platform = autoSyncPlatform,
        )
    }
    DisposableEffect(autoSyncCoordinator) {
        onDispose { autoSyncCoordinator.dispose() }
    }
    val readyController = remember(controller, autoSyncCoordinator) {
        // setSubtitleUri replaces the external subtitles, so the retimed copy takes the original's place.
        autoSyncCoordinator.wrap(controller) { _, path -> controller.setSubtitleUri(path) }
    }
    val controlsState = playerControlsState.withAutoSyncMessage(autoSyncPlatform.message)
    val latestControlsState = rememberUpdatedState(controlsState)
    val latestOnPlayerControlsAction = rememberUpdatedState(onPlayerControlsAction)
    val latestOnPlayerControlsEvent = rememberUpdatedState(onPlayerControlsEvent)
    val latestOnPlayerControlsScrubChange = rememberUpdatedState(onPlayerControlsScrubChange)
    val latestOnPlayerControlsScrubFinished = rememberUpdatedState(onPlayerControlsScrubFinished)
    val latestOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)
    val latestOnError = rememberUpdatedState(onError)
    val playerSettings by PlayerSettingsRepository.uiState.collectAsState()
    val decoderPriority = playerSettings.decoderPriority
    val nvidiaRtxSuperResolutionEnabled = playerSettings.nvidiaRtxSuperResolutionEnabled
    val nvidiaRtxHdrEnabled = playerSettings.nvidiaRtxHdrEnabled

    SideEffect {
        controller.setRemoteCommandsEnabled(useNativeController)
        controller.setMiniPlayerMode(controlsState.isInMiniPlayer)
        onControllerReady(readyController)
    }

    DisposableEffect(host, useNativeController, nativeHooks.forceHostReady) {
        if (nativeHooks.forceHostReady) {
            hostFirstPaintComplete.value = true
            hostFirstFullSizePaintComplete.value = true
        }
        if (useNativeController) {
            DesktopPlayerPictureInPicture.setHost(host)
            DesktopPlayerPictureInPicture.setController(controller)
        }
        host.onDisplayableChanged = { displayable ->
            if (!displayable && !nativeHooks.forceHostReady) {
                hostFirstPaintComplete.value = false
                hostFirstFullSizePaintComplete.value = false
            }
        }
        host.onFirstPaint = {
            hostFirstPaintComplete.value = true
        }
        host.onFirstFullSizePaint = {
            hostFirstFullSizePaintComplete.value = true
        }
        onDispose {
            if (useNativeController) {
                DesktopPlayerPictureInPicture.release(host)
            }
            host.onDisplayableChanged = null
            host.onFirstPaint = null
            host.onFirstFullSizePaint = null
        }
    }

    LaunchedEffect(controller) {
        controller.setControlCallbacks(
            onAction = { action -> latestOnPlayerControlsAction.value(action) },
            onEvent = { type, value -> latestOnPlayerControlsEvent.value(type, value) },
            onScrubChange = { positionMs -> latestOnPlayerControlsScrubChange.value(positionMs) },
            onScrubFinished = { positionMs -> latestOnPlayerControlsScrubFinished.value(positionMs) },
        )
    }

    DisposableEffect(controller, sourceAvailable, sourceUrl, playbackHeaders) {
        onDispose { controller.dispose() }
    }

    val pipChanges by DesktopPlayerPictureInPicture.changes.collectAsState()
    val isInPip = useNativeController && pipChanges >= 0 && DesktopPlayerPictureInPicture.isEnabled

    // The controls overlay owns the player shortcuts while in full-screen mode.
    // When minimized or in PiP, focus stays with the main Compose window so
    // search inputs and navigation shortcuts work normally.
    val focusForwardingEnabled = hostFirstFullSizePaintComplete.value &&
        !controlsState.isInMiniPlayer &&
        !isInPip
    DisposableEffect(controller, focusForwardingEnabled) {
        val uninstall = if (focusForwardingEnabled) {
            controller.requestKeyboardFocus()
            controller.installWindowFocusForwarding()
        } else {
            controller.releaseKeyboardFocusToWindow()
            null
        }
        onDispose { uninstall?.invoke() }
    }

    LaunchedEffect(
        controller,
        sourceAvailable,
        sourceUrl,
        playbackHeaders,
        decoderPriority,
        nvidiaRtxSuperResolutionEnabled,
        nvidiaRtxHdrEnabled,
        hostFirstFullSizePaintComplete.value,
        initialPositionMs,
        initialPositionRequestKey,
    ) {
        if (!sourceAvailable || !hostFirstFullSizePaintComplete.value) {
            return@LaunchedEffect
        }
        if (nativeHooks.attachDelayMs > 0L) {
            delay(nativeHooks.attachDelayMs)
        }
        controller.attach(
            sourceUrl = sourceUrl,
            sourceHeaders = playbackHeaders,
            playWhenReady = playWhenReady,
            initialPositionMs = initialPositionMs,
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled,
            nvidiaRtxHdrEnabled = nvidiaRtxHdrEnabled,
            onError = { message -> latestOnError.value(message) },
        )
        initialPositionRequestKey?.let { key ->
            latestOnInitialPositionHandled.value(key, initialPositionMs > 0L)
        }
        onControllerReady(readyController)
    }

    LaunchedEffect(controller, sourceAvailable, playWhenReady) {
        if (!sourceAvailable) return@LaunchedEffect
        if (playWhenReady) {
            controller.play()
        } else {
            controller.pause()
        }
    }

    LaunchedEffect(controller, resizeMode) {
        controller.setResizeMode(resizeMode)
    }

    LaunchedEffect(controller, controlsState, useNativeController) {
        controller.updateControls(controlsState)
        if (useNativeController) {
            DesktopPlayerPictureInPicture.setWindowTitle(controlsState.pipWindowTitle)
        }
    }

    LaunchedEffect(controller, useNativeController) {
        if (!useNativeController) return@LaunchedEffect
        DesktopPlayerPictureInPicture.changes.drop(1).collect {
            controller.updateControls(latestControlsState.value)
        }
    }

    LaunchedEffect(controller, useNativeController) {
        if (!useNativeController) return@LaunchedEffect
        AppPresenceState.current.collect { snapshot ->
            val player = snapshot as? PresenceSnapshot.Player
            controller.updatePresenceNowPlayingFallback(player?.title, player?.episodeLabel, player?.posterUrl)
        }
    }

    LaunchedEffect(controller) {
        desktopFullscreenChanges.drop(1).collect {
            controller.onDesktopFullscreenChanged()
        }
    }

    val isInMiniPlayer = controlsState.isInMiniPlayer
    LaunchedEffect(controller, isInMiniPlayer, playWhenReady) {
        val pollIntervalMs = desktopSnapshotPollIntervalMs(isInMiniPlayer)
        val includeVideoSize = !isInMiniPlayer
        var observedRefreshTick = controller.manualRefreshTick.value
        while (true) {
            val snapshot = if (!controller.isReleasing) {
                controller.snapshot(includeVideoSize = includeVideoSize).also(onSnapshot)
            } else {
                null
            }
            if (
                isInMiniPlayer &&
                !playWhenReady &&
                snapshot != null &&
                !snapshot.isLoading &&
                !snapshot.isPlaying
            ) {
                observedRefreshTick = controller.manualRefreshTick.first { it != observedRefreshTick }
                continue
            }
            delay(pollIntervalMs)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isInPip) Color.Transparent else Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // SwingPanel keeps the stable AWT host. PiP moves only the native player
        // surface to the independent window, so Compose never loses its host peer.
        CompositionLocalProvider(LocalDensity provides platformDensity) {
            if (nativeHooks.renderSwingPanel) {
                SwingPanel(
                    factory = { host },
                    modifier = if (isInPip) {
                        Modifier
                            .align(Alignment.BottomEnd)
                            .requiredSize(1.dp)
                    } else if (hostFirstPaintComplete.value) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .align(Alignment.BottomEnd)
                            .requiredSize(1.dp)
                    },
                    background = Color.Black,
                )
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun DesktopStubPlayerSurface(
    modifier: Modifier,
    initialPositionRequestKey: String?,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
) {
    val controller = remember { DesktopStubPlayerController() }

    LaunchedEffect(controller) {
        onControllerReady(controller)
        onSnapshot(PlayerPlaybackSnapshot(isLoading = false))
    }

    LaunchedEffect(initialPositionRequestKey) {
        initialPositionRequestKey?.let { key -> onInitialPositionHandled(key, false) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Desktop in-app playback is not available yet.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private class DesktopStubPlayerController : PlayerEngineController {
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(offsetMs: Long) = Unit
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
