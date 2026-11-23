package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_failed
import nuvio.composeapp.generated.resources.downloads_movie_already_downloaded
import nuvio.composeapp.generated.resources.downloads_movie_not_found
import nuvio.composeapp.generated.resources.downloads_movies_finished
import nuvio.composeapp.generated.resources.downloads_movies_stopped
import nuvio.composeapp.generated.resources.downloads_season_count_existing
import nuvio.composeapp.generated.resources.downloads_season_count_failed
import nuvio.composeapp.generated.resources.downloads_season_count_new
import nuvio.composeapp.generated.resources.downloads_season_count_not_found
import nuvio.composeapp.generated.resources.downloads_season_count_paused
import nuvio.composeapp.generated.resources.downloads_season_finished
import nuvio.composeapp.generated.resources.downloads_season_stopped
import nuvio.composeapp.generated.resources.downloads_season_summary
import org.jetbrains.compose.resources.getString

/**
 * App wiring for [SeasonBatchDownloader]: installed addons supply the sources, the existing
 * [DownloadsRepository] queue does the downloading.
 *
 * Plugin scrapers and native server sources are not used for batches yet.
 */
internal object SeasonBatchDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val downloader = SeasonBatchDownloader(
        scope = scope,
        downloads = DownloadsRepository.uiState,
        // Embedded streams are what the app itself uses first; installed addons are asked when they
        // give nothing usable for the chosen quality.
        fetchStreams = { episode -> MetaDetailsRepository.findEmbeddedStreams(episode.videoId) },
        fetchFallbackStreams = ::fetchAddonStreams,
        prepareStream = ::prepareStream,
        enqueue = { request, episode, stream -> enqueueEpisode(request, episode, stream) },
        onFinished = { request, summary ->
            if (request.replacesBadSource) {
                SourceReplacements.onSearchFinished(request, summary)
            } else if (request.autoDownloadRuleKey == null) {
                summaryToast(request, summary)?.let { NuvioToastController.show(it) }
                // The toast goes away; the last run stays on the Downloads screen until dismissed.
                summary.toRunRecord(request, DownloadsClock.nowEpochMs())
                    ?.let { BatchRunSummaries.record(ProfileRepository.activeProfileId, it) }
            } else {
                AutoDownloads.onBatchFinished(request, summary)
            }
        },
        discard = ::discardSource,
        verifyCompleted = { item -> DownloadsRepository.verifyCompletedDownload(item.id) },
        inspect = DownloadsRepository::inspectCompletedDownload,
        preferAudioLanguage = { item, language -> DownloadsRepository.setPreferredAudioLanguage(item.id, language) },
        onSourceTried = { request, episode, attempt ->
            if (request.replacesBadSource) SourceReplacements.onSourceTried(request.titleKey(episode), attempt)
        },
    )

    internal fun discardSource(item: DownloadItem) = DownloadsRepository.rejectBatchSource(item.id)

    /** The titles a running season download, or a search for another source, is working on. */
    val titlesInFlight: StateFlow<Set<String>> get() = downloader.titlesInFlight

    val running: StateFlow<Map<String, SeasonBatchProgress>> get() = downloader.running

    fun start(request: SeasonBatchRequest): SeasonBatchStart {
        DownloadsRepository.ensureLoaded()
        downloader.adoptRunning(DownloadsRepository.takeDownloadsResumedAtLoad())
        return downloader.start(request)
    }

    fun cancel(parentMetaId: String, seasonNumber: Int) = downloader.cancel(parentMetaId, seasonNumber)

    /** Starts the app's own search for another source for one title ([SeasonBatchRequest.replacesBadSource]). */
    internal fun startReplacement(request: SeasonBatchRequest): SeasonBatchStart = start(request)

    /**
     * Queues one item of a batch: an episode of the request's show, or a movie of its own. A source
     * that an automatic download may not use because of its address is reported through
     * [onPrivateSourceSkipped], apart from the refusal itself, so the rule can say why it has
     * nothing.
     */
    internal fun enqueueEpisode(
        request: SeasonBatchRequest,
        episode: SeasonBatchEpisode,
        stream: StreamItem,
        onPrivateSourceSkipped: (SeasonBatchRequest, SeasonBatchEpisode) -> Unit = AutoDownloads::onPrivateSourceSkipped,
    ): DownloadEnqueueResult {
        if (request.autoDownloadRuleKey != null && stream.playableDirectUrl?.hasPublicDownloadHost() == false) {
            onPrivateSourceSkipped(request, episode)
        }
        val movie = episode.movie
        val result = DownloadsRepository.enqueueFromStream(
            contentType = movie?.metaType ?: request.contentType,
            videoId = episode.videoId,
            parentMetaId = movie?.metaId ?: request.parentMetaId,
            parentMetaType = movie?.metaType ?: request.parentMetaType,
            title = movie?.title ?: request.title,
            logo = if (movie != null) movie.logo else request.logo,
            poster = if (movie != null) movie.poster else request.poster,
            background = if (movie != null) movie.background else request.background,
            seasonNumber = episode.seasonNumber.takeIf { movie == null },
            episodeNumber = episode.episodeNumber.takeIf { movie == null },
            episodeTitle = episode.title,
            episodeThumbnail = episode.thumbnail,
            stream = stream,
            autoDownloadRuleKey = request.autoDownloadRuleKey,
        )
        // A single movie has no summary worth waiting for: the user is told as soon as it is queued.
        val queued = result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced
        if (queued && request.isSingleMovie && !request.replacesBadSource) NuvioToastController.show(result.toastMessage())
        return result
    }

    private suspend fun fetchAddonStreams(episode: SeasonBatchEpisode): List<StreamItem> {
        val type = episode.movie?.metaType ?: "series"
        val streamId = if (episode.movie != null) movieStreamId(episode.videoId) else episode.videoId
        return coroutineScope {
            AddonRepository.uiState.value.addons.enabledAddons()
                .mapNotNull { addon ->
                    val manifest = addon.manifest ?: return@mapNotNull null
                    if (!manifest.supportsStream(type, streamId)) return@mapNotNull null
                    async {
                        runCatchingUnlessCancelled {
                            StreamParser.parse(
                                payload = fetchAddonResponseText(
                                    buildAddonResourceUrl(
                                        manifestUrl = manifest.transportUrl,
                                        resource = "stream",
                                        type = type,
                                        id = streamId,
                                    ),
                                ),
                                addonName = addon.displayTitle.ifBlank { manifest.name },
                                addonId = addon.streamAddonInstanceId(manifest.id),
                                addonLogo = manifest.logoUrl,
                            )
                        }.getOrDefault(emptyList())
                    }
                }
                .awaitAll()
                .flatten()
        }
    }

    /**
     * The id addons are asked with for a movie. The movies of a collection carry a TMDB id, which is
     * turned into the IMDb id addons know. When that is not known the id is used as it is.
     */
    private suspend fun movieStreamId(videoId: String): String {
        val tmdbId = videoId.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.toIntOrNull() ?: return videoId
        return runCatchingUnlessCancelled { TmdbService.tmdbToImdb(tmdbId, "movie") }.getOrNull() ?: videoId
    }

    private suspend fun prepareStream(stream: StreamItem, episode: SeasonBatchEpisode): StreamItem? {
        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = stream,
            season = episode.seasonNumber.takeIf { episode.movie == null },
            episode = episode.episodeNumber.takeIf { episode.movie == null },
        )
        return (resolved as? DirectDebridPlayableResult.Success)?.stream
    }

    /** What to tell the user when a batch has ended, or null when there is nothing to tell. */
    internal suspend fun summaryToast(request: SeasonBatchRequest, summary: SeasonBatchSummary): String? {
        if (!request.isSingleMovie) return summary.toastMessage(movieSet = request.isMovieSet)
        return when (summary.results.values.single()) {
            SeasonBatchEpisodeResult.NoMatchingSource -> getString(Res.string.downloads_movie_not_found)
            SeasonBatchEpisodeResult.Failed -> getString(Res.string.download_failed)
            SeasonBatchEpisodeResult.AlreadyDownloaded -> getString(Res.string.downloads_movie_already_downloaded)
            // Queued and finished, paused or stopped: the Downloads screen shows it.
            SeasonBatchEpisodeResult.Completed,
            SeasonBatchEpisodeResult.Paused,
            SeasonBatchEpisodeResult.Cancelled -> null
        }
    }

    private suspend fun SeasonBatchSummary.toastMessage(movieSet: Boolean): String {
        val heading = getString(
            when {
                movieSet && wasCancelled -> Res.string.downloads_movies_stopped
                movieSet -> Res.string.downloads_movies_finished
                wasCancelled -> Res.string.downloads_season_stopped
                else -> Res.string.downloads_season_finished
            },
        )
        val counts = nonZeroCounts().mapNotNull { (result, count) ->
            val label = when (result) {
                SeasonBatchEpisodeResult.Completed -> Res.string.downloads_season_count_new
                SeasonBatchEpisodeResult.AlreadyDownloaded -> Res.string.downloads_season_count_existing
                SeasonBatchEpisodeResult.NoMatchingSource -> Res.string.downloads_season_count_not_found
                SeasonBatchEpisodeResult.Paused -> Res.string.downloads_season_count_paused
                SeasonBatchEpisodeResult.Failed -> Res.string.downloads_season_count_failed
                SeasonBatchEpisodeResult.Cancelled -> return@mapNotNull null
            }
            getString(label, count)
        }
        return if (counts.isEmpty()) {
            heading
        } else {
            getString(Res.string.downloads_season_summary, heading, counts.joinToString(", "))
        }
    }
}
