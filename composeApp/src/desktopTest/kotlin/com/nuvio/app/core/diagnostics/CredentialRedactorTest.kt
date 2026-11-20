package com.nuvio.app.core.diagnostics

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import io.sentry.Breadcrumb
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import kotlin.test.*

class CredentialRedactorTest {
    @Test fun removesEntireAddonUrlIncludingPathUserInfoQueryAndFragment() {
        val examples = listOf(
            "https://addon.example/short-secret/manifest.json",
            "https://addon.example/{\"rd\": \"raw-secret\"}/manifest.json",
            "/relative-secret/manifest.json",
            "https://name:password@addon.example/config/stream/movie/id.json?key=query-secret#fragment-secret",
            "stremio://addon.example/eyJhbGciOi/path-secret/manifest.json",
            "https://addon.example/%7B%22apiKey%22%3A%22encoded-secret%22%7D/manifest.json",
            "https:\\/\\/addon.example\\/escaped-secret\\/manifest.json",
            "https%3A%2F%2Faddon.example%2Fencoded-secret%2Fmanifest.json",
        )
        examples.forEach { assertEquals("Fetching [redacted]", CredentialRedactor.redact("Fetching $it")) }
    }

    @Test fun masksHeadersJsonAssignmentsAndKnownBareValues() {
        val examples = listOf(
            "Authorization: Bearer header-secret",
            "Cookie: sid=cookie-secret; refresh=another-secret",
            "{\"access_token\":\"access-secret\",\"refreshToken\":\"refresh-secret\"}",
            "api_key=key-secret password=password-secret client_secret=client-secret pin=1234",
            "Bearer bearer-secret",
            """{\"access_token\":\"nested-secret\"}""",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
        )
        examples.forEach { value ->
            val clean = CredentialRedactor.redact(value)
            assertFalse(clean.contains("-secret"), clean)
            assertFalse(clean.contains("1234"), clean)
            assertFalse(clean.contains("eyJ"), clean)
        }
        CredentialRedactor.remember("{\"refresh_token\":\"remembered-private-value\"}")
        assertEquals("Failed: [redacted]", CredentialRedactor.redact("Failed: remembered-private-value"))
        assertEquals("Fetched 12 streams in 30 ms", CredentialRedactor.redact("Fetched 12 streams in 30 ms"))
    }

    @Test fun logWriterNeverPassesOriginalThrowableIncludingCausesAndSuppressedErrors() {
        var output = ""
        var passed: Throwable? = null
        val destination = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                output = message; passed = throwable
            }
        }
        val error = IllegalStateException("https://addon.example/path-secret/manifest.json", Exception("api_key=cause-secret"))
        error.addSuppressed(Exception("Authorization: Bearer suppressed-secret"))
        RedactingLogWriter(destination).log(Severity.Error, "Failed token=message-secret", "network", error)
        assertFalse(output.contains("-secret"))
        assertTrue(output.contains("IllegalStateException"))
        assertNull(passed)
        assertTrue(error.message!!.contains("path-secret"))
    }

    @Test fun outputStreamHandlesSplitWritesAndOversizedLinesWithoutLeaking() {
        val bytes = ByteArrayOutputStream()
        val stream = RedactingOutputStream(bytes)
        stream.write("Fetching https://addon.example/pri".toByteArray())
        stream.flush()
        assertEquals("", bytes.toString())
        stream.write("vate-value/manifest.json\n".toByteArray())
        assertEquals("Fetching [redacted]\n", bytes.toString())
        stream.write(("x".repeat(70_000) + "password=tail-secret\n").toByteArray())
        assertFalse(bytes.toString().contains("tail-secret"))
        assertTrue(bytes.toString().contains("omitted"))
    }

    @Test fun crashSanitizerCoversNestedDataMessagesStackLocalsAndBreadcrumbs() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "Failed https://addon.example/path-secret/manifest.json" }
            exceptions = listOf(SentryException().apply {
                type = "NetworkError"
                value = "Authorization: Bearer exception-secret"
                stacktrace = SentryStackTrace(listOf(SentryStackFrame().apply {
                    filename = "Example.kt"
                    lineno = 12
                    vars = mapOf("credentials" to mapOf("api_key" to "local-secret"))
                }))
            })
            setExtra("nested", mapOf("headers" to listOf(mapOf("Authorization" to "Bearer extra-secret"))))
            contexts.put("custom", mapOf("password" to "context-secret"))
            setTag("url", "https://addon.example/tag-secret/manifest.json")
            addBreadcrumb(Breadcrumb("https://addon.example/breadcrumb-secret/manifest.json").apply {
                setData("refresh_token", "breadcrumb-data-secret")
            })
        }
        val clean = assertNotNull(SentryEventSanitizer.sanitize(event))
        val serialized = StringWriter().also { JsonSerializer(SentryOptions()).serialize(clean, it) }.toString()
        assertFalse(serialized.contains("-secret"), serialized)
        assertTrue(serialized.contains("Example.kt"))
        assertTrue(serialized.contains("NetworkError"))
    }
    @Test fun coversRpcPinsClientIdsPasswordsAndMultiwordAssignments() {
        listOf("p_pin=1234", "client_id=client-value", "Pw=pass phrase", "password=two words").forEach {
            assertEquals(it.substringBefore('=') + "=[redacted]", CredentialRedactor.redact(it))
        }
        val clean = CredentialRedactor.redactJson(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"p_pin":"1234","client_id":"client-value","Pw":"pass phrase"}"""))
        assertFalse(clean.toString().contains("1234"))
        assertFalse(clean.toString().contains("client-value"))
        assertFalse(clean.toString().contains("pass phrase"))
    }

    @Test fun remembersArrayStringsAndArbitraryPluginCredentials() {
        CredentialRedactor.remember("""["array-private-value"]""")
        CredentialRedactor.remember("""{"key":"plugin-private-value","auth":"custom-private-value","rdKey":"provider-private-value"}""")
        listOf("array-private-value", "plugin-private-value", "custom-private-value", "provider-private-value").forEach {
            assertEquals("[redacted]", CredentialRedactor.redact(it))
        }
        assertEquals("[redacted]", CredentialRedactor.redact("nuvio%3A%2F%2Fauth%2Ftrakt%3Fcode%3Dprivate-value"))
    }

    @Test fun shortSecretsDoNotDamageEventStructureOrDates() {
        listOf("2026", "0000", "type", "user").forEach(CredentialRedactor::remember)
        val event = SentryEvent(java.util.Date(1795000000000L)).apply {
            message = Message().apply { formatted = "Private value: 2026" }
            setExtra("private-user", mapOf("type" to "user"))
            exceptions = listOf(SentryException().apply { type = "NetworkError"; value = "user" })
        }
        val clean = assertNotNull(SentryEventSanitizer.sanitize(event))
        assertEquals(event.timestamp, clean.timestamp)
        assertEquals("NetworkError", clean.exceptions!!.single().type)
        assertEquals("Private value: [redacted]", clean.message!!.formatted)
        assertEquals("[redacted]", clean.exceptions!!.single().value)
        val serialized = StringWriter().also { JsonSerializer(SentryOptions()).serialize(clean, it) }.toString()
        assertFalse(serialized.contains("private-user"))
    }

}
