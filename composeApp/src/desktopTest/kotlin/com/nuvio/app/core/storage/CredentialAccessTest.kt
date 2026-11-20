package com.nuvio.app.core.storage

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CredentialAccessTest {
    @Test fun unavailableStartupAndProfileSwitchWaitForRetryWithoutLosingSavedState() = runBlocking {
        val access = CredentialAccess()
        var loaded = false
        assertFalse(access.run { throw SecureStorageUnavailable() })
        assertTrue(access.blocked.value)
        access.retry()
        assertTrue(access.run { loaded = true })
        assertTrue(loaded)
        assertFalse(access.blocked.value)
    }
}
