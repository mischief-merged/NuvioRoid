package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sizes on the Downloads screen use decimal gigabytes, the same count as the storage limit. */
class DownloadSizeFormatTest {
    @Test
    fun a_storage_limit_is_shown_as_that_many_decimal_gigabytes() {
        listOf(10, 25, 50, 100, 250).forEach { limitGb ->
            val shown = formatBytes(limitGb * BytesPerGigabyte)
            assertEquals("$limitGb.0 GB", shown)
            assertEquals(shown, formatListedSize(limitGb * BytesPerGigabyte))
            assertTrue(shown.startsWith(limitGb.toString()), shown)
        }
    }

    @Test
    fun a_gibibyte_is_not_called_one_gigabyte() {
        assertEquals("1.1 GB", formatBytes(1024L * 1024 * 1024))
        assertEquals("1.0 GB", formatBytes(BytesPerGigabyte))
    }

    @Test
    fun smaller_sizes_use_decimal_units_too() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.0 KB", formatBytes(1_000))
        assertEquals("1.0 MB", formatBytes(1_000_000))
        assertEquals("8.5 MB", formatBytes(8_509_628))
    }
}
