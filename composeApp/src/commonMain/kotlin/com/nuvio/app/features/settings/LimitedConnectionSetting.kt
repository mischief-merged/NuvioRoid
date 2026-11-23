package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import com.nuvio.app.features.details.components.DownloadChoiceDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.downloads.ConnectionHold
import com.nuvio.app.features.downloads.LimitedConnections
import com.nuvio.app.features.downloads.updateInBackground
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Playback > Pause downloads on hotspots and Low Data Mode networks. On by default; while it
 * is on, downloads wait on such a network and carry on by themselves when it is left.
 */
@Composable
internal fun LimitedConnectionSetting(isTablet: Boolean, hold: ConnectionHold = LimitedConnections.hold) {
    val state by hold.state.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_pause_on_limited_title),
        description = stringResource(Res.string.settings_pause_on_limited_description),
        checked = state.enabled,
        isTablet = isTablet,
        onCheckedChange = hold::setEnabled,
    )
}

/**
 * Settings > Playback > Let other apps go first. On by default: downloads slow down whenever other apps
 * need the connection, and automatic downloads wait while the network is very slow.
 */
@Composable
internal fun PoliteDownloadsSetting(isTablet: Boolean) {
    val connection by LimitedConnections.hold.state.collectAsStateWithLifecycle()
    val enabled by com.nuvio.app.features.downloads.PoliteDownloadsHost.enabled.collectAsStateWithLifecycle()
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_polite_downloads_title),
        description = stringResource(if (connection.travel.enabled) Res.string.travel_polite_required else Res.string.settings_polite_downloads_description),
        checked = connection.travel.yieldingEnabled(enabled),
        enabled = !connection.travel.enabled,
        isTablet = isTablet,
        onCheckedChange = com.nuvio.app.features.downloads.PoliteDownloadsHost::setEnabled,
    )
}

@Composable
internal fun TravelModeSettings(isTablet: Boolean, hold: ConnectionHold = LimitedConnections.hold) {
    val connection by hold.state.collectAsStateWithLifecycle()
    val travel = connection.travel
    SettingsSwitchRow(
        title = stringResource(Res.string.travel_mode_title),
        description = stringResource(Res.string.travel_mode_description),
        checked = travel.enabled,
        isTablet = isTablet,
        onCheckedChange = { value -> hold.updateInBackground { setTravelEnabled(value) } },
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.travel_network_title),
        description = stringResource(if (travel.canRemember) Res.string.travel_network_description else Res.string.travel_network_unknown),
        checked = travel.remembered,
        enabled = travel.canRemember,
        isTablet = isTablet,
        onCheckedChange = { value -> hold.updateInBackground { rememberTravelNetwork(value) } },
    )
    SettingsGroupDivider(isTablet = isTablet)
    var choosingCap by remember { mutableStateOf(false) }
    val title = stringResource(Res.string.travel_speed_title)
    SettingsNavigationRow(
        title = title,
        description = stringResource(Res.string.travel_speed_description),
        isTablet = isTablet,
        trailingContent = { Text(stringResource(Res.string.travel_speed_value, (travel.capBytesPerSecond / 1000).toInt())) },
        onClick = { choosingCap = true },
    )
    if (choosingCap) {
        DownloadChoiceDialog(
            title = title,
            options = listOf(250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L),
            selected = travel.capBytesPerSecond,
            label = { stringResource(Res.string.travel_speed_value, (it / 1000).toInt()) },
            onSelect = { value -> hold.updateInBackground { setTravelCap(value) }; choosingCap = false },
            onDismiss = { choosingCap = false },
        )
    }
}
