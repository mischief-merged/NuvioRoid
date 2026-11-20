package com.nuvio.app.core.diagnostics

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.*

internal object CredentialRedactor {
    const val hidden = "[redacted]"
    private val lock = SynchronizedObject()
    private val known = mutableSetOf<String>()
    private val urls = Regex("""(?i)\b[a-z][a-z0-9+.-]*:(?://|\\/\\/)[^\r\n]*""")
    private val encodedUrls = Regex("""(?i)\b[a-z][a-z0-9+.-]*%3a%2f%2f[^\r\n]*""")
    private val bearer = Regex("""(?i)\b(?:bearer|basic)\s+[a-z0-9._~+/=-]+""")
    private val jwt = Regex("""\beyJ[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+""")
    private val keyPattern = "(?:[a-z0-9_-]*(?:token|password|passwd|secret|api[_-]?key|apikey|authorization|cookie|code[_-]?verifier|device[_-]?code|client[_-]?id|pin|pw)|key|auth)"
    private val assignment = Regex("""(?i)(?<![a-z0-9_-])(["']?$keyPattern["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\r\n,;}&\])]+)""")
    private val relativeAddonPaths = Regex("""(?i)/(?:[^\s/]+/)*(?:manifest\.json|stream/|catalog/|meta/|subtitles/)[^\r\n]*""")
    private val sensitiveKeys = Regex("(?i)^$keyPattern$")
    private val escapedAssignment = Regex("""(?i)(\\["']$keyPattern\\["']\s*[:=]\s*)[^\r\n]*""")
    private val headers = Regex("""(?im)\b((?:proxy-)?authorization|(?:set-)?cookie)\s*[:=]\s*[^\r\n]+""")

    fun sensitiveKey(key: String): Boolean = sensitiveKeys.matches(key)

    fun remember(value: String) {
        val parsed = runCatching { Json.parseToJsonElement(value) }.getOrNull()
        if (parsed is JsonObject || parsed is JsonArray) rememberJson(parsed) else rememberSecret(value)
    }

    private fun rememberJson(value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                if (sensitiveKey(key) && child is JsonPrimitive) rememberSecret(child.content)
                else rememberJson(child)
            }
            is JsonArray -> value.forEach(::rememberJson)
            is JsonPrimitive -> if (value.isString) rememberSecret(value.content)
        }
    }

    private fun rememberSecret(value: String) {
        if (value.length >= 4) synchronized(lock) { known.add(value) }
    }

    fun redact(value: String): String {
        var result = value
        synchronized(lock) { known.sortedByDescending(String::length) }.forEach { secret ->
            result = result.replace(secret, hidden)
        }
        result = encodedUrls.replace(result, hidden)
        result = urls.replace(result, hidden)
        result = relativeAddonPaths.replace(result, hidden)
        result = escapedAssignment.replace(result) { "${it.groupValues[1]}$hidden" }
        result = headers.replace(result) { "${it.groupValues[1]}: $hidden" }
        result = bearer.replace(result, hidden)
        result = jwt.replace(result, hidden)
        return assignment.replace(result) { "${it.groupValues[1]}$hidden" }
    }

    fun redactJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.associate { (key, child) ->
            redact(key) to if (sensitiveKey(key)) JsonPrimitive(hidden) else redactJson(child)
        })
        is JsonArray -> JsonArray(value.map(::redactJson))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(redact(value.content)) else value
    }
}
