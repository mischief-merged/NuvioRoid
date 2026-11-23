package com.nuvio.app.features.downloads

import com.nuvio.app.core.memory.BoundedCache
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the player and the offline pages need to offer the next episode, and to show an episode
 * and its show, when the details of the title are not in memory and cannot be fetched.
 *
 * It is saved beside the video, in the sibling folder `<video file name>.subtitles/`, as
 * `episodes.json` (see [SkipTimesSidecar] for the rule that folder follows). It holds the show's
 * name and ids, the episodes of the season of that download and the first episode of the next
 * season, with title, video id, air date, thumbnail address and a short overview. Nothing is
 * invented: only what the title's details said when it was downloaded. A later save adds to the
 * list and updates entries, and never removes an episode that was saved.
 */
internal object EpisodeListSidecar {
    const val FILE_NAME = "episodes.json"
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(localVideoUri: String): SavedEpisodes? {
        if (!localVideoUri.startsWith("file:")) return null
        val text = runCatching { DownloadSubtitleStorage(localVideoUri).read(FILE_NAME) }.getOrNull() ?: return null
        return decode(text)
    }

    /** False when nothing was written. A file of a newer version is never replaced. */
    fun write(localVideoUri: String, episodes: SavedEpisodes): Boolean {
        if (!localVideoUri.startsWith("file:")) return false
        val existing = runCatching { DownloadSubtitleStorage(localVideoUri).read(FILE_NAME) }.getOrNull()
        if (existing != null && isNewerVersion(existing)) return false
        val body = json.encodeToString(SavedEpisodes.serializer(), episodes.copy(version = VERSION))
        return runCatching { DownloadSubtitleStorage(localVideoUri).write(FILE_NAME, body) }.isSuccess
    }

    internal fun decode(text: String): SavedEpisodes? = try {
        val root = json.parseToJsonElement(text) as? JsonObject
        val version = root?.get("version")?.jsonPrimitive?.int
        if (root == null || version != VERSION) null
        else json.decodeFromJsonElement<SavedEpisodes>(root).takeIf { it.fetchedAtEpochMs > 0L && it.parentMetaId.isNotBlank() }
    } catch (error: Exception) {
        null
    }

    private fun isNewerVersion(text: String): Boolean = try {
        ((json.parseToJsonElement(text) as? JsonObject)?.get("version")?.jsonPrimitive?.int ?: 0) > VERSION
    } catch (error: Exception) {
        false
    }
}

@Serializable
internal data class SavedEpisodes(
    val version: Int = EpisodeListSidecar.VERSION,
    val fetchedAtEpochMs: Long,
    val showName: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val imdbId: String? = null,
    val poster: String? = null,
    val episodes: List<SavedEpisode> = emptyList(),
) {
    fun toMetaVideos(): List<MetaVideo> = episodes.map {
        MetaVideo(
            id = it.id, title = it.title, released = it.released, thumbnail = it.thumbnail,
            season = it.season, episode = it.episode, overview = it.overview, runtime = it.runtime,
        )
    }
}

@Serializable
internal data class SavedEpisode(
    val season: Int,
    val episode: Int,
    val id: String,
    val title: String,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val runtime: Int? = null,
)

/** The episodes to keep for a download of [season]: that season, and the first episode of the next one that has any. */
internal fun MetaDetails.savedEpisodesFor(season: Int, fetchedAtEpochMs: Long): SavedEpisodes? {
    val known = videos.filter { it.season != null && it.episode != null && it.id.isNotBlank() }
    val thisSeason = known.filter { it.season == season }
    if (thisSeason.isEmpty()) return null
    val nextSeason = known.mapNotNull { it.season }.filter { it > season }.minOrNull()
    val firstOfNext = nextSeason?.let { next -> known.filter { it.season == next }.minByOrNull { it.episode!! } }
    return SavedEpisodes(
        fetchedAtEpochMs = fetchedAtEpochMs,
        showName = name,
        parentMetaId = id,
        parentMetaType = type,
        imdbId = imdbId,
        poster = poster,
        episodes = (thisSeason + listOfNotNull(firstOfNext)).sortedWith(compareBy({ it.season }, { it.episode })).map {
            SavedEpisode(
                season = it.season!!, episode = it.episode!!, id = it.id, title = it.title, released = it.released,
                thumbnail = it.thumbnail, overview = it.overview?.take(MaxOverviewChars), runtime = it.runtime,
            )
        },
    )
}

