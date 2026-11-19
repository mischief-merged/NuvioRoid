package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonPlayerAction
import com.nuvio.app.features.addons.AddonPlayerEventTarget
import androidx.compose.runtime.snapshots.Snapshot
import com.nuvio.app.features.downloads.AutoDownloads
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerStreams
import com.nuvio.app.features.streams.saysNotCached
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.isDesktop
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.isWatchProgressComplete
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_engine_switching_automatic_message
import nuvio.composeapp.generated.resources.player_engine_switching_manual_message
import kotlin.math.abs
import kotlin.time.TimeSource

internal fun PlayerScreenRuntime.finishTimelineScrub(positionMs: Long) {
    lastManualSkipSeekPositions = playbackSnapshot.positionMs to positionMs
    isScrubbingTimeline = false
    scrubbingPositionMs = positionMs.takeIf { playbackSnapshot.isLoading }
}

internal fun PlayerScreenRuntime.updatePlaybackSnapshot(
    snapshot: PlayerPlaybackSnapshot,
    playbackKey: PlaybackKey = activePlaybackKey,
): Boolean {
    if (frozenForQuit) return false
    if (playbackKey != activePlaybackKey) return false
    val previousPositionMs = playbackSnapshot.positionMs
    val reported = if (partyRateNudge != null) snapshot.copy(playbackSpeed = partyBaseRate()) else snapshot
    playbackSnapshot = if (reported.durationMs <= 0L && playbackSnapshotKey == playbackKey) {
        reported.copy(durationMs = playbackSnapshot.durationMs)
    } else {
        reported
    }
    playbackSnapshotKey = playbackKey
    playbackSnapshotMark = TimeSource.Monotonic.markNow()
    if (
        awaitingPausedSeekSnapshot &&
        !shouldPlay &&
        !playbackSnapshot.isPlaying &&
        abs(playbackSnapshot.positionMs - previousPositionMs) >= 500L
    ) {
        awaitingPausedSeekSnapshot = false
        scheduleProgressSyncAfterSeek()
    }
    val targetPositionMs = scrubbingPositionMs ?: return true
    if (!isScrubbingTimeline && (
            !snapshot.isLoading || snapshot.isEnded ||
                abs(snapshot.positionMs - targetPositionMs) <= 1_000L
            )
    ) {
        scrubbingPositionMs = null
    }
    return true
}

internal val PlayerScreenRuntime.activePlaybackIdentity: String
    get() = activeTorrentInfoHash
        ?.let { hash -> "torrent:$hash:${activeTorrentFileIdx ?: -1}" }
        ?: activeSourceUrl

internal val PlayerScreenRuntime.activePlaybackKey: PlaybackKey
    get() = PlaybackKey(
        sourceIdentity = activePlaybackIdentity,
        videoId = activeVideoId,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        playbackEngine = playbackEngineOverride,
    )

internal val PlayerScreenRuntime.sourcePlaybackKey: PlaybackKey
    get() = PlaybackKey(
        sourceIdentity = activePlaybackIdentity,
        videoId = activeVideoId,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
    )

internal val PlayerScreenRuntime.playbackSession: WatchProgressPlaybackSession
    get() = WatchProgressPlaybackSession(
        profileId = profileId,
        contentType = contentType ?: parentMetaType,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: buildPlaybackVideoId(
            parentMetaId = parentMetaId,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            fallbackVideoId = activeVideoId,
        ),
        title = title,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
        providerName = activeProviderName,
        providerAddonId = activeProviderAddonId,
        lastStreamTitle = activeStreamTitle,
        lastStreamSubtitle = activeStreamSubtitle,
        pauseDescription = activePauseDescription,
        lastSourceUrl = activeSourceUrl.takeUnless { ServerStreams.isServerSourceId(activeProviderAddonId) },
    )

