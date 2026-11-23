package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.NuvioStatusModal
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_replace_playback_confirm
import nuvio.composeapp.generated.resources.player_replace_playback_keep
import nuvio.composeapp.generated.resources.player_replace_playback_message
import nuvio.composeapp.generated.resources.player_replace_playback_message_generic
import nuvio.composeapp.generated.resources.player_replace_playback_title
import org.jetbrains.compose.resources.stringResource

internal data class PendingPlaybackReplacement(
    val currentTitle: String,
    val incomingTitle: String,
    val onConfirm: () -> Unit,
    /** A trailer or other preview. Confirming it is refused once previews are no longer allowed. */
    val requiresPreview: Boolean = false,
)

internal sealed interface ReplacePlaybackDecision {
    data object ProceedNow : ReplacePlaybackDecision
    data object ReopenActive : ReplacePlaybackDecision
    data class AskFirst(
        val currentTitle: String,
        val incomingTitle: String,
    ) : ReplacePlaybackDecision
}

internal fun formatPlaybackItemLabel(
    title: String,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): String {
    val cleanTitle = title.trim()
    return when {
        seasonNumber != null && episodeNumber != null && cleanTitle.isNotEmpty() ->
            "$cleanTitle (S$seasonNumber E$episodeNumber)"
        episodeNumber != null && cleanTitle.isNotEmpty() ->
            "$cleanTitle (E$episodeNumber)"
        else -> cleanTitle
    }
}

internal fun decidePlaybackLaunch(
    activeState: MiniPlayerPlaybackState?,
    activeLaunch: PlayerLaunch?,
    incomingTitle: String,
    videoId: String?,
    parentMetaId: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    sourceUrl: String? = null,
    allowSameTargetReopen: Boolean = true,
): ReplacePlaybackDecision {
    if (activeState == null && activeLaunch == null) {
        return ReplacePlaybackDecision.ProceedNow
    }
    if (
        allowSameTargetReopen &&
        matchesActivePlaybackTarget(
            activeState = activeState,
            activeLaunch = activeLaunch,
            videoId = videoId,
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            sourceUrl = sourceUrl,
        )
    ) {
        return ReplacePlaybackDecision.ReopenActive
    }
    val currentLabel = formatPlaybackItemLabel(
        title = activeState?.title?.takeIf { it.isNotBlank() } ?: activeLaunch?.title.orEmpty(),
        seasonNumber = activeState?.seasonNumber ?: activeLaunch?.seasonNumber,
        episodeNumber = activeState?.episodeNumber ?: activeLaunch?.episodeNumber,
    )
    val nextLabel = formatPlaybackItemLabel(
        title = incomingTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
    return ReplacePlaybackDecision.AskFirst(
        currentTitle = currentLabel,
        incomingTitle = nextLabel,
    )
}

@Composable
internal fun ReplacePlaybackDialog(
    pending: PendingPlaybackReplacement?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (pending == null) return
    val message = if (pending.currentTitle.isNotBlank()) {
        stringResource(
            Res.string.player_replace_playback_message,
            pending.currentTitle,
            pending.incomingTitle,
        )
    } else {
        stringResource(
            Res.string.player_replace_playback_message_generic,
            pending.incomingTitle,
        )
    }
    NuvioStatusModal(
        title = stringResource(Res.string.player_replace_playback_title),
        message = message,
        isVisible = true,
        confirmText = stringResource(Res.string.player_replace_playback_confirm),
        dismissText = stringResource(Res.string.player_replace_playback_keep),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
