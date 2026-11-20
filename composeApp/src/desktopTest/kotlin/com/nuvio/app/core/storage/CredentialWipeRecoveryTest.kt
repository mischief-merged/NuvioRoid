package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.test.*

class CredentialWipeRecoveryTest {
    @Test fun failedWipeKeepsAPendingMarkerAndRetriesEveryNamespaceIncludingLegacy() {
        val root = Files.createTempDirectory("wipe-recovery")
        try {
            Files.writeString(root.resolve("nuvio_account_sessions.properties"), "saved-reference")
            val downloads = Files.createDirectories(root.resolve("downloads"))
            Files.writeString(downloads.resolve("movie.part"), "partial")
            var legacyCleared = false
            val attempts = mutableListOf<String>()
            assertFailsWith<IllegalStateException> {
                DesktopStorage.wipeCredentialsAndFiles(root, { legacyCleared = true }, {
                    attempts.add(it)
                    throw IllegalStateException("locked")
                })
            }
            assertTrue(legacyCleared)
            assertEquals(DesktopCredentials.storeNames, attempts.toSet())
            assertTrue(Files.exists(root.resolve("credential-wipe-pending")))
            assertTrue(Files.exists(root.resolve("nuvio_account_sessions.properties")))
            attempts.clear()
            DesktopStorage.wipeCredentialsAndFiles(root, { legacyCleared = true }, { attempts.add(it) })
            assertEquals(DesktopCredentials.storeNames, attempts.toSet())
            assertFalse(Files.exists(root.resolve("credential-wipe-pending")))
            assertFalse(Files.exists(root.resolve("nuvio_account_sessions.properties")))
            assertEquals("partial", Files.readString(downloads.resolve("movie.part")))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun failedFileDeletionCannotFinishTheWipe() {
        val root = Files.createTempDirectory("wipe-readonly")
        val permissions = Files.getPosixFilePermissions(root)
        try {
            Files.writeString(root.resolve("credential-wipe-pending"), "pending")
            Files.writeString(root.resolve("nuvio_account_sessions.properties"), "old-session")
            Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
            assertFailsWith<IllegalStateException> {
                DesktopStorage.wipeCredentialsAndFiles(root, {}, {})
            }
            assertTrue(Files.exists(root.resolve("credential-wipe-pending")))
            Files.setPosixFilePermissions(root, permissions)
            DesktopStorage.wipeCredentialsAndFiles(root, {}, {})
            assertFalse(Files.exists(root.resolve("credential-wipe-pending")))
            assertFalse(Files.exists(root.resolve("nuvio_account_sessions.properties")))
        } finally {
            Files.setPosixFilePermissions(root, permissions)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun coldPendingWipeFinishesBeforeAnyCredentialReaderStarts() {
        var pending = true
        val order = mutableListOf<String>()
        prepareAccountWipe(
            { order.add("stop") },
            { pending = false; order.add("wipe") },
            { check(!pending); order.add("load") },
        )
        assertEquals(listOf("stop", "wipe", "load"), order)
    }

    @Test fun blockedWipeDoesNotInitializeCredentialReaders() {
        var started = false
        assertFailsWith<IllegalStateException> {
            prepareAccountWipe({}, { error("locked") }, { started = true })
        }
        assertFalse(started)
    }

}
