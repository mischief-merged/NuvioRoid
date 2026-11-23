package com.nuvio.app.core.memory

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nuvio.app.core.ui.configurePlatformImageLoader
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.collection.FolderDetailRepository
import com.nuvio.app.features.collection.FolderDetailUiState
import com.nuvio.app.features.collection.FolderTab
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.downloads.*
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.streams.BadgeImageLoader
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class MemoryReviewRegressionTest {
    @Test fun subtitlePreparationsReleaseTheirPathRegistry() = runBlocking {
        val root = Files.createTempDirectory("subtitle-lifetime")
        val previous = DownloadSubtitles.loadAddon
        DownloadSubtitles.loadAddon = { emptyList() }
        try {
            repeat(8) { index ->
                val video = root.resolve("video-$index.mkv").toFile().also { it.writeText("video") }
                DownloadSubtitles.prepare(item(), video.toURI().toString())
            }
            assertEquals(0, holderSize(DownloadSubtitles, "runs"))
        } finally { DownloadSubtitles.loadAddon = previous; root.toFile().deleteRecursively() }
    }

    @Test fun cancellingABackfillOwnerDoesNotStopAFinishedDownloadsCompanions() = runBlocking {
        val root = Files.createTempDirectory("companion-lifetime")
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val episodeList = EpisodeListCompanion(fetchMeta = { _, _ ->
            if (++calls == 1) { started.complete(Unit); awaitCancellation() }
            MetaDetails("tt1", "series", "Show")
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val marker = root.resolve("artwork.saved")
        val artwork = object : DownloadCompanion {
            override val name = "artwork"
            override fun isDue(item: DownloadItem, localVideoUri: String) = true
            override suspend fun run(item: DownloadItem, localVideoUri: String): CompanionOutcome {
                Files.writeString(marker, "saved")
                return CompanionOutcome.Saved
            }
        }
        try {
            val uri = root.resolve("video.mkv").toFile().also { it.writeText("video") }.toURI().toString()
            val owner = async(start = CoroutineStart.UNDISPATCHED) { episodeList.run(item(), uri) }
            started.await()
            val pipeline = DownloadCompanions(listOf(episodeList, artwork), isOnline = { true }, scope = scope, io = Dispatchers.Unconfined)
            val finished = pipeline.saveAfterDownload(item(), uri)
            owner.cancelAndJoin()
            withTimeout(3000) { finished.join() }
            assertFalse(finished.isCancelled)
            assertEquals("saved", Files.readString(marker))
        } finally { scope.cancel(); root.toFile().deleteRecursively() }
    }

    @Test fun badgeImagesUseTheHeapBudgetAndAreTrimmedThroughTheRealLoader() {
        val root = Files.createTempDirectory("badge-budget")
        val loader = BadgeImageLoader.get(PlatformContext.INSTANCE)
        val mainLoader = ImageLoader.Builder(PlatformContext.INSTANCE).configurePlatformImageLoader().build()
        try {
            assertSame(mainLoader.memoryCache, loader.memoryCache)
            assertEquals(imageMemoryBudget(Runtime.getRuntime().maxMemory()), loader.memoryCache!!.maxSize)
            val file = root.resolve("badge.png").toFile()
            ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", file)
            assertTrue(runBlocking { loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(file).build()) } is SuccessResult)
            assertTrue(loader.memoryCache!!.size > 0)
            DesktopMemoryCaches.trim()
            assertEquals(0, loader.memoryCache!!.size)
        } finally { mainLoader.shutdown(); root.toFile().deleteRecursively() }
    }

    @Test fun theCombinedFolderTabKeepsTheProductionWindowSize() {
        val field = FolderDetailRepository::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(null) as MutableStateFlow<FolderDetailUiState>
        val previous = state.value
        try {
            state.value = FolderDetailUiState(tabs = listOf(FolderTab(label = "All", isAllTab = true)) +
                (1..3).map { source -> FolderTab(label = "Source $source", isLoading = false,
                    items = (1..2000).map { MetaPreview(id = "$source-$it", type = "movie", name = "Video") }) })
            FolderDetailRepository::class.java.getDeclaredMethod("rebuildAllTab").apply { isAccessible = true }.invoke(FolderDetailRepository)
            assertEquals(2000, state.value.tabs.first { it.isAllTab }.items.size)
        } finally { state.value = previous }
    }

    @Test fun preferenceCachesEvictTheLeastRecentlyUsedResultAcrossReloads() {
        val root = Files.createTempDirectory("cache-recency")
        try {
            val file = root.resolve("results.properties")
            val store = DesktopStorage.Store(file, cacheEntries = 2)
            store.putString("a", "a")
            store.putString("b", "b")
            assertEquals("a", store.getString("a"))
            store.putString("c", "c")
            assertEquals(setOf("a", "c"), store.keys())
            val reopened = DesktopStorage.Store(file, cacheEntries = 2)
            reopened.putString("d", "d")
            assertEquals(setOf("c", "d"), reopened.keys())
        } finally { root.toFile().deleteRecursively() }
    }

    private fun item() = DownloadItem(id = "test", contentType = "series", parentMetaId = "tt1", parentMetaType = "series",
        videoId = "tt1:1:1", title = "Show", seasonNumber = 1, episodeNumber = 1, streamTitle = "Video", providerName = "Source",
        sourceUrl = "https://example.invalid/video", fileName = "video.mkv", status = DownloadStatus.Completed,
        createdAtEpochMs = 1, updatedAtEpochMs = 1)

    private fun holderSize(owner: Any, name: String): Int {
        val value = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        return if (value is Map<*, *>) value.size else value.javaClass.getMethod("getSize").invoke(value) as Int
    }
}
