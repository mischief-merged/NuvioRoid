package com.nuvio.app.core.storage

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopTestStorageIsolationTest {
    @Test
    fun the_shipped_app_folder_ignores_a_storage_switch() {
        val sentinel = "/tmp/nuvio-storage-sentinel-${System.nanoTime()}"
        val previous = System.getProperty("nuvio.storageDir")
        System.setProperty("nuvio.storageDir", sentinel)
        try {
            val resolved = DesktopStorage.resolveAppDataDir()
            assertFalse(resolved.toString().contains("nuvio-storage-sentinel"))
            assertEquals(
                Paths.get(System.getProperty("user.home")).resolve("Library/Application Support/Nuvio"),
                resolved,
            )
        } finally {
            restore("nuvio.storageDir", previous)
        }
    }

    @Test
    fun tests_use_their_own_storage_folder() {
        val root = DesktopStorage.rootDir
        assertTrue(root.toString().contains("desktop-test-home"), root.toString())
        assertFalse(root.toString().contains("Application Support${java.io.File.separator}Nuvio"), root.toString())
        val previous = System.getProperty("nuvio.storageDir")
        System.setProperty("nuvio.storageDir", "/tmp/nuvio-should-not-apply-${System.nanoTime()}")
        try {
            assertEquals(root, DesktopStorage.rootDir)
        } finally {
            restore("nuvio.storageDir", previous)
        }
    }

    @Test
    fun tests_keep_logins_in_memory() {
        assertTrue(DesktopCredentials.vault is MemoryCredentialVault)
    }

    @Test
    fun a_mac_without_a_supplied_store_uses_the_keychain() {
        val previous = System.getProperty("nuvio.credentialVault")
        System.setProperty("nuvio.credentialVault", "memory")
        try {
            assertTrue(credentialVaultForPlatform(null, "Mac OS X") is MacKeychainVault)
            assertNull(credentialVaultForPlatform(null, "Linux"))
            assertTrue(credentialVaultForPlatform(MemoryCredentialVault(), "Mac OS X") is MemoryCredentialVault)
        } finally {
            restore("nuvio.credentialVault", previous)
        }
    }

    private fun restore(name: String, previous: String?) {
        if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
    }
}
