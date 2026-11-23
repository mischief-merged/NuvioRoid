package com.nuvio.app.features.home.components

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nuvio.app.core.ui.AnimatedSkiaImage
import com.nuvio.app.core.ui.NuvioAnimatedImageKey
import com.nuvio.app.core.ui.SkiaGifDecoder
import androidx.compose.ui.unit.IntSize
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ImageInfo

class CollectionCardGifCodecLifetimeTest {
    @Test
    fun evictingCachedImageDoesNotCloseAHolderStillUsedByACard() {
        val root = Files.createTempDirectory("collection-lifetime")
        val cache = MemoryCache.Builder().maxSizeBytes(512).weakReferencesEnabled(false).build()
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .memoryCache { cache }
            .components { add(SkiaGifDecoder.Factory()) }
            .build()
        try {
            val file = root.resolve("preview.gif").toFile().also { it.writeBytes(Base64.getDecoder().decode(GIF)) }
            fun load(index: Int): SuccessResult = runBlocking {
                val request = ImageRequest.Builder(PlatformContext.INSTANCE).data(file)
                    .memoryCacheKey("collection-lifetime-$index")
                    .apply { extras[NuvioAnimatedImageKey] = true }.build()
                loader.execute(request) as SuccessResult
            }
            val first = load(0)
            val retained = first.image as AnimatedSkiaImage
            val key = first.memoryCacheKey!!
            assertTrue(cache[key] != null)
            repeat(16) { load(it + 1) }
            assertTrue(cache.size <= 512)
            assertNull(cache[key], "The first image must actually be evicted")
            assertFalse(retained.data.isClosed)
            assertFalse(retained.firstFrame.isClosed, "Eviction must not invalidate a card's retained frame")
            Codec.makeFromData(retained.data).use { codec ->
                Bitmap().use { bitmap ->
                    bitmap.allocPixels(ImageInfo.makeN32Premul(2, 2))
                    codec.readPixels(bitmap, 0)
                    codec.readPixels(bitmap, 1)
                    assertEquals(2, codec.frameCount)
                }
            }
            val frameBefore = retained.player.frame
            val until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (retained.player.frame === frameBefore && System.nanoTime() < until) {
                retained.player.onDrawn(IntSize(2, 2))
                Thread.sleep(10)
            }
            assertTrue(retained.player.frame !== frameBefore, "A retained card must still decode after cache eviction")
        } finally {
            loader.shutdown()
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val GIF = "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBFAABACwAAAAAAgACAIEAgAAAAAAAAAAAAAAIBgABCAQQEAA7"
    }
}
