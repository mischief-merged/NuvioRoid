package com.nuvio.app.core.memory

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nuvio.app.core.ui.AnimatedSkiaImage
import com.nuvio.app.core.ui.FramePlayer
import com.nuvio.app.core.ui.NuvioAnimatedImageKey
import com.nuvio.app.core.ui.SkiaGifDecoder
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MemoryReviewAnimationTest {
    @Test fun enqueuePrunesDeadWaitingPlayers() = withPlayer { source, queue ->
        val player = FramePlayer(source)
        synchronized(queue) { queue.addLast(WeakReference(null)) }
        FramePlayer::class.java.getDeclaredMethod("enqueue").apply { isAccessible = true }.invoke(player)
        synchronized(queue) {
            assertEquals(1, queue.size)
            assertSame(player, queue.first().get())
        }
    }

    @Test fun handingOffASlotSkipsDeadWaitersAndStartsTheNextLivePlayer() = withPlayer { source, queue ->
        val player = FramePlayer(source)
        synchronized(queue) { queue.addLast(WeakReference(null)); queue.addLast(WeakReference(player)) }
        FramePlayer::class.java.getDeclaredMethod("startNextWaiting").apply { isAccessible = true }.invoke(player)
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (player.frame === source.firstFrame) {
            assertTrue(System.nanoTime() < until)
            Thread.sleep(10)
        }
        synchronized(queue) { assertTrue(queue.isEmpty()) }
    }

    private fun withPlayer(check: (AnimatedSkiaImage, ArrayDeque<WeakReference<FramePlayer>>) -> Unit) {
        val root = Files.createTempDirectory("animation-queue")
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).components { add(SkiaGifDecoder.Factory()) }.build()
        val owner = Class.forName("com.nuvio.app.core.ui.AnimatedSkiaImageKt")
        val active = owner.getDeclaredField("activePlayers").apply { isAccessible = true }.get(null) as AtomicInteger
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (active.get() != 0) { assertTrue(System.nanoTime() < until); Thread.sleep(10) }
        @Suppress("UNCHECKED_CAST")
        val queue = owner.getDeclaredField("waitingPlayers").apply { isAccessible = true }.get(null) as ArrayDeque<WeakReference<FramePlayer>>
        val previous = synchronized(queue) { queue.toList().also { queue.clear() } }
        try {
            val file = root.resolve("preview.gif").toFile().also { it.writeBytes(Base64.getDecoder().decode(GIF)) }
            val source = runBlocking {
                (loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(file)
                    .apply { extras[NuvioAnimatedImageKey] = true }.build()) as SuccessResult).image as AnimatedSkiaImage
            }
            check(source, queue)
        } finally {
            synchronized(queue) { queue.clear(); queue.addAll(previous) }
            loader.shutdown()
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val GIF = "R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBFAABACwAAAAAAgACAIEAgAAAAAAAAAAAAAAIBgABCAQQEAA7"
    }
}
