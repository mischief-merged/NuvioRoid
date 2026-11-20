package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveRaw
import com.nuvio.app.features.streams.StreamClientResolveStream
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException

internal sealed interface AddonSourceLookupResult {
    data class Streams(val streams: List<StreamItem>) : AddonSourceLookupResult
    data object Unavailable : AddonSourceLookupResult
    data class Failed(val message: String? = null) : AddonSourceLookupResult
}

/**
 * Re-resolves the exact same source of a [DownloadItem] when its download URL expires or returns
 * 403/404/410 mid-transfer: first directly via [DirectDebridPlaybackResolver] when the source was
 * locally debrid-resolved, and otherwise (or as fallback) by re-fetching from the same add-on and
 * matching the same release/file identity (add-on, name, size, info hash where present).
 */
internal class DownloadSourceRefresher(
    private val shouldResolveDebrid: (StreamItem) -> Boolean = DirectDebridPlaybackResolver::shouldResolveToPlayableStream,
    private val resolveDebrid: suspend (StreamItem, Int?, Int?, Boolean) -> DirectDebridPlayableResult = { stream, season, episode, forceRefresh ->
        DirectDebridPlaybackResolver.resolveToPlayableStream(stream, season, episode, forceRefresh)
    },
    private val fetchSameAddonStreams: suspend (DownloadItem) -> AddonSourceLookupResult = ::defaultFetchSameAddonStreams,
    private val isSupportedUrl: (String) -> Boolean = String::isSupportedDownloadUrl,
    private val isPublicHost: (String) -> Boolean = String::hasPublicDownloadHost,
) {
    suspend fun refresh(item: DownloadItem): SourceLinkRefreshResult {
        var sawTemporaryError = false
        var lastErrorMessage: String? = null

        val localCandidate = item.toUnresolvedDebridStream()
        if (localCandidate != null && shouldResolveDebrid(localCandidate)) {
            when (val resolved = resolveDebrid(localCandidate, item.seasonNumber, item.episodeNumber, true)) {
                is DirectDebridPlayableResult.Success -> {
                    resolved.stream.toFreshRefreshedLink(item)?.let { return SourceLinkRefreshResult.Refreshed(it) }
                }
                DirectDebridPlayableResult.Error -> sawTemporaryError = true
                DirectDebridPlayableResult.MissingApiKey,
                DirectDebridPlayableResult.NotCached,
                DirectDebridPlayableResult.Stale,
                -> Unit
            }
        }

        val lookup = try {
            fetchSameAddonStreams(item)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AddonSourceLookupResult.Failed(error.message)
        }
        val streams = when (lookup) {
            is AddonSourceLookupResult.Streams -> lookup.streams
            is AddonSourceLookupResult.Failed -> {
                sawTemporaryError = true
                lastErrorMessage = lookup.message
                emptyList()
            }
            AddonSourceLookupResult.Unavailable -> emptyList()
        }

        val matched = findMatchingSourceForRefresh(item, streams, shouldResolveDebrid, isSupportedUrl)
            ?: return if (sawTemporaryError) SourceLinkRefreshResult.Failed(lastErrorMessage) else SourceLinkRefreshResult.Unavailable

        val resolvedStream = if (shouldResolveDebrid(matched) || matched.debridResolved) {
            val unresolved = if (matched.debridResolved) matched.copy(url = null, debridResolved = false) else matched
            if (!shouldResolveDebrid(unresolved)) {
                return if (sawTemporaryError) SourceLinkRefreshResult.Failed(lastErrorMessage) else SourceLinkRefreshResult.Unavailable
            }
            when (val resolved = resolveDebrid(unresolved, item.seasonNumber, item.episodeNumber, true)) {
                is DirectDebridPlayableResult.Success -> resolved.stream
                DirectDebridPlayableResult.Error -> return SourceLinkRefreshResult.Failed(lastErrorMessage)
                DirectDebridPlayableResult.MissingApiKey,
                DirectDebridPlayableResult.NotCached,
                DirectDebridPlayableResult.Stale,
                -> return SourceLinkRefreshResult.Unavailable
            }
        } else {
            matched
        }

        val refreshed = resolvedStream.toFreshRefreshedLink(item)
            ?: return if (sawTemporaryError) SourceLinkRefreshResult.Failed(lastErrorMessage) else SourceLinkRefreshResult.Unavailable
        return SourceLinkRefreshResult.Refreshed(refreshed)
    }

    private fun StreamItem.toFreshRefreshedLink(item: DownloadItem): RefreshedSourceLink? {
        val url = playableDirectUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!isSupportedUrl(url)) return null
        if (item.autoDownloadRuleKey != null && !isPublicHost(url)) return null
        val requestHeaders = sanitizeDownloadRequestHeaders(behaviorHints.proxyHeaders?.request)
        val responseHeaders = sanitizeDownloadResponseHeaders(behaviorHints.proxyHeaders?.response)
        if (url == item.sourceUrl && requestHeaders == item.sourceHeaders) return null
        return RefreshedSourceLink(
            url = url,
            requestHeaders = requestHeaders,
            responseHeaders = responseHeaders,
            filename = behaviorHints.filename ?: clientResolve?.filename ?: clientResolve?.stream?.raw?.filename,
            videoSize = behaviorHints.videoSize ?: clientResolve?.stream?.raw?.size,
        )
    }
}

