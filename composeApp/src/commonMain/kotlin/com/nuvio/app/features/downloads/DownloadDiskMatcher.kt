package com.nuvio.app.features.downloads

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a file found in a download folder was not re-added as a completed download. */
enum class UnrecognisedFileReason {
    NoCompanionFolder,
    ForeignCompanionFolder,
    EpisodeMismatch,
    AmbiguousName,
    UnreadableCompanion,
    NotAVideoFile,
}

/** A file on disk in a download location that could not be matched confidently. Never moved or deleted. */
data class UnrecognisedFile(
    val localFileUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val locationId: String? = null,
    val locationName: String? = null,
    val reason: UnrecognisedFileReason,
)

/** What scanning all connected storage locations found. */
internal data class DiskScanResult(
    val recoveredItems: List<DownloadItem> = emptyList(),
    val unrecognisedFiles: List<UnrecognisedFile> = emptyList(),
    val busy: Boolean = false,
)

/** Summary shown to the user after tapping "Find downloads on disk". */
internal data class DiskScanSummary(
    val addedCount: Int = 0,
    val unrecognisedCount: Int = 0,
    val skippedBusy: Boolean = false,
)

/** What was read from a candidate video's `<video>.subtitles/` companion folder. */
internal data class CompanionSnapshot(
    val episodes: SavedEpisodes? = null,
    val episodesPresent: Boolean = false,
    val episodesUnreadable: Boolean = false,
    val manifest: SubtitleManifest? = null,
    val manifestPresent: Boolean = false,
    val manifestUnreadable: Boolean = false,
    val hasSkipTimes: Boolean = false,
    val hasArtwork: Boolean = false,
    val isOwnedByMarker: Boolean = false,
    val foreignOrInvalid: Boolean = false,
) {
    val hasPositiveEvidence: Boolean
        get() = episodes != null || manifest != null || hasSkipTimes || hasArtwork || isOwnedByMarker
}

internal data class ParsedVideoFileName(
    val baseTitle: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val extractedDownloadId: String?,
    val hasAmbiguousEpisodes: Boolean,
)

internal data class DiskCandidate(
    val fileName: String,
    val localFileUri: String,
    val sizeBytes: Long,
    val locationId: String?,
    val locationName: String?,
    val lastModifiedEpochMs: Long,
    val isVideoContainer: Boolean,
    val startsLikeWebPage: Boolean,
    val companion: CompanionSnapshot?,
)

internal sealed interface MatchOutcome {
    data class Matched(val item: DownloadItem) : MatchOutcome
    data class Unrecognised(val reason: UnrecognisedFileReason) : MatchOutcome
}

private val CollisionSuffixRegex = Regex("""^(.+?) \((\d{1,3})\)$""")
private val Base36DownloadIdRegex = Regex("""^(.+)_([0-9a-z]{7,12}_[0-9a-z]{1,6})$""")
private val UuidDownloadIdRegex = Regex("""^(.+)_([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$""")
private val EpisodeMarkerRegex = Regex(
    "(?<![A-Za-z0-9])S(\\d{1,3})E(\\d{1,3})((?:-?E\\d{1,3}|-\\d{1,3}(?![0-9pPiIkK]))*)(?!\\d)",
    RegexOption.IGNORE_CASE,
)
private val ImdbIdRegex = Regex("""(?<![A-Za-z0-9])(tt\d{6,10})(?![A-Za-z0-9])""")
private val NonFileNameCharsRegex = Regex("[^A-Za-z0-9._ -]")
private val StrictManifestJson = Json { ignoreUnknownKeys = true }

/**
 * Parses a Nuvio `manifest.json` strictly enough that an empty `{}` or unrelated JSON object
 * is rejected instead of decoded with defaults.
 */
internal fun parseSubtitleManifestStrict(text: String): SubtitleManifest? = runCatching {
    val root = StrictManifestJson.parseToJsonElement(text) as? JsonObject ?: return null
    val tracks = root["tracks"] as? JsonArray ?: return null
    val complete = root["complete"]
    if (complete != null && complete !is JsonPrimitive) return null
    StrictManifestJson.decodeFromJsonElement(SubtitleManifest.serializer(), root)
        .takeIf { it.tracks.size == tracks.size }
}.getOrNull()

