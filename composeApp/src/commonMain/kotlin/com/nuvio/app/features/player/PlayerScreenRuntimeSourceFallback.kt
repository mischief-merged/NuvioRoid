package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.runtimeMinutesFor
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlayPolicy
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.cachedFirst
import com.nuvio.app.features.streams.saysNotCached
import com.nuvio.app.isDesktop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_failover_exhausted
import nuvio.composeapp.generated.resources.player_failover_now_playing
import nuvio.composeapp.generated.resources.player_failover_placeholder_exhausted
import nuvio.composeapp.generated.resources.player_failover_placeholder_last_source
import nuvio.composeapp.generated.resources.player_failover_placeholder_warning
import nuvio.composeapp.generated.resources.player_failover_trying_after_placeholder
import nuvio.composeapp.generated.resources.player_failover_trying_after_stall
import nuvio.composeapp.generated.resources.player_failover_trying_source
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private const val SOURCE_FALLBACK_POLL_COUNT = 30
private const val SOURCE_FALLBACK_POLL_INTERVAL_MS = 500L
private const val SOURCE_FAILOVER_NOTICE_DURATION_MS = 4_000L

private val log = Logger.withTag("PlayerSourceFallback")

/** Monotonic clock for the placeholder check; its origin does not matter, only differences do. */
private val placeholderClock = kotlin.time.TimeSource.Monotonic.markNow()

/**
 * Plays [stream] at [url] in place of the current source. Playback resumes at [resumePositionMs]
 * when it is given. Otherwise, before playback has started the pending resume position is kept,
 * and after it continues from the current position. A source chosen by moving on from a failed
 * one counts as chosen by the app ([byFailover]), so it can be replaced in turn.
 */
internal fun PlayerScreenRuntime.playReplacementSource(
    stream: StreamItem,
    url: String,
    videoId: String,
    resumePositionMs: Long? = null,
    byFailover: Boolean = false,
    preparedSubtitles: PreparedStartupSubtitles? = null,
) {
    if (playerSettingsUiState.streamReuseLastLinkEnabled) {
        saveDirectStreamForReuse(stream, url, videoId, activeSeasonNumber, activeEpisodeNumber)
    }
    if (byFailover || activeSourceUrl == autoSelectedSourceUrl) autoSelectedSourceUrl = url
    if (resumePositionMs != null) {
        activeInitialPositionMs = resumePositionMs.coerceAtLeast(0L)
        activeInitialProgressFraction = null
    } else if (initialLoadCompleted) {
        activeInitialPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
        activeInitialProgressFraction = null
    }
    flushWatchProgress()
    stopActiveP2pStream()
    externalSubtitles = preparedSubtitles?.subtitles ?: stream.externalSubtitles
    startupSubtitleSelection = preparedSubtitles?.selection
    activeSourceUrl = url
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
    activeStreamType = stream.streamType
    activeSourceIdentityKey = stream.playerSourceIdentityKey() ?: activeSourceIdentityKey
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
    showSourcesPanel = false
    controlsVisible = true
}

/** Where moving on to another source looks for sources. */
internal interface FailoverSourceSearch {
    fun start(type: String, videoId: String, season: Int?, episode: Int?)
    fun state(): com.nuvio.app.features.streams.StreamsUiState
    fun stop()

    /** The player's own source search, which joins the one the stream screen ran. */
    object Player : FailoverSourceSearch {
        override fun start(type: String, videoId: String, season: Int?, episode: Int?) =
            PlayerStreamsRepository.loadSources(type = type, videoId = videoId, season = season, episode = episode)
        override fun state() = PlayerStreamsRepository.sourceState.value
        override fun stop() = PlayerStreamsRepository.stopSourcesLoading()
    }
}

/** Shows [message] as the playback error unless the next source is tried instead. */
internal fun PlayerScreenRuntime.showErrorUnlessSourceFallback(message: String?) {
    if (tryFallbackToNextSourceAfterError(message)) return
    errorMessage = message
    controlsVisible = !playerControlsLocked
}

/** The user pressed Back: nothing is started after this, and a source being looked up is dropped. */
internal fun PlayerScreenRuntime.cancelSourceFailoverOnExit() {
    sourceFailoverCancelled = true
    endSourceFailover()
}