internal fun findMatchingSourceForRefresh(
    item: DownloadItem,
    streams: List<StreamItem>,
    shouldResolveDebrid: (StreamItem) -> Boolean = DirectDebridPlaybackResolver::shouldResolveToPlayableStream,
    isSupportedUrl: (String) -> Boolean = String::isSupportedDownloadUrl,
): StreamItem? {
    if (streams.isEmpty()) return null
    val expectedAddonId = item.providerAddonId?.trim()?.takeIf { it.isNotEmpty() }
    val expectedAddonName = item.providerName.trim()
    val expectedHash = item.sourceInfoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val expectedFileIdx = item.sourceFileIdx ?: item.sourceClientResolve?.fileIdx
    val expectedFilename = item.sourceFilename?.normalizedFilenameOrNull()
    val expectedStructuredSize = item.sourceFileSizeBytes?.takeIf { it > 0L }
    val expectedTextSize = claimedSizeFromSourceText(item.streamTitle, item.streamSubtitle)
    val expectedBingeGroup = item.sourceBingeGroup?.trim()?.takeIf { it.isNotEmpty() }
    val expectedTitle = normalizeSourceText(item.streamTitle)
    val expectedSubtitle = normalizeSourceText(item.streamSubtitle)
    val expectedRelease = extractSourceReleaseKey(item.streamSubtitle)

    return streams.asSequence().mapNotNull { candidate ->
        val playableUrl = candidate.playableDirectUrl?.trim()?.takeIf { it.isNotBlank() }
        val resolvable = shouldResolveDebrid(candidate) || candidate.debridResolved
        if (playableUrl == null && !resolvable) return@mapNotNull null
        if (playableUrl != null && !resolvable && !isSupportedUrl(playableUrl)) return@mapNotNull null

        val addonMatches = if (expectedAddonId != null) {
            candidate.addonId.trim() == expectedAddonId
        } else {
            candidate.addonName.trim() == expectedAddonName
        }
        if (!addonMatches) return@mapNotNull null

        val candidateHash = candidate.p2pInfoHash?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (expectedHash != null && candidateHash != expectedHash) return@mapNotNull null

        val candidateIndices = listOfNotNull(candidate.p2pFileIdx, candidate.clientResolve?.fileIdx).distinct()
        if (expectedFileIdx != null && candidateIndices.any { it != expectedFileIdx }) return@mapNotNull null
        val candidateIdx = candidate.p2pFileIdx ?: candidate.clientResolve?.fileIdx

        val candidateFilename = (
            candidate.behaviorHints.filename
                ?: candidate.clientResolve?.filename
                ?: candidate.clientResolve?.stream?.raw?.filename
            )?.normalizedFilenameOrNull()
        if (expectedFilename != null && candidateFilename != null && !filenamesMatch(expectedFilename, candidateFilename)) {
            return@mapNotNull null
        }

        val candidateBingeGroup = candidate.behaviorHints.bingeGroup?.trim()?.takeIf { it.isNotEmpty() }
        if (expectedBingeGroup != null && candidateBingeGroup != null && candidateBingeGroup != expectedBingeGroup) {
            return@mapNotNull null
        }

        val candidateStructuredSize = (
            candidate.behaviorHints.videoSize
                ?: candidate.clientResolve?.stream?.raw?.size
                ?: candidate.debridCacheStatus?.cachedSize
            )?.takeIf { it > 0L }
        if (expectedStructuredSize != null && candidateStructuredSize != null && candidateStructuredSize != expectedStructuredSize) {
            return@mapNotNull null
        }

        val candidateTextSize = claimedSizeFromSourceText(candidate.streamLabel, candidate.streamSubtitle)
        if (expectedTextSize != null && candidateTextSize != null && candidateTextSize != expectedTextSize) {
            return@mapNotNull null
        }

        val hashMatched = expectedHash != null && candidateHash == expectedHash
        val filenameMatched = expectedFilename != null && candidateFilename != null && filenamesMatch(expectedFilename, candidateFilename)
        val candidateTitle = normalizeSourceText(candidate.streamLabel)
        val candidateSubtitle = normalizeSourceText(candidate.streamSubtitle)
        val candidateRelease = extractSourceReleaseKey(candidate.streamSubtitle)
        val subtitleCompatible = when {
            expectedSubtitle.isEmpty() && candidateSubtitle.isEmpty() -> true
            expectedSubtitle.isNotEmpty() && candidateSubtitle.isNotEmpty() ->
                candidateSubtitle == expectedSubtitle || (expectedRelease.isNotEmpty() && candidateRelease == expectedRelease)
            else -> false
        }
        val labelMatched = candidateTitle == expectedTitle && subtitleCompatible

        if (!hashMatched && !filenameMatched && !labelMatched) return@mapNotNull null
        if (hashMatched && candidateFilename == null && expectedFilename != null &&
            expectedRelease.isNotEmpty() && candidateRelease.isNotEmpty() && candidateRelease != expectedRelease &&
            !item.sourceDebridResolved
        ) {
            return@mapNotNull null
        }

        var score = 0
        if (hashMatched) score += 100
        if (expectedFileIdx != null && candidateIdx == expectedFileIdx) score += 80
        if (filenameMatched) score += 70
        if (expectedStructuredSize != null && candidateStructuredSize == expectedStructuredSize) score += 50
        if (expectedTextSize != null && candidateTextSize == expectedTextSize) score += 40
        if (candidateTitle == expectedTitle) score += 30
        if (expectedSubtitle.isNotEmpty() && candidateSubtitle == expectedSubtitle) score += 25
        else if (expectedRelease.isNotEmpty() && candidateRelease == expectedRelease) score += 20
        if (playableUrl != null && playableUrl != item.sourceUrl) score += 10
        score to candidate
    }.maxByOrNull { (score, _) -> score }?.second
}

