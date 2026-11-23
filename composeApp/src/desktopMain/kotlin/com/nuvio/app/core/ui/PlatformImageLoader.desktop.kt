package com.nuvio.app.core.ui

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.memory.MemoryCache
import com.nuvio.app.core.memory.imageMemoryBudget

// All desktop image loaders draw from one budget, including badges.
private val platformImageCache = lazy {
    MemoryCache.Builder().maxSizeBytes(imageMemoryBudget(Runtime.getRuntime().maxMemory())).build()
}

internal fun trimImageMemoryCaches() {
    if (platformImageCache.isInitialized()) runCatching { platformImageCache.value.clear() }
}

internal actual val platformProvidesImageLoader: Boolean = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    memoryCache { platformImageCache.value }

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() {
    add(SkiaGifDecoder.Factory())
    add(SvgEmbeddedImageDecoder.Factory())
}
