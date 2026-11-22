package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadSubtitles
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.player.skip.NextEpisodeInfo
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.servers.serverPlaybackMessage
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.YouTubeStreamResolver
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.youtube_resolution_failed
import org.jetbrains.compose.resources.getString

internal fun PlayerScreenRuntime.resolveDebridForPlayer(
    stream: StreamItem,
    season: Int?,
    episode: Int?,
    onResolved: (StreamItem) -> Unit,
    onStale: () -> Unit,
): Boolean {
    if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) return false
    scope.launch {
        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = stream,
            season = season,
            episode = episode,
        )
        when (resolved) {
            is DirectDebridPlayableResult.Success -> onResolved(resolved.stream)
            else -> {
                resolved.toastMessage()?.let { NuvioToastController.show(it) }
                if (resolved == DirectDebridPlayableResult.Stale) {
                    onStale()
                }
            }
        }
    }
    return true
}

internal fun PlayerScreenRuntime.prepareServerForPlayer(
    stream: StreamItem,
    onPrepared: suspend (StreamItem) -> Unit,
): Boolean {
    if (!stream.needsServerPreparation) return false
    scope.launch {
        runCatching { ServerPlayback.prepare(stream) }
            .onFailure { if (it is CancellationException) throw it }
            .onSuccess { onPrepared(it) }
            .onFailure { NuvioToastController.show(it.serverPlaybackMessage()) }
    }
    return true
}

internal fun PlayerScreenRuntime.resolveYouTubeForPlayer(
    stream: StreamItem,
    onResolved: (StreamItem) -> Unit,
): Boolean {
    if (stream.youTubeIdToResolve == null) return false
    scope.launch {
        val resolved = YouTubeStreamResolver.Default.resolve(stream)
        if (resolved == null) {
            NuvioToastController.show(getString(Res.string.youtube_resolution_failed))
            return@launch
        }
        onResolved(resolved)
    }
    return true
}

internal fun PlayerScreenRuntime.p2pSentinelUrl(infoHash: String, fileIdx: Int?): String =
    "torrent://$infoHash${fileIdx?.let { "?index=$it" }.orEmpty()}"

internal fun PlayerScreenRuntime.isP2pStream(stream: StreamItem): Boolean =
    stream.needsLocalDebridResolve && stream.p2pInfoHash != null

internal fun PlayerScreenRuntime.openExternalSourceUrl(stream: StreamItem): Boolean {
    if (!stream.shouldOpenExternally) return false
    val url = stream.externalOpenUrl ?: return false
    val openExternalUrl = args.onOpenExternalUrl ?: return false
    openExternalUrl(url)
    showSourcesPanel = false
    showEpisodesPanel = false
    showChaptersPanel = false
    controlsVisible = true
    PlayerStreamsRepository.pauseSearchForPlayback()
    return true
}

internal fun StreamItem.playerSourceIdentityKey(): String? {
    serverTarget?.let { target -> return "server:${target.item.encode()}:${target.mediaSourceId.orEmpty()}" }
    p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "torrent:$hash:${p2pFileIdx ?: -1}"
    }

    clientResolve?.let { resolve ->
        val raw = resolve.stream?.raw
        val keyParts = listOf(
            addonId,
            resolve.service,
            resolve.serviceIndex?.toString(),
            resolve.infoHash?.trim()?.lowercase(),
            resolve.fileIdx?.toString(),
            resolve.magnetUri,
            resolve.torrentName,
            resolve.filename,
            raw?.torrentName,
            raw?.filename,
            raw?.size?.toString(),
            behaviorHints.filename,
            behaviorHints.videoSize?.toString(),
            streamLabel,
            streamSubtitle,
        ).map { it.orEmpty().trim() }
        if (keyParts.any { it.isNotBlank() }) {
            return "resolve:${keyParts.joinToString("|")}"
        }
    }

    behaviorHints.videoHash?.trim()?.takeIf { it.isNotBlank() }?.let { hash ->
        return "hash:$addonId:$hash:${behaviorHints.videoSize ?: ""}:${behaviorHints.filename.orEmpty()}"
    }

    playableDirectUrl?.trim()?.takeIf { it.isNotBlank() }?.let { url ->
        return "url:$url"
    }

    val fallbackParts = listOf(
        addonId,
        addonName,
        streamLabel,
        streamSubtitle.orEmpty(),
        behaviorHints.filename.orEmpty(),
        behaviorHints.videoSize?.toString().orEmpty(),
        sourceName.orEmpty(),
        sources.joinToString(","),
    ).map { it.trim() }
    return fallbackParts
        .takeIf { parts -> parts.any { it.isNotBlank() } }
        ?.joinToString(separator = "|", prefix = "meta:")
}

