package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

internal const val DEFAULT_STARTUP_SUBTITLE_TIMEOUT_MS = 3_500L
internal const val DEFAULT_PER_FILE_SUBTITLE_TIMEOUT_MS = 2_500L
internal const val DEFAULT_LOCAL_SUBTITLE_VALIDATION_TIMEOUT_MS = 1_500L
internal const val DEFAULT_MAX_SUBTITLE_FILE_BYTES = 4 * 1024 * 1024
private const val FAILED_URL_TTL_MS = 60_000L
private const val ADDON_PREFETCH_TTL_MS = 60_000L
private const val MAX_CANDIDATE_ATTEMPTS = 3

private val startupSubLog = Logger.withTag("StartupSubtitles")

/**
 * Startup subtitle selection metadata passed alongside [PlayerLaunch.externalSubtitles] so
 * players know which local file to activate at startup and whether startup resolution fell back to none.
 */
data class StartupSubtitleSelection(
    val selectedUrl: String? = null,
    val disableAutoSelect: Boolean = false,
    val exhaustedOrTimedOut: Boolean = false,
    val failedUrls: Set<String> = emptySet(),
    val localByRemoteUrl: Map<String, String> = emptyMap(),
)

/**
 * Validated local subtitle files prepared before playback starts.
 */
data class PreparedStartupSubtitles(
    val subtitles: List<StreamSubtitle> = emptyList(),
    val selection: StartupSubtitleSelection = StartupSubtitleSelection(),
)

internal data class StartupSubtitleCandidate(
    val id: String,
    val url: String,
    val language: String,
    val name: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val addonName: String? = null,
    val isForced: Boolean = false,
    val isLocal: Boolean = false,
    val isStreamAttachment: Boolean = false,
)

internal data class StartupSubtitleRequest(
    val type: String,
    val videoId: String,
    val parentMetaId: String,
    val settings: PlayerSettingsUiState,
    val streamExternalSubtitles: List<StreamSubtitle> = emptyList(),
    val videoHash: String? = null,
    val videoSizeBytes: Long? = null,
    val filename: String? = null,
    val audioLanguageHint: String? = null,
)

internal data class StartupSubtitleDependencies(
    val fetchAddonSubtitlesStreaming: suspend (
        request: StartupSubtitleRequest,
        onBatchLoaded: (List<AddonSubtitle>) -> Unit,
    ) -> List<AddonSubtitle>,
    val fetchSubtitleText: suspend (
        url: String,
        headers: Map<String, String>,
        languageHint: String?,
    ) -> String?,
    val readLocalSubtitleText: suspend (uriOrPath: String) -> String?,
    val writeCachedSubtitleText: suspend (fileName: String, content: String) -> String?,
    val validateSubtitleContent: suspend (String, String?) -> Boolean = { text, sourceUrl ->
        withContext(Dispatchers.Default) { isValidSubtitleContent(text, sourceUrl) }
    },
    val deviceLanguages: () -> List<String> = { DeviceLanguagePreferences.preferredLanguageCodes() },
    val loadPersistedTrackPreference: (contentId: String) -> PersistedPlayerTrackPreference? =
        PlayerTrackPreferenceStorage::load,
    val nowMs: () -> Long = defaultMonotonicNowMs(),
    val startupTimeoutMs: Long = DEFAULT_STARTUP_SUBTITLE_TIMEOUT_MS,
    val perFileTimeoutMs: Long = DEFAULT_PER_FILE_SUBTITLE_TIMEOUT_MS,
    val localValidationTimeoutMs: Long = DEFAULT_LOCAL_SUBTITLE_VALIDATION_TIMEOUT_MS,
    val maxSubtitleBytes: Int = DEFAULT_MAX_SUBTITLE_FILE_BYTES,
)

private fun defaultMonotonicNowMs(): () -> Long {
    val startMark = TimeSource.Monotonic.markNow()
    return { startMark.elapsedNow().inWholeMilliseconds }
}