internal fun StreamItem.toDownloadClientResolve(): DownloadClientResolve? {
    val resolve = clientResolve
    val hasDebridInfo = resolve != null || debridResolved || p2pInfoHash != null || torrentMagnetUri != null || sources.isNotEmpty()
    if (!hasDebridInfo) return null
    return DownloadClientResolve(
        type = resolve?.type,
        infoHash = (resolve?.infoHash ?: infoHash ?: p2pInfoHash)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
        fileIdx = resolve?.fileIdx ?: p2pFileIdx,
        magnetUri = resolve?.magnetUri ?: torrentMagnetUri,
        sources = resolve?.sources?.takeIf { it.isNotEmpty() } ?: sources,
        torrentName = resolve?.torrentName,
        filename = resolve?.filename ?: behaviorHints.filename,
        season = resolve?.season,
        episode = resolve?.episode,
        service = resolve?.service,
        isCached = resolve?.isCached,
        rawFilename = resolve?.stream?.raw?.filename,
        rawSize = resolve?.stream?.raw?.size,
        debridProviderId = debridCacheStatus?.providerId,
        debridProviderName = debridCacheStatus?.providerName,
    )
}

internal fun DownloadItem.toUnresolvedDebridStream(): StreamItem? {
    val resolve = sourceClientResolve
    val directDebrid = resolve?.type.equals("debrid", ignoreCase = true) && !resolve?.service.isNullOrBlank()
    if (!sourceDebridResolved && !directDebrid) return null
    val magnet = resolve?.magnetUri?.takeIf { it.trimStart().startsWith("magnet:", ignoreCase = true) }
    val clientResolve = resolve?.takeIf { !it.type.isNullOrBlank() || !it.service.isNullOrBlank() }?.let {
        StreamClientResolve(
            type = it.type,
            infoHash = it.infoHash ?: sourceInfoHash,
            fileIdx = it.fileIdx ?: sourceFileIdx,
            magnetUri = it.magnetUri,
            sources = it.sources,
            torrentName = it.torrentName,
            filename = it.filename ?: sourceFilename,
            season = it.season ?: seasonNumber,
            episode = it.episode ?: episodeNumber,
            service = it.service,
            isCached = it.isCached,
            stream = if (it.rawFilename != null || it.rawSize != null) {
                StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        filename = it.rawFilename,
                        size = it.rawSize,
                    ),
                )
            } else {
                null
            },
        )
    }
    val cacheStatus = resolve?.debridProviderId?.let { providerId ->
        StreamDebridCacheStatus(
            providerId = providerId,
            providerName = resolve.debridProviderName.orEmpty(),
            state = StreamDebridCacheState.CACHED,
        )
    }
    return StreamItem(
        name = streamTitle,
        description = streamSubtitle,
        url = magnet,
        infoHash = sourceInfoHash ?: resolve?.infoHash,
        fileIdx = sourceFileIdx ?: resolve?.fileIdx,
        sources = resolve?.sources.orEmpty(),
        addonName = providerName,
        addonId = providerAddonId.orEmpty(),
        clientResolve = clientResolve,
        debridCacheStatus = cacheStatus,
        behaviorHints = StreamBehaviorHints(
            bingeGroup = sourceBingeGroup,
            videoHash = expectedVideoHash,
            videoSize = sourceFileSizeBytes,
            filename = sourceFilename,
            proxyHeaders = if (sourceHeaders.isEmpty() && sourceResponseHeaders.isEmpty()) {
                null
            } else {
                StreamProxyHeaders(
                    request = sourceHeaders.takeIf { it.isNotEmpty() },
                    response = sourceResponseHeaders.takeIf { it.isNotEmpty() },
                )
            },
        ),
        debridResolved = false,
    )
}

