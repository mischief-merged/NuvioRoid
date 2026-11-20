package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.downloads.AutoDownloads
import com.nuvio.app.features.downloads.WatchedRemovalGuard

/** Keep the surface from opening a local file until an overlapping removal has rolled back. */
@Composable
internal fun rememberReadyPlayerSource(sourceUrl: String?): String? {
    val localUri = sourceUrl?.takeIf { it.startsWith("file:") }
    var ready by remember(localUri) { mutableStateOf(localUri == null) }
    DisposableEffect(localUri) {
        if (localUri != null) AutoDownloads.onLocalFileOpened(localUri)
        onDispose { if (localUri != null) AutoDownloads.onLocalFileClosed(localUri) }
    }
    LaunchedEffect(localUri) {
        if (localUri != null) {
            WatchedRemovalGuard.awaitRemovalFinished()
            ready = true
        }
    }
    return sourceUrl.takeIf { ready }
}