/** All distinct IMDb IDs referenced across subtitle URLs. */
internal fun extractAllImdbIdsFromManifest(manifest: SubtitleManifest): Set<String> {
    val urls = manifest.tracks.map { it.sourceUrl } + manifest.failed.map { it.sourceUrl }
    return urls.flatMap { url -> ImdbIdRegex.findAll(url).map { it.groupValues[1] } }
        .distinctBy { it.lowercase() }
        .toSet()
}

/** Returns the single IMDb ID referenced across subtitle URLs if all distinct IDs agree. */
internal fun extractImdbIdFromManifest(manifest: SubtitleManifest): String? =
    extractAllImdbIdsFromManifest(manifest).singleOrNull()

internal fun parseVideoFileName(fileName: String): ParsedVideoFileName {
    val lastDot = fileName.lastIndexOf('.')
    val stemWithoutExt = if (lastDot > 0) fileName.substring(0, lastDot) else fileName

    val collisionMatch = CollisionSuffixRegex.matchEntire(stemWithoutExt)
    val stemWithoutCollision = if (collisionMatch != null && (collisionMatch.groupValues[2].toIntOrNull() ?: 0) in 2..999) {
        collisionMatch.groupValues[1]
    } else {
        stemWithoutExt
    }

    var extractedId: String? = null
    val coreTitle = run {
        val base36Match = Base36DownloadIdRegex.matchEntire(stemWithoutCollision)
        if (base36Match != null && base36Match.groupValues[2].substringBefore('_').any { it.isDigit() }) {
            extractedId = base36Match.groupValues[2]
            base36Match.groupValues[1]
        } else {
            val uuidMatch = UuidDownloadIdRegex.matchEntire(stemWithoutCollision)
            if (uuidMatch != null) {
                extractedId = uuidMatch.groupValues[2]
                uuidMatch.groupValues[1]
            } else {
                stemWithoutCollision
            }
        }
    }

    val episodeMatches = EpisodeMarkerRegex.findAll(coreTitle).toList()
    if (episodeMatches.size > 1) {
        return ParsedVideoFileName(
            baseTitle = cleanTitleSegment(coreTitle),
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            extractedDownloadId = extractedId,
            hasAmbiguousEpisodes = true,
        )
    }
    if (episodeMatches.size == 1) {
        val match = episodeMatches.single()
        if (match.groupValues[3].isNotEmpty()) {
            return ParsedVideoFileName(
                baseTitle = cleanTitleSegment(coreTitle.substring(0, match.range.first)),
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                extractedDownloadId = extractedId,
                hasAmbiguousEpisodes = true,
            )
        }
        val season = match.groupValues[1].toIntOrNull()
        val episode = match.groupValues[2].toIntOrNull()
        val rawShow = coreTitle.substring(0, match.range.first)
        val rawEpisodeTitle = coreTitle.substring(match.range.last + 1)
        return ParsedVideoFileName(
            baseTitle = cleanTitleSegment(rawShow),
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = cleanTitleSegment(rawEpisodeTitle).takeIf { it.isNotBlank() },
            extractedDownloadId = extractedId,
            hasAmbiguousEpisodes = season == null || episode == null,
        )
    }

    return ParsedVideoFileName(
        baseTitle = cleanTitleSegment(coreTitle),
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        extractedDownloadId = extractedId,
        hasAmbiguousEpisodes = false,
    )
}

internal fun cleanTitleSegment(raw: String): String {
    val trimmed = raw.trim().trim { it == '-' || it == '_' || it == '.' || it.isWhitespace() }
    if (trimmed.isEmpty()) return ""
    val withSpaces = if (!trimmed.contains(' ') && trimmed.contains('.')) {
        trimmed.replace('.', ' ')
    } else {
        trimmed
    }
    return buildString {
        var lastWasSpace = true
        for (ch in withSpaces) {
            val mapped = if (ch == '_') ' ' else ch
            if (mapped.isWhitespace()) {
                if (!lastWasSpace) {
                    append(' ')
                    lastWasSpace = true
                }
            } else {
                append(mapped)
                lastWasSpace = false
            }
        }
    }.trim()
}

