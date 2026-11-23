package com.nuvio.app.features.home.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.nuvio.app.core.ui.SkiaGifDecoder
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioDesktopAnimation

/** Collection previews share the image loader's heap budget and bounded animation players. */
@Composable
internal actual fun CollectionCardRemoteImage(
    imageUrl: String,
    staticImageUrl: String?,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val playing = animateIfPossible && (hovered || staticImageUrl.isNullOrBlank())
    val displayUrl = if (playing) imageUrl else staticImageUrl?.takeIf { it.isNotBlank() } ?: imageUrl
    val context = LocalPlatformContext.current
    val request = remember(context, displayUrl, playing) {
        ImageRequest.Builder(context).data(displayUrl).size(512)
            .apply { if (playing) decoderFactory(SkiaGifDecoder.Factory()) }
            .build()
    }
    NuvioAsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.hoverable(interaction),
        contentScale = contentScale,
        desktopAnimation = if (playing) NuvioDesktopAnimation.Always else NuvioDesktopAnimation.Off,
    )
}