internal class StartupSubtitlePreparer(
    private val deps: StartupSubtitleDependencies,
) {
    private val cacheMutex = Mutex()
    private val cachedLocalByRemoteUrl = LinkedHashMap<String, String>()
    private val failedUrlsWithTimestamp = LinkedHashMap<String, Long>()
    private val cachedAddonBatches = LinkedHashMap<String, Pair<Long, List<AddonSubtitle>>>()
    private val inFlightPrefetches = LinkedHashMap<String, Deferred<PreparedStartupSubtitles>>()

    /**
     * Validates saved download sidecar subtitles next to a video and picks the preferred language track.
     */
    suspend fun prepareDownloadedSubtitles(
        localSubtitles: List<StreamSubtitle>,
        settings: PlayerSettingsUiState,
        parentMetaId: String,
        audioLanguageHint: String? = null,
    ): PreparedStartupSubtitles {
        val persisted = loadPersistedPreference(parentMetaId)
        val plan = buildSelectionPlan(settings, persisted, audioLanguageHint)
        if (localSubtitles.isEmpty()) {
            return PreparedStartupSubtitles(
                subtitles = emptyList(),
                selection = StartupSubtitleSelection(
                    selectedUrl = null,
                    disableAutoSelect = plan.disabled,
                ),
            )
        }

        val deadlineMs = deps.nowMs() + deps.localValidationTimeoutMs
        val validLocalSubtitles = ArrayList<StreamSubtitle>(localSubtitles.size)
        val failed = LinkedHashSet<String>()

        for (subtitle in localSubtitles) {
            if (!subtitle.url.isLocalSubtitleUri()) continue
            val remainingMs = deadlineMs - deps.nowMs()
            if (remainingMs <= 0L) {
                failed.add(subtitle.url)
                continue
            }
            val valid = validateLocalSubtitleUri(subtitle.url, remainingMs) != null
            if (valid) {
                validLocalSubtitles.add(subtitle)
            } else {
                failed.add(subtitle.url)
            }
        }

        if (validLocalSubtitles.isEmpty()) {
            return PreparedStartupSubtitles(
                subtitles = emptyList(),
                selection = StartupSubtitleSelection(
                    selectedUrl = null,
                    disableAutoSelect = plan.disabled,
                    exhaustedOrTimedOut = localSubtitles.isNotEmpty(),
                    failedUrls = failed,
                ),
            )
        }

        if (plan.disabled) {
            return PreparedStartupSubtitles(
                subtitles = validLocalSubtitles,
                selection = StartupSubtitleSelection(
                    selectedUrl = null,
                    disableAutoSelect = true,
                    failedUrls = failed,
                ),
            )
        }

        val candidates = validLocalSubtitles.mapIndexed { index, sub ->
            sub.toCandidate(id = "download_$index", isStreamAttachment = true)
        }
        val ranked = rankMatchingCandidates(candidates, plan, settings, audioLanguageHint)
        val selectedCandidate = ranked.firstOrNull()
        val selectedStreamSub = selectedCandidate?.let { chosen ->
            validLocalSubtitles.firstOrNull { it.url == chosen.url }
        }

        return PreparedStartupSubtitles(
            subtitles = validLocalSubtitles,
            selection = StartupSubtitleSelection(
                selectedUrl = selectedStreamSub?.url,
                disableAutoSelect = selectedStreamSub == null &&
                    settings.preferredSubtitleLanguage == SubtitleLanguageOption.NONE,
                failedUrls = failed,
            ),
        )
    }

    /**
     * Starts background subtitle discovery and local caching while stream links are still loading.
     */
    fun prefetchForStream(
        scope: CoroutineScope,
        request: StartupSubtitleRequest,
    ): Deferred<PreparedStartupSubtitles> {
        val key = prefetchKey(request)
        return scope.async {
            val active = cacheMutex.withLock {
                inFlightPrefetches[key]?.takeUnless { it.isCancelled }
            }
            if (active != null) {
                return@async active.await()
            }

            val created = CompletableDeferred<PreparedStartupSubtitles>()
            val winner = cacheMutex.withLock {
                val existing = inFlightPrefetches[key]?.takeUnless { it.isCancelled }
                if (existing != null) {
                    existing
                } else {
                    inFlightPrefetches[key] = created
                    null
                }
            }
            if (winner != null) {
                created.cancel()
                return@async winner.await()
            }

            try {
                val result = prepareStreamSubtitles(request)
                created.complete(result)
                result
            } catch (cancel: CancellationException) {
                created.cancel(cancel)
                throw cancel
            } catch (_: Throwable) {
                val fallback = PreparedStartupSubtitles(
                    selection = StartupSubtitleSelection(exhaustedOrTimedOut = true),
                )
                created.complete(fallback)
                fallback
            } finally {
                cacheMutex.withLock {
                    if (inFlightPrefetches[key] === created) {
                        inFlightPrefetches.remove(key)
                    }
                }
            }
        }
    }

    /**
     * Fetches the preferred subtitle in parallel with [resolveSource] and returns a validated
     * local file ready for mpv, falling back cleanly to none on failure or timeout.
     */
    suspend fun <T> prepareStreamInParallelWithSource(
        request: StartupSubtitleRequest,
        resolveSource: suspend () -> T,
        extractStreamSubtitles: (T) -> List<StreamSubtitle> = { emptyList() },
    ): Pair<T, PreparedStartupSubtitles> = coroutineScope {
        val persisted = loadPersistedPreference(request.parentMetaId)
        val plan = buildSelectionPlan(request.settings, persisted, request.audioLanguageHint)

        if (plan.disabled) {
            val sourceResult = resolveSource()
            return@coroutineScope sourceResult to PreparedStartupSubtitles(
                subtitles = (request.streamExternalSubtitles + extractStreamSubtitles(sourceResult))
                    .distinctBy { it.url },
                selection = StartupSubtitleSelection(
                    selectedUrl = null,
                    disableAutoSelect = true,
                ),
            )
        }

        val existingPrefetch = cacheMutex.withLock {
            inFlightPrefetches[prefetchKey(request)]?.takeUnless { it.isCancelled }
        }
        val deadlineMs = deps.nowMs() + deps.startupTimeoutMs
        val lateStreamSubtitles = Channel<List<StreamSubtitle>>(Channel.CONFLATED)

        val sourceDeferred = async {
            val sourceResult = resolveSource()
            lateStreamSubtitles.trySend(extractStreamSubtitles(sourceResult))
            lateStreamSubtitles.close()
            sourceResult
        }

        val subtitleDeferred = async {
            val budgetMs = (deadlineMs - deps.nowMs()).coerceAtLeast(0L)
            if (budgetMs <= 0L) {
                PreparedStartupSubtitles(
                    selection = StartupSubtitleSelection(exhaustedOrTimedOut = true),
                )
            } else {
                withTimeoutOrNull(budgetMs) {
                    prepareStreamSubtitlesWithDeadline(
                        request = request,
                        plan = plan,
                        deadlineMs = deadlineMs,
                        lateStreamSubtitles = lateStreamSubtitles,
                        existingPrefetch = existingPrefetch?.takeIf { request.streamExternalSubtitles.isEmpty() },
                    )
                } ?: PreparedStartupSubtitles(
                    selection = StartupSubtitleSelection(exhaustedOrTimedOut = true),
                )
            }
        }

        val sourceResult = sourceDeferred.await()
        val preparedSubtitles = subtitleDeferred.await()
        sourceResult to preparedSubtitles
    }

    /**
     * Fetches addon + stream subtitles and prepares a validated local file within timeout.
     */
    suspend fun prepareStreamSubtitles(request: StartupSubtitleRequest): PreparedStartupSubtitles {
        val persisted = loadPersistedPreference(request.parentMetaId)
        val plan = buildSelectionPlan(request.settings, persisted, request.audioLanguageHint)
        if (plan.disabled) {
            return PreparedStartupSubtitles(
                subtitles = request.streamExternalSubtitles,
                selection = StartupSubtitleSelection(
                    selectedUrl = null,
                    disableAutoSelect = true,
                ),
            )
        }

        val deadlineMs = deps.nowMs() + deps.startupTimeoutMs
        return withTimeoutOrNull(deps.startupTimeoutMs) {
            prepareStreamSubtitlesWithDeadline(
                request = request,
                plan = plan,
                deadlineMs = deadlineMs,
                lateStreamSubtitles = null,
            )
        } ?: PreparedStartupSubtitles(
            selection = StartupSubtitleSelection(exhaustedOrTimedOut = true),
        )
    }

    /**
     * Downloads or reads and validates a subtitle URI/path on demand, returning a local file:// URI
     * or `null` if invalid or timed out.
     */
    suspend fun resolvePlayableLocalSubtitle(
        url: String,
        headers: Map<String, String> = emptyMap(),
        languageHint: String? = null,
        timeoutMs: Long = deps.perFileTimeoutMs,
    ): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.isLocalSubtitleUri()) {
            val content = validateLocalSubtitleUri(trimmed, timeoutMs) ?: return null
            val resolvedPath = SubtitleLocalFilePlatform.resolveLocalFilePath(trimmed)
            if (resolvedPath != null) return trimmed
            val ext = PlayerSubtitleCueParser.fileExtension(content, trimmed)
            return deps.writeCachedSubtitleText("on_demand_${stableHash(trimmed)}.$ext", content)
        }

        cachedValidLocalUri(trimmed)?.let { return it }

        val result = withTimeoutOrNull(timeoutMs) {
            downloadValidateAndCache(
                url = trimmed,
                headers = headers,
                languageHint = languageHint,
                cachePrefix = "startup_",
            )
        }
        if (result != null) {
            cacheMutex.withLock {
                cachedLocalByRemoteUrl[trimmed] = result
                failedUrlsWithTimestamp.remove(trimmed)
            }
            return result
        }
        markUrlFailed(trimmed)
        return null
    }

    internal suspend fun cachedValidLocalUri(remoteUrl: String): String? {
        val cached = cacheMutex.withLock { cachedLocalByRemoteUrl[remoteUrl] } ?: return null
        if (validateLocalSubtitleUri(cached, deps.localValidationTimeoutMs) != null) {
            return cached
        }
        cacheMutex.withLock { cachedLocalByRemoteUrl.remove(remoteUrl) }
        return null
    }

    internal suspend fun isRecentlyFailed(remoteUrl: String): Boolean {
        val failedAt = cacheMutex.withLock { failedUrlsWithTimestamp[remoteUrl] } ?: return false
        if (deps.nowMs() - failedAt <= FAILED_URL_TTL_MS) return true
        cacheMutex.withLock { failedUrlsWithTimestamp.remove(remoteUrl) }
        return false
    }

    private suspend fun prepareStreamSubtitlesWithDeadline(
        request: StartupSubtitleRequest,
        plan: StartupSelectionPlan,
        deadlineMs: Long,
        lateStreamSubtitles: Channel<List<StreamSubtitle>>?,
        existingPrefetch: Deferred<PreparedStartupSubtitles>? = null,
    ): PreparedStartupSubtitles = coroutineScope {
        val failed = LinkedHashSet<String>()
        val collectedStreamSubs = ArrayList<StreamSubtitle>(request.streamExternalSubtitles)
        val collectedAddons = ArrayList<AddonSubtitle>()
        val triedUrls = HashSet<String>()
        val candidateSignals = Channel<Unit>(Channel.CONFLATED)
        val candidateLock = Mutex()
        var preparedPrefetch: PreparedStartupSubtitles? = null

        suspend fun signalUpdated() {
            candidateSignals.trySend(Unit)
        }

        val addonCacheId = addonCacheKey(request)
        val cachedAddons = cachedAddonsIfFresh(addonCacheId)
        val incomingBatches = Channel<List<AddonSubtitle>>(Channel.UNLIMITED)
        val addonDone = CompletableDeferred<Unit>()
        val fetchJob = launch {
            try {
                if (existingPrefetch != null) {
                    val result = existingPrefetch.await()
                    candidateLock.withLock { preparedPrefetch = result }
                    signalUpdated()
                } else if (cachedAddons != null) {
                    candidateLock.withLock { collectedAddons.addAll(cachedAddons) }
                    signalUpdated()
                } else {
                    val fresh = deps.fetchAddonSubtitlesStreaming(request) { batch ->
                        if (batch.isNotEmpty()) {
                            incomingBatches.trySend(batch)
                            candidateSignals.trySend(Unit)
                        }
                    }
                    while (true) {
                        val batch = incomingBatches.tryReceive().getOrNull() ?: break
                        candidateLock.withLock { collectedAddons.addAll(batch) }
                    }
                    if (fresh.isNotEmpty()) {
                        candidateLock.withLock {
                            if (collectedAddons.isEmpty()) {
                                collectedAddons.addAll(fresh)
                            }
                        }
                        storeCachedAddons(addonCacheId, collectedAddons.toList())
                        signalUpdated()
                    }
                }
            } finally {
                incomingBatches.close()
                addonDone.complete(Unit)
                candidateSignals.trySend(Unit)
            }
        }

        val streamDone = CompletableDeferred<Unit>()
        val lateStreamJob = launch {
            try {
                if (lateStreamSubtitles != null) {
                    val more = lateStreamSubtitles.receiveCatching().getOrNull().orEmpty()
                    if (more.isNotEmpty()) {
                        candidateLock.withLock { collectedStreamSubs.addAll(more) }
                        signalUpdated()
                    }
                }
            } finally {
                streamDone.complete(Unit)
                candidateSignals.trySend(Unit)
            }
        }

        suspend fun snapshotRanked(onlyPrimary: Boolean): List<StartupSubtitleCandidate> =
            candidateLock.withLock {
                while (true) {
                    val batch = incomingBatches.tryReceive().getOrNull() ?: break
                    collectedAddons.addAll(batch)
                }
                val streamCandidates = collectedStreamSubs.mapIndexed { index, sub ->
                    sub.toCandidate(id = "stream_$index", isStreamAttachment = true)
                }
                val addonCandidates = collectedAddons.map { it.toCandidate() }
                val combined = (streamCandidates + addonCandidates).distinctBy { it.url }
                val ranked = rankMatchingCandidates(
                    combined,
                    plan,
                    request.settings,
                    request.audioLanguageHint,
                ).filterNot { it.url in triedUrls }
                if (onlyPrimary) ranked.filter { isPrimaryPreferenceMatch(it, plan) } else ranked
            }

        suspend fun tryRanked(onlyPrimary: Boolean): PreparedStartupSubtitles? {
            val budget = MAX_CANDIDATE_ATTEMPTS - triedUrls.size
            if (budget <= 0) return null
            val subset = snapshotRanked(onlyPrimary).take(budget)
            if (subset.isEmpty()) return null
            subset.forEach { triedUrls.add(it.url) }
            val outcome = if (existingPrefetch == null) {
                tryResolveCandidatesToLocal(subset, deadlineMs, failed)
            } else {
                coroutineScope {
                    val resolving = async { tryResolveCandidatesToLocal(subset, deadlineMs, failed) }
                    try {
                        select {
                            existingPrefetch.onAwait { prefetched ->
                                prefetched.takeIf { it.selection.selectedUrl != null } ?: resolving.await()
                            }
                            resolving.onAwait { it }
                        }
                    } finally {
                        resolving.cancel()
                    }
                }
            }
            failed.addAll(outcome.selection.failedUrls)
            return outcome.takeIf { it.selection.selectedUrl != null }?.let {
                it.copy(selection = it.selection.copy(failedUrls = failed.toSet()))
            }
        }

        suspend fun successfulPrefetch(): PreparedStartupSubtitles? = candidateLock.withLock {
            val result = preparedPrefetch ?: return@withLock null
            failed.addAll(result.selection.failedUrls)
            result.takeIf { it.selection.selectedUrl != null }?.let {
                it.copy(selection = it.selection.copy(failedUrls = failed.toSet()))
            }
        }

        // Try initial stream-provided candidates immediately while addon discovery is in flight.
        if (collectedStreamSubs.isNotEmpty()) {
            tryRanked(onlyPrimary = true)?.let { found ->
                fetchJob.cancel()
                lateStreamJob.cancel()
                candidateSignals.close()
                return@coroutineScope found
            }
        }

        val startedAtMs = deps.nowMs()
        val secondaryAllowanceMs = minOf(deps.startupTimeoutMs / 2, 1_500L)
        while (deps.nowMs() < deadlineMs &&
            (triedUrls.size < MAX_CANDIDATE_ATTEMPTS || (existingPrefetch != null && !addonDone.isCompleted))
        ) {
            successfulPrefetch()?.let { found ->
                fetchJob.cancel()
                lateStreamJob.cancel()
                candidateSignals.close()
                return@coroutineScope found
            }
            tryRanked(onlyPrimary = true)?.let { found ->
                fetchJob.cancel()
                lateStreamJob.cancel()
                candidateSignals.close()
                return@coroutineScope found
            }
            val allowSecondaryNow = (addonDone.isCompleted && streamDone.isCompleted) ||
                (deps.nowMs() - startedAtMs >= secondaryAllowanceMs)
            if (allowSecondaryNow) {
                tryRanked(onlyPrimary = false)?.let { found ->
                    fetchJob.cancel()
                    lateStreamJob.cancel()
                    candidateSignals.close()
                    return@coroutineScope found
                }
            }
            if (addonDone.isCompleted && streamDone.isCompleted) break
            val remainingMs = (deadlineMs - deps.nowMs()).coerceAtLeast(0L)
            if (remainingMs <= 0L) break
            val waitWindowMs = if (!allowSecondaryNow) {
                val untilSecondary = (startedAtMs + secondaryAllowanceMs - deps.nowMs()).coerceAtLeast(1L)
                minOf(remainingMs, untilSecondary)
            } else {
                remainingMs
            }
            withTimeoutOrNull(waitWindowMs) {
                candidateSignals.receiveCatching()
            }
        }

        successfulPrefetch()?.let { found ->
            fetchJob.cancel()
            lateStreamJob.cancel()
            candidateSignals.close()
            return@coroutineScope found
        }

        // Fall back to secondary language targets if primary yielded no working local subtitle.
        if (deps.nowMs() < deadlineMs && triedUrls.size < MAX_CANDIDATE_ATTEMPTS) {
            tryRanked(onlyPrimary = false)?.let { found ->
                fetchJob.cancel()
                lateStreamJob.cancel()
                candidateSignals.close()
                return@coroutineScope found
            }
        }

        fetchJob.cancel()
        lateStreamJob.cancel()
        candidateSignals.close()
        val anyCandidates = candidateLock.withLock {
            triedUrls.isNotEmpty() || collectedStreamSubs.isNotEmpty() || collectedAddons.isNotEmpty() ||
                preparedPrefetch?.selection?.exhaustedOrTimedOut == true
        }
        PreparedStartupSubtitles(
            subtitles = emptyList(),
            selection = StartupSubtitleSelection(
                selectedUrl = null,
                disableAutoSelect = false,
                exhaustedOrTimedOut = anyCandidates,
                failedUrls = failed,
            ),
        )
    }

    private suspend fun tryResolveCandidatesToLocal(
        candidates: List<StartupSubtitleCandidate>,
        deadlineMs: Long,
        initialFailed: Set<String>,
    ): PreparedStartupSubtitles {
        val failed = LinkedHashSet<String>(initialFailed)
        for (candidate in candidates.take(MAX_CANDIDATE_ATTEMPTS)) {
            val remainingMs = deadlineMs - deps.nowMs()
            if (remainingMs <= 0L) {
                failed.add(candidate.url)
                markUrlFailed(candidate.url)
                break
            }
            val attemptTimeoutMs = minOf(remainingMs, deps.perFileTimeoutMs)
            val resolvedUri = if (candidate.isLocal) {
                if (validateLocalSubtitleUri(candidate.url, attemptTimeoutMs) != null) {
                    candidate.url
                } else {
                    null
                }
            } else {
                cachedValidLocalUri(candidate.url) ?: withTimeoutOrNull(attemptTimeoutMs) {
                    downloadValidateAndCache(
                        url = candidate.url,
                        headers = candidate.headers,
                        languageHint = candidate.language,
                        cachePrefix = "startup_",
                    )
                }?.also { localUri ->
                    cacheMutex.withLock {
                        cachedLocalByRemoteUrl[candidate.url] = localUri
                        failedUrlsWithTimestamp.remove(candidate.url)
                    }
                }
            }

            if (resolvedUri != null) {
                val streamSub = StreamSubtitle(
                    url = resolvedUri,
                    language = candidate.language,
                    name = candidate.name ?: SubtitleLanguageMatching.languageCodeToName(candidate.language),
                )
                return PreparedStartupSubtitles(
                    subtitles = listOf(streamSub),
                    selection = StartupSubtitleSelection(
                        selectedUrl = resolvedUri,
                        disableAutoSelect = false,
                        exhaustedOrTimedOut = false,
                        failedUrls = failed,
                        localByRemoteUrl = if (candidate.isLocal) {
                            emptyMap()
                        } else {
                            mapOf(candidate.url to resolvedUri)
                        },
                    ),
                )
            } else {
                failed.add(candidate.url)
                markUrlFailed(candidate.url)
            }
        }

        return PreparedStartupSubtitles(
            subtitles = emptyList(),
            selection = StartupSubtitleSelection(
                selectedUrl = null,
                disableAutoSelect = false,
                exhaustedOrTimedOut = true,
                failedUrls = failed,
            ),
        )
    }

    private suspend fun validateLocalSubtitleUri(uriOrPath: String, timeoutMs: Long): String? {
        if (timeoutMs <= 0L) return null
        return withTimeoutOrNull(timeoutMs) {
            val content = deps.readLocalSubtitleText(uriOrPath) ?: return@withTimeoutOrNull null
            content.takeIf { deps.validateSubtitleContent(it, uriOrPath) }
        }
    }

    private suspend fun downloadValidateAndCache(
        url: String,
        headers: Map<String, String>,
        languageHint: String?,
        cachePrefix: String,
    ): String? {
        val text = try {
            deps.fetchSubtitleText(url, headers, languageHint)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            startupSubLog.w("Subtitle download failed url=$url reason=${error.message}")
            null
        } ?: return null

        if (!deps.validateSubtitleContent(text, url)) {
            startupSubLog.w("Rejected invalid/empty subtitle content url=$url")
            return null
        }

        val extension = PlayerSubtitleCueParser.fileExtension(text, url)
        val fileName = "$cachePrefix${stableHash(url)}.$extension"
        return deps.writeCachedSubtitleText(fileName, text)
    }

    private suspend fun cachedAddonsIfFresh(key: String): List<AddonSubtitle>? =
        cacheMutex.withLock {
            val entry = cachedAddonBatches[key] ?: return@withLock null
            if (deps.nowMs() - entry.first <= ADDON_PREFETCH_TTL_MS) {
                entry.second
            } else {
                cachedAddonBatches.remove(key)
                null
            }
        }

    private suspend fun storeCachedAddons(key: String, addons: List<AddonSubtitle>) {
        cacheMutex.withLock {
            cachedAddonBatches[key] = deps.nowMs() to addons
        }
    }

    private suspend fun markUrlFailed(url: String) {
        val now = deps.nowMs()
        cacheMutex.withLock {
            failedUrlsWithTimestamp[url] = now
        }
    }

    private fun loadPersistedPreference(parentMetaId: String): PersistedPlayerTrackPreference? {
        val trimmed = parentMetaId.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { deps.loadPersistedTrackPreference(trimmed) }.getOrNull()
    }

    private fun buildSelectionPlan(
        settings: PlayerSettingsUiState,
        persistedPreference: PersistedPlayerTrackPreference?,
        audioLanguageHint: String?,
    ): StartupSelectionPlan {
        if (persistedPreference?.subtitleType == PersistedSubtitleSelectionType.DISABLED) {
            return StartupSelectionPlan(disabled = true)
        }

        val deviceLanguages = deps.deviceLanguages()
        val preferredAudioTargets = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = settings.preferredAudioLanguage,
            secondaryPreferredAudioLanguage = settings.secondaryPreferredAudioLanguage,
            deviceLanguages = deviceLanguages,
        )
        val effectiveAudioTrack = audioLanguageHint?.takeIf { it.isNotBlank() }?.let { lang ->
            AudioTrack(index = 0, id = "audio", label = lang, language = lang, isSelected = true)
        } ?: preferredAudioTargets.firstOrNull()?.let { target ->
            AudioTrack(index = 0, id = "audio", label = target, language = target, isSelected = true)
        }
        val subtitleTargets = resolvePreferredSubtitleLanguageTargets(
            preferredSubtitleLanguage = settings.preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = settings.secondaryPreferredSubtitleLanguage,
            deviceLanguages = deviceLanguages,
        )
        if (subtitleTargets.isEmpty() && settings.preferredSubtitleLanguage == SubtitleLanguageOption.NONE &&
            persistedPreference == null
        ) {
            return StartupSelectionPlan(disabled = true)
        }
        val basePlan = resolveSubtitleAutoSelectionPlan(
            selectedAudioTrack = effectiveAudioTrack,
            preferredAudioTargets = preferredAudioTargets,
            preferredSubtitleTargets = subtitleTargets,
            useForcedSubtitles = settings.subtitleStyle.useForcedSubtitles ||
                settings.preferredSubtitleLanguage == SubtitleLanguageOption.FORCED,
        )

        if (basePlan == null && persistedPreference == null) {
            return StartupSelectionPlan(disabled = true)
        }

        return StartupSelectionPlan(
            disabled = false,
            persistedPreference = persistedPreference,
            mode = basePlan?.mode ?: SubtitleAutoSelectionMode.NORMAL_ONLY,
            targets = basePlan?.targets.orEmpty(),
        )
    }
}