/** Adds to what is saved: entries of [fresh] win, and no saved episode is dropped. */
internal fun SavedEpisodes?.mergedWith(fresh: SavedEpisodes): SavedEpisodes {
    if (this == null) return fresh
    val byKey = LinkedHashMap<Pair<Int, Int>, SavedEpisode>()
    episodes.forEach { byKey[it.season to it.episode] = it }
    fresh.episodes.forEach { byKey[it.season to it.episode] = it }
    return fresh.copy(episodes = byKey.values.sortedWith(compareBy({ it.season }, { it.episode })))
}

private const val MaxOverviewChars = 600

/** Saves the episode list of a series download. */
internal class EpisodeListCompanion(
    private val fetchMeta: suspend (type: String, id: String) -> MetaDetails? = { type, id ->
        MetaDetailsRepository.fetch(type, id, cacheResult = false)
    },
    private val nowEpochMs: () -> Long = { DownloadsClock.nowEpochMs() },
) : DownloadCompanion {
    override val name: String get() = "episode list"

    private val cacheLock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val shows = BoundedCache<String, Pair<Long, MetaDetails>>()
    private val showLoads = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<MetaDetails?>>()

    /**
     * The details of a show, loaded once for all its episodes: a show loaded within [ShowCacheMs] is
     * reused, and two episodes asking together share one load. A load that failed is not remembered.
     */
    private suspend fun detailsOf(type: String, id: String): MetaDetails? {
        val key = "$type:$id"
        var owner = false
        val pending = kotlinx.atomicfu.locks.synchronized(cacheLock) {
            shows[key]?.takeIf { (at, _) -> nowEpochMs() - at in 0 until ShowCacheMs }?.second?.let { return it }
            showLoads[key] ?: kotlinx.coroutines.CompletableDeferred<MetaDetails?>().also {
                showLoads[key] = it
                owner = true
            }
        }
        if (!owner) return pending.await()
        return try {
            val loaded = withTimeoutOrNull(LookupTimeoutMs) { fetchMeta(type, id) }
            if (loaded != null) shows[key] = nowEpochMs() to loaded
            pending.complete(loaded)
            loaded
        } catch (error: CancellationException) {
            pending.cancel(error)
            throw error
        } catch (_: Exception) {
            pending.complete(null)
            null
        } finally {
            kotlinx.atomicfu.locks.synchronized(cacheLock) { showLoads.remove(key) }
        }
    }

    override fun isDue(item: DownloadItem, localVideoUri: String): Boolean {
        if (!item.isEpisode) return false
        val saved = EpisodeListSidecar.read(localVideoUri) ?: return true
        val age = nowEpochMs() - saved.fetchedAtEpochMs
        return age < 0 || age >= RefreshAgeMs
    }

    override suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome {
        val season = item.seasonNumber ?: return CompanionOutcome.NothingFound
        val meta = detailsOf(item.parentMetaType, item.parentMetaId) ?: return CompanionOutcome.Failed
        val fresh = meta.savedEpisodesFor(season, nowEpochMs()) ?: return CompanionOutcome.NothingFound
        return SidecarLocks.withLock(localVideoUri) {
            if (!DownloadCompanionGuard.stillWanted(localVideoUri)) return@withLock CompanionOutcome.Busy
            val merged = EpisodeListSidecar.read(localVideoUri).mergedWith(fresh)
            if (EpisodeListSidecar.write(localVideoUri, merged)) CompanionOutcome.Saved else CompanionOutcome.Failed
        }
    }

    companion object {
        /** The list is fetched again, when a pass looks at the download, after this long. */
        const val RefreshAgeMs = 14L * 24 * 60 * 60 * 1000

        /** The details of a show are reused for its other episodes for this long. */
        const val ShowCacheMs = 10L * 60 * 1000

        /** A lookup that takes longer than this is given up. */
        const val LookupTimeoutMs = 25_000L
    }
}

/**
 * The episode list to use when the title's details are missing and the file is a finished
 * download: what is saved beside it, or null. Details that are available always win; the caller
 * uses this only when it has none.
 */
internal fun savedEpisodeVideosFor(localVideoUri: String?): List<MetaVideo> =
    localVideoUri?.let { EpisodeListSidecar.read(it) }?.toMetaVideos().orEmpty()
