package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoDownloadPlannerTest {
    private val released = (1..8).map { testEpisode(it) }

    private fun plan(
        rule: AutoDownloadRule,
        watched: Set<Int> = emptySet(),
        downloads: List<DownloadItem> = emptyList(),
        episodes: List<SeasonBatchEpisode> = released,
    ) = planAutoDownload(rule, episodes, { it.episodeNumber in watched }, downloads)

    private fun List<SeasonBatchEpisode>.numbers() = map { it.episodeNumber }

    @Test
    fun next_unwatched_takes_the_episodes_after_the_last_watched_one() {
        val plan = plan(testRule(keepCount = 3), watched = setOf(1, 2, 4))

        assertEquals(listOf(5, 6, 7), plan.window.numbers())
        assertEquals(listOf(5, 6, 7), plan.toDownload.numbers())
    }

    @Test
    fun next_unwatched_starts_at_the_first_episode_when_nothing_is_watched() {
        assertEquals(listOf(1, 2), plan(testRule(keepCount = 2)).window.numbers())
    }

    @Test
    fun latest_released_takes_the_newest_episodes_without_the_watched_ones() {
        val plan = plan(testRule(keepCount = 3, scope = AutoDownloadScope.LatestReleased), watched = setOf(7))

        assertEquals(listOf(6, 8), plan.window.numbers())
    }

    @Test
    fun latest_released_on_a_show_watched_to_the_end_wants_nothing() {
        val plan = plan(testRule(keepCount = 3, scope = AutoDownloadScope.LatestReleased), watched = (1..8).toSet())

        assertEquals(emptyList(), plan.window)
        assertEquals(emptyList(), plan.toDownload)
    }

    @Test
    fun specials_are_never_wanted() {
        val episodes = listOf(testEpisode(1, season = 0), testEpisode(2, season = 0)) + released

        assertEquals(listOf(1 to 1, 1 to 2), plan(testRule(keepCount = 2), episodes = episodes).window.map { it.seasonNumber to it.episodeNumber })
    }

    @Test
    fun episodes_are_ordered_across_seasons() {
        val episodes = listOf(testEpisode(1, season = 2), testEpisode(2, season = 1), testEpisode(1, season = 1))

        val plan = planAutoDownload(testRule(keepCount = 3), episodes, { it.seasonNumber == 1 && it.episodeNumber == 1 }, emptyList())

        assertEquals(listOf(1 to 2, 2 to 1), plan.window.map { it.seasonNumber to it.episodeNumber })
    }

    @Test
    fun an_episode_that_is_downloaded_downloading_or_paused_is_kept_but_not_queued_again() {
        val downloads = listOf(
            testDownload(1, DownloadStatus.Completed),
            testDownload(2, DownloadStatus.Downloading),
            testDownload(3, DownloadStatus.Paused),
        )

        val plan = plan(testRule(keepCount = 5), downloads = downloads)

        assertEquals(listOf(1, 2, 3, 4, 5), plan.window.numbers())
        assertEquals(listOf(4, 5), plan.toDownload.numbers())
    }

    @Test
    fun an_episode_whose_finished_file_is_only_a_placeholder_clip_is_queued_again() {
        val clip = testDownload(1, DownloadStatus.Completed).copy(downloadedBytes = 421_667)
        val small = testDownload(2, DownloadStatus.Completed).copy(downloadedBytes = 16_875_000)

        val plan = plan(testRule(keepCount = 2), downloads = listOf(clip, small))

        assertEquals(listOf(1, 2), plan.window.numbers())
        assertEquals(listOf(1), plan.toDownload.numbers())
    }

    @Test
    fun a_placeholder_clip_is_queued_again_also_when_a_full_size_was_announced_for_it() {
        val clip = testDownload(1, DownloadStatus.Completed).copy(downloadedBytes = 421_667, totalBytes = 900_000_000)

        val plan = plan(testRule(keepCount = 1), downloads = listOf(clip))

        assertEquals(listOf(1), plan.toDownload.numbers())
    }

    @Test
    fun an_episode_whose_download_failed_is_queued_again() {
        val plan = plan(testRule(keepCount = 2), downloads = listOf(testDownload(1, DownloadStatus.Failed)))

        assertEquals(listOf(1, 2), plan.toDownload.numbers())
    }

    @Test
    fun an_episode_the_rule_downloaded_that_is_gone_from_downloads_is_skipped() {
        val rule = testRule(keepCount = 3, owned = setOf("1|1", "1|2"))

        val plan = plan(rule, downloads = listOf(testDownload(1, DownloadStatus.Completed)))

        assertEquals(listOf(1, 3, 4), plan.window.numbers())
        assertEquals(listOf(3, 4), plan.toDownload.numbers())
    }

    @Test
    fun an_episode_that_was_never_seen_as_a_download_of_the_rule_is_still_wanted() {
        val plan = plan(testRule(keepCount = 2, owned = emptySet()))

        assertEquals(listOf(1, 2), plan.toDownload.numbers())
    }

    @Test
    fun downloads_of_another_show_do_not_count() {
        val plan = plan(testRule(keepCount = 1), downloads = listOf(testDownload(1, DownloadStatus.Completed, showId = "tt2")))

        assertEquals(listOf(1), plan.toDownload.numbers())
    }

    @Test
    fun the_wait_before_another_attempt_grows_to_a_day() {
        val hour = 3_600_000L

        assertEquals(listOf(hour, 3 * hour, 12 * hour, 24 * hour, 24 * hour), (1..5).map(::autoDownloadRetryDelayMs))
    }

    @Test
    fun next_unwatched_does_not_count_episodes_waiting_for_deletion_toward_keep_count() {
        val downloads = listOf(
            testDownload(1, DownloadStatus.Completed),
            testDownload(2, DownloadStatus.Completed),
            testDownload(3, DownloadStatus.Completed),
        )

        val plan = planAutoDownload(
            rule = testRule(keepCount = 3),
            released = released,
            isWatched = { false },
            downloads = downloads,
            pendingRemovalDownloadIds = setOf("tt1-1-1", "tt1-1-2"),
        )

        assertEquals(listOf(3, 4, 5), plan.window.numbers())
        assertEquals(listOf(4, 5), plan.toDownload.numbers())
    }

    @Test
    fun latest_released_treats_episodes_waiting_for_deletion_as_watched() {
        val downloads = listOf(
            testDownload(7, DownloadStatus.Completed),
            testDownload(8, DownloadStatus.Completed),
        )

        val plan = planAutoDownload(
            rule = testRule(keepCount = 3, scope = AutoDownloadScope.LatestReleased),
            released = released,
            isWatched = { false },
            downloads = downloads,
            pendingRemovalDownloadIds = setOf("tt1-1-7"),
        )

        assertEquals(listOf(6, 8), plan.window.numbers())
        assertEquals(listOf(6), plan.toDownload.numbers())
    }
}
