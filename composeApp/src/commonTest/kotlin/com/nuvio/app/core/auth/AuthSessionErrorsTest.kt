package com.nuvio.app.core.auth

import com.nuvio.app.core.network.authFailureIsDefinitive
import com.nuvio.app.core.storage.SecureStorageUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSessionErrorsTest {
    @Test
    fun a_timeout_or_missing_network_is_not_a_lost_session() {
        assertFalse(shouldClearLocalSessionAfterValidationError(IllegalStateException("timed out")))
        assertFalse(shouldClearLocalSessionAfterValidationError(IllegalStateException("network unreachable")))
        assertFalse(shouldClearLocalSessionAfterValidationError(IllegalStateException("unknown host")))
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

    @Test
    fun a_bare_rejection_is_not_enough_to_drop_the_session() {
        assertFalse(shouldClearLocalSessionAfterValidationError(IllegalStateException("invalid token")))
        assertFalse(shouldClearLocalSessionAfterValidationError(IllegalStateException("invalid jwt")))
        assertFalse(authFailureIsDefinitive(401, "unauthorized"))
        assertFalse(authFailureIsDefinitive(403, ""))
    }

    @Test
    fun transient_http_statuses_keep_the_session_even_when_the_body_looks_final() {
        assertFalse(authFailureIsDefinitive(408, "session_not_found"))
        assertFalse(authFailureIsDefinitive(429, "invalid_grant"))
        assertFalse(authFailureIsDefinitive(500, "user does not exist"))
        assertFalse(authFailureIsDefinitive(503, "refresh token not found"))
    }

    @Test
    fun only_a_revoked_session_or_a_deleted_account_is_final() {
        assertTrue(authFailureIsDefinitive(400, """{"error_code":"session_not_found"}"""))
        assertTrue(authFailureIsDefinitive(400, """{"error":"invalid_grant"}"""))
        assertTrue(authFailureIsDefinitive(401, "invalid refresh token"))
        assertTrue(authFailureIsDefinitive(400, "user does not exist"))
        assertTrue(shouldClearLocalSessionAfterValidationError(IllegalStateException("invalid session")))
    }

    @Test
    fun an_offline_session_uses_the_account_id_in_the_access_token() {
        val payload = kotlin.io.encoding.Base64.UrlSafe.encode("""{"sub":"user-9"}""".encodeToByteArray())
        val token = "header.$payload.sig"
        assertEquals("user-9", accountIdFromAccessToken(token))
        val state = localAuthStateWhileOffline(
            io.github.jan.supabase.auth.user.UserSession(
                accessToken = token,
                refreshToken = "refresh-token",
                expiresIn = 3600,
                tokenType = "bearer",
            ),
        )
        assertEquals("user-9", state?.userId)
        assertEquals(false, state?.isAnonymous)
        assertNull(localAuthStateWhileOffline(null))
        assertNull(accountIdFromAccessToken("not-a-token"))
    }
}