internal fun PlayerScreenRuntime.stopActiveP2pStream() {
    if (activeTorrentInfoHash != null || p2pResolvedSourceUrl != null) {
        P2pStreamingEngine.stopStream()
    }
    activeTorrentInfoHash = null
    activeTorrentFileIdx = null
    activeTorrentFilename = null
    activeTorrentTrackers = emptyList()
    p2pResolvedSourceUrl = null
    activeStreamFilename = null
}

/**
 * Filename used by the auto engine resolver for a direct stream, mirroring NuvioTV:
 * the stream's `behaviorHints.filename` when present, otherwise the URL path basename
 * (query string stripped, only when it looks like a file with an extension).
 */
internal fun streamPlaybackFilename(stream: StreamItem, url: String): String? =
    stream.behaviorHints.filename?.takeIf { it.isNotBlank() }
        ?: sourceFilenameFromUrl(url)

internal fun sourceFilenameFromUrl(url: String?): String? =
    url?.substringBefore('?')?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() && it.contains('.') }

internal fun PlayerScreenRuntime.saveP2pStreamForReuse(
    stream: StreamItem,
    videoId: String?,
    season: Int?,
    episode: Int?,
) {
    if (!playerSettingsUiState.streamReuseLastLinkEnabled || videoId == null) return
    val infoHash = stream.p2pInfoHash ?: return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
    )
    StreamLinkCacheRepository.save(
        contentKey = cacheKey,
        url = "",
        streamName = stream.streamLabel,
        addonName = stream.addonName,
        addonId = stream.addonId,
        requestHeaders = emptyMap(),
        responseHeaders = emptyMap(),
        filename = stream.behaviorHints.filename,
        videoHash = stream.behaviorHints.videoHash,
        videoSize = stream.behaviorHints.videoSize,
        infoHash = infoHash,
        fileIdx = stream.p2pFileIdx,
        sources = stream.sources,
        bingeGroup = stream.behaviorHints.bingeGroup,
        addonLogo = stream.addonLogo,
        streamDescription = stream.streamSubtitle,
    )
}

internal fun PlayerScreenRuntime.switchToP2pSourceStream(stream: StreamItem) {
    val infoHash = stream.p2pInfoHash ?: return
    if (!P2pSettingsRepository.isVisible) return
    restartSourceFailoverAfterUserChoice()
    if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
        pendingP2pSwitch = PendingPlayerP2pSwitch(stream = stream, episode = null, isAutoPlay = false)
        return
    }
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    flushWatchProgress()
    stopActiveP2pStream()
    saveP2pStreamForReuse(
        stream = stream,
        videoId = activeVideoId,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    externalSubtitles = stream.externalSubtitles
    activeSourceUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeTorrentInfoHash = infoHash
    activeTorrentFileIdx = stream.p2pFileIdx
    activeTorrentFilename = stream.behaviorHints.filename
    activeSourceFilename = stream.behaviorHints.filename
    activeTorrentTrackers = stream.p2pTrackers
    activeSourceIdentityKey = stream.playerSourceIdentityKey()
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    activeStream = stream
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeSubtitleExtras = SubtitleRequestExtras.from(stream.behaviorHints)
    currentStreamFilename = stream.behaviorHints.filename
    currentStreamInfoHash = stream.infoHash
    activeInitialPositionMs = currentPositionMs
    activeInitialProgressFraction = null
    showSourcesPanel = false
    controlsVisible = true
    PlayerStreamsRepository.pauseSearchForPlayback()
}

