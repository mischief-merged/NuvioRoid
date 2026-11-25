package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.components.DownloadChoiceDialog
import com.nuvio.app.features.details.components.DownloadPreferenceEditor
import com.nuvio.app.features.details.components.SeasonDownloadChoiceRow
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloadStore
import com.nuvio.app.features.downloads.AutoDownloads
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.downloads.SeasonDownloadDefaultsRepository
import com.nuvio.app.features.downloads.downloadChoicesStart
import com.nuvio.app.features.player.AudioLanguageOption
import com.nuvio.app.features.player.DeviceLanguagePreferences
import com.nuvio.app.features.downloads.autoDownloadStorageLimitLabel
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Playback > Quality and language preferences. Edits, for the active profile, the
 * quality, maximum bitrate and audio language that downloads start from and that the "best match"
 * Play mode uses, and the choice between asking and picking the best match when Play is pressed.
 */
@Composable
internal fun PlaybackPreferencesSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
    onOpenStreamAutoPlay: () -> Unit,
) {
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val profileId = profileState.activeProfile?.profileIndex ?: ProfileRepository.activeProfileId
    val deviceLanguages = remember { DeviceLanguagePreferences.preferredLanguageCodes() }
    PlaybackPreferencesContent(
        isTablet = isTablet,
        profileId = profileId,
        playbackAudioLanguage = settings.preferredAudioLanguage,
        deviceLanguages = deviceLanguages,
        playMode = settings.streamAutoPlayMode,
        onPlayModeChange = PlayerSettingsRepository::setStreamAutoPlayMode,
        onOpenStreamAutoPlay = onOpenStreamAutoPlay,
    )
}

@Composable
internal fun PlaybackPreferencesContent(
    isTablet: Boolean,
    profileId: Int,
    playMode: StreamAutoPlayMode,
    onPlayModeChange: (StreamAutoPlayMode) -> Unit,
    onOpenStreamAutoPlay: () -> Unit,
    /** The playback audio language. A profile that never saved choices starts with the download language that goes with it. */
    playbackAudioLanguage: String = AudioLanguageOption.DEFAULT,
    deviceLanguages: List<String> = emptyList(),
    autoDownloads: AutoDownloadController = AutoDownloads,
    showStorageLimit: Boolean = isDesktop && AppFeaturePolicy.downloadsEnabled,
) {
    // What is shown is read again for each profile, and every change is saved at once, so the
    // season download dialog, which reads the same saved choices, shows what was chosen here.
    // A profile that never saved choices starts with the download language that goes with its
    // playback audio language. That is shown, not saved: the first change saves everything shown.
    val start = remember(profileId, playbackAudioLanguage, deviceLanguages) {
        downloadChoicesStart(profileId, playbackAudioLanguage, deviceLanguages)
    }
    var saved by remember(start) { mutableStateOf(start.defaults) }
    var languageFromPlayback by remember(start) { mutableStateOf(start.languageFromPlayback) }
    fun change(updated: SeasonDownloadDefaults) {
        val normalized = updated.normalized()
        saved = normalized
        languageFromPlayback = false
        SeasonDownloadDefaultsRepository.save(profileId, normalized)
    }

    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_preferences_section_choices),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.controlGap),
                ) {
                    Text(
                        text = stringResource(Res.string.settings_preferences_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.nuvio.colors.textMuted,
                    )
                    DownloadPreferenceEditor(
                        quality = saved.quality,
                        maxBitrateMbps = saved.maxBitrateMbps,
                        audioLanguage = saved.audioLanguage,
                        onQualityChange = { change(saved.copy(quality = it)) },
                        onMaxBitrateChange = { change(saved.copy(maxBitrateMbps = it)) },
                        onAudioLanguageChange = { change(saved.copy(audioLanguage = it)) },
                        audioLanguageNote = if (languageFromPlayback) {
                            stringResource(Res.string.downloads_season_audio_language_prefilled)
                        } else {
                            null
                        },
                    )
                }
            }
        }

        SettingsSection(
            title = stringResource(Res.string.settings_preferences_section_play),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
                ) {
                    PlayChoiceOptions(
                        mode = playMode,
                        onModeChange = onPlayModeChange,
                        onOpenStreamAutoPlay = onOpenStreamAutoPlay,
                    )
                }
            }
        }

        if (showStorageLimit) {
            SettingsSection(
                title = stringResource(Res.string.settings_preferences_section_storage),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.nuvio.spacing.controlGap),
                    ) {
                        TotalStorageLimitChoice(profileId = profileId, controller = autoDownloads)
                        StorageLimitChoice(profileId = profileId, controller = autoDownloads)
                    }
                }
            }
        }
    }
}

