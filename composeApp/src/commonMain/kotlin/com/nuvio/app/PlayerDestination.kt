package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.player.ActivePlayerSessionRegistry
import com.nuvio.app.features.player.ExternalPlayerIntentResult
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.MiniPlayerControlsHeight
import com.nuvio.app.features.player.MiniPlayerDock
import com.nuvio.app.features.player.MiniPlayerPlaybackState
import com.nuvio.app.features.player.PlayerBackReleaseGuard
import com.nuvio.app.features.player.PlayerBackRequest
import com.nuvio.app.features.player.PlayerExitTarget
import com.nuvio.app.features.player.PlayerExitTargetHolder
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerPresentationMode
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.player.PlayerSessionController
import com.nuvio.app.features.player.ReplacePlaybackDialog
import com.nuvio.app.features.player.rememberIsInPictureInPicture
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.navigation.EpisodeDetailRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute

internal val MiniPlayerWidth = 336.dp
internal val MiniPlayerVideoHeight = 189.dp
internal val MiniPlayerHorizontalInset = 20.dp
internal val MiniPlayerBottomInset = 20.dp
internal val MiniPlayerCornerRadius = 14.dp

internal fun fullPlayerOwnsControllerInput(mode: PlayerPresentationMode?): Boolean =
    mode == PlayerPresentationMode.Full

/** Extra space that lifts the browsing hint row above the dock. */
internal fun miniPlayerHintClearance(mode: PlayerPresentationMode?): Dp =
    if (mode == PlayerPresentationMode.Mini) {
        MiniPlayerVideoHeight + MiniPlayerControlsHeight + 12.dp
    } else {
        0.dp
    }

