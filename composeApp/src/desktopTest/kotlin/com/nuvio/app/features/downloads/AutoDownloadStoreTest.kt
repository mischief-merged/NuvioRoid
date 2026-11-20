package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoDownloadStoreTest {
    private val payloads = MemoryAutoDownloadPayloads()
    private var activeProfile = 1
    private val store = AutoDownloadStore(payloads) { activeProfile }

    @Test
    fun recording_owned_episodes_does_not_cancel_a_removal() {
        val rule = testRule().copy(deleteAfterWatching = true)
        store.upsert(1, rule)
        val stillWanted = WatchedRemovalGuard.check { true }

        assertTrue(store.update(1, rule.key) { it.copy(ownedEpisodeKeys = it.ownedEpisodeKeys + "1|2") })

        assertTrue(stillWanted())
        assertEquals(setOf("1|2"), store.rule(1, rule.key)?.ownedEpisodeKeys)
    }

    @Test
    fun a_saved_rule_is_read_back_by_a_new_store() {
        store.upsert(1, testRule(keepCount = 5, scope = AutoDownloadScope.LatestReleased))

        val reloaded = AutoDownloadStore(payloads) { 1 }

        assertEquals(listOf(testRule(keepCount = 5, scope = AutoDownloadScope.LatestReleased)), reloaded.currentRules())
        assertEquals(reloaded.currentRules(), reloaded.rules.value)
    }

    @Test
    fun each_profile_sees_and_saves_only_its_own_rules() {
        store.upsert(1, testRule(showId = "tt1"))
        activeProfile = 2

        assertEquals(emptyList(), store.currentRules())
        store.upsert(2, testRule(showId = "tt2"))
        activeProfile = 1

        assertEquals(listOf("tt1"), store.currentRules().map { it.parentMetaId })
        assertEquals(listOf("tt2"), AutoDownloadStore(payloads) { 2 }.currentRules().map { it.parentMetaId })
    }

    @Test
    fun a_change_made_for_a_profile_that_is_no_longer_active_is_dropped() {
        store.upsert(1, testRule(showId = "tt1"))
        activeProfile = 2
        val before = payloads.saved.toMap()

        assertFalse(store.upsert(1, testRule(showId = "tt9")))
        assertFalse(store.update(1, testRule(showId = "tt1").key) { it.copy(keepCount = 10) })
        assertFalse(store.remove(1, testRule(showId = "tt1").key))

        assertEquals(before, payloads.saved)
        assertEquals(emptyList(), store.currentRules())
        assertNull(store.rule(1, testRule(showId = "tt1").key))
    }

    @Test
    fun a_payload_that_cannot_be_read_is_kept_aside_and_is_not_replaced_by_an_empty_one() {
        payloads.saved[1] = "{not json"

        assertEquals(emptyList(), store.currentRules())

        assertEquals("{not json", payloads.saved[1])
        assertEquals("{not json", payloads.unreadable[1])

        store.upsert(1, testRule())

        assertEquals("{not json", payloads.unreadable[1])
        assertEquals(listOf(testRule()), AutoDownloadStore(payloads) { 1 }.currentRules())
    }

    @Test
    fun every_field_of_a_rule_is_written_also_when_it_has_its_default_value() {
        store.upsert(1, AutoDownloadRule(parentMetaType = "series", parentMetaId = "tt1", title = "Show"))

        val payload = payloads.saved.getValue(1)

        listOf("\"keepCount\":3", "\"scope\":\"NextUnwatched\"", "\"ownedEpisodeKeys\":[]", "\"logo\":null").forEach {
            assertTrue(it in payload, "$it is missing from $payload")
        }
    }

    @Test
    fun a_rule_stored_without_its_optional_fields_is_read_with_the_defaults_and_written_back_in_full() {
        payloads.saved[1] = "{\"rules\":[{\"parentMetaType\":\"series\",\"parentMetaId\":\"tt1\",\"title\":\"Show\"}]}"

        assertEquals(testRule(), store.currentRules().single())
        store.update(1, testRule().key) { it.copy(keepCount = 7) }

        assertTrue("\"keepCount\":7" in payloads.saved.getValue(1))
        assertTrue("\"scope\":\"NextUnwatched\"" in payloads.saved.getValue(1))
    }

    @Test
    fun a_rule_stored_before_owned_episodes_existed_loads_and_keeps_what_the_user_deletes_afterwards() {
        payloads.saved[1] = "{\"rules\":[{\"parentMetaType\":\"series\",\"parentMetaId\":\"tt1\",\"title\":\"Show\"}]}"

        assertEquals(emptySet(), store.currentRules().single().ownedEpisodeKeys)
        store.update(1, testRule().key) { it.copy(ownedEpisodeKeys = it.ownedEpisodeKeys + "1|2") }

        assertEquals(setOf("1|2"), AutoDownloadStore(payloads) { 1 }.currentRules().single().ownedEpisodeKeys)
    }

    private fun reopened(profileId: Int = 1) = AutoDownloadStore(payloads) { profileId }.also { it.currentRules() }

    @Test
    fun removed_episodes_are_kept_per_profile_and_entries_older_than_a_week_are_dropped_when_one_is_added() {
        val day = 24 * 3_600_000L
        store.upsert(1, testRule())
        store.recordDeleted(1, AutoDownloadDeleted("Show", 1, 1, atEpochMs = 0))
        store.recordDeleted(1, AutoDownloadDeleted("Show", 1, 2, atEpochMs = 3 * day))
        store.recordDeleted(1, AutoDownloadDeleted("Show", 1, 3, atEpochMs = 9 * day))

        assertEquals(listOf(2, 3), store.recentlyDeleted.value.map { it.episodeNumber })
        assertEquals(listOf(2, 3), reopened().recentlyDeleted.value.map { it.episodeNumber })
        assertEquals(false, store.recordDeleted(2, AutoDownloadDeleted("Show", 1, 4, atEpochMs = 9 * day)))
        assertEquals(emptyList(), reopened(2).recentlyDeleted.value)
    }

    @Test
    fun a_payload_stored_before_removed_episodes_were_noted_loads_with_none_and_keeps_its_other_settings() {
        payloads.saved[1] = "{\"rules\":[{\"parentMetaType\":\"series\",\"parentMetaId\":\"tt1\",\"title\":\"Show\"}],\"storageLimitGb\":25}"

        assertEquals(testRule(), store.currentRules().single())
        assertEquals(emptyList(), store.recentlyDeleted.value)
        assertEquals(25, store.storageLimitGb.value)
        assertEquals(emptyMap(), payloads.unreadable)
    }

    @Test
    fun at_most_twenty_removed_episodes_are_kept() {
        store.upsert(1, testRule())
        (1..30).forEach { store.recordDeleted(1, AutoDownloadDeleted("Show", 1, it, atEpochMs = it.toLong())) }

        assertEquals((11..30).toList(), store.recentlyDeleted.value.map { it.episodeNumber })
    }

    @Test
    fun whether_episodes_are_deleted_after_watching_is_written_for_every_rule_and_is_off_unless_chosen() {
        store.upsert(1, testRule(showId = "tt1"))
        store.upsert(1, testRule(showId = "tt2").copy(deleteAfterWatching = true))

        val payload = payloads.saved.getValue(1)

        assertTrue("\"deleteAfterWatching\":false" in payload, payload)
        assertTrue("\"deleteAfterWatching\":true" in payload, payload)
        assertEquals(listOf(false, true), AutoDownloadStore(payloads) { 1 }.currentRules().map { it.deleteAfterWatching })
    }

    @Test
    fun a_rule_stored_before_the_option_existed_does_not_delete_after_watching() {
        payloads.saved[1] = "{\"rules\":[{\"parentMetaType\":\"series\",\"parentMetaId\":\"tt1\",\"title\":\"Show\",\"keepCount\":10}]}"

        assertEquals(false, store.currentRules().single().deleteAfterWatching)
        assertEquals(10, store.currentRules().single().keepCount)
    }

    @Test
    fun seven_episodes_is_a_keep_count_that_is_offered() {
        assertEquals(listOf(1, 2, 3, 5, 7, 10), AutoDownloadRule.keepCountOptions)
        store.upsert(1, testRule(keepCount = 7))

        assertEquals(7, store.currentRules().single().keepCount)
    }

    @Test
    fun saving_a_rule_again_keeps_the_episodes_it_already_downloaded() {
        store.upsert(1, testRule(keepCount = 3, owned = setOf("1|1")))

        store.upsert(1, testRule(keepCount = 10))

        assertEquals(testRule(keepCount = 10, owned = setOf("1|1")), store.currentRules().single())
    }

    @Test
    fun a_keep_count_that_is_not_offered_falls_back_to_the_default() {
        assertTrue(store.upsert(1, testRule(keepCount = 400)))

        assertEquals(AutoDownloadRule.DefaultKeepCount, store.currentRules().single().keepCount)
    }

    @Test
    fun the_storage_limit_is_fifty_gigabytes_until_it_is_changed() {
        assertEquals(50, store.storageLimitGb.value)
        assertEquals(50_000_000_000L, store.storageLimitBytes())
    }

    @Test
    fun a_chosen_storage_limit_and_no_limit_are_read_back_by_a_new_store() {
        store.upsert(1, testRule())
        store.setStorageLimit(1, 250)
        assertEquals(250_000_000_000L, AutoDownloadStore(payloads) { 1 }.storageLimitBytes())

        store.setStorageLimit(1, null)

        val reloaded = AutoDownloadStore(payloads) { 1 }
        assertNull(reloaded.storageLimitBytes())
        assertNull(reloaded.storageLimitGb.value)
        assertEquals(listOf(testRule()), reloaded.currentRules())
    }

    @Test
    fun the_storage_limit_is_kept_per_profile_and_not_changed_for_a_profile_that_is_not_active() {
        store.setStorageLimit(1, 10)
        activeProfile = 2

        assertEquals(50_000_000_000L, store.storageLimitBytes())
        assertFalse(store.setStorageLimit(1, 100))
        activeProfile = 1

        assertEquals(10_000_000_000L, store.storageLimitBytes())
        assertEquals(10, store.storageLimitGb.value)
    }

    @Test
    fun a_storage_limit_that_is_not_offered_falls_back_to_the_default() {
        store.setStorageLimit(1, 7)

        assertEquals(50, store.storageLimitGb.value)
    }

    @Test
    fun a_change_that_changes_nothing_is_not_written() {
        store.upsert(1, testRule())
        payloads.saved[1] = "sentinel"

        store.update(1, testRule().key) { it }

        assertEquals("sentinel", payloads.saved[1])
    }
}