/**
 * The user picked a source (or an episode) themselves. A source being looked up is dropped. A
 * source that failed earlier is still never tried again, but the count of sources tried starts
 * over; a new episode forgets the failed sources too, as they belonged to another video.
 */
internal fun PlayerScreenRuntime.restartSourceFailoverAfterUserChoice(newVideo: Boolean = false) {
    endSourceFailover()
    sourceFallbackAttempts = 0
    if (newVideo) sourceFallbackTriedKeys.clear()
}

private fun PlayerScreenRuntime.endSourceFailover() {
    sourceFallbackJob?.cancel()
    sourceFallbackJob = null
    sourceFailoverTryingName = null
    sourceFailoverPlayingName = null
}

/** The name of a source as the loading screen and the messages give it. */
private fun StreamItem.failoverDisplayName(): String =
    addonName.takeIf { it.isNotBlank() } ?: streamLabel

/** Called on the first frame of a source that replaced a failed one: says which source plays now. */
internal fun PlayerScreenRuntime.announceSourceFailoverIfPending() {
    val name = sourceFailoverPlayingName ?: return
    sourceFailoverPlayingName = null
    sourceFailoverTryingName = null
    scope.launch { showSourceNotice(getString(Res.string.player_failover_now_playing, name)) }
}

private fun PlayerScreenRuntime.showSourceNotice(text: String) {
    if (isDesktop) {
        playerNotificationMessage = text
        playerNotificationToken += 1L
    } else {
        NuvioToastController.show(text, SOURCE_FAILOVER_NOTICE_DURATION_MS, announce = true)
    }
}

/** The loading screen text while a replacement for a failed source is looked up, or null when none is. */
@Composable
internal fun PlayerScreenRuntime.sourceFailoverLoadingMessage(): String? =
    sourceFailoverTryingName?.let { name ->
        stringResource(
            when {
                sourceFailoverForPlaceholder -> Res.string.player_failover_trying_after_placeholder
                sourceFailoverForStall -> Res.string.player_failover_trying_after_stall
                else -> Res.string.player_failover_trying_source
            },
            name,
        )
    }

/**
 * Looks at the first seconds of a source for a placeholder clip: a file far shorter than the expected
 * running time. Runs once per source on desktop. When the setting is off the source is kept and the user
 * is told once; otherwise it is treated as a failed source and the next one is tried from the start.
 */
internal fun PlayerScreenRuntime.checkForPlaceholderSource(snapshot: PlayerPlaybackSnapshot) {
    if (!isDesktop || sourceFailoverCancelled) return
    val url = activeSourceUrl
    val videoId = activeVideoId
    if (url.isBlank() || videoId == null || placeholderCheckedUrl == url) return
    val nowMs = placeholderClock.elapsedNow().inWholeMilliseconds
    // A file that has played to its end has its final length: no need to wait for it to settle. A clip
    // shorter than the check's settling time ends before the check could decide otherwise.
    if (snapshot.isEnded && snapshot.durationMs > 0L) {
        placeholderCheckedUrl = url
        placeholderCheckState = null
        judgePlaceholderLength(url, videoId, snapshot.durationMs, ended = true)
        return
    }
    // The window starts at the first frame; before it there is nothing to look at.
    val state = placeholderCheckState?.takeIf { placeholderCheckStateUrl == url }
        ?: if (snapshot.isLoading) return else PlaceholderCheckState(nowMs, snapshot.positionMs)
    placeholderCheckStateUrl = url
    val step = stepPlaceholderCheck(
        state = state,
        nowMs = nowMs,
        positionMs = snapshot.positionMs,
        durationMs = snapshot.durationMs,
        isLoading = snapshot.isLoading,
        network = NetworkStatusRepository.uiState.value.condition,
    )
    val durationMs = when (step) {
        is PlaceholderCheckStep.Continue -> {
            placeholderCheckState = step.state
            return
        }
        PlaceholderCheckStep.Finished -> {
            placeholderCheckedUrl = url
            placeholderCheckState = null
            return
        }
        is PlaceholderCheckStep.Stable -> step.durationMs
    }
    placeholderCheckedUrl = url
    placeholderCheckState = null
    judgePlaceholderLength(url, videoId, durationMs, ended = false)
}

