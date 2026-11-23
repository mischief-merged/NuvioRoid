package com.nuvio.app.features.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.MiniPlayerCornerRadius
import com.nuvio.app.MiniPlayerVideoHeight
import com.nuvio.app.MiniPlayerWidth
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_action_pause
import nuvio.composeapp.generated.resources.compose_player_close
import nuvio.composeapp.generated.resources.compose_player_episode_code_episode_only
import nuvio.composeapp.generated.resources.compose_player_episode_code_full
import nuvio.composeapp.generated.resources.compose_player_episode_title_format
import nuvio.composeapp.generated.resources.compose_player_reopen
import nuvio.composeapp.generated.resources.compose_player_seek_forward_10
import nuvio.composeapp.generated.resources.detail_btn_play
import nuvio.composeapp.generated.resources.player_next_episode
import nuvio.composeapp.generated.resources.player_seek_position
import org.jetbrains.compose.resources.stringResource

internal val MiniPlayerControlsHeight = 68.dp
internal const val MiniPlayerDockTestTag = "mini_player_dock"
internal const val MiniPlayerProgressTestTag = "mini_player_progress"

@Composable
internal fun formatMiniPlayerEpisodeText(state: MiniPlayerPlaybackState): String {
    val season = state.seasonNumber
    val episode = state.episodeNumber
    val episodeTitle = state.episodeTitle?.takeIf { it.isNotBlank() }
    return when {
        season != null && episode != null && episodeTitle != null ->
            stringResource(Res.string.compose_player_episode_title_format, season, episode, episodeTitle)
        season != null && episode != null ->
            stringResource(Res.string.compose_player_episode_code_full, season, episode)
        episode != null ->
            stringResource(Res.string.compose_player_episode_code_episode_only, episode)
        episodeTitle != null -> episodeTitle
        else -> ""
    }
}

@Composable
internal fun MiniPlayerDock(
    state: MiniPlayerPlaybackState,
    canPlayNextEpisode: Boolean,
    onTogglePlayback: () -> Unit,
    onSeekForward: () -> Unit,
    onPlayNextEpisode: () -> Unit,
    onClose: () -> Unit,
    onReopenFullPlayer: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val episodeText = formatMiniPlayerEpisodeText(state)
    val progress = state.progressFraction
    val progressDescription = stringResource(Res.string.player_seek_position)
    val reopenDescription = stringResource(Res.string.compose_player_reopen)
    val playPauseDescription = stringResource(
        if (state.isPlaying) Res.string.compose_action_pause else Res.string.detail_btn_play,
    )
    val seekForwardDescription = stringResource(Res.string.compose_player_seek_forward_10)
    val nextEpisodeDescription = stringResource(Res.string.player_next_episode)
    val closeDescription = stringResource(Res.string.compose_player_close)

    Surface(
        modifier = modifier
            .width(MiniPlayerWidth)
            .testTag(MiniPlayerDockTestTag),
        shape = RoundedCornerShape(MiniPlayerCornerRadius),
        color = tokens.colors.surfacePopover,
        tonalElevation = tokens.elevation.playerControls,
        shadowElevation = tokens.elevation.modal,
        border = BorderStroke(tokens.borders.thin, tokens.colors.borderDefault),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top 16:9 slot framing the live heavyweight player surface rendered above it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MiniPlayerVideoHeight)
                    .background(Color.Black)
                    .then(
                        if (onReopenFullPlayer != null) {
                            Modifier.clickable(
                                role = Role.Button,
                                onClickLabel = reopenDescription,
                                onClick = onReopenFullPlayer,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )

            // Bottom Compose control strip positioned outside the AWT SwingPanel bounds.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MiniPlayerControlsHeight),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NuvioTokens.Space.s3)
                        .background(tokens.colors.playerTimelineTrack)
                        .testTag(MiniPlayerProgressTestTag)
                        .semantics {
                            contentDescription = progressDescription
                            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(tokens.colors.playerTimelineFill),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (onReopenFullPlayer != null) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = reopenDescription,
                                    onClick = onReopenFullPlayer,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(start = NuvioTokens.Space.s12, end = NuvioTokens.Space.s6),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                    ) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = tokens.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (episodeText.isNotBlank()) {
                            Text(
                                text = episodeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiniPlayerIconButton(
                            icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = playPauseDescription,
                            onClick = onTogglePlayback,
                        )
                        MiniPlayerIconButton(
                            icon = Icons.Rounded.Forward10,
                            contentDescription = seekForwardDescription,
                            onClick = onSeekForward,
                        )
                        if (canPlayNextEpisode) {
                            MiniPlayerIconButton(
                                icon = Icons.Rounded.SkipNext,
                                contentDescription = nextEpisodeDescription,
                                onClick = onPlayNextEpisode,
                            )
                        }
                        MiniPlayerIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = closeDescription,
                            onClick = onClose,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(NuvioTokens.Space.s32),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tokens.colors.textPrimary,
            modifier = Modifier.size(tokens.icons.md),
        )
    }
}
