package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Signing out or a lost session drops the account session and never deletes the user's local data files. */
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

    @Test
    fun a_lost_session_or_sign_out_leaves_library_downloads_watch_progress_rules_profiles_and_player_settings() {
        val kept = listOf(
            "nuvio_library.properties",
            "nuvio_downloads.properties",
            "nuvio_watch_progress.properties",
            "nuvio_auto_downloads.properties",
            "nuvio_profile_cache.properties",
            "nuvio_player_settings.properties",
        )
        kept.forEach { Files.writeString(root.resolve(it), "keep=true") }
        val video = root.resolve("downloads/Film.mkv").also { it.parent.createDirectories(); Files.writeString(it, "the film") }
        Files.writeString(root.resolve("nuvio_account_sessions.properties"), "session=@keychain:v1:dead")

        DesktopStorage.wipeCredentialsAndFiles(root, {}, { name ->
            if (name == "nuvio_account_sessions") Files.deleteIfExists(root.resolve("$name.properties"))
        })

        kept.forEach { name ->
            assertTrue(root.resolve(name).exists(), name)
            assertEquals("keep=true", Files.readString(root.resolve(name)))
        }
        assertEquals("the film", Files.readString(video))
    }
}
