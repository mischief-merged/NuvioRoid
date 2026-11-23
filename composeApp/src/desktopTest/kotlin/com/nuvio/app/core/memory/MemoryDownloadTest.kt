package com.nuvio.app.core.memory

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.downloads.*
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryDownloadTest {
    @Test fun publishedFileIdentityLivesOnlyUntilTheSuccessCallbackReturns() {
        val bytes = ByteArray(1024) { 7 }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/video") { request ->
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        server.start()
        val name = "memory-test-${System.nanoTime()}.mkv"
        val id = "memory-${System.nanoTime()}"
        val done = CountDownLatch(1)
        var uri: String? = null
        var failure: String? = null
        var presentDuringCallback = false
        val freeBefore = DownloadsPlatformDownloader.freeBytesProvider
        DownloadsPlatformDownloader.freeBytesProvider = { null }
        try {
            DownloadsPlatformDownloader.start(DownloadPlatformRequest(DownloadItem(
                id = id, contentType = "movie", parentMetaId = "tt0", parentMetaType = "movie", videoId = "tt0",
                title = "Video", streamTitle = "Video", providerName = "Source", fileName = name,
                sourceUrl = "http://127.0.0.1:${server.address.port}/video", status = DownloadStatus.Downloading,
                createdAtEpochMs = 1, updatedAtEpochMs = 1,
            )), onProgress = { _, _ -> }, onSuccess = { path, _ ->
                uri = path
                presentDuringCallback = publishedKeys().containsKey(path)
                done.countDown()
            }, onFailure = { failure = it; done.countDown() }, onPaused = { done.countDown() })
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertNull(failure)
            assertTrue(presentDuringCallback)
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (WriterTokens.kindOf(id) != null && System.nanoTime() < until) Thread.sleep(10)
            assertNull(WriterTokens.kindOf(id))
            assertFalse(publishedKeys().containsKey(uri))
            assertTrue(File(URI(uri!!)).readBytes().contentEquals(bytes))
        } finally {
            DownloadsPlatformDownloader.freeBytesProvider = freeBefore
            server.stop(0)
            uri?.let { File(URI(it)).delete() }
            DesktopStorage.rootDir.resolve("downloads/$name.part").toFile().delete()
        }
    }

    @Test fun folderSnapshotsForgetLocationsThatAreNoLongerConfigured() {
        val world = FolderWorld()
        try {
            val drive = world.driveLocation("Drive")
            val usage = DownloadStorageUsage(world.locations)
            usage.current(emptyList(), mapOf(DownloadLocations.AppId to FolderAvailability.Available, drive.id to FolderAvailability.Available))
            assertEquals(2, lastKnown(usage).size)
            world.locations.forgetExcept(emptySet())
            usage.invalidate()
            usage.current(emptyList(), mapOf(DownloadLocations.AppId to FolderAvailability.Available))
            assertEquals(setOf(DownloadLocations.AppId), lastKnown(usage).keys)
        } finally { world.close() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun publishedKeys(): Map<String, String> = DownloadsPlatformDownloader::class.java.getDeclaredField("publishedKeys").let {
        it.isAccessible = true
        it.get(null) as Map<String, String>
    }

    private fun lastKnown(usage: DownloadStorageUsage): Map<*, *> = usage.javaClass.getDeclaredField("lastKnown").let {
        it.isAccessible = true
        it.get(usage) as Map<*, *>
    }
}
