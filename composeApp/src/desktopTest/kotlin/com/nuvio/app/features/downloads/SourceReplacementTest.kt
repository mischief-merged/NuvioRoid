package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A download that is not the real video, or whose source failed for good, is followed by the app's own
 * search for another source: the real repository and the real season downloader, with sources and
 * transfers in memory.
 */
class SourceReplacementTest {
    private class FakeBackend : DownloadsBackend {
        var payload: String? = null
        val files = mutableMapOf<String, DownloadedFileFacts>()
        val started = CopyOnWriteArrayList<String>()
        val trashed = CopyOnWriteArrayList<String>()
        val removed = CopyOnWriteArrayList<String?>()
        val removedPartials = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        val fileAccesses = CopyOnWriteArrayList<String>()
        val disconnected = mutableSetOf<String>()
        val lateReports = mutableMapOf<String, () -> Unit>()

        override fun isConnected(item: DownloadItem): Boolean = item.sourceUrl !in disconnected
        override fun heldByFolder(item: DownloadItem): Boolean = ItemLocks.isHeld(item.id) || !isConnected(item)

        /** Addresses whose server announces a short clip in place of the file. */
        val placeholders = mutableSetOf<String>()

        /** Addresses whose transfer starts and then sends nothing. */
        val silent = mutableSetOf<String>()
        val failures = mutableMapOf<String, String>()
        val pendingFailures = mutableMapOf<String, (String) -> Unit>()
        val audioLanguages = mutableMapOf<String, List<String>>()

        override fun loadPayload(): String? = payload
        override fun savePayload(payload: String) { this.payload = payload }
        override fun restoreItem(item: DownloadItem): DownloadItem = item

        override fun start(
            request: DownloadPlatformRequest,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
            onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
            onFailure: (message: String) -> Unit,
            onPaused: () -> Unit,
        ): DownloadsTaskHandle {
            val url = request.item.sourceUrl
            started += url
            lateReports[url] = {
                onProgress(FilmBytes, FilmBytes)
                onSuccess("file:/new/late.mkv", FilmBytes)
                onFailure("HTTP 403")
                onPaused()
            }
            when (url) {
                in silent -> pendingFailures[url] = onFailure
                in failures -> onFailure(failures.getValue(url))
                in placeholders -> onProgress(0L, ClipBytes)
                else -> {
                    val uri = "file:/new/" + url.substringAfterLast('/')
                    files[uri] = DownloadedFileFacts(exists = true, sizeBytes = FilmBytes, isVideoContainer = true, audioLanguages = audioLanguages[url])
                    onSuccess(uri, FilmBytes)
                }
            }
            return object : DownloadsTaskHandle {
                override fun cancel() {
                    cancelled += url
                    onPaused()
                }
            }
        }

        override fun removeFile(localFileUri: String?): Boolean {
            removed += localFileUri
            return true
        }

        override fun removePartialFile(destinationFileName: String): Boolean {
            removedPartials += destinationFileName
            return true
        }
        override fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
            fileAccesses += "resolve:$destinationFileName"
            return localFileUri?.takeIf { it in files }
        }
        override fun inspectFile(localFileUri: String): DownloadedFileFacts {
            fileAccesses += "inspect:$localFileUri"
            return files[localFileUri] ?: DownloadedFileFacts(exists = false)
        }

