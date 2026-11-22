package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A lost session must leave the user's files where they are.
 * Sign-out must copy them into a dated folder before the live copies are cleared.
 */
class NeverWipeLocalDataTest {
    @Test
    fun a_lost_session_leaves_the_library_and_the_lists_in_place() {
        val root = folderWithLocalData()
        try {
            DesktopAccountFiles.onLostSession(root)
            localData.forEach { (name, body) ->
                val file = root.resolve(name)
                assertTrue(Files.exists(file), "$name was deleted")
                assertEquals(body, Files.readString(file), name)
            }
            assertEquals("the film", Files.readString(root.resolve("downloads/Film.mkv")))
            assertEquals("nested", Files.readString(root.resolve("cache/note.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sign_out_keeps_a_dated_copy_and_then_clears_the_live_files() {
        val root = folderWithLocalData()
        val now = LocalDateTime.of(2026, 11, 22, 15, 4, 5)
        try {
            val older = root.resolve("signed-out-20200101-000000")
            Files.createDirectories(older)
            Files.writeString(older.resolve("nuvio_library.properties"), "older-library")

            val copied = DesktopAccountFiles.signOut(root, now).getOrThrow()

            assertEquals("signed-out-20261122-150405", copied.fileName.toString())
            localData.forEach { (name, body) ->
                assertEquals(body, Files.readString(copied.resolve(name)), name)
                assertFalse(Files.exists(root.resolve(name)), "$name was still in the live folder")
            }
            assertEquals("nested", Files.readString(copied.resolve("cache/note.txt")))
            assertFalse(Files.exists(root.resolve("cache/note.txt")))
            assertEquals("the film", Files.readString(root.resolve("downloads/Film.mkv")))
            assertFalse(Files.exists(copied.resolve("downloads")))
            assertEquals("older-library", Files.readString(older.resolve("nuvio_library.properties")))
            assertFalse(Files.exists(copied.resolve("signed-out-20200101-000000")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_second_sign_out_in_the_same_second_keeps_the_first_copy() {
        val root = folderWithLocalData()
        val now = LocalDateTime.of(2026, 11, 22, 15, 4, 5)
        try {
            val first = DesktopAccountFiles.signOut(root, now).getOrThrow()
            Files.writeString(root.resolve("nuvio_library.properties"), "second")
            val second = DesktopAccountFiles.signOut(root, now).getOrThrow()

            assertEquals("signed-out-20261122-150405", first.fileName.toString())
            assertEquals("signed-out-20261122-150405-2", second.fileName.toString())
            assertEquals("library=kept", Files.readString(first.resolve("nuvio_library.properties")))
            assertEquals("second", Files.readString(second.resolve("nuvio_library.properties")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_failed_copy_clears_nothing() {
        val root = folderWithLocalData()
        val now = LocalDateTime.of(2026, 11, 22, 15, 4, 5)
        try {
            val result = DesktopAccountFiles.signOut(root, now, transfer = { _, _ ->
                throw java.io.IOException("no room")
            })

            assertTrue(result.isFailure)
            localData.forEach { (name, body) ->
                assertEquals(body, Files.readString(root.resolve(name)), name)
            }
            assertEquals("the film", Files.readString(root.resolve("downloads/Film.mkv")))
            assertEquals("nested", Files.readString(root.resolve("cache/note.txt")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private val localData = listOf(
    "nuvio_library.properties" to "library=kept",
    "nuvio_downloads.properties" to "downloads=list",
    "nuvio_watch_progress.properties" to "progress=12",
    "nuvio_auto_downloads.properties" to "rules=on",
    "nuvio_profiles.properties" to "profiles=2",
    "nuvio_player_settings.properties" to "player=settings",
    "nuvio_addons.properties" to "addons=list",
)

private fun folderWithLocalData(): Path {
    val root = Files.createTempDirectory("nuvio-never-wipe")
    localData.forEach { (name, body) -> Files.writeString(root.resolve(name), body) }
    val video = root.resolve("downloads/Film.mkv")
    video.parent.createDirectories()
    Files.writeString(video, "the film")
    val nested = root.resolve("cache/note.txt")
    nested.parent.createDirectories()
    Files.writeString(nested, "nested")
    return root
}