internal fun PlayerScreenRuntime.currentLaunch(launch: PlayerLaunch): PlayerLaunch {
    val positionMs = playbackSnapshot.positionMs.takeIf {
        it > 0L && initialSeekApplied && playbackSnapshotKey == activePlaybackKey
    }
    return launch.copy(
        sourceUrl = activeSourceUrl,
        sourceAudioUrl = activeSourceAudioUrl,
        sourceHeaders = activeSourceHeaders,
        sourceResponseHeaders = activeSourceResponseHeaders,
        externalSubtitles = externalSubtitles,
        startupSubtitleSelection = startupSubtitleSelection,
        streamType = activeStreamType,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        episodeThumbnail = activeEpisodeThumbnail,
        streamTitle = activeStreamTitle,
        streamSubtitle = activeStreamSubtitle,
        bingeGroup = currentStreamBingeGroup,
        pauseDescription = activePauseDescription,
        providerName = activeProviderName,
        providerAddonId = activeProviderAddonId,
        videoId = activeVideoId,
        torrentInfoHash = activeTorrentInfoHash,
        torrentFileIdx = activeTorrentFileIdx,
        torrentFilename = activeTorrentFilename,
        torrentTrackers = activeTorrentTrackers,
        sourceFilename = activeSourceFilename,
        initialPositionMs = positionMs ?: activeInitialPositionMs,
        initialProgressFraction = activeInitialProgressFraction.takeIf { positionMs == null },
    )
}

internal fun PlayerScreenRuntime.resetIdentityStateIfNeeded() {
    val sourceIdentity = sourcePlaybackKey
    if (lastResetSourcePlaybackIdentity != sourceIdentity) {
        lastResetSourcePlaybackIdentity = sourceIdentity
        engineFailoverTriggered = false
        engineFailoverDisarmed = false
        engineFailoverSourceIdentity = null
        playbackEngineOverride = null
        autoFetchedAddonSubtitlesForKey = null
    }

    val identity = activePlaybackKey
    if (lastResetPlaybackIdentity != identity) {
        lastResetPlaybackIdentity = identity
        shouldPlay = true
        initialLoadCompleted = false
        speedBoostRestoreSpeed = null
        isHoldToSpeedGestureActive = false
        initialSeekApplied = activeInitialPositionMs <= 0L &&
            (activeInitialProgressFraction == null || activeInitialProgressFraction!! <= 0f)
        lastProgressPersistEpochMs = 0L
        previousIsPlaying = false
        frozenForQuit = false
        pendingSeekScrobbleRestart = false
        awaitingPausedSeekSnapshot = false
        resetTrackSelectionState()
    }

    val videoIdentity = "$identity:$activeVideoId:$activeSeasonNumber:$activeEpisodeNumber"
    if (lastResetVideoIdentity != videoIdentity) {
        lastResetVideoIdentity = videoIdentity
        hasRequestedScrobbleStartForCurrentItem = false
        scrobbleStartRequestGeneration = 0L
        pendingSeekScrobbleRestart = false
        hasSentCompletionScrobbleForCurrentItem = false
        currentTrackingMedia = null
        resetTrackSelectionState()
    }
    noteSleepTimerEpisode()
}

private fun PlayerScreenRuntime.resetTrackSelectionState() {
    trackPreferenceRestoreApplied = false
    preferredAudioSelectionApplied = false
    appliedAudioPreferences = null
    isUserExplicitAudioSelection = false
    preferredSubtitleSelectionApplied = false
    isUserExplicitSubtitleSelection = false
    hasScannedTextTracksOnce = false
    audioTracks = emptyList()
    subtitleTracks = emptyList()
    selectedSubtitleIndex = -1
    selectedAddonSubtitleId = null
    useCustomSubtitles = false
}

internal fun PlayerScreenRuntime.resetAddonSubtitlesIfSourceChanged() {
    val sourceIdentity = sourcePlaybackKey
    if (lastAddonSubtitleSourceIdentity == sourceIdentity) return
    lastAddonSubtitleSourceIdentity = sourceIdentity
    SubtitleRepository.clear()
    autoFetchedAddonSubtitlesForKey = null
}

internal fun PlayerScreenRuntime.currentPlaybackProgressPercent(
    snapshot: PlayerPlaybackSnapshot = playbackSnapshot,
): Float {
    if (snapshot.isEnded && !isShortPlaceholderDuration(snapshot.durationMs)) return 100f
    val duration = snapshot.durationMs.takeIf { it > 0L } ?: return 0f
    return ((snapshot.positionMs.toFloat() / duration.toFloat()) * 100f)
        .coerceIn(0f, 100f)
}

internal data class TrackingScrobbleItemInputs(
    val contentType: String,
    val parentMetaId: String,
    val videoId: String?,
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
)

