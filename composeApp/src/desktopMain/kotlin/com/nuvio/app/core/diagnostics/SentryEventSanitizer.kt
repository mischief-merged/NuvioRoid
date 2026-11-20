package com.nuvio.app.core.diagnostics

import io.sentry.SentryEvent
import io.sentry.JsonSerializer
import io.sentry.SentryOptions
import java.io.StringReader
import java.io.StringWriter
import kotlinx.serialization.json.*

internal object SentryEventSanitizer {
    val ignoredIssueText = listOf(
        "Large HTTP payload",
        "File IO on Main Thread",
    )

    fun sanitize(event: SentryEvent): SentryEvent? {
        event.request = null
        event.user = null
        event.serverName = null
        if (shouldDrop(event)) return null
        // Round-trip the complete event so new SDK fields, nested contexts and stack locals are covered.
        return runCatching {
            val serializer = JsonSerializer(SentryOptions())
            val output = StringWriter()
            serializer.serialize(event, output)
            val clean = sanitizeTree(Json.parseToJsonElement(output.toString()))
            serializer.deserialize(StringReader(clean.toString()), SentryEvent::class.java)
        }.getOrNull()
    }

    // These fields are typed protocol data, not free text. Keep their validated values intact.
    private val protocolFields = setOf("timestamp", "start_timestamp", "event_id", "trace_id", "span_id", "parent_span_id", "level", "type", "boot_time", "app_start_time")
    private val opaqueData = setOf("extra", "data", "vars", "tags")

    private fun sanitizeTree(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            when {
                key == "contexts" && child is JsonObject -> sanitizeContexts(child)
                key in opaqueData -> CredentialRedactor.redactJson(child)
                key in protocolFields -> child
                CredentialRedactor.sensitiveKey(key) -> JsonPrimitive(CredentialRedactor.hidden)
                else -> sanitizeTree(child)
            }
        })
        is JsonArray -> JsonArray(value.map(::sanitizeTree))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(CredentialRedactor.redact(value.content)) else value
    }

    private fun sanitizeContexts(value: JsonObject): JsonObject {
        val standard = setOf("app", "browser", "device", "gpu", "os", "runtime", "trace", "response", "culture")
        return JsonObject(value.entries.associate { (key, child) ->
            if (key in standard) key to sanitizeTree(child)
            else CredentialRedactor.redact(key) to CredentialRedactor.redactJson(child)
        })
    }

    private fun shouldDrop(event: SentryEvent): Boolean =
        eventText(event).any { text ->
            ignoredIssueText.any { ignored ->
                text.contains(ignored, ignoreCase = true)
            }
        }

    private fun eventText(event: SentryEvent): List<String> {
        val values = mutableListOf<String>()
        event.message?.formatted?.let(values::add)
        event.message?.message?.let(values::add)
        event.logger?.let(values::add)
        event.transaction?.let(values::add)
        event.exceptions?.forEach { exception ->
            exception.type?.let(values::add)
            exception.value?.let(values::add)
        }
        return values
    }
}
