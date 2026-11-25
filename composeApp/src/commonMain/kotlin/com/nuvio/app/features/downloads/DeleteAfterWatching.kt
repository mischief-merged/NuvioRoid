package com.nuvio.app.features.downloads

/** What happened to a removal that was asked for or was due. */
internal enum class AutoDownloadRemovalOutcome {
    Removed,

    /** The episode is open in the player. The removal stays scheduled. */
    Playing,

    /** No later episode of the show is known to be released. The removal stays scheduled. */
    LastEpisode,

    /** The file could not be moved out of the downloads folder. The removal stays scheduled. */
    NotMoved,

    /** The file is on a location that is not connected. The removal stays scheduled and nothing is touched. */
    NotReachable,

    /** A move has the item. The removal stays scheduled. */
    Moving,

    /** The removal no longer applies and was dropped. */
    Cancelled,
}

/**
 * Schedules removal only after local playback finishes, with a stored due time that survives sleep
 * and restart. The profile setting controls eligibility; automatic downloads also need their rule's
 * switch. Keep always wins. Rule downloads wait for a later released episode, while manual downloads
 * do not. Every removal uses the same guarded Trash step and records a note for a week.
 */
internal class DeleteAfterWatching(
    private val store: AutoDownloadStore,
    private val downloads: () -> List<DownloadItem>,
    /** The playback progress of the episode is still at its end. */
    private val isWatchedToTheEnd: (DownloadItem) -> Boolean,
    private val isInPlayer: (DownloadItem) -> Boolean,
    /** Whether an episode after this one is released, or null when the episodes are not known. */
    private val hasLaterEpisode: (AutoDownloadRule, DownloadItem) -> Boolean?,
    /**
     * The step that moves the file away and drops the download. It checks the download once more
     * and asks stillWanted at the last moment before it touches the file.
     */
    private val removeDownload: (downloadId: String, ruleKey: String?, stillWanted: () -> Boolean) -> AutoDownloadRemovalResult,
    private val nowEpochMs: () -> Long,
    private val holdMs: Long = DefaultHoldMs,
    private val removeDownloadWithUndo: ((downloadId: String, ruleKey: String?, stillWanted: () -> Boolean) -> WatchedTrashOutcome)? = null,
) {
    /** True when a removal was scheduled for [item]. */
    fun onLocalPlaybackCompleted(
        profileId: Int,
        item: DownloadItem,
        afterCancelEpoch: Long? = null,
    ): Boolean {
        val ruleKey = item.autoDownloadRuleKey
        if (item.status != DownloadStatus.Completed || item.isPlaceholder) return false
        if (!store.allowsRemoval(profileId, ruleKey)) return false
        return store.scheduleRemoval(
            profileId = profileId,
            removal = AutoDownloadRemoval(item.id, ruleKey, nowEpochMs() + holdMs),
            afterCancelEpoch = afterCancelEpoch,
        )
    }

    fun keep(profileId: Int, downloadId: String) {
        store.keep(profileId, downloadId)
    }

    fun undoKeep(profileId: Int, downloadId: String) {
        store.undoKeep(profileId, downloadId, nowEpochMs, holdMs)
    }

    /** Cancels scheduled or kept-aside removals for downloads matching unwatched or cleared content. */
    fun cancelForContent(
        profileId: Int,
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
        onlyNonEpisode: Boolean = false,
    ): Boolean = cancelForContent(
        profileId = profileId,
        parentMetaIds = listOf(parentMetaId),
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        videoId = videoId,
        onlyNonEpisode = onlyNonEpisode,
    )

    /** Cancels scheduled or kept-aside removals for downloads matching any alias of unwatched or cleared content. */
    fun cancelForContent(
        profileId: Int,
        parentMetaIds: Collection<String>,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
        onlyNonEpisode: Boolean = false,
    ): Boolean {
        val normalizedParents = parentMetaIds
            .map { it.trim() }
            .filterTo(linkedSetOf()) { it.isNotEmpty() }
        val normalizedVideoId = videoId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedParents.isEmpty() && normalizedVideoId == null) return false
        val matchingIds = downloads().mapNotNullTo(mutableSetOf()) { item ->
            val itemParent = item.parentMetaId.trim()
            val matches = when {
                onlyNonEpisode ->
                    normalizedParents.isNotEmpty() &&
                        itemParent in normalizedParents &&
                        item.seasonNumber == null &&
                        item.episodeNumber == null
                seasonNumber != null && episodeNumber != null ->
                    (normalizedParents.isNotEmpty() &&
                        itemParent in normalizedParents &&
                        item.seasonNumber == seasonNumber &&
                        item.episodeNumber == episodeNumber) ||
                        (normalizedVideoId != null && item.videoId.trim() == normalizedVideoId)
                normalizedVideoId != null ->
                    item.videoId.trim() == normalizedVideoId &&
                        (normalizedParents.isEmpty() || itemParent in normalizedParents)
                else ->
                    normalizedParents.isNotEmpty() && itemParent in normalizedParents
            }
            item.id.takeIf { matches }
        }
        if (matchingIds.isEmpty()) return false
        return store.cancelRemovals(profileId, matchingIds)
    }

    /** Removes the download now, at the user's request. Null when no removal is scheduled for it. */
    fun deleteNow(profileId: Int, downloadId: String): AutoDownloadRemovalOutcome? =
        store.withScheduledRemoval(profileId, downloadId) { scheduled ->
            process(profileId, scheduled, askedByUser = true)
        }

    /** Handles every removal whose time has come. */
    fun runDue(profileId: Int): Map<String, AutoDownloadRemovalOutcome> {
        val now = nowEpochMs()
        return store.mapDueScheduledRemovals(profileId, now) { scheduled ->
            process(profileId, scheduled, askedByUser = false)
        }
    }

    private fun process(profileId: Int, scheduled: ScheduledAutoDownloadRemoval, askedByUser: Boolean): AutoDownloadRemovalOutcome {
        val removal = scheduled.removal
        val item = downloads().firstOrNull { it.id == removal.downloadId }
        val rule = removal.ruleKey?.let { store.rule(profileId, it) }
        if (
            item == null ||
            !store.allowsRemoval(profileId, removal.ruleKey) ||
            item.autoDownloadRuleKey != removal.ruleKey ||
            item.status != DownloadStatus.Completed ||
            // A file marked as not the real video is the user's to remove, never removed by this.
            item.isPlaceholder ||
            store.hasPendingKeep(profileId, item.id) ||
            store.isKept(profileId, item.id)
        ) {
            return cancel(profileId, scheduled)
        }
        if (isInPlayer(item)) return AutoDownloadRemovalOutcome.Playing
        if (!askedByUser) {
            if (!isWatchedToTheEnd(item)) return cancel(profileId, scheduled)
            if (rule != null && hasLaterEpisode(rule, item) != true) return AutoDownloadRemovalOutcome.LastEpisode
        }
        // Things may change while this runs: the episode is opened, Keep, a new time after a rewatch,
        // the option turned off, another profile. All of that is asked again right before the file
        // is touched.
        val stillWanted = {
            !isInPlayer(item) &&
                removal in store.removals(profileId) &&
                !store.hasPendingKeep(profileId, item.id) &&
                !store.isKept(profileId, item.id) &&
                store.allowsRemoval(profileId, removal.ruleKey) &&
                (askedByUser || isWatchedToTheEnd(item))
        }
        val outcome = removeDownloadWithUndo?.invoke(item.id, removal.ruleKey, stillWanted)
            ?: WatchedTrashOutcome(removeDownload(item.id, removal.ruleKey, stillWanted))
        return when (outcome.result) {
            AutoDownloadRemovalResult.Removed -> {
                // The rule must know it had this episode, so that it does not fetch it again.
                val season = item.seasonNumber
                val episode = item.episodeNumber
                if (removal.ruleKey != null && season != null && episode != null) {
                    store.update(profileId, removal.ruleKey) {
                        it.copy(ownedEpisodeKeys = it.ownedEpisodeKeys + autoDownloadEpisodeKey(season, episode))
                    }
                }
                store.dropRemoval(profileId, removal)
                val deleted = if (outcome.trashed != null) {
                    AutoDownloadDeleted(
                        title = item.title,
                        seasonNumber = season,
                        episodeNumber = episode,
                        atEpochMs = nowEpochMs(),
                        downloadId = item.id,
                        reason = DeletedDownloadReason.AfterWatching,
                        restorableItem = item,
                        trashed = outcome.trashed,
                    )
                } else {
                    AutoDownloadDeleted(item.title, season, episode, nowEpochMs())
                }
                store.recordDeleted(profileId, deleted)
                AutoDownloadRemovalOutcome.Removed
            }
            AutoDownloadRemovalResult.Changed -> cancel(profileId, scheduled)
            AutoDownloadRemovalResult.NotMoved -> AutoDownloadRemovalOutcome.NotMoved
            AutoDownloadRemovalResult.NotReachable -> AutoDownloadRemovalOutcome.NotReachable
            AutoDownloadRemovalResult.Busy -> AutoDownloadRemovalOutcome.Moving
        }
    }

    private fun cancel(profileId: Int, scheduled: ScheduledAutoDownloadRemoval): AutoDownloadRemovalOutcome {
        store.cancelRemoval(profileId, scheduled)
        return AutoDownloadRemovalOutcome.Cancelled
    }

    companion object {
        const val DefaultHoldMs = 24L * 60 * 60 * 1000
    }
}