internal data class StartupSelectionPlan(
    val disabled: Boolean = false,
    val persistedPreference: PersistedPlayerTrackPreference? = null,
    val mode: SubtitleAutoSelectionMode = SubtitleAutoSelectionMode.NORMAL_ONLY,
    val targets: List<String> = emptyList(),
)

internal fun isValidSubtitleContent(text: String?, sourceUrl: String?): Boolean {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    if (looksLikeErrorPayload(trimmed, sourceUrl)) return false
    return PlayerSubtitleCueParser.parse(trimmed, sourceUrl).isNotEmpty()
}

private fun looksLikeErrorPayload(trimmed: String, sourceUrl: String?): Boolean {
    val head = trimmed.take(256).lowercase()
    if (head.startsWith("{") && ("\"error" in head || "\"message" in head)) return true
    val isTtmlUrl = sourceUrl?.let {
        it.substringBefore('?').endsWith(".ttml", ignoreCase = true) ||
            it.substringBefore('?').endsWith(".dfxp", ignoreCase = true) ||
            it.substringBefore('?').endsWith(".xml", ignoreCase = true)
    } == true
    if (!isTtmlUrl && (head.startsWith("<!doctype html") || head.startsWith("<html") || "<html" in head)) {
        return true
    }
    return false
}

internal fun rankMatchingCandidates(
    candidates: List<StartupSubtitleCandidate>,
    plan: StartupSelectionPlan,
    settings: PlayerSettingsUiState,
    audioLanguageHint: String? = null,
): List<StartupSubtitleCandidate> {
    if (plan.disabled || candidates.isEmpty()) return emptyList()

    val persisted = plan.persistedPreference
    if (persisted != null && persisted.subtitleType == PersistedSubtitleSelectionType.ADDON) {
        val addonSubtitles = candidates.map { it.toAddonSubtitle() }
        val exactPersisted = findPersistedAddonSubtitle(addonSubtitles, persisted)
        if (exactPersisted != null) {
            val matchedCandidate = candidates.firstOrNull { it.url == exactPersisted.url }
            if (matchedCandidate != null) {
                val others = rankByPlan(
                    candidates.filterNot { it.url == matchedCandidate.url },
                    plan,
                    settings,
                    audioLanguageHint,
                )
                return listOf(matchedCandidate) + others
            }
        }
    }

    return rankByPlan(candidates, plan, settings, audioLanguageHint)
}