/** Judges the final length of the source at [url]; a placeholder is moved on from. */
private fun PlayerScreenRuntime.judgePlaceholderLength(url: String, videoId: String, durationMs: Long, ended: Boolean) {
    val expectedMinutes = expectedRuntimeMinutes(videoId, activeSeasonNumber, activeEpisodeNumber)
    // A link, name or label that made the source a suspect needs only a short stable length to be confirmed.
    val suspect = placeholderSuspectUrl == url
    val confirmed = isPlaceholderConfirmed(
        suspect = suspect,
        durationMs = durationMs,
        expectedRuntimeMinutes = expectedMinutes,
        resumeMs = activeInitialPositionMs,
    )
    if (!confirmed) return
    log.i { "Source looks like a placeholder: $durationMs ms against $expectedMinutes minutes expected (suspect=$suspect, ended=$ended)" }
    // Judged by its length: a real video can be that short, so when no other source plays this one stays.
    handleDetectedPlaceholder(url, byLength = true)
}

/**
 * The running time of a video of the title playing: from the details screen when it is open on that title,
 * otherwise from the details the player loaded itself (playing from Continue Watching, Home or the next
 * episode). Null when neither knows it.
 */
internal fun PlayerScreenRuntime.expectedRuntimeMinutes(videoId: String, season: Int?, episode: Int?): Int? =
    MetaDetailsRepository.runtimeMinutesFor(parentMetaId, videoId, season, episode)
        ?: playerMeta?.takeIf { it.id == parentMetaId }?.runtimeMinutesFor(videoId, season, episode)

/**
 * Looks at the resolved link and the file names of a source that was just handed to the player. A known status
 * clip in a static folder decides before playback starts; any other sign only makes the source a suspect. Desktop only,
 * like the duration check. A source with a P2P torrent behind it is left alone.
 */
internal fun PlayerScreenRuntime.checkForPlaceholderBySourceSignals() {
    if (!isDesktop || sourceFailoverCancelled || activeTorrentInfoHash != null) return
    val url = activeSourceUrl
    if (url.isBlank() || activeVideoId == null) return
    val assessment = assessSourceLink(url, listOf(activeSourceFilename, activeStreamFilename))
    if (assessment == null) {
        // The add-on says the debrid service does not have the file yet: it plays the service's notice.
        if (activeStream?.saysNotCached == true) {
            log.i { "Source is suspect of being a placeholder: its label says it is not cached" }
            placeholderSuspectUrl = url
        }
        return
    }
    if (!assessment.decisive) {
        // Only a suspect: the length the player reports has to confirm it.
        log.i { "Source is suspect of being a placeholder: ${assessment.signal.name}" }
        placeholderSuspectUrl = url
        return
    }
    log.i { "Source is a provider placeholder by its link: ${assessment.signal.name}" }
    handleDetectedPlaceholder(url)
}

/**
 * A placeholder was recognised: treat it as a failed source and move on, which applies in every mode. When
 * the setting is off (or the device is offline) the source is kept and the user is told once.
 */
private fun PlayerScreenRuntime.handleDetectedPlaceholder(url: String, byLength: Boolean = false) {
    sourceFailoverPlaceholderByLength = byLength
    if (tryFallbackToNextSourceAfterError(message = null, failureOverride = SourceFailure.PlaceholderClip)) return
    // Not moved on: the setting is off (or the device is offline). Say so once, only when the user chose to stay.
    if (!playerSettingsUiState.streamAutoPlayFallbackActive && placeholderWarnedUrl != url) {
        placeholderWarnedUrl = url
        scope.launch { showSourceNotice(getString(Res.string.player_failover_placeholder_warning)) }
    }
}

/**
 * Called when a source fails and the user's setting asks for the next one to be tried. Before
 * playback starts it applies to a source the app chose, never to one the user picked by hand
 * (see [SOURCE_FAILOVER_ON_START_IN_MANUAL_MODE]); after playback started it applies to any
 * source and resumes a couple of seconds before where it died. It excludes the engine switch on
 * error, never runs while the device is offline, never tries a source that already failed and
 * stops at the number of sources set. The order is the one the user's mode produced.
 *
 * Returns `true` when the failure is taken care of (a replacement is being looked up, or the final
 * message is being shown) and the caller must not show the error itself.
 */
