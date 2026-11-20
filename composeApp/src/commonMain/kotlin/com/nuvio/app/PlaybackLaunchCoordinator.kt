package com.nuvio.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadSubtitles
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.player_loading_preparing
import org.jetbrains.compose.resources.stringResource

internal data class VerifiedLocalPlayback(
    val item: DownloadItem,
    val sourceUrl: String,
    val externalSubtitles: List<StreamSubtitle>,
)

internal suspend fun resolveVerifiedLocalPlayback(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    videoId: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    findPlayable: (String, Int?, Int?, String?) -> DownloadItem? = DownloadsRepository::findPlayableDownload,
    verifyCompleted: (String) -> Boolean = DownloadsRepository::verifyCompletedDownload,
    resolveUri: (DownloadItem) -> String? = DownloadsRepository::playableLocalFileUri,
    loadSubtitles: (String) -> List<StreamSubtitle> = DownloadSubtitles::localSubtitles,
): VerifiedLocalPlayback? = withContext(ioDispatcher) {
    val downloadedItem = findPlayable(parentMetaId, seasonNumber, episodeNumber, videoId)
        ?: return@withContext null
    if (!verifyCompleted(downloadedItem.id)) return@withContext null
    val sourceUrl = resolveUri(downloadedItem)?.takeIf { it.isNotBlank() }
        ?: return@withContext null
    VerifiedLocalPlayback(
        item = downloadedItem,
        sourceUrl = sourceUrl,
        externalSubtitles = loadSubtitles(sourceUrl),
    )
}

internal suspend fun verifyDownloadedItemForPlayback(
    item: DownloadItem,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    verifyCompleted: (String) -> Boolean = DownloadsRepository::verifyCompletedDownload,
    resolveUri: (DownloadItem) -> String? = DownloadsRepository::playableLocalFileUri,
    loadSubtitles: (String) -> List<StreamSubtitle> = DownloadSubtitles::localSubtitles,
): VerifiedLocalPlayback? = withContext(ioDispatcher) {
    if (item.isPlaceholder || !verifyCompleted(item.id)) return@withContext null
    val sourceUrl = resolveUri(item)?.takeIf { it.isNotBlank() }
        ?: return@withContext null
    VerifiedLocalPlayback(
        item = item,
        sourceUrl = sourceUrl,
        externalSubtitles = loadSubtitles(sourceUrl),
    )
}

internal class PlaybackPreparationController(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val resolveLocalPlayback: suspend (
        parentMetaId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        videoId: String,
    ) -> VerifiedLocalPlayback? = { parentMetaId, seasonNumber, episodeNumber, videoId ->
        resolveVerifiedLocalPlayback(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            videoId = videoId,
            ioDispatcher = ioDispatcher,
        )
    },
    private val verifyDownloadItem: suspend (DownloadItem) -> VerifiedLocalPlayback? = { item ->
        verifyDownloadedItemForPlayback(item = item, ioDispatcher = ioDispatcher)
    },
) {
    var isPreparing by mutableStateOf(false)
        private set
    private var activeJob: Job? = null

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        isPreparing = false
    }

    fun launchWithDownloadPreference(
        manualSelection: Boolean,
        downloadsEnabled: Boolean,
        parentMetaId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        videoId: String,
        onStreamFallback: () -> Unit,
        onLocalPlaybackReady: (VerifiedLocalPlayback) -> Unit,
    ) {
        if (manualSelection || !downloadsEnabled) {
            onStreamFallback()
            return
        }
        launchAsync(
            resolve = { resolveLocalPlayback(parentMetaId, seasonNumber, episodeNumber, videoId) },
            onReady = { resolved ->
                if (resolved != null) onLocalPlaybackReady(resolved) else onStreamFallback()
            },
        )
    }

    fun openDownloadedItem(
        item: DownloadItem,
        onUnavailable: () -> Unit,
        onLocalPlaybackReady: (VerifiedLocalPlayback) -> Unit,
    ) {
        if (item.isPlaceholder) {
            onUnavailable()
            return
        }
        launchAsync(
            resolve = { verifyDownloadItem(item) },
            onReady = { resolved ->
                if (resolved != null) onLocalPlaybackReady(resolved) else onUnavailable()
            },
        )
    }

    private fun launchAsync(
        resolve: suspend () -> VerifiedLocalPlayback?,
        onReady: (VerifiedLocalPlayback?) -> Unit,
    ) {
        if (activeJob?.isActive == true) return
        isPreparing = true
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val resolved = resolve()
                if (activeJob !== coroutineContext[Job]) return@launch
                activeJob = null
                isPreparing = false
                onReady(resolved)
            } finally {
                if (activeJob === coroutineContext[Job]) {
                    activeJob = null
                    isPreparing = false
                }
            }
        }
        activeJob = job
        job.start()
    }
}

@Composable
internal fun PlaybackPreparationOverlay(
    visible: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformBackHandler(enabled = visible, onBack = onCancel)
    if (!visible) return
    val message = stringResource(Res.string.player_loading_preparing)
    val cancelDescription = stringResource(Res.string.action_cancel)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.nuvio.colors.surfaceElevated,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NuvioLoadingIndicator(modifier = Modifier.size(22.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = cancelDescription,
                        tint = MaterialTheme.nuvio.colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
