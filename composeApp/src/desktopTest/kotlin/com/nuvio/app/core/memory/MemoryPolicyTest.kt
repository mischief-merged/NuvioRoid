package com.nuvio.app.core.memory

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.ui.acceptsImageDimensions
import com.nuvio.app.core.ui.configurePlatformImageLoader
import com.nuvio.app.core.ui.addPlatformDecoders
import com.nuvio.app.core.ui.readBoundedImageBytes
import com.nuvio.app.core.ui.MaxEncodedImageBytes
import com.nuvio.app.features.downloads.FsGuard
import com.nuvio.app.features.downloads.SidecarIo
import com.nuvio.app.features.downloads.SidecarLocks
import com.nuvio.app.features.player.desktop.WebviewImages
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryPolicyTest {
    @Test fun browseWindowsRemainBoundedWhileNewPagesKeepArriving() {
        var window = emptyList<Int>()
        repeat(1000) { page ->
            val incoming = (page * 100 until (page + 1) * 100).toList()
            val merged = window + incoming
            assertTrue(merged.size > window.size)
            window = retainBrowseWindow(merged)
            assertTrue(window.size <= BrowseWindowSize)
            assertEquals(incoming.last(), window.last())
        }
        assertEquals((98000 until 100000).toList(), window)
    }

    @Test fun commentPagesEvictOldResultsAndCanFetchThemAgain() {
        var pages = emptyMap<Int, String>()
        repeat(500) { page ->
            pages = rememberRecentPage(pages, page, "$page")
            assertTrue(pages.size <= 8)
        }
        assertEquals((492..499).toSet(), pages.keys)
        pages = rememberRecentPage(pages, 0, "fresh")
        assertEquals("fresh", pages[0])
        assertEquals(8, pages.size)
        assertFalse(pages.containsKey(492))
    }

    @Test fun cacheEvictsLeastRecentlyUsedAndSupportsNullsAndViews() {
        val cache = BoundedCache<Int, String?>(2)
        cache[1] = null
        cache[2] = "two"
        assertTrue(cache.containsKey(1))
        assertNull(cache[1])
        cache[3] = "three"
        assertFalse(cache.containsKey(2))
        cache.putAll((4..500).associateWith { "$it" })
        assertEquals(2, cache.size)
        cache.entries.removeAll { it.key == 499 }
        assertEquals(setOf(500), cache.keys)
        cache.values.remove("500")
        assertTrue(cache.isEmpty())
    }

    @Test fun concurrentWritersAndTrimmingKeepTheCapacity() {
        val cache = BoundedCache<Int, Int>(8)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = (0..3).map { worker -> workers.submit {
                repeat(1000) { i ->
                    cache[worker * 1000 + i] = i
                    cache.entries.forEach { it.value }
                    if (i % 100 == 0) DesktopMemoryCaches.trim()
                    assertTrue(cache.size <= 8)
                }
            } }
            results.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { workers.shutdown() }
        DesktopMemoryCaches.trim()
        assertEquals(0, cache.size)
    }

    @Test fun pressureRequiresCollectionsAndDoesNotRepeatUntilRecovery() {
        var trims = 0
        var notices = 0
        val policy = HeapPressurePolicy(0.8, 0.6, 2, { trims++ }, { notices++ })
        policy.sample(90, 100)
        assertEquals(0, trims)
        policy.sample(70, 100)
        policy.sample(90, 100)
        assertEquals(0, trims)
        repeat(100) { policy.sample(90, 100) }
        assertEquals(1, trims)
        assertEquals(1, notices)
        policy.sample(50, 100)
        repeat(2) { policy.sample(90, 100) }
        assertEquals(2, trims)
        assertEquals(2, notices)
    }

    @Test fun pressureFailuresAreContainedAndNotRetriedInALoop() {
        var calls = 0
        val policy = HeapPressurePolicy(requiredSamples = 1, trim = { calls++; error("unavailable") }, notice = { error("unavailable") })
        repeat(20) { policy.sample(90, 100) }
        assertEquals(1, calls)
        policy.sample(-1, 100)
        policy.sample(90, -1)
    }

    @Test fun watchdogOnlyCountsNewCompletedCollections() {
        var collections = 1L
        var notices = 0
        val monitor = HeapWatchdog(intervalMs = 60_000, collectionCount = { collections }, heap = { 90L to 100L },
            policy = HeapPressurePolicy(requiredSamples = 2, trim = {}, notice = { notices++ }))
        try {
            repeat(5) { monitor.poll() }
            assertEquals(0, notices)
            collections++
            monitor.poll()
            repeat(5) { monitor.poll() }
            assertEquals(0, notices)
            collections++
            monitor.poll()
            assertEquals(1, notices)
        } finally { monitor.close(); monitor.close() }
    }

    @Test fun imageBudgetTracksHeapAndTrimsTheRealImageCache() {
        assertEquals(64L * 1024 * 1024, imageMemoryBudget(512L * 1024 * 1024))
        assertEquals(256L * 1024 * 1024, imageMemoryBudget(2L * 1024 * 1024 * 1024))
        assertEquals(256L * 1024 * 1024, imageMemoryBudget(Long.MAX_VALUE))
        val root = Files.createTempDirectory("image-budget")
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).configurePlatformImageLoader().build()
        try {
            val file = root.resolve("poster.png").toFile()
            ImageIO.write(BufferedImage(1200, 1800, BufferedImage.TYPE_INT_RGB), "png", file)
            val result = runBlocking { loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(file).size(80, 120).build()) }
            assertTrue(result is SuccessResult)
            assertTrue(result.image.width <= 80 && result.image.height <= 120)
            assertTrue(loader.memoryCache!!.size > 0)
            assertEquals(imageMemoryBudget(Runtime.getRuntime().maxMemory()), loader.memoryCache!!.maxSize)
            DesktopMemoryCaches.trim()
            assertEquals(0, loader.memoryCache!!.size)
        } finally { loader.shutdown(); root.toFile().deleteRecursively() }
    }

    @Test fun controlsImagesDecodeToThumbnailSizeThroughTheRealFilePath() {
        val root = Files.createTempDirectory("controls-image")
        try {
            val path = root.resolve("avatar.jpg").toFile()
            ImageIO.write(BufferedImage(2400, 1600, BufferedImage.TYPE_INT_RGB), "jpg", path)
            val ready = CountDownLatch(1)
            assertEquals("", WebviewImages.resolve(path.toURI().toString()) { ready.countDown() })
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            val encoded = WebviewImages.resolve(path.toURI().toString()) {}
            val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(encoded.substringAfter(','))))
            assertEquals(112, image.width)
            assertEquals(112, image.height)
        } finally { DesktopMemoryCaches.trim(); root.toFile().deleteRecursively() }
    }

    @Test fun onlyReplaceablePreferenceCachesAreBoundedOnDiskAndReload() {
        val root = Files.createTempDirectory("preference-cache")
        try {
            val file = root.resolve("cache.properties")
            val cache = DesktopStorage.Store(file, cacheEntries = 8)
            repeat(32) { cache.putString("key-$it", "$it") }
            assertEquals(8, cache.freshEntries().size)
            assertEquals("31", cache.getString("key-31"))
            assertEquals(8, DesktopStorage.Store(file, cacheEntries = 8).freshEntries().size)
            val records = DesktopStorage.Store(root.resolve("records.properties"))
            repeat(32) { records.putString("key-$it", "$it") }
            assertEquals(32, records.freshEntries().size)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun sidecarLocksKeepWaitersTogetherAndReleaseAfterFailure() {
        val pool = Executors.newFixedThreadPool(4)
        val active = AtomicInteger()
        val largest = AtomicInteger()
        try {
            val results = (1..4).map { pool.submit {
                repeat(100) {
                    runCatching { SidecarLocks.withLock("file:/test/shared") {
                        val count = active.incrementAndGet()
                        largest.updateAndGet { maxOf(it, count) }
                        try { Thread.yield(); error("write failed") } finally { active.decrementAndGet() }
                    } }
                }
            } }
            results.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, largest.get())
        } finally { pool.shutdown() }
    }

    @Test fun disconnectedDrivesCannotAccumulateFilesystemWorkers() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(4)
        try {
            repeat(4) { assertEquals("timeout", FsGuard.call(100, "timeout") {
                started.countDown()
                while (release.count > 0) { try { release.await() } catch (_: InterruptedException) {} }
                "done"
            }) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(100) { assertEquals("busy", FsGuard.call(100, "busy") { error("must not queue more work") }) }
        } finally { release.countDown() }
    }

    @Test fun rejectedSidecarDispatchesDoNotLeakWorkPermits() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        dispatcher.close()
        repeat(8) { runCatching { SidecarIo.read(dispatcher = dispatcher) { 1 } } }
        assertEquals(42, SidecarIo.read { 42 })
    }

    @Test fun disconnectedSidecarReadsHaveNoUnboundedQueue() = runBlocking {
        val release = CountDownLatch(1)
        try {
            repeat(4) { assertNull(SidecarIo.read(100) { release.await(); "late" }) }
            repeat(100) { assertNull(SidecarIo.read<String>(100) { error("must not queue more work") }) }
        } finally { release.countDown() }
    }

    @Test fun svgRasterAndEncodedInputsHaveFiniteBounds() {
        val scale = com.nuvio.app.core.ui.svgRasterScale(100_000f, 1_000_000f)
        assertTrue(100_000f * scale <= 800.01f)
        assertTrue(1_000_000f * scale <= 2048.01f)
        val large = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100000\" height=\"1000000\"><rect width=\"100000\" height=\"1000000\" fill=\"red\"/></svg>"
        runBlocking { com.nuvio.app.core.ui.renderSvgWithImages(large) { error("no embedded image") } }!!.use { bitmap ->
            assertTrue(bitmap.width <= 800 && bitmap.height <= 2048)
        }
        kotlin.test.assertFails { com.nuvio.app.core.ui.svgRasterScale(Float.POSITIVE_INFINITY, 1f) }
        kotlin.test.assertFails { okio.Buffer().write(ByteArray(33)).readBoundedImageBytes(32) }
        assertEquals(32, okio.Buffer().write(ByteArray(32)).readBoundedImageBytes(32).size)
        val tooMany = "<svg width=\"100\" height=\"100\">" +
            "<image href=\"data:bad\" width=\"1\" height=\"1\"/>".repeat(com.nuvio.app.core.ui.MaxEmbeddedSvgImages + 1) + "</svg>"
        kotlin.test.assertFails { runBlocking { com.nuvio.app.core.ui.renderSvgWithImages(tooMany) { error("must reject before loading") } } }
    }

    @Test fun recursiveSvgImagesStopAtTheNestingLimitThroughTheRealLoader() {
        val requests = AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${server.address.port}/loop.svg"
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><image href=\"$url\" width=\"100\" height=\"100\"/></svg>".encodeToByteArray()
        server.createContext("/loop.svg") { request ->
            requests.incrementAndGet()
            request.responseHeaders.add("Content-Type", "image/svg+xml")
            request.sendResponseHeaders(200, svg.size.toLong())
            request.responseBody.use { it.write(svg) }
        }
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).diskCache(null)
            .components { add(com.nuvio.app.core.ui.SvgEmbeddedImageDecoder.Factory()) }.build()
        server.start()
        try {
            val result = runBlocking { loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(url).size(100).build()) }
            assertTrue(result is SuccessResult)
            assertEquals(com.nuvio.app.core.ui.MaxEmbeddedSvgDepth + 1, requests.get())
        } finally { loader.shutdown(); server.stop(0) }
    }

    @Test fun oversizedAnimationsAreRejectedBeforePixelAllocation() {
        assertTrue(acceptsImageDimensions(3840, 2160, 1))
        assertFalse(acceptsImageDimensions(10000, 10000, 1))
        assertFalse(acceptsImageDimensions(64, 64, 1001))
        assertFalse(acceptsImageDimensions(Int.MAX_VALUE, Int.MAX_VALUE, 1))
        assertEquals(16L * 1024 * 1024, MaxEncodedImageBytes)
    }
}