@Composable
internal fun BoxScope.PlayerSessionHost(
    controller: PlayerSessionController,
    navController: NuvioNavigator,
    externalPlayerId: String?,
    externalPlayerNotConfiguredText: String,
    externalPlayerFailedText: String,
    onExternalPlayerLaunch: (PlayerLaunch) -> Unit,
    launchExternalPlayer: (ExternalPlayerIntentResult.Success) -> Boolean,
    openExternalStreamUrl: (String) -> Boolean,
    onOpenPartyMedia: (PartyMedia) -> Unit,
    allowExternalPlayer: Boolean = externalPlayerSupported,
) {
    DisposableEffect(controller) {
        ActivePlayerSessionRegistry.bindController(controller)
        onDispose {
            ActivePlayerSessionRegistry.unbindController(controller)
        }
    }
    val session = controller.activeSession
    // The replacement dialog stays mounted when the in-app session is already gone.
    // An external launch can still be preparing, and a newer title has to be able to ask.
    if (session != null) key(session.launchId) {
        val launch = session.launch
        val guard = remember { PlayerBackReleaseGuard() }
        val exitTarget = remember { PlayerExitTargetHolder() }
        var systemBackHandler by remember { mutableStateOf<(() -> Unit)?>(null) }
        val currentFullscreen = com.nuvio.app.core.ui.isFullscreenActionActive()
        val restoreInitialFullscreen: () -> Unit = remember(session.initialFullscreen, currentFullscreen) {
            {
                if (currentFullscreen != session.initialFullscreen) {
                    com.nuvio.app.core.ui.toggleFullscreenAction()
                }
            }
        }
        val openPendingExitTarget: () -> Unit = remember(navController, exitTarget) {
            {
                val target = exitTarget.target
                exitTarget.target = null
                if (target != null) {
                    navController.openPlayerExitTarget(target)
                }
            }
        }
        val onMinimize: () -> Unit = remember(controller, restoreInitialFullscreen, openPendingExitTarget) {
            {
                restoreInitialFullscreen()
                if (controller.minimize()) {
                    openPendingExitTarget()
                }
            }
        }
        val onBack: PlayerBackRequest = remember(
            controller,
            session.launchId,
            guard,
            restoreInitialFullscreen,
            openPendingExitTarget,
        ) {
            { releaseBeforeBack ->
                restoreInitialFullscreen()
                guard.request(
                    canStart = { controller.activeSession?.launchId == session.launchId },
                    releaseBeforeBack = releaseBeforeBack,
                    beforePop = {
                        ResumePromptRepository.markPlayerExitedNormally()
                        PlayerLaunchStore.remove(session.launchId)
                    },
                    pop = {
                        val cleared = controller.clearSession(session.launchId)
                        if (cleared && !controller.hasActiveSession) {
                            openPendingExitTarget()
                        } else if (cleared) {
                            exitTarget.target = null
                        }
                        cleared
                    },
                )
            }
        }

        PlatformBackHandler(
            enabled = session.mode == PlayerPresentationMode.Full && systemBackHandler != null,
            onBack = { systemBackHandler?.invoke() },
        )

        val isInPip = rememberIsInPictureInPicture()
        val wasInPip = remember { mutableStateOf(isInPip) }
        val latestRestoreInitialFullscreen = rememberUpdatedState(restoreInitialFullscreen)
        val latestOpenPendingExitTarget = rememberUpdatedState(openPendingExitTarget)
        LaunchedEffect(isInPip) {
            if (isInPip) {
                latestRestoreInitialFullscreen.value()
                if (controller.setPictureInPicture(true)) {
                    latestOpenPendingExitTarget.value()
                }
            } else if (wasInPip.value) {
                controller.setPictureInPicture(false)
            }
            wasInPip.value = isInPip
        }

        LaunchedEffect(launch.videoId) {
            launch.videoId?.let { ResumePromptRepository.markPlayerEntered(it) }
        }

        if (session.mode == PlayerPresentationMode.Mini) {
            val dockState = controller.playbackState ?: MiniPlayerPlaybackState(
                title = launch.title,
                seasonNumber = launch.seasonNumber,
                episodeNumber = launch.episodeNumber,
                episodeTitle = launch.episodeTitle,
                videoId = launch.videoId,
                parentMetaId = launch.parentMetaId,
                parentMetaType = launch.parentMetaType,
                sourceUrl = launch.sourceUrl,
            )
            MiniPlayerDock(
                state = dockState,
                canPlayNextEpisode = controller.canPlayNextEpisode,
                onTogglePlayback = controller::togglePlayback,
                onSeekForward = controller::seekForward,
                onPlayNextEpisode = controller::playNextEpisode,
                onClose = { controller.closeActiveSession() },
                onReopenFullPlayer = { controller.restoreFullScreen() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = MiniPlayerHorizontalInset, bottom = MiniPlayerBottomInset)
                    .zIndex(11f),
            )
        }

        val playerSurfaceModifier = when (session.mode) {
            PlayerPresentationMode.Full -> Modifier
                .fillMaxSize()
                .testTag("player_session_surface_slot")
                .zIndex(12f)
            PlayerPresentationMode.Mini -> Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = MiniPlayerHorizontalInset + 1.dp,
                    bottom = MiniPlayerBottomInset + MiniPlayerControlsHeight,
                )
                .size(width = MiniPlayerWidth - 2.dp, height = MiniPlayerVideoHeight - 1.dp)
                .testTag("player_session_surface_slot")
                .clip(
                    RoundedCornerShape(
                        topStart = MiniPlayerCornerRadius - 1.dp,
                        topEnd = MiniPlayerCornerRadius - 1.dp,
                    ),
                )
                .zIndex(12f)
            PlayerPresentationMode.PictureInPicture -> Modifier
                .align(Alignment.BottomEnd)
                .size(1.dp)
                .testTag("player_session_surface_slot")
        }

        PlayerScreen(
            profileId = launch.profileId,
            title = launch.title,
            sourceUrl = launch.sourceUrl,
            sourceAudioUrl = launch.sourceAudioUrl,
            sourceHeaders = launch.sourceHeaders,
            sourceResponseHeaders = launch.sourceResponseHeaders,
            externalSubtitles = launch.externalSubtitles,
            streamType = launch.streamType,
            logo = launch.logo,
            poster = launch.poster,
            background = launch.background,
            seasonNumber = launch.seasonNumber,
            episodeNumber = launch.episodeNumber,
            episodeTitle = launch.episodeTitle,
            episodeThumbnail = launch.episodeThumbnail,
            streamTitle = launch.streamTitle,
            streamSubtitle = launch.streamSubtitle,
            initialBingeGroup = launch.bingeGroup,
            pauseDescription = launch.pauseDescription,
            providerName = launch.providerName,
            providerAddonId = launch.providerAddonId,
            contentType = launch.contentType,
            videoId = launch.videoId,
            parentMetaId = launch.parentMetaId,
            parentMetaType = launch.parentMetaType,
            torrentInfoHash = launch.torrentInfoHash,
            torrentFileIdx = launch.torrentFileIdx,
            torrentFilename = launch.torrentFilename,
            torrentTrackers = launch.torrentTrackers,
            initialPositionMs = launch.initialPositionMs,
            initialProgressFraction = launch.initialProgressFraction,
            contentLanguage = launch.contentLanguage,
            subtitleExtras = launch.subtitleExtras,
            launchId = session.launchId,
            sourceFilename = launch.sourceFilename,
            stream = launch.stream,
            sourceFromLinkCache = launch.sourceFromLinkCache,
            sourceAutoSelected = launch.sourceAutoSelected,
            onBack = onBack,
            isMinimized = session.mode == PlayerPresentationMode.Mini,
            onMinimize = onMinimize,
            onRestoreMiniPlayer = { controller.restoreFullScreen() },
            onSessionStateChanged = { state -> controller.updatePlaybackState(session.launchId, state) },
            onSessionControlsChanged = { controls -> controller.bindControls(session.launchId, controls) },
            onSessionCloseFailed = { controller.notifyCloseFailed(session.launchId) },
            onExitTarget = { exitTarget.target = it },
            onSystemBackHandlerChanged = { handler -> systemBackHandler = handler },
            onOpenInExternalPlayer = if (allowExternalPlayer) {
                { request ->
                    val playerLaunch = PlayerLaunch(
                        profileId = launch.profileId,
                        title = launch.title,
                        sourceUrl = request.sourceUrl,
                        sourceHeaders = request.sourceHeaders,
                        logo = launch.logo,
                        poster = launch.poster,
                        background = launch.background,
                        seasonNumber = launch.seasonNumber,
                        episodeNumber = launch.episodeNumber,
                        episodeTitle = launch.episodeTitle,
                        episodeThumbnail = launch.episodeThumbnail,
                        streamTitle = request.streamTitle ?: launch.streamTitle,
                        streamSubtitle = launch.streamSubtitle,
                        bingeGroup = launch.bingeGroup,
                        pauseDescription = launch.pauseDescription,
                        providerName = launch.providerName,
                        providerAddonId = launch.providerAddonId,
                        contentType = launch.contentType,
                        videoId = launch.videoId,
                        parentMetaId = launch.parentMetaId,
                        parentMetaType = launch.parentMetaType,
                        initialPositionMs = request.resumePositionMs,
                        subtitleExtras = launch.subtitleExtras,
                    )
                    val intentResult = ExternalPlayerPlatform.buildIntent(
                        request = request,
                        playerId = externalPlayerId,
                    )
                    when (intentResult) {
                        is ExternalPlayerIntentResult.Success -> {
                            controller.closeActiveSession(
                                onClosed = {
                                    onExternalPlayerLaunch(playerLaunch)
                                    val launched = launchExternalPlayer(intentResult)
                                    if (!launched) {
                                        NuvioToastController.show(externalPlayerFailedText)
                                    }
                                },
                            )
                        }
                        ExternalPlayerIntentResult.NotConfigured -> {
                            NuvioToastController.show(externalPlayerNotConfiguredText)
                        }
                        ExternalPlayerIntentResult.Failed -> {
                            NuvioToastController.show(externalPlayerFailedText)
                        }
                    }
                }
            } else {
                null
            },
            onOpenExternalUrl = { url ->
                openExternalStreamUrl(url)
            },
            onOpenPartyMedia = onOpenPartyMedia,
            modifier = playerSurfaceModifier,
        )
    }
    ReplacePlaybackDialog(
        pending = controller.pendingReplacement,
        onConfirm = controller::confirmPendingReplacement,
        onDismiss = controller::cancelPendingReplacement,
    )
}

