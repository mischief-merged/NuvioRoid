package com.nuvio.app.features.downloads

import com.nuvio.app.core.memory.BoundedCache
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntervalLookup
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.SkipLookupReport
import com.nuvio.app.features.player.skip.resolveSkipIntervalLookup
import com.nuvio.app.features.player.skip.segmentCategory
import com.nuvio.app.features.simkl.SimklConfig
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What to look up. Taken from a download, or from the title being played, and the same either way. */
internal data class SkipTimesRequest(
    val isMovie: Boolean,
    val parentMetaId: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    /** An IMDb id known from the title's details, used when the video id is an anime id. */
    val imdbId: String? = null,
)

internal fun DownloadItem.toSkipTimesRequest() = SkipTimesRequest(
    isMovie = contentType.ifBlank { parentMetaType }.equals("movie", ignoreCase = true),
    parentMetaId = parentMetaId,
    videoId = videoId,
    season = seasonNumber,
    episode = episodeNumber,
    imdbId = parentMetaId.takeIf { it.startsWith("tt") },
)

internal sealed interface SkipFetchResult {
    /**
     * The services returned these. When [complete] is false a service did not answer, so more may
     * exist: the result may add what is not saved yet but must not replace or remove what is.
     */
    data class Found(val intervals: List<SkipInterval>, val complete: Boolean = true) : SkipFetchResult

    /** The services answered and have nothing for this title. */
    data object NoneFound : SkipFetchResult

    /** The services could not be asked or did not answer. Says nothing about the title. */
    data object Failed : SkipFetchResult
}

internal fun interface SkipTimesFetcher {
    suspend fun fetch(request: SkipTimesRequest): SkipFetchResult

    /**
     * Whether any service could be asked about this title at all in this build and these settings.
     * When none can, nothing is looked up, nothing is recorded, and it is no failure.
     */
    fun canLookUp(request: SkipTimesRequest): Boolean = true
}

/**
 * Which lookups can be made with the services that are set up. The main service (IntroDB) needs its
 * address set when the app is built; Simkl, which turns an IMDb or Kitsu id into the ids the anime
 * services use, needs a client id set when the app is built; AniSkip is open and needs only a MAL id.
 */
internal fun skipLookupPossible(request: SkipTimesRequest, introDbConfigured: Boolean, simklConfigured: Boolean): Boolean {
    if (request.isMovie) return introDbConfigured
    return when (resolveSkipIntervalLookup(request.videoId, request.season, request.episode)) {
        null -> false
        is SkipIntervalLookup.Imdb -> introDbConfigured || simklConfigured
        is SkipIntervalLookup.Mal -> true
        is SkipIntervalLookup.Kitsu -> simklConfigured || (introDbConfigured && request.imdbId != null)
    }
}

/**
 * Asks the same services as playback does, whether or not "skip intro" is switched on, bypassing
 * the in-memory cache so that a changed answer is seen, and telling an unreachable service from one
 * that has nothing.
 */
internal object ServiceSkipTimesFetcher : SkipTimesFetcher {
    /** A lookup that takes longer than this is given up and counts as failed. */
    const val LOOKUP_TIMEOUT_MS = 25_000L

    override fun canLookUp(request: SkipTimesRequest): Boolean =
        skipLookupPossible(request, SkipIntroRepository.introDbConfigured, SimklConfig.CLIENT_ID.isNotBlank())

    override suspend fun fetch(request: SkipTimesRequest): SkipFetchResult {
        // With no service to ask, nothing was asked, so "none found" would be untrue.
        if (!canLookUp(request)) return SkipFetchResult.Failed
        val report = SkipLookupReport()
        val intervals = try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { withContext(report) { lookup(request) } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        } ?: return SkipFetchResult.Failed
        return when {
            intervals.isNotEmpty() -> SkipFetchResult.Found(
                intervals,
                complete = !report.hadUnansweredRequest && !NetworkStatusRepository.uiState.value.isOfflineLike,
            )
            report.hadUnansweredRequest || NetworkStatusRepository.uiState.value.isOfflineLike -> SkipFetchResult.Failed
            else -> SkipFetchResult.NoneFound
        }
    }