private fun rankByPlan(
    candidates: List<StartupSubtitleCandidate>,
    plan: StartupSelectionPlan,
    settings: PlayerSettingsUiState,
    audioLanguageHint: String?,
): List<StartupSubtitleCandidate> {
    if (plan.mode == SubtitleAutoSelectionMode.FORCED_ONLY) {
        val targets = plan.targets.ifEmpty {
            listOfNotNull(audioLanguageHint?.let { SubtitleLanguageMatching.normalizeLanguageCode(it) })
        }
        return candidates.filter { candidate ->
            candidate.isForced && targets.any { target -> candidateMatchesTarget(candidate, target) }
        }.sortedByDescending { it.isLocal || it.isStreamAttachment }
    }

    val targets = plan.targets
    if (targets.isEmpty()) {
        val persistedLang = plan.persistedPreference?.subtitleLanguage?.takeIf { it.isNotBlank() }
        return if (persistedLang != null) {
            candidates.filter { candidateMatchesTarget(it, persistedLang) }
        } else {
            emptyList()
        }
    }

    val nonForced = candidates.filterNot { it.isForced }
    val ordered = ArrayList<StartupSubtitleCandidate>()
    val seenUrls = HashSet<String>()

    targets.forEach { target ->
        val rawMatching = nonForced.filter { candidateMatchesTarget(it, target) }
        val sortedForTarget = rawMatching.sortedWith(
            compareByDescending<StartupSubtitleCandidate> { matchesRegionalVariantPreference(it, target) }
                .thenByDescending { it.isLocal }
                .thenByDescending { it.isStreamAttachment }
                .thenByDescending { matchesPreferredTextStyleHint(it, settings) },
        )
        sortedForTarget.forEach { candidate ->
            if (seenUrls.add(candidate.url)) {
                ordered.add(candidate)
            }
        }
    }

    return ordered
}