        override fun trashFile(localFileUri: String): Boolean {
            trashed += localFileUri
            return true
        }
    }

    private companion object {
        const val ClipBytes = 8_509_628L
        const val FilmBytes = 9_530_000_000L
        const val PlaceholderUri = "file:/downloads/film.mp4"
        const val TitleKey = "tt1|movie"
    }

    private val backend = FakeBackend()
    private val realBackend = DownloadsRepository.backend
    private val realRuntimeLookup = downloadRuntimeLookup
    private val realCompanions = DownloadsRepository.companions
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun use_an_in_memory_store() {
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = backend
        DownloadsRepository.companions = { DownloadCompanions(emptyList()) }
        downloadRuntimeLookup = { null }
    }

    @AfterTest
    fun restore_the_real_repository() {
        scopes.forEach { it.cancel() }
        DownloadsRepository.clearLocalState()
        DownloadsRepository.backend = realBackend
        DownloadsRepository.companions = realCompanions
        downloadRuntimeLookup = realRuntimeLookup
    }

    private fun claim(name: String, quality: String) = "[💾 9.53 GB] Some.Title.2026.$quality.WEB-DL-$name.mkv"

    private fun url(name: String) = "https://example.com/$name.mkv"

    /** A source as an add-on lists it. [cached] false writes the "not cached yet" tag of the add-on. */
    private fun source(name: String, quality: String = "1080p", cached: Boolean = true) = StreamItem(
        name = if (cached) "[RD+] $name $quality" else "[RD download] $name $quality",
        description = claim(name, quality),
        url = url(name),
        addonName = "Addon",
        addonId = "addon",
    )

    /** Disclosure Day as it stands in the list: a finished download from source "a", marked as not the real video. */
    private fun flaggedRecord(extra: String = "") =
        """{"id":"p","contentType":"movie","parentMetaType":"movie","videoId":"tt1","parentMetaId":"tt1","title":"Some Title",""" +
            """"streamTitle":"[RD+] a 1080p","streamSubtitle":"${claim("a", "1080p")}","providerName":"Addon",""" +
            """"sourceUrl":"${url("a")}","fileName":"film.mp4","localFileUri":"$PlaceholderUri","status":"Completed",""" +
            """"downloadedBytes":$ClipBytes,"totalBytes":$ClipBytes,"framesCheck":"NotTheRealVideo",""" +
            """"errorMessage":"Not the real video: 8.1 MB arrived, the source said 9.5 GB","createdAtEpochMs":1,"updatedAtEpochMs":1$extra}"""

    /** A download from source "a" that failed because its source refused it. */
    private val refusedRecord =
        """{"id":"f","contentType":"movie","parentMetaType":"movie","videoId":"tt1","parentMetaId":"tt1","title":"Some Title",""" +
            """"streamTitle":"[RD+] a 1080p","streamSubtitle":"${claim("a", "1080p")}","providerName":"Addon",""" +
            """"sourceUrl":"${url("a")}","fileName":"film.mp4","status":"Failed","failureKind":"SourceRefused",""" +
            """"errorMessage":"The source refused","createdAtEpochMs":1,"updatedAtEpochMs":1}"""

    private fun load(vararg records: String) {
        backend.payload = """{"items":[${records.joinToString(",")}]}"""
        backend.files[PlaceholderUri] = DownloadedFileFacts(exists = true, sizeBytes = ClipBytes, isVideoContainer = true, durationMs = 300_900L)
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
    }

    /** The app restarts: what is in memory is gone, the stored list is read again. */
    private fun restart() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
    }

    private fun items() = DownloadsRepository.uiState.value.items

    private fun newStarts() = backend.started.toList()

    /** The app's search wired to the real season downloader, with [available] as what the lookup finds. */
    private inner class Search(
        var available: List<StreamItem>,
        val canSearch: MutableStateFlow<Boolean> = MutableStateFlow(true),
        var online: Boolean = true,
        val audioLanguage: DebridStreamLanguage? = null,
    ) {
        val finished = CopyOnWriteArrayList<SeasonBatchEpisodeResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        lateinit var replacer: AutoSourceReplacer
        val batches = SeasonBatchDownloader(
            scope = scope,
            downloads = DownloadsRepository.uiState,
            fetchStreams = { available },
            prepareStream = { stream, _ -> stream },
            enqueue = { request, episode, stream ->
                val movie = episode.movie!!
                DownloadsRepository.enqueueFromStream(
                    contentType = movie.metaType, videoId = episode.videoId, parentMetaId = movie.metaId,
                    parentMetaType = movie.metaType, title = movie.title, logo = null, poster = null, background = null,
                    seasonNumber = null, episodeNumber = null, episodeTitle = null, episodeThumbnail = null, stream = stream,
                )
            },
            onFinished = { request, summary ->
                summary.results.forEach { (episode, result) ->
                    replacer.onSearchFinished(request.titleKey(episode), result)
                    finished += result
                }
            },
            discard = SeasonBatchDownloads::discardSource,
            inspect = DownloadsRepository::inspectCompletedDownload,
            onSourceTried = { request, episode, attempt -> replacer.onSourceTried(request.titleKey(episode), attempt) },
        )

        init {
            replacer = AutoSourceReplacer(
                downloads = DownloadsRepository.uiState,
                busyTitles = batches.titlesInFlight,
                canSearch = canSearch,
                isOnline = { online },
                isAway = { false },
                startSearch = { step ->
                    batches.start(replacementRequest(step.bad, step.skip, step.attemptsUsed, SeasonDownloadDefaults(audioLanguage = audioLanguage), null)) ==
                        SeasonBatchStart.Started
                },
                giveUp = DownloadsRepository::markNoWorkingSourceFound,
            )
        }

        fun watch() = scope.launch { replacer.watch() }

        fun awaitSearches(count: Int) = runBlocking {
            withTimeout(20_000) {
                while (finished.size < count) delay(10)
            }
        }
    }

    private fun awaitItems(predicate: (List<DownloadItem>) -> Boolean) = runBlocking {
        withTimeout(20_000) { DownloadsRepository.uiState.first { predicate(it.items) } }
    }

    // What the search does

    @Test
    fun a_file_that_is_not_the_video_is_followed_by_the_best_untried_source_and_a_cached_one_comes_first() {
        load(flaggedRecord())
        // "a" is the source of the bad file. "b" is the higher resolution but not cached yet; "c" is cached.
        val search = Search(listOf(source("a"), source("b", "2160p", cached = false), source("c", "1080p", cached = true)))

        search.watch()
        awaitItems { list -> list.any { it.sourceUrl == url("c") && it.status == DownloadStatus.Completed } }
        search.awaitSearches(1)

        assertEquals(listOf(url("c")), newStarts(), "the bad source again, or a source not cached before a cached one")
        val replacement = items().single { it.sourceUrl == url("c") }
        assertEquals(1, replacement.autoSourceAttempts)
        assertTrue(replacement.triedSources.containsAll(source("a").sourceIdentityKeys), "the bad source was not remembered")
        assertTrue(replacement.triedSources.containsAll(source("c").sourceIdentityKeys))
    }

    @Test
    fun a_source_that_failed_for_good_is_followed_by_the_next_untried_source() {
        load(refusedRecord)
        val search = Search(listOf(source("a"), source("b")))

        search.watch()
        awaitItems { list -> list.any { it.sourceUrl == url("b") && it.status == DownloadStatus.Completed } }

        assertEquals(listOf(url("b")), newStarts())
        // The failed record had no file; the new download took its place.
        assertEquals(listOf(url("b")), items().map { it.sourceUrl })
    }

    @Test
    fun the_bad_file_is_never_counted_as_downloaded_and_is_never_removed() {
        load(flaggedRecord())
        backend.placeholders += url("b")
        val search = Search(listOf(source("b"), source("c")))

        search.watch()
        awaitItems { list -> list.any { it.sourceUrl == url("c") && it.status == DownloadStatus.Completed } }

        val state = DownloadsRepository.uiState.value
        assertEquals(listOf(url("c")), state.completedItems.map { it.sourceUrl }, "only the real video counts as downloaded")
        val placeholder = state.items.single { it.id == "p" }
        assertTrue(placeholder.isPlaceholder)
        assertEquals(PlaceholderUri, placeholder.localFileUri)
        assertTrue(backend.trashed.isEmpty(), "the search moved a file to the trash: ${backend.trashed}")
        assertFalse(PlaceholderUri in backend.removed, "the search removed the file the user owns")
        assertTrue(state.items.none { it.status == DownloadStatus.Failed && it.isPlayable })
    }

    @Test
    fun the_search_stops_after_three_sources_and_leaves_the_choice_to_the_user() {
        load(flaggedRecord())
        val sources = listOf("b", "c", "d", "e", "f").map { source(it) }
        sources.forEach { backend.placeholders += it.url!! }
        val search = Search(sources)

        search.watch()
        awaitItems { list -> list.any { it.id == "p" && it.noWorkingSourceFound } }
        search.awaitSearches(1)
        // Another look changes nothing.
        search.replacer.evaluate()
        runBlocking { delay(300) }

        assertEquals(listOf(url("b"), url("c"), url("d")), newStarts())
        val placeholder = items().single { it.id == "p" }
        assertEquals(SourceSearchRowState.NoneFound, sourceSearchRowState(placeholder, search.replacer.progress.value))
        val lastTry = items().single { it.id != "p" }
        assertTrue(lastTry.noWorkingSourceFound)
        assertEquals(3, lastTry.autoSourceAttempts)
        assertEquals(SourceSearchRowState.NoneFound, sourceSearchRowState(lastTry, search.replacer.progress.value))
    }

    @Test
    fun with_no_untried_source_left_it_gives_up_at_once() {
        load(flaggedRecord())
        val search = Search(listOf(source("a")))

        search.watch()
        awaitItems { list -> list.single().noWorkingSourceFound }

        assertTrue(newStarts().isEmpty())
    }

    @Test
    fun tried_sources_are_still_skipped_after_a_restart() {
        load(flaggedRecord())
        backend.placeholders += url("c")
        // The internet goes away while the first try fails, so the search ends without giving up.
        val first = Search(listOf(source("a"), source("c")), online = false)
        first.watch()
        first.awaitSearches(1)
        assertEquals(listOf(url("c")), newStarts())
        assertFalse(items().any { it.noWorkingSourceFound })

        restart()
        val second = Search(listOf(source("a"), source("c"), source("b")))
        second.watch()
        awaitItems { list -> list.any { it.sourceUrl == url("b") && it.status == DownloadStatus.Completed } }

        assertEquals(listOf(url("c"), url("b")), newStarts(), "a source tried before the restart was fetched again")
        assertEquals(2, items().single { it.sourceUrl == url("b") }.autoSourceAttempts)
    }

    @Test
    fun discarded_replacements_keep_the_history_and_limit_after_a_restart() {
        load(refusedRecord)
        backend.audioLanguages[url("b")] = listOf("rus")
        backend.silent += url("c")
        val first = Search(listOf(source("b"), source("c")), audioLanguage = DebridStreamLanguage.EN)
        first.watch()
        awaitItems { list -> list.any { it.sourceUrl == url("c") && it.status == DownloadStatus.Downloading } }

        val running = items().single { it.sourceUrl == url("c") }
        assertEquals(2, running.autoSourceAttempts)
        assertTrue(running.triedSources.containsAll(source("a").sourceIdentityKeys + source("b").sourceIdentityKeys))

        restart()
        assertEquals(2, items().single { it.sourceUrl == url("c") }.autoSourceAttempts)
        backend.pendingFailures.getValue(url("c"))("HTTP 403")
        backend.placeholders += url("d")
        val second = Search(listOf("a", "b", "c", "d", "e").map { source(it) })
        second.watch()
        second.awaitSearches(1)

        assertEquals(listOf(url("b"), url("c"), url("c"), url("d")), newStarts(), "only the interrupted transfer resumes; old sources stay skipped")
        assertEquals(3, items().maxOf { it.autoSourceAttempts })
        assertTrue(items().any { it.noWorkingSourceFound })
        assertTrue(backend.removed.filterNotNull().isEmpty(), "rejected files must not be permanently removed")
    }

    @Test
    fun a_restart_between_discarding_and_queueing_keeps_the_rejected_source_history() {
        load(refusedRecord)
        val bad = items().single()
        val request = replacementRequest(bad, bad.sourceIdentityKeys.toSet(), 0, SeasonDownloadDefaults(), null)
        SeasonBatchDownloads.enqueueEpisode(request, request.episodes.single(), source("b"))
        val finished = items().single()
        SeasonBatchDownloads.discardSource(finished)
        // Looking at the file again must not erase a rejection of its audio or download choices.
        DownloadsRepository.recheckCompleted(finished.id)
        assertTrue(items().single().hasBadSource)

        restart()
        val step = assertIs<SourceReplacementStep.Search>(sourceReplacementSteps(items(), emptySet()).single())
        assertEquals(1, step.attemptsUsed)
        assertTrue(step.skip.containsAll(source("a").sourceIdentityKeys + source("b").sourceIdentityKeys))
        val second = Search(listOf(source("a"), source("b"), source("c")))
        second.watch()
        awaitItems { list -> list.any { it.sourceUrl == url("c") && it.status == DownloadStatus.Completed } }
        second.awaitSearches(1)

        assertEquals(listOf(url("b"), url("c")), newStarts())
        assertEquals(2, items().single { it.sourceUrl == url("c") }.autoSourceAttempts)
        assertTrue(backend.removed.filterNotNull().isEmpty())
        assertTrue(backend.removedPartials.isEmpty())
    }

    @Test
    fun a_downloading_source_is_rejected_while_a_folder_move_holds_it() =
        rejectHeldSource(completed = false, moving = true)

    @Test
    fun a_completed_source_is_rejected_while_a_folder_move_holds_it() =
        rejectHeldSource(completed = true, moving = true)

    @Test
    fun a_downloading_source_is_rejected_while_its_drive_is_disconnected() =
        rejectHeldSource(completed = false, moving = false)

    @Test
    fun a_completed_source_is_rejected_while_its_drive_is_disconnected() =
        rejectHeldSource(completed = true, moving = false)

    private fun rejectHeldSource(completed: Boolean, moving: Boolean) {
        load(refusedRecord)
        if (!completed) backend.silent += url("b")
        val bad = items().single()
        val request = replacementRequest(bad, bad.sourceIdentityKeys.toSet(), 0, SeasonDownloadDefaults(), null)
        SeasonBatchDownloads.enqueueEpisode(request, request.episodes.single(), source("b"))
        val rejected = items().single()
        assertEquals(if (completed) DownloadStatus.Completed else DownloadStatus.Downloading, rejected.status)
        assertFalse(rejected.hasBadSource)
        val accesses = backend.fileAccesses.toList()
        val files = backend.files.toMap()
        val removed = backend.removed.toList()
        val partials = backend.removedPartials.toList()
        val trashed = backend.trashed.toList()
        val cancellations = backend.cancelled.size
        if (moving) assertTrue(ItemLocks.tryAcquire(rejected.id)) else backend.disconnected += url("b")
        try {
            assertTrue(DownloadsRepository.isBusyOrAway(rejected))
            SeasonBatchDownloads.discardSource(rejected)

            val saved = items().single()
            assertEquals(DownloadFailureKind.BadFile, saved.failureKind)
            assertEquals(if (completed) DownloadStatus.Completed else DownloadStatus.Failed, saved.status)
            assertTrue(saved.hasBadSource)
            assertFalse(saved.isPlayable)
            assertEquals(rejected.localFileUri, saved.localFileUri)
            assertEquals(rejected.triedSources, saved.triedSources)
            assertEquals(rejected.autoSourceAttempts, saved.autoSourceAttempts)
            assertEquals(cancellations + if (completed) 0 else 1, backend.cancelled.size)
            assertEquals(saved, DownloadsCodec.decode(backend.payload!!)!!.items.single())

            // Reports already in flight, including a pause from cancel(), cannot undo rejection.
            backend.lateReports.getValue(url("b"))()
            assertEquals(saved, items().single())
            assertEquals(accesses, backend.fileAccesses.toList(), "rejection accessed a held download's files")
            assertEquals(files, backend.files.toMap())
            assertEquals(removed, backend.removed.toList())
            assertEquals(partials, backend.removedPartials.toList())
            assertEquals(trashed, backend.trashed.toList())
            if (moving) assertTrue(ItemLocks.isHeld(rejected.id))
        } finally {
            if (moving) ItemLocks.release(rejected.id) else backend.disconnected -= url("b")
        }

        restart()
        val search = Search(listOf(source("b"), source("c")))
        search.watch()
        search.awaitSearches(1)
        assertEquals(listOf(url("b"), url("c")), newStarts())
        val replacement = items().single { it.sourceUrl == url("c") }
        assertEquals(DownloadStatus.Completed, replacement.status)
        assertEquals(2, replacement.autoSourceAttempts)
        assertTrue(replacement.triedSources.containsAll(source("a").sourceIdentityKeys + source("b").sourceIdentityKeys))
    }

    @Test
    fun a_transient_failure_keeps_the_automatic_count_after_a_restart() {
        load(refusedRecord)
        backend.failures[url("b")] = "Connection reset"
        backend.silent += url("c")
        val first = Search(listOf(source("b"), source("c")))
        first.watch()
        awaitItems { list -> list.any { it.sourceUrl == url("c") && it.status == DownloadStatus.Downloading } }

        assertEquals(2, items().single().autoSourceAttempts)
        assertTrue(backend.removedPartials.isEmpty(), "changing sources must keep any partial download")
        restart()
        assertEquals(2, items().single().autoSourceAttempts)
        backend.pendingFailures.getValue(url("c"))("HTTP 403")
        backend.placeholders += url("d")
        val second = Search(listOf("a", "b", "c", "d", "e").map { source(it) })
        second.watch()
        second.awaitSearches(1)

        assertEquals(listOf(url("c"), url("c"), url("d")), newStarts().filterNot { it == url("b") })
        assertEquals(3, items().single().autoSourceAttempts)
        assertTrue(items().single().noWorkingSourceFound)
    }

    @Test
    fun a_title_given_up_on_is_not_searched_again_after_a_restart() {
        load(flaggedRecord())
        backend.placeholders += url("c")
        val first = Search(listOf(source("c")))
        first.watch()
        awaitItems { list -> list.any { it.id == "p" && it.noWorkingSourceFound } }
        first.awaitSearches(1)
        val startsBefore = newStarts().size

        restart()
        val second = Search(listOf(source("b")))
        second.watch()
        second.replacer.evaluate()
        runBlocking { delay(500) }

        assertEquals(startsBefore, newStarts().size)
        assertTrue(second.finished.isEmpty())
    }

    @Test
    fun a_flagged_download_from_before_the_update_is_searched_once_when_the_app_starts() {
        load(flaggedRecord())
        val search = Search(listOf(source("b")))

        search.watch()
        awaitItems { list -> list.any { it.sourceUrl == url("b") && it.status == DownloadStatus.Completed } }
        search.awaitSearches(1)
        search.replacer.evaluate()
        runBlocking { delay(300) }

        assertEquals(1, search.finished.size)
        assertEquals(listOf(url("b")), newStarts())
    }

    // What it leaves alone

    @Test
    fun a_download_the_user_paused_is_left_alone() {
        load(flaggedRecord())
        backend.silent += url("b")
        val search = Search(listOf(source("b"), source("c")))

        search.watch()
        val running = awaitItems { list -> list.any { it.sourceUrl == url("b") && it.status == DownloadStatus.Downloading } }
            .let { items().single { it.sourceUrl == url("b") } }
        DownloadsRepository.pauseDownload(running.id)
        search.awaitSearches(1)
        search.replacer.evaluate()
        runBlocking { delay(300) }

        assertEquals(SeasonBatchEpisodeResult.Paused, search.finished.single())
        assertEquals(listOf(url("b")), newStarts(), "the search went on past a download the user paused")
        assertEquals(DownloadStatus.Paused, items().single { it.sourceUrl == url("b") }.status)
        assertFalse(items().any { it.noWorkingSourceFound })
    }

    @Test
    fun nothing_is_searched_while_downloads_wait_for_a_better_connection() {
        load(flaggedRecord())
        val search = Search(listOf(source("b")), canSearch = MutableStateFlow(false))

        search.watch()
        runBlocking { delay(500) }
        assertTrue(newStarts().isEmpty(), "a search started on a limited connection")
        assertEquals(SourceSearchRowState.Waiting, sourceSearchRowState(items().single(), search.replacer.progress.value))

        search.canSearch.value = true
        awaitItems { list -> list.any { it.sourceUrl == url("b") && it.status == DownloadStatus.Completed } }
        assertEquals(listOf(url("b")), newStarts())
    }

    @Test
    fun a_season_download_working_on_the_title_is_left_to_do_it() {
        load(flaggedRecord())

        val steps = sourceReplacementSteps(items(), busyTitles = setOf(TitleKey))

        assertTrue(steps.isEmpty())
    }

    @Test
    fun a_download_of_an_automatic_rule_is_left_to_its_rule() {
        load(flaggedRecord(""","autoDownloadRuleKey":"rule""""))

        assertTrue(sourceReplacementSteps(items(), emptySet()).isEmpty())
        assertNull(sourceSearchRowState(items().single(), emptyMap()))
    }

    // The history a download carries

    @Test
    fun a_new_download_takes_over_the_history_of_its_title_and_counts_an_automatic_try() {
        load(flaggedRecord(""","autoSourceAttempts":1,"triedSources":["label:old"]"""))

        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = "tt1", parentMetaId = "tt1", parentMetaType = "movie", title = "Some Title",
            logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null, episodeTitle = null,
            episodeThumbnail = null, stream = source("b"),
        )

        val added = items().single { it.id != "p" }
        assertEquals(2, added.autoSourceAttempts)
        assertTrue("label:old" in added.triedSources)
        assertTrue(added.triedSources.containsAll(source("a").sourceIdentityKeys))
    }

    @Test
    fun a_source_the_user_picks_starts_the_count_again() {
        load(flaggedRecord(""","autoSourceAttempts":3,"noWorkingSourceFound":true"""))

        DownloadsRepository.enqueueFromStream(
            contentType = "movie", videoId = "tt1", parentMetaId = "tt1", parentMetaType = "movie", title = "Some Title",
            logo = null, poster = null, background = null, seasonNumber = null, episodeNumber = null, episodeTitle = null,
            episodeThumbnail = null, stream = source("b"), userChoseReplacement = true,
        )

        val added = items().single()
        assertEquals(0, added.autoSourceAttempts)
        assertFalse(added.noWorkingSourceFound)
        assertTrue(added.triedSources.containsAll(source("a").sourceIdentityKeys))
    }

    @Test
    fun the_identity_of_a_download_matches_the_listing_it_came_from() {
        load(flaggedRecord())

        assertTrue(source("a").wasTried(items().single().sourceIdentityKeys.toSet()))
        assertFalse(source("b").wasTried(items().single().sourceIdentityKeys.toSet()))
    }

    @Test
    fun a_search_for_a_movie_asks_for_no_more_sources_than_are_left() {
        load(flaggedRecord())
        val bad = items().single()

        val request = replacementRequest(bad, setOf("label:x"), attemptsUsed = 2, SeasonDownloadDefaults(), runtimeMinutes = 120)

        assertTrue(request.replacesBadSource)
        assertEquals(1, request.maxSources)
        assertEquals(setOf("label:x"), request.skipSources)
        assertEquals(TitleKey, request.titleKey(request.episodes.single()))
        assertEquals("tt1", request.episodes.single().movie?.metaId)
        assertEquals(120, request.episodes.single().runtimeMinutes)
    }

    @Test
    fun the_row_says_which_try_is_running() {
        load(flaggedRecord())

        val state = sourceSearchRowState(items().single(), mapOf(TitleKey to SourceSearchProgress(2)))

        assertIs<SourceSearchRowState.Trying>(state)
        assertEquals(2, state.attempt)
        assertEquals(3, state.of)
    }
}
