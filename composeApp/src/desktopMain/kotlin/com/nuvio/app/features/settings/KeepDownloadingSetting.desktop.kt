package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_keep_downloading_title
import nuvio.composeapp.generated.resources.settings_keep_downloading_description
import org.jetbrains.compose.resources.stringResource

internal object BackgroundDownloadPreferences {
    private val store by lazy { DesktopStorage.store("nuvio_background_downloads") }
    private val enabled by lazy {
        MutableStateFlow(store.getString("keep_after_close")?.toBooleanStrictOrNull() ?: true)
    }
    val keepAfterClose get() = enabled.asStateFlow()

    fun setKeepAfterClose(value: Boolean) {
        store.putString("keep_after_close", value.toString())
        enabled.value = value
    }
}

@Composable
internal actual fun KeepDownloadingSetting(isTablet: Boolean) {
    if (DesktopHostOs.current != DesktopHostOs.MACOS) return
    val enabled by BackgroundDownloadPreferences.keepAfterClose.collectAsState()
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_keep_downloading_title),
        description = stringResource(Res.string.settings_keep_downloading_description),
        checked = enabled,
        isTablet = isTablet,
        onCheckedChange = BackgroundDownloadPreferences::setKeepAfterClose,
    )
}
