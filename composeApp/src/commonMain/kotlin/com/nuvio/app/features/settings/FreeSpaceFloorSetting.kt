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
import com.nuvio.app.features.downloads.FreeSpaceFloors
import com.nuvio.app.features.downloads.FreeSpaceHold
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FreeSpaceFloorSetting(
    isTablet: Boolean,
    hold: FreeSpaceHold = FreeSpaceFloors.hold,
    profileId: Int = ProfileRepository.activeProfileId,
) {
    LaunchedEffect(profileId) {
        hold.onProfileChanged()
    }
    val state by hold.state.collectAsStateWithLifecycle()
    var showDialog by remember(profileId) { mutableStateOf(false) }
    val title = stringResource(Res.string.settings_free_space_floor_title)
    SettingsNavigationRow(
        title = title,
        description = stringResource(Res.string.settings_free_space_floor_description),
        isTablet = isTablet,
        trailingContent = {
            Text(
                text = freeSpaceFloorLabel(state.floorGb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
        },
        onClick = { showDialog = true },
    )
    if (showDialog) {
        DownloadChoiceDialog(
            title = title,
            options = FreeSpaceHold.optionsGb,
            selected = state.floorGb,
            label = { freeSpaceFloorLabel(it) },
            onSelect = {
                hold.setFloorGb(value = it, profileId = profileId, notifyInBackground = true)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
internal fun freeSpaceFloorLabel(floorGb: Int?): String =
    if (floorGb == null) {
        stringResource(Res.string.settings_free_space_floor_off)
    } else {
        stringResource(Res.string.settings_free_space_floor_value, floorGb)
    }
