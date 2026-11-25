package com.nuvio.app.features.home

import com.nuvio.app.core.network.TrustedLocalServerTest
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeRepositoryRetryTest : TrustedLocalServerTest() {
    private var server: HttpServer? = null

    @BeforeTest
    fun resetBefore() {
        HomeRepository.clear()
    }

    @AfterTest
    fun tearDown() {
        HomeRepository.clear()
        server?.stop(0)
        server = null
    }

    private fun addonFor(port: Int, userSetName: String? = null): ManagedAddon {
        val manifestUrl = "http://127.0.0.1:$port/manifest.json"
        return ManagedAddon(
            manifestUrl = manifestUrl,
            userSetName = userSetName,
            manifest = AddonManifest(
                id = "test-home-addon",
                name = "Test Addon",
                description = "",
                version = "1.0.0",
                resources = listOf(AddonResource(name = "catalog", types = listOf("movie"))),
                types = listOf("movie"),
                catalogs = listOf(
                    AddonCatalog(type = "movie", id = "popular", name = "Popular"),
                    AddonCatalog(type = "movie", id = "trending", name = "Trending"),
                ),
                transportUrl = manifestUrl,
            ),
        )
    }

    private fun awaitLoaded() = runBlocking {
        withTimeout(10_000) {
            HomeRepository.uiState.first { !it.isLoading }
        }
    }

    @Test
    fun partial_or_cached_failures_remain_retryable_while_full_success_reuses_cache() {
        val failTrending = AtomicBoolean(true)
        val failAll = AtomicBoolean(false)
        val popularHits = AtomicInteger(0)
        val trendingHits = AtomicInteger(0)

        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/catalog/movie/popular.json") { exchange ->
            popularHits.incrementAndGet()
            if (failAll.get()) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            } else {
                val body = """{"metas":[{"id":"tt100","type":"movie","name":"Popular One"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        http.createContext("/catalog/movie/trending.json") { exchange ->
            trendingHits.incrementAndGet()
            if (failAll.get() || failTrending.get()) {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            } else {
                val body = """{"metas":[{"id":"tt200","type":"movie","name":"Trending One"}]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        http.start()
        server = http

        val addon = addonFor(http.address.port)

        // 1. Partial failure: popular succeeds, trending fails.
        HomeRepository.refresh(listOf(addon), force = false)
        val afterPartial = awaitLoaded()
        assertEquals(listOf("tt100"), afterPartial.sections.flatMap { it.items }.map { it.id })

        // 2. Recover trending; an ordinary (non-forced) refresh must retry instead of returning cached.
        failTrending.set(false)
        val trendingHitsBeforeRetry = trendingHits.get()
        HomeRepository.refresh(listOf(addon), force = false)
        val afterRecovery = awaitLoaded()
        assertEquals(2, afterRecovery.sections.size)
        assertEquals(trendingHitsBeforeRetry + 1, trendingHits.get())

        // 3. Full success: renaming the addon and calling non-forced refresh reuses cached rows without fetching.
        val popularHitsAfterFullSuccess = popularHits.get()
        val trendingHitsAfterFullSuccess = trendingHits.get()
        HomeRepository.refresh(listOf(addonFor(http.address.port, userSetName = "Renamed Addon")), force = false)
        val afterRename = awaitLoaded()
        assertEquals(2, afterRename.sections.size)
        assertEquals(setOf("Renamed Addon"), afterRename.sections.map { it.addonName }.toSet())
        assertEquals(popularHitsAfterFullSuccess, popularHits.get())
        assertEquals(trendingHitsAfterFullSuccess, trendingHits.get())

        // 4. A forced refresh that fails while cached sections exist must not mark the request loaded.
        failAll.set(true)
        HomeRepository.refresh(listOf(addon), force = true)
        awaitLoaded()
        val hitsAfterFailedForce = popularHits.get()

        failAll.set(false)
        HomeRepository.refresh(listOf(addon), force = false)
        val afterPostFailureRetry = awaitLoaded()
        assertEquals(2, afterPostFailureRetry.sections.size)
        assertEquals(hitsAfterFailedForce + 1, popularHits.get())
    }
}