internal fun PlayerScreenRuntime.switchToP2pEpisodeStream(
    stream: StreamItem,
    episode: MetaVideo,
    isAutoPlay: Boolean = false,
) {
    val infoHash = stream.p2pInfoHash ?: return
    if (!P2pSettingsRepository.isVisible) return
    if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
        pendingP2pSwitch = PendingPlayerP2pSwitch(stream = stream, episode = episode, isAutoPlay = isAutoPlay)
        return
    }
    resetEpisodePanelAndNextEpisodeState()
    flushProgressForEpisodeChange(episode)
    stopActiveP2pStream()
    val epVideoId = episode.id
    val resume = resolveEpisodeResume(epVideoId, episode)
    saveP2pStreamForReuse(
        stream = stream,
        videoId = epVideoId,
        season = episode.season,
        episode = episode.episode,
    )
    externalSubtitles = stream.externalSubtitles
    activeSourceUrl = p2pSentinelUrl(infoHash, stream.p2pFileIdx)
    // A torrent is never an auto-selected source here, so the episode has nothing to fall back from.
    restartSourceFallback(activeSourceUrl, autoSelection = null)
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeTorrentInfoHash = infoHash
    activeTorrentFileIdx = stream.p2pFileIdx
    activeTorrentFilename = stream.behaviorHints.filename
    activeTorrentTrackers = stream.p2pTrackers
    activeSubtitleExtras = SubtitleRequestExtras.from(stream.behaviorHints)
    applyEpisodeStreamMetadata(stream, episode, resume)
}

internal fun PlayerScreenRuntime.switchToSource(
    stream: StreamItem,
    saveForReuse: Boolean = true,
) {
    // Even picking the source that is playing now ends a lookup for a replacement.
    restartSourceFailoverAfterUserChoice()
    if (prepareServerForPlayer(stream) { switchToSource(it) }) return
    // The resolved URL expires after a few hours, so it isn't kept for reuse.
    if (resolveYouTubeForPlayer(stream) { switchToSource(it, saveForReuse = false) }) return
    if (
        resolveDebridForPlayer(
            stream = stream,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
            onResolved = { switchToSource(it) },
            onStale = {
                val vid = activeVideoId
                if (vid != null) {
                    PlayerStreamsRepository.loadSources(
                        type = contentType ?: parentMetaType,
                        videoId = vid,
                        season = activeSeasonNumber,
                        episode = activeEpisodeNumber,
                        forceRefresh = true,
                    )
                }
            },
        )
    ) return
    if (isP2pStream(stream)) {
        switchToP2pSourceStream(stream)
        return
    }
    if (openExternalSourceUrl(stream)) return
    val url = stream.playableDirectUrl ?: return
    val sourceIdentityKey = stream.playerSourceIdentityKey()
    if (url == activeSourceUrl) {
        activeSourceIdentityKey = sourceIdentityKey ?: activeSourceIdentityKey
        return
    }
    val currentPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    flushWatchProgress()
    stopActiveP2pStream()
    val currentVideoId = activeVideoId
    if (saveForReuse && playerSettingsUiState.streamReuseLastLinkEnabled && currentVideoId != null) {
        saveDirectStreamForReuse(stream, url, currentVideoId, activeSeasonNumber, activeEpisodeNumber)
    }
    externalSubtitles = stream.externalSubtitles
    activeSourceUrl = url
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    activeSourceIdentityKey = sourceIdentityKey
    activeSourceFilename = stream.behaviorHints.filename
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeStreamFilename = streamPlaybackFilename(stream, url)
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    activeStream = stream
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    activeSubtitleExtras = SubtitleRequestExtras.from(stream.behaviorHints, url)
    currentStreamFilename = stream.behaviorHints.filename
    currentStreamInfoHash = stream.infoHash
    activeInitialPositionMs = currentPositionMs
    activeInitialProgressFraction = null
    showSourcesPanel = false
    controlsVisible = true
    PlayerStreamsRepository.pauseSearchForPlayback()
}

/**
 * @param autoSelection non-null when auto source selection picked [stream]: the sources it tried
 * before it, so the fallback to the next matching source goes on from them.
 */