internal fun normalizeTitleKey(title: String): String = buildString {
    var lastWasSpace = true
    for (ch in title.lowercase()) {
        if (ch.category == CharCategory.NON_SPACING_MARK ||
            ch.category == CharCategory.COMBINING_SPACING_MARK ||
            ch.category == CharCategory.ENCLOSING_MARK
        ) {
            continue
        }
        if (ch.isLetterOrDigit()) {
            append(ch)
            lastWasSpace = false
        } else if (!lastWasSpace) {
            append(' ')
            lastWasSpace = true
        }
    }
}.trim()

/**
 * Keys that match [showName] both in its original form and after the filename sanitization
 * performed when saving downloads (`[^A-Za-z0-9._ -]` -> `_`).
 */
internal fun showTitleKeys(showName: String): Set<String> {
    val rawKey = normalizeTitleKey(showName)
    val sanitizedKey = normalizeTitleKey(cleanTitleSegment(showName.trim().replace(NonFileNameCharsRegex, "_")))
    return buildSet {
        if (rawKey.isNotBlank()) add(rawKey)
        if (sanitizedKey.isNotBlank()) add(sanitizedKey)
    }
}

/**
 * Matches all scanned candidates in two passes: first matching files that carry their own
 * `episodes.json` (while indexing unambiguous show episode lists), then matching remaining
 * companions against `episodesIndex` or manifest/file name.
 */
internal fun matchDiskCandidates(
    candidates: List<DiskCandidate>,
    nextDownloadId: (Long) -> String,
    nowEpochMs: Long,
): DiskScanResult {
    val episodesByShowKey = mutableMapOf<String, MutableList<SavedEpisodes>>()
    candidates.forEach { candidate ->
        val episodes = candidate.companion?.takeIf {
            !it.foreignOrInvalid && !it.episodesUnreadable && !it.manifestUnreadable
        }?.episodes ?: return@forEach
        for (key in showTitleKeys(episodes.showName)) {
            episodesByShowKey.getOrPut(key) { mutableListOf() }.add(episodes)
        }
    }
    // Keep only shows where every discovered episodes.json agrees on parentMetaId.
    val uniqueEpisodesByShowKey: Map<String, SavedEpisodes> = episodesByShowKey.mapNotNull { (key, lists) ->
        val distinctIds = lists.map { it.parentMetaId.trim() }.toSet()
        if (distinctIds.size != 1) {
            null
        } else {
            val merged = lists.reduce { acc, next -> acc.mergedWith(next) }
            key to merged
        }
    }.toMap()

    val recovered = mutableListOf<DownloadItem>()
    val unrecognised = mutableListOf<UnrecognisedFile>()

    for (candidate in candidates) {
        when (val outcome = matchSingleCandidate(candidate, uniqueEpisodesByShowKey, nextDownloadId, nowEpochMs)) {
            is MatchOutcome.Matched -> recovered += outcome.item
            is MatchOutcome.Unrecognised -> unrecognised += UnrecognisedFile(
                localFileUri = candidate.localFileUri,
                fileName = candidate.fileName,
                sizeBytes = candidate.sizeBytes,
                locationId = candidate.locationId,
                locationName = candidate.locationName,
                reason = outcome.reason,
            )
        }
    }

    return DiskScanResult(
        recoveredItems = recovered,
        unrecognisedFiles = unrecognised,
    )
}

