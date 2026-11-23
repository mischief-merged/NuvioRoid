package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.features.plugins.PluginsSettingsPageContent

internal actual fun LazyListScope.pluginsSettingsContent() {
    item { NetworkTrustSettings() }
    item {
        PluginsSettingsPageContent(
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@androidx.compose.runtime.Composable
private fun NetworkTrustSettings() {
    var hosts by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.nuvio.app.core.network.TrustedHomeServers.hosts()) }
    var address by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var invalid by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Text(stringResource(Res.string.network_trusted_servers), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        androidx.compose.material3.Text(stringResource(Res.string.network_trusted_servers_help))
        androidx.compose.material3.OutlinedTextField(value = address, onValueChange = { address = it; invalid = false },
            label = { androidx.compose.material3.Text(stringResource(Res.string.network_server_address)) }, singleLine = true,
            isError = invalid, modifier = Modifier.fillMaxWidth())
        if (invalid) androidx.compose.material3.Text(stringResource(Res.string.network_invalid_server))
        androidx.compose.material3.TextButton(onClick = {
            invalid = !com.nuvio.app.core.network.TrustedHomeServers.add(address)
            if (!invalid) { address = ""; hosts = com.nuvio.app.core.network.TrustedHomeServers.hosts() }
        }) { androidx.compose.material3.Text(stringResource(Res.string.network_add_server)) }
        androidx.compose.material3.TextButton(onClick = {
            com.nuvio.app.features.player.desktop.PlaybackNetworkTrust.choices.forget()
            if (com.nuvio.app.features.player.desktop.DesktopHostOs.current == com.nuvio.app.features.player.desktop.DesktopHostOs.MACOS) {
                runCatching { com.nuvio.app.features.player.desktop.NativePlayerBridge.requireVerifiedPlayback() }
            }
        }) { androidx.compose.material3.Text(stringResource(Res.string.playback_tls_forget)) }
        hosts.sorted().forEach { host ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text(host, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = {
                    com.nuvio.app.core.network.TrustedHomeServers.remove(host)
                    hosts = com.nuvio.app.core.network.TrustedHomeServers.hosts()
                }) { androidx.compose.material3.Text(stringResource(Res.string.network_remove_server)) }
            }
        }
    }
}
