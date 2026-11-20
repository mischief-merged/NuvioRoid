package com.nuvio.app.features.profiles

import java.io.IOException
import java.nio.file.Files
import com.nuvio.app.core.storage.DesktopStorage
import kotlin.test.*

class ProfilePinSecurityTest {
    @Test fun saltedKdfVerifiesOnlyTheCorrectPinAndProfile() {
        val salt = generateProfilePinSalt()
        val secondSalt = generateProfilePinSalt()
        assertNotEquals(salt, secondSalt)
        val digest = hashProfilePin(1, salt, "1234")
        assertTrue(digest.startsWith("pbkdf2-sha256\$600000\$"))
        assertTrue(ProfilePinCrypto.verify(1, salt, "1234", digest))
        assertFalse(ProfilePinCrypto.verify(1, salt, "1235", digest))
        assertFalse(ProfilePinCrypto.verify(2, salt, "1234", digest))
        assertNotEquals(digest, hashProfilePin(1, secondSalt, "1234"))
        assertFalse(ProfilePinCrypto.verify(1, salt, "1234", "pbkdf2-sha256\$999999999\$" + "0".repeat(64)))
        assertFalse(ProfilePinCrypto.verify(1, salt, "1234", "malformed"))
    }

    @Test fun successfulLegacyVerificationMigratesButWrongPinsDoNot() {
        val old = CachedProfilePinPayload("legacy-salt", ProfilePinCrypto.sha256Hex("profile:2:legacy-salt:1357"), "updated")
        var migrated: CachedProfilePinPayload? = null
        assertFalse(verifyAndUpgradeProfilePin(2, "0000", old) { migrated = it })
        assertNull(migrated)
        assertTrue(verifyAndUpgradeProfilePin(2, "1357", old) { migrated = it })
        val saved = assertNotNull(migrated)
        assertEquals("updated", saved.profileUpdatedAt)
        assertNotEquals(old.salt, saved.salt)
        assertTrue(ProfilePinCrypto.verify(2, saved.salt, "1357", saved.digest))
        assertTrue(verifyAndUpgradeProfilePin(2, "1357", saved) { fail("already upgraded") })
    }

    @Test fun failedMigrationLeavesLegacyVerifierUsable() {
        val old = CachedProfilePinPayload("salt", ProfilePinCrypto.sha256Hex("profile:1:salt:2468"))
        assertTrue(verifyAndUpgradeProfilePin(1, "2468", old) { throw IOException("interrupted") })
        assertTrue(ProfilePinCrypto.verify(1, old.salt, "2468", old.digest))
    }

    @Test fun backoffPersistsAcrossInstancesGrowsAndResetsOnSuccess() {
        val states = mutableMapOf<Int, String>()
        var clock = 100_000L
        fun limiter() = ProfilePinAttemptLimiter(states::get, { id, value -> states[id] = value }, { clock })
        val first = limiter()
        repeat(4) { first.reserve(1); assertEquals(0, first.remaining(1)) }
        first.reserve(1)
        assertEquals(30, limiter().remaining(1))
        assertEquals(0, first.remaining(2))
        clock += 29_001
        assertEquals(1, first.remaining(1))
        clock += 999
        assertEquals(0, first.remaining(1))
        first.reserve(1)
        assertEquals(60, first.remaining(1))
        repeat(30) { clock += 900_000; first.reserve(1) }
        assertEquals(900, first.remaining(1))
        first.success(1)
        assertEquals(0, limiter().remaining(1))
        repeat(4) { first.reserve(1); assertEquals(0, first.remaining(1)) }
    }

    @Test fun replacementRemovesFastVerifierFromRecoveryCopies() {
        val directory = Files.createTempDirectory("pin-upgrade")
        try {
            val file = directory.resolve("pin.properties")
            val store = DesktopStorage.Store(file)
            store.putString("pin", "old-fast-digest")
            store.putString("pin", "new-slow-digest")
            store.replacePreviousValue("pin", "new-slow-digest")
            assertEquals("new-slow-digest", store.getPreviousString("pin"))
            assertFalse(Files.readString(file.resolveSibling("pin.properties.bak")).contains("old-fast-digest"))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun removingPinUsesTheSameAttemptBudgetAsVerification() = kotlinx.coroutines.runBlocking {
        val saved = mutableMapOf<Int, String>()
        val requests = ProfilePinRequests(ProfilePinAttemptLimiter(saved::get, { id, value -> saved[id] = value }, { 100_000 }))
        repeat(5) { requests.verify(1) { PinVerifyResult(unlocked = false) } }
        var called = false
        val blocked = requests.clear(1) { called = true; PinVerifyResult(unlocked = true) }
        assertFalse(called)
        assertEquals(30, blocked.retryAfterSeconds)
    }

    @Test fun persistenceFailureReturnsAnErrorWithoutCheckingThePin() = kotlinx.coroutines.runBlocking {
        val requests = ProfilePinRequests(ProfilePinAttemptLimiter({ null }, { _, _ -> throw IOException("full") }), { "unavailable" })
        var checked = false
        val result = requests.verify(1) { checked = true; PinVerifyResult(unlocked = true) }
        assertFalse(checked)
        assertFalse(result.unlocked)
        assertNotNull(result.message)
        Unit
    }

}
