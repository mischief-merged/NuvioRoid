package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.EpisodeAvailability
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.availability
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun EpisodeAvailabilityBadge(
    video: MetaVideo,
    isOffline: Boolean = false,
    isAvailableOffline: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (video.availability(offline = isOffline, hasLocalDownload = isAvailableOffline) == EpisodeAvailability.Playable) return

    Text(
        text = stringResource(Res.string.episodes_unavailable).uppercase(),
        modifier = modifier
            .background(Color(0xCC5D4037), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