internal fun matchSingleCandidate(
    candidate: DiskCandidate,
    episodesByShowKey: Map<String, SavedEpisodes>,
    nextDownloadId: (Long) -> String,
    nowEpochMs: Long,
): MatchOutcome {
    if (candidate.sizeBytes <= 0L ||
        candidate.startsLikeWebPage ||
        !candidate.isVideoContainer
    ) {
        return MatchOutcome.Unrecognised(UnrecognisedFileReason.NotAVideoFile)
    }

    val companion = candidate.companion ?: return MatchOutcome.Unrecognised(UnrecognisedFileReason.NoCompanionFolder)
    if (companion.foreignOrInvalid) {
        return MatchOutcome.Unrecognised(UnrecognisedFileReason.ForeignCompanionFolder)
    }
    if (companion.episodesUnreadable || companion.manifestUnreadable) {
        return MatchOutcome.Unrecognised(UnrecognisedFileReason.UnreadableCompanion)
    }
    if (!companion.hasPositiveEvidence) {
        return MatchOutcome.Unrecognised(UnrecognisedFileReason.NoCompanionFolder)
    }

    val parsed = parseVideoFileName(candidate.fileName)
    if (parsed.hasAmbiguousEpisodes) {
        return MatchOutcome.Unrecognised(UnrecognisedFileReason.AmbiguousName)
    }

    val createdAt = candidate.lastModifiedEpochMs.takeIf { it > 0L } ?: nowEpochMs
    val ownEpisodes = companion.episodes
    if (ownEpisodes != null) {
        val season = parsed.seasonNumber ?: return MatchOutcome.Unrecognised(UnrecognisedFileReason.EpisodeMismatch)
        val episode = parsed.episodeNumber ?: return MatchOutcome.Unrecognised(UnrecognisedFileReason.EpisodeMismatch)
        if (parsed.baseTitle.isNotBlank() &&
            normalizeTitleKey(parsed.baseTitle) !in showTitleKeys(ownEpisodes.showName)
        ) {
            return MatchOutcome.Unrecognised(UnrecognisedFileReason.EpisodeMismatch)
        }
        val matchedEpisode = ownEpisodes.episodes.firstOrNull { it.season == season && it.episode == episode }
            ?: return MatchOutcome.Unrecognised(UnrecognisedFileReason.EpisodeMismatch)
        return MatchOutcome.Matched(
            buildRecoveredEpisodeItem(
                candidate = candidate,
                showName = ownEpisodes.showName,
                parentMetaId = ownEpisodes.parentMetaId,
                parentMetaType = ownEpisodes.parentMetaType,
                poster = ownEpisodes.poster,
                matchedEpisode = matchedEpisode,
                fallbackEpisodeTitle = parsed.episodeTitle,
                createdAtEpochMs = createdAt,
                nowEpochMs = nowEpochMs,
                id = nextDownloadId(nowEpochMs),
            ),
        )
    }

    val season = parsed.seasonNumber
    val episode = parsed.episodeNumber
    if (season != null && episode != null) {
        if (parsed.baseTitle.isBlank() || parsed.baseTitle.equals("download", ignoreCase = true)) {
            return MatchOutcome.Unrecognised(UnrecognisedFileReason.AmbiguousName)
        }
        val showKey = normalizeTitleKey(parsed.baseTitle)
        val manifestImdbIds = companion.manifest?.let(::extractAllImdbIdsFromManifest).orEmpty()
        if (manifestImdbIds.size > 1) {
            return MatchOutcome.Unrecognised(UnrecognisedFileReason.AmbiguousName)
        }
        val manifestImdbId = manifestImdbIds.singleOrNull()
        val sharedEpisodes = episodesByShowKey[showKey]
        val sharedEpisode = sharedEpisodes?.episodes?.firstOrNull { it.season == season && it.episode == episode }
        val sharedContradictsManifest = sharedEpisodes != null && manifestImdbIds.isNotEmpty() && run {
            val sharedKnownImdbIds = setOfNotNull(
                sharedEpisodes.imdbId?.trim()?.takeIf { it.isNotEmpty() },
                sharedEpisodes.parentMetaId.trim().takeIf { ImdbIdRegex.matches(it) },
            )
            sharedKnownImdbIds.isEmpty() || manifestImdbIds.none { id ->
                sharedKnownImdbIds.any { it.equals(id, ignoreCase = true) }
            }
        }
        if (sharedEpisodes != null && sharedEpisode != null && !sharedContradictsManifest) {
            return MatchOutcome.Matched(
                buildRecoveredEpisodeItem(
                    candidate = candidate,
                    showName = sharedEpisodes.showName,
                    parentMetaId = sharedEpisodes.parentMetaId,
                    parentMetaType = sharedEpisodes.parentMetaType,
                    poster = sharedEpisodes.poster,
                    matchedEpisode = sharedEpisode,
                    fallbackEpisodeTitle = parsed.episodeTitle,
                    createdAtEpochMs = createdAt,
                    nowEpochMs = nowEpochMs,
                    id = nextDownloadId(nowEpochMs),
                ),
            )
        }
        val parentMetaId = manifestImdbId ?: "recovered:series:$showKey"
        return MatchOutcome.Matched(
            DownloadItem(
                id = nextDownloadId(nowEpochMs),
                contentType = "series",
                parentMetaId = parentMetaId,
                parentMetaType = "series",
                videoId = "$parentMetaId:$season:$episode",
                title = parsed.baseTitle,
                seasonNumber = season,
                episodeNumber = episode,
                episodeTitle = parsed.episodeTitle,
                streamTitle = "",
                providerName = "",
                sourceUrl = "",
                localFileUri = candidate.localFileUri,
                fileName = candidate.fileName,
                status = DownloadStatus.Completed,
                downloadedBytes = candidate.sizeBytes,
                totalBytes = candidate.sizeBytes,
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = nowEpochMs,
                verifiedBy = null,
                framesCheck = DownloadFramesCheck.NotChecked,
                locationId = candidate.locationId,
            ),
        )
    }

    // Movie: no episodes.json and no SxxEyy marker, backed by a valid Nuvio companion folder.
    if (parsed.baseTitle.isBlank() || parsed.baseTitle.equals("download", ignoreCase = true)) {
        return MatchOutcome.Unrecognised(UnrecognisedFileReason.AmbiguousName)
    }
    val movieKey = normalizeTitleKey(parsed.baseTitle)
    val imdbId = companion.manifest?.let(::extractImdbIdFromManifest)
    val parentMetaId = imdbId ?: "recovered:movie:$movieKey"
    return MatchOutcome.Matched(
        DownloadItem(
            id = nextDownloadId(nowEpochMs),
            contentType = "movie",
            parentMetaId = parentMetaId,
            parentMetaType = "movie",
            videoId = parentMetaId,
            title = parsed.baseTitle,
            streamTitle = "",
            providerName = "",
            sourceUrl = "",
            localFileUri = candidate.localFileUri,
            fileName = candidate.fileName,
            status = DownloadStatus.Completed,
            downloadedBytes = candidate.sizeBytes,
            totalBytes = candidate.sizeBytes,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = nowEpochMs,
            verifiedBy = null,
            framesCheck = DownloadFramesCheck.NotChecked,
            locationId = candidate.locationId,
        ),
    )
}