internal fun PlayerScreenRuntime.snapshotTrackingScrobbleItemInputs(): TrackingScrobbleItemInputs {
    val inputs = TrackingScrobbleItemInputs(
        contentType = contentType ?: parentMetaType,
        parentMetaId = parentMetaId,
        videoId = activeVideoId,
        title = title,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
    )
    if (!ServerItemRef.isServerId(parentMetaId)) return inputs
    val imdbId = (metaUiState.meta ?: playerMeta)
        ?.takeIf { it.id == parentMetaId }
        ?.imdbId
        ?.takeIf { it.startsWith("tt") }
    return if (imdbId != null) inputs.copy(parentMetaId = imdbId, videoId = null) else inputs.copy(title = "")
}

private fun TrackingScrobbleItemInputs.buildMedia(): TrackingMediaReference =
    buildTrackingMediaReference(
        contentType = contentType,
        parentMetaId = parentMetaId,
        videoId = videoId,
        title = title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
    )

internal fun PlayerScreenRuntime.currentTrackingMedia(): TrackingMediaReference =
    snapshotTrackingScrobbleItemInputs().buildMedia()

internal fun PlayerScreenRuntime.emitTrackingScrobbleStart() {
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    if (hasRequestedScrobbleStartForCurrentItem) return
    hasRequestedScrobbleStartForCurrentItem = true
    val requestGeneration = scrobbleStartRequestGeneration + 1L
    scrobbleStartRequestGeneration = requestGeneration

    scope.launch {
        if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return@launch
        val media = currentTrackingMedia()
        if (!media.hasResolvableIdentity) {
            hasRequestedScrobbleStartForCurrentItem = false
            return@launch
        }
        if (requestGeneration != scrobbleStartRequestGeneration || !hasRequestedScrobbleStartForCurrentItem) {
            return@launch
        }
        currentTrackingMedia = media
        TrackingScrobbleCoordinator.scrobble(
            profileId = profileId,
            action = TrackingScrobbleAction.START,
            event = TrackingScrobbleEvent(
                media = media,
                progressPercent = currentPlaybackProgressPercent().toDouble(),
            ),
        )
    }
}

internal fun PlayerScreenRuntime.emitTrackingScrobblePause(progressPercent: Float? = null) {
    emitTrackingScrobbleTerminal(
        action = TrackingScrobbleAction.PAUSE,
        progressPercent = progressPercent,
    )
}

internal fun PlayerScreenRuntime.emitTrackingScrobbleStop(progressPercent: Float? = null) {
    emitTrackingScrobbleTerminal(
        action = TrackingScrobbleAction.STOP,
        progressPercent = progressPercent,
    )
}

private fun PlayerScreenRuntime.emitTrackingScrobbleTerminal(
    action: TrackingScrobbleAction,
    progressPercent: Float?,
) {
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    val provided = progressPercent
    if (!hasRequestedScrobbleStartForCurrentItem && (provided ?: 0f) < 80f) return

    val percent = provided ?: currentPlaybackProgressPercent()
    val mediaSnapshot = currentTrackingMedia
    val inputsSnapshot = snapshotTrackingScrobbleItemInputs()
    scope.launch(NonCancellable) {
        val media = mediaSnapshot ?: inputsSnapshot.buildMedia()
        if (!media.hasResolvableIdentity) return@launch
        TrackingScrobbleCoordinator.scrobble(
            profileId = profileId,
            action = action,
            event = TrackingScrobbleEvent(media = media, progressPercent = percent.toDouble()),
        )
    }
    currentTrackingMedia = null
    hasRequestedScrobbleStartForCurrentItem = false
    pendingSeekScrobbleRestart = false
    scrobbleStartRequestGeneration += 1L
}

internal fun PlayerScreenRuntime.emitStopScrobbleForCurrentProgress() {
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    val progressPercent = currentPlaybackProgressPercent()
    if (!shouldSendStopScrobble(hasRequestedScrobbleStartForCurrentItem, progressPercent)) {
        return
    }
    if (progressPercent < 80f) {
        emitTrackingScrobbleStop(progressPercent)
        return
    }

    if (progressPercent >= 80f && !hasSentCompletionScrobbleForCurrentItem) {
        hasSentCompletionScrobbleForCurrentItem = true
        emitTrackingScrobbleStop(progressPercent)
    }
}

internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float,
): Boolean = hasActiveScrobble || progressPercent >= 80f

