package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloads
import com.nuvio.app.features.downloads.autoDownloadRuleKey
import com.nuvio.app.features.downloads.BatchQuality
import com.nuvio.app.features.downloads.SeasonBatchDownloads
import com.nuvio.app.features.downloads.SeasonBatchProgress
import com.nuvio.app.features.downloads.SeasonBatchRequest
import com.nuvio.app.features.downloads.SeasonBatchStart
import com.nuvio.app.features.downloads.SeasonDownloadChoices
import com.nuvio.app.features.downloads.SeasonDownloadDefaults
import com.nuvio.app.features.downloads.seasonBatchEpisodes
import com.nuvio.app.features.downloads.seasonBatchKey
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.LanguageSelectionDialog
import com.nuvio.app.features.settings.LanguageSelectionOption
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.isDesktop
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * "Download" control for the season header. Desktop only. It opens a menu with two actions.
 *
 * "Download season" asks once for quality, maximum bitrate and audio language, starting from the
 * last saved choices, then adds every released episode of the season to the normal downloads queue.
 * "Auto-download" opens the show's automatic-download rule.
 *
 * The control always opens the menu and itself shows what is going on: for a show with an
 * automatic-download rule its label and its state description say that the rule is on, and while
 * the season is being added they show how far it is. The menu's first entry is then
 * "Stop (n/total)", and "Auto-download" stays within reach.
 */
internal val LocalSeasonDownloadStarter = staticCompositionLocalOf<(SeasonBatchRequest) -> SeasonBatchStart> {
    SeasonBatchDownloads::start
}