/** Whether [episodes] has a regular episode after the one [item] is. Null when it is not an episode. */
internal fun hasLaterReleasedEpisode(item: DownloadItem, episodes: List<SeasonBatchEpisode>): Boolean? {
    val season = item.seasonNumber ?: return null
    val episode = item.episodeNumber ?: return null
    return episodes.any {
        it.seasonNumber > 0 && (it.seasonNumber > season || (it.seasonNumber == season && it.episodeNumber > episode))
    }
}

private const val MillisPerHour = 3_600_000L

/** The whole hours until [dueAtEpochMs], rounded to the nearest. 0 means less than half an hour; never negative. */
internal fun removalHoursLeft(dueAtEpochMs: Long, nowEpochMs: Long): Int =
    ((dueAtEpochMs - nowEpochMs + MillisPerHour / 2) / MillisPerHour).coerceAtLeast(0L).toInt()

/**
 * The removed downloads shown to the user, newest first: every entry that can still be undone
 * from the Trash, plus up to [max] non-undoable history entries from the last week.
 */
internal fun recentlyDeletedShown(entries: List<AutoDownloadDeleted>, nowEpochMs: Long, max: Int = 5): List<AutoDownloadDeleted> {
    val keptNonUndo = entries
        .withIndex()
        .filter { (_, entry) -> !entry.canUndo && nowEpochMs - entry.atEpochMs < AutoDownloadStore.RecentlyDeletedMs }
        .sortedWith(compareByDescending<IndexedValue<AutoDownloadDeleted>> { it.value.atEpochMs }.thenByDescending { it.index })
        .take(max)
        .mapTo(HashSet()) { it.index }
    return entries
        .withIndex()
        .filter { (index, entry) -> entry.canUndo || index in keptNonUndo }
        .sortedWith(compareByDescending<IndexedValue<AutoDownloadDeleted>> { it.value.atEpochMs }.thenByDescending { it.index })
        .map { it.value }
}

/** How many whole days ago [entry] was removed. */
internal fun daysSinceDeleted(entry: AutoDownloadDeleted, nowEpochMs: Long): Int =
    ((nowEpochMs - entry.atEpochMs) / (24 * MillisPerHour)).coerceAtLeast(0L).toInt()