private fun isPrimaryPreferenceMatch(
    candidate: StartupSubtitleCandidate,
    plan: StartupSelectionPlan,
): Boolean {
    val persisted = plan.persistedPreference
    if (persisted?.subtitleType == PersistedSubtitleSelectionType.ADDON) {
        if (!persisted.addonSubtitleUrl.isNullOrBlank() && candidate.url == persisted.addonSubtitleUrl) {
            return true
        }
        if (!persisted.addonSubtitleId.isNullOrBlank() && candidate.id == persisted.addonSubtitleId) {
            return true
        }
        val persistedLang = persisted.subtitleLanguage?.takeIf { it.isNotBlank() }
        if (persistedLang != null && candidateMatchesTarget(candidate, persistedLang)) {
            return true
        }
    }
    val primaryTarget = plan.targets.firstOrNull() ?: return false
    if (plan.mode == SubtitleAutoSelectionMode.FORCED_ONLY) {
        return candidate.isForced && candidateMatchesTarget(candidate, primaryTarget)
    }
    return !candidate.isForced && candidateMatchesTarget(candidate, primaryTarget)
}

private fun candidateMatchesTarget(
    candidate: StartupSubtitleCandidate,
    target: String,
): Boolean = addonSubtitleMatchesLanguage(candidate.toAddonSubtitle(), target)