@Composable
internal fun SeasonDownloadButton(
    meta: MetaDetails,
    seasonNumber: Int,
    modifier: Modifier = Modifier,
    profileId: Int = ProfileRepository.activeProfileId,
    startBatch: (SeasonBatchRequest) -> SeasonBatchStart = LocalSeasonDownloadStarter.current,
    running: StateFlow<Map<String, SeasonBatchProgress>> = SeasonBatchDownloads.running,
    cancelBatch: (parentMetaId: String, seasonNumber: Int) -> Unit = SeasonBatchDownloads::cancel,
    autoDownloads: AutoDownloadController = AutoDownloads,
) {
    if (!isDesktop || !AppFeaturePolicy.downloadsEnabled || meta.type != "series") return

    val tokens = MaterialTheme.nuvio
    val runningBatches by running.collectAsState()
    val progress = runningBatches[seasonBatchKey(meta.id, seasonNumber)]
    // The open dialog. It keeps the profile it was opened for, so a profile switch while it is open
    // neither replaces what is being edited nor saves it for the other profile.
    var openChoices by remember { mutableStateOf<SeasonDownloadChoices?>(null) }
    val seasonLabel = if (seasonNumber <= 0) {
        stringResource(Res.string.episodes_specials)
    } else {
        stringResource(Res.string.episodes_season, seasonNumber)
    }
    val dialogTitle = stringResource(Res.string.downloads_season_dialog_title, seasonLabel)
    val autoDownloadRules by autoDownloads.rules.collectAsState()
    val autoDownloadRule = autoDownloadRules.firstOrNull { it.key == autoDownloadRuleKey(meta.type, meta.id) }
    val autoDownloadLabel = stringResource(
        if (autoDownloadRule != null) Res.string.downloads_auto_button_on else Res.string.downloads_auto_button,
    )
    val autoDownloadState = stringResource(
        if (autoDownloadRule != null) Res.string.downloads_auto_button_on else Res.string.downloads_auto_button_off,
    )
    val idleLabel = stringResource(
        if (autoDownloadRule != null) Res.string.downloads_button_auto_on else Res.string.downloads_season_start,
    )
    // The control always opens the menu, so the rule stays within reach while a season is being
    // added. Its label and state then show how far the season is, and the menu's first entry stops.
    val buttonLabel = progress
        ?.let { stringResource(Res.string.downloads_button_running, idleLabel, it.finished, it.total) }
        ?: idleLabel
    val buttonState = progress
        ?.let { stringResource(Res.string.downloads_button_state_running, autoDownloadState, it.finished, it.total) }
        ?: autoDownloadState
    val stopLabel = progress?.let { stringResource(Res.string.downloads_season_stop, it.finished, it.total) }
    val seasonActionLabel = stopLabel ?: stringResource(Res.string.downloads_season_button)
    var menuOpen by remember { mutableStateOf(false) }
    // The profile the automatic-download dialog was opened for, while it is open.
    var autoDownloadDialogProfileId by remember { mutableStateOf<Int?>(null) }
    val startedText = stringResource(Res.string.downloads_season_started)
    val nothingToDownloadText = stringResource(Res.string.downloads_season_nothing)
    val alreadyRunningText = stringResource(Res.string.downloads_season_already_running)

    Box(modifier = modifier) {
        TextButton(
            modifier = Modifier.semantics {
                contentDescription = buttonLabel
                stateDescription = buttonState
            },
            onClick = { menuOpen = true },
            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s6),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(tokens.icons.sm),
                )
                Text(text = buttonLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
        Menu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            MenuItem(
                text = seasonActionLabel,
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = stopLabel ?: dialogTitle
                },
                onClick = {
                    menuOpen = false
                    if (progress != null) {
                        cancelBatch(meta.id, seasonNumber)
                    } else {
                        openChoices = SeasonDownloadChoices(profileId)
                    }
                },
            )
            MenuItem(
                text = autoDownloadLabel,
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = autoDownloadLabel
                },
                onClick = {
                    menuOpen = false
                    autoDownloadDialogProfileId = profileId
                },
            )
        }
    }

    autoDownloadDialogProfileId?.let { dialogProfileId ->
        AutoDownloadDialog(
            meta = meta,
            rule = autoDownloadRule,
            profileId = dialogProfileId,
            controller = autoDownloads,
            onDismiss = { autoDownloadDialogProfileId = null },
        )
    }

    openChoices?.let { choices ->
        DownloadChoicesDialog(
            choices = choices,
            title = dialogTitle,
            message = stringResource(Res.string.downloads_season_dialog_message),
            confirmLabel = stringResource(Res.string.downloads_season_button),
            onDismiss = { openChoices = null },
            onConfirm = { confirmed ->
                openChoices = null
                val result = startBatch(
                    SeasonBatchRequest(
                        contentType = meta.type,
                        parentMetaId = meta.id,
                        parentMetaType = meta.type,
                        title = meta.name,
                        logo = meta.logo,
                        poster = meta.poster,
                        background = meta.background,
                        seasonNumber = seasonNumber,
                        episodes = meta.seasonBatchEpisodes(seasonNumber, CurrentDateProvider.todayIsoDate()),
                        quality = confirmed.quality,
                        maxBitrateMbps = confirmed.maxBitrateMbps,
                        audioLanguage = confirmed.audioLanguage,
                    ),
                )
                NuvioToastController.show(
                    when (result) {
                        SeasonBatchStart.Started -> startedText
                        SeasonBatchStart.NothingToDownload -> nothingToDownloadText
                        SeasonBatchStart.AlreadyRunning -> alreadyRunningText
                    },
                )
            },
        )
    }
}

/**
 * Asks for quality, maximum bitrate and audio language, starting from [choices]. The choices are
 * saved, and handed to [onConfirm], only when the dialog is confirmed. [content] is shown between
 * the choices and the buttons.
 */
@Composable
internal fun DownloadChoicesDialog(
    choices: SeasonDownloadChoices,
    title: String,
    message: String,
    onConfirm: (SeasonDownloadDefaults) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(Res.string.downloads_season_start),
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var picker by remember(choices) { mutableStateOf<DownloadPreferencePicker?>(null) }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
        message = message,
    ) {
        DownloadPreferenceRows(
            quality = choices.quality,
            maxBitrateMbps = choices.maxBitrateMbps,
            audioLanguage = choices.audioLanguage,
            onPick = { picker = it },
        )
        content()
        SavedChoicesNote(choices)
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = confirmLabel,
                style = DialogButtonStyle.Primary,
                enabled = confirmEnabled,
                onClick = { onConfirm(choices.confirm()) },
            )
        }
    }

    DownloadPreferencePickers(
        picker = picker,
        quality = choices.quality,
        maxBitrateMbps = choices.maxBitrateMbps,
        audioLanguage = choices.audioLanguage,
        onQualityChange = { choices.quality = it },
        onMaxBitrateChange = { choices.maxBitrateMbps = it },
        onAudioLanguageChange = { choices.audioLanguage = it },
        onDismiss = { picker = null },
    )
}