internal fun shouldUpdateTrackingScrobbleAfterSeek(
    hasActiveScrobble: Boolean,
    progressPercent: Float,
): Boolean = hasActiveScrobble && progressPercent >= 1f && progressPercent < 80f

internal fun PlayerScreenRuntime.emitTrackingSeekScrobbleStart() {
    if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return
    val mediaSnapshot = currentTrackingMedia
    val inputsSnapshot = snapshotTrackingScrobbleItemInputs()
    scope.launch {
        if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return@launch
        val media = mediaSnapshot ?: inputsSnapshot.buildMedia()
        if (!media.hasResolvableIdentity) return@launch
        TrackingScrobbleCoordinator.scrobbleSeek(
            profileId = profileId,
            action = TrackingScrobbleAction.START,
            event = TrackingScrobbleEvent(
                media = media,
                progressPercent = currentPlaybackProgressPercent().toDouble(),
            ),
        )
    }
}

internal fun PlayerScreenRuntime.tryShowParentalGuide() {
    if (!playerSettingsUiState.showParentalGuide) return
    if (!parentalGuideHasShown && parentalWarnings.isNotEmpty() && !playbackStartedForParentalGuide) {
        playbackStartedForParentalGuide = true
        showParentalGuide = true
        parentalGuideHasShown = true
    }
}

internal suspend fun PlayerScreenRuntime.resolveParentalGuideImdbId(): String? {
    val candidates = listOf(parentMetaId, activeVideoId)
    candidates.firstNotNullOfOrNull(::extractParentalGuideImdbId)?.let { return it }
    // Fallback: use imdb_id from addon meta response
    val metaImdbId = (metaUiState.meta ?: playerMeta)
        ?.takeIf { it.id == parentMetaId }
        ?.imdbId
        ?.takeIf { it.startsWith("tt") }
    if (metaImdbId != null) return metaImdbId
    val tmdbId = candidates.firstNotNullOfOrNull(::extractParentalGuideTmdbId) ?: return null
    return TmdbService.tmdbToImdb(
        tmdbId = tmdbId,
        mediaType = contentType ?: parentMetaType,
    )
}

internal fun PlayerScreenRuntime.isSourceAcceptedForAddonPlayerEvents(
    durationMs: Long = playbackSnapshot.durationMs,
    allowEnded: Boolean = false,
): Boolean {
    val url = activeSourceUrl
    if (url.isBlank()) return false
    if (errorMessage != null) return false
    if (sourceFailoverCancelled) return false
    if (sourceFallbackJob?.isActive == true) return false
    if (sourceFailoverForPlaceholder) return false
    if (placeholderDetectedUrl == url) return false
    if (isDesktop && activeVideoId != null && !url.startsWith("file:") && addonTrackingDurationSettledUrl != url) return false
    if (!allowEnded && playbackSnapshot.isEnded) return false
    if (durationMs <= 0L) return false
    if (isShortPlaceholderDuration(durationMs)) return false
    val assessment = if (activeTorrentInfoHash == null) {
        assessSourceLink(url, listOf(activeSourceFilename, activeStreamFilename))
    } else {
        null
    }
    if (assessment?.decisive == true) return false
    val suspect = placeholderSuspectUrl == url ||
        assessment != null ||
        activeStream?.saysNotCached == true
    val expectedMinutes = activeVideoId?.let { expectedRuntimeMinutes(it, activeSeasonNumber, activeEpisodeNumber) }
    return !isPlaceholderConfirmed(
        suspect = suspect,
        durationMs = durationMs,
        expectedRuntimeMinutes = expectedMinutes,
        resumeMs = activeInitialPositionMs,
    )
}

internal fun PlayerScreenRuntime.syncAddonPlayerStartForPlayback() {
    if (!playbackSnapshot.isPlaying || playbackSnapshot.isLoading) return
    if (activeAddonPlayerEventTarget != null && !isSourceAcceptedForAddonPlayerEvents()) {
        emitAddonPlayerEvent(AddonPlayerAction.STOP)
        return
    }
    if (!previousIsPlaying || activeAddonPlayerEventTarget == null) {
        emitAddonPlayerEvent(AddonPlayerAction.START)
    }
}

