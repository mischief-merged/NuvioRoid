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
import com.nuvio.app.features.downloads.AutoDownloads
import com.nuvio.app.features.downloads.DeleteAfterWatchingMode
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DeleteAfterWatchingSetting(
    isTablet: Boolean,
    controller: AutoDownloadController = AutoDownloads,
    profileId: Int = ProfileRepository.activeProfileId,
) {
    LaunchedEffect(profileId) {
        if (controller === AutoDownloads) AutoDownloads.onProfileChanged()
    }
    val selected by controller.deleteAfterWatchingMode.collectAsStateWithLifecycle()
    var showDialog by remember(profileId) { mutableStateOf(false) }
    val title = stringResource(Res.string.settings_delete_after_watching_title)
    SettingsNavigationRow(
        title = title,
        description = stringResource(Res.string.settings_delete_after_watching_description),
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = deleteAfterWatchingLabel(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
        },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        DownloadChoiceDialog(
            title = title,
            options = DeleteAfterWatchingMode.entries.toList(),
            selected = selected,
            label = { deleteAfterWatchingLabel(it) },
            onSelect = {
                controller.setDeleteAfterWatchingMode(profileId, it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun deleteAfterWatchingLabel(mode: DeleteAfterWatchingMode): String = stringResource(
    when (mode) {
        DeleteAfterWatchingMode.Off -> Res.string.settings_delete_after_watching_off
        DeleteAfterWatchingMode.AutomaticOnly -> Res.string.settings_delete_after_watching_automatic
        DeleteAfterWatchingMode.AllDownloads -> Res.string.settings_delete_after_watching_all
    },
)
