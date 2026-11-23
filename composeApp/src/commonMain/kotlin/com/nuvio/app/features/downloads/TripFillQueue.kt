package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Approved episode identities, saved before source preparation. Sources are refreshed after restart. */
@Serializable
internal data class SavedTripEpisode(
    val type: String,
    val showId: String,
    val showTitle: String,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val title: String?,
    val thumbnail: String?,
    val runtimeMinutes: Int,
    val runtimeEstimated: Boolean,
    val estimatedBytes: Long,
) {
    fun planned(): TripPlannedEpisode {
        val source = com.nuvio.app.features.streams.StreamItem(addonName = "", addonId = "")
        return TripPlannedEpisode(
            TripShowCandidate(type, showId, showTitle),
            SeasonBatchEpisode(videoId, season, episode, title, thumbnail, runtimeMinutes),
            runtimeMinutes, runtimeEstimated, emptyList(), source, false, estimatedBytes, true,
        )
    }
}

@Serializable
internal data class SavedTripPlan(
    val profileId: Int,
    val requestedHours: Int,
    val quality: SeasonDownloadDefaults,
    val episodes: List<SavedTripEpisode>,
) {
    fun plan(): TripFillPlan = TripFillPlan(
        requestedHours, episodes.map { it.planned() }, episodes.sumOf { it.runtimeMinutes },
        episodes.sumOf { it.estimatedBytes }, quality, null, null, null, null,
        TripPlanStopReason.TargetReached, profileId = profileId,
    )
}

internal class TripFillQueue(
    private val load: (Int) -> String?,
    private val save: (Int, String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(plan: TripFillPlan) {
        val profile = plan.profileId ?: return
        val snapshot = SavedTripPlan(profile, plan.requestedHours, plan.qualityDefaults, plan.plannedEpisodes.map { item ->
            SavedTripEpisode(
                item.show.parentMetaType, item.show.parentMetaId, item.show.title, item.episode.videoId,
                item.episode.seasonNumber, item.episode.episodeNumber, item.episode.title, item.episode.thumbnail,
                item.effectiveRuntimeMinutes, item.runtimeEstimated, item.estimatedBytes,
            )
        })
        save(profile, json.encodeToString(snapshot))
    }

    fun pending(profileId: Int): TripFillPlan? = load(profileId)?.takeIf { it.isNotBlank() }?.let { payload ->
        runCatching { json.decodeFromString<SavedTripPlan>(payload) }.getOrNull()
            ?.takeIf { it.profileId == profileId }?.plan()
    }

    fun clear(profileId: Int?) {
        if (profileId != null) save(profileId, "")
    }
}