internal fun PlayerScreenRuntime.switchToEpisodeStream(
    stream: StreamItem,
    episode: MetaVideo,
    serverResumeMs: Long? = null,
    saveForReuse: Boolean = true,
    autoSelection: PlayerSourceFallbackTried? = null,
) {
    if (
        prepareServerForPlayer(stream) {
            switchToEpisodeStream(
                it,
                episode,
                serverResumeMs = newerServerResumeMs(it, episode),
                autoSelection = autoSelection,
            )
        }
    ) return
    if (
        resolveYouTubeForPlayer(stream) { resolved ->
            switchToEpisodeStream(resolved, episode, saveForReuse = false, autoSelection = autoSelection)
        }
    ) return
    if (
        resolveDebridForPlayer(
            stream = stream,
            season = episode.season,
            episode = episode.episode,
            onResolved = { resolvedStream -> switchToEpisodeStream(resolvedStream, episode, autoSelection = autoSelection) },
            onStale = {
                PlayerStreamsRepository.loadEpisodeStreams(
                    type = contentType ?: parentMetaType,
                    videoId = episode.id,
                    season = episode.season,
                    episode = episode.episode,
                    forceRefresh = true,
                )
            },
        )
    ) return
    if (isP2pStream(stream)) {
        switchToP2pEpisodeStream(stream, episode)
        return
    }
    if (openExternalSourceUrl(stream)) return
    val url = stream.playableDirectUrl ?: return
    resetEpisodePanelAndNextEpisodeState()
    flushProgressForEpisodeChange(episode)
    stopActiveP2pStream()
    val epVideoId = episode.id
    val resume = serverResumeMs?.let { EpisodeResume(positionMs = it, fraction = null) } ?: resolveEpisodeResume(epVideoId, episode)
    if (saveForReuse && playerSettingsUiState.streamReuseLastLinkEnabled) {
        saveDirectStreamForReuse(stream, url, epVideoId, episode.season, episode.episode)
    }
    externalSubtitles = stream.externalSubtitles
    activeSourceUrl = url
    restartSourceFallback(url, autoSelection)
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    activeSubtitleExtras = SubtitleRequestExtras.from(stream.behaviorHints, url)
    applyEpisodeStreamMetadata(stream, episode, resume)
}

internal enum class DownloadedEpisodeSwitchResult { Accepted, Pending, Rejected }

internal class DownloadedEpisodeSwitch(val episodeId: String, val job: Job)

internal fun PlayerScreenRuntime.switchToDownloadedEpisode(
    downloadItem: DownloadItem,
    episode: MetaVideo,
    continuePending: Boolean = false,
    isStillValid: () -> Boolean = { true },
): DownloadedEpisodeSwitchResult {
    val localFileUri = playableDownloadedUri(downloadItem) ?: return DownloadedEpisodeSwitchResult.Rejected
    if (!isStillValid()) return DownloadedEpisodeSwitchResult.Rejected
    val inFlight = synchronized(episodeSwitchLock) { downloadedEpisodeSwitch }
    if (inFlight != null && inFlight.job.isActive) {
        if (inFlight.episodeId == episode.id && !continuePending) return DownloadedEpisodeSwitchResult.Pending
        if (!(continuePending && inFlight.episodeId == episode.id)) inFlight.job.cancel()
    }
    restartSourceFailoverAfterUserChoice(newVideo = true)
    resetEpisodePanelAndNextEpisodeState()
    flushProgressForEpisodeChange(episode)
    stopActiveP2pStream()

    val fallbackVideoId = buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
        fallbackVideoId = episode.id,
    )
    val resolvedVideoId = episode.id.takeIf { it.isNotBlank() } ?: fallbackVideoId
    val epEntry = WatchProgressRepository.progressForVideo(
        videoId = resolvedVideoId,
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
    )
        ?.takeIf { !it.isCompleted }
    val epResumeFraction = epEntry?.progressPercent
        ?.takeIf { it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val epResumePositionMs = epEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L

    externalSubtitles = DownloadSubtitles.localSubtitles(localFileUri)
    activeSourceUrl = localFileUri
    restartSourceFallback(activeSourceUrl, autoSelection = null)
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeStreamType = null
    activeSourceIdentityKey = null
    activeSourceFilename = null
    activeStreamTitle = downloadItem.streamTitle.ifBlank {
        episode.title.ifBlank { title }
    }
    activeStreamSubtitle = downloadItem.streamSubtitle
    activeStreamFilename = sourceFilenameFromUrl(localFileUri)
    activeProviderName = downloadItem.providerName.ifBlank { downloadedLabel }
    activeProviderAddonId = downloadItem.providerAddonId
    activeStream = null
    currentStreamBingeGroup = null
    activeSubtitleExtras = null
    currentStreamFilename = null
    currentStreamInfoHash = null
    activeSeasonNumber = episode.season
    activeEpisodeNumber = episode.episode
    activeEpisodeTitle = episode.title
    activeEpisodeThumbnail = episode.thumbnail
    activePauseDescription = episode.overview
    activeVideoId = resolvedVideoId
    activeInitialPositionMs = epResumePositionMs
    activeInitialProgressFraction = epResumeFraction
    controlsVisible = true
    return DownloadedEpisodeSwitchResult.Accepted
}