internal fun PlayerScreenRuntime.tryFallbackToNextSourceAfterError(
    message: String?,
    failureOverride: SourceFailure? = null,
): Boolean {
    if (sourceFallbackJob?.isActive == true) return true
    val settings = playerSettingsUiState
    if (!settings.streamAutoPlayFallbackActive) return false
    if (settings.engineSwitchOnErrorBlocksSourceFailover) return false
    val failedUrl = activeSourceUrl
    if (failedUrl.isBlank()) return false
    val videoId = activeVideoId ?: return false

    val failure = failureOverride
        ?: if (initialLoadCompleted) SourceFailure.DiedDuringPlayback else SourceFailure.FailedToStart
    val forPlaceholder = failure == SourceFailure.PlaceholderClip
    val forStall = failure == SourceFailure.StalledDuringPlayback
    val manualMode = settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL
    val chosenByApp = failedUrl == autoSelectedSourceUrl
    sourceFailoverForPlaceholder = forPlaceholder
    sourceFailoverForStall = forStall
    // Everything except the list is checked first, so a failure that does not apply is left alone.
    val preflight = decideSourceFailover(
        failure = failure,
        settingEnabled = true,
        manualMode = manualMode,
        failedSourceChosenByApp = chosenByApp,
        userCancelled = sourceFailoverCancelled,
        network = NetworkStatusRepository.uiState.value.condition,
        attempts = sourceFallbackAttempts,
        maxAttempts = settings.streamAutoPlayFallbackMaxSources,
        ranked = listOf(""),
        failedKeys = emptySet(),
        keyOf = { it },
    )
    if (preflight is SourceFailoverDecision.Stop) {
        if (preflight.reason == SourceFailoverStop.LimitReached && sourceFallbackAttempts > 0) {
            scope.launch { finishSourceFailover(message, preflight.reason) }
            return true
        }
        sourceFailoverForPlaceholder = false
        sourceFailoverForStall = false
        return false
    }

    activeStream?.playerSourceIdentityKey()?.let(sourceFallbackTriedKeys::add)
    activeSourceIdentityKey?.let(sourceFallbackTriedKeys::add)
    val failedInfoHash = activeStream?.p2pInfoHash ?: currentStreamInfoHash
    val failedAddonId = activeProviderAddonId
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    val resumeMs = when (failure) {
        // The placeholder's position means nothing for the real video.
        SourceFailure.PlaceholderClip -> 0L
        SourceFailure.DiedDuringPlayback, SourceFailure.StalledDuringPlayback -> sourceFailoverResumePositionMs(
            positionMs = playbackSnapshot.positionMs,
            durationMs = playbackSnapshot.durationMs,
        )
        SourceFailure.FailedToStart -> null
    }
    errorMessage = null

    sourceFallbackJob = scope.launch {
        var stopReason = SourceFailoverStop.Exhausted
        try {
            // Joins the shared source search, so results the stream screen already found are reused.
            failoverSourceSearch.start(contentType ?: parentMetaType, videoId, season, episode)
            val preferences = StreamAutoPlayPolicy.preferencesFor(settings.streamAutoPlayMode)
            val runtimeMinutes = if (settings.streamAutoPlayMode == StreamAutoPlayMode.PREFERRED) {
                expectedRuntimeMinutes(videoId, season, episode)
            } else {
                null
            }
            val wanted = if (settings.streamAutoPlayMode == StreamAutoPlayMode.PREFERRED) {
                MetaDetailsRepository.wantedContentFor(parentMetaId, season, episode)
            } else {
                null
            }
            var polls = 0
            while (true) {
                val state = failoverSourceSearch.state()
                val ranked = rankedSourceFailoverCandidates(
                    streams = state.groups.flatMap { it.streams },
                    settings = playerSettingsUiState,
                    failedAddonId = failedAddonId,
                    failedInfoHash = failedInfoHash,
                    preferences = preferences,
                    runtimeMinutes = runtimeMinutes,
                    wanted = wanted,
                )
                val decision = decideSourceFailover(
                    failure = failure,
                    settingEnabled = playerSettingsUiState.streamAutoPlayFallbackActive,
                    manualMode = manualMode,
                    failedSourceChosenByApp = chosenByApp,
                    userCancelled = sourceFailoverCancelled,
                    network = NetworkStatusRepository.uiState.value.condition,
                    attempts = sourceFallbackAttempts,
                    maxAttempts = playerSettingsUiState.streamAutoPlayFallbackMaxSources,
                    ranked = ranked,
                    failedKeys = sourceFallbackTriedKeys,
                    keyOf = { it.playerSourceIdentityKey() },
                )
                if (decision is SourceFailoverDecision.Stop) {
                    stopReason = decision.reason
                    val searchDone = state.emptyStateReason != null || (!state.isAnyLoading && state.groups.isNotEmpty())
                    if (decision.reason == SourceFailoverStop.Exhausted && !searchDone && polls < SOURCE_FALLBACK_POLL_COUNT) {
                        delay(SOURCE_FALLBACK_POLL_INTERVAL_MS)
                        polls++
                        continue
                    }
                    break
                }
                val next = (decision as SourceFailoverDecision.TryNext).candidate
                next.playerSourceIdentityKey()?.let(sourceFallbackTriedKeys::add)
                sourceFallbackAttempts++
                sourceFailoverTryingName = next.failoverDisplayName()

                val (resolved, preparedSubs) = SubtitleStartupCoordinator.prepareStreamInParallelWithSource(
                    request = StartupSubtitleRequest(
                        type = contentType ?: parentMetaType,
                        videoId = videoId,
                        parentMetaId = parentMetaId,
                        settings = playerSettingsUiState,
                        streamExternalSubtitles = next.externalSubtitles,
                        videoHash = next.behaviorHints.videoHash,
                        videoSizeBytes = next.behaviorHints.videoSize,
                        filename = next.behaviorHints.filename,
                    ),
                    resolveSource = {
                        DirectDebridPlaybackResolver.resolveToPlayableStream(next, season, episode)
                    },
                    extractStreamSubtitles = { res ->
                        (res as? DirectDebridPlayableResult.Success)?.stream?.externalSubtitles.orEmpty()
                    },
                )
                val playable = when (resolved) {
                    is DirectDebridPlayableResult.Success -> resolved.stream
                    else -> null
                }
                val url = playable?.playableDirectUrl
                if (playable == null || url.isNullOrBlank() || url == failedUrl) {
                    log.d { "Fallback source from ${next.addonName} has no playable link, trying the next one" }
                    continue
                }
                val stopBeforeLoading = sourceFailoverStopBeforeLoading(
                    userCancelled = sourceFailoverCancelled,
                    network = NetworkStatusRepository.uiState.value.condition,
                )
                if (stopBeforeLoading == SourceFailoverStop.UserCancelled) return@launch
                if (stopBeforeLoading != null) {
                    // Went offline while the link was resolved: nothing is loaded and the attempt is given back.
                    sourceFallbackAttempts = (sourceFallbackAttempts - 1).coerceAtLeast(0)
                    stopReason = stopBeforeLoading
                    break
                }
                log.i { "Source failed (${failure.name}), moving on to ${playable.streamLabel} from ${playable.addonName}" }
                playReplacementSource(
                    stream = playable,
                    url = url,
                    videoId = videoId,
                    resumePositionMs = resumeMs,
                    byFailover = true,
                    preparedSubtitles = preparedSubs,
                )
                // A placeholder may have played to its end, which stops playback: the real video is to play.
                if (failure == SourceFailure.PlaceholderClip) shouldPlay = true
                sourceFailoverPlayingName = next.failoverDisplayName()
                return@launch
            }
        } finally {
            failoverSourceSearch.stop()
        }
        if (sourceFailoverCancelled) return@launch
        finishSourceFailover(message, stopReason)
    }
    return true
}