internal fun PlayerScreenRuntime.emitAddonPlayerEvent(
    action: AddonPlayerAction,
    forceResend: Boolean = false,
) {
    val durationMs = playbackSnapshot.durationMs
    val positionMs = playbackSnapshot.positionMs
    val active = activeAddonPlayerEventTarget
    if (action == AddonPlayerAction.STOP) {
        if (active == null) return
        activeAddonPlayerEventTarget = null
        lastAddonPlayerAction = null
        dispatchAddonPlayerEvent(active, AddonPlayerAction.STOP, positionMs, durationMs)
        return
    }
    val session = playbackSession
    val target = AddonPlayerEventTarget(type = session.contentType, videoId = session.videoId)
    if (active != null && active != target) {
        activeAddonPlayerEventTarget = null
        lastAddonPlayerAction = null
        dispatchAddonPlayerEvent(active, AddonPlayerAction.STOP, positionMs, durationMs)
    }
    if (action == AddonPlayerAction.PAUSE && activeAddonPlayerEventTarget == null) return
    if (!forceResend && activeAddonPlayerEventTarget == target && lastAddonPlayerAction == action) return
    if (!isSourceAcceptedForAddonPlayerEvents(
            durationMs = durationMs,
            allowEnded = action == AddonPlayerAction.PAUSE,
        )
    ) {
        val current = activeAddonPlayerEventTarget
        if (current != null && !(action == AddonPlayerAction.PAUSE && playbackHoldIncomplete())) {
            activeAddonPlayerEventTarget = null
            lastAddonPlayerAction = null
            dispatchAddonPlayerEvent(current, AddonPlayerAction.STOP, positionMs, durationMs)
        }
        return
    }
    activeAddonPlayerEventTarget = target
    lastAddonPlayerAction = action
    dispatchAddonPlayerEvent(target, action, positionMs, durationMs)
}

internal fun PlayerScreenRuntime.emitAddonPlayerProgressEvent(
    scrobbleAction: TrackingScrobbleAction = TrackingScrobbleAction.STOP,
    holdIncomplete: Boolean = playbackHoldIncomplete(),
) {
    when (scrobbleActionForProgressSave(scrobbleAction, holdIncomplete)) {
        TrackingScrobbleAction.PAUSE -> emitAddonPlayerEvent(AddonPlayerAction.PAUSE)
        TrackingScrobbleAction.STOP -> emitAddonPlayerEvent(AddonPlayerAction.STOP)
        TrackingScrobbleAction.START -> Unit
    }
}

internal fun PlayerScreenRuntime.resendAddonPlayerStateAfterSeek() {
    val action = lastAddonPlayerAction ?: return
    if (activeAddonPlayerEventTarget == null) return
    emitAddonPlayerEvent(action, forceResend = true)
}

/**
 * True when this save is the episode being left, not another source for the same one.
 * Season and episode come first. Ids are the tie-break when those numbers match.
 */
internal fun isLeavingPlaybackEpisode(
    currentVideoId: String?,
    currentSeason: Int?,
    currentEpisode: Int?,
    nextVideoId: String?,
    nextSeason: Int?,
    nextEpisode: Int?,
): Boolean {
    if (nextSeason != currentSeason || nextEpisode != currentEpisode) return true
    val currentId = currentVideoId?.takeIf { it.isNotBlank() } ?: return false
    val nextId = nextVideoId?.takeIf { it.isNotBlank() } ?: return false
    return currentId != nextId
}

/**
 * The save every minute stays on this computer. Pause, seek, and stop are what reach the server.
 * [holdIncomplete] only changes the row stored here. It does not send the tick.
 */
internal fun playbackProgressTickSyncsRemote(holdIncomplete: Boolean): Boolean = false

internal fun PlayerScreenRuntime.flushWatchProgress(
    scrobbleAction: TrackingScrobbleAction = TrackingScrobbleAction.STOP,
    leavingEpisode: Boolean = false,
    holdIncomplete: Boolean = playbackHoldIncomplete(leavingEpisode = leavingEpisode),
) {
    if (frozenForQuit) return
    commitWatchProgress(
        scrobbleAction = scrobbleAction,
        holdIncomplete = holdIncomplete,
        syncRemote = !holdIncomplete,
    )
}

/**
 * The save shared by pause, stop, and quit. A sleep hold turns a stop into a pause, keeps the
 * episode unfinished, and does not schedule removal of a downloaded file.
 */