internal fun PlayerScreenRuntime.resolvePlayablePreviousEpisode(): MetaVideo? {
    if (!isSeries || playerMetaVideos.isEmpty()) return null
    val previous = PlayerNextEpisodeRules.resolvePreviousEpisode(
        videos = playerMetaVideos,
        currentSeason = activeSeasonNumber,
        currentEpisode = activeEpisodeNumber,
    ) ?: return null
    if (previous.season == null || previous.episode == null) return null
    if (!PlayerNextEpisodeRules.hasEpisodeAired(previous.released)) return null
    return previous
}

internal fun PlayerScreenRuntime.playNextEpisode(automatic: Boolean = false): Boolean {
    val targetInfo = nextEpisodeInfo?.takeIf { it.hasAired } ?: return false
    return playTargetEpisode(
        targetInfo = targetInfo,
        automatic = automatic,
        isStillValid = { playbackKey ->
            playbackKey == activePlaybackKey &&
                nextEpisodeInfo?.videoId == targetInfo.videoId &&
                (!automatic || (
                    playerSettingsUiState.streamAutoPlayNextEpisodeEnabled &&
                        !nextEpisodeCardDismissed &&
                        isAtNextEpisodeThreshold() &&
                        !sleepTimer.blocksAutomaticNextEpisode()
                    ))
        },
    )
}

internal fun PlayerScreenRuntime.playPreviousEpisode(): Boolean {
    val previous = resolvePlayablePreviousEpisode() ?: return false
    val season = previous.season ?: return false
    val episode = previous.episode ?: return false
    val targetInfo = NextEpisodeInfo(
        videoId = previous.id,
        season = season,
        episode = episode,
        title = previous.title,
        thumbnail = previous.thumbnail,
        overview = previous.overview,
        released = previous.released,
        hasAired = true,
        isWatched = false,
        unairedMessage = null,
    )
    return playTargetEpisode(
        targetInfo = targetInfo,
        automatic = false,
        isPrevious = true,
        isStillValid = { playbackKey -> playbackKey == activePlaybackKey },
    )
}

