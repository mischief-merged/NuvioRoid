package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Signing out or deleting the account wipes the app's data, never the user's downloaded videos or a sign-out copy. */
class AppDataWipeKeepsDownloadsTest {
    private val root = Files.createTempDirectory("nuvio-wipe").toRealPath()

    @AfterTest
    fun clean_up() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun a_wipe_removes_settings_and_lists_but_keeps_the_downloads_folder_and_the_move_journals() {
        val video = root.resolve("downloads/Film.mkv").also { it.parent.createDirectories(); Files.writeString(it, "the film") }
        val subtitles = root.resolve("downloads/Film.mkv.subtitles/0.srt").also { it.parent.createDirectories(); Files.writeString(it, "en") }
        val journal = root.resolve("moves/move-ab-00000001.journal").also { it.parent.createDirectories(); Files.writeString(it, "{}") }
        val settings = root.resolve("nuvio_downloads.properties").also { Files.writeString(it, "x=1") }
        val cache = root.resolve("cache/thing").also { it.parent.createDirectories(); Files.writeString(it, "c") }

        DesktopStorage.wipeFolder(root, DesktopStorage.KeptOnWipe)

        assertEquals("the film", Files.readString(video))
        assertTrue(subtitles.exists() && journal.exists())
        assertFalse(settings.exists())
        assertFalse(cache.exists())
    }
}