private fun matchesRegionalVariantPreference(
    candidate: StartupSubtitleCandidate,
    target: String,
): Boolean {
    val normalizedTarget = SubtitleLanguageMatching.normalizeLanguageCode(target)
    val detected = SubtitleLanguageMatching.detectTrackLanguageVariant(
        language = candidate.language,
        name = candidate.name,
        trackId = candidate.id,
    )
    return detected == normalizedTarget
}

private fun matchesPreferredTextStyleHint(
    candidate: StartupSubtitleCandidate,
    settings: PlayerSettingsUiState,
): Boolean {
    if (!settings.subtitleStyle.stripSdh) return true
    val label = candidate.name.orEmpty().lowercase()
    return "sdh" !in label && "cc" !in label && "hearing" !in label
}

private fun StreamSubtitle.toCandidate(
    id: String,
    isStreamAttachment: Boolean,
): StartupSubtitleCandidate {
    val forced = inferForcedSubtitleTrack(
        label = name ?: url.substringAfterLast('/'),
        language = language,
        trackId = id,
    )
    return StartupSubtitleCandidate(
        id = id,
        url = url,
        language = language,
        name = name,
        headers = headers.orEmpty(),
        isForced = forced,
        isLocal = url.isLocalSubtitleUri(),
        isStreamAttachment = isStreamAttachment,
    )
}

