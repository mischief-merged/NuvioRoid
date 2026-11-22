package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.SecureStorageUnavailable
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthSessionErrorsTest {
    @Test
    fun a_timeout_or_missing_network_is_not_a_lost_session() {
        assertFalse(shouldClearLocalSessionAfterValidationError(SocketTimeoutException("timed out")))
        assertFalse(shouldClearLocalSessionAfterValidationError(UnknownHostException("no address")))
        assertFalse(shouldClearLocalSessionAfterValidationError(java.io.IOException("network unreachable")))
    }

    @Test
    fun a_storage_or_keychain_error_is_not_a_lost_session() {
        assertFalse(shouldClearLocalSessionAfterValidationError(SecureStorageUnavailable()))
        assertFalse(shouldClearLocalSessionAfterValidationError(IllegalStateException("Secure storage operation failed (-128)")))
    }

    @Test
    fun a_deleted_account_or_missing_session_still_clears_local_auth() {
        assertTrue(shouldClearLocalSessionAfterValidationError(IllegalStateException("session not found")))
        assertTrue(shouldClearLocalSessionAfterValidationError(IllegalStateException("user does not exist")))
    }
}
