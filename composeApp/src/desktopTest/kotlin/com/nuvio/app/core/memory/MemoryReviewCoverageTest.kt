package com.nuvio.app.core.memory

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.SkiaGifDecoder
import com.nuvio.app.features.downloads.*
import com.nuvio.app.features.player.desktop.WebviewImageLoader
import com.nuvio.app.features.player.desktop.WebviewImages
import java.awt.image.BufferedImage
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.imageio.IIOImage
import kotlinx.coroutines.*
import kotlin.test.*

class MemoryReviewCoverageTest {
    @Test fun availabilitySnapshotsForgetRemovedLocationsThroughRefresh() {
        val world = FolderWorld()
        try {
            world.driveLocation("Drive")
            val folders = DesktopDownloadFolders(world.locations, world.validator, world.mover(), ProfileHooks(emptyList()))
            folders.refresh()
            assertEquals(2, availability(folders).size)
            world.locations.forgetExcept(emptySet())
            folders.refresh()
            assertEquals(setOf(DownloadLocations.AppId), availability(folders).keys)
        } finally { world.close() }
    }

    @Test fun saturatedControlsWorkersDropRejectedLoadsAndCanRetryRealFiles() {
        val root = Files.createTempDirectory("controls-workers")
        val release = CountDownLatch(1)
        val started = CountDownLatch(2)
        val ready = CountDownLatch(2)
        val active = AtomicInteger()
        val largest = AtomicInteger()
        val cache = BoundedCache<String, String>(64)
        val loader = WebviewImageLoader(cache) { uri ->
            val count = active.incrementAndGet()
            largest.updateAndGet { maxOf(it, count) }
            try { started.countDown(); release.await(); WebviewImages.encode(uri) }
            finally { active.decrementAndGet() }
        }
        try {
            val urls = (1..3).map { index -> root.resolve("$index.png").toFile().let {
                ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", it)
                it.toURI().toString()
            } }
            loader.resolve(urls[0]) { ready.countDown() }
            loader.resolve(urls[1]) { ready.countDown() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(100) { loader.resolve(urls[2]) { error("rejected work must not run") } }
            assertEquals(2, loader.pendingCount)
            assertEquals(2, loader.maximumWorkers)
            release.countDown()
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            waitFor { loader.pendingCount == 0 }
            val retried = CountDownLatch(1)
            waitFor { loader.resolve(urls[2]) { retried.countDown() }; retried.await(10, TimeUnit.MILLISECONDS) }
            waitFor { loader.pendingCount == 0 }
            assertEquals(2, largest.get())
            assertTrue(cache[urls[2]]!!.startsWith("data:image/png;base64,"))
        } finally { release.countDown(); loader.close(); root.toFile().deleteRecursively() }
    }

    @Test fun brokenControlsImagesReleaseTheirLoadingEntry() {
        val loader = WebviewImageLoader(BoundedCache(64)) { error("unreadable") }
        try {
            loader.resolve("file:/missing") { error("not ready") }
            waitFor { loader.pendingCount == 0 }
            assertEquals("", loader.resolve("file:/missing") {})
            waitFor { loader.pendingCount == 0 }
        } finally { loader.close() }
    }

    @Test fun oversizedGifBytesAreRejectedByTheActualDecoder() = rejectedGif { file ->
        file.writeBytes(gif() + ByteArray(16 * 1024 * 1024))
    }

    @Test fun oversizedGifDimensionsAreRejectedByTheActualDecoder() = rejectedGif { file ->
        val bytes = gif()
        bytes[6] = 0x10; bytes[7] = 0x27
        bytes[8] = 0x10; bytes[9] = 0x27
        file.writeBytes(bytes)
    }

    @Test fun tooManyGifFramesAreRejectedByTheActualDecoder() = rejectedGif { file ->
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        try {
            ImageIO.createImageOutputStream(file).use { output ->
                writer.output = output
                writer.prepareWriteSequence(null)
                val frame = IIOImage(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), null, null)
                repeat(1001) { writer.writeToSequence(frame, null) }
                writer.endWriteSequence()
            }
        } finally { writer.dispose() }
    }