/**
 * Ends moving on with the error screen. When at least one other source was tried, the message says
 * that the sources tried did not play. When the device is offline, the original error stays: the
 * sources were not at fault.
 */
private suspend fun PlayerScreenRuntime.finishSourceFailover(message: String?, reason: SourceFailoverStop) {
    // The last source that failed may have been saved for reuse.
    removeFailedStreamFromCache()
    sourceFailoverTryingName = null
    sourceFailoverPlayingName = null
    sourceFailoverForStall = false
    if (sourceFailoverForPlaceholder) {
        sourceFailoverForPlaceholder = false
        if (sourceFailoverPlaceholderByLength && reason != SourceFailoverStop.Offline) {
            // Only its length made it a placeholder, and every other source failed or looked the same: it may
            // be a real short video. It keeps playing (the user can start it again) instead of an error screen.
            showSourceNotice(getString(Res.string.player_failover_placeholder_last_source))
            controlsVisible = !playerControlsLocked
            return
        }
        if (reason == SourceFailoverStop.Offline) {
            // The device is offline: the other sources were not at fault, so the clip keeps playing and the user is told.
            showSourceNotice(getString(Res.string.player_failover_placeholder_warning))
        } else {
            errorMessage = getString(Res.string.player_failover_placeholder_exhausted)
            controlsVisible = !playerControlsLocked
        }
        return
    }
    errorMessage = if (sourceFallbackAttempts > 0 && reason != SourceFailoverStop.Offline) {
        getString(Res.string.player_failover_exhausted)
    } else {
        message
    }
    controlsVisible = !playerControlsLocked
}

