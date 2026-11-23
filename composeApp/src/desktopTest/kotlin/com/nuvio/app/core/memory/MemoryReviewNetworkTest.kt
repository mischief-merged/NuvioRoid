package com.nuvio.app.core.memory

import com.nuvio.app.features.addons.*
import com.nuvio.app.features.mdblist.*
import com.nuvio.app.features.autosync.EmbeddedSubtitleTimelineLoader
import com.nuvio.app.features.catalog.*
import com.nuvio.app.features.collection.*
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.party.*
import com.nuvio.app.features.search.SearchRepository
import com.nuvio.app.features.trakt.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlin.test.*

class MemoryReviewNetworkTest {
    @Test fun catalogPaginationKeepsFetchingAfterTheWindowFills() = runBlocking {
        withServer(::catalogBody) { base ->
            CatalogRepository.clear()
            try {
                CatalogRepository.load(CatalogTarget.Addon("$base/manifest.json", "movie", "feed", supportsPagination = true))
                waitUntil { !CatalogRepository.uiState.value.isLoading }
                repeat(3) {
                    CatalogRepository.loadMore()
                    waitUntil { !CatalogRepository.uiState.value.isLoading }
                }
                val state = CatalogRepository.uiState.value
                assertNull(state.errorMessage)
                assertEquals(2000, state.items.size)
                assertTrue(state.nextSkip!! >= 4000)
                assertTrue(state.items.any { it.id == "3999" })
            } finally { CatalogRepository.clear() }
        }
    }

    @Test fun discoverPaginationKeepsFetchingAfterTheWindowFills() = runBlocking {
        withServer(::catalogBody) { base ->
            SearchRepository.reset()
            try {
                val addon = ManagedAddon("$base/manifest.json", AddonManifest("fixture", "Source", "", "1",
                    resources = listOf(AddonResource("catalog", listOf("movie"))), types = listOf("movie"),
                    catalogs = listOf(AddonCatalog("movie", "feed", "Feed", listOf(AddonExtraProperty("skip")))), transportUrl = base))
                SearchRepository.refreshDiscover(listOf(addon), forceRefresh = true)
                waitUntil { !SearchRepository.discoverUiState.value.isLoading }
                repeat(3) {
                    SearchRepository.loadMoreDiscover()
                    waitUntil { !SearchRepository.discoverUiState.value.isLoading }
                }
                val state = SearchRepository.discoverUiState.value
                assertNull(state.errorMessage)
                assertEquals(2000, state.items.size)
                assertTrue(state.nextSkip!! >= 4000)
                assertTrue(state.items.any { it.id == "3999" })
            } finally { SearchRepository.reset() }
        }
    }

    @Test fun folderSourcePaginationAndCombinedTabStayBoundedTogether() = runBlocking {
        withServer(::catalogBody) { base ->
            FolderDetailRepository.clear()
            val state = state<FolderDetailUiState>(FolderDetailRepository, "_uiState")
            try {
                state.value = FolderDetailUiState(tabs = listOf(
                    FolderTab(label = "All", isAllTab = true),
                    FolderTab(label = "Source", manifestUrl = "$base/manifest.json", type = "movie", catalogId = "feed",
                        supportsPagination = true, nextSkip = 0, isLoading = false)), selectedTabIndex = 0)
                repeat(4) {
                    FolderDetailRepository.loadMoreSelectedTab()
                    waitUntil { !state.value.tabs[1].isLoading && !state.value.tabs[1].isLoadingMore }
                }
                assertEquals(2000, state.value.tabs[0].items.size)
                assertEquals(2000, state.value.tabs[1].items.size)
                assertTrue(state.value.tabs[1].nextSkip!! >= 4000)
                assertTrue(state.value.tabs[0].items.any { it.id == "3999" })
            } finally { FolderDetailRepository.clear() }
        }
    }

