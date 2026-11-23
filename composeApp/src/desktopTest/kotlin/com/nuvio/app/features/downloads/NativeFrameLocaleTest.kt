package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeFrameLocaleTest {
    private fun withCommaLocale(block: (NativeLibrary, Pointer) -> Unit) {
        assumeTrue(DesktopHostOs.current == DesktopHostOs.MACOS)
        NativePlayerBridge.ensureLoaded()
        val library = NativeLibrary.getInstance("c")
        // These category numbers and masks are the ones on this platform.
        val locale = assertNotNull(library.getFunction("newlocale").invokePointer(arrayOf<Any?>(1 shl 4, "fr_FR.UTF-8", null)))
        val previous = assertNotNull(library.getFunction("uselocale").invokePointer(arrayOf<Any?>(locale)))
        try {
            assertEquals(",", library.getFunction("localeconv").invokePointer(emptyArray<Any?>()).getPointer(0).getString(0))
            block(library, locale)
        } finally {
            library.getFunction("uselocale").invokePointer(arrayOf<Any?>(previous))
            library.getFunction("freelocale").invokeVoid(arrayOf<Any?>(locale))
        }
    }

    private fun sample(file: File) = NativePlayerBridge.sampleFrames(
        path = file.absolutePath, fractions = doubleArrayOf(0.5), durationSeconds = 0.0,
        retryEarlierSeconds = 0.0, positionTimeoutMs = 10_000L, budgetMs = 20_000L,
    )

    @Test fun a_probe_works_before_playback_with_a_comma_process_locale() = withCommaLocale { library, locale ->
        val set = library.getFunction("setlocale")
        val before = set.invokeString(arrayOf<Any?>(4, null), false)
        try {
            assertNotNull(set.invokeString(arrayOf<Any?>(4, "fr_FR.UTF-8"), false))
            val fixture = listOf(File("testFixtures/subtitles/video.mp4"), File("../testFixtures/subtitles/video.mp4"))
                .first { it.isFile }
            val result = assertNotNull(sample(fixture))
            assertEquals(FramePixelCount, assertNotNull(result[1]).size)
            assertEquals("C", set.invokeString(arrayOf<Any?>(4, null), false))
            assertEquals(locale, library.getFunction("uselocale").invokePointer(arrayOf<Any?>(null)))
        } finally { set.invokeString(arrayOf<Any?>(4, before), false) }
    }

    @Test fun a_real_probe_restores_the_calling_threads_locale_and_sets_the_process_numeric_locale() = withCommaLocale { library, locale ->
        val fixture = listOf(File("testFixtures/subtitles/video.mp4"), File("../testFixtures/subtitles/video.mp4"))
            .firstOrNull { it.isFile }
        assertNotNull(fixture, "the existing video fixture is missing")
        val result = assertNotNull(sample(fixture))
        assertEquals(2, result.size)
        val header = assertNotNull(result[0]).decodeToString()
        assertTrue(Regex("^[0-9]+\\.[0-9]+\\n").containsMatchIn(header), header)
        assertEquals(FramePixelCount, assertNotNull(result[1]).size)
        assertEquals(locale, library.getFunction("uselocale").invokePointer(arrayOf<Any?>(null)))
        assertEquals(",", library.getFunction("localeconv").invokePointer(emptyArray<Any?>()).getPointer(0).getString(0))
        assertEquals("C", library.getFunction("setlocale").invokeString(arrayOf<Any?>(4, null), false))
    }

    @Test fun a_probe_that_cannot_open_a_file_also_restores_the_locale() = withCommaLocale { library, locale ->
        assertEquals(null, sample(File("/a-folder-that-does-not-exist/video.mkv")))
        assertEquals(locale, library.getFunction("uselocale").invokePointer(arrayOf<Any?>(null)))
        assertEquals(",", library.getFunction("localeconv").invokePointer(emptyArray<Any?>()).getPointer(0).getString(0))
        assertEquals("C", library.getFunction("setlocale").invokeString(arrayOf<Any?>(4, null), false))
    }
}