/**
 * The sources to try after one failed, best first, in the order the user's mode produced: the
 * saved preferences in [StreamAutoPlayMode.PREFERRED], the rules of the mode otherwise, and the
 * order of the source list in Manual mode. Other copies of the torrent that failed are left out.
 * Sources that already failed are skipped by the caller.
 */
internal fun rankedSourceFailoverCandidates(
    streams: List<StreamItem>,
    settings: PlayerSettingsUiState,
    failedAddonId: String?,
    failedInfoHash: String?,
    preferences: com.nuvio.app.features.downloads.SeasonDownloadDefaults = com.nuvio.app.features.downloads.SeasonDownloadDefaults(),
    runtimeMinutes: Int? = null,
    wanted: com.nuvio.app.features.streams.WantedContent? = null,
    installedAddonNames: Set<String> = AddonRepository.uiState.value.addons.enabledAddons().map { it.displayTitle }.toSet(),
    debridEnabled: Boolean = DebridSettingsRepository.snapshot().canResolvePlayableLinks,
    activeResolverProviderId: String? = DebridSettingsRepository.snapshot().activeResolverProviderId,
): List<StreamItem> {
    val candidates = streams.filterNot { stream ->
        failedInfoHash != null && stream.addonId == failedAddonId &&
            stream.p2pInfoHash.equals(failedInfoHash, ignoreCase = true)
    }
    if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) {
        // The order of the list, except that sources the debrid service does not have yet come last.
        return candidates
            .filter { StreamAutoPlaySelector.canStartInPlayer(it, debridEnabled, activeResolverProviderId) }
            .cachedFirst { it.saysNotCached }
    }
    return StreamAutoPlaySelector.evaluateAutoPlayStream(
        streams = candidates,
        mode = settings.streamAutoPlayMode,
        regexPattern = settings.streamAutoPlayRegex,
        source = settings.streamAutoPlaySource,
        installedAddonNames = installedAddonNames,
        selectedAddons = settings.streamAutoPlaySelectedAddons,
        selectedPlugins = settings.streamAutoPlaySelectedPlugins,
        debridEnabled = debridEnabled,
        activeResolverProviderId = activeResolverProviderId,
        preferences = preferences,
        runtimeMinutes = runtimeMinutes,
        wanted = wanted,
    ).readyStreams
}

/**
 * The best stream the user's mode would pick from [streams], leaving out the sources already
 * tried and other copies of the torrent that failed.
 */
internal fun nextSourceFallbackCandidate(
    streams: List<StreamItem>,
    settings: PlayerSettingsUiState,
    triedKeys: Set<String>,
    failedAddonId: String? = null,
    failedInfoHash: String? = null,
    installedAddonNames: Set<String> = AddonRepository.uiState.value.addons.enabledAddons().map { it.displayTitle }.toSet(),
    debridEnabled: Boolean = DebridSettingsRepository.snapshot().canResolvePlayableLinks,
    activeResolverProviderId: String? = DebridSettingsRepository.snapshot().activeResolverProviderId,
): StreamItem? =
    rankedSourceFailoverCandidates(
        streams = streams,
        settings = settings,
        failedAddonId = failedAddonId,
        failedInfoHash = failedInfoHash,
        installedAddonNames = installedAddonNames,
        debridEnabled = debridEnabled,
        activeResolverProviderId = activeResolverProviderId,
    ).firstOrNull { stream ->
        val key = stream.playerSourceIdentityKey()
        key == null || key !in triedKeys
    }
