package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DebridStreamLanguage
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutoDownloadEngineTest {
    private val hour = 3_600_000L
    private val ruleKey = testRule().key

    /**
     * An engine over the real season downloader. Downloads live in memory: queueing adds a
     * Downloading item and the test then settles it.
     */
    private class Harness(private val scope: TestScope) {
        var activeProfile = 1
        val payloads = MemoryAutoDownloadPayloads()
        val store = AutoDownloadStore(payloads) { activeProfile }
        val downloads = MutableStateFlow(DownloadsUiState())
        var released: List<SeasonBatchEpisode>? = (1..8).map { testEpisode(it) }
        val watched = mutableSetOf<Int>()
        var sources: (SeasonBatchEpisode) -> List<StreamItem> = { listOf(testStream("https://example.com/${it.episodeNumber}.mp4")) }
        var choices = SeasonDownloadDefaults()
        val requests = mutableListOf<SeasonBatchRequest>()
        val queued = mutableListOf<Int>()
        var mostAtOnce = 0
        var onEpisodesLoaded: () -> Unit = {}
        var onBatchStarting: () -> Unit = {}
        var autoDownloadBytes = 0L
        val resumed = mutableListOf<String>()

        private val downloader: SeasonBatchDownloader = SeasonBatchDownloader(
            scope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob()),
            downloads = downloads,
            fetchStreams = { sources(it) },
            prepareStream = { stream, _ -> stream },
            enqueue = ::enqueue,
            onFinished = { request, summary -> engine.onBatchFinished(request, summary) },
            discard = { item -> downloads.value = DownloadsUiState(downloads.value.items.filterNot { it.id == item.id }) },
        )

        val engine: AutoDownloadEngine = AutoDownloadEngine(
            store = store,
            loadEpisodes = { released.also { onEpisodesLoaded() } },
            isWatched = { _, episode -> episode.episodeNumber in watched },
            downloads = { downloads.value.items },
            seasonDefaults = { choices },
            startBatch = { request ->
                requests += request
                onBatchStarting()
                downloader.start(request)
            },
            cancelBatch = downloader::cancel,
            nowEpochMs = { scope.currentTime },
            autoDownloadBytes = { autoDownloadBytes },
            resumeDownload = { resumed += it },
        )

        private fun enqueue(request: SeasonBatchRequest, episode: SeasonBatchEpisode, stream: StreamItem): DownloadEnqueueResult {
            if (stream.playableDirectUrl?.hasPublicDownloadHost() == false) {
                engine.onPrivateSourceSkipped(request, episode)
                return DownloadEnqueueResult.UnsupportedFormat
            }
            queued += episode.episodeNumber
            val others = downloads.value.items.filterNot { it.episodeNumber == episode.episodeNumber }
            downloads.value = DownloadsUiState(
                others + testDownload(episode.episodeNumber, DownloadStatus.Downloading, ruleKey = request.autoDownloadRuleKey),
            )
            mostAtOnce = maxOf(mostAtOnce, downloading().size)
            return DownloadEnqueueResult.Started
        }

        fun settle(episode: Int, status: DownloadStatus?) {
            downloads.value = DownloadsUiState(
                downloads.value.items.mapNotNull { item ->
                    if (item.episodeNumber != episode) {
                        item
                    } else {
                        status?.let { item.copy(status = it, downloadedBytes = if (it == DownloadStatus.Completed) 700_000_000 else 0) }
                    }
                },
            )
        }

        fun stop() = downloader.cancel("tt1", 1)

        fun downloading() = downloads.value.items.filter { it.status == DownloadStatus.Downloading }.mapNotNull { it.episodeNumber }

        fun requestedEpisodes() = requests.flatMap { request -> request.episodes.map { it.episodeNumber } }

        fun status() = engine.statuses.value[testRule().key]

        suspend fun check(ignoreWaiting: Boolean = false) {
            engine.runCheck(ignoreWaiting = ignoreWaiting)
            scope.advanceUntilIdle()
        }
    }

    private fun TestScope.harness(rule: AutoDownloadRule = testRule(keepCount = 3)) =
        Harness(this).also { it.store.upsert(1, rule) }

    @Test
    fun a_check_queues_the_wanted_episodes_two_at_a_time_with_the_saved_choices() = runTest {
        val harness = harness()
        harness.watched += 1
        harness.choices = SeasonDownloadDefaults(BatchQuality.P1080, maxBitrateMbps = 10, audioLanguage = DebridStreamLanguage.EN)

        harness.check()

        assertEquals(listOf(2, 3), harness.downloading())
        assertEquals(AutoDownloadStatus.Downloading, harness.status())
        harness.settle(2, DownloadStatus.Completed)
        advanceUntilIdle()
        assertEquals(listOf(3, 4), harness.downloading())
        harness.settle(3, DownloadStatus.Completed)
        harness.settle(4, DownloadStatus.Completed)
        advanceUntilIdle()

        val request = harness.requests.single()
        assertEquals(listOf(2, 3, 4), request.episodes.map { it.episodeNumber })
        assertEquals(BatchQuality.P1080, request.quality)
        assertEquals(10, request.maxBitrateMbps)
        assertEquals(DebridStreamLanguage.EN, request.audioLanguage)
        assertEquals(ruleKey, request.autoDownloadRuleKey)
        assertEquals(2, harness.mostAtOnce)
        assertTrue(harness.downloads.value.items.all { it.autoDownloadRuleKey == ruleKey })
        assertEquals(AutoDownloadStatus.UpToDate, harness.status())
    }

    @Test
    fun an_episode_without_a_source_is_not_remembered_as_downloaded_and_is_tried_again_after_an_hour() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.sources = { emptyList() }

        harness.check()

        assertEquals(AutoDownloadStatus.NoSource, harness.status())
        assertEquals(emptySet(), harness.store.currentRules().single().ownedEpisodeKeys)
        assertEquals(emptyList(), harness.queued)

        advanceTimeBy(hour - 1)
        harness.check()
        assertEquals(listOf(1), harness.requestedEpisodes(), "tried again before the hour was over")

        advanceTimeBy(1)
        harness.sources = { listOf(testStream("https://example.com/1.mp4")) }
        harness.check()

        assertEquals(listOf(1, 1), harness.requestedEpisodes())
        assertEquals(listOf(1), harness.downloading())
    }

    @Test
    fun an_episode_whose_only_sources_are_at_private_addresses_says_so_and_not_that_no_source_was_found() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.sources = { listOf(testStream("http://192.168.1.20/1.mp4"), testStream("http://127.0.0.1:11470/1.mp4")) }

        harness.check()

        assertEquals(AutoDownloadStatus.PrivateSourcesSkipped, harness.status())
        assertEquals(emptyList(), harness.queued)

        advanceTimeBy(hour)
        harness.sources = { emptyList() }
        harness.check()
        assertEquals(AutoDownloadStatus.NoSource, harness.status())
    }

    @Test
    fun an_episode_that_downloads_from_a_public_source_after_a_private_one_was_skipped_is_not_reported() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.sources = { listOf(testStream("http://192.168.1.20/1.mp4"), testStream("https://example.com/1.mp4", label = "720p")) }

        harness.check()
        harness.settle(1, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(listOf(1), harness.queued)
        assertEquals(AutoDownloadStatus.UpToDate, harness.status())
    }

    @Test
    fun an_episode_whose_download_failed_is_tried_again_after_one_three_and_twelve_hours() = runTest {
        val harness = harness(testRule(keepCount = 1))
        suspend fun failOnce() {
            harness.check()
            harness.settle(1, DownloadStatus.Failed)
            advanceUntilIdle()
        }

        failOnce()
        assertEquals(AutoDownloadStatus.Failed, harness.status())
        assertEquals(1, harness.requests.size)

        advanceTimeBy(hour)
        failOnce()
        assertEquals(2, harness.requests.size)

        advanceTimeBy(3 * hour - 1)
        harness.check()
        assertEquals(2, harness.requests.size, "tried again before three hours were over")
        advanceTimeBy(1)
        failOnce()
        assertEquals(3, harness.requests.size)

        advanceTimeBy(12 * hour - 1)
        harness.check()
        assertEquals(3, harness.requests.size, "tried again before twelve hours were over")
        advanceTimeBy(1)
        harness.check()
        assertEquals(4, harness.requests.size)
    }

    @Test
    fun an_episode_that_downloads_after_failing_no_longer_waits() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.check()
        harness.settle(1, DownloadStatus.Failed)
        advanceUntilIdle()
        advanceTimeBy(hour)

        harness.check()
        harness.settle(1, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(AutoDownloadStatus.UpToDate, harness.status())
        assertEquals(setOf("1|1"), harness.store.currentRules().single().ownedEpisodeKeys)
    }

    @Test
    fun a_check_the_user_asked_for_does_not_wait() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.sources = { emptyList() }
        harness.check()

        harness.check(ignoreWaiting = true)

        assertEquals(listOf(1, 1), harness.requestedEpisodes())
    }

    @Test
    fun an_episode_that_was_stopped_before_it_was_queued_is_tried_again_after_an_hour() = runTest {
        val harness = harness(testRule(keepCount = 3))
        harness.check()
        assertEquals(listOf(1, 2), harness.downloading())

        harness.stop()
        advanceUntilIdle()
        harness.settle(1, DownloadStatus.Completed)
        harness.settle(2, DownloadStatus.Completed)
        advanceUntilIdle()
        harness.check()

        assertEquals(AutoDownloadStatus.Stopped, harness.status())
        assertEquals(1, harness.requests.size)

        advanceTimeBy(hour)
        harness.check()

        assertEquals(listOf(3), harness.requests.last().episodes.map { it.episodeNumber })
        assertEquals(listOf(3), harness.downloading())
    }

    @Test
    fun an_episode_removed_from_downloads_is_not_fetched_again() = runTest {
        val harness = harness(testRule(keepCount = 2))
        harness.check()
        harness.settle(1, DownloadStatus.Completed)
        harness.settle(2, DownloadStatus.Completed)
        advanceUntilIdle()

        harness.settle(2, null)
        harness.check()

        assertEquals(listOf(1, 2, 3), harness.queued)
        assertEquals(listOf(3), harness.requests.last().episodes.map { it.episodeNumber })
    }

    @Test
    fun an_episode_deleted_by_hand_before_it_finished_is_not_fetched_again() = runTest {
        val harness = harness(testRule(keepCount = 2))
        harness.check()
        harness.settle(1, DownloadStatus.Completed)
        advanceUntilIdle()
        val unfinished = harness.downloads.value.items.first { it.episodeNumber == 2 }
        assertEquals(DownloadStatus.Downloading, unfinished.status)

        // The user deletes the second episode while it is still downloading.
        assertTrue(harness.engine.onDeletedByUser(1, unfinished))
        harness.settle(2, null)
        harness.check(ignoreWaiting = true)

        assertTrue("1|2" in harness.store.rule(1, ruleKey)!!.ownedEpisodeKeys)
        assertEquals(1, harness.queued.count { it == 2 }, "episode 2 was queued again: ${harness.queued}")
    }

    @Test
    fun a_failed_episode_deleted_by_hand_stops_waiting_for_another_try() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.sources = { emptyList() }
        harness.check()
        assertEquals(AutoDownloadStatus.NoSource, harness.status())
        val failed = testDownload(1, DownloadStatus.Failed, ruleKey = ruleKey)

        harness.engine.onDeletedByUser(1, failed)

        assertTrue(harness.engine.rulesWithDueRetry().isEmpty())
        advanceTimeBy(2 * hour)
        assertTrue(harness.engine.rulesWithDueRetry().isEmpty())
    }

    @Test
    fun deleting_a_download_no_rule_queued_changes_no_rule() = runTest {
        val harness = harness(testRule(keepCount = 2))
        val byHand = testDownload(1, DownloadStatus.Paused, ruleKey = null)
        val unknownRule = testDownload(1, DownloadStatus.Paused, ruleKey = "series|other")

        assertFalse(harness.engine.onDeletedByUser(1, byHand))
        assertFalse(harness.engine.onDeletedByUser(1, unknownRule))
        assertFalse(harness.engine.onDeletedByUser(2, testDownload(1, DownloadStatus.Paused, ruleKey = ruleKey)))

        assertEquals(emptySet(), harness.store.rule(1, ruleKey)!!.ownedEpisodeKeys)
    }

    @Test
    fun watching_an_episode_moves_the_rule_on_to_the_next_one() = runTest {
        val harness = harness(testRule(keepCount = 2))
        harness.check()
        harness.settle(1, DownloadStatus.Completed)
        harness.settle(2, DownloadStatus.Completed)
        advanceUntilIdle()

        harness.watched += 1
        harness.check()

        assertEquals(listOf(3), harness.requests.last().episodes.map { it.episodeNumber })
        assertEquals(setOf(1, 2, 3), harness.downloads.value.items.mapNotNull { it.episodeNumber }.toSet())
    }

    @Test
    fun an_episode_list_that_cannot_be_fetched_queues_nothing() = runTest {
        val harness = harness()
        harness.released = null

        harness.check()

        assertEquals(AutoDownloadStatus.CouldNotCheck, harness.status())
        assertEquals(emptyList(), harness.requests)
    }

    @Test
    fun a_paused_download_is_left_alone_and_reported() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.check()

        harness.settle(1, DownloadStatus.Paused)
        advanceUntilIdle()
        harness.check()

        assertEquals(AutoDownloadStatus.Paused, harness.status())
        assertEquals(1, harness.requests.size)
    }

    @Test
    fun a_profile_change_while_episodes_are_fetched_queues_nothing_and_writes_nothing() = runTest {
        val harness = harness()
        harness.onEpisodesLoaded = { harness.activeProfile = 2 }
        val savedForFirstProfile = harness.payloads.saved[1]

        harness.check()

        assertEquals(emptyList(), harness.requests)
        assertEquals(savedForFirstProfile, harness.payloads.saved[1])
        assertNull(harness.payloads.saved[2])
    }

    @Test
    fun a_profile_change_stops_what_the_rules_of_the_previous_profile_were_adding() = runTest {
        val harness = harness(testRule(keepCount = 5))
        harness.check()
        assertEquals(listOf(1, 2), harness.downloading())

        harness.activeProfile = 2
        harness.engine.useProfile(2)
        harness.settle(1, DownloadStatus.Completed)
        harness.settle(2, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(listOf(1, 2), harness.queued)
        assertEquals(emptyMap(), harness.engine.statuses.value)
        assertTrue(harness.engine.isFullCheckDue())
    }

    @Test
    fun telling_the_engine_about_the_profile_it_already_works_for_stops_nothing() = runTest {
        val harness = harness(testRule(keepCount = 3))
        harness.check()

        harness.engine.useProfile(1)
        harness.settle(1, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(listOf(2, 3), harness.downloading())
    }

    @Test
    fun a_rule_turned_off_while_its_episodes_are_fetched_starts_nothing() = runTest {
        val harness = harness(testRule(keepCount = 3))
        harness.onEpisodesLoaded = {
            harness.store.remove(1, ruleKey)
            harness.engine.forget(ruleKey)
        }

        harness.check()

        assertEquals(emptyList(), harness.queued)
        assertNull(harness.status())
    }

    @Test
    fun a_rule_turned_off_while_its_batch_is_started_has_the_batch_stopped() = runTest {
        val harness = harness(testRule(keepCount = 5))
        harness.onBatchStarting = {
            harness.store.remove(1, ruleKey)
            harness.engine.forget(ruleKey)
        }

        harness.check()

        assertEquals(1, harness.requests.size)
        assertEquals(emptyList(), harness.queued)
        assertNull(harness.status())
    }

    @Test
    fun a_check_of_every_rule_is_due_at_first_and_again_six_hours_after_the_last_one() = runTest {
        val harness = harness(testRule(keepCount = 1))
        assertTrue(harness.engine.isFullCheckDue())

        harness.check()
        harness.settle(1, DownloadStatus.Completed)
        advanceUntilIdle()

        assertFalse(harness.engine.isFullCheckDue())
        advanceTimeBy(6 * hour - 1)
        assertFalse(harness.engine.isFullCheckDue())
        advanceTimeBy(1)
        assertTrue(harness.engine.isFullCheckDue())
    }

    @Test
    fun a_rule_is_reported_for_another_check_once_the_wait_of_one_of_its_episodes_is_over() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.sources = { emptyList() }
        harness.check()

        advanceTimeBy(hour - 1)
        assertEquals(emptyList(), harness.engine.rulesWithDueRetry())
        advanceTimeBy(1)
        assertEquals(listOf(ruleKey), harness.engine.rulesWithDueRetry())

        harness.watched += 1
        harness.released = listOf(testEpisode(1))
        harness.check()
        assertEquals(emptyList(), harness.engine.rulesWithDueRetry(), "an episode that is no longer wanted still waits")
    }

    @Test
    fun removing_the_last_download_of_a_rule_brings_its_status_up_to_date() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.check()
        harness.settle(1, DownloadStatus.Paused)
        advanceUntilIdle()
        harness.engine.onDownloadsChanged(1, harness.downloads.value.items)
        assertEquals(AutoDownloadStatus.Paused, harness.status())

        harness.settle(1, null)
        harness.engine.onDownloadsChanged(1, harness.downloads.value.items)

        assertEquals(AutoDownloadStatus.Pending, harness.status())
        assertEquals(emptySet(), harness.store.currentRules().single().ownedEpisodeKeys)
    }

    @Test
    fun a_download_that_failed_and_was_then_removed_is_fetched_again() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.check()
        harness.engine.onDownloadsChanged(1, harness.downloads.value.items)
        harness.settle(1, DownloadStatus.Failed)
        advanceUntilIdle()
        harness.engine.onDownloadsChanged(1, harness.downloads.value.items)

        harness.settle(1, null)
        harness.check(ignoreWaiting = true)

        assertEquals(listOf(1, 1), harness.requestedEpisodes())
        assertEquals(listOf(1), harness.downloading())
    }

    @Test
    fun turning_a_rule_off_stops_what_it_was_adding() = runTest {
        val harness = harness(testRule(keepCount = 5))
        harness.check()

        harness.store.remove(1, ruleKey)
        harness.engine.forget(ruleKey)
        harness.settle(1, DownloadStatus.Completed)
        harness.settle(2, DownloadStatus.Completed)
        advanceUntilIdle()

        assertEquals(listOf(1, 2), harness.queued)
        assertNull(harness.status())
    }

    @Test
    fun a_rule_changed_while_its_batch_runs_leaves_the_batch_alone_and_applies_from_the_next_check() = runTest {
        val harness = harness(testRule(keepCount = 3))
        harness.check()
        assertEquals(listOf(1, 2), harness.downloading())

        harness.store.upsert(1, testRule(keepCount = 5))
        harness.check(ignoreWaiting = true)

        assertEquals(1, harness.requests.size, "a second batch was started for the season that is being added")
        assertEquals(listOf(1, 2), harness.queued)
        (1..3).forEach {
            harness.settle(it, DownloadStatus.Completed)
            advanceUntilIdle()
        }
        assertEquals(listOf(1, 2, 3), harness.queued)

        harness.check()

        assertEquals(listOf(4, 5), harness.requests.last().episodes.map { it.episodeNumber })
        assertEquals(listOf(1, 2, 3, 4, 5), harness.queued)
    }

    @Test
    fun a_download_that_arrives_as_a_tiny_clip_is_tried_again_like_a_failed_one() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.check()

        harness.downloads.value = DownloadsUiState(harness.downloads.value.items.map { it.copy(status = DownloadStatus.Completed, downloadedBytes = 73_615) })
        advanceUntilIdle()

        assertEquals(AutoDownloadStatus.Failed, harness.status())
        assertEquals(emptyList(), harness.downloads.value.items)
        assertEquals(emptySet(), harness.store.currentRules().single().ownedEpisodeKeys)
    }

    @Test
    fun nothing_is_queued_once_automatic_downloads_take_as_much_as_the_storage_limit() = runTest {
        val harness = harness(testRule(keepCount = 2))
        harness.store.setStorageLimit(1, 10)
        harness.autoDownloadBytes = 10_000_000_000L

        harness.check()

        assertEquals(emptyList(), harness.requests)
        assertEquals(AutoDownloadStatus.StorageLimitReached, harness.status())

        harness.autoDownloadBytes = 9_999_999_999L
        harness.check()

        assertEquals(listOf(1, 2), harness.downloading())
        assertEquals(AutoDownloadStatus.Downloading, harness.status())
    }

    @Test
    fun without_a_storage_limit_episodes_are_queued_whatever_automatic_downloads_take() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.store.setStorageLimit(1, null)
        harness.autoDownloadBytes = Long.MAX_VALUE

        harness.check()

        assertEquals(listOf(1), harness.downloading())
    }

    @Test
    fun a_download_paused_by_the_storage_limit_is_reported_and_resumed_only_when_there_is_room() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.store.setStorageLimit(1, 10)
        harness.downloads.value = DownloadsUiState(
            listOf(
                testDownload(1, DownloadStatus.Paused, ruleKey = ruleKey).copy(autoDownloadStop = AutoDownloadStop.StorageLimit),
            ),
        )
        harness.autoDownloadBytes = 10_000_000_000L

        harness.check()

        assertEquals(emptyList(), harness.resumed)
        assertEquals(AutoDownloadStatus.StorageLimitReached, harness.status())

        harness.autoDownloadBytes = 1_000_000_000L
        harness.check()

        assertEquals(listOf("tt1-1-1"), harness.resumed)
    }

    @Test
    fun a_download_paused_for_free_space_is_reported_and_tried_again_by_a_check() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.downloads.value = DownloadsUiState(
            listOf(
                testDownload(1, DownloadStatus.Paused, ruleKey = ruleKey).copy(autoDownloadStop = AutoDownloadStop.LowDiskSpace),
            ),
        )

        harness.check()

        assertEquals(listOf("tt1-1-1"), harness.resumed)
        assertEquals(AutoDownloadStatus.LowDiskSpace, harness.status())
    }

    @Test
    fun a_download_the_user_paused_is_not_resumed() = runTest {
        val harness = harness(testRule(keepCount = 1))
        harness.downloads.value = DownloadsUiState(listOf(testDownload(1, DownloadStatus.Paused, ruleKey = ruleKey)))

        harness.check()

        assertEquals(emptyList(), harness.resumed)
        assertEquals(AutoDownloadStatus.Paused, harness.status())
    }

    @Test
    fun only_one_rule_is_checked_when_one_is_named() = runTest {
        val harness = harness()
        harness.store.upsert(1, testRule(showId = "tt2"))

        harness.engine.runCheck(onlyRuleKey = testRule(showId = "tt2").key)
        advanceUntilIdle()

        assertEquals(listOf("tt2"), harness.requests.map { it.parentMetaId })
    }

    @Test
    fun an_episode_waiting_for_deletion_after_watching_queues_the_next_episode_in_the_same_check() = runTest {
        val harness = harness(testRule(keepCount = 3).copy(deleteAfterWatching = true))
        harness.downloads.value = DownloadsUiState(
            listOf(
                testDownload(1, DownloadStatus.Completed, ruleKey = ruleKey),
                testDownload(2, DownloadStatus.Completed, ruleKey = ruleKey),
                testDownload(3, DownloadStatus.Completed, ruleKey = ruleKey),
            ),
        )
        assertTrue(harness.store.scheduleRemoval(1, AutoDownloadRemoval("tt1-1-1", ruleKey, dueAtEpochMs = 24 * hour)))

        harness.check()

        assertEquals(listOf(4), harness.requests.last().episodes.map { it.episodeNumber })
        assertEquals(listOf(4), harness.downloading())
        assertEquals(AutoDownloadStatus.Downloading, harness.status())
    }

    @Test
    fun episodes_waiting_for_deletion_after_watching_do_not_count_toward_keep_next_n() = runTest {
        val harness = harness(testRule(keepCount = 3).copy(deleteAfterWatching = true))
        harness.downloads.value = DownloadsUiState(
            listOf(
                testDownload(1, DownloadStatus.Completed, ruleKey = ruleKey),
                testDownload(2, DownloadStatus.Completed, ruleKey = ruleKey),
                testDownload(3, DownloadStatus.Completed, ruleKey = ruleKey),
            ),
        )
        assertTrue(harness.store.scheduleRemoval(1, AutoDownloadRemoval("tt1-1-1", ruleKey, dueAtEpochMs = 24 * hour)))
        assertTrue(harness.store.scheduleRemoval(1, AutoDownloadRemoval("tt1-1-2", ruleKey, dueAtEpochMs = 24 * hour)))

        harness.check()

        assertEquals(listOf(4, 5), harness.requests.last().episodes.map { it.episodeNumber })
        assertEquals(listOf(4, 5), harness.downloading())
        assertEquals(AutoDownloadStatus.Downloading, harness.status())
    }
}
