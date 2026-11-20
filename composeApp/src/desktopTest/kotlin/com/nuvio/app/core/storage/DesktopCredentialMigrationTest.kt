package com.nuvio.app.core.storage

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

class DesktopCredentialMigrationTest {
    private class Vault : CredentialVault {
        val values = mutableMapOf<String, String>()
        var writes = 0
        var failAfter = Int.MAX_VALUE
        var locked = false
        var mismatch = false
        override fun read(account: String): String? {
            check(!locked)
            return if (mismatch) "{}" else values[account]
        }
        override fun write(account: String, value: String) {
            check(!locked && writes++ < failAfter)
            values[account] = value
        }
        override fun remove(account: String) { check(!locked); values.remove(account) }
    }
    private fun fixture(test: (Path, Vault) -> Unit) {
        val directory = Files.createTempDirectory("credential-migration")
        try { test(directory.resolve("settings.properties"), Vault()) }
        finally { directory.toFile().deleteRecursively() }
    }
    private fun store(file: Path, vault: Vault, warning: () -> Unit = {}) = DesktopStorage.Store(
        file, vault = vault, protectedKey = { it.startsWith("secret") }, warning = warning,
    )
    private fun seed(file: Path) {
        DesktopStorage.Store(file).apply {
            putString("secret_1", "first-token")
            putString("secret_2", "second-token")
            putString("theme", "dark")
        }
    }
    private fun assertClean(file: Path) {
        listOf(file, file.resolveSibling("${file.fileName}.bak"), file.resolveSibling("${file.fileName}.tmp"))
            .filter(Files::exists).forEach { source ->
                val text = Files.readString(source)
                assertFalse(text.contains("first-token"))
                assertFalse(text.contains("second-token"))
            }
    }

    @Test fun migrationPreservesAllValuesAndCleansBackupAndInterruptedWrite() = fixture { file, vault ->
        seed(file)
        Files.copy(file, file.resolveSibling("${file.fileName}.tmp"))
        val secured = store(file, vault)
        assertEquals("first-token", secured.getString("secret_1"))
        assertEquals("second-token", secured.getString("secret_2"))
        assertEquals("dark", secured.getString("theme"))
        assertClean(file)
        assertEquals("first-token", store(file, vault).getString("secret_1"))
        assertEquals("second-token", secured.getPreviousString("secret_2"))
        val count = vault.writes
        secured.freshEntries()
        assertEquals(count, vault.writes)
    }

    @Test fun failureMidwayLeavesPlaintextUntilAllWritesAreConfirmedAndCanRetry() = fixture { file, vault ->
        seed(file)
        vault.failAfter = 1
        var warnings = 0
        assertEquals("first-token", store(file, vault) { warnings++ }.getString("secret_1"))
        assertTrue(warnings > 0)
        assertTrue(Files.readString(file).contains("first-token"))
        vault.failAfter = Int.MAX_VALUE
        assertEquals("second-token", store(file, vault).getString("secret_2"))
        assertClean(file)
    }

    @Test fun readBackMismatchRejectsWriteAndKeepsOriginalFiles() = fixture { file, vault ->
        seed(file)
        val original = Files.readAllBytes(file)
        vault.mismatch = true
        val secured = store(file, vault)
        assertEquals("first-token", secured.getString("secret_1"))
        assertFailsWith<IllegalStateException> { secured.putString("secret_1", "refreshed-token") }
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals("first-token", secured.getString("secret_1"))
    }

    @Test fun lockedWriteNeverCreatesAPlaintextCopyAndRetriesAfterUnlock() = fixture { file, vault ->
        val secured = store(file, vault)
        secured.putString("secret_1", "original-token")
        val original = Files.readAllBytes(file)
        vault.locked = true
        assertFailsWith<IllegalStateException> { secured.putString("secret_1", "replacement-token") }
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals("original-token", secured.getString("secret_1"))
        vault.locked = false
        secured.putString("secret_1", "replacement-token")
        assertEquals("replacement-token", store(file, vault).getString("secret_1"))
        Files.list(file.parent).use { paths -> paths.filter(Files::isRegularFile).forEach {
            assertFalse(Files.readString(it).contains("replacement-token"))
        } }
    }

