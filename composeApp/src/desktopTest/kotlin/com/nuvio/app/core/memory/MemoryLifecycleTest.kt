package com.nuvio.app.core.memory

import com.nuvio.app.core.network.TrustedLocalServerTest
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.readResponseBody
import com.nuvio.app.features.downloads.DownloadCompanions
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadRechecks
import com.nuvio.app.features.downloads.EpisodeListCompanion
import com.nuvio.app.features.downloads.OfflineSkipTimes
import com.nuvio.app.features.downloads.RecheckOutcome
import com.nuvio.app.features.mdblist.*
import com.nuvio.app.features.trakt.EpisodeMappingEntry
import com.nuvio.app.features.servers.ServerProviders
import com.nuvio.app.features.party.PartyChatMessage
import com.nuvio.app.features.party.PartyChatStatus
import com.nuvio.app.features.party.appendPartyChatMessage
import com.nuvio.app.features.servers.ServerMatcher
import com.nuvio.app.features.watchprogress.ProviderProgressMetadataOverlay
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class MemoryLifecycleTest : TrustedLocalServerTest() {
    @Test fun companionAndOverlayCachesHaveFiniteCapacity() {
        checkMaps(EpisodeListCompanion(), "shows")
        checkMaps(DownloadCompanions(emptyList()), "failedAt")
        checkMaps(OfflineSkipTimes(), "failedAt")
        checkMaps(DownloadRechecks({ emptyList() }, { null }, { false }, { RecheckOutcome.Skipped }), "lookedAt")
        checkMaps(ServerMatcher(), "convertedImdb")
        checkMaps(ProviderProgressMetadataOverlay(), "metadataByKey")
        val harness = MdbListTestHarness()
        checkMaps(MdbListRatingsRepository(MdbListRatingsClient(harness.api, harness.store)), "cache")
        ServerProviders.registered
            .filterIsInstance<com.nuvio.app.features.servers.mediabrowser.MediaBrowserProvider>()
            .forEach { checkMaps(it, "aioStreamsServers", "libraryGenres") }
    }

    @Test fun episodeMappingsFollowTheCurrentProjectionAndPrefetchReleasesItsNames() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            val service = MdbListEpisodeMappingService(scope,
                addonEpisodes = { listOf(EpisodeMappingEntry(1, 1)) },
                tmdb = object : MdbListTmdbEpisodes {
                    override suspend fun showId(contentId: String): Long = 1L
                    override suspend fun seasonCounts(showId: Long) = mapOf(1 to 1)
                    override suspend fun episodes(showId: Long, season: Int) = emptyList<EpisodeMappingEntry>()
                })
            val projection = MdbListProgressProjection(MdbListSyncSnapshot(accountId = 1))
            repeat(500) { service.resolve("show-$it", projection) }
            val resolved = field(service, "mutableResolved").get(service) as kotlinx.coroutines.flow.StateFlow<*>
            assertEquals(1, (resolved.value as Map<*, *>).size)
            service.prefetch(listOf("fresh-show"), projection)
            val pending = field(service, "prefetched").get(service) as kotlinx.coroutines.flow.StateFlow<*>
            assertEquals(0, (pending.value as Set<*>).size)
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun cancelledEpisodeOwnersReleaseTheirFlightAndActiveWaitersRetry() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val companion = EpisodeListCompanion(fetchMeta = { _, _ ->
            if (++calls == 1) { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            com.nuvio.app.features.details.MetaDetails("tt1", "series", "Show")
        })
        val item = item()
        val owner = async { companion.run(item, "file:/test/video") }
        started.await()
        val waiter = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { companion.run(item, "file:/test/video") }
        kotlinx.coroutines.yield()
        owner.cancelAndJoin()
        assertEquals(com.nuvio.app.features.downloads.CompanionOutcome.NothingFound, waiter.await())
        assertEquals(2, calls)
        assertEquals(0, (field(companion, "showLoads").get(companion) as SharedLookup<*, *>).size)
    }

    @Test fun failedEpisodeLookupsDoNotRetainOldShowLocks() = runBlocking {
        val companion = EpisodeListCompanion(fetchMeta = { _, _ -> null })
        repeat(500) { companion.run(item().copy(parentMetaId = "show-$it"), "file:/test/video") }
        assertEquals(0, (field(companion, "showLoads").get(companion) as SharedLookup<*, *>).size)
    }

    @Test fun failedChatSendsUseTheSameWindowLimitAsReceivedMessages() {
        var messages = emptyList<PartyChatMessage>()
        repeat(1000) { index ->
            messages = appendPartyChatMessage(messages, PartyChatMessage(
                key = "$index", id = null, memberId = "member", name = "Member", avatarUrl = null,
                avatarColor = null, body = "Message", sentAt = index.toLong(), mine = true, status = PartyChatStatus.Failed,
            ), 200)
            assertTrue(messages.size <= 200)
        }
        assertEquals("800", messages.first().key)
        assertEquals("999", messages.last().key)
    }

    @Test fun textResponsesRejectExcessBytesInsteadOfRetainingOrParsingTruncatedData() {
        assertFails { readResponseBody("x".repeat(33).toResponseBody(), maxBytes = 32) }
        assertEquals("small", readResponseBody("small".toResponseBody(), maxBytes = 32))
    }

    @Test fun oversizedAddonResponseIsRejectedThroughTheRealNetworkPath() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/meta") { request ->
            request.sendResponseHeaders(200, 16L * 1024 * 1024 + 1)
            request.responseBody.use { it.write(1) }
        }
        server.start()
        try {
            assertFails { runBlocking { httpGetText("http://127.0.0.1:${server.address.port}/meta") } }
        } finally { server.stop(0) }
    }

    private fun item() = DownloadItem(
        id = "test", contentType = "series", parentMetaId = "tt1", parentMetaType = "series", videoId = "tt1:1:1",
        status = com.nuvio.app.features.downloads.DownloadStatus.Completed, title = "Show", seasonNumber = 1, episodeNumber = 1, streamTitle = "Video", providerName = "Source",
        sourceUrl = "https://example.invalid/video", fileName = "video.mkv", createdAtEpochMs = 1, updatedAtEpochMs = 1,
    )

    private fun checkMaps(instance: Any, vararg names: String) {
        names.forEach { name ->
            val cache = map(instance, name)
            try {
                repeat(2048) { cache["test-$it"] = "value" }
                assertEquals(128, (cache as BoundedCache<*, *>).capacity)
                assertEquals(128, cache.size, "${instance.javaClass.simpleName}.$name retained results")
            } finally { cache.clear() }
        }
    }

    private fun field(instance: Any, name: String): java.lang.reflect.Field =
        generateSequence(instance.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField(name) }.getOrNull() }.first().apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun map(instance: Any, name: String): MutableMap<Any, Any?> = field(instance, name).get(instance) as MutableMap<Any, Any?>
}