private fun AddonSubtitle.toCandidate(): StartupSubtitleCandidate =
    StartupSubtitleCandidate(
        id = id,
        url = url,
        language = language,
        name = display,
        addonName = addonName,
        isForced = addonSubtitleIsForced(this),
        isLocal = url.isLocalSubtitleUri(),
        isStreamAttachment = false,
    )

private fun StartupSubtitleCandidate.toAddonSubtitle(): AddonSubtitle =
    AddonSubtitle(
        id = id,
        url = url,
        language = language,
        display = name ?: SubtitleLanguageMatching.languageCodeToName(language),
        addonName = addonName,
    )

private fun stableHash(value: String): String {
    var hash = 0xcbf29ce484222325UL
    val prime = 0x100000001b3UL
    for (ch in value) {
        hash = hash xor ch.code.toULong()
        hash *= prime
    }
    return hash.toString(16)
}

private fun addonCacheKey(request: StartupSubtitleRequest): String =
    "${request.type}|${request.videoId}|${request.videoHash.orEmpty()}|${request.filename.orEmpty()}"

private fun prefetchKey(request: StartupSubtitleRequest): String {
    val lang = request.settings.preferredSubtitleLanguage.lowercase()
    val secondary = request.settings.secondaryPreferredSubtitleLanguage?.lowercase().orEmpty()
    return "${request.type}|${request.videoId}|${request.parentMetaId}|$lang|$secondary|${request.settings.subtitleStyle.useForcedSubtitles}"
}

