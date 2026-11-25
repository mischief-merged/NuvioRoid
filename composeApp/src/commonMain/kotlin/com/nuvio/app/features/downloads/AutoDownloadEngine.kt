package com.nuvio.app.features.downloads

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the shows that have an automatic-download rule stocked with the episodes the rule asks for.
 *
 * A check looks at one show at a time: it fetches the released episodes, works out which ones the
 * rule wants from them, the watched state and the downloads that exist, and hands the missing ones
 * to the season downloader with the saved season-download choices. Nothing is remembered about an
 * episode until its finished download is seen in Downloads, so an episode that found no source,
 * failed or was stopped is tried again: after 1 hour, then 3 hours, 12 hours and daily. The waiting
 * times are kept in memory and start over when the app restarts.
 *
 * An episode the user deleted by hand from Downloads is remembered like one that was removed
 * after watching, whatever state its download was in, so it is not fetched again.
 *
 * Once automatic downloads take as much as the storage limit, no further episode is queued. A
 * download that a limit paused is resumed by a check when there is room again; whether it then
 * fits is decided by the transfer itself.
 *
 * The engine starts no checks itself. Its owner asks [isFullCheckDue] and [rulesWithDueRetry] from
 * time to time and calls [runCheck]. What it holds in memory belongs to one profile and is dropped
 * as soon as it is used for another. Checks never overlap, and every function can be called from
 * any thread.
 */
