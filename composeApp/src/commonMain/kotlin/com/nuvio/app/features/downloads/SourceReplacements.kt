package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The search of the app for one title whose source was bad, as a season download of that one item:
 * the saved quality, bitrate and language choices, every source already downloaded for it left out,
 * and no more sources than the app may still start for it ([MaxAutoSourceAttempts] in all).
 */
internal fun replacementRequest(
    bad: DownloadItem,
    skip: Set<String>,
    attemptsUsed: Int,
    choices: SeasonDownloadDefaults,
    runtimeMinutes: Int?,
): SeasonBatchRequest {
    val season = bad.seasonNumber
    val episodeNumber = bad.episodeNumber
    val episode = if (season != null && episodeNumber != null) {
        SeasonBatchEpisode(
            videoId = bad.videoId,
            seasonNumber = season,
            episodeNumber = episodeNumber,
            title = bad.episodeTitle,
            thumbnail = bad.episodeThumbnail,
            runtimeMinutes = runtimeMinutes,
        )
    } else {
        SeasonBatchEpisode(
            videoId = bad.videoId,
            seasonNumber = MovieBatchSeasonNumber,
            episodeNumber = 1,
            title = null,
            thumbnail = null,
            runtimeMinutes = runtimeMinutes,
            movie = SeasonBatchMovie(
                metaId = bad.parentMetaId,
                metaType = bad.parentMetaType,
                title = bad.title,
                logo = bad.logo,
                poster = bad.poster,
                background = bad.background,
            ),
        )
    }
    return SeasonBatchRequest(
        contentType = bad.contentType,
        parentMetaId = bad.parentMetaId,
        parentMetaType = bad.parentMetaType,
        title = bad.title,
        logo = bad.logo,
        poster = bad.poster,
        background = bad.background,
        seasonNumber = season ?: MovieBatchSeasonNumber,
        episodes = listOf(episode),
        quality = choices.quality,
        maxBitrateMbps = choices.maxBitrateMbps,
        audioLanguage = choices.audioLanguage,
        replacesBadSource = true,
        skipSources = skip,
        maxSources = (MaxAutoSourceAttempts - attemptsUsed).coerceAtLeast(0),
    )
}

/** App wiring for [AutoSourceReplacer]: the real downloads, season downloads, network and connection hold. */
internal object SourceReplacements {
    /** Wait after the app starts, so that startup and playback come first (the same wait as the other background passes). */
    private const val StartDelayMs = DownloadCompanions.BackfillStartDelayMs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val canSearch: StateFlow<Boolean> by lazy {
        combine(NetworkStatusRepository.uiState, LimitedConnections.hold.state) { network, hold ->
            network.isOnline && !hold.holding
        }.stateIn(scope, SharingStarted.Eagerly, false)
    }

    private val replacer: AutoSourceReplacer by lazy {
        AutoSourceReplacer(
            downloads = DownloadsRepository.uiState,
            busyTitles = SeasonBatchDownloads.titlesInFlight,
            canSearch = canSearch,
            isOnline = { NetworkStatusRepository.uiState.value.isOnline },
            isAway = DownloadsRepository::isBusyOrAway,
            startSearch = { step ->
                val request = replacementRequest(
                    bad = step.bad,
                    skip = step.skip,
                    attemptsUsed = step.attemptsUsed,
                    choices = SeasonDownloadDefaultsRepository.load(ProfileRepository.activeProfileId),
                    runtimeMinutes = runtimeMinutesOfDownload(step.bad),
                )
                SeasonBatchDownloads.startReplacement(request) == SeasonBatchStart.Started
            },
            giveUp = DownloadsRepository::markNoWorkingSourceFound,
        )
    }

    /** The searches running now, by title, for the Downloads screen. */
    val progress: StateFlow<Map<String, SourceSearchProgress>> get() = replacer.progress

    /**
     * Waits a little after the app starts, then looks for another source for every title whose source
     * was bad, the ones already in that state when the app started too, and keeps doing so as long as
     * it runs. Runs until cancelled.
     */
    suspend fun watch() {
        delay(StartDelayMs)
        DownloadsRepository.ensureLoaded()
        replacer.watch()
    }

    fun onSourceTried(titleKey: String, attempt: Int) = replacer.onSourceTried(titleKey, attempt)

    fun onSearchFinished(request: SeasonBatchRequest, summary: SeasonBatchSummary) {
        summary.results.forEach { (episode, result) -> replacer.onSearchFinished(request.titleKey(episode), result) }
    }
}