private fun PlayerScreenRuntime.playTargetEpisode(
    targetInfo: NextEpisodeInfo,
    automatic: Boolean,
    isPrevious: Boolean = false,
    isStillValid: (PlaybackKey) -> Boolean,
): Boolean {
    if (automatic && sleepTimer.blocksAutomaticNextEpisode()) {
        cancelNextEpisodeAutoPlay()
        return false
    }
    var downloadedEpisodeSelected = false
    val job = synchronized(episodeSwitchLock) {
        val pending = downloadedEpisodeSwitch
        if (pending != null && pending.job.isActive) return true
        if (nextEpisodeAutoPlaySearching || nextEpisodeAutoPlayCountdown != null) return true
        nextEpisodePreloadTriggered = false
        val playbackKey = activePlaybackKey
        fun isCurrentRequest(): Boolean = isStillValid(playbackKey)
        if (!isCurrentRequest()) return false
        nextEpisodeAutoPlayAutomatic = automatic
        val started = scope.launchPlayerNextEpisodeAutoPlay(
            previousJob = nextEpisodeAutoPlayJob,
            nextEpisodeInfo = targetInfo,
            allEpisodes = playerMetaVideos,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            contentType = contentType,
            settings = playerSettingsUiState,
            currentStreamBingeGroup = currentStreamBingeGroup,
            isOffline = episodePlaybackOffline,
            findDownloadedEpisode = { findEpisodeDownload(parentMetaId, it) },
            verifyDownloadedEpisode = verifyEpisodeDownload,
            onDownloadVerificationJob = { verification ->
                downloadedEpisodeSwitch = DownloadedEpisodeSwitch(targetInfo.videoId, verification)
                verification.invokeOnCompletion {
                    synchronized(episodeSwitchLock) {
                        if (downloadedEpisodeSwitch?.job === verification) downloadedEpisodeSwitch = null
                    }
                }
            },
            onDownloadedEpisodeSelected = { item, episode ->
                if (isCurrentRequest()) {
                    when (switchToDownloadedEpisode(item, episode, continuePending = true) { isCurrentRequest() }) {
                        DownloadedEpisodeSwitchResult.Accepted,
                        DownloadedEpisodeSwitchResult.Pending,
                        -> downloadedEpisodeSelected = true
                        DownloadedEpisodeSwitchResult.Rejected -> Unit
                    }
                }
            },
            onEpisodeStreamSelected = { stream, episode, tried ->
                // Auto source selection picked it, whether or not the user asked for the episode.
                if (isCurrentRequest()) switchToEpisodeStream(stream, episode, autoSelection = tried)
            },
            onManualSelectionRequired = { targetVideo ->
                if (isCurrentRequest()) {
                    nextEpisodeCardDismissed = true
                    episodeStreamsPanelState = EpisodeStreamsPanelState(
                        showStreams = true,
                        selectedEpisode = targetVideo,
                    )
                    showEpisodesPanel = true
                }
            },
            onSearchingChanged = {
                if (playbackKey == activePlaybackKey) {
                    if (it) nextEpisodeAutoPlayTarget = PlayerEpisodeCardState(targetInfo, isPrevious)
                    nextEpisodeAutoPlaySearching = it
                }
            },
            onSourceNameChanged = {
                if (playbackKey == activePlaybackKey) nextEpisodeAutoPlaySourceName = it
            },
            onCountdownChanged = {
                if (playbackKey == activePlaybackKey) nextEpisodeAutoPlayCountdown = it
            },
            onNextEpisodeCardVisibleChanged = {
                if (playbackKey == activePlaybackKey) showNextEpisodeCard = it
            },
        )
        if (started != null) {
            nextEpisodeAutoPlayJob = started
            started.invokeOnCompletion {
                if (nextEpisodeAutoPlayJob === started && playbackKey == activePlaybackKey) {
                    nextEpisodeAutoPlayTarget = null
                }
            }
        }
        started
    }
    return downloadedEpisodeSelected || job != null || downloadedEpisodeSwitch?.job?.isActive == true
}

internal fun PlayerScreenRuntime.openSourcesPanel() {
    val vid = activeVideoId ?: return
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = vid,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
    showSourcesPanel = true
    showEpisodesPanel = false
    showChaptersPanel = false
    controlsVisible = false
}

internal fun PlayerScreenRuntime.openEpisodesPanel() {
    if (playerMetaVideos.isEmpty()) {
        scope.launch {
            playerMetaVideos = MetaDetailsRepository.fetch(parentMetaType, parentMetaId)?.videos ?: emptyList()
        }
    }
    showEpisodesPanel = true
    showSourcesPanel = false
    showChaptersPanel = false
    controlsVisible = false
}

private data class EpisodeResume(val positionMs: Long, val fraction: Float?)

private fun PlayerScreenRuntime.flushProgressForEpisodeChange(episode: MetaVideo) {
    flushWatchProgress(
        leavingEpisode = isLeavingPlaybackEpisode(
            currentVideoId = activeVideoId,
            currentSeason = activeSeasonNumber,
            currentEpisode = activeEpisodeNumber,
            nextVideoId = episode.id,
            nextSeason = episode.season,
            nextEpisode = episode.episode,
        ),
    )
}