/**
 * Production coordinator for pre-playback subtitle loading, caching, validation, and local resolution.
 */
internal object SubtitleStartupCoordinator {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val preparer = StartupSubtitlePreparer(
        StartupSubtitleDependencies(
            fetchAddonSubtitlesStreaming = { request, onBatchLoaded ->
                val extras = SubtitleRequestExtras(
                    videoHash = request.videoHash,
                    videoSize = request.videoSizeBytes,
                    filename = request.filename,
                )
                val requests = addonSubtitleRequests(
                    type = request.type,
                    videoId = request.videoId,
                    extras = extras,
                )
                if (requests.isEmpty()) {
                    emptyList()
                } else {
                    loadAddonSubtitles(requests) { _, batch ->
                        if (batch.isNotEmpty()) {
                            onBatchLoaded(batch)
                        }
                    }
                }
            },
            fetchSubtitleText = { url, headers, languageHint ->
                val response = com.nuvio.app.features.autosync.AutoSyncSubtitleHttp.get(
                    url = url,
                    headers = mapOf("Accept" to "*/*") + headers,
                    maxResponseBodyBytes = DEFAULT_MAX_SUBTITLE_FILE_BYTES,
                    languageHint = languageHint,
                )
                if (response.status in 200..299 && !response.body.endsWith("\n...[truncated]")) {
                    response.body
                } else {
                    null
                }
            },
            readLocalSubtitleText = { uriOrPath ->
                SubtitleLocalFilePlatform.readLocalSubtitleText(
                    uriOrPath = uriOrPath,
                    maxBytes = DEFAULT_MAX_SUBTITLE_FILE_BYTES,
                )
            },
            writeCachedSubtitleText = { fileName, content ->
                SubtitleLocalFilePlatform.writeCachedSubtitleText(fileName, content)
            },
        ),
    )

    suspend fun prepareDownloadedSubtitles(
        localSubtitles: List<StreamSubtitle>,
        settings: PlayerSettingsUiState,
        parentMetaId: String,
        audioLanguageHint: String? = null,
    ): PreparedStartupSubtitles =
        preparer.prepareDownloadedSubtitles(
            localSubtitles = localSubtitles,
            settings = settings,
            parentMetaId = parentMetaId,
            audioLanguageHint = audioLanguageHint,
        )

    fun prefetchForStream(request: StartupSubtitleRequest): Deferred<PreparedStartupSubtitles> =
        preparer.prefetchForStream(backgroundScope, request)

    suspend fun <T> prepareStreamInParallelWithSource(
        request: StartupSubtitleRequest,
        resolveSource: suspend () -> T,
        extractStreamSubtitles: (T) -> List<StreamSubtitle> = { emptyList() },
    ): Pair<T, PreparedStartupSubtitles> =
        preparer.prepareStreamInParallelWithSource(
            request = request,
            resolveSource = resolveSource,
            extractStreamSubtitles = extractStreamSubtitles,
        )

    suspend fun prepareStreamSubtitles(request: StartupSubtitleRequest): PreparedStartupSubtitles =
        preparer.prepareStreamSubtitles(request)

    suspend fun resolvePlayableLocalSubtitle(
        url: String,
        headers: Map<String, String> = emptyMap(),
        languageHint: String? = null,
    ): String? =
        preparer.resolvePlayableLocalSubtitle(
            url = url,
            headers = headers,
            languageHint = languageHint,
        )

    suspend fun cachedLocalUriForRemoteUrl(remoteUrl: String): String? =
        preparer.cachedValidLocalUri(remoteUrl)

    suspend fun isRecentlyFailed(remoteUrl: String): Boolean =
        preparer.isRecentlyFailed(remoteUrl)
}