/**
 * Says that the choices above are saved, where they apply and where to change them, and offers
 * "Just this time" so that a one-off download leaves the saved choices alone.
 */
@Composable
private fun SavedChoicesNote(choices: SeasonDownloadChoices) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.padding(top = NuvioTokens.Space.s8),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
    ) {
        Text(
            text = stringResource(Res.string.downloads_choices_saved_note),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        val label = stringResource(Res.string.downloads_choices_just_this_time)
        DialogOption(
            text = label,
            description = stringResource(Res.string.downloads_choices_just_this_time_description),
            selected = choices.justThisTime,
            role = Role.Checkbox,
            modifier = Modifier.semantics {
                contentDescription = label
                toggleableState = ToggleableState(choices.justThisTime)
            },
            onClick = { choices.justThisTime = !choices.justThisTime },
        )
    }
}

/** The three download preferences. Each one has a row and, once picked, a picker. */
internal enum class DownloadPreferencePicker { Quality, MaxBitrate, AudioLanguage }

/**
 * The rows for quality, maximum bitrate and audio language, each showing its current value. A click
 * hands the row's [DownloadPreferencePicker] to [onPick]. Shared by the season download dialog, the
 * settings page and the first-start dialog, so that all of them offer the same choices.
 */
@Composable
internal fun DownloadPreferenceRows(
    quality: BatchQuality,
    maxBitrateMbps: Int?,
    audioLanguage: DebridStreamLanguage?,
    onPick: (DownloadPreferencePicker) -> Unit,
    /** Added to the audio language row's help, for a language that was taken from playback and is not saved yet. */
    audioLanguageNote: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap)) {
        SeasonDownloadChoiceRow(
            title = stringResource(Res.string.downloads_season_quality_heading),
            value = batchQualityLabel(quality),
            onClick = { onPick(DownloadPreferencePicker.Quality) },
        )
        SeasonDownloadChoiceRow(
            title = stringResource(Res.string.downloads_season_max_bitrate),
            description = stringResource(Res.string.downloads_season_max_bitrate_description),
            value = maxBitrateLabel(maxBitrateMbps),
            onClick = { onPick(DownloadPreferencePicker.MaxBitrate) },
        )
        SeasonDownloadChoiceRow(
            title = stringResource(Res.string.downloads_season_audio_language),
            description = listOfNotNull(
                stringResource(Res.string.downloads_season_audio_language_description),
                audioLanguageNote,
            ).joinToString(" "),
            value = audioLanguageLabel(audioLanguage),
            onClick = { onPick(DownloadPreferencePicker.AudioLanguage) },
        )
    }
}