    @Test fun failedClearIsReportedAndRetainsTheNamespaceForRetry() = fixture { file, vault ->
        val secured = store(file, vault)
        secured.putString("secret_1", "original-token")
        vault.locked = true
        assertFailsWith<IllegalStateException> { secured.clearCredentials() }
        assertTrue(Files.exists(file))
        vault.locked = false
        secured.clearCredentials()
        assertTrue(vault.values.isEmpty())
    }

    @Test fun malformedVaultIsPreservedUntilRepaired() = fixture { file, vault ->
        val secured = store(file, vault)
        secured.putString("secret_1", "original-token")
        val good = vault.values.toMap()
        listOf("", "not-json").forEach { broken ->
            vault.values.replaceAll { _, _ -> broken }
            assertFailsWith<IllegalStateException> { store(file, vault).putString("secret_2", "new-token") }
            assertEquals(broken, vault.values.values.single())
        }
        vault.values.putAll(good)
        assertEquals("original-token", store(file, vault).getString("secret_1"))
    }

    @Test fun securedCheckDoesNotNeedAnotherVaultRead() = fixture { file, vault ->
        val secured = store(file, vault)
        secured.putString("secret_1", "original-token")
        vault.locked = true
        assertTrue(store(file, vault).isSecured("secret_1"))
    }

    @Test fun fileFailureAfterConfirmedVaultWriteKeepsTheOriginalSignIn() = fixture { file, vault ->
        seed(file)
        val interrupted = DesktopStorage.Store(file, vault, { it.startsWith("secret") }, {}) { _, output ->
            output.write("interrupted".toByteArray())
            throw IOException("interrupted")
        }
        assertEquals("first-token", interrupted.getString("secret_1"))
        assertTrue(Files.readString(file).contains("first-token"))
        assertEquals("first-token", store(file, vault).getString("secret_1"))
        assertClean(file)
    }

    @Test fun lockedVaultKeepsLegacyValuesAndDoesNotTurnMigratedSignInIntoNull() = fixture { file, vault ->
        seed(file)
        vault.locked = true
        assertEquals("first-token", store(file, vault).getString("secret_1"))
        vault.locked = false
        val secured = store(file, vault)
        assertEquals("first-token", secured.getString("secret_1"))
        vault.locked = true
        assertEquals("first-token", secured.getString("secret_1"))
        assertFailsWith<IllegalStateException> { store(file, vault).getString("secret_1") }
        vault.locked = false
        assertEquals("first-token", store(file, vault).getString("secret_1"))
    }

    @Test fun refreshAndSignOutDoNotRestoreAnOldTokenFromBackup() = fixture { file, vault ->
        seed(file)
        val secured = store(file, vault)
        secured.putString("secret_1", "refreshed-token")
        assertEquals("refreshed-token", store(file, vault).getString("secret_1"))
        assertFalse(Files.readString(file).contains("refreshed-token"))
        secured.putString("secret_1", null)
        assertNull(secured.getPreviousString("secret_1"))
        Files.write(file, byteArrayOf())
        assertNull(store(file, vault).getString("secret_1"))
        secured.clearCredentials()
        assertTrue(vault.values.isEmpty())
    }

    @Test fun profileKeysRemainSeparateAndTheCredentialPolicyCoversProviders() = fixture { file, vault ->
        val secured = store(file, vault)
        secured.putString("secret_1", "profile-one")
        secured.putString("secret_2", "profile-two")
        secured.remove("secret_1")
        assertEquals("profile-two", store(file, vault).getString("secret_2"))
        assertTrue(DesktopCredentials.protects("nuvio_trakt_auth", "trakt_auth_payload_2"))
        assertTrue(DesktopCredentials.protects("nuvio_debrid_settings", "debrid_premiumize_api_key_3"))
        assertTrue(DesktopCredentials.protects("nuvio_player_settings", "introdb_api_key_1"))
        assertTrue(DesktopCredentials.protects("server_credentials", "server_1"))
        assertTrue(DesktopCredentials.protects("nuvio_player_settings", "animeskip_client_id_1"))
        assertTrue(DesktopCredentials.protects("nuvio_custom_poster_url", "custom_poster_url_pattern_1"))
        assertFalse(DesktopCredentials.protects("nuvio_debrid_settings", "debrid_enabled_1"))
    }
}
