package com.nuvio.app.features.player

data class PersistedPlayerTrackPreference(
    val subtitleType: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleName: String? = null,
    val subtitleTrackId: String? = null,
    val addonSubtitleId: String? = null,
    val addonSubtitleUrl: String? = null,
    val addonSubtitleItemId: String? = null,
    val addonSubtitleAddonName: String? = null,
    val audioLanguage: String? = null,
    val audioName: String? = null,
    val audioTrackId: String? = null,
    val subtitleIsForced: Boolean? = null,
)

object PersistedSubtitleSelectionType {
    const val INTERNAL = "INTERNAL"
    const val ADDON = "ADDON"
    const val DISABLED = "DISABLED"
}

internal expect object PlayerTrackPreferenceStorage {
    fun load(contentId: String): PersistedPlayerTrackPreference?
    fun save(contentId: String, preference: PersistedPlayerTrackPreference)
    fun loadSubtitleDelayMs(videoId: String): Int?
    fun saveSubtitleDelayMs(videoId: String, delayMs: Int)
    fun loadAudioDelayMs(audioOutputKey: String): Int?
    fun saveAudioDelayMs(audioOutputKey: String, delayMs: Int)
}

/**
 * Resolves the persistence key for per-show subtitle delay adjustments, preferring [parentMetaId]
 * so episodes of the same show share the offset and falling back to [videoId] for standalone items.
 */
internal fun subtitleDelayStorageKey(
    parentMetaId: String,
    videoId: String,
): String = parentMetaId.trim().takeIf { it.isNotEmpty() } ?: videoId.trim()

internal fun loadShowSubtitleDelayMs(
    parentMetaId: String,
    videoId: String,
): Int? {
    val showKey = subtitleDelayStorageKey(parentMetaId, videoId)
    if (showKey.isEmpty()) return null
    return PlayerTrackPreferenceStorage.loadSubtitleDelayMs(showKey)
        ?: videoId.trim().takeIf { it.isNotEmpty() && it != showKey }
            ?.let(PlayerTrackPreferenceStorage::loadSubtitleDelayMs)
}

internal fun saveShowSubtitleDelayMs(
    parentMetaId: String,
    videoId: String,
    delayMs: Int,
) {
    val showKey = subtitleDelayStorageKey(parentMetaId, videoId)
    if (showKey.isNotEmpty()) {
        PlayerTrackPreferenceStorage.saveSubtitleDelayMs(showKey, delayMs)
    }
}
