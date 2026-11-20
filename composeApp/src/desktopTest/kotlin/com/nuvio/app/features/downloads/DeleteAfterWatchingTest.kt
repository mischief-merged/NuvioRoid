package com.nuvio.app.features.downloads

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeleteAfterWatchingTest {
    private val hour = 3_600_000L
    private val ruleKey = testRule().key
    private val payloads = MemoryAutoDownloadPayloads()
    private var activeProfile = 1
    private var now = 1_000_000L
    private var items = listOf(
        testDownload(1, DownloadStatus.Completed, ruleKey = ruleKey),
        testDownload(2, DownloadStatus.Completed, ruleKey = ruleKey),
        testDownload(3, DownloadStatus.Completed, ruleKey = null),
    )
    private val notWatchedToTheEnd = mutableSetOf<String>()
    private var playing: String? = null
    private var laterEpisode: Boolean? = true
    private var removeResult = AutoDownloadRemovalResult.Removed
    private val removeCalls = mutableListOf<Pair<String, String?>>()
    private var beforeFinalStep: () -> Unit = {}

    private var store = AutoDownloadStore(payloads) { activeProfile }
    private var feature = feature()

    private fun feature() = DeleteAfterWatching(
        store = store,
        downloads = { items },
        isWatchedToTheEnd = { it.id !in notWatchedToTheEnd },
        isInPlayer = { it.id == playing },
        hasLaterEpisode = { _, _ -> laterEpisode },
        removeDownload = { downloadId, key, stillWanted ->
            beforeFinalStep()
            if (!stillWanted()) {
                AutoDownloadRemovalResult.Changed
            } else {
                removeCalls += downloadId to key
                removeResult.also { if (it == AutoDownloadRemovalResult.Removed) items = items.filterNot { item -> item.id == downloadId } }
            }
        },
        nowEpochMs = { now },
    )

    /** What a restart of the app leaves: the stored payloads and nothing else. */
    private fun restart() {
        store = AutoDownloadStore(payloads) { activeProfile }
        feature = feature()
    }

    private fun optIn(profileId: Int = 1, showId: String = "tt1") {
        store.upsert(profileId, testRule(showId = showId).copy(deleteAfterWatching = true))
    }

    private fun item(episode: Int) = items.first { it.episodeNumber == episode }

    private fun watch(episode: Int) = feature.onLocalPlaybackCompleted(activeProfile, item(episode))

    @Test
    fun scheduled_toasts_name_movies_without_episode_numbers_and_show_the_hold() = runBlocking {
        val movie = item(3).copy(contentType = "movie", parentMetaType = "movie", seasonNumber = null, episodeNumber = null)
        assertEquals("Show will move to the Trash in about 24 hours", scheduledDeletionMessage(movie, 24))
        assertEquals("Show will move to the Trash soon", scheduledDeletionMessage(movie, 0))
        assertEquals("Show S1 E3 will be deleted in about 24 hours", scheduledDeletionMessage(item(3), 24))
    }

    @Test
    fun a_stored_manual_removal_without_a_rule_field_is_valid() {
        payloads.saved[1] = """{"deleteAfterWatchingMode":"AllDownloads","removals":[{"downloadId":"tt1-1-3","dueAtEpochMs":42}]}"""
        restart()
        assertEquals(listOf(AutoDownloadRemoval("tt1-1-3", null, 42)), store.removals(1))
        assertTrue(payloads.unreadable.isEmpty())
    }

    @Test
    fun manual_downloads_require_all_downloads_and_keep_the_due_time_after_restart() {
        for (mode in listOf(DeleteAfterWatchingMode.Off, DeleteAfterWatchingMode.AutomaticOnly)) {
            store.setDeleteAfterWatchingMode(1, mode)
            assertFalse(watch(3))
            assertFalse(store.scheduleRemoval(1, AutoDownloadRemoval(item(3).id, null, now)))
        }
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        assertTrue(watch(3))
        val due = now + 24 * hour
        restart()
        assertEquals(DeleteAfterWatchingMode.AllDownloads, store.profileId().let { store.deleteAfterWatchingMode.value })
        assertEquals(AutoDownloadRemoval(item(3).id, null, due), store.removals(1).single())
        now = due - 1
        assertTrue(feature.runDue(1).isEmpty())
        now = due + hour
        laterEpisode = null
        assertEquals(AutoDownloadRemovalOutcome.Removed, feature.runDue(1).values.single())
        assertEquals(listOf<Pair<String, String?>>("tt1-1-3" to null), removeCalls)
        assertEquals(3, store.recentlyDeleted.value.single().episodeNumber)
    }

    @Test
    fun a_manual_movie_is_scheduled_removed_and_noted_without_an_episode_number() {
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        val movie = item(3).copy(contentType = "movie", parentMetaType = "movie", seasonNumber = null, episodeNumber = null)
        items = listOf(movie)
        laterEpisode = false
        assertTrue(feature.onLocalPlaybackCompleted(1, movie))
        now += 24 * hour
        assertEquals(AutoDownloadRemovalOutcome.Removed, feature.runDue(1).values.single())
        restart()
        store.profileId()
        assertEquals(listOf(AutoDownloadDeleted(movie.title, atEpochMs = now)), store.recentlyDeleted.value)
    }

    @Test
    fun changing_modes_drops_disallowed_schedules_immediately_and_does_not_restore_them() {
        optIn()
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        assertTrue(watch(1))
        assertTrue(watch(3))
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AutomaticOnly)
        assertEquals(listOf(item(1).id), store.removals(1).map { it.downloadId })
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        assertEquals(1, store.removals(1).size)
        watch(3)
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.Off)
        assertTrue(store.removals(1).isEmpty())
        assertFalse(watch(1))
        assertFalse(watch(3))
        restart()
        store.profileId()
        assertEquals(DeleteAfterWatchingMode.Off, store.deleteAfterWatchingMode.value)
        assertTrue(store.removals(1).isEmpty())
    }

    @Test
    fun all_downloads_keeps_the_rule_switch_and_later_episode_check() {
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        store.upsert(1, testRule())
        assertFalse(watch(1))
        optIn()
        assertTrue(watch(1))
        assertTrue(watch(3))
        laterEpisode = false
        now += 24 * hour
        assertEquals(mapOf(item(1).id to AutoDownloadRemovalOutcome.LastEpisode, item(3).id to AutoDownloadRemovalOutcome.Removed), feature.runDue(1))
    }

    @Test
    fun manual_keep_is_lasting_blocks_an_in_flight_removal_and_can_be_undone() {
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        val id = item(3).id
        store.keep(1, id)
        assertFalse(watch(3))
        restart()
        assertTrue(store.isKept(1, id))
        assertEquals(setOf(id), store.keptDownloadIds.value)
        store.undoKeep(1, id)
        restart()
        assertFalse(store.isKept(1, id))
        assertTrue(store.removals(1).isEmpty())
        assertTrue(watch(3))
        now += 24 * hour
        beforeFinalStep = { store.keep(1, id) }
        assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())
        assertTrue(removeCalls.isEmpty())
        store.undoKeep(1, id)
        beforeFinalStep = {}
        assertTrue(watch(3))
        now += 24 * hour
        assertEquals(AutoDownloadRemovalOutcome.Removed, feature.runDue(1).values.single())
    }

    @Test
    fun manual_downloads_still_wait_for_the_player_moves_and_disconnected_drives() {
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        watch(3)
        now += 24 * hour
        playing = item(3).id
        assertEquals(AutoDownloadRemovalOutcome.Playing, feature.runDue(1).values.single())
        playing = null
        for ((result, outcome) in listOf(
            AutoDownloadRemovalResult.Busy to AutoDownloadRemovalOutcome.Moving,
            AutoDownloadRemovalResult.NotReachable to AutoDownloadRemovalOutcome.NotReachable,
            AutoDownloadRemovalResult.NotMoved to AutoDownloadRemovalOutcome.NotMoved,
        )) {
            removeResult = result
            assertEquals(outcome, feature.runDue(1).values.single())
            assertEquals(1, store.removals(1).size)
        }
        notWatchedToTheEnd += item(3).id
        assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())
        assertTrue(items.any { it.episodeNumber == 3 })
    }

    @Test
    fun manual_placeholders_are_never_scheduled_or_removed() {
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        val original = item(3)
        items = listOf(original.copy(framesCheck = DownloadFramesCheck.NotTheRealVideo))
        assertFalse(watch(3))
        items = listOf(original)
        assertTrue(watch(3))
        items = listOf(original.copy(framesCheck = DownloadFramesCheck.NotTheRealVideo))
        now += 24 * hour
        assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())
        assertTrue(removeCalls.isEmpty())
    }

    @Test
    fun a_setting_change_at_the_last_step_stops_manual_and_rule_removal() {
        optIn()
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        watch(1)
        watch(3)
        now += 24 * hour
        beforeFinalStep = { store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.Off) }
        assertTrue(feature.runDue(1).values.all { it == AutoDownloadRemovalOutcome.Cancelled })
        assertTrue(removeCalls.isEmpty())
    }

    @Test
    fun progress_changed_at_the_last_step_stops_manual_removal() {
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        watch(3)
        now += 24 * hour
        beforeFinalStep = { notWatchedToTheEnd += item(3).id }
        assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())
        assertTrue(removeCalls.isEmpty())
    }

    @Test
    fun modes_and_keep_choices_belong_to_their_profile() {
        store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.AllDownloads)
        store.keep(1, item(3).id)
        activeProfile = 2
        store.profileId()
        assertEquals(DeleteAfterWatchingMode.AutomaticOnly, store.deleteAfterWatchingMode.value)
        assertTrue(store.keptDownloadIds.value.isEmpty())
        assertFalse(store.setDeleteAfterWatchingMode(1, DeleteAfterWatchingMode.Off))
        assertFalse(store.undoKeep(1, item(3).id))
        assertFalse(watch(3))
        activeProfile = 1
        store.profileId()
        assertEquals(DeleteAfterWatchingMode.AllDownloads, store.deleteAfterWatchingMode.value)
        assertTrue(store.isKept(1, item(3).id))
    }

    @Test
    fun old_payload_with_a_rule_removal_loads_with_automatic_only() {
        payloads.saved[1] = """{
            "rules":[{"parentMetaType":"series","parentMetaId":"tt1","title":"Show","deleteAfterWatching":true}],
            "storageLimitGb":25,
            "removals":[{"downloadId":"tt1-1-1","ruleKey":"$ruleKey","dueAtEpochMs":42}],
            "keptDownloadIds":["tt1-1-2"]
        }"""
        restart()
        assertEquals(listOf(AutoDownloadRemoval("tt1-1-1", ruleKey, 42)), store.removals(1))
        assertEquals(DeleteAfterWatchingMode.AutomaticOnly, store.deleteAfterWatchingMode.value)
        assertEquals(25, store.storageLimitGb.value)
        assertTrue(store.isKept(1, "tt1-1-2"))
        assertTrue(payloads.unreadable.isEmpty())
    }

    @Test
    fun nothing_is_scheduled_for_a_show_that_did_not_ask_for_it() {
        store.upsert(1, testRule())

        assertFalse(watch(1))

        assertEquals(emptyList(), store.removals(1))
    }

    @Test
    fun a_download_the_user_started_is_not_scheduled_by_default() {
        optIn()

        assertFalse(watch(3))
        now += 1_000 * hour
        feature.runDue(1)

        assertEquals(emptyList(), removeCalls)
        assertTrue(items.any { it.episodeNumber == 3 })
    }

    @Test
    fun a_download_that_is_not_finished_is_not_scheduled() {
        optIn()
        items = listOf(testDownload(1, DownloadStatus.Downloading, ruleKey = ruleKey))

        assertFalse(watch(1))
    }

    @Test
    fun a_watched_episode_is_removed_twenty_four_hours_later_and_not_before() {
        optIn()
        assertTrue(watch(1))
        assertEquals(now + 24 * hour, store.removals(1).single().dueAtEpochMs)

        now += 24 * hour - 1
        assertEquals(emptyMap(), feature.runDue(1))
        assertEquals(emptyList(), removeCalls)

        now += 1
        assertEquals(mapOf(item(1).id to AutoDownloadRemovalOutcome.Removed), feature.runDue(1))

        assertEquals(listOf<Pair<String, String?>>("tt1-1-1" to ruleKey), removeCalls)
        assertEquals(emptyList(), store.removals(1))
        assertTrue("1|1" in store.currentRules().single().ownedEpisodeKeys)
    }

    @Test
    fun the_time_is_kept_across_a_restart() {
        optIn()
        watch(1)
        val due = store.removals(1).single().dueAtEpochMs

        restart()
        assertEquals(due, store.removals(1).single().dueAtEpochMs)
        now = due - 1
        assertEquals(emptyMap(), feature.runDue(1))
        now = due
        assertEquals(AutoDownloadRemovalOutcome.Removed, feature.runDue(1).values.single())
    }

    @Test
    fun a_removal_that_became_due_while_the_computer_slept_is_carried_out_on_the_next_run() {
        optIn()
        watch(1)

        now += 30 * hour

        assertEquals(AutoDownloadRemovalOutcome.Removed, feature.runDue(1).values.single())
    }

    @Test
    fun watching_the_episode_again_sets_a_new_time() {
        optIn()
        watch(1)
        now += 20 * hour

        watch(1)

        assertEquals(now + 24 * hour, store.removals(1).single().dueAtEpochMs)
        now += 5 * hour
        assertEquals(emptyMap(), feature.runDue(1))
    }

    @Test
    fun the_last_episode_is_never_removed_until_a_later_one_is_released() {
        optIn()
        watch(2)
        now += 24 * hour

        laterEpisode = false
        assertEquals(AutoDownloadRemovalOutcome.LastEpisode, feature.runDue(1).values.single())
        laterEpisode = null
        assertEquals(AutoDownloadRemovalOutcome.LastEpisode, feature.runDue(1).values.single())
        assertEquals(emptyList(), removeCalls)
        assertEquals(1, store.removals(1).size)

        laterEpisode = true
        assertEquals(AutoDownloadRemovalOutcome.Removed, feature.runDue(1).values.single())
    }

    @Test
    fun an_episode_that_is_open_in_the_player_is_left_until_it_is_closed() {
        optIn()
        watch(1)
        now += 24 * hour
        playing = item(1).id

        assertEquals(AutoDownloadRemovalOutcome.Playing, feature.runDue(1).values.single())
        assertEquals(AutoDownloadRemovalOutcome.Playing, feature.deleteNow(1, item(1).id))
        assertEquals(emptyList(), removeCalls)

        playing = null
        assertEquals(AutoDownloadRemovalOutcome.Removed, feature.runDue(1).values.single())
    }

    @Test
    fun an_episode_that_is_no_longer_watched_to_the_end_is_dropped_from_the_schedule() {
        optIn()
        watch(1)
        now += 24 * hour
        notWatchedToTheEnd += item(1).id

        assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())

        assertEquals(emptyList(), removeCalls)
        assertEquals(emptyList(), store.removals(1))
    }

    @Test
    fun keep_drops_the_removal_and_no_new_one_is_scheduled_also_after_a_restart() {
        optIn()
        watch(1)

        feature.keep(1, item(1).id)

        assertEquals(emptyList(), store.removals(1))
        assertFalse(watch(1))
        restart()
        assertFalse(watch(1))
        now += 100 * hour
        assertEquals(emptyMap(), feature.runDue(1))
        assertEquals(emptyList(), removeCalls)
    }

    @Test
    fun turning_the_option_or_the_rule_off_cancels_what_was_scheduled() {
        optIn()
        watch(1)
        watch(2)
        store.update(1, ruleKey) { it.copy(deleteAfterWatching = false) }
        now += 24 * hour

        assertEquals(emptyList(), store.removals(1))
        assertEquals(emptyMap(), feature.runDue(1))
        assertEquals(emptyList(), removeCalls)

        optIn()
        watch(1)
        store.remove(1, ruleKey)
        assertEquals(emptyList(), store.removals(1))
    }

    @Test
    fun turning_the_option_off_and_on_again_before_the_time_leaves_nothing_scheduled() {
        optIn()
        watch(1)

        store.update(1, ruleKey) { it.copy(deleteAfterWatching = false) }
        assertEquals(emptyList(), store.removals(1))
        optIn()
        now += 24 * hour

        assertEquals(emptyMap(), feature.runDue(1))
        assertEquals(emptyList(), removeCalls)
    }

    @Test
    fun keep_pressed_while_the_removal_is_being_carried_out_still_keeps_the_episode() {
        optIn()
        watch(1)
        now += 24 * hour
        beforeFinalStep = { feature.keep(1, item(1).id) }

        assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())

        assertEquals(emptyList(), removeCalls)
        assertTrue(items.any { it.episodeNumber == 1 })
    }

    @Test
    fun an_episode_opened_in_the_player_while_the_removal_is_being_carried_out_is_not_removed() {
        optIn()
        watch(1)
        now += 24 * hour
        beforeFinalStep = { playing = item(1).id }

        feature.runDue(1)

        assertEquals(emptyList(), removeCalls)
        assertTrue(items.any { it.episodeNumber == 1 })
    }

    @Test
    fun a_profile_change_or_a_rewatch_while_the_removal_is_being_carried_out_stops_it() {
        optIn()
        watch(1)
        watch(2)
        now += 24 * hour
        beforeFinalStep = {
            if (activeProfile == 1) watch(2)
            activeProfile = 2
        }

        feature.runDue(1)

        assertEquals(emptyList(), removeCalls)
        assertEquals(2, items.count { it.autoDownloadRuleKey == ruleKey })
    }

    @Test
    fun a_download_that_became_the_users_own_is_not_removed() {
        optIn()
        watch(1)
        items = items.map { if (it.episodeNumber == 1) it.copy(autoDownloadRuleKey = null) else it }
        now += 24 * hour

        assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())
        assertEquals(emptyList(), removeCalls)
    }

    @Test
    fun a_removed_episode_is_noted_so_the_user_can_see_it_happened_also_after_a_restart() {
        optIn()
        watch(2)
        now += 24 * hour

        feature.runDue(1)

        val expected = AutoDownloadDeleted("Show", seasonNumber = 1, episodeNumber = 2, atEpochMs = now)
        assertEquals(listOf(expected), store.recentlyDeleted.value)
        restart()
        assertEquals(listOf(expected), store.currentRules().let { store.recentlyDeleted.value })
    }

    @Test
    fun an_episode_deleted_early_at_the_users_request_is_noted_too() {
        optIn()
        watch(1)

        feature.deleteNow(1, item(1).id)

        assertEquals(listOf(1), store.recentlyDeleted.value.map { it.episodeNumber })
    }

    @Test
    fun nothing_is_noted_when_nothing_was_removed() {
        optIn()
        watch(1)
        now += 24 * hour
        removeResult = AutoDownloadRemovalResult.NotMoved

        feature.runDue(1)

        assertEquals(emptyList(), store.recentlyDeleted.value)
    }

    @Test
    fun the_hours_left_are_rounded_to_the_nearest_and_never_negative() {
        assertEquals(24, removalHoursLeft(dueAtEpochMs = 24 * hour, nowEpochMs = 0))
        assertEquals(24, removalHoursLeft(dueAtEpochMs = 24 * hour - 10 * 60_000, nowEpochMs = 0))
        assertEquals(1, removalHoursLeft(dueAtEpochMs = 40 * 60_000, nowEpochMs = 0))
        assertEquals(0, removalHoursLeft(dueAtEpochMs = 20 * 60_000, nowEpochMs = 0))
        assertEquals(0, removalHoursLeft(dueAtEpochMs = 0, nowEpochMs = 5 * hour))
    }

    @Test
    fun only_the_last_week_of_removed_episodes_is_shown_newest_first() {
        val day = 24 * hour
        val entries = listOf(
            AutoDownloadDeleted("Old", 1, 1, atEpochMs = 0),
            AutoDownloadDeleted("Recent", 1, 2, atEpochMs = 9 * day),
            AutoDownloadDeleted("Newest", 1, 3, atEpochMs = 10 * day - hour),
        )

        val shown = recentlyDeletedShown(entries, nowEpochMs = 10 * day)

        assertEquals(listOf("Newest", "Recent"), shown.map { it.title })
        assertEquals(0, daysSinceDeleted(shown[0], 10 * day))
        assertEquals(1, daysSinceDeleted(shown[1], 10 * day))
    }

    @Test
    fun a_file_that_cannot_be_moved_stays_scheduled() {
        optIn()
        watch(1)
        now += 24 * hour
        removeResult = AutoDownloadRemovalResult.NotMoved

        assertEquals(AutoDownloadRemovalOutcome.NotMoved, feature.runDue(1).values.single())

        assertEquals(1, store.removals(1).size)
        assertTrue(items.any { it.episodeNumber == 1 })
    }

    @Test
    fun a_download_that_changed_before_the_final_step_is_dropped_from_the_schedule() {
        optIn()
        watch(1)
        now += 24 * hour
        removeResult = AutoDownloadRemovalResult.Changed

        assertEquals(AutoDownloadRemovalOutcome.Cancelled, feature.runDue(1).values.single())
        assertEquals(emptyList(), store.removals(1))
    }

    @Test
    fun delete_now_does_not_wait_and_also_removes_the_last_episode() {
        optIn()
        watch(2)
        laterEpisode = false
        notWatchedToTheEnd += item(2).id

        assertEquals(AutoDownloadRemovalOutcome.Removed, feature.deleteNow(1, "tt1-1-2"))

        assertEquals(listOf<Pair<String, String?>>("tt1-1-2" to ruleKey), removeCalls)
    }

    @Test
    fun delete_now_does_nothing_for_a_download_without_a_scheduled_removal() {
        optIn()

        assertNull(feature.deleteNow(1, item(1).id))
        assertNull(feature.deleteNow(1, item(3).id))
        assertEquals(emptyList(), removeCalls)
    }

    @Test
    fun the_schedule_of_one_profile_is_not_seen_run_or_saved_under_another() {
        optIn(profileId = 1)
        watch(1)
        val savedForFirstProfile = payloads.saved[1]
        activeProfile = 2
        now += 100 * hour

        assertEquals(emptyList(), store.removals(2))
        assertEquals(emptyList(), store.removals(1))
        assertEquals(emptyMap(), feature.runDue(2))
        assertEquals(emptyMap(), feature.runDue(1))
        assertNull(feature.deleteNow(1, item(1).id))
        feature.keep(1, item(1).id)
        feature.keep(2, item(2).id)
        optIn(profileId = 2)
        assertTrue(watch(1))

        assertEquals(emptyList(), removeCalls)
        assertEquals(savedForFirstProfile, payloads.saved[1])
        activeProfile = 1
        assertEquals(1, store.removals(1).size)
        assertFalse(store.isKept(1, item(1).id))
        assertFalse(store.isKept(1, item(2).id))
        assertEquals(AutoDownloadRemovalOutcome.Removed, feature.runDue(1).values.single())
    }

    @Test
    fun a_stored_schedule_that_cannot_be_read_is_kept_aside() {
        payloads.saved[1] = "{\"removals\": 7"
        restart()

        assertEquals(emptyList(), store.removals(1))

        assertEquals("{\"removals\": 7", payloads.unreadable[1])
        assertEquals("{\"removals\": 7", payloads.saved[1])
    }

    @Test
    fun an_episode_after_this_one_counts_as_later_and_a_special_does_not() {
        val second = testDownload(2, DownloadStatus.Completed)

        assertEquals(true, hasLaterReleasedEpisode(second, listOf(testEpisode(1), testEpisode(2), testEpisode(3))))
        assertEquals(true, hasLaterReleasedEpisode(second, listOf(testEpisode(1, season = 2))))
        assertEquals(false, hasLaterReleasedEpisode(second, listOf(testEpisode(1), testEpisode(2), testEpisode(9, season = 0))))
        assertNull(hasLaterReleasedEpisode(second.copy(seasonNumber = null, episodeNumber = null), listOf(testEpisode(3))))
    }

    @Test
    fun a_file_marked_as_not_the_real_video_is_never_removed_after_watching() {
        optIn()
        assertTrue(watch(1))
        // The file was scheduled, and a later look marked it.
        items = items.map { if (it.episodeNumber == 1) it.copy(framesCheck = DownloadFramesCheck.NotTheRealVideo) else it }
        now += 1_000 * hour

        feature.runDue(1)

        assertEquals(emptyList(), removeCalls)
        assertTrue(items.any { it.episodeNumber == 1 })
    }
}
