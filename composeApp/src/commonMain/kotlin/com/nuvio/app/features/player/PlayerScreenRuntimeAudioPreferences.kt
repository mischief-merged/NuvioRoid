package com.nuvio.app.features.player

import com.nuvio.app.features.downloads.DownloadsRepository

internal val PlayerScreenRuntime.contentLanguage: String?
    get() {
        val metadata = listOfNotNull(metaUiState.meta, playerMeta).filter {
            it.id == parentMetaId && it.type == parentMetaType
        }
        return metadata.firstNotNullOfOrNull { normalizeLanguageCode(it.language) }
            ?: normalizeLanguageCode(args.contentLanguage)
            ?: metadata.firstNotNullOfOrNull { countryToLanguageCode(it.country) }
    }

internal val PlayerScreenRuntime.preferredAudioLanguageTargets: List<String>
    get() = audioLanguageTargets(
        downloadLanguage = activeSourceUrl.takeIf { it.startsWith("file:") }?.let(DownloadsRepository::preferredAudioLanguageOfFile),
        preferred = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
            secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
            contentOriginalLanguage = contentLanguage,
        ),
    )

/** The language that was noted for a downloaded file comes before the languages of the settings. */
internal fun audioLanguageTargets(downloadLanguage: String?, preferred: List<String>): List<String> =
    (listOfNotNull(downloadLanguage) + preferred).distinct()

internal data class AppliedAudioPreferences(
    val controller: PlayerEngineController,
    val languages: List<String>,
    val tracks: List<AudioTrack>,
)

internal fun PlayerScreenRuntime.applyPreferredAudioTrack(targets: List<String>) {
    if (isUserExplicitAudioSelection) return
    val controller = playerController ?: return
    val preferences = AppliedAudioPreferences(
        controller = controller,
        languages = targets,
        tracks = audioTracks.map { it.copy(isSelected = false) },
    )
    if (appliedAudioPreferences != preferences) {
        controller.applyAudioLanguagePreferences(targets)
        appliedAudioPreferences = preferences
        preferredAudioSelectionApplied = false
    } else {
        preferredAudioSelectionApplied = true
    }
}

internal fun PlayerScreenRuntime.restorePersistedAudioPreference(preference: PersistedPlayerTrackPreference) {
    if (audioTracks.isEmpty()) return
    val restoredIndex = findPersistedAudioTrackIndex(audioTracks, preference)
    if (restoredIndex < 0) return
    playerController?.selectAudioTrack(restoredIndex)
    selectedAudioIndex = restoredIndex
    isUserExplicitAudioSelection = true
    preferredAudioSelectionApplied = true
}

internal fun PlayerScreenRuntime.refreshAudioTracksIfChanged() {
    if (playbackSnapshot.isLoading) return
    val controller = playerController ?: return
    if (!preferredAudioSelectionApplied) {
        refreshTracks()
        return
    }
    if (args.isMinimized) return
    val tracks = controller.getAudioTracks()
    // The engine caches its track list, so the same instance means nothing changed.
    if (tracks !== audioTracks && tracks != audioTracks) {
        refreshTracks()
    }
}
