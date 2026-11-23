package com.nuvio.app.core.ui

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.memory.MemoryCache

import com.nuvio.app.core.memory.imageMemoryBudget
import java.lang.ref.WeakReference

private val imageCaches = mutableListOf<WeakReference<MemoryCache>>()

internal fun trimImageMemoryCaches() {
    val live = synchronized(imageCaches) {
        imageCaches.removeAll { it.get() == null }
        imageCaches.mapNotNull { it.get() }
    }
    live.forEach { runCatching { it.clear() } }
}

internal actual val platformProvidesImageLoader: Boolean = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    return memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(imageMemoryBudget(Runtime.getRuntime().maxMemory()))
            .build().also { cache ->
                synchronized(imageCaches) {
                    imageCaches.removeAll { it.get() == null }
                    imageCaches.add(WeakReference(cache))
                }
            }
    }
}

internal actual fun ComponentRegistry.Builder.addPlatformDecoders() {
    add(SkiaGifDecoder.Factory())
    add(SvgEmbeddedImageDecoder.Factory())
}
