package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatroskaInfoTest {
    private fun info(bytes: ByteArray) = matroskaInfo(bytes.size.toLong()) { offset, length ->
        if (offset < 0L || offset >= bytes.size) null
        else bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
    }

    @Test fun duration_and_title_need_only_nine_small_reads_even_with_a_large_media_body() {
        val header = matroskaEpisode()
        var calls = 0
        var requested = 0
        val result = assertNotNull(matroskaInfo(30_000_000_000L) { offset, length ->
            calls++; requested += length
            assertTrue(offset < header.size, "media bytes must not be read")
            header.copyOfRange(offset.toInt(), minOf(header.size.toLong(), offset + length).toInt())
        })
        assertEquals(2_700_000L, result.durationMs)
        assertEquals("Show S01E01", result.title)
        assertEquals(9, calls)
        assertEquals(99, requested)
    }

    @Test fun a_nondefault_time_scale_is_applied() {
        assertEquals(2_700_000L, info(matroskaEpisode(scale = 2_000_000L, duration = 1_350_000.0))?.durationMs)
    }

    @Test fun nonfinite_and_negative_lengths_prove_nothing() {
        for (duration in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertNull(info(matroskaEpisode(duration = duration))?.durationMs)
        }
        assertNull(info(matroskaEpisode(scale = -1L))?.durationMs)
    }

    @Test fun an_info_field_that_runs_past_the_file_is_not_used() {
        val bytes = matroskaEpisode()
        assertNull(info(bytes.copyOf(bytes.size - 10)))
    }

    @Test fun info_is_not_sought_inside_media() {
        val bytes = matroskaEpisode()
        // Put an unknown-length cluster where the segment info would have been.
        byteArrayOf(0x1f, 0x43, 0xb6.toByte(), 0x75, 0xff.toByte()).copyInto(bytes, 10)
        assertNull(info(bytes))
    }
}
