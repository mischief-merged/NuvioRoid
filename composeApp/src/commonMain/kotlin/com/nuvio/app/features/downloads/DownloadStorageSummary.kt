package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.components.DownloadChoiceDialog
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** A size said for a screen reader: "48.2 gigabytes". Same units and rounding as [formatBytes]. */
@Composable
internal fun spokenBytes(bytes: Long): String {
    val size = decimalByteSize(bytes)
    val unit = when (size.scale) {
        DecimalByteScale.Bytes -> stringResource(Res.string.downloads_unit_bytes)
        DecimalByteScale.Kilobytes -> stringResource(Res.string.downloads_unit_kilobytes)
        DecimalByteScale.Megabytes -> stringResource(Res.string.downloads_unit_megabytes)
        DecimalByteScale.Gigabytes -> stringResource(Res.string.downloads_unit_gigabytes)
    }
    return "${size.amount} $unit"
}

/**
 * The top of the Downloads screen: how much the downloads take on disk and how much room is left, as
 * one readable line; progress against the limit for automatic downloads when there is one; and, with
 * more than one location, a compact list of them that opens and closes. Locations are managed in
 * the setting, which [onManageLocations] opens.
 */
@Composable
internal fun DownloadStorageSummary(
    state: DownloadFolderState,
    autoLimitGb: Int?,
    onManageLocations: (() -> Unit)?,
    /**
     * Changes the storage limit for automatic downloads. Null where the screen cannot: the summary
     * then shows the usage and no control. The first argument is the profile the control was opened
     * for, so a profile change while the picker is open does not save against the new one.
     */
    onChangeStorageLimit: ((profileId: Int, limitGb: Int?) -> Unit)? = null,
    profileId: Int = 0,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pickerProfileId by rememberSaveable { mutableStateOf<Int?>(null) }
    val used = state.totalUsedBytes
    val free = state.totalFreeBytes
    val headline = when {
        !state.usageMeasured -> stringResource(Res.string.downloads_storage_measuring)
        free != null -> stringResource(Res.string.downloads_storage_summary, formatBytes(used), formatBytes(free))
        else -> stringResource(Res.string.downloads_storage_summary_no_free, formatBytes(used))
    }
    val spoken = when {
        !state.usageMeasured -> headline
        free != null -> stringResource(Res.string.downloads_storage_summary_spoken, spokenBytes(used), spokenBytes(free))
        else -> stringResource(Res.string.downloads_storage_summary_spoken_no_free, spokenBytes(used))
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // One item for a screen reader, with the units spoken out.
            Text(
                text = headline,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                style = MaterialTheme.typography.titleSmall,
            )
            if ((autoLimitGb != null && autoLimitGb > 0) || onChangeStorageLimit != null) {
                val limitBytes = autoLimitGb?.takeIf { it > 0 }?.times(BytesPerGigabyte)
                if (limitBytes != null) {
                    val autoUsed = state.autoDownloadUsedBytes
                    val fraction = (autoUsed.toDouble() / limitBytes).toFloat().coerceIn(0f, 1f)
                    val label = stringResource(Res.string.downloads_storage_auto, formatBytes(autoUsed), formatBytes(limitBytes))
                    val spokenAuto = stringResource(Res.string.downloads_storage_auto_spoken, spokenBytes(autoUsed), spokenBytes(limitBytes))
                    StorageLimitLine(
                        label = label,
                        spoken = spokenAuto,
                        limitGb = autoLimitGb,
                        onChange = if (onChangeStorageLimit != null) ({ pickerProfileId = profileId }) else null,
                    )
                    LinearProgressIndicator(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = spokenAuto
                            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                        },
                    )
                } else if (onChangeStorageLimit != null) {
                    StorageLimitLine(
                        label = stringResource(Res.string.downloads_storage_auto_no_limit),
                        spoken = stringResource(Res.string.downloads_storage_auto_no_limit),
                        limitGb = autoLimitGb,
                        onChange = { pickerProfileId = profileId },
                    )
                }
                if (onChangeStorageLimit != null) {
                    Text(
                        text = stringResource(Res.string.downloads_auto_storage_limit_profile_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.inUse.size > 1) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) Res.string.downloads_storage_hide_locations else Res.string.downloads_storage_show_locations,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (expanded) {
                    state.inUse.forEach { location ->
                        val line = if (location.availability == FolderAvailability.Available) {
                            stringResource(
                                Res.string.downloads_storage_location_line,
                                location.name, formatBytes(location.usedBytes), location.freeBytes?.let(::formatBytes) ?: "?",
                            )
                        } else {
                            stringResource(Res.string.downloads_storage_location_line_away, location.name, formatBytes(location.usedBytes))
                        }
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onManageLocations != null) {
                        TextButton(onClick = onManageLocations) {
                            Text(stringResource(Res.string.downloads_storage_manage), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
    if (pickerProfileId != null && onChangeStorageLimit != null) {
        val change = onChangeStorageLimit
        DownloadChoiceDialog(
            title = stringResource(Res.string.downloads_auto_storage_limit),
            options = AutoDownloadStore.storageLimitOptionsGb,
            selected = autoLimitGb,
            label = { autoDownloadStorageLimitLabel(it) },
            onSelect = { limit ->
                change(pickerProfileId ?: profileId, limit)
                pickerProfileId = null
            },
            onDismiss = { pickerProfileId = null },
        )
    }
}

@Composable
private fun StorageLimitLine(
    label: String,
    spoken: String,
    limitGb: Int?,
    onChange: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).semantics { contentDescription = spoken },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onChange != null) {
            val title = stringResource(Res.string.downloads_auto_storage_limit)
            val value = autoDownloadStorageLimitLabel(limitGb)
            TextButton(
                onClick = onChange,
                modifier = Modifier.semantics {
                    contentDescription = title
                    stateDescription = value
                },
            ) {
                Text(
                    text = stringResource(Res.string.downloads_storage_change_limit),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