private fun PlayerScreenRuntime.commitWatchProgress(
    scrobbleAction: TrackingScrobbleAction,
    holdIncomplete: Boolean,
    syncRemote: Boolean,
) {
    val action = scrobbleActionForProgressSave(scrobbleAction, holdIncomplete)
    WatchProgressRepository.flushPlaybackProgress(
        session = playbackSession,
        snapshot = playbackSnapshot,
        syncRemote = syncRemote,
        holdIncomplete = holdIncomplete,
    )
    when (action) {
        TrackingScrobbleAction.PAUSE -> emitTrackingScrobblePause()
        TrackingScrobbleAction.STOP -> {
            emitStopScrobbleForCurrentProgress()
            reportLocalPlaybackCompleted()
        }
        TrackingScrobbleAction.START -> Unit
    }
    emitAddonPlayerProgressEvent(
        scrobbleAction = scrobbleAction,
        holdIncomplete = holdIncomplete,
    )
}

/** Tells automatic downloads that a downloaded file was played to the point that counts as watched. */
private fun PlayerScreenRuntime.reportLocalPlaybackCompleted() {
    if (!activeSourceUrl.startsWith("file:")) return
    val completed = isWatchProgressComplete(
        positionMs = playbackSnapshot.positionMs,
        durationMs = playbackSnapshot.durationMs,
        isEnded = playbackSnapshot.isEnded,
    )
    if (completed) AutoDownloads.onLocalPlaybackCompleted(activeSourceUrl)
}

internal fun PlayerScreenRuntime.stopPlaybackAndFlushForQuit() {
    // Taken before playback is forced off. An armed minute timer uses that to decide whether
    // it would pause before the file ends, and a sleep stop already holding stays held.
    val holdIncomplete = playbackHoldIncomplete()
    Snapshot.withMutableSnapshot {
        frozenForQuit = true
        shouldPlay = false
        seekProgressSyncJob?.cancel()
        seekProgressSyncJob = null
        previousIsPlaying = false
        pendingSeekScrobbleRestart = false
        if (playbackSnapshot.isPlaying) {
            playbackSnapshot = playbackSnapshot.copy(isPlaying = false)
        }
    }
    runCatching { playerController?.pause() }
    commitWatchProgress(
        scrobbleAction = TrackingScrobbleAction.STOP,
        holdIncomplete = holdIncomplete,
        syncRemote = false,
    )
}

internal fun PlayerScreenRuntime.scheduleProgressSyncAfterSeek() {
    if (frozenForQuit) return
    val shouldRestartScrobbleAfterSeek = shouldPlay || playbackSnapshot.isPlaying
    if (!shouldRestartScrobbleAfterSeek) {
        awaitingPausedSeekSnapshot = true
    }
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(PlayerSeekProgressSyncDebounceMs)
        if (frozenForQuit) return@launch
        if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return@launch
        val expectedTargetMs = lastSeekTargetMs ?: pendingSeekTarget?.first
        val reachedTarget = expectedTargetMs?.let { targetMs ->
            abs(playbackSnapshot.positionMs - targetMs) <= 1_000L
        } ?: true
        if (reachedTarget) {
            awaitingPausedSeekSnapshot = false
        }
        // A seek during a sleep stop must not mark the episode watched or scrobble past it.
        val holdIncomplete = playbackHoldIncomplete()
        WatchProgressRepository.upsertPlaybackProgress(
            session = playbackSession,
            snapshot = playbackSnapshot,
            syncRemote = !holdIncomplete,
            holdIncomplete = holdIncomplete,
        )
        if (holdIncomplete) return@launch
        resendAddonPlayerStateAfterSeek()

        val progressPercent = currentPlaybackProgressPercent()
        if (
            !shouldUpdateTrackingScrobbleAfterSeek(
                hasActiveScrobble = hasRequestedScrobbleStartForCurrentItem,
                progressPercent = progressPercent,
            )
        ) {
            return@launch
        }

        val media = currentTrackingMedia ?: currentTrackingMedia()
        if (!media.hasResolvableIdentity) return@launch
        val stopEvent = TrackingScrobbleEvent(
            media = media,
            progressPercent = progressPercent.toDouble(),
        )
        scope.launch {
            if (isShortPlaceholderDuration(playbackSnapshot.durationMs)) return@launch
            TrackingScrobbleCoordinator.scrobbleSeek(
                profileId = profileId,
                action = TrackingScrobbleAction.STOP,
                event = stopEvent,
            )
            if (!shouldRestartScrobbleAfterSeek || !shouldPlay || playbackSnapshot.isEnded ||
                isShortPlaceholderDuration(playbackSnapshot.durationMs)
            ) return@launch
            if (playbackSnapshot.isPlaying) {
                pendingSeekScrobbleRestart = false
                TrackingScrobbleCoordinator.scrobbleSeek(
                    profileId = profileId,
                    action = TrackingScrobbleAction.START,
                    event = stopEvent.copy(
                        progressPercent = currentPlaybackProgressPercent().toDouble(),
                    ),
                )
            } else {
                pendingSeekScrobbleRestart = true
            }
        }
    }
}