    @Test fun commentPagesEvictAndReloadThroughTheRealRequestAndRepository() = runBlocking {
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        withServer({ uri -> counts.computeIfAbsent(uri.query.orEmpty()) { AtomicInteger() }.incrementAndGet(); "[]" }) { base ->
            val oldHeaders = TraktCommentsRepository.requestHeaders
            val oldRequest = TraktCommentsRepository.requestPage
            TraktCommentsRepository.clearCache()
            TraktCommentsRepository.requestHeaders = { emptyMap() }
            TraktCommentsRepository.requestPage = { url, headers -> httpRequestRaw("GET", base + "/comments?" + url.substringAfter('?'), headers, "") }
            try {
                val meta = MetaDetails("tt1234567", "movie", "Video", imdbId = "tt1234567")
                repeat(12) { TraktCommentsRepository.getCommentsPage(meta, page = it + 1) }
                val cache = field(TraktCommentsRepository, "cache").get(null) as Map<*, *>
                val cached = cache.values.single()!!
                val pages = field(cached, "pages").get(cached) as Map<*, *>
                assertEquals((5..12).toSet(), pages.keys)
                TraktCommentsRepository.getCommentsPage(meta, page = 12)
                assertEquals(12, counts.values.sumOf { it.get() })
                TraktCommentsRepository.getCommentsPage(meta, page = 1)
                assertEquals(13, counts.values.sumOf { it.get() })
            } finally {
                TraktCommentsRepository.requestHeaders = oldHeaders
                TraktCommentsRepository.requestPage = oldRequest
                TraktCommentsRepository.clearCache()
            }
        }
    }

    @Test fun cancelledTrackerEpisodeOwnerDoesNotBecomeAnEmptyAnswerForActiveWaiters() = runBlocking {
        episodeProviders { base ->
            val started = CompletableDeferred<Unit>()
            var calls = 0
            TraktEpisodeMappingService.episodeHeaders = { emptyMap() }
            TraktEpisodeMappingService.episodeText = { _, headers ->
                if (++calls == 1) { started.complete(Unit); awaitCancellation() }
                httpGetTextWithHeaders("$base/episodes", headers)
            }
            val owner = async(start = CoroutineStart.UNDISPATCHED) { TraktEpisodeMappingService.getTraktEpisodes("show") }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { TraktEpisodeMappingService.getTraktEpisodes("show") }
            owner.cancelAndJoin()
            assertEquals(2, withTimeout(3000) { waiter.await() }.size)
            assertEquals(2, calls)
            assertEquals(0, flights("traktEpisodesInFlight").size)
        }
    }

    @Test fun cancelledAddonEpisodeOwnerDoesNotBecomeAnEmptyAnswerForActiveWaiters() = runBlocking {
        episodeProviders { base ->
            val started = CompletableDeferred<Unit>()
            var calls = 0
            TraktEpisodeMappingService.addonEpisodeLoader = { _, _ ->
                if (++calls == 1) { started.complete(Unit); awaitCancellation() }
                assertTrue(httpGetText("$base/episodes").contains("episodes"))
                listOf(EpisodeMappingEntry(1, 1))
            }
            val owner = async(start = CoroutineStart.UNDISPATCHED) { TraktEpisodeMappingService.getAddonEpisodes("show", "series") }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { TraktEpisodeMappingService.getAddonEpisodes("show", "series") }
            owner.cancelAndJoin()
            assertEquals(listOf(EpisodeMappingEntry(1, 1)), withTimeout(3000) { waiter.await() })
            assertEquals(2, calls)
            assertEquals(0, flights("addonEpisodesInFlight").size)
        }
    }

    @Test fun failedOrUnauthorizedEpisodeFlightsAreRemovedAndCanBeRetried() = runBlocking {
        episodeProviders { base ->
            TraktEpisodeMappingService.episodeHeaders = { null }
            assertEquals(emptyList(), TraktEpisodeMappingService.getTraktEpisodes("show"))
            assertEquals(0, flights("traktEpisodesInFlight").size)
            TraktEpisodeMappingService.episodeHeaders = { emptyMap() }
            TraktEpisodeMappingService.episodeText = { _, _ -> error("offline") }
            assertEquals(emptyList(), TraktEpisodeMappingService.getTraktEpisodes("show"))
            assertEquals(0, flights("traktEpisodesInFlight").size)
            TraktEpisodeMappingService.episodeText = { _, headers -> httpGetTextWithHeaders("$base/episodes", headers) }
            assertEquals(2, TraktEpisodeMappingService.getTraktEpisodes("show").size)
            TraktEpisodeMappingService.addonEpisodeLoader = { _, _ -> error("offline") }
            assertEquals(emptyList(), TraktEpisodeMappingService.getAddonEpisodes("show", "series"))
            assertEquals(0, flights("addonEpisodesInFlight").size)
        }
    }