private fun PlayerScreenRuntime.resetEpisodePanelAndNextEpisodeState() {
    showNextEpisodeCard = false
    showSourcesPanel = false
    showEpisodesPanel = false
    showChaptersPanel = false
    episodeStreamsPanelState = EpisodeStreamsPanelState()
    cancelNextEpisodeAutoPlay()
    PlayerStreamsRepository.clearEpisodeStreams()
}

private fun PlayerScreenRuntime.episodeProgress(epVideoId: String, episode: MetaVideo): WatchProgressEntry? {
    val epResumeVideoId = buildPlaybackVideoId(
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
        fallbackVideoId = epVideoId,
    )
    return WatchProgressRepository.progressForVideo(
        videoId = epVideoId.takeIf { it.isNotBlank() } ?: epResumeVideoId,
        parentMetaId = parentMetaId,
        seasonNumber = episode.season,
        episodeNumber = episode.episode,
    )
}

private suspend fun PlayerScreenRuntime.newerServerResumeMs(stream: StreamItem, episode: MetaVideo): Long? =
    ServerPlayback.newerResumePositionMs(stream.playableDirectUrl, episodeProgress(episode.id, episode)?.lastUpdatedEpochMs)

private fun PlayerScreenRuntime.resolveEpisodeResume(epVideoId: String, episode: MetaVideo): EpisodeResume {
    val epEntry = episodeProgress(epVideoId, episode)?.takeIf { !it.isCompleted }
    val epResumeFraction = epEntry?.progressPercent
        ?.takeIf { it > 0f }
        ?.let { (it / 100f).coerceIn(0f, 1f) }
    val epResumePositionMs = epEntry?.lastPositionMs?.takeIf { it > 0L } ?: 0L
    return EpisodeResume(positionMs = epResumePositionMs, fraction = epResumeFraction)
}

private fun PlayerScreenRuntime.applyEpisodeStreamMetadata(
    stream: StreamItem,
    episode: MetaVideo,
    resume: EpisodeResume,
) {
    activeSourceIdentityKey = stream.playerSourceIdentityKey()
    activeSourceFilename = stream.behaviorHints.filename
    activeStreamTitle = stream.streamLabel
    activeStreamSubtitle = stream.streamSubtitle
    activeStreamFilename = streamPlaybackFilename(stream, stream.playableDirectUrl.orEmpty())
    activeProviderName = stream.addonName
    activeProviderAddonId = stream.addonId
    activeStream = stream
    currentStreamBingeGroup = stream.behaviorHints.bingeGroup
    currentStreamFilename = stream.behaviorHints.filename
    currentStreamInfoHash = stream.infoHash
    activeSeasonNumber = episode.season
    activeEpisodeNumber = episode.episode
    activeEpisodeTitle = episode.title
    activeEpisodeThumbnail = episode.thumbnail
    activePauseDescription = episode.overview
    activeVideoId = episode.id
    activeInitialPositionMs = resume.positionMs
    activeInitialProgressFraction = resume.fraction
    controlsVisible = true
}

internal fun PlayerScreenRuntime.saveDirectStreamForReuse(
    stream: StreamItem,
    url: String,
    videoId: String,
    season: Int?,
    episode: Int?,
) {
    if (stream.serverTarget != null) return
    val cacheKey = StreamLinkCacheRepository.contentKey(
        type = contentType ?: parentMetaType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        season = season,
        episode = episode,
    )
    StreamLinkCacheRepository.save(
        contentKey = cacheKey,
        url = url,
        streamName = stream.streamLabel,
        addonName = stream.addonName,
        addonId = stream.addonId,
        requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
        responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
        filename = stream.behaviorHints.filename,
        videoHash = stream.behaviorHints.videoHash,
        videoSize = stream.behaviorHints.videoSize,
        infoHash = stream.p2pInfoHash,
        fileIdx = stream.p2pFileIdx,
        bingeGroup = stream.behaviorHints.bingeGroup,
        streamType = stream.streamType,
        contentLanguage = contentLanguage,
        addonLogo = stream.addonLogo,
        streamDescription = stream.streamSubtitle,
    )
}