    private suspend fun lookup(request: SkipTimesRequest): List<SkipInterval> {
        if (request.isMovie) {
            return SkipIntroRepository.getMovieSkipIntervals(
                contentId = request.parentMetaId,
                videoId = request.videoId,
                requireSkipIntroEnabled = false,
                useCache = false,
            )
        }
        val lookup = resolveSkipIntervalLookup(request.videoId, request.season, request.episode) ?: return emptyList()
        return when (lookup) {
            is SkipIntervalLookup.Imdb -> SkipIntroRepository.getSkipIntervals(
                imdbId = lookup.imdbId,
                season = lookup.season,
                episode = lookup.episode,
                requireSkipIntroEnabled = false,
                useCache = false,
            )
            is SkipIntervalLookup.Mal -> SkipIntroRepository.getSkipIntervalsForMal(
                malId = lookup.malId,
                episode = lookup.episode,
                requireSkipIntroEnabled = false,
                imdbId = request.imdbId,
                imdbSeason = request.season,
                imdbEpisode = request.episode ?: lookup.episode,
                useCache = false,
            )
            is SkipIntervalLookup.Kitsu -> SkipIntroRepository.getSkipIntervalsForKitsu(
                kitsuId = lookup.kitsuId,
                episode = lookup.episode,
                requireSkipIntroEnabled = false,
                imdbId = request.imdbId,
                imdbSeason = request.season,
                imdbEpisode = request.episode ?: lookup.episode,
                useCache = false,
            )
        }
    }
}

/** What a finished download's sidecar says, in words for a row. */
internal enum class SkipTimesNote { Saved, NoneFound }

/**
 * Saves, uses and refreshes the skip times of downloaded titles.
 *
 * What is saved is in [SkipTimesSidecar]. How it combines with the rest, at play time:
 * - Saved intervals are used at once, with no network, in the place the online intervals take.
 * - Chapters in the file keep their priority, through the same merge as for online intervals:
 *   a chapter wins for a kind of segment it covers, and saved intervals fill the kinds it does not.
 * - Online, the services are asked again in the background, at most once per [FoundRefreshAgeMs]
 *   for a title that has intervals and once per [NoneFoundRecheckAgeMs] for one that had none.
 *   A different answer replaces the saved intervals. An answer with nothing, or no answer, never
 *   removes saved intervals: an empty answer cannot be told from a service that is down.
 */