private fun buildRecoveredEpisodeItem(
    candidate: DiskCandidate,
    showName: String,
    parentMetaId: String,
    parentMetaType: String,
    poster: String?,
    matchedEpisode: SavedEpisode,
    fallbackEpisodeTitle: String?,
    createdAtEpochMs: Long,
    nowEpochMs: Long,
    id: String,
): DownloadItem {
    val type = parentMetaType.ifBlank { "series" }
    return DownloadItem(
        id = id,
        contentType = type,
        parentMetaId = parentMetaId,
        parentMetaType = type,
        videoId = matchedEpisode.id.ifBlank { "$parentMetaId:${matchedEpisode.season}:${matchedEpisode.episode}" },
        title = showName,
        poster = poster,
        seasonNumber = matchedEpisode.season,
        episodeNumber = matchedEpisode.episode,
        episodeTitle = matchedEpisode.title.takeIf { it.isNotBlank() } ?: fallbackEpisodeTitle,
        episodeThumbnail = matchedEpisode.thumbnail,
        streamTitle = "",
        providerName = "",
        sourceUrl = "",
        localFileUri = candidate.localFileUri,
        fileName = candidate.fileName,
        status = DownloadStatus.Completed,
        downloadedBytes = candidate.sizeBytes,
        totalBytes = candidate.sizeBytes,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = nowEpochMs,
        verifiedBy = null,
        framesCheck = DownloadFramesCheck.NotChecked,
        locationId = candidate.locationId,
    )
}
