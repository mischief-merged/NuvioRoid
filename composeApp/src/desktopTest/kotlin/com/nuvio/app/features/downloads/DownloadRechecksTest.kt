package com.nuvio.app.features.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadRechecksTest {
    private var now = 1_000_000L
    private val looked = CopyOnWriteArrayList<String>()
    private val pauses = CopyOnWriteArrayList<Long>()
    private val inFlight = AtomicInteger()
    private val peak = AtomicInteger()
    private var playing = emptySet<String>()
    private var unavailable = emptySet<String>()
    private var outcome: (String) -> RecheckOutcome = { RecheckOutcome.LooksRight }

    private fun item(id: String, verifiedBy: DownloadVerification? = null, subtitle: String? = null, expected: Long? = null,
                     status: DownloadStatus = DownloadStatus.Completed, frames: DownloadFramesCheck = DownloadFramesCheck.NotChecked) =
        DownloadItem(
            id = id, contentType = "movie", parentMetaId = "tt$id", parentMetaType = "movie", videoId = "tt$id", title = "T$id",
            streamTitle = "RD+", streamSubtitle = subtitle, providerName = "A", sourceUrl = "https://example.com/$id.mkv",
            fileName = "$id.mkv", status = status, createdAtEpochMs = 1, updatedAtEpochMs = 1,
            verifiedBy = verifiedBy, expectedSizeBytes = expected, framesCheck = frames, localFileUri = "file:/d/$id.mkv",
        )

    private fun checks(all: List<DownloadItem>) = DownloadRechecks(
        items = { all },
        resolveLocalFileUri = { if (it.id in unavailable) null else it.localFileUri },
        isPlaying = { it.id in playing },
        recheck = { id ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now, ::maxOf)
            Thread.sleep(20)
            looked += id
            inFlight.decrementAndGet()
            outcome(id)
        },
        nowEpochMs = { now },
        pause = { pauses += it },
        io = Dispatchers.Default,
    )

    private val many = (1..20).map { item("m$it") }

    @Test
    fun activity_is_visible_during_a_pass_and_cleared_on_completion_or_cancellation() = runBlocking {
        val checks = checks(listOf(item("one")))
        assertFalse(checks.isRunning.value)
        outcome = {
            assertTrue(checks.isRunning.value)
            RecheckOutcome.LooksRight
        }
        checks.pass(force = true)
        assertFalse(checks.isRunning.value)
        outcome = {
            assertTrue(checks.isRunning.value)
            throw kotlinx.coroutines.CancellationException()
        }
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { checks.pass(force = true) }
        assertFalse(checks.isRunning.value)
    }

    @Test
    fun a_pass_takes_only_a_few_downloads_and_two_at_a_time_with_a_pause_between_groups() = runBlocking {
        val summary = checks(many).pass()

        assertEquals(DownloadRechecks.MaxPerPass, looked.size)
        assertEquals(DownloadRechecks.MaxPerPass, summary.looked)
        assertTrue(summary.moreWaiting)
        assertTrue(peak.get() <= DownloadCompanions.BackfillConcurrency, "peak ${peak.get()}")
        assertEquals(List(DownloadRechecks.MaxPerPass / DownloadCompanions.BackfillConcurrency - 1) { DownloadCompanions.BackfillPauseMs }, pauses.toList())
    }

    @Test
    fun the_limits_are_the_ones_of_the_companion_pass_and_a_few() {
        assertTrue(DownloadRechecks.MaxPerPass in 1..10)
        assertEquals(2, DownloadCompanions.BackfillConcurrency)
    }

    @Test
    fun only_downloads_that_were_never_checked_or_have_an_unchecked_claim_are_due() = runBlocking {
        val all = listOf(
            item("old"),
            item("checked", verifiedBy = DownloadVerification.Size),
            item("claim", verifiedBy = DownloadVerification.Size, subtitle = "[💾 9.53 GB] Film.mkv"),
            item("claimDone", verifiedBy = DownloadVerification.Size, subtitle = "[💾 9.53 GB] Film.mkv", expected = 9_530_000_000L),
            item("marked", frames = DownloadFramesCheck.NotTheRealVideo),
            item("active", status = DownloadStatus.Downloading),
        )

        checks(all).pass()

        assertEquals(setOf("old", "claim"), looked.toSet())
    }

    @Test
    fun a_download_that_was_looked_at_is_not_looked_at_again_for_the_retry_wait() = runBlocking {
        val checks = checks(listOf(item("a")))
        checks.pass()
        checks.pass()
        assertEquals(listOf("a"), looked.toList())

        now += DownloadCompanions.BackfillRetryAfterMs
        checks.pass()
        assertEquals(listOf("a", "a"), looked.toList())
    }

    @Test
    fun a_file_that_is_not_there_is_skipped_and_costs_nothing() = runBlocking {
        unavailable = setOf("a")

        val summary = checks(listOf(item("a"), item("b"))).pass()

        assertEquals(listOf("b"), looked.toList())
        assertEquals(1, summary.unavailable)
    }

    @Test
    fun a_file_that_is_playing_is_never_looked_at_and_is_left_for_a_later_pass() = runBlocking {
        playing = setOf("a")
        val checks = checks(listOf(item("a"), item("b")))

        val first = checks.pass()
        assertEquals(listOf("b"), looked.toList())
        assertEquals(1, first.playing)

        playing = emptySet()
        checks.pass()
        assertEquals(listOf("b", "a"), looked.toList())
    }

    @Test
    fun the_results_are_counted_and_a_flag_is_reported() = runBlocking {
        outcome = { id -> if (id == "a") RecheckOutcome.Flagged else RecheckOutcome.CouldNotCheck }

        val summary = checks(listOf(item("a"), item("b"))).pass()

        assertEquals(RecheckSummary(looked = 2, flagged = 1, couldNotCheck = 1), summary)
    }

    @Test
    fun checking_again_looks_at_the_download_whatever_was_recorded() = runBlocking {
        val checked = item("a", verifiedBy = DownloadVerification.Size, frames = DownloadFramesCheck.Passed)

        val summary = checks(listOf(checked)).checkAgain(checked)

        assertEquals(listOf("a"), looked.toList())
        assertEquals(1, summary.looked)
    }

    @Test
    fun checking_all_looks_at_every_finished_download_including_marked_ones_and_none_else() = runBlocking {
        val all = many.take(9) + item("marked", frames = DownloadFramesCheck.NotTheRealVideo) +
            item("active", status = DownloadStatus.Paused)

        val summary = checks(all).checkAll()

        assertEquals(10, looked.size)
        assertFalse("active" in looked)
        assertTrue("marked" in looked)
        assertEquals(10, summary.looked)
    }

    @Test
    fun opening_the_screen_looks_at_the_due_downloads_on_it_without_waiting_between_groups() = runBlocking {
        val summary = checks(many).checkOnScreen(many)

        assertEquals(DownloadRechecks.ScreenMaxItems, summary.looked)
        assertEquals(emptyList(), pauses.toList())
    }

    @Test
    fun a_look_that_throws_is_counted_as_not_looked_and_does_not_stop_the_pass() = runBlocking {
        outcome = { id -> if (id == "a") error("disk error") else RecheckOutcome.LooksRight }

        val summary = checks(listOf(item("a"), item("b"))).pass()

        assertEquals(2, summary.looked)
        assertEquals(1, summary.couldNotCheck)
    }
}
