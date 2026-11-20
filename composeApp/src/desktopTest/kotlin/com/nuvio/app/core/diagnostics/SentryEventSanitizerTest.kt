package com.nuvio.app.core.diagnostics

import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.User
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class SentryEventSanitizerTest {
    @Test
    fun removesPersonalAndRequestData() {
        val event = SentryEvent().apply {
            request = Request().apply { url = "https://example.com/private" }
            user = User().apply { email = "person@example.com" }
            serverName = "private-computer"
        }

        val clean = assertNotNull(SentryEventSanitizer.sanitize(event))
        assertNull(clean.request)
        assertNull(clean.user)
        assertNull(clean.serverName)
    }

    @Test
    fun dropsIgnoredIssueMessages() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "File IO on Main Thread" }
        }

        assertNull(SentryEventSanitizer.sanitize(event))
    }
}