@Composable
internal fun PlayerDestination(
    route: PlayerRoute,
    navController: NuvioNavigator,
    externalPlayerId: String?,
    externalPlayerNotConfiguredText: String,
    externalPlayerFailedText: String,
    onExternalPlayerLaunch: (PlayerLaunch) -> Unit,
    launchExternalPlayer: (ExternalPlayerIntentResult.Success) -> Boolean,
    openExternalStreamUrl: (String) -> Boolean,
    onSystemBackHandlerChanged: (PlayerRoute, (() -> Unit)?) -> Unit,
    onOpenPartyMedia: (PartyMedia) -> Unit,
) {
    val popBack = rememberGuardedPopBackStack(
        navController = navController,
        route = route,
        beforePop = ResumePromptRepository::markPlayerExitedNormally,
    )
    // Where to go once Back has finished, when the title or episode text of the player was used.
    val exitTarget = remember(route) { PlayerExitTargetHolder() }
    val launch = remember(route.launchId) { PlayerLaunchStore.get(route.launchId) }
    if (launch == null) {
        LaunchedEffect(route.launchId) {
            popBack()
        }
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    val currentFullscreen = com.nuvio.app.core.ui.isFullscreenActionActive()
    val initialFullscreen = remember { currentFullscreen }
    val afterPop = remember(navController, exitTarget) {
        { exitTarget.target?.let { navController.openPlayerExitTarget(it) }; Unit }
    }
    val onBackBase = rememberGuardedPlayerPopBackStack(
        navController = navController,
        route = route,
        beforePop = ResumePromptRepository::markPlayerExitedNormally,
        afterPop = afterPop,
    )
    val onBack = remember(onBackBase, initialFullscreen, currentFullscreen) {
        { releaseBeforeBack: com.nuvio.app.features.player.PlayerReleaseBeforeBack ->
            if (currentFullscreen != initialFullscreen) {
                com.nuvio.app.core.ui.toggleFullscreenAction()
            }
            onBackBase(releaseBeforeBack)
        }
    }
    val registerSystemBack = remember(route, onSystemBackHandlerChanged) {
        { handler: (() -> Unit)? -> onSystemBackHandlerChanged(route, handler) }
    }
    LaunchedEffect(launch.videoId) {
        launch.videoId?.let { ResumePromptRepository.markPlayerEntered(it) }
    }
    PlayerScreen(
        profileId = launch.profileId,
        title = launch.title,
        sourceUrl = launch.sourceUrl,
        sourceAudioUrl = launch.sourceAudioUrl,
        sourceHeaders = launch.sourceHeaders,
        sourceResponseHeaders = launch.sourceResponseHeaders,
        externalSubtitles = launch.externalSubtitles,
        startupSubtitleSelection = launch.startupSubtitleSelection,
        streamType = launch.streamType,
        logo = launch.logo,
        poster = launch.poster,
        background = launch.background,
        seasonNumber = launch.seasonNumber,
        episodeNumber = launch.episodeNumber,
        episodeTitle = launch.episodeTitle,
        episodeThumbnail = launch.episodeThumbnail,
        streamTitle = launch.streamTitle,
        streamSubtitle = launch.streamSubtitle,
        initialBingeGroup = launch.bingeGroup,
        pauseDescription = launch.pauseDescription,
        providerName = launch.providerName,
        providerAddonId = launch.providerAddonId,
        contentType = launch.contentType,
        videoId = launch.videoId,
        parentMetaId = launch.parentMetaId,
        parentMetaType = launch.parentMetaType,
        torrentInfoHash = launch.torrentInfoHash,
        torrentFileIdx = launch.torrentFileIdx,
        torrentFilename = launch.torrentFilename,
        torrentTrackers = launch.torrentTrackers,
        initialPositionMs = launch.initialPositionMs,
        initialProgressFraction = launch.initialProgressFraction,
        contentLanguage = launch.contentLanguage,
        subtitleExtras = launch.subtitleExtras,
        launchId = route.launchId,
        sourceFilename = launch.sourceFilename,
        stream = launch.stream,
        sourceFromLinkCache = launch.sourceFromLinkCache,
        sourceAutoSelected = launch.sourceAutoSelected,
        onBack = onBack,
        onExitTarget = { exitTarget.target = it },
        onSystemBackHandlerChanged = registerSystemBack,
        onOpenInExternalPlayer = if (externalPlayerSupported) { { request ->
            val playerLaunch = PlayerLaunch(
                profileId = launch.profileId,
                title = launch.title,
                sourceUrl = request.sourceUrl,
                sourceHeaders = request.sourceHeaders,
                logo = launch.logo,
                poster = launch.poster,
                background = launch.background,
                seasonNumber = launch.seasonNumber,
                episodeNumber = launch.episodeNumber,
                episodeTitle = launch.episodeTitle,
                episodeThumbnail = launch.episodeThumbnail,
                streamTitle = request.streamTitle ?: launch.streamTitle,
                streamSubtitle = launch.streamSubtitle,
                bingeGroup = launch.bingeGroup,
                pauseDescription = launch.pauseDescription,
                providerName = launch.providerName,
                providerAddonId = launch.providerAddonId,
                contentType = launch.contentType,
                videoId = launch.videoId,
                parentMetaId = launch.parentMetaId,
                parentMetaType = launch.parentMetaType,
                initialPositionMs = request.resumePositionMs,
                subtitleExtras = launch.subtitleExtras,
            )
            onExternalPlayerLaunch(playerLaunch)
            val intentResult = ExternalPlayerPlatform.buildIntent(
                request = request,
                playerId = externalPlayerId,
            )
            when (intentResult) {
                is ExternalPlayerIntentResult.Success -> {
                    val launched = launchExternalPlayer(intentResult)
                    if (!launched) {
                        NuvioToastController.show(externalPlayerFailedText)
                    } else if (externalPlayerId == "infuse") {
                        popBack()
                    }
                }
                ExternalPlayerIntentResult.NotConfigured -> {
                    NuvioToastController.show(externalPlayerNotConfiguredText)
                }
                ExternalPlayerIntentResult.Failed -> {
                    NuvioToastController.show(externalPlayerFailedText)
                }
            }
        } } else null,
        onOpenExternalUrl = { url ->
            openExternalStreamUrl(url)
        },
        onOpenPartyMedia = onOpenPartyMedia,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Runs after the player has been popped, so the usual guards and the progress save have already happened. */
internal fun NuvioNavigator.openPlayerExitTarget(target: PlayerExitTarget) {
    when (target) {
        is PlayerExitTarget.Show -> openShowPage(target.type, target.id, target.title)
        is PlayerExitTarget.Episode -> {
            val videoId = target.videoId
            if (videoId.isNullOrBlank() && target.episodeNumber == null) {
                openShowPage(target.type, target.id, target.title)
            } else {
                openEpisodePage(
                    EpisodeDetailRoute(
                        type = target.type,
                        id = target.id,
                        videoId = videoId.orEmpty(),
                        season = target.seasonNumber,
                        episode = target.episodeNumber,
                        title = target.title,
                    ),
                )
            }
        }
    }
}