    @Test fun cancellingAnEpisodeWaiterDoesNotCancelItsOwner() = runBlocking {
        episodeProviders { base ->
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            TraktEpisodeMappingService.episodeHeaders = { emptyMap() }
            TraktEpisodeMappingService.episodeText = { _, headers -> started.complete(Unit); finish.await(); httpGetTextWithHeaders("$base/episodes", headers) }
            val owner = async(start = CoroutineStart.UNDISPATCHED) { TraktEpisodeMappingService.getTraktEpisodes("show") }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { TraktEpisodeMappingService.getTraktEpisodes("show") }
            waiter.cancelAndJoin()
            assertTrue(owner.isActive)
            finish.complete(Unit)
            assertEquals(2, owner.await().size)
            assertEquals(0, flights("traktEpisodesInFlight").size)
        }
    }

    @Test fun cancelledAccountEpisodeOwnerLetsAnotherCallerResolveItsMapping() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val service = MdbListEpisodeMappingService(scope, addonEpisodes = {
                if (++calls == 1) { started.complete(Unit); awaitCancellation() }
                listOf(EpisodeMappingEntry(1, 1))
            }, tmdb = object : MdbListTmdbEpisodes {
                override suspend fun showId(contentId: String) = 1L
                override suspend fun seasonCounts(showId: Long) = mapOf(1 to 1)
                override suspend fun episodes(showId: Long, season: Int) = emptyList<EpisodeMappingEntry>()
            })
            val projection = MdbListProgressProjection(MdbListSyncSnapshot(accountId = 1))
            val owner = async(start = CoroutineStart.UNDISPATCHED) { service.resolve("show", projection) }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { service.resolve("show", projection) }
            owner.cancelAndJoin()
            assertSame(MdbListEpisodeMapping.Unchanged, waiter.await())
            assertEquals(2, calls)
            assertEquals(0, (field(service, "inFlight").get(service) as SharedLookup<*, *>).size)
        } finally { scope.cancel() }
    }

    @Test fun cancelledWatchedSnapshotOwnerLetsAnActiveCallerFetchItsOwnSnapshot() = runBlocking {
        withServer({ "[{\"last_watched_at\":\"2026-08-09T10:00:00.000Z\",\"show\":{\"title\":\"Video\",\"ids\":{\"trakt\":1,\"imdb\":\"tt1\"}},\"seasons\":[{\"number\":1,\"episodes\":[{\"number\":1,\"plays\":1}]}]}]" }) { base ->
            val started = CompletableDeferred<Unit>()
            var calls = 0
            val store = TraktWatchedShowSnapshotStore(TraktWatchedPageClient(TraktWatchedHttpEngine { _, headers, _ ->
                if (++calls == 1) { started.complete(Unit); awaitCancellation() }
                httpRequestRaw("GET", "$base/watched", headers, "")
            }))
            val owner = async(start = CoroutineStart.UNDISPATCHED) { store.get(emptyMap()) }
            started.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { store.get(emptyMap()) }
            owner.cancelAndJoin()
            assertEquals(1, waiter.await().size)
            assertEquals(2, calls)
        }
    }

    @Test fun cancelledEmbeddedTimelineOwnerRetriesWithoutRecursiveWaiterCalls() = runBlocking {
        withServer({ "unsupported container" }) { base ->
            val old = EmbeddedSubtitleTimelineLoader.indexLoader
            val started = CompletableDeferred<Unit>()
            var calls = 0
            EmbeddedSubtitleTimelineLoader.indexLoader = { _, _ ->
                if (++calls == 1) { started.complete(Unit); awaitCancellation() }
                httpGetText("$base/index")
                null
            }
            try {
                val owner = async(start = CoroutineStart.UNDISPATCHED) { EmbeddedSubtitleTimelineLoader.load(base) }
                started.await()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { EmbeddedSubtitleTimelineLoader.load(base) }
                owner.cancelAndJoin()
                assertNull(withTimeout(3000) { waiter.await() })
                assertEquals(2, calls)
                assertEquals(0, (field(EmbeddedSubtitleTimelineLoader, "inFlight").get(null) as SharedLookup<*, *>).size)
            } finally { EmbeddedSubtitleTimelineLoader.indexLoader = old }
        }
    }

    @Test fun chatStartPrunesOldMemberIdsAgainstTheRetainedRows() = runBlocking {
        val oldHistory = PartyChat.loadHistory
        val roomField = field(PartyChat, "room")
        val oldRoom = roomField.get(null)
        val messages = state<List<PartyChatMessage>>(PartyChat, "_messages")
        val oldMessages = messages.value
        @Suppress("UNCHECKED_CAST")
        val ids = field(PartyChat, "ownMemberIds").get(null) as MutableSet<String>
        val oldIds = ids.toSet()
        val scope = field(PartyChat, "scope").get(null) as CoroutineScope
        try {
            roomField.set(null, PartyChatRoom("test-room", "old", emptyList()))
            ids.clear(); ids.addAll((0..999).map { "member-$it" })
            messages.value = (0..199).map { PartyChatMessage("$it", it.toLong(), "member-$it", "Member", null, null, "Text", 1, true) }
            PartyChat.loadHistory = { _, _, _ -> PartyChatHistory() }
            PartyChat.start(PartyChatRoom("test-room", "current", emptyList()))
            waitUntil { scope.coroutineContext[Job]!!.children.none { it.isActive } }
            assertEquals(201, ids.size)
            assertTrue("current" in ids && "member-0" in ids)
            assertFalse("member-500" in ids)
        } finally {
            PartyChat.loadHistory = oldHistory
            roomField.set(null, oldRoom)
            messages.value = oldMessages
            ids.clear(); ids.addAll(oldIds)
        }
    }

    private suspend fun episodeProviders(block: suspend (String) -> Unit) {
        val oldAddon = TraktEpisodeMappingService.addonEpisodeLoader
        val oldHeaders = TraktEpisodeMappingService.episodeHeaders
        val oldText = TraktEpisodeMappingService.episodeText
        TraktEpisodeMappingService.clearCache()
        try { withServer({ "[{\"number\":1,\"episodes\":[{\"season\":1,\"number\":1,\"title\":\"First\"},{\"season\":1,\"number\":2,\"title\":\"Second\"}]}]" }, block) }
        finally {
            TraktEpisodeMappingService.addonEpisodeLoader = oldAddon
            TraktEpisodeMappingService.episodeHeaders = oldHeaders
            TraktEpisodeMappingService.episodeText = oldText
            TraktEpisodeMappingService.clearCache()
        }
    }

    private fun flights(name: String) = field(TraktEpisodeMappingService, name).get(null) as SharedLookup<*, *>
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(owner: Any, name: String) = field(owner, name).get(null) as MutableStateFlow<T>
    private suspend fun waitUntil(check: () -> Boolean) = withTimeout(5000) { while (!check()) delay(10) }
    private fun catalogBody(uri: URI): String {
        val skip = Regex("skip=(\\d+)").find(uri.path)?.groupValues?.get(1)?.toInt() ?: 0
        return (skip until skip + 1000).joinToString(prefix = "{\"metas\":[", postfix = "]}") {
            "{\"id\":\"$it\",\"type\":\"movie\",\"name\":\"Video $it\"}"
        }
    }
    private suspend fun withServer(body: (URI) -> String, block: suspend (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            val bytes = body(request.requestURI).encodeToByteArray()
            request.responseHeaders.add("Content-Type", "application/json")
            request.responseHeaders.add("X-Pagination-Page-Count", "1")
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }
}
