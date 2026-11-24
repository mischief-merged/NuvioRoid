package com.nuvio.app.features.watchprogress

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private data class RecordedPlaybackClear(
    val epoch: Long,
    val profileId: Int,
    val parentMetaIds: Set<String>,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val videoId: String?,
    val onlyNonEpisode: Boolean,
)

/**
 * Monotonic stamps for explicit unwatch and progress-clear actions, so a player session that
 * paused or ended before the user cleared that item does not write its stale snapshot back on exit.
 */
internal object PlaybackClearEpochs {
    private const val MaxRecentClears = 64
    private val nextEpoch = atomic(0L)
    private val lock = SynchronizedObject()
    private val recentClears = ArrayDeque<RecordedPlaybackClear>()

    fun currentEpoch(): Long = nextEpoch.value

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
        synchronized(lock) {
            val epoch = nextEpoch.incrementAndGet()
            recentClears.addLast(
                RecordedPlaybackClear(
                    epoch = epoch,
                    profileId = profileId,
                    parentMetaIds = normalizedParents,
                    seasonNumber = effectiveSeason,
                    episodeNumber = effectiveEpisode,
                    videoId = normalizedVideoId,
                    onlyNonEpisode = onlyNonEpisode,
                ),
            )
            while (recentClears.size > MaxRecentClears) {
                recentClears.removeFirst()
            }
        }
    }

    fun wasClearedAfter(
        session: WatchProgressPlaybackSession,
        afterEpoch: Long,
    ): Boolean {
        if (nextEpoch.value <= afterEpoch) return false
        val sessionVideoId = session.videoId.trim()
        val sessionParents = buildSet {
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
        return synchronized(lock) {
            for (index in recentClears.indices.reversed()) {
                val record = recentClears[index]
                if (record.epoch <= afterEpoch) break
                if (record.profileId != session.profileId) continue
                if (record.matches(sessionParents, session.seasonNumber, session.episodeNumber, sessionVideoId)) {
                    return@synchronized true
                }
            }
            false
        }
    }

    private fun RecordedPlaybackClear.matches(
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