/** The picker for [picker], if there is one. A choice is handed to its callback and closes the picker. */
@Composable
internal fun DownloadPreferencePickers(
    picker: DownloadPreferencePicker?,
    quality: BatchQuality,
    maxBitrateMbps: Int?,
    audioLanguage: DebridStreamLanguage?,
    onQualityChange: (BatchQuality) -> Unit,
    onMaxBitrateChange: (Int?) -> Unit,
    onAudioLanguageChange: (DebridStreamLanguage?) -> Unit,
    onDismiss: () -> Unit,
) {
    when (picker) {
        DownloadPreferencePicker.Quality -> DownloadChoiceDialog(
            title = stringResource(Res.string.downloads_season_quality_heading),
            options = BatchQuality.entries,
            selected = quality,
            label = { batchQualityLabel(it) },
            onSelect = {
                onQualityChange(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        DownloadPreferencePicker.MaxBitrate -> DownloadChoiceDialog(
            title = stringResource(Res.string.downloads_season_max_bitrate),
            options = SeasonDownloadDefaults.maxBitrateOptions,
            selected = maxBitrateMbps,
            label = { maxBitrateLabel(it) },
            onSelect = {
                onMaxBitrateChange(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        DownloadPreferencePicker.AudioLanguage -> {
            val options = SeasonDownloadDefaults.audioLanguageOptions.map { language ->
                LanguageSelectionOption(value = language?.code, label = audioLanguageLabel(language))
            }
            LanguageSelectionDialog(
                title = stringResource(Res.string.downloads_season_audio_language),
                options = options,
                selectedValue = audioLanguage?.code,
                onSelect = { code ->
                    onAudioLanguageChange(SeasonDownloadDefaults.audioLanguageOptions.firstOrNull { it?.code == code })
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        null -> Unit
    }
}

/**
 * The three preferences with their pickers, for a page that edits them in place: every choice is
 * handed to its callback as soon as it is made.
 */
@Composable
internal fun DownloadPreferenceEditor(
    quality: BatchQuality,
    maxBitrateMbps: Int?,
    audioLanguage: DebridStreamLanguage?,
    onQualityChange: (BatchQuality) -> Unit,
    onMaxBitrateChange: (Int?) -> Unit,
    onAudioLanguageChange: (DebridStreamLanguage?) -> Unit,
    audioLanguageNote: String? = null,
) {
    var picker by remember { mutableStateOf<DownloadPreferencePicker?>(null) }
    DownloadPreferenceRows(quality, maxBitrateMbps, audioLanguage, onPick = { picker = it }, audioLanguageNote = audioLanguageNote)
    DownloadPreferencePickers(
        picker = picker,
        quality = quality,
        maxBitrateMbps = maxBitrateMbps,
        audioLanguage = audioLanguage,
        onQualityChange = onQualityChange,
        onMaxBitrateChange = onMaxBitrateChange,
        onAudioLanguageChange = onAudioLanguageChange,
        onDismiss = { picker = null },
    )
}

/**
 * A picker for one download choice. Every option is a radio button of one group that is named by
 * its label, so assistive technology reads the option and whether it is the chosen one.
 */
@Composable
internal fun <T> DownloadChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(onDismissRequest = onDismiss, title = title) {
        Column(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
        ) {
            options.forEach { option ->
                val optionLabel = label(option)
                DialogOption(
                    text = optionLabel,
                    modifier = Modifier.semantics { contentDescription = optionLabel },
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
        DialogButtons {
            DialogButton(text = stringResource(Res.string.action_done), onClick = onDismiss)
        }
    }
}

/**
 * One download choice: a button named by [title] whose state is the current [value]. A click opens
 * its picker.
 */
@Composable
internal fun SeasonDownloadChoiceRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    description: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = value
            }
            .padding(horizontal = NuvioTokens.Space.s14, vertical = NuvioTokens.Space.s12),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
            if (description != null) {
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
    }
}

@Composable
internal fun batchQualityLabel(quality: BatchQuality): String = when (quality) {
    BatchQuality.BestAvailable -> stringResource(Res.string.downloads_season_quality_any)
    BatchQuality.P2160 -> stringResource(Res.string.downloads_season_quality_2160)
    BatchQuality.P1080 -> stringResource(Res.string.downloads_season_quality_1080)
    BatchQuality.P720 -> stringResource(Res.string.downloads_season_quality_720)
    BatchQuality.P480 -> stringResource(Res.string.downloads_season_quality_480)
}

@Composable
internal fun maxBitrateLabel(maxBitrateMbps: Int?): String =
    if (maxBitrateMbps == null) {
        stringResource(Res.string.downloads_season_bitrate_no_limit)
    } else {
        stringResource(Res.string.downloads_season_bitrate_value, maxBitrateMbps)
    }

@Composable
internal fun audioLanguageLabel(language: DebridStreamLanguage?): String =
    language?.label ?: stringResource(Res.string.downloads_season_audio_language_any)