/** How the stored Play mode reads on this page. */
internal enum class PlayChoice {
    /** [StreamAutoPlayMode.MANUAL]. */
    AskEveryTime,

    /** [StreamAutoPlayMode.PREFERRED]. */
    BestMatch,

    /** [StreamAutoPlayMode.FIRST_STREAM] or [StreamAutoPlayMode.REGEX_MATCH]: set elsewhere, neither option is chosen. */
    SetElsewhere,
}

internal fun StreamAutoPlayMode.playChoice(): PlayChoice = when (this) {
    StreamAutoPlayMode.MANUAL -> PlayChoice.AskEveryTime
    StreamAutoPlayMode.PREFERRED -> PlayChoice.BestMatch
    StreamAutoPlayMode.FIRST_STREAM, StreamAutoPlayMode.REGEX_MATCH -> PlayChoice.SetElsewhere
}

/**
 * "Ask me every time" or "Pick the best match for these preferences", as one radio group. When the
 * stored mode is one of the others, neither is selected, the mode is named, and a button opens the
 * Stream auto-play settings. It is replaced only when the user picks one of the two options.
 */
@Composable
internal fun PlayChoiceOptions(
    mode: StreamAutoPlayMode,
    onModeChange: (StreamAutoPlayMode) -> Unit,
    onOpenStreamAutoPlay: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val choice = mode.playChoice()
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        DialogOption(
            text = stringResource(Res.string.settings_preferences_play_ask),
            description = stringResource(Res.string.settings_preferences_play_ask_description),
            selected = choice == PlayChoice.AskEveryTime,
            onClick = { if (mode != StreamAutoPlayMode.MANUAL) onModeChange(StreamAutoPlayMode.MANUAL) },
        )
        DialogOption(
            text = stringResource(Res.string.settings_preferences_play_best),
            description = stringResource(Res.string.settings_preferences_play_best_description),
            selected = choice == PlayChoice.BestMatch,
            onClick = { if (mode != StreamAutoPlayMode.PREFERRED) onModeChange(StreamAutoPlayMode.PREFERRED) },
        )
    }
    if (choice == PlayChoice.SetElsewhere) {
        Column(
            modifier = Modifier.padding(top = NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Text(
                text = stringResource(Res.string.settings_preferences_play_other, stringResource(mode.labelRes)),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
            if (onOpenStreamAutoPlay != null) {
                val openLabel = stringResource(Res.string.settings_preferences_open_stream_auto_play)
                TextButton(
                    modifier = Modifier.semantics { contentDescription = openLabel },
                    onClick = onOpenStreamAutoPlay,
                ) {
                    Text(text = openLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun TotalStorageLimitChoice(profileId: Int, controller: AutoDownloadController) {
    val totalLimitGb by controller.totalStorageLimitGb.collectAsState()
    var pickerOpen by remember(profileId) { mutableStateOf(false) }
    SeasonDownloadChoiceRow(
        title = stringResource(Res.string.downloads_total_storage_limit),
        description = stringResource(Res.string.downloads_total_storage_limit_description),
        value = autoDownloadStorageLimitLabel(totalLimitGb),
        onClick = { pickerOpen = true },
    )
    if (pickerOpen) {
        DownloadChoiceDialog(
            title = stringResource(Res.string.downloads_total_storage_limit),
            options = AutoDownloadStore.totalStorageLimitOptionsGb,
            selected = totalLimitGb,
            label = { autoDownloadStorageLimitLabel(it) },
            onSelect = {
                controller.setTotalStorageLimit(profileId, it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun StorageLimitChoice(profileId: Int, controller: AutoDownloadController) {
    val storageLimitGb by controller.storageLimitGb.collectAsState()
    var pickerOpen by remember(profileId) { mutableStateOf(false) }
    SeasonDownloadChoiceRow(
        title = stringResource(Res.string.downloads_auto_storage_limit),
        description = stringResource(Res.string.downloads_auto_storage_limit_description),
        value = autoDownloadStorageLimitLabel(storageLimitGb),
        onClick = { pickerOpen = true },
    )
    if (pickerOpen) {
        DownloadChoiceDialog(
            title = stringResource(Res.string.downloads_auto_storage_limit),
            options = AutoDownloadStore.storageLimitOptionsGb,
            selected = storageLimitGb,
            label = { autoDownloadStorageLimitLabel(it) },
            onSelect = {
                controller.setStorageLimit(profileId, it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}
