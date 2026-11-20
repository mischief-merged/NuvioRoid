package com.nuvio.app.core.network

import com.nuvio.app.core.storage.CredentialVault
import com.nuvio.app.core.storage.DesktopStorage
import com.russhwolf.settings.PropertiesSettings
import java.util.Properties
import java.nio.file.Files
import kotlin.test.*

class DesktopSessionSettingsTest {
    @Test fun legacySessionMovesOnlyAfterConfirmationAndSdkKeyMigrationKeepsIt() {
        val directory = Files.createTempDirectory("session-migration")
        try {
            var locked = true
            val saved = mutableMapOf<String, String>()
            val vault = object : CredentialVault {
                override fun read(account: String): String? { check(!locked); return saved[account] }
                override fun write(account: String, value: String) { check(!locked); saved[account] = value }
                override fun remove(account: String) { saved.remove(account) }
            }
            val legacy = PropertiesSettings(Properties().apply { setProperty("session", "old-session") })
            val store = DesktopStorage.Store(directory.resolve("session.properties"), vault, { true }, {})
            val settings = DesktopSessionSettings(legacy, store) {}
            assertEquals("old-session", settings.getStringOrNull("session"))
            assertEquals("old-session", legacy.getStringOrNull("session"))
            assertFalse(Files.exists(directory.resolve("session.properties")))
            locked = false
            assertEquals("old-session", settings.getStringOrNull("session"))
            assertNull(legacy.getStringOrNull("session"))
            settings.putString("sb-example-session", settings.getStringOrNull("session")!!)
            settings.remove("session")
            assertEquals("old-session", settings.getStringOrNull("sb-example-session"))
            settings.putString("sb-example-session", "new-session")
            assertEquals("new-session", settings.getStringOrNull("sb-example-session"))
            assertFalse(Files.readString(directory.resolve("session.properties")).contains("new-session"))
            settings.remove("sb-example-session")
            assertNull(settings.getStringOrNull("sb-example-session"))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun interruptedImportReturnsLegacySessionAndLeavesItSaved() {
        val directory = Files.createTempDirectory("session-interrupted")
        try {
            val values = mutableMapOf<String, String>()
            val vault = object : CredentialVault {
                override fun read(account: String): String? = values[account]
                override fun write(account: String, value: String) { values[account] = value }
                override fun remove(account: String) { values.remove(account) }
            }
            val legacy = PropertiesSettings(Properties().apply { setProperty("session", "kept-session") })
            val store = DesktopStorage.Store(directory.resolve("session.properties"), vault, { true }, {}) { _, _ ->
                throw java.io.IOException("interrupted")
            }
            val settings = DesktopSessionSettings(legacy, store) {}
            assertEquals("kept-session", settings.getStringOrNull("session"))
            assertEquals("kept-session", legacy.getStringOrNull("session"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun legacyFlushFailureStillRemovesTheCurrentSession() {
        val directory = Files.createTempDirectory("session-remove")
        try {
            val legacy = PropertiesSettings(Properties())
            val store = DesktopStorage.Store(directory.resolve("session.properties"))
            store.putString("session", "saved-session")
            val settings = DesktopSessionSettings(legacy, store) { throw java.util.prefs.BackingStoreException("unavailable") }
            runCatching { settings.remove("session") }
            assertNull(store.getFreshString("session"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun wipeClearsEveryLegacyEndpointButKeepsUnrelatedPreferences() {
        val legacy = PropertiesSettings(Properties())
        val keys = listOf("session", "supabase_code_verifier", "sb-old-server-session", "sb-new-server-session", "sb-old-server-supabase_code_verifier")
        keys.forEach { legacy.putString(it, "legacy-private-value") }
        legacy.putString("unrelated", "kept")
        var flushed = false
        clearLegacyAccountSessions(legacy) { flushed = true }
        keys.forEach { assertNull(legacy.getStringOrNull(it)) }
        assertEquals("kept", legacy.getStringOrNull("unrelated"))
        assertTrue(flushed)
    }

}