private suspend fun defaultFetchSameAddonStreams(item: DownloadItem): AddonSourceLookupResult {
    if (item.providerAddonId == "embedded") {
        return AddonSourceLookupResult.Streams(MetaDetailsRepository.findEmbeddedStreams(item.videoId))
    }
    val addons = AddonRepository.uiState.value.addons.enabledAddons()
    val addonId = item.providerAddonId?.trim()?.takeIf { it.isNotEmpty() }
    val addon = if (addonId != null) {
        addons.firstOrNull { candidate ->
            val manifest = candidate.manifest ?: return@firstOrNull false
            candidate.streamAddonInstanceId(manifest.id) == addonId
        }
    } else {
        val name = item.providerName.trim()
        addons.firstOrNull { candidate ->
            val manifest = candidate.manifest ?: return@firstOrNull false
            candidate.displayTitle.ifBlank { manifest.name }.trim() == name
        }
    } ?: return AddonSourceLookupResult.Unavailable

    val manifest = addon.manifest ?: return AddonSourceLookupResult.Unavailable
    val type = item.contentType.trim().ifBlank { if (item.isEpisode) "series" else item.parentMetaType.trim() }
    val streamId = if (!item.isEpisode) resolveMovieStreamId(item.videoId) else item.videoId
    if (!manifest.supportsStream(type, streamId)) return AddonSourceLookupResult.Unavailable

    val url = buildAddonResourceUrl(
        manifestUrl = manifest.transportUrl,
        resource = "stream",
        type = type,
        id = streamId,
    )
    return try {
        val payload = fetchAddonResponseText(url = url, forceRefresh = true)
        val parsed = StreamParser.parse(
            payload = payload,
            addonName = addon.displayTitle.ifBlank { manifest.name },
            addonId = addon.streamAddonInstanceId(manifest.id),
            addonLogo = manifest.logoUrl,
        )
        AddonSourceLookupResult.Streams(parsed)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        AddonSourceLookupResult.Failed(error.message)
    }
}

private suspend fun resolveMovieStreamId(videoId: String): String {
    val tmdbId = videoId.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.toIntOrNull() ?: return videoId
    return runCatchingUnlessCancelled { TmdbService.tmdbToImdb(tmdbId, "movie") }.getOrNull() ?: videoId
}

internal fun sanitizeDownloadRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

internal fun sanitizeDownloadResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private val whitespaceRun = Regex("\\s+")
private val peerCountToken = Regex("""👤\s*\d+""")

private fun normalizeSourceText(text: String?): String =
    text.orEmpty().trim().lowercase().replace(whitespaceRun, " ")

private fun extractSourceReleaseKey(subtitle: String?): String {
    val firstLine = subtitle
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    return firstLine
        .replace(peerCountToken, "")
        .lowercase()
        .replace(whitespaceRun, " ")
        .trim()
}

private fun String.normalizedFilenameOrNull(): String? =
    trim().replace('\\', '/').trimStart('/').lowercase().takeIf { it.isNotEmpty() }

private fun filenamesMatch(expected: String, candidate: String): Boolean =
    expected == candidate || expected.substringAfterLast('/') == candidate.substringAfterLast('/')
