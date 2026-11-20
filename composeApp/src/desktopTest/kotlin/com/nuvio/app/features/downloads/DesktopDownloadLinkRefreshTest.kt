package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.TorboxApiClient
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveRaw
import com.nuvio.app.features.streams.StreamClientResolveStream
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopDownloadLinkRefreshTest {
    private val servers = mutableListOf<HttpServer>()
    private val installedAddonUrls = mutableListOf<String>()
    private lateinit var world: FolderWorld
    private lateinit var folders: DesktopDownloadFolders

    private val freeBytesBefore = DownloadsPlatformDownloader.freeBytesProvider
    private val waitBefore = DownloadsPlatformDownloader.waitBeforeRetry
    private val trashBefore = DownloadsPlatformDownloader.trashPartial
    private val maxWithoutProgressBefore = DownloadsPlatformDownloader.maxLinkRefreshesWithoutProgress
    private val maxTotalBefore = DownloadsPlatformDownloader.maxTotalLinkRefreshes
    private val backendBefore = DownloadsRepository.backend
    private val refresherBefore = DownloadsRepository.linkRefresher
    private val torboxBaseBefore = TorboxApiClient.baseUrl

    @BeforeTest
    fun setUp() {
        DownloadsRepository.clearLocalState()
        runBlocking { DirectDebridPlaybackResolver.clearCacheForTest() }
        world = FolderWorld()
        folders = DesktopDownloadFolders(
            locations = world.locations,
            validator = world.validator,
            mover = world.mover(),
            hooks = ProfileHooks(emptyList()),
            trashPath = world.trash::send,
        )
        DesktopDownloads.replaceForTest(folders)
        DownloadsPlatformDownloader.freeBytesProvider = { null }
        DownloadsPlatformDownloader.waitBeforeRetry = {}
        DownloadsPlatformDownloader.maxLinkRefreshesWithoutProgress = 3
        DownloadsPlatformDownloader.maxTotalLinkRefreshes = 8
    }

    @AfterTest
    fun tearDown() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backendBefore
        DownloadsRepository.linkRefresher = refresherBefore
        TorboxApiClient.baseUrl = torboxBaseBefore
        runBlocking { DirectDebridPlaybackResolver.clearCacheForTest() }
        installedAddonUrls.forEach { runCatching { AddonRepository.removeAddon(it) } }
        DebridSettingsRepository.setEnabled(false)
        DebridSettingsRepository.setProviderApiKey(DebridProviders.Torbox.id, "")
        DownloadsPlatformDownloader.freeBytesProvider = freeBytesBefore
        DownloadsPlatformDownloader.waitBeforeRetry = waitBefore
        DownloadsPlatformDownloader.trashPartial = trashBefore
        DownloadsPlatformDownloader.maxLinkRefreshesWithoutProgress = maxWithoutProgressBefore
        DownloadsPlatformDownloader.maxTotalLinkRefreshes = maxTotalBefore
        DesktopDownloads.replaceForTest(null)
        servers.forEach { it.stop(0) }
        world.close()
    }

    private fun startServer(concurrent: Boolean = false, configure: (HttpServer) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        if (concurrent) {
            server.executor = Executors.newCachedThreadPool()
        }
        configure(server)
        server.start()
        servers += server
        return server
    }

    private fun baseItem(sourceUrl: String, fileName: String = "film.mkv"): DownloadItem = DownloadItem(
        id = "dl-${System.nanoTime()}",
        contentType = "movie",
        parentMetaId = "tt100",
        parentMetaType = "movie",
        videoId = "tt100",
        title = "Film",
        streamTitle = "1080p",
        providerName = "Addon",
        sourceUrl = sourceUrl,
        fileName = fileName,
        status = DownloadStatus.Downloading,
        downloadedBytes = 0L,
        totalBytes = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
    )

    private fun runDownload(request: DownloadPlatformRequest): DownloadOutcome {
        val done = CountDownLatch(1)
        var successUri: String? = null
        var successTotal: Long? = null
        var failure: String? = null
        var paused = false
        DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { _, _ -> },
            onSuccess = { uri, total ->
                successUri = uri
                successTotal = total
                done.countDown()
            },
            onFailure = { message ->
                failure = message
                done.countDown()
            },
            onPaused = {
                paused = true
                done.countDown()
            },
        )
        assertTrue(done.await(20, TimeUnit.SECONDS), "Download timed out")
        awaitWriterExit(request.item.id)
        return DownloadOutcome(successUri, successTotal, failure, paused)
    }

    private fun awaitWriterExit(id: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (WriterTokens.kindOf(id) != null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(null, WriterTokens.kindOf(id), "Transfer did not finish closing")
    }

    private data class DownloadOutcome(
        val successUri: String?,
        val totalBytes: Long?,
        val failure: String?,
        val paused: Boolean,
    )

    @Test
    fun resumes_existing_partial_with_range_across_403_404_and_410_when_etag_matches() {
        val payload = ByteArray(300 * 1024) { ((it * 17) + 3).toByte() }
        val cutoff = 128 * 1024
        for (expiredStatus in listOf(403, 404, 410)) {
            val oldCalls = AtomicInteger(0)
            val refreshedRanges = CopyOnWriteArrayList<String?>()
            val refreshedAuth = CopyOnWriteArrayList<String?>()
            val server = startServer { s ->
                s.createContext("/expired") { exchange ->
                    if (oldCalls.incrementAndGet() == 1) {
                        exchange.responseHeaders.add("ETag", "\"same-release-etag\"")
                        exchange.sendResponseHeaders(200, payload.size.toLong())
                        exchange.responseBody.use { out ->
                            out.write(payload, 0, cutoff)
                            out.flush()
                        }
                    } else {
                        exchange.sendResponseHeaders(expiredStatus, -1)
                        exchange.close()
                    }
                }
                s.createContext("/fresh") { exchange ->
                    refreshedAuth += exchange.requestHeaders.getFirst("Authorization")
                    exchange.responseHeaders.add("ETag", "\"same-release-etag\"")
                    serveRanged(exchange, payload, rangeOverride = { r -> refreshedRanges += r })
                }
            }

            val base = "http://127.0.0.1:${server.address.port}"
            val fileName = "film-$expiredStatus.mkv"
            val refreshCalls = AtomicInteger(0)
            val refreshedUrls = mutableListOf<String>()
            val outcome = runDownload(
                DownloadPlatformRequest(
                    item = baseItem("$base/expired", fileName),
                    refreshSourceLink = {
                        refreshCalls.incrementAndGet()
                        SourceLinkRefreshResult.Refreshed(
                            RefreshedSourceLink(
                                url = "$base/fresh",
                                requestHeaders = mapOf("Authorization" to "Bearer token-$expiredStatus"),
                            ),
                        )
                    },
                    onSourceRefreshed = { link, _ -> refreshedUrls += link.url },
                ),
            )

            assertEquals(null, outcome.failure, "status $expiredStatus failed: ${outcome.failure}")
            assertEquals(payload.size.toLong(), outcome.totalBytes)
            assertEquals(1, refreshCalls.get(), "status $expiredStatus should only need 1 refresh")
            assertEquals(listOf("$base/fresh"), refreshedUrls)
            assertEquals(listOf("Bearer token-$expiredStatus"), refreshedAuth.toList())
            assertEquals(listOf("bytes=$cutoff-"), refreshedRanges.toList())
            assertContentEquals(payload, world.appFolder.resolve(fileName).toFile().readBytes())
            assertFalse(Files.exists(world.appFolder.resolve("$fileName.part")))
        }
    }

    @Test
    fun resumes_existing_partial_when_last_modified_matches_without_etag() {
        val payload = ByteArray(256 * 1024) { ((it * 29) + 5).toByte() }
        val cutoff = 96 * 1024
        val lm = "Tue, 02 Feb 2026 12:34:56 GMT"
        val oldCalls = AtomicInteger(0)
        val server = startServer { s ->
            s.createContext("/expired") { exchange ->
                if (oldCalls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("Last-Modified", lm)
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { out ->
                        out.write(payload, 0, cutoff)
                        out.flush()
                    }
                } else {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                }
            }
            s.createContext("/fresh") { exchange ->
                exchange.responseHeaders.add("Last-Modified", lm)
                serveRanged(exchange, payload)
            }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val fileName = "last-modified.mkv"
        val outcome = runDownload(
            DownloadPlatformRequest(
                item = baseItem("$base/expired", fileName),
                refreshSourceLink = { SourceLinkRefreshResult.Refreshed(RefreshedSourceLink("$base/fresh")) },
            ),
        )

        assertEquals(null, outcome.failure)
        assertContentEquals(payload, world.appFolder.resolve(fileName).toFile().readBytes())
    }

    @Test
    fun resumes_existing_partial_when_only_weak_or_no_validators_by_verifying_first_and_last_chunks() {
        val payload = ByteArray(380 * 1024) { ((it * 43) + 11).toByte() }
        val cutoff = 180 * 1024
        val oldCalls = AtomicInteger(0)
        val freshRanges = CopyOnWriteArrayList<String?>()
        val server = startServer { s ->
            s.createContext("/expired") { exchange ->
                if (oldCalls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("ETag", "W/\"weak-ignored\"")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { out ->
                        out.write(payload, 0, cutoff)
                        out.flush()
                    }
                } else {
                    exchange.sendResponseHeaders(410, -1)
                    exchange.close()
                }
            }
            s.createContext("/fresh") { exchange ->
                exchange.responseHeaders.add("ETag", "W/\"weak-ignored-different\"")
                serveRanged(exchange, payload, rangeOverride = { r -> freshRanges += r })
            }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val fileName = "chunk-verified.mkv"
        val outcome = runDownload(
            DownloadPlatformRequest(
                item = baseItem("$base/expired", fileName),
                refreshSourceLink = { SourceLinkRefreshResult.Refreshed(RefreshedSourceLink("$base/fresh")) },
            ),
        )

        assertEquals(null, outcome.failure)
        assertEquals(
            listOf(
                "bytes=0-65535",
                "bytes=${cutoff - 65536}-${cutoff - 1}",
                "bytes=$cutoff-",
            ),
            freshRanges.toList(),
        )
        assertContentEquals(payload, world.appFolder.resolve(fileName).toFile().readBytes())
    }

    @Test
    fun starts_fresh_in_new_partial_on_size_or_etag_or_chunk_mismatch_or_200_keeping_old_partial_until_complete() {
        val oldPayload = ByteArray(260 * 1024) { ((it * 19) + 1).toByte() }
        val cutoff = 170 * 1024

        // 1. Different total size
        verifyNewPartialBehavior(
            name = "size-mismatch",
            oldPayload = oldPayload,
            cutoff = cutoff,
            newPayload = ByteArray(275 * 1024) { ((it * 37) + 9).toByte() },
            oldETag = null,
            newETag = null,
            ignoreRangeOnFresh = false,
        )

        // 2. Same size + strong ETag conflict
        verifyNewPartialBehavior(
            name = "etag-conflict",
            oldPayload = oldPayload,
            cutoff = cutoff,
            newPayload = ByteArray(oldPayload.size) { ((it * 23) + 4).toByte() },
            oldETag = "\"etag-old\"",
            newETag = "\"etag-new\"",
            ignoreRangeOnFresh = false,
        )

        // 3. Same size + no validators + first chunk differs
        val firstMismatch = oldPayload.copyOf().apply { this[100] = (this[100].toInt() xor 0x55).toByte() }
        verifyNewPartialBehavior(
            name = "first-chunk-mismatch",
            oldPayload = oldPayload,
            cutoff = cutoff,
            newPayload = firstMismatch,
            oldETag = null,
            newETag = null,
            ignoreRangeOnFresh = false,
        )

        // 4. Same size + no validators + last chunk before resume offset differs
        val lastMismatch = oldPayload.copyOf().apply { this[cutoff - 10] = (this[cutoff - 10].toInt() xor 0x55).toByte() }
        verifyNewPartialBehavior(
            name = "last-chunk-mismatch",
            oldPayload = oldPayload,
            cutoff = cutoff,
            newPayload = lastMismatch,
            oldETag = null,
            newETag = null,
            ignoreRangeOnFresh = false,
        )

        // 5. Same size & validators, server ignores Range and returns 200
        verifyNewPartialBehavior(
            name = "ignores-range-200",
            oldPayload = oldPayload,
            cutoff = cutoff,
            newPayload = oldPayload.copyOf(),
            oldETag = "\"etag-same\"",
            newETag = "\"etag-same\"",
            ignoreRangeOnFresh = true,
        )
    }

    private fun verifyNewPartialBehavior(
        name: String,
        oldPayload: ByteArray,
        cutoff: Int,
        newPayload: ByteArray,
        oldETag: String?,
        newETag: String?,
        ignoreRangeOnFresh: Boolean,
    ) {
        val oldCalls = AtomicInteger(0)
        val freshStartedLatch = CountDownLatch(1)
        val allowFreshFinish = CountDownLatch(1)
        val fileName = "$name.mkv"
        val expectedOldPrefix = oldPayload.copyOfRange(0, cutoff)

        val server = startServer(concurrent = true) { s ->
            s.createContext("/expired") { exchange ->
                if (oldCalls.incrementAndGet() == 1) {
                    oldETag?.let { exchange.responseHeaders.add("ETag", it) }
                    exchange.sendResponseHeaders(200, oldPayload.size.toLong())
                    exchange.responseBody.use { out ->
                        out.write(oldPayload, 0, cutoff)
                        out.flush()
                    }
                } else {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                }
            }
            s.createContext("/fresh") { exchange ->
                newETag?.let { exchange.responseHeaders.add("ETag", it) }
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                when {
                    rangeHeader != null && !ignoreRangeOnFresh -> serveRanged(exchange, newPayload)
                    rangeHeader != null -> {
                        exchange.sendResponseHeaders(200, newPayload.size.toLong())
                        runCatching { exchange.responseBody.use { it.write(newPayload) } }
                    }
                    else -> {
                        exchange.sendResponseHeaders(200, newPayload.size.toLong())
                        exchange.responseBody.use { out ->
                            val head = minOf(16 * 1024, newPayload.size)
                            out.write(newPayload, 0, head)
                            out.flush()
                            freshStartedLatch.countDown()
                            assertTrue(allowFreshFinish.await(15, TimeUnit.SECONDS), "Timed out waiting for mid-transfer check")
                            out.write(newPayload, head, newPayload.size - head)
                            out.flush()
                        }
                    }
                }
            }
        }

        val trashedPaths = CopyOnWriteArrayList<Path>()
        DownloadsPlatformDownloader.trashPartial = { path ->
            trashedPaths.add(path)
            world.trash.send(path)
        }

        val base = "http://127.0.0.1:${server.address.port}"
        val switchedPartial = AtomicReference<String?>(null)
        val switchedKept = AtomicReference<KeptPartialFile?>(null)
        val retiredKeys = AtomicReference<Set<String>?>(null)
        val done = CountDownLatch(1)
        var outcomeUri: String? = null
        var failure: String? = null

        val switchingItem = baseItem("$base/expired", fileName)
        DownloadsPlatformDownloader.start(
            request = DownloadPlatformRequest(
                item = switchingItem,
                refreshSourceLink = { SourceLinkRefreshResult.Refreshed(RefreshedSourceLink("$base/fresh")) },
                onSwitchedToNewPartial = { newPartialName, kept ->
                    switchedPartial.set(newPartialName)
                    switchedKept.set(kept)
                },
                onSupersededPartialsRetired = { keys -> retiredKeys.set(keys) },
            ),
            onProgress = { _, _ -> },
            onSuccess = { uri, _ ->
                outcomeUri = uri
                done.countDown()
            },
            onFailure = { msg ->
                failure = msg
                done.countDown()
            },
            onPaused = { done.countDown() },
        )

        assertTrue(freshStartedLatch.await(15, TimeUnit.SECONDS), "$name: fresh partial never started")
        val oldPartPath = world.appFolder.resolve("$fileName.part")
        val newPartName = switchedPartial.get()
        assertEquals("$name (2).mkv.part", newPartName)
        val newPartPath = world.appFolder.resolve(newPartName!!)
        val partialWaitDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < partialWaitDeadline &&
            (!Files.exists(newPartPath) || newPartPath.toFile().length() <= 0L)
        ) {
            Thread.sleep(5)
        }

        // Old partial MUST stay untouched on disk while new partial downloads.
        assertTrue(Files.exists(oldPartPath), "$name: old partial was removed prematurely")
        assertTrue(Files.exists(newPartPath), "$name: new partial did not exist yet")
        assertContentEquals(expectedOldPrefix, oldPartPath.toFile().readBytes())
        assertTrue(trashedPaths.isEmpty(), "$name: trashPartial called before new partial completed")

        allowFreshFinish.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS), "$name: download did not complete")
        awaitWriterExit(switchingItem.id)
        assertEquals(null, failure, "$name: unexpected failure: $failure")
        assertNotNull(outcomeUri)

        // New partial completed cleanly; old partial was retired to Trash.
        assertContentEquals(newPayload, world.appFolder.resolve(fileName).toFile().readBytes())
        assertFalse(Files.exists(newPartPath))
        assertFalse(Files.exists(oldPartPath))
        assertEquals(listOf(oldPartPath), trashedPaths.toList())
        assertEquals(setOfNotNull(switchedKept.get()?.partialFileKey), retiredKeys.get())
    }

    @Test
    fun keeps_old_partial_intact_if_new_partial_fails_before_completion() {
        val oldPayload = ByteArray(200 * 1024) { it.toByte() }
        val cutoff = 120 * 1024
        val oldCalls = AtomicInteger(0)
        val server = startServer { s ->
            s.createContext("/expired") { exchange ->
                if (oldCalls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("ETag", "\"old\"")
                    exchange.sendResponseHeaders(200, oldPayload.size.toLong())
                    exchange.responseBody.use { out -> out.write(oldPayload, 0, cutoff) }
                } else {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                }
            }
            s.createContext("/fresh") { exchange ->
                exchange.responseHeaders.add("ETag", "\"new\"")
                exchange.sendResponseHeaders(200, (300 * 1024).toLong())
                exchange.responseBody.use { out ->
                    out.write(ByteArray(32 * 1024) { 7 })
                }
            }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val fileName = "fail-after-switch.mkv"
        val trashed = CopyOnWriteArrayList<Path>()
        DownloadsPlatformDownloader.trashPartial = { path ->
            trashed.add(path)
            world.trash.send(path)
        }
        val refreshAttempts = AtomicInteger(0)
        val outcome = runDownload(
            DownloadPlatformRequest(
                item = baseItem("$base/expired", fileName),
                refreshSourceLink = {
                    val call = refreshAttempts.incrementAndGet()
                    if (call == 1) SourceLinkRefreshResult.Refreshed(RefreshedSourceLink("$base/fresh"))
                    else SourceLinkRefreshResult.Unavailable
                },
            ),
        )

        assertNotNull(outcome.failure)
        assertTrue(trashed.isEmpty(), "old partial must remain kept when new partial failed")
        val oldPart = world.appFolder.resolve("$fileName.part")
        assertTrue(Files.exists(oldPart))
        assertContentEquals(oldPayload.copyOfRange(0, cutoff), oldPart.toFile().readBytes())
    }

    @Test
    fun bounds_link_refreshes_without_progress_and_total_cap_and_handles_unavailable() {
        val dripPayload = ByteArray(100_000) { (it * 5).toByte() }
        val servedDripTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val server = startServer { s ->
            s.createContext("/always-410") { exchange ->
                exchange.sendResponseHeaders(410, -1)
                exchange.close()
            }
            s.createContext("/always-404") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            s.createContext("/drip") { exchange ->
                val token = exchange.requestURI.query ?: "default"
                if (!servedDripTokens.add(token)) {
                    exchange.sendResponseHeaders(410, -1)
                    exchange.close()
                    return@createContext
                }
                exchange.responseHeaders.add("ETag", "\"drip-etag\"")
                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                val start = rangeHeader
                    ?.takeIf { it.startsWith("bytes=") }
                    ?.removePrefix("bytes=")
                    ?.substringBefore('-')
                    ?.toIntOrNull()
                    ?: 0
                val chunk = 1_024
                if (start > 0) {
                    exchange.responseHeaders.add("Content-Range", "bytes $start-${dripPayload.lastIndex}/${dripPayload.size}")
                    exchange.sendResponseHeaders(206, (dripPayload.size - start).toLong())
                } else {
                    exchange.sendResponseHeaders(200, dripPayload.size.toLong())
                }
                exchange.responseBody.use { out ->
                    out.write(dripPayload, start, chunk)
                    out.flush()
                }
            }
        }
        val base = "http://127.0.0.1:${server.address.port}"

        // 1. Bounded retries without progress (maxLinkRefreshesWithoutProgress = 3)
        DownloadsPlatformDownloader.maxLinkRefreshesWithoutProgress = 3
        val refreshCount = AtomicInteger(0)
        val outcomeNoProgress = runDownload(
            DownloadPlatformRequest(
                item = baseItem("$base/always-410", "bounded.mkv"),
                refreshSourceLink = {
                    val n = refreshCount.incrementAndGet()
                    SourceLinkRefreshResult.Refreshed(RefreshedSourceLink("$base/always-410?n=$n"))
                },
            ),
        )
        assertEquals("Download failed with HTTP 410", outcomeNoProgress.failure)
        assertEquals(3, refreshCount.get())

        // 2. Unavailable stops immediately without retrying link refresh
        val unavailableCount = AtomicInteger(0)
        val outcomeUnavailable = runDownload(
            DownloadPlatformRequest(
                item = baseItem("$base/always-404", "unavail.mkv"),
                refreshSourceLink = {
                    unavailableCount.incrementAndGet()
                    SourceLinkRefreshResult.Unavailable
                },
            ),
        )
        assertEquals("Download failed with HTTP 404", outcomeUnavailable.failure)
        assertEquals(1, unavailableCount.get())

        // 3. Total refresh cap stops even if each refreshed URL drips a few bytes before expiring
        DownloadsPlatformDownloader.maxLinkRefreshesWithoutProgress = 10
        DownloadsPlatformDownloader.maxTotalLinkRefreshes = 4
        val dripRefreshCount = AtomicInteger(0)
        val dripOutcome = runDownload(
            DownloadPlatformRequest(
                item = baseItem("$base/always-410", "drip-cap.mkv"),
                refreshSourceLink = {
                    val count = dripRefreshCount.incrementAndGet()
                    SourceLinkRefreshResult.Refreshed(RefreshedSourceLink("$base/drip?c=$count"))
                },
            ),
        )
        assertEquals("Download failed with HTTP 410", dripOutcome.failure)
        assertEquals(4, dripRefreshCount.get())
        assertEquals(4_096L, world.appFolder.resolve("drip-cap.mkv.part").toFile().length())
    }

    @Test
    fun end_to_end_repository_direct_debrid_and_addon_refresh_and_source_replacement_skip() {
        val fullPayload = ByteArray(220 * 1024) { ((it * 13) + 7).toByte() }
        val cutoff = 110 * 1024
        val hash = "0123456789abcdef0123456789abcdef01234567"

        val tbCdn1Calls = AtomicInteger(0)
        val tbRequestDlCalls = AtomicInteger(0)
        val addonStreamRequests = AtomicInteger(0)
        val addonNoCacheSeen = CopyOnWriteArrayList<String?>()
        val addonMode = AtomicReference("valid-refresh") // or "always-403"
        val addonCdn1Calls = AtomicInteger(0)

        val server = startServer { s ->
            // Torbox API mock
            s.createContext("/v1/api/torrents/createtorrent") { exchange ->
                respondJson(exchange, """{"success":true,"data":{"torrent_id":42,"hash":"$hash"}}""")
            }
            s.createContext("/v1/api/torrents/mylist") { exchange ->
                respondJson(
                    exchange,
                    """{"success":true,"data":{"id":42,"hash":"$hash","name":"Film.2025.1080p.mkv","files":[{"id":1,"name":"Film.2025.1080p.mkv","short_name":"Film.2025.1080p.mkv","size":${fullPayload.size}}]}}""",
                )
            }
            s.createContext("/v1/api/torrents/requestdl") { exchange ->
                val call = tbRequestDlCalls.incrementAndGet()
                val port = s.address.port
                respondJson(exchange, """{"success":true,"data":"http://127.0.0.1:$port/tb-cdn-$call/film.mkv"}""")
            }
            s.createContext("/tb-cdn-1") { exchange ->
                if (tbCdn1Calls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("ETag", "\"tb-etag\"")
                    exchange.sendResponseHeaders(200, fullPayload.size.toLong())
                    exchange.responseBody.use { out -> out.write(fullPayload, 0, cutoff) }
                } else {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                }
            }
            s.createContext("/tb-cdn-2") { exchange ->
                exchange.responseHeaders.add("ETag", "\"tb-etag\"")
                serveRanged(exchange, fullPayload)
            }

            // Stremio Addon mock
            s.createContext("/addon/manifest.json") { exchange ->
                respondJson(
                    exchange,
                    """{"id":"com.example.test","name":"TestAddon","version":"1.0.0","description":"Test","resources":["stream"],"types":["movie"],"idPrefixes":["tt"]}""",
                )
            }
            s.createContext("/addon/stream/movie/tt200.json") { exchange ->
                val n = addonStreamRequests.incrementAndGet()
                addonNoCacheSeen += exchange.requestHeaders.getFirst("Cache-Control")
                val port = s.address.port
                val freshUrl = when (addonMode.get()) {
                    "always-403" -> "http://127.0.0.1:$port/always-403/$n/film.mkv"
                    else -> "http://127.0.0.1:$port/addon-cdn-fresh/film.mkv"
                }
                respondJson(
                    exchange,
                    """{"streams":[{"name":"Distractor 720p","description":"Wrong release\n👤 99","url":"http://127.0.0.1:$port/wrong.mkv","infoHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","behaviorHints":{"filename":"Wrong.mkv","videoSize":12345}},{"name":"TestSource 1080p","description":"Film.2025.1080p.mkv\n👤 ${20 - n}","url":"$freshUrl","infoHash":"$hash","fileIdx":1,"behaviorHints":{"filename":"Film.2025.1080p.mkv","videoSize":${fullPayload.size}}}]}""",
                )
            }
            s.createContext("/addon-cdn-1") { exchange ->
                if (addonCdn1Calls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("ETag", "\"addon-etag\"")
                    exchange.sendResponseHeaders(200, fullPayload.size.toLong())
                    exchange.responseBody.use { out -> out.write(fullPayload, 0, cutoff) }
                } else {
                    exchange.sendResponseHeaders(410, -1)
                    exchange.close()
                }
            }
            s.createContext("/addon-cdn-fresh") { exchange ->
                exchange.responseHeaders.add("ETag", "\"addon-etag\"")
                serveRanged(exchange, fullPayload)
            }
            s.createContext("/always-403") { exchange ->
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            }
        }

        val port = server.address.port
        DownloadsRepository.backend = object : DownloadsBackend {
            private var payloadJson: String? = null
            override fun loadPayload(): String? = payloadJson
            override fun savePayload(payload: String) { payloadJson = payload }
            override fun restoreItem(item: DownloadItem) = DownloadsPlatformDownloader.restoreItem(item)
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
                onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
                onFailure: (message: String) -> Unit,
                onPaused: () -> Unit,
            ) = DownloadsPlatformDownloader.start(request, onProgress, onSuccess, onFailure, onPaused)
            override fun removeFile(localFileUri: String?) = DownloadsPlatformDownloader.removeFile(localFileUri)
            override fun removePartialFile(destinationFileName: String) = DownloadsPlatformDownloader.removePartialFile(destinationFileName)
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String) =
                DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, destinationFileName)
            override fun removePartialOf(item: DownloadItem) = folders.removePartialOf(item)
        }
        DownloadsRepository.linkRefresher = DownloadSourceRefresher(isPublicHost = { true })

        // Part A: Direct Debrid via TorboxApiClient + DirectDebridPlaybackResolver
        TorboxApiClient.baseUrl = "http://127.0.0.1:$port"
        DebridSettingsRepository.setProviderApiKey(DebridProviders.Torbox.id, "tb-key")
        DebridSettingsRepository.setEnabled(true)

        val unresolvedDebridStream = StreamItem(
            name = "TorBox 1080p",
            description = "Film.2025.1080p.mkv",
            url = null,
            infoHash = hash,
            fileIdx = 1,
            addonName = "TestAddon",
            addonId = "addon:com.example.test",
            debridResolved = false,
            debridCacheStatus = StreamDebridCacheStatus("torbox", "TorBox", StreamDebridCacheState.CACHED),
            clientResolve = StreamClientResolve(
                type = "debrid",
                infoHash = hash,
                fileIdx = 1,
                filename = "Film.2025.1080p.mkv",
                service = "torbox",
                isCached = true,
                stream = StreamClientResolveStream(raw = StreamClientResolveRaw(filename = "Film.2025.1080p.mkv", size = fullPayload.size.toLong())),
            ),
            behaviorHints = StreamBehaviorHints(
                filename = "Film.2025.1080p.mkv",
                videoSize = fullPayload.size.toLong(),
            ),
        )
        val initialResolved = runBlocking {
            DirectDebridPlaybackResolver.resolveToPlayableStream(unresolvedDebridStream, season = null, episode = null)
        }
        val debridStream = assertIs<DirectDebridPlayableResult.Success>(initialResolved).stream
        assertEquals("http://127.0.0.1:$port/tb-cdn-1/film.mkv", debridStream.playableDirectUrl)
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt100",
            parentMetaId = "tt100",
            parentMetaType = "movie",
            title = "Debrid Film",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = debridStream,
        )
        awaitStatus("tt100", DownloadStatus.Completed)
        val completedDebrid = DownloadsRepository.uiState.value.items.first { it.videoId == "tt100" }
        assertEquals(fullPayload.size.toLong(), completedDebrid.downloadedBytes)
        assertEquals(2, tbRequestDlCalls.get())
        assertEquals("http://127.0.0.1:$port/tb-cdn-2/film.mkv", completedDebrid.sourceUrl)

        // Part B: Same add-on re-fetch with Cache-Control: no-cache + changed peer count
        val manifestUrl = "http://127.0.0.1:$port/addon/manifest.json"
        runBlocking { AddonRepository.addAddon(manifestUrl) }
        installedAddonUrls += manifestUrl
        val addon = AddonRepository.uiState.value.addons.first { it.manifestUrl == manifestUrl }
        val addonInstanceId = addon.streamAddonInstanceId(addon.manifest!!.id)

        val addonStream = StreamItem(
            name = "TestSource 1080p",
            description = "Film.2025.1080p.mkv\n👤 20",
            url = "http://127.0.0.1:$port/addon-cdn-1/film.mkv",
            infoHash = hash,
            fileIdx = 1,
            addonName = "TestAddon",
            addonId = addonInstanceId,
            behaviorHints = StreamBehaviorHints(
                filename = "Film.2025.1080p.mkv",
                videoSize = fullPayload.size.toLong(),
            ),
        )
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt200",
            parentMetaId = "tt200",
            parentMetaType = "movie",
            title = "Addon Film",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = addonStream,
        )
        awaitStatus("tt200", DownloadStatus.Completed)
        val completedAddon = DownloadsRepository.uiState.value.items.first { it.videoId == "tt200" }
        assertEquals(fullPayload.size.toLong(), completedAddon.downloadedBytes)
        assertEquals(listOf("no-cache"), addonNoCacheSeen.toList())
        assertEquals("http://127.0.0.1:$port/addon-cdn-fresh/film.mkv", completedAddon.sourceUrl)

        // Part C: Exhausted refresh -> SourceRefused -> SourceReplacement skips rotated source with hash/file keys
        addonMode.set("always-403")
        val failingStream = addonStream.copy(
            url = "http://127.0.0.1:$port/always-403/0/film.mkv",
        )
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt300",
            parentMetaId = "tt200",
            parentMetaType = "movie",
            title = "Addon Film Retry",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 2,
            episodeNumber = 1,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = failingStream,
        )
        awaitStatus("tt200|2|1", DownloadStatus.Failed, matchLogicalKey = true)
        val failedItem = DownloadsRepository.uiState.value.items.first { it.logicalContentKey == "tt200|2|1" }
        assertEquals(DownloadFailureKind.SourceRefused, failedItem.failureKind)
        assertTrue(failedItem.needsAnotherSource)
        val steps = sourceReplacementSteps(listOf(failedItem), busyTitles = emptySet())
        val search = assertIs<SourceReplacementStep.Search>(steps.single())
        val reListedSameSourceWithRotatedUrlAndPeers = failingStream.copy(
            description = "Film.2025.1080p.mkv\n👤 2",
            url = "http://127.0.0.1:$port/always-403/999/film.mkv",
        )
        assertTrue(reListedSameSourceWithRotatedUrlAndPeers.wasTried(search.skip))
    }

    @Test
    fun resumes_across_pause_after_partial_switch_and_retires_prior_superseded_partial() {
        val oldPayload = ByteArray(220 * 1024) { ((it * 11) + 2).toByte() }
        val newPayload = ByteArray(250 * 1024) { ((it * 41) + 9).toByte() }
        val oldCutoff = 110 * 1024
        val oldPartFile = world.appFolder.also { Files.createDirectories(it) }.resolve("resume-switched.mkv.part").toFile()
        oldPartFile.writeBytes(oldPayload.copyOfRange(0, oldCutoff))
        val oldPartKey = DownloadMover.identityOf(oldPartFile.toPath())!!

        val newPartFile = world.appFolder.resolve("resume-switched (2).mkv.part").toFile()
        val firstNewSlice = 90 * 1024
        newPartFile.writeBytes(newPayload.copyOfRange(0, firstNewSlice))
        val newPartKey = DownloadMover.identityOf(newPartFile.toPath())!!

        val server = startServer { s ->
            s.createContext("/stale") { exchange ->
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            }
            s.createContext("/fresh") { exchange ->
                // No ETag/Last-Modified: must verify first & last chunks of newPartFile before joining.
                serveRanged(exchange, newPayload)
            }
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val resumedItem = DownloadItem(
            id = "dl-switched-resume",
            contentType = "movie",
            parentMetaId = "tt999",
            parentMetaType = "movie",
            videoId = "tt999",
            title = "Switched Resume",
            streamTitle = "1080p",
            providerName = "Addon",
            sourceUrl = "$base/stale",
            fileName = "resume-switched.mkv",
            status = DownloadStatus.Downloading,
            downloadedBytes = firstNewSlice.toLong(),
            totalBytes = newPayload.size.toLong(),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            partialFileKey = newPartKey,
            partialFileName = "resume-switched (2).mkv.part",
            supersededPartials = listOf(KeptPartialFile("resume-switched.mkv.part", oldPartKey)),
        )

        val retiredKeys = AtomicReference<Set<String>?>(null)
        val outcome = runDownload(
            DownloadPlatformRequest(
                item = resumedItem,
                refreshSourceLink = { SourceLinkRefreshResult.Refreshed(RefreshedSourceLink("$base/fresh")) },
                onSupersededPartialsRetired = { keys -> retiredKeys.set(keys) },
            ),
        )

        assertEquals(null, outcome.failure)
        assertContentEquals(newPayload, world.appFolder.resolve("resume-switched.mkv").toFile().readBytes())
        assertFalse(Files.exists(newPartFile.toPath()))
        assertFalse(Files.exists(oldPartFile.toPath()))
        assertEquals(setOf(oldPartKey), retiredKeys.get())
    }

    @Test
    fun retries_temporary_refresh_failure_then_succeeds_and_bounds_repeated_temporary_failures() {
        val payload = ByteArray(160 * 1024) { ((it * 7) + 1).toByte() }
        val cutoff = 80 * 1024
        val oldCalls = AtomicInteger(0)
        val server = startServer { s ->
            s.createContext("/expired") { exchange ->
                if (oldCalls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("ETag", "\"temp-retry-etag\"")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { out -> out.write(payload, 0, cutoff) }
                } else {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                }
            }
            s.createContext("/fresh") { exchange ->
                exchange.responseHeaders.add("ETag", "\"temp-retry-etag\"")
                serveRanged(exchange, payload)
            }
            s.createContext("/always-403") { exchange ->
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            }
        }
        val base = "http://127.0.0.1:${server.address.port}"

        // 1. Two temporary failures then a valid refresh -> recovers and resumes cleanly
        DownloadsPlatformDownloader.maxLinkRefreshesWithoutProgress = 3
        val attempts = AtomicInteger(0)
        val recovered = runDownload(
            DownloadPlatformRequest(
                item = baseItem("$base/expired", "temp-ok.mkv"),
                refreshSourceLink = {
                    val n = attempts.incrementAndGet()
                    if (n < 3) SourceLinkRefreshResult.Failed("addon 503")
                    else SourceLinkRefreshResult.Refreshed(RefreshedSourceLink("$base/fresh"))
                },
            ),
        )
        assertEquals(null, recovered.failure)
        assertEquals(3, attempts.get())
        assertContentEquals(payload, world.appFolder.resolve("temp-ok.mkv").toFile().readBytes())

        // 2. Repeated temporary failures stop within bounded limit
        val failAttempts = AtomicInteger(0)
        val failed = runDownload(
            DownloadPlatformRequest(
                item = baseItem("$base/always-403", "temp-fail.mkv"),
                refreshSourceLink = {
                    failAttempts.incrementAndGet()
                    SourceLinkRefreshResult.Failed("addon down")
                },
            ),
        )
        assertEquals("Download failed with HTTP 403", failed.failure)
        assertEquals(3, failAttempts.get())
    }

    @Test
    fun pause_and_restart_after_refresh_still_verifies_refreshed_link_before_joining_or_discarding_partial() {
        val payloadA = ByteArray(240 * 1024) { ((it * 13) + 5).toByte() }
        val payloadB206 = ByteArray(280 * 1024) { ((it * 29) + 11).toByte() }
        val payloadB200 = ByteArray(260 * 1024) { ((it * 31) + 7).toByte() }
        val cutoff = 120 * 1024

        // Case 1: Pause right after refreshed URL is saved, before verification finishes -> server B returns 206 for a different file.
        val old1Calls = AtomicInteger(0)
        val fresh206Calls = AtomicInteger(0)
        val fresh206Requested = CountDownLatch(1)
        val releaseFresh206First = CountDownLatch(1)

        // Case 2: Restart after refreshed URL is saved, before verification finishes -> server B ignores Range and returns 200.
        val old2Calls = AtomicInteger(0)
        val fresh200Calls = AtomicInteger(0)
        val fresh200Requested = CountDownLatch(1)
        val releaseFresh200First = CountDownLatch(1)
        val fresh200MidBodyLatch = CountDownLatch(1)
        val allowFresh200Finish = CountDownLatch(1)

        val server = startServer(concurrent = true) { s ->
            s.createContext("/expired-1") { exchange ->
                if (old1Calls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("ETag", "\"etag-a1\"")
                    exchange.sendResponseHeaders(200, payloadA.size.toLong())
                    exchange.responseBody.use { out -> out.write(payloadA, 0, cutoff) }
                } else {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                }
            }
            s.createContext("/fresh-206") { exchange ->
                if (fresh206Calls.incrementAndGet() == 1) {
                    fresh206Requested.countDown()
                    releaseFresh206First.await(10, TimeUnit.SECONDS)
                    exchange.close()
                } else {
                    exchange.responseHeaders.add("ETag", "\"etag-b1\"")
                    serveRanged(exchange, payloadB206)
                }
            }
            s.createContext("/expired-2") { exchange ->
                if (old2Calls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("ETag", "\"etag-a2\"")
                    exchange.sendResponseHeaders(200, payloadA.size.toLong())
                    exchange.responseBody.use { out -> out.write(payloadA, 0, cutoff) }
                } else {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                }
            }
            s.createContext("/fresh-200") { exchange ->
                val n = fresh200Calls.incrementAndGet()
                if (n == 1) {
                    fresh200Requested.countDown()
                    releaseFresh200First.await(10, TimeUnit.SECONDS)
                    exchange.close()
                } else {
                    // Always ignores Range and sends 200 OK; pauses mid-body so we can assert old partial still exists.
                    exchange.responseHeaders.add("ETag", "\"etag-a2\"")
                    exchange.sendResponseHeaders(200, payloadB200.size.toLong())
                    exchange.responseBody.use { out ->
                        val head = 16 * 1024
                        out.write(payloadB200, 0, head)
                        out.flush()
                        fresh200MidBodyLatch.countDown()
                        assertTrue(allowFresh200Finish.await(15, TimeUnit.SECONDS))
                        out.write(payloadB200, head, payloadB200.size - head)
                        out.flush()
                    }
                }
            }
        }

        val port = server.address.port
        var payloadJson: String? = null
        DownloadsRepository.backend = object : DownloadsBackend {
            override fun loadPayload(): String? = payloadJson
            override fun savePayload(payload: String) { payloadJson = payload }
            override fun restoreItem(item: DownloadItem) = DownloadsPlatformDownloader.restoreItem(item)
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
                onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
                onFailure: (message: String) -> Unit,
                onPaused: () -> Unit,
            ) = DownloadsPlatformDownloader.start(request, onProgress, onSuccess, onFailure, onPaused)
            override fun removeFile(localFileUri: String?) = DownloadsPlatformDownloader.removeFile(localFileUri)
            override fun removePartialFile(destinationFileName: String) = DownloadsPlatformDownloader.removePartialFile(destinationFileName)
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String) =
                DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, destinationFileName)
            override fun removePartialOf(item: DownloadItem) = folders.removePartialOf(item)
        }
        DownloadsRepository.linkRefresher = DownloadSourceRefresher(
            shouldResolveDebrid = { false },
            fetchSameAddonStreams = { item ->
                val freshUrl = if (item.videoId == "tt-pause-206") {
                    "http://127.0.0.1:$port/fresh-206"
                } else {
                    "http://127.0.0.1:$port/fresh-200"
                }
                AddonSourceLookupResult.Streams(
                    listOf(
                        StreamItem(
                            name = "1080p",
                            description = item.streamSubtitle,
                            url = freshUrl,
                            addonName = "TestAddon",
                            addonId = "addon:test",
                        ),
                    ),
                )
            },
            isPublicHost = { true },
        )

        // Run Case 1: pause during verification, then resumeDownload -> must not corrupt partial A with 206 of file B.
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-pause-206",
            parentMetaId = "tt-pause-206",
            parentMetaType = "movie",
            title = "Pause206",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "1080p",
                description = "Pause206.mkv",
                url = "http://127.0.0.1:$port/expired-1",
                addonName = "TestAddon",
                addonId = "addon:test",
            ),
        )
        assertTrue(fresh206Requested.await(15, TimeUnit.SECONDS), "Refreshed 206 URL was never requested")
        val item1 = DownloadsRepository.uiState.value.items.first { it.videoId == "tt-pause-206" }
        assertTrue(DownloadsRepository.pauseAndWait(item1.id))
        releaseFresh206First.countDown()
        assertEquals("http://127.0.0.1:$port/fresh-206", DownloadsRepository.uiState.value.items.first { it.id == item1.id }.sourceUrl)

        DownloadsRepository.resumeDownload(item1.id)
        awaitStatus("tt-pause-206", DownloadStatus.Completed)
        val completed1 = DownloadsRepository.uiState.value.items.first { it.id == item1.id }
        val published1 = File(java.net.URI(completed1.localFileUri!!))
        assertContentEquals(payloadB206, published1.readBytes())

        // Run Case 2: restart after refresh saved URL B, before verification finishes -> 200 must not delete partial A prematurely.
        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-restart-200",
            parentMetaId = "tt-restart-200",
            parentMetaType = "movie",
            title = "Restart200",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "1080p",
                description = "Restart200.mkv",
                url = "http://127.0.0.1:$port/expired-2",
                addonName = "TestAddon",
                addonId = "addon:test",
            ),
        )
        assertTrue(fresh200Requested.await(15, TimeUnit.SECONDS), "Refreshed 200 URL was never requested")
        val item2 = DownloadsRepository.uiState.value.items.first { it.videoId == "tt-restart-200" }
        assertTrue(DownloadsRepository.pauseAndWait(item2.id))
        releaseFresh200First.countDown()
        val old2FileName = DownloadsRepository.uiState.value.items.first { it.id == item2.id }.fileName
        val old2PartPath = world.appFolder.resolve("$old2FileName.part")
        assertTrue(Files.exists(old2PartPath), "Partial A should exist before restart")

        // Simulate app restart while item was Downloading with persisted refreshed URL B.
        val persistedBeforeRestart = DownloadsCodec.decode(payloadJson!!)!!.items.map {
            if (it.id == item2.id) it.copy(status = DownloadStatus.Downloading) else it
        }
        payloadJson = DownloadsCodec.encode(persistedBeforeRestart, loaded = null)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()

        assertTrue(fresh200MidBodyLatch.await(15, TimeUnit.SECONDS), "Resumed 200 transfer never started")
        // Partial A MUST still exist untouched on disk while B is downloading!
        assertTrue(Files.exists(old2PartPath), "Partial A was deleted before 200 replacement completed")
        assertContentEquals(payloadA.copyOfRange(0, cutoff), old2PartPath.toFile().readBytes())

        allowFresh200Finish.countDown()
        awaitStatus("tt-restart-200", DownloadStatus.Completed)
        awaitWriterExit(item2.id)
        val completed2 = DownloadsRepository.uiState.value.items.first { it.id == item2.id }
        assertContentEquals(payloadB200, File(java.net.URI(completed2.localFileUri!!)).readBytes())
        assertFalse(Files.exists(old2PartPath))
    }

    @Test
    fun placeholder_rejection_on_replacement_keeps_superseded_original_partial_intact() {
        val originalPayload = ByteArray(220 * 1024) { ((it * 17) + 3).toByte() }
        val cutoff = 110 * 1024
        val placeholderBody = ByteArray(10_000) { 0x2A }
        val oldCalls = AtomicInteger(0)

        val server = startServer { s ->
            s.createContext("/orig") { exchange ->
                if (oldCalls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("ETag", "\"orig-etag\"")
                    exchange.sendResponseHeaders(200, 2_000_000_000L)
                    exchange.responseBody.use { out ->
                        out.write(originalPayload, 0, cutoff)
                        out.flush()
                    }
                } else {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                }
            }
            s.createContext("/placeholder") { exchange ->
                exchange.responseHeaders.add("ETag", "\"placeholder-etag\"")
                exchange.sendResponseHeaders(200, placeholderBody.size.toLong())
                exchange.responseBody.use { out -> out.write(placeholderBody) }
            }
        }

        val port = server.address.port
        val trashedPaths = CopyOnWriteArrayList<Path>()
        val spyFolders = DesktopDownloadFolders(
            locations = world.locations,
            validator = world.validator,
            mover = world.mover(),
            hooks = ProfileHooks(emptyList()),
            trashPath = { path ->
                trashedPaths.add(path)
                world.trash.send(path)
            },
        )
        DesktopDownloads.replaceForTest(spyFolders)

        var payloadJson: String? = null
        DownloadsRepository.backend = object : DownloadsBackend {
            override fun loadPayload(): String? = payloadJson
            override fun savePayload(payload: String) { payloadJson = payload }
            override fun restoreItem(item: DownloadItem) = DownloadsPlatformDownloader.restoreItem(item)
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
                onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
                onFailure: (message: String) -> Unit,
                onPaused: () -> Unit,
            ) = DownloadsPlatformDownloader.start(request, onProgress, onSuccess, onFailure, onPaused)
            override fun removeFile(localFileUri: String?) = DownloadsPlatformDownloader.removeFile(localFileUri)
            override fun removePartialFile(destinationFileName: String) = DownloadsPlatformDownloader.removePartialFile(destinationFileName)
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String) =
                DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, destinationFileName)
            override fun removePartialOf(item: DownloadItem) = spyFolders.removePartialOf(item)
        }
        DownloadsRepository.linkRefresher = DownloadSourceRefresher(
            shouldResolveDebrid = { false },
            fetchSameAddonStreams = { item ->
                AddonSourceLookupResult.Streams(
                    listOf(
                        StreamItem(
                            name = "1080p",
                            description = item.streamSubtitle,
                            url = "http://127.0.0.1:$port/placeholder",
                            addonName = "TestAddon",
                            addonId = "addon:test",
                        ),
                    ),
                )
            },
            isPublicHost = { true },
        )

        DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-placeholder-keep",
            parentMetaId = "tt-placeholder-keep",
            parentMetaType = "movie",
            title = "PlaceholderKeep",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "1080p",
                description = "PlaceholderKeep.2025.1080p.mkv\n👤 12  2.00 GB",
                url = "http://127.0.0.1:$port/orig",
                addonName = "TestAddon",
                addonId = "addon:test",
            ),
        )

        awaitStatus("tt-placeholder-keep", DownloadStatus.Failed)
        val failedItem = DownloadsRepository.uiState.value.items.first { it.videoId == "tt-placeholder-keep" }
        assertEquals(DownloadFailureKind.BadFile, failedItem.failureKind)
        val originalPartPath = world.appFolder.resolve("${failedItem.fileName}.part")
        assertTrue(Files.exists(originalPartPath), "Original partial was trashed on placeholder rejection")
        assertContentEquals(originalPayload.copyOfRange(0, cutoff), originalPartPath.toFile().readBytes())
        assertFalse(originalPartPath in trashedPaths, "Original partial must not be trashed: $trashedPaths")
        assertTrue(failedItem.supersededPartials.any { it.fileName == "${failedItem.fileName}.part" })
    }

    @Test
    fun repository_rejection_after_publish_keeps_original_partial_and_tracking() {
        checkRetirementAfterValidation(accept = false)
    }

    @Test
    fun repository_acceptance_retires_original_only_after_validation() {
        checkRetirementAfterValidation(accept = true)
    }

    private fun checkRetirementAfterValidation(accept: Boolean) {
        val payload = ByteArray(200 * 1024) { (it * 7).toByte() }
        val originalBytes = ByteArray(80 * 1024) { (it * 13).toByte() }
        val original = world.appFolder.also { Files.createDirectories(it) }.resolve("validated.mkv.part")
        Files.write(original, originalBytes)
        val kept = KeptPartialFile(original.fileName.toString(), DownloadMover.identityOf(original)!!, DownloadLocations.AppId)
        val server = startServer { s -> s.createContext("/video") { serveRanged(it, payload) } }
        val item = baseItem("http://127.0.0.1:${server.address.port}/video", "validated.mkv").copy(
            status = DownloadStatus.Paused,
            partialFileName = "validated (2).mkv.part",
            supersededPartials = listOf(kept),
        )
        var stored = DownloadsCodec.encode(listOf(item), null)
        val inspecting = CountDownLatch(1)
        val allowValidation = CountDownLatch(1)
        val finished = CountDownLatch(1)
        DownloadsRepository.backend = object : DownloadsBackend {
            override fun loadPayload() = stored
            override fun savePayload(payload: String) { stored = payload }
            override fun restoreItem(item: DownloadItem) = item
            override fun start(
                request: DownloadPlatformRequest,
                onProgress: (Long, Long?) -> Unit,
                onSuccess: (String, Long?) -> Unit,
                onFailure: (String) -> Unit,
                onPaused: () -> Unit,
            ) = DownloadsPlatformDownloader.start(
                request.copy(onSupersededPartialsRetired = { keys ->
                    request.onSupersededPartialsRetired(keys)
                    finished.countDown()
                }), onProgress,
                { uri, bytes ->
                    onSuccess(uri, bytes)
                    if (!accept) finished.countDown()
                }, onFailure, onPaused,
            )
            override fun inspectFile(localFileUri: String): DownloadedFileFacts {
                inspecting.countDown()
                check(allowValidation.await(15, TimeUnit.SECONDS))
                return DownloadedFileFacts(exists = true, sizeBytes = payload.size.toLong(), isVideoContainer = accept)
            }
            override fun removeJustPublished(localFileUri: String) = world.trash.send(Path.of(java.net.URI(localFileUri)))
            override fun removeFile(localFileUri: String?) = false
            override fun removePartialFile(destinationFileName: String) = false
            override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String) = localFileUri
        }
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.resumeDownload(item.id)
        try {
            assertTrue(inspecting.await(15, TimeUnit.SECONDS), "Validation never started")
            assertTrue(Files.exists(original), "Original was retired before repository validation")
            assertContentEquals(originalBytes, Files.readAllBytes(original))
            assertEquals(listOf(kept), DownloadsRepository.uiState.value.items.single().supersededPartials)
        } finally {
            allowValidation.countDown()
            assertTrue(finished.await(15, TimeUnit.SECONDS), "Completion never finished")
        }
        awaitWriterExit(item.id)
        val finalItem = DownloadsRepository.uiState.value.items.single()
        val savedItem = DownloadsCodec.decode(stored)!!.items.single()
        if (accept) {
            assertEquals(DownloadStatus.Completed, finalItem.status)
            assertFalse(Files.exists(original))
            assertTrue(finalItem.supersededPartials.isEmpty())
            assertTrue(savedItem.supersededPartials.isEmpty())
            assertTrue(Files.exists(world.appFolder.resolve(item.fileName)))
        } else {
            assertEquals(DownloadStatus.Failed, finalItem.status)
            assertContentEquals(originalBytes, Files.readAllBytes(original))
            assertEquals(listOf(kept), finalItem.supersededPartials)
            assertEquals(listOf(kept), savedItem.supersededPartials)
            assertFalse(Files.exists(world.appFolder.resolve(item.fileName)))
        }
    }

    @Test
    fun legacy_partial_keeps_its_original_location_after_pause_move_and_resume() {
        val oldBytes = ByteArray(80 * 1024) { (it * 13).toByte() }
        val payload = ByteArray(200 * 1024) { (it * 7).toByte() }
        val original = world.appFolder.also { Files.createDirectories(it) }.resolve("moved.mkv.part")
        Files.write(original, oldBytes)
        val server = startServer { s -> s.createContext("/video") { exchange ->
            exchange.responseHeaders.add("ETag", "\"new\"")
            serveRanged(exchange, payload)
        } }
        var item = baseItem("http://127.0.0.1:${server.address.port}/video", "moved.mkv").copy(
            partialFileKey = DownloadMover.identityOf(original),
            downloadedBytes = oldBytes.size.toLong(),
            totalBytes = payload.size.toLong(),
            sourceETag = "\"old\"",
            pendingLinkVerification = true,
        )
        val paused = runDownload(DownloadPlatformRequest(
            item = item,
            mayPublish = { false },
            onSwitchedToNewPartial = { name, kept ->
                item = item.copy(partialFileName = name, partialFileKey = null,
                    supersededPartials = listOfNotNull(kept), pendingLinkVerification = false)
            },
            onPartialCreated = { key -> item = item.copy(partialFileKey = key) },
        ))
        assertTrue(paused.paused)
        val drive = world.driveLocation("External")
        val active = world.appFolder.resolve(item.activePartialFileName)
        Files.move(active, drive.path.resolve(item.activePartialFileName))
        // A paused transfer can be saved and loaded after its active file moves to another location.
        item = DownloadsCodec.decode(DownloadsCodec.encode(listOf(item.copy(locationId = drive.id)), null))!!.items.single()
        val retired = AtomicReference<Set<String>>(emptySet())
        val resumed = runDownload(DownloadPlatformRequest(item, onSupersededPartialsRetired = { retired.set(it) }))
        assertEquals(null, resumed.failure)
        assertContentEquals(payload, Files.readAllBytes(drive.path.resolve(item.fileName)))
        assertFalse(Files.exists(original), "Original in the app folder was orphaned after the move")
        assertEquals(setOf(item.supersededPartials.single().partialFileKey), retired.get())
        assertEquals(DownloadLocations.AppId, item.supersededPartials.single().locationId)
    }

    private fun awaitStatus(key: String, target: DownloadStatus, matchLogicalKey: Boolean = false) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            val match = DownloadsRepository.uiState.value.items.firstOrNull {
                if (matchLogicalKey) it.logicalContentKey == key else it.videoId == key
            }
            if (match?.status == target) return
            if (match?.status == DownloadStatus.Failed && target != DownloadStatus.Failed) {
                throw AssertionError("Download $key failed unexpectedly: ${match.errorMessage}")
            }
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for $key to reach $target")
    }

    private fun respondJson(exchange: HttpExchange, json: String) {
        val bytes = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun serveRanged(
        exchange: HttpExchange,
        payload: ByteArray,
        rangeOverride: (String?) -> Unit = {},
    ) {
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        rangeOverride(rangeHeader)
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val dash = spec.indexOf('-')
            val start = spec.substring(0, dash).toInt()
            val end = spec.substring(dash + 1).takeIf { it.isNotEmpty() }?.toInt()?.coerceAtMost(payload.lastIndex) ?: payload.lastIndex
            if (start >= payload.size || start > end) {
                exchange.responseHeaders.add("Content-Range", "bytes */${payload.size}")
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return
            }
            val length = end - start + 1
            exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
            exchange.sendResponseHeaders(206, length.toLong())
            exchange.responseBody.use { out -> out.write(payload, start, length) }
        } else {
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { out -> out.write(payload) }
        }
    }
}
