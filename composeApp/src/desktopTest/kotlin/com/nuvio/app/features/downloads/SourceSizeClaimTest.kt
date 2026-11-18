package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceSizeClaimTest {
    private val gb = 1_000_000_000L
    private val mb = 1_000_000L
    private val gib = 1024L * 1024L * 1024L

    private fun claim(text: String?) = parseClaimedSizeBytes(text)

    // Positives

    @Test
    fun the_disk_symbol_form_of_the_real_source_is_read() {
        assertEquals(9_530_000_000L, claim("[💾 9.53 GB] Some.Title.2026.1080p.WEB-DL.mkv"))
    }

    @Test
    fun the_usual_spellings_are_read() {
        assertEquals(9_530_000_000L, claim("Some.Title.2026.1080p 9.53GB"))
        assertEquals((9.5 * gib).toLong(), claim("Size: 9.5 GiB"))
        assertEquals(1_400_000_000L, claim("1.4 GB"))
        assertEquals(700 * mb, claim("700 MB"))
        assertEquals(9_530_000_000L, claim("[9.53 GB]"))
        assertEquals(9_530_000_000L, claim("💾9.53GB"))
        assertEquals(9_530_000_000L, claim("💾️ 9.53 GB"))
        assertEquals(2 * 1024L * 1024L, claim("2 MiB"))
    }

    @Test
    fun a_decimal_comma_is_read_like_a_point() {
        assertEquals(9_530_000_000L, claim("[💾 9,53 GB]"))
        assertEquals(1_400_000_000L, claim("Size: 1,4 GB"))
    }

    @Test
    fun units_are_read_in_any_case() {
        assertEquals(1_400_000_000L, claim("1.4 gb"))
        assertEquals(700 * mb, claim("700 mb"))
    }

    // Things that are not the size of the file

    @Test
    fun bitrates_are_not_sizes() {
        assertNull(claim("1080p 10 Mbps"))
        assertNull(claim("1080p 10 Mb/s"))
        assertNull(claim("Bitrate 25 MB/s"))
        assertNull(claim("8 Mbit"))
        assertNull(claim("2.5 GB/s"))
    }

    @Test
    fun resolutions_channel_layouts_and_counts_are_not_sizes() {
        assertNull(claim("Title 2026 1080p x265 DDP5.1 Atmos"))
        assertNull(claim("Title 2160p HDR 5.1 7.1"))
        assertNull(claim("👤 150 seeders"))
        assertNull(claim("Title.2026.1080p.WEB-DL.DDP5.1.H264-GROUP.mkv"))
    }

    @Test
    fun a_unit_that_starts_a_longer_word_is_not_a_size() {
        assertNull(claim("12 GBR"))
        assertNull(claim("5 MBA programme"))
    }

    @Test
    fun a_number_with_no_unit_is_not_a_size_and_an_absurd_size_is_ignored() {
        assertNull(claim("9.53"))
        assertNull(claim("0 GB"))
        assertNull(claim("0.0001 MB"))
        assertNull(claim("9000 TB"))
        assertNull(claim(""))
        assertNull(claim(null))
    }

    // Several sizes

    @Test
    fun the_size_marked_with_a_disk_symbol_wins() {
        assertEquals(9_530_000_000L, claim("Extras 700 MB [💾 9.53 GB] sample 50 MB"))
    }

    @Test
    fun the_size_marked_with_the_word_size_wins() {
        assertEquals(9_530_000_000L, claim("Pack 120 GB, Size: 9.53 GB"))
        assertEquals(9_530_000_000L, claim("Pack 120 GB, size 9.53 GB"))
    }

    @Test
    fun several_sizes_with_no_mark_claim_nothing() {
        assertNull(claim("9.53 GB or 4.2 GB"))
        assertNull(claim("1.4 GB then 700 MB"))
    }

    @Test
    fun two_marked_sizes_that_differ_claim_nothing() {
        assertNull(claim("💾 9.53 GB 💾 4.2 GB"))
    }

    @Test
    fun the_same_size_written_twice_is_one_claim() {
        assertEquals(9_530_000_000L, claim("[💾 9.53 GB] Title (9.53 GB)"))
    }

    // From a source's title and description

    @Test
    fun a_pack_states_the_size_of_the_pack_not_of_the_file() {
        assertNull(claimedSizeFromSourceText("Show S01 Complete 1080p", "[💾 41.2 GB] Show.S01.1080p"))
        assertNull(claimedSizeFromSourceText("Show", "[💾 41.2 GB] Show.Season.1-3.1080p"))
        assertNull(claimedSizeFromSourceText("Film Collection", "[💾 41.2 GB]"))
        assertNull(claimedSizeFromSourceText("Show", "[💾 41.2 GB] Show.S01-S03.1080p"))
    }

    @Test
    fun an_episode_with_its_own_marker_is_not_a_pack() {
        assertEquals(
            1_200_000_000L,
            claimedSizeFromSourceText("Show", "[💾 1.2 GB] Show.S01E03.1080p.mkv"),
        )
    }

    @Test
    fun the_title_and_the_description_are_read_together() {
        assertEquals(9_530_000_000L, claimedSizeFromSourceText("RD+ 1080p", "[💾 9.53 GB] Title.mkv"))
        assertNull(claimedSizeFromSourceText(null as String?, null as String?))
        assertNull(claimedSizeFromSourceText("RD+ 1080p", null))
    }

    @Test
    fun the_claim_of_a_download_is_the_stated_size_or_else_the_text() {
        fun item(expected: Long?, subtitle: String?) = DownloadItem(
            id = "a", contentType = "movie", parentMetaId = "tt1", parentMetaType = "movie", videoId = "tt1",
            title = "Film", streamTitle = "RD+ 1080p", streamSubtitle = subtitle, providerName = "A",
            sourceUrl = "https://example.com/a.mkv", fileName = "a.mkv", status = DownloadStatus.Completed,
            createdAtEpochMs = 1, updatedAtEpochMs = 1, expectedSizeBytes = expected,
        )
        assertEquals(5 * gb, item(5 * gb, "[💾 9.53 GB]").claimedSizeBytes())
        assertEquals(9_530_000_000L, item(null, "[💾 9.53 GB]").claimedSizeBytes())
        assertNull(item(null, "no size here").claimedSizeBytes())
        assertTrue(item(null, null).claimedSizeBytes() == null)
    }

    // Sentences

    @Test
    fun sizes_are_written_for_a_sentence_the_way_the_list_and_the_source_write_them() {
        assertEquals("8.5 MB", formatListedSize(8_509_628L))
        assertEquals("9.5 GB", formatClaimedSize(9_530_000_000L))
        assertEquals("8.5 MB", formatDecimalSize(8_509_628L))
    }

    @Test
    fun season_wording_and_ranges_describe_a_pack_whatever_the_dash() {
        assertNull(claimedSizeFromSourceText("Show", "Show Season 1 \u2014 30 GB"))
        assertNull(claimedSizeFromSourceText("Show Season 1", "30 GB"))
        assertNull(claimedSizeFromSourceText("Show", "[\uD83D\uDCBE 30 GB] Show.S01E01-E10.1080p"))
        assertNull(claimedSizeFromSourceText("Show", "[\uD83D\uDCBE 30 GB] 10 files"))
        assertNull(claimedSizeFromSourceText("Show", "[\uD83D\uDCBE 30 GB] 3 of 10 files"))
        assertEquals(
            1_200_000_000L,
            claimedSizeFromSourceText("Show", "[\uD83D\uDCBE 1.2 GB] Show Season 1 Episode 3"),
        )
    }
}
