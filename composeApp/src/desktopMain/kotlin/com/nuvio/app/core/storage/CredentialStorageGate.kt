package com.nuvio.app.core.storage

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CredentialStorageGate(content: @Composable () -> Unit) {
    val blocked by credentialAccess.blocked.collectAsState()
    var ready by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        ready = false
        val available = credentialAccess.run {
            if (attempt > 0) com.nuvio.app.core.network.SupabaseProvider.reset()
            withContext(Dispatchers.IO) { DesktopStorage.prepareCredentials() }
        }
        if (available) {
            if (attempt > 0) AuthRepository.reinitialize()
            ready = true
        }
    }
    if (ready && !blocked) {
        content()
    } else {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (blocked) {
                        Text(stringResource(Res.string.secure_storage_unavailable))
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { ready = false; credentialAccess.retry(); attempt++ }) { Text(stringResource(Res.string.action_retry)) }
                    } else CircularProgressIndicator()
                }
            }
        }
    }
}
