package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/*
 * When a download turns out not to be the video, or its source fails in a way that trying it again
 * cannot fix, the app looks for another source for the same title by itself and downloads that one.
 *
 * The search is a season download of one item ([SeasonBatchDownloader]): the same lookup, the same
 * ranking by the saved quality, bitrate and language choices, with cached sources before ones that are
 * not, the same identity checks, and the same move to the next source when a file is bad. On top of
 * that it leaves out every source already downloaded for the title, which the downloads remember
 * ([DownloadItem.triedSources]), so a source is never fetched twice by the app.
 *
 * At most [MaxAutoSourceAttempts] sources are started by the app for one title, counted on the
 * downloads ([DownloadItem.autoSourceAttempts]) so that the limit holds across a restart. When none of
 * them works, or there is none left to try, the downloads are marked ([DownloadItem.noWorkingSourceFound])
 * and only the user picks another source. A bad file is never removed by the search: a file that is not
 * the video stays where it is, beside the new copy, and is not counted as downloaded.
 */

/**
 * Most sources the app starts by itself for one title after a bad one: the number a season download
 * tries for one episode. Three gives the next two or three best sources a chance, which is where a
 * working one usually is when there is one, without fetching many gigabytes of files that are not it.
 */
internal const val MaxAutoSourceAttempts = SeasonBatchDownloader.MaxSourcesPerEpisode

/** How many source identities one download remembers. The oldest are forgotten first. */
internal const val MaxRememberedSources = 40

private val whitespace = Regex("\\s+")

private fun normalizedPart(text: String?): String = text.orEmpty().trim().lowercase().replace(whitespace, " ")

/**
 * The identity of a source as its add-on lists it: the add-on, the label and the description. It is
 * the same before and after a debrid link was worked out for it, and the same in the next lookup.
 */
internal fun sourceLabelKey(addonName: String?, label: String?, description: String?): String =
    "label:" + listOf(addonName, label, description).joinToString("\n", transform = ::normalizedPart)

private fun sourceUrlKey(url: String?): String? = url?.trim()?.takeIf { it.isNotEmpty() }?.let { "url:$it" }

/** What a source is known by, to tell whether it was tried already: its listing, and its address when it has one. */
internal val StreamItem.sourceIdentityKeys: List<String>
    get() = listOfNotNull(sourceLabelKey(addonName, streamLabel, streamSubtitle), sourceUrlKey(playableDirectUrl))

/** What the source of this download is known by. */
internal val DownloadItem.sourceIdentityKeys: List<String>
    get() = listOfNotNull(sourceLabelKey(providerName, streamTitle, streamSubtitle), sourceUrlKey(sourceUrl))

/** Whether [this] source is one of [tried]. */
internal fun StreamItem.wasTried(tried: Set<String>): Boolean = tried.isNotEmpty() && sourceIdentityKeys.any { it in tried }

/** What a new download of a title takes over from the downloads of that title that are already there. */
internal class SourceHistory(val triedSources: List<String>, val autoSourceAttempts: Int)

/**
 * The history a new download from [stream] starts with, given the downloads of the same title that
 * are there now ([sameTitle]): every source they tried and their own, and this one. A download the
 * app starts after a bad source, including after a later transfer failure, counts as one more of its
 * attempts. One the user chose starts the count again: the user has made a new choice, so the app may
 * look again if that one is bad too.
 */
internal fun sourceHistoryFor(sameTitle: List<DownloadItem>, stream: StreamItem, userChose: Boolean): SourceHistory {
    val tried = (sameTitle.flatMap { it.triedSources + it.sourceIdentityKeys } + stream.sourceIdentityKeys)
        .distinct()
        .takeLast(MaxRememberedSources)
    val attempts = when {
        userChose || sameTitle.none { it.hasBadSource || it.autoSourceAttempts > 0 } -> 0
        else -> sameTitle.maxOf { it.autoSourceAttempts } + 1
    }
    return SourceHistory(tried, attempts)
}

