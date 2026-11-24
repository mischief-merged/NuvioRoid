package com.nuvio.app.features.watchprogress

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class PlaybackClearTarget(
    val profileId: Int,
    val parentMetaIds: Set<String>,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val videoId: String?,
    val onlyNonEpisode: Boolean,
)

internal data class RecordedPlaybackClear(
    val epoch: Long,
    val target: PlaybackClearTarget,
)

/**
 * Monotonic stamps and live observers for explicit unwatch and progress-clear actions, so a player
 * session that paused or ended before the user cleared that item never writes its stale snapshot
 * back on exit, even if many other items are cleared before the player closes.
 */
internal object PlaybackClearEpochs {
    internal const val MaxRecentClears = 64
    private val nextEpoch = atomic(0L)
    private val lock = SynchronizedObject()
    private val recentClears = LinkedHashMap<PlaybackClearTarget, Long>()
    private val activeObservers = LinkedHashSet<(RecordedPlaybackClear) -> Unit>()

    fun currentEpoch(): Long = nextEpoch.value

    fun registerObserver(observer: (RecordedPlaybackClear) -> Unit): () -> Unit {
        synchronized(lock) {
            activeObservers.add(observer)
        }
        return {
            synchronized(lock) {
                activeObservers.remove(observer)
            }
        }
    }

    internal fun activeObserverCount(): Int = synchronized(lock) { activeObservers.size }

    fun recordClear(
        profileId: Int,
        parentMetaIds: Collection<String>,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
        onlyNonEpisode: Boolean = false,
    ) {
        val normalizedVideoId = videoId?.trim()?.takeIf { it.isNotEmpty() }
        val parsedVideoEpisode = if (!onlyNonEpisode && normalizedVideoId != null) {
            parseEpisodeFromPlaybackVideoId(normalizedVideoId, parentMetaId = null)
        } else {
            null
        }
        val effectiveSeason = seasonNumber ?: parsedVideoEpisode?.second
        val effectiveEpisode = episodeNumber ?: parsedVideoEpisode?.third
        val normalizedParents = buildSet {
            parentMetaIds.forEach { raw ->
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) add(trimmed)
            }
            if (parsedVideoEpisode != null &&
                (effectiveSeason == null || parsedVideoEpisode.second == effectiveSeason) &&
                (effectiveEpisode == null || parsedVideoEpisode.third == effectiveEpisode)
            ) {
                add(parsedVideoEpisode.first)
            }
        }
        if (normalizedParents.isEmpty() && normalizedVideoId == null) return
        val target = PlaybackClearTarget(
            profileId = profileId,
            parentMetaIds = normalizedParents,
            seasonNumber = effectiveSeason,
            episodeNumber = effectiveEpisode,
            videoId = normalizedVideoId,
            onlyNonEpisode = onlyNonEpisode,
        )
        val (recorded, observers) = synchronized(lock) {
            val epoch = nextEpoch.incrementAndGet()
            recentClears.remove(target)
            recentClears[target] = epoch
            while (recentClears.size > MaxRecentClears) {
                val iterator = recentClears.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
            RecordedPlaybackClear(epoch = epoch, target = target) to activeObservers.toList()
        }
        observers.forEach { observer -> observer(recorded) }
    }

    fun matchesSession(
        record: RecordedPlaybackClear,
        session: WatchProgressPlaybackSession,
    ): Boolean {
        if (record.target.profileId != session.profileId) return false
        val sessionVideoId = session.videoId.trim()
        val sessionParents = resolveSessionParents(session, sessionVideoId)
        return record.target.matches(
            sessionParents = sessionParents,
            sessionSeason = session.seasonNumber,
            sessionEpisode = session.episodeNumber,
            sessionVideoId = sessionVideoId,
        )
    }

    fun latestMatchingEpoch(
        session: WatchProgressPlaybackSession,
        afterEpoch: Long,
    ): Long {
        if (nextEpoch.value <= afterEpoch) return 0L
        val sessionVideoId = session.videoId.trim()
        val sessionParents = resolveSessionParents(session, sessionVideoId)
        return synchronized(lock) {
            var matched = 0L
            for ((target, epoch) in recentClears.entries) {
                if (epoch <= afterEpoch || epoch <= matched) continue
                if (target.profileId != session.profileId) continue
                if (target.matches(sessionParents, session.seasonNumber, session.episodeNumber, sessionVideoId)) {
                    matched = epoch
                }
            }
            matched
        }
    }

    fun wasClearedAfter(
        session: WatchProgressPlaybackSession,
        afterEpoch: Long,
    ): Boolean = latestMatchingEpoch(session = session, afterEpoch = afterEpoch) > afterEpoch

    private fun resolveSessionParents(
        session: WatchProgressPlaybackSession,
        sessionVideoId: String,
    ): Set<String> = buildSet {
        val trimmedParent = session.parentMetaId.trim()
        if (trimmedParent.isNotEmpty()) {
            add(trimmedParent)
            WatchProgressRepository.normalizedParentContentId(trimmedParent, sessionVideoId)
                ?.let(::add)
        }
        if (sessionVideoId.isNotEmpty()) {
            parseEpisodeFromPlaybackVideoId(sessionVideoId, parentMetaId = null)
                ?.let { (vidParent, vidSeason, vidEpisode) ->
                    if ((session.seasonNumber == null || vidSeason == session.seasonNumber) &&
                        (session.episodeNumber == null || vidEpisode == session.episodeNumber)
                    ) {
                        add(vidParent)
                    }
                }
        }
    }

    private fun PlaybackClearTarget.matches(
        sessionParents: Set<String>,
        sessionSeason: Int?,
        sessionEpisode: Int?,
        sessionVideoId: String,
    ): Boolean = when {
        onlyNonEpisode ->
            parentMetaIds.isNotEmpty() &&
                sessionParents.any { it in parentMetaIds } &&
                sessionSeason == null &&
                sessionEpisode == null
        seasonNumber != null && episodeNumber != null ->
            (parentMetaIds.isNotEmpty() &&
                sessionParents.any { it in parentMetaIds } &&
                sessionSeason == seasonNumber &&
                sessionEpisode == episodeNumber) ||
                (videoId != null && sessionVideoId.isNotEmpty() && sessionVideoId == videoId)
        videoId != null ->
            sessionVideoId.isNotEmpty() &&
                sessionVideoId == videoId &&
                (parentMetaIds.isEmpty() || sessionParents.any { it in parentMetaIds })
        else ->
            parentMetaIds.isNotEmpty() && sessionParents.any { it in parentMetaIds }
    }
}