internal class OfflineSkipTimes(
    private val fetcher: SkipTimesFetcher = ServiceSkipTimesFetcher,
    private val isOnline: () -> Boolean = { NetworkStatusRepository.uiState.value.isOnline },
    private val nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
    private val io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) : DownloadCompanion {
    private val lock = SynchronizedObject()
    private val running = mutableSetOf<String>()
    private val failedAt = BoundedCache<String, Long>()

    override val name: String get() = "skip times"

    /** Backfill and the end of a download fill gaps. A download that has intervals is left to be refreshed when played. */
    override fun isDue(item: DownloadItem, localVideoUri: String): Boolean =
        fetcher.canLookUp(item.toSkipTimesRequest()) && needsBackfill(SkipTimesSidecar.read(localVideoUri))

    override suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome =
        lookup(item.toSkipTimesRequest(), localVideoUri) { needsBackfill(it) }.outcome

    /** The saved intervals of a file, at once and without the network. Empty when none are saved. */
    fun saved(localVideoUri: String): List<SkipInterval> =
        (SkipTimesSidecar.read(localVideoUri) as? SavedSkipTimesRead.Saved)?.times?.intervals.orEmpty()

    /** For a row of the list of downloads: null when nothing was looked up yet. */
    fun note(localVideoUri: String): SkipTimesNote? =
        (SkipTimesSidecar.read(localVideoUri) as? SavedSkipTimesRead.Saved)?.times?.let {
            if (it.noneFound) SkipTimesNote.NoneFound else SkipTimesNote.Saved
        }

    /**
     * Asks the services if the saved answer is due for it (always when there is none), and saves
     * what they say. Returns the intervals when they now differ from what was saved, else null.
     * Does nothing without a connection, and never removes or shrinks a saved answer on failure,
     * on an empty answer or on an incomplete one.
     */
    suspend fun refresh(request: SkipTimesRequest, localVideoUri: String): List<SkipInterval>? =
        lookup(request, localVideoUri) { isDue(it) }.changed

    private class Attempt(val outcome: CompanionOutcome, val changed: List<SkipInterval>? = null)

    /** Every read and write of the sidecar happens on the IO dispatcher, whoever asks. */
    private suspend fun lookup(request: SkipTimesRequest, uri: String, eligible: (SavedSkipTimesRead) -> Boolean): Attempt =
        withContext(io) { lookupOnIo(request, uri, eligible) }

    private suspend fun lookupOnIo(request: SkipTimesRequest, uri: String, eligible: (SavedSkipTimesRead) -> Boolean): Attempt {
        if (!fetcher.canLookUp(request)) return Attempt(CompanionOutcome.Busy)
        if (!isOnline()) return Attempt(CompanionOutcome.Failed)
        if (!eligible(SkipTimesSidecar.read(uri))) return Attempt(CompanionOutcome.Busy)
        if (inCooldown(uri)) return Attempt(CompanionOutcome.Busy)
        if (!claim(uri)) return Attempt(CompanionOutcome.Busy)
        try {
            val result = fetcher.fetch(request)
            // Read again under the lock: playback or a background pass may have saved something while the services were asked.
            return SidecarLocks.withLock(uri) {
                val existing = SkipTimesSidecar.read(uri)
                if (!DownloadCompanionGuard.stillWanted(uri) || !eligible(existing)) return@withLock Attempt(CompanionOutcome.Busy)
                val before = (existing as? SavedSkipTimesRead.Saved)?.times
                val merged = mergeSkipLookup(before, result, nowEpochMs())
                val incomplete = result is SkipFetchResult.Found && !result.complete
                if (result == SkipFetchResult.Failed || incomplete) markFailed(uri)
                if (merged == null) return@withLock Attempt(if (result == SkipFetchResult.Failed) CompanionOutcome.Failed else CompanionOutcome.NothingFound)
                if (!SkipTimesSidecar.write(uri, merged)) return@withLock Attempt(CompanionOutcome.Failed)
                val changed = merged.intervals.takeIf { it.isNotEmpty() && it != before?.intervals }
                Attempt(
                    when {
                        merged.noneFound -> CompanionOutcome.NothingFound
                        changed != null -> CompanionOutcome.Saved
                        else -> CompanionOutcome.NothingFound
                    },
                    changed,
                )
            }
        } finally {
            release(uri)
        }
    }

    private fun inCooldown(uri: String): Boolean = synchronized(lock) {
        val at = failedAt[uri] ?: return false
        val age = nowEpochMs() - at
        (age in 0 until FailureCooldownMs).also { if (!it) failedAt.remove(uri) }
    }

    private fun markFailed(uri: String) = synchronized(lock) { failedAt[uri] = nowEpochMs() }

    private fun claim(uri: String): Boolean = synchronized(lock) { running.add(uri) }

    private fun release(uri: String) = synchronized(lock) { running.remove(uri) }

    /** Whether the services should be asked again, as at play time. */
    internal fun isDue(read: SavedSkipTimesRead): Boolean = when (read) {
        SavedSkipTimesRead.Missing, SavedSkipTimesRead.Unusable -> true
        SavedSkipTimesRead.NewerVersion -> false
        is SavedSkipTimesRead.Saved -> {
            val age = nowEpochMs() - read.times.fetchedAtEpochMs
            // A time in the future (a clock that was set back) must not stop rechecks for good.
            age < 0 || age >= when {
                read.times.noneFound -> NoneFoundRecheckAgeMs
                !read.times.complete -> IncompleteRecheckAgeMs
                else -> FoundRefreshAgeMs
            }
        }
    }

    /** Backfill fills the gaps. A download that has intervals is left to be refreshed when played. */
    private fun needsBackfill(read: SavedSkipTimesRead): Boolean = when (read) {
        SavedSkipTimesRead.Missing, SavedSkipTimesRead.Unusable -> true
        SavedSkipTimesRead.NewerVersion -> false
        is SavedSkipTimesRead.Saved -> (read.times.noneFound || !read.times.complete) && isDue(read)
    }

    companion object {
        /** A title that had no skip times is looked up again only after this long: the databases fill in over time. */
        const val NoneFoundRecheckAgeMs = 14L * 24 * 60 * 60 * 1000

        /** A title with saved skip times is looked up again when played, if the answer is older than this. */
        const val FoundRefreshAgeMs = 24L * 60 * 60 * 1000

        /** Saved intervals that only some of the services answered for are looked up again after this long. */
        const val IncompleteRecheckAgeMs = 60L * 60 * 1000

        /** After a lookup for a file failed or was incomplete, it is not tried again for this long, whatever asks. */
        const val FailureCooldownMs = 15L * 60 * 1000

        /** The one the app uses. */
        val app: OfflineSkipTimes by lazy { OfflineSkipTimes() }
    }
}

/**
 * What to save after a lookup, or null for nothing. Only a complete answer may replace what is
 * saved. An empty answer never overwrites intervals, because it cannot be told from a service that
 * is down. An incomplete answer may add the kinds of segment that are not saved yet.
 */
internal fun mergeSkipLookup(saved: SavedSkipTimes?, result: SkipFetchResult, nowEpochMs: Long): SavedSkipTimes? = when (result) {
    SkipFetchResult.Failed -> null
    SkipFetchResult.NoneFound ->
        if (saved == null || saved.noneFound) SavedSkipTimes(nowEpochMs, emptyList()) else saved.copy(fetchedAtEpochMs = nowEpochMs)
    is SkipFetchResult.Found ->
        if (result.complete) {
            SavedSkipTimes(nowEpochMs, result.intervals)
        } else {
            val have = saved?.intervals.orEmpty().mapNotNull { segmentCategory(it.type) }.toSet()
            val added = result.intervals.filter { segmentCategory(it.type)?.let { c -> c !in have } == true }
            if (added.isEmpty()) null else SavedSkipTimes(saved?.fetchedAtEpochMs ?: nowEpochMs, saved?.intervals.orEmpty() + added, complete = false)
        }
}