/** What the app does next about a title whose source was bad. */
internal sealed interface SourceReplacementStep {
    val titleKey: String

    /** Look for another source for the title of [bad], leaving out [skip]. [attemptsUsed] were started before. */
    data class Search(val bad: DownloadItem, val skip: Set<String>, val attemptsUsed: Int) : SourceReplacementStep {
        override val titleKey: String get() = bad.logicalContentKey
    }

    /** The app has started as many sources as it may: only the user picks one now. */
    data class GiveUp(override val titleKey: String) : SourceReplacementStep
}

/**
 * What to do now, worked out from the downloads alone. A title is looked at when one of its downloads
 * has a bad source and was not given up on. It is left alone while:
 *  - a season download, or a search of the app, is working on it ([busyTitles]);
 *  - another download of it runs, waits or is paused (the user's pause is never undone), or a good
 *    copy of it is downloaded;
 *  - the download belongs to an automatic-download rule, which tries again on its own schedule;
 *  - its location cannot be reached ([isAway]).
 */
internal fun sourceReplacementSteps(
    items: List<DownloadItem>,
    busyTitles: Set<String>,
    isAway: (DownloadItem) -> Boolean = { false },
): List<SourceReplacementStep> =
    items.groupBy { it.logicalContentKey }.mapNotNull { (key, sameTitle) ->
        val waiting = sameTitle.filter { it.hasBadSource && !it.noWorkingSourceFound }
        when {
            waiting.isEmpty() -> null
            waiting.any { it.autoDownloadRuleKey != null } -> null
            key in busyTitles -> null
            sameTitle.any {
                it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Paused ||
                    (it.status == DownloadStatus.Completed && !it.isPlaceholder)
            } -> null
            waiting.any { runCatching { isAway(it) }.getOrDefault(true) } -> null
            else -> {
                val used = sameTitle.maxOf { it.autoSourceAttempts }
                if (used >= MaxAutoSourceAttempts) {
                    SourceReplacementStep.GiveUp(key)
                } else {
                    SourceReplacementStep.Search(
                        bad = waiting.maxBy { it.updatedAtEpochMs },
                        skip = sameTitle.flatMap { it.triedSources + it.sourceIdentityKeys }.toSet(),
                        attemptsUsed = used,
                    )
                }
            }
        }
    }

/** Which source of the app's own search for a title is being tried: the [attempt]th of [of]. */
internal data class SourceSearchProgress(val attempt: Int, val of: Int = MaxAutoSourceAttempts)

/** What a download row says about the app's search for another source. */
internal sealed interface SourceSearchRowState {
    /** The app is trying the [attempt]th source of [of] for this title. */
    data class Trying(val attempt: Int, val of: Int) : SourceSearchRowState

    /** The app will look for another source as soon as it can. */
    data object Waiting : SourceSearchRowState

    /** The app found no working source. The user picks one. */
    data object NoneFound : SourceSearchRowState
}

/**
 * The search state a row shows, from the download and the searches running now ([searches], by title).
 * Null for a download the search does not concern: one that is fine, or one of an automatic-download rule.
 */
internal fun sourceSearchRowState(item: DownloadItem, searches: Map<String, SourceSearchProgress>): SourceSearchRowState? {
    if (item.autoDownloadRuleKey != null) return null
    val progress = searches[item.logicalContentKey]
    return when {
        item.hasBadSource -> when {
            progress != null -> SourceSearchRowState.Trying(progress.attempt, progress.of)
            item.noWorkingSourceFound -> SourceSearchRowState.NoneFound
            else -> SourceSearchRowState.Waiting
        }
        item.status == DownloadStatus.Downloading && progress != null -> SourceSearchRowState.Trying(progress.attempt, progress.of)
        else -> null
    }
}