internal fun PlayerScreenRuntime.persistPlaybackProgressTick() {
    if (frozenForQuit) return
    val now = WatchProgressClock.nowEpochMs()
    if (now - lastProgressPersistEpochMs < PlaybackProgressPersistIntervalMs) return
    lastProgressPersistEpochMs = now
    val holdIncomplete = playbackHoldIncomplete(now)
    WatchProgressRepository.upsertPlaybackProgress(
        session = playbackSession,
        snapshot = playbackSnapshot,
        syncRemote = playbackProgressTickSyncsRemote(holdIncomplete),
        holdIncomplete = holdIncomplete,
    )
}

internal val AndroidPlaybackEngine.switchTarget: AndroidPlaybackEngine
    get() = if (this == AndroidPlaybackEngine.Libmpv) AndroidPlaybackEngine.ExoPlayer else AndroidPlaybackEngine.Libmpv

internal fun PlayerScreenRuntime.switchPlaybackEngine() {
    val target = playerController?.playbackEngine?.switchTarget ?: return
    flushWatchProgress()
    if (initialSeekApplied) {
        activeInitialPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
        activeInitialProgressFraction = null
    }
    // Capture the current track selection so it can be restored on the new engine.
    rememberCurrentTrackPreferenceForEngineSwitch(target)
    playbackEngineOverride = target
    // A manual switch is an explicit user decision: never let the automatic engine
    // failover drag the user back to the engine they just switched away from.
    engineFailoverDisarmed = true
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = Res.string.player_engine_switching_manual_message,
            messageArgs = listOf(target.label),
        ),
    )
}

internal fun PlayerScreenRuntime.refreshStreamInfo() {
    refreshTracks()
    val controller = playerController ?: return
    scope.launch { streamMediaInfo = controller.getMediaInfo() }
}

/**
 * Switches to the opposite playback engine after a playback error, when the
 * "auto-switch engine on playback error" setting is enabled. Applies whichever engine is
 * selected; fires at most once per source, after the engine's own retries, and never after a
 * manual engine switch.
 *
 * @return true when the failover was started and the caller should swallow the error.
 */
internal fun PlayerScreenRuntime.maybeFailoverPlaybackEngine(
    resolvedAutoEngine: AndroidPlaybackEngine?,
): Boolean {
    if (!playerSettingsUiState.autoSwitchPlaybackEngineOnError) return false
    if (engineFailoverDisarmed) return false
    val sourceIdentity = activePlaybackIdentity
    if (engineFailoverSourceIdentity == sourceIdentity) return false

    val current = playerController?.playbackEngine
        ?: playbackEngineOverride
        ?: resolvedAutoEngine
        ?: playerSettingsUiState.androidPlaybackEngine
    val target = if (current == AndroidPlaybackEngine.Libmpv) {
        AndroidPlaybackEngine.ExoPlayer
    } else {
        AndroidPlaybackEngine.Libmpv
    }

    engineFailoverTriggered = true
    engineFailoverSourceIdentity = sourceIdentity
    flushWatchProgress()
    if (initialSeekApplied) {
        activeInitialPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
        activeInitialProgressFraction = null
    }
    // Capture the current track selection so it can be restored on the new engine.
    rememberCurrentTrackPreferenceForEngineSwitch(target)
    playbackEngineOverride = target
    showGestureFeedback(
        GestureFeedbackState(
            messageRes = Res.string.player_engine_switching_automatic_message,
            messageArgs = listOf(target.label),
        ),
    )
    return true
}

internal fun PlayerScreenRuntime.openStreamInfo() {
    refreshTracks()
    val controller = playerController
    scope.launch {
        streamMediaInfo = controller?.getMediaInfo() ?: PlayerMediaInfo()
        showStreamInfo = true
    }
}
