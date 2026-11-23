package com.nuvio.app.features.details

import com.nuvio.app.core.time.isEpisodeReleaseAired

/** Where an episode sits in a show, used by the episode page for its trail and neighbours. */
internal data class EpisodeTrail(
    val showName: String,
    val seasonNumber: Int,
    val episodeNumber: Int?,
    val episodeTitle: String,
)

/** Episodes in the order the season selector shows them: seasons ascending, specials last. */
internal fun MetaDetails.episodesInDisplayOrder(): List<MetaVideo> =
    groupedEpisodesForDisplay().values.flatten()

/** Finds an episode by its video id, or by season and episode number when no id matches. */
internal fun MetaDetails.findEpisode(
    videoId: String?,
    season: Int? = null,
    episode: Int? = null,
): MetaVideo? {
    val episodes = episodesInDisplayOrder()
    if (!videoId.isNullOrBlank()) {
        episodes.firstOrNull { it.id == videoId }?.let { return it }
    }
    if (episode == null) return null
    val wantedSeason = normalizeSeasonNumber(season)
    return episodes.firstOrNull {
        normalizeSeasonNumber(it.season) == wantedSeason && it.episode == episode
    }
}

/** The episode shown before [video], crossing season boundaries. Null at the start or when unknown. */
internal fun MetaDetails.previousEpisode(video: MetaVideo): MetaVideo? {
    val episodes = episodesInDisplayOrder()
    val index = episodes.indexOfFirst { it.id == video.id }
    return if (index > 0) episodes[index - 1] else null
}

/** The episode shown after [video], crossing season boundaries. Null at the end or when unknown. */
internal fun MetaDetails.nextEpisode(video: MetaVideo): MetaVideo? {
    val episodes = episodesInDisplayOrder()
    val index = episodes.indexOfFirst { it.id == video.id }
    return if (index >= 0 && index < episodes.lastIndex) episodes[index + 1] else null
}

/** All episodes of the season [video] belongs to, in display order. */
internal fun MetaDetails.episodesInSeasonOf(video: MetaVideo): List<MetaVideo> =
    groupedEpisodesForDisplay()[normalizeSeasonNumber(video.season)].orEmpty()

internal fun MetaDetails.trailFor(video: MetaVideo): EpisodeTrail =
    EpisodeTrail(
        showName = name,
        seasonNumber = normalizeSeasonNumber(video.season),
        episodeNumber = video.episode,
        episodeTitle = video.title,
    )

/** Why an episode can or cannot be played now. */
internal enum class EpisodeAvailability {
    Playable,

    /** Dated in the future: the page says when it airs. */
    NotReleased,

    /** Listed as unavailable although the date is past or unknown: no air date is promised. */
    Unavailable,
}

internal fun MetaVideo.availability(
    nowEpochMs: Long? = null,
    offline: Boolean = false,
    hasLocalDownload: Boolean = false,
): EpisodeAvailability {
    val aired = if (nowEpochMs == null) {
        isEpisodeReleaseAired(released)
    } else {
        isEpisodeReleaseAired(released, nowEpochMs)
    }
    val effectiveAvailable = if (offline) hasLocalDownload else available
    return when {
        aired == false -> EpisodeAvailability.NotReleased
        !effectiveAvailable -> EpisodeAvailability.Unavailable
        else -> EpisodeAvailability.Playable
    }
}

/** True when the episode can be played now: listed as available and not dated in the future. */
internal fun MetaVideo.isPlayableNow(
    nowEpochMs: Long? = null,
    offline: Boolean = false,
    hasLocalDownload: Boolean = false,
): Boolean = availability(nowEpochMs, offline = offline, hasLocalDownload = hasLocalDownload) == EpisodeAvailability.Playable

/** The position of [video] among the episodes of its season, or null when the show does not list it. */
internal fun MetaDetails.episodeIndexInSeason(video: MetaVideo): Int? =
    episodesInSeasonOf(video).indexOfFirst { it.id == video.id }.takeIf { it >= 0 }

/** The outer list index of an episode row in the list layout: after the header, in season order. */
internal fun episodeListItemIndex(headerIndex: Int, indexInSeason: Int): Int =
    headerIndex + 1 + indexInSeason.coerceAtLeast(0)

/**
 * The season a show page should open on when it was asked for [initialSeason] or for [focusVideo].
 * The focused episode wins. An unknown season gives null, so the page opens as usual.
 */
internal fun MetaDetails.requestedSeason(initialSeason: Int?, focusVideo: MetaVideo?): Int? {
    if (focusVideo != null) return normalizeSeasonNumber(focusVideo.season)
    if (initialSeason == null) return null
    val wanted = normalizeSeasonNumber(initialSeason)
    return wanted.takeIf { it in groupedEpisodesForDisplay() }
}