/**
 * Watches the downloads and starts the search for another source wherever [sourceReplacementSteps]
 * says so, one search per title at a time. Nothing is started while [canSearch] is false (offline, or
 * downloads wait for a better connection). A search that ends without a working source marks the title
 * as given up ([giveUp]), unless the internet was gone by then: then nothing is marked, and the search
 * runs again when the app is back online, within what is left of the limit.
 */
internal class AutoSourceReplacer(
    private val downloads: StateFlow<DownloadsUiState>,
    /** Titles a season download is working on. */
    private val busyTitles: StateFlow<Set<String>>,
    private val canSearch: StateFlow<Boolean>,
    private val isOnline: () -> Boolean,
    private val isAway: (DownloadItem) -> Boolean,
    /** Starts the search. False when it could not start now; it is then tried again on the next change. */
    private val startSearch: (SourceReplacementStep.Search) -> Boolean,
    private val giveUp: (titleKey: String) -> Unit,
) {
    private val lock = SynchronizedObject()

    // The searches this replacer started and that have not ended: the attempts used before each.
    private val searches = mutableMapOf<String, Int>()

    // Titles whose search ended without a working source while the internet was gone. Each is looked at
    // again once the app is online, and not before: a lookup without the internet finds nothing.
    private val waitingForNetwork = mutableSetOf<String>()

    private val _progress = MutableStateFlow<Map<String, SourceSearchProgress>>(emptyMap())

    /** The searches running now, by title. */
    val progress: StateFlow<Map<String, SourceSearchProgress>> = _progress.asStateFlow()

    /** Looks at the downloads now and starts or gives up what is due. */
    fun evaluate() {
        if (!canSearch.value) return
        val online = runCatching(isOnline).getOrDefault(false)
        val running = synchronized(lock) {
            if (online) waitingForNetwork.clear()
            searches.keys + waitingForNetwork
        }
        val steps = sourceReplacementSteps(downloads.value.items, busyTitles.value + running, isAway)
        for (step in steps) {
            when (step) {
                is SourceReplacementStep.GiveUp -> giveUp(step.titleKey)
                is SourceReplacementStep.Search -> {
                    val key = step.titleKey
                    val claimed = synchronized(lock) {
                        if (key in searches) {
                            false
                        } else {
                            searches[key] = step.attemptsUsed
                            _progress.update { it + (key to SourceSearchProgress(step.attemptsUsed + 1)) }
                            true
                        }
                    }
                    if (!claimed) continue
                    val started = runCatching { startSearch(step) }.getOrDefault(false)
                    if (!started) end(key)
                }
            }
        }
    }

    /**
     * Evaluates on every change of the downloads, the season downloads, whether a search may run, and
     * the searches of this replacer (one that ends frees its title). Runs until cancelled.
     */
    suspend fun watch() {
        combine(downloads, busyTitles, canSearch, _progress) { _, _, _, _ -> Unit }.collect { evaluate() }
    }

    /** The search for [titleKey] moved on to its [attemptInSearch]th source. */
    fun onSourceTried(titleKey: String, attemptInSearch: Int) {
        synchronized(lock) {
            val used = searches[titleKey] ?: return
            _progress.update { it + (titleKey to SourceSearchProgress((used + attemptInSearch).coerceAtMost(MaxAutoSourceAttempts))) }
        }
    }

    /** The search for [titleKey] ended with [result]. */
    fun onSearchFinished(titleKey: String, result: SeasonBatchEpisodeResult) {
        val noneWorked = result == SeasonBatchEpisodeResult.Failed || result == SeasonBatchEpisodeResult.NoMatchingSource
        // Marked before the search is let go, so that the next look does not start it again.
        if (noneWorked) {
            if (runCatching(isOnline).getOrDefault(false)) {
                runCatching { giveUp(titleKey) }
            } else {
                synchronized(lock) { waitingForNetwork += titleKey }
            }
        }
        // Ending it changes [progress], which the watcher takes as a change to look again at.
        end(titleKey)
    }

    private fun end(titleKey: String) {
        synchronized(lock) {
            searches.remove(titleKey)
            _progress.update { it - titleKey }
        }
    }
}