    @Test fun deadFileGateRegistrationsArePrunedWithoutReplacingLiveOwners() {
        val root = Files.createTempDirectory("gate-lifetime")
        val livePath = root.resolve("live.lock")
        val deadPath = root.resolve("dead.lock")
        val live = DesktopStorage.FileGate.of(livePath)
        val field = DesktopStorage.FileGate::class.java.getDeclaredField("gates").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val gates = field.get(null) as MutableMap<Path, WeakReference<DesktopStorage.FileGate>>
        try {
            synchronized(gates) { gates[deadPath] = WeakReference(null) }
            assertSame(live, DesktopStorage.FileGate.of(livePath))
            synchronized(gates) { assertFalse(gates.containsKey(deadPath)) }
            assertSame(live, DesktopStorage.FileGate.of(livePath))
        } finally {
            synchronized(gates) { gates.remove(livePath); gates.remove(deadPath) }
            root.toFile().deleteRecursively()
        }
    }

    @Test fun atomicCacheLookupDistinguishesCachedNullFromConcurrentTrim() {
        val cache = BoundedCache<Int, String?>()
        cache[1] = null
        val hit = cache.lookup(1)!!
        cache.clear()
        assertNull(hit.value)
        assertNull(cache.lookup(1))
        cache[1] = "saved"
        val saved = cache.lookup(1)!!
        cache.clear()
        assertEquals("saved", saved.value)
    }

    @Test fun sharedWorkReleasesEveryPathAfterFailureAndWaitingCallerCancellation() = runBlocking {
        val work = SharedLookup<String, Int>()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) { work.get("one") { entered.complete(Unit); finish.await(); 7 } }
        entered.await()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { work.get("one") { error("duplicate") } }
        waiter.cancelAndJoin()
        assertEquals(1, work.size)
        finish.complete(Unit)
        assertEquals(7, owner.await())
        assertEquals(0, work.size)
        repeat(1000) { key -> runCatching { work.get("$key") { error("failed") } } }
        assertEquals(0, work.size)
    }

    @Test fun serializedWorkDoesNotReuseAWeakerRequestOrKeepCompletedKeys() = runBlocking {
        val work = SharedLookup<String, Int>()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { work.sequential("one") { started.complete(Unit); finish.await(); 1 } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { work.sequential("one") { 2 } }
        finish.complete(Unit)
        assertEquals(1, first.await())
        assertEquals(2, second.await())
        assertEquals(0, work.size)
    }

    @Test fun textCallDeadlineDoesNotLeakIntoTheSharedMediaClient() {
        assertEquals(0, com.nuvio.app.features.addons.desktopHttpClient.callTimeoutMillis)
        assertEquals(60_000, com.nuvio.app.features.addons.desktopTextClient.callTimeoutMillis)
    }

    private fun rejectedGif(write: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("gif-bound")
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).components { add(SkiaGifDecoder.Factory()) }.build()
        try {
            val file = root.resolve("image.gif").toFile()
            write(file)
            val result = runBlocking { loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(file).build()) }
            assertTrue(result is ErrorResult, "The actual decoder must reject the oversized input")
        } finally { loader.shutdown(); root.toFile().deleteRecursively() }
    }

    private fun gif() = Base64.getDecoder().decode("R0lGODlhAgACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQAFAAAACwAAAAAAgACAAAIBgABCAQQEAAh+QQBFAABACwAAAAAAgACAIEAgAAAAAAAAAAAAAAIBgABCAQQEAA7")
    private fun availability(owner: DesktopDownloadFolders) = owner.javaClass.getDeclaredField("lastAvailability").let {
        it.isAccessible = true
        it.get(owner) as Map<*, *>
    }
    private fun waitFor(check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) { assertTrue(System.nanoTime() < until, "work did not finish"); Thread.sleep(10) }
    }
}
