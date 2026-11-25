package com.nuvio.app.features.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.components.DownloadChoiceDialog
import com.nuvio.app.features.downloads.AutoDownloadController
import com.nuvio.app.features.downloads.AutoDownloadStore
import com.nuvio.app.features.downloads.AutoDownloads
import com.nuvio.app.features.downloads.autoDownloadStorageLimitLabel
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TotalStorageLimitSetting(
    isTablet: Boolean,
    controller: AutoDownloadController = AutoDownloads,
    profileId: Int = ProfileRepository.activeProfileId,
) {
    LaunchedEffect(profileId) {
        if (controller === AutoDownloads) AutoDownloads.onProfileChanged()
    }
    val selected by controller.totalStorageLimitGb.collectAsStateWithLifecycle()
    var showDialog by remember(profileId) { mutableStateOf(false) }
    val title = stringResource(Res.string.downloads_total_storage_limit)
    SettingsNavigationRow(
        title = title,
        description = stringResource(Res.string.downloads_total_storage_limit_description),
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = autoDownloadStorageLimitLabel(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
        },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        DownloadChoiceDialog(
            title = title,
            options = AutoDownloadStore.totalStorageLimitOptionsGb,
            selected = selected,
            label = { autoDownloadStorageLimitLabel(it) },
            onSelect = {
                controller.setTotalStorageLimit(profileId, it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
internal fun AutoDownloadStorageLimitSetting(
    isTablet: Boolean,
    controller: AutoDownloadController = AutoDownloads,
    profileId: Int = ProfileRepository.activeProfileId,
) {
    LaunchedEffect(profileId) {
        if (controller === AutoDownloads) AutoDownloads.onProfileChanged()
    }
    val selected by controller.storageLimitGb.collectAsStateWithLifecycle()
    var showDialog by remember(profileId) { mutableStateOf(false) }
    val title = stringResource(Res.string.downloads_auto_storage_limit)
    SettingsNavigationRow(
        title = title,
        description = stringResource(Res.string.downloads_auto_storage_limit_description),
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = autoDownloadStorageLimitLabel(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
        },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        DownloadChoiceDialog(
            title = title,
            options = AutoDownloadStore.storageLimitOptionsGb,
            selected = selected,
            label = { autoDownloadStorageLimitLabel(it) },
            onSelect = {
                controller.setStorageLimit(profileId, it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
internal fun MakeRoomAutomaticallySetting(
    isTablet: Boolean,
    controller: AutoDownloadController = AutoDownloads,
    profileId: Int = ProfileRepository.activeProfileId,
) {
    LaunchedEffect(profileId) {
        if (controller === AutoDownloads) AutoDownloads.onProfileChanged()
    }
    val enabled by controller.makeRoomAutomatically.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_make_room_title),
        description = stringResource(Res.string.settings_make_room_description),
        checked = enabled,
        isTablet = isTablet,
        onCheckedChange = { controller.setMakeRoomAutomatically(profileId, it) },
    )
}
