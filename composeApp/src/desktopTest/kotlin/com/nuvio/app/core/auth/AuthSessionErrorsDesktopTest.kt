package com.nuvio.app.core.auth

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse

class AuthSessionErrorsDesktopTest {
    @Test
    fun socket_and_io_failures_are_not_a_lost_session() {
        assertFalse(shouldClearLocalSessionAfterValidationError(SocketTimeoutException("timed out")))
        assertFalse(shouldClearLocalSessionAfterValidationError(UnknownHostException("no address")))
        assertFalse(shouldClearLocalSessionAfterValidationError(IOException("network unreachable")))
    }
}
