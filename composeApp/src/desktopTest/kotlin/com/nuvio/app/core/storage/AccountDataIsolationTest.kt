package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountDataIsolationTest {
    @Test
    fun the_first_account_adopts_data_that_has_no_owner() {
        val root = Files.createTempDirectory("account-adopt")
        try {
            Files.writeString(root.resolve("nuvio_library.properties"), "library=kept")
            val result = prepareDesktopAccount(root, "account-a", "20261121-120000")
            assertEquals(AccountDataPrep.Adopted, result)
            assertEquals("account-a", Files.readString(root.resolve("account-owner")).trim())
            assertEquals("library=kept", Files.readString(root.resolve("nuvio_library.properties")))
            assertFalse(Files.list(root).use { entries -> entries.anyMatch { it.fileName.toString().startsWith("previous-account-") } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun the_same_account_reuses_its_data() {
        val root = Files.createTempDirectory("account-reuse")
        try {
            Files.writeString(root.resolve("account-owner"), "account-a")
            Files.writeString(root.resolve("nuvio_library.properties"), "library=kept")
            val result = prepareDesktopAccount(root, "account-a", "20261121-120000")
            assertEquals(AccountDataPrep.Reused, result)
            assertEquals("library=kept", Files.readString(root.resolve("nuvio_library.properties")))
            assertEquals("account-a", Files.readString(root.resolve("account-owner")).trim())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_different_account_moves_local_data_and_keeps_the_session_downloads_and_locks() {
        val root = Files.createTempDirectory("account-move")
        val outside = Files.createTempDirectory("account-link-target")
        try {
            Files.writeString(root.resolve("account-owner"), "account-a")
            Files.writeString(root.resolve("nuvio_library.properties"), "library=old")
            Files.writeString(root.resolve("nuvio_watch_progress.properties"), "progress=old")
            Files.writeString(root.resolve("nuvio_account_sessions.properties"), "session=current")
            Files.writeString(root.resolve("nuvio_account_sessions.properties.bak"), "session=backup")
            Files.writeString(root.resolve("store.properties.lock"), "lock")
            val video = root.resolve("downloads/Film.mkv")
            Files.createDirectories(video.parent)
            Files.writeString(video, "the film")
            Files.createDirectories(root.resolve("moves"))
            Files.writeString(root.resolve("moves/move-1.journal"), "{}")
            Files.writeString(outside.resolve("kept.txt"), "outside")
            Files.createSymbolicLink(root.resolve("linked-notes"), outside)
            var separated = false
            val result = prepareDesktopAccount(root, "account-b", "20261121-120000") { separated = true }
            assertTrue(separated)
            assertEquals(AccountDataPrep.Moved, result)
            assertEquals("account-b", Files.readString(root.resolve("account-owner")).trim())
            val archived = root.resolve("previous-account-20261121-120000")
            assertEquals("library=old", Files.readString(archived.resolve("nuvio_library.properties")))
            assertEquals("progress=old", Files.readString(archived.resolve("nuvio_watch_progress.properties")))
            assertTrue(Files.isSymbolicLink(archived.resolve("linked-notes")))
            assertEquals("outside", Files.readString(outside.resolve("kept.txt")))
            assertEquals("session=current", Files.readString(root.resolve("nuvio_account_sessions.properties")))
            assertEquals("session=backup", Files.readString(root.resolve("nuvio_account_sessions.properties.bak")))
            assertEquals("lock", Files.readString(root.resolve("store.properties.lock")))
            assertEquals("the film", Files.readString(video))
            assertEquals("{}", Files.readString(root.resolve("moves/move-1.journal")))
            assertFalse(Files.exists(root.resolve("nuvio_library.properties")))
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_second_move_in_the_same_stamp_uses_another_folder() {
        val root = Files.createTempDirectory("account-stamp")
        try {
            Files.createDirectories(root.resolve("previous-account-20261121-120000"))
            Files.writeString(root.resolve("account-owner"), "account-a")
            Files.writeString(root.resolve("nuvio_library.properties"), "library=old")
            val result = prepareDesktopAccount(root, "account-b", "20261121-120000")
            assertEquals(AccountDataPrep.Moved, result)
            assertEquals("library=old", Files.readString(root.resolve("previous-account-20261121-120000-2/nuvio_library.properties")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_failed_move_does_not_change_the_owner() {
        val root = Files.createTempDirectory("account-fail")
        val permissions = Files.getPosixFilePermissions(root)
        try {
            Files.writeString(root.resolve("account-owner"), "account-a")
            Files.writeString(root.resolve("nuvio_library.properties"), "library=old")
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-x------"))
            var separated = false
            assertFailsWith<java.io.IOException> {
                prepareDesktopAccount(root, "account-b", "20261121-120000") { separated = true }
            }
            assertTrue(separated)
            Files.setPosixFilePermissions(root, permissions)
            assertEquals("account-a", Files.readString(root.resolve("account-owner")).trim())
            assertEquals("library=old", Files.readString(root.resolve("nuvio_library.properties")))
        } finally {
            Files.setPosixFilePermissions(root, permissions)
            root.toFile().deleteRecursively()
        }
    }
}