internal class AutoDownloadEngine(
    private val store: AutoDownloadStore,
    /** The released episodes of the rule's show, or null when they could not be fetched. */
    private val loadEpisodes: suspend (AutoDownloadRule) -> List<SeasonBatchEpisode>?,
    private val isWatched: (AutoDownloadRule, SeasonBatchEpisode) -> Boolean,
    private val downloads: () -> List<DownloadItem>,
    private val seasonDefaults: (profileId: Int) -> SeasonDownloadDefaults,
    private val startBatch: (SeasonBatchRequest) -> SeasonBatchStart,
    private val cancelBatch: (parentMetaId: String, seasonNumber: Int) -> Unit,
    private val nowEpochMs: () -> Long,
    /** The bytes automatic downloads take now. */
    private val autoDownloadBytes: () -> Long = { 0L },
    /** The bytes all downloads together take now. */
    private val allDownloadBytes: () -> Long = { 0L },
    private val resumeDownload: (downloadId: String) -> Unit = {},
    /** The location that holds the rule's show is not connected, so nothing is queued for it. */
    private val locationAway: (AutoDownloadRule) -> Boolean = { false },
    private val fullCheckIntervalMs: Long = 6L * 60 * 60 * 1000,
) {
    private val checkMutex = Mutex()
    private val lock = SynchronizedObject()

    // Everything below belongs to stateProfileId and is read and written under lock.
    private var stateProfileId: Int? = null
    private var lastFullCheckEpochMs: Long? = null
    private val episodesByRule = mutableMapOf<String, List<SeasonBatchEpisode>>()
    private val retriesByRule = mutableMapOf<String, MutableMap<String, Retry>>()
    private val runningBatches = mutableMapOf<String, SeasonBatchRequest>()
    private val privateSourceSkips = mutableMapOf<String, MutableSet<String>>()
    private val _statuses = MutableStateFlow<Map<String, AutoDownloadStatus>>(emptyMap())

    /** The outcome of the last check of each rule, by rule key. */
    val statuses: StateFlow<Map<String, AutoDownloadStatus>> = _statuses.asStateFlow()

    /** The released episodes the last successful check of this rule found, if there was one. */
    fun knownEpisodes(ruleKey: String): List<SeasonBatchEpisode>? = synchronized(lock) { episodesByRule[ruleKey] }

    /**
     * Checks every rule, or only [onlyRuleKey]. [ignoreWaiting] also tries the episodes that are
     * waiting for their next attempt, for a check the user asked for.
     */
    suspend fun runCheck(onlyRuleKey: String? = null, ignoreWaiting: Boolean = false) {
        checkMutex.withLock {
            val profileId = store.profileId()
            useProfile(profileId)
            if (onlyRuleKey == null) synchronized(lock) { lastFullCheckEpochMs = nowEpochMs() }
            store.currentRules()
                .filter { onlyRuleKey == null || it.key == onlyRuleKey }
                .forEach { rule -> checkRule(profileId, rule.key, ignoreWaiting) }
        }
    }

    private suspend fun checkRule(profileId: Int, ruleKey: String, ignoreWaiting: Boolean) {
        val rule = store.rule(profileId, ruleKey) ?: return
        setStatus(ruleKey, AutoDownloadStatus.Checking)
        val episodes = try {
            loadEpisodes(rule)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }
        // The profile changed, or the rule was turned off, while the episodes were being fetched.
        val current = store.rule(profileId, ruleKey) ?: return
        if (episodes == null) {
            setStatus(ruleKey, AutoDownloadStatus.CouldNotCheck)
            return
        }
        synchronized(lock) { episodesByRule[ruleKey] = episodes }

        val items = downloads()
        recordOwnedEpisodes(profileId, items)
        val plan = planAutoDownload(
            rule = store.rule(profileId, ruleKey) ?: return,
            released = episodes,
            isWatched = { isWatched(current, it) },
            downloads = items,
            pendingRemovalDownloadIds = store.removals(profileId).mapTo(mutableSetOf()) { it.downloadId },
        )
        resumeStoppedDownloads(ruleKey, items)
        val now = nowEpochMs()
        // An episode that is no longer missing has nothing left to wait for.
        synchronized(lock) {
            retriesByRule[ruleKey]?.keys?.retainAll(plan.toDownload.map { it.autoDownloadKey }.toSet())
        }
        val due = plan.toDownload.filter { episode ->
            ignoreWaiting || (retryFor(ruleKey, episode)?.notBeforeEpochMs ?: 0L) <= now
        }
        if (due.isNotEmpty() && locationAway(current)) {
            // The show stays together: its episodes are not put anywhere else. They wait for the location.
            setStatus(ruleKey, AutoDownloadStatus.FolderNotConnected)
            return
        }
        if (due.isNotEmpty() && !storageLimitReached()) {
            val choices = seasonDefaults(profileId)
            val startedBatches = mutableListOf<String>()
            due.groupBy { it.seasonNumber }.forEach { (seasonNumber, seasonEpisodes) ->
                val request = SeasonBatchRequest(
                    contentType = current.parentMetaType,
                    parentMetaId = current.parentMetaId,
                    parentMetaType = current.parentMetaType,
                    title = current.title,
                    logo = current.logo,
                    poster = current.poster,
                    background = current.background,
                    seasonNumber = seasonNumber,
                    episodes = seasonEpisodes,
                    quality = choices.quality,
                    maxBitrateMbps = choices.maxBitrateMbps,
                    audioLanguage = choices.audioLanguage,
                    autoDownloadRuleKey = ruleKey,
                )
                val batchKey = seasonBatchKey(current.parentMetaId, seasonNumber)
                // Registered before the batch starts, so a batch that finishes at once is still known.
                // An earlier batch of this season that is still running keeps its registration.
                val registered = synchronized(lock) {
                    if (stateProfileId != profileId || batchKey in runningBatches) {
                        false
                    } else {
                        runningBatches[batchKey] = request
                        true
                    }
                }
                if (!registered) return@forEach
                val started = store.profileId() == profileId && startBatch(request) == SeasonBatchStart.Started
                if (started) startedBatches += batchKey else synchronized(lock) { runningBatches.remove(batchKey) }
            }
            // The rule was turned off while its batches were being started.
            if (store.rule(profileId, ruleKey) == null) {
                forget(ruleKey)
                startedBatches.forEach(::cancelBatchByKey)
                return
            }
        }
        refreshStatus(profileId, ruleKey)
    }

    /**
     * A batch this engine started has ended. Episodes that got a download, or already had one, no
     * longer wait. The others are tried again after a wait that grows with every attempt.
     */
    fun onBatchFinished(request: SeasonBatchRequest, summary: SeasonBatchSummary) {
        val ruleKey = request.autoDownloadRuleKey ?: return
        val batchKey = seasonBatchKey(request.parentMetaId, request.seasonNumber)
        val now = nowEpochMs()
        val profileId = store.profileId()
        useProfile(profileId)
        synchronized(lock) {
            // A batch that is not registered was cancelled because its rule or its profile went away.
            if (runningBatches[batchKey] !== request) return
            runningBatches.remove(batchKey)
            val retries = retriesByRule.getOrPut(ruleKey) { mutableMapOf() }
            summary.results.forEach { (episode, result) ->
                val reason = when (result) {
                    SeasonBatchEpisodeResult.Completed,
                    SeasonBatchEpisodeResult.AlreadyDownloaded,
                    SeasonBatchEpisodeResult.Paused -> null
                    SeasonBatchEpisodeResult.NoMatchingSource ->
                        if (episode.autoDownloadKey in privateSourceSkips[ruleKey].orEmpty()) {
                            AutoDownloadStatus.PrivateSourcesSkipped
                        } else {
                            AutoDownloadStatus.NoSource
                        }
                    SeasonBatchEpisodeResult.Failed -> AutoDownloadStatus.Failed
                    SeasonBatchEpisodeResult.Cancelled -> AutoDownloadStatus.Stopped
                }
                privateSourceSkips[ruleKey]?.remove(episode.autoDownloadKey)
                if (reason == null) {
                    retries.remove(episode.autoDownloadKey)
                } else {
                    val attempts = (retries[episode.autoDownloadKey]?.attempts ?: 0) + 1
                    retries[episode.autoDownloadKey] = Retry(attempts, now + autoDownloadRetryDelayMs(attempts), reason)
                }
            }
        }
        recordOwnedEpisodes(profileId, downloads())
        refreshStatus(profileId, ruleKey)
    }

    /**
     * A source of [episode] was refused because it is at this computer or on a private network.
     * When the episode ends without a download, its status says so, not that no source was found.
     */
    fun onPrivateSourceSkipped(request: SeasonBatchRequest, episode: SeasonBatchEpisode) {
        val ruleKey = request.autoDownloadRuleKey ?: return
        synchronized(lock) { privateSourceSkips.getOrPut(ruleKey) { mutableSetOf() } += episode.autoDownloadKey }
    }

    /** True when no check of every rule has run for this profile yet, or the last one is six hours old. */
    fun isFullCheckDue(): Boolean = synchronized(lock) {
        val last = lastFullCheckEpochMs
        last == null || nowEpochMs() - last >= fullCheckIntervalMs
    }

    /** The rules with an episode whose wait for another attempt is over. */
    fun rulesWithDueRetry(): List<String> = synchronized(lock) {
        val now = nowEpochMs()
        retriesByRule.filterValues { retries -> retries.values.any { it.notBeforeEpochMs <= now } }.keys.toList()
    }

    /** The downloads changed: notes the episodes the rules own and brings every status up to date. */
    fun onDownloadsChanged(profileId: Int, items: List<DownloadItem>) {
        useProfile(profileId)
        recordOwnedEpisodes(profileId, items)
        synchronized(lock) { episodesByRule.keys.toList() }.forEach { refreshStatus(profileId, it) }
    }

    /**
     * Notes which episodes are in [items] as a finished download of one of the rules. The rule
     * remembers them, so that an episode which later disappears from Downloads is not fetched again.
     * A download that is still running, paused or failed is not remembered.
     */
    fun recordOwnedEpisodes(profileId: Int, items: List<DownloadItem>) {
        val keysByRule = mutableMapOf<String, MutableSet<String>>()
        items.forEach { item ->
            val ruleKey = item.autoDownloadRuleKey ?: return@forEach
            if (item.status != DownloadStatus.Completed || item.isPlaceholder) return@forEach
            val season = item.seasonNumber ?: return@forEach
            val episode = item.episodeNumber ?: return@forEach
            keysByRule.getOrPut(ruleKey) { mutableSetOf() } += autoDownloadEpisodeKey(season, episode)
        }
        keysByRule.forEach { (ruleKey, keys) ->
            val rule = store.rule(profileId, ruleKey) ?: return@forEach
            if (!rule.ownedEpisodeKeys.containsAll(keys)) {
                store.update(profileId, ruleKey) { it.copy(ownedEpisodeKeys = it.ownedEpisodeKeys + keys) }
            }
        }
    }

    /**
     * The user deleted [item] from Downloads. When a rule queued it, the rule remembers the episode
     * as one it had, also when the download was not finished, so that the next check does not
     * fetch it again. Returns whether a rule was updated.
     */
    fun onDeletedByUser(profileId: Int, item: DownloadItem): Boolean {
        val ruleKey = item.autoDownloadRuleKey ?: return false
        val season = item.seasonNumber ?: return false
        val episode = item.episodeNumber ?: return false
        val key = autoDownloadEpisodeKey(season, episode)
        if (store.rule(profileId, ruleKey) == null) return false
        if (!store.update(profileId, ruleKey) { it.copy(ownedEpisodeKeys = it.ownedEpisodeKeys + key) }) return false
        synchronized(lock) { retriesByRule[ruleKey]?.remove(key) }
        refreshStatus(profileId, ruleKey)
        return true
    }

    /** Works out the status of a rule again from the downloads as they are now. Fetches nothing. */
    fun refreshStatus(profileId: Int, ruleKey: String) {
        val rule = store.rule(profileId, ruleKey) ?: return
        val episodes = knownEpisodes(ruleKey) ?: return
        val items = downloads()
        val plan = planAutoDownload(
            rule = rule,
            released = episodes,
            isWatched = { isWatched(rule, it) },
            downloads = items,
            pendingRemovalDownloadIds = store.removals(profileId).mapTo(mutableSetOf()) { it.downloadId },
        )
        val statusByEpisode = items.episodeStatuses(rule.parentMetaId)
        val windowStatuses = plan.window.map { statusByEpisode[it.autoDownloadKey] }
        val waiting = plan.toDownload.mapNotNull { retryFor(ruleKey, it) }
        val batchRunning = synchronized(lock) { runningBatches.values.any { it.autoDownloadRuleKey == ruleKey } }
        val stops = items.filter { it.autoDownloadRuleKey == ruleKey }.mapNotNull { it.autoDownloadStop }
        setStatus(
            ruleKey,
            when {
                AutoDownloadStop.StorageLimit in stops || AutoDownloadStop.TotalStorageLimit in stops ->
                    AutoDownloadStatus.StorageLimitReached
                AutoDownloadStop.LowDiskSpace in stops -> AutoDownloadStatus.LowDiskSpace
                plan.toDownload.isNotEmpty() && locationAway(rule) -> AutoDownloadStatus.FolderNotConnected
                plan.toDownload.isNotEmpty() && !batchRunning && storageLimitReached() ->
                    AutoDownloadStatus.StorageLimitReached
                batchRunning || DownloadStatus.Downloading in windowStatuses -> AutoDownloadStatus.Downloading
                waiting.isNotEmpty() -> waiting.maxBy { it.notBeforeEpochMs }.reason
                DownloadStatus.Paused in windowStatuses -> AutoDownloadStatus.Paused
                plan.toDownload.isNotEmpty() -> AutoDownloadStatus.Pending
                else -> AutoDownloadStatus.UpToDate
            },
        )
    }

    private fun storageLimitReached(): Boolean = autoStorageLimitReached() || totalStorageLimitReached()

    private fun autoStorageLimitReached(): Boolean {
        val limit = store.storageLimitBytes() ?: return false
        return autoDownloadBytes() >= limit
    }

    private fun totalStorageLimitReached(): Boolean {
        val limit = store.totalStorageLimitBytes() ?: return false
        return allDownloadBytes() >= limit
    }

    /** Resumes the rule's downloads that a limit paused, unless the storage limit is still reached. */
    private fun resumeStoppedDownloads(ruleKey: String, items: List<DownloadItem>) {
        items
            .filter { it.autoDownloadRuleKey == ruleKey && it.status == DownloadStatus.Paused && it.autoDownloadStop != null }
            .forEach { item ->
                if (item.autoDownloadStop == AutoDownloadStop.StorageLimit && storageLimitReached()) return@forEach
                if (item.autoDownloadStop == AutoDownloadStop.TotalStorageLimit && totalStorageLimitReached()) return@forEach
                resumeDownload(item.id)
            }
    }

    /** Forgets a rule that was turned off and stops the episodes it is still adding. */
    fun forget(ruleKey: String) {
        val batches = synchronized(lock) {
            episodesByRule.remove(ruleKey)
            retriesByRule.remove(ruleKey)
            privateSourceSkips.remove(ruleKey)
            runningBatches.filterValues { it.autoDownloadRuleKey == ruleKey }.keys.toList().onEach(runningBatches::remove)
        }
        _statuses.update { it - ruleKey }
        batches.forEach(::cancelBatchByKey)
    }

    /**
     * Makes [profileId] the profile the engine works for. When it was working for another one, what
     * it knew is dropped and the batches it had started are stopped. Does nothing otherwise.
     */
    fun useProfile(profileId: Int) {
        val batches = synchronized(lock) {
            if (stateProfileId == profileId) return
            stateProfileId = profileId
            lastFullCheckEpochMs = null
            episodesByRule.clear()
            retriesByRule.clear()
            privateSourceSkips.clear()
            _statuses.value = emptyMap()
            runningBatches.keys.toList().also { runningBatches.clear() }
        }
        batches.forEach(::cancelBatchByKey)
    }

    private fun cancelBatchByKey(batchKey: String) {
        val seasonNumber = batchKey.substringAfterLast('|').toIntOrNull() ?: return
        cancelBatch(batchKey.substringBeforeLast('|'), seasonNumber)
    }

    private fun retryFor(ruleKey: String, episode: SeasonBatchEpisode): Retry? =
        synchronized(lock) { retriesByRule[ruleKey]?.get(episode.autoDownloadKey) }

    private fun setStatus(ruleKey: String, status: AutoDownloadStatus) {
        _statuses.update { it + (ruleKey to status) }
    }

    private class Retry(val attempts: Int, val notBeforeEpochMs: Long, val reason: AutoDownloadStatus)
}
