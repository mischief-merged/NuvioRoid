package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.error_connection_failed
import nuvio.composeapp.generated.resources.error_connection_timed_out
import nuvio.composeapp.generated.resources.error_not_found
import nuvio.composeapp.generated.resources.error_playback_unavailable
import nuvio.composeapp.generated.resources.error_rate_limited
import nuvio.composeapp.generated.resources.error_request_denied
import nuvio.composeapp.generated.resources.error_secure_connection_failed
import nuvio.composeapp.generated.resources.error_server_problem
import nuvio.composeapp.generated.resources.error_server_unreachable
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

internal data class FailureMessages(
    val timeout: String,
    val unreachable: String,
    val refused: String,
    val insecure: String,
    val playbackUnavailable: String,
    val notFound: String,
    val rateLimited: String,
    val server: String,
    val denied: String,
)

internal enum class FailureKind {
    Timeout,
    Unreachable,
    Refused,
    Insecure,
    PlaybackUnavailable,
    NotFound,
    RateLimited,
    Server,
    Denied,
    Technical,
    Plain,
}

internal fun classifyFailure(raw: String?): FailureKind {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return FailureKind.Technical
    val lower = text.lowercase()
    val http = httpStatus(lower)
    return when {
        lower.contains("rebuild the app") || lower.contains("player engine not available") ->
            FailureKind.PlaybackUnavailable
        lower.contains("timeout") || lower.contains("timed out") -> FailureKind.Timeout
        lower.contains("unknownhost") ||
            lower.contains("unable to resolve") ||
            lower.contains("no address associated") ||
            lower.contains("unresolved address") -> FailureKind.Unreachable
        lower.contains("connection refused") ||
            lower.contains("failed to connect") ||
            lower.contains("connectexception") ||
            lower.contains("connection reset") ||
            lower.contains("network is unreachable") -> FailureKind.Refused
        lower.contains("ssl") ||
            lower.contains("certificate") ||
            lower.contains("handshake") -> FailureKind.Insecure
        http == 404 -> FailureKind.NotFound
        http == 429 -> FailureKind.RateLimited
        http == 401 || http == 403 -> FailureKind.Denied
        http != null && http >= 500 -> FailureKind.Server
        looksTechnical(lower) -> FailureKind.Technical
        else -> FailureKind.Plain
    }
}

internal fun userFacingErrorMessage(
    raw: String?,
    fallback: String,
    messages: FailureMessages,
): String {
    val text = raw?.trim().orEmpty()
    return when (classifyFailure(text)) {
        FailureKind.Timeout -> messages.timeout
        FailureKind.Unreachable -> messages.unreachable
        FailureKind.Refused -> messages.refused
        FailureKind.Insecure -> messages.insecure
        FailureKind.PlaybackUnavailable -> messages.playbackUnavailable
        FailureKind.NotFound -> messages.notFound
        FailureKind.RateLimited -> messages.rateLimited
        FailureKind.Server -> messages.server
        FailureKind.Denied -> messages.denied
        FailureKind.Technical -> fallback
        FailureKind.Plain -> text
    }
}

internal suspend fun failureMessages(): FailureMessages = FailureMessages(
    timeout = getString(Res.string.error_connection_timed_out),
    unreachable = getString(Res.string.error_server_unreachable),
    refused = getString(Res.string.error_connection_failed),
    insecure = getString(Res.string.error_secure_connection_failed),
    playbackUnavailable = getString(Res.string.error_playback_unavailable),
    notFound = getString(Res.string.error_not_found),
    rateLimited = getString(Res.string.error_rate_limited),
    server = getString(Res.string.error_server_problem),
    denied = getString(Res.string.error_request_denied),
)

internal suspend fun userFacingFailure(raw: String?, fallback: String): String =
    userFacingErrorMessage(raw, fallback, failureMessages())

@Composable
internal fun userFacingErrorText(raw: String?, fallback: String): String {
    val messages = FailureMessages(
        timeout = stringResource(Res.string.error_connection_timed_out),
        unreachable = stringResource(Res.string.error_server_unreachable),
        refused = stringResource(Res.string.error_connection_failed),
        insecure = stringResource(Res.string.error_secure_connection_failed),
        playbackUnavailable = stringResource(Res.string.error_playback_unavailable),
        notFound = stringResource(Res.string.error_not_found),
        rateLimited = stringResource(Res.string.error_rate_limited),
        server = stringResource(Res.string.error_server_problem),
        denied = stringResource(Res.string.error_request_denied),
    )
    return userFacingErrorMessage(raw, fallback, messages)
}

private fun httpStatus(lower: String): Int? {
    val match = Regex("""\bhttp(?:\s+status)?\s*[:=]?\s*(\d{3})\b""").find(lower)
        ?: Regex("""\bstatus(?:\s+code)?\s*[:=]?\s*(\d{3})\b""").find(lower)
        ?: Regex("""\b(\d{3})\s+(?:not found|forbidden|unauthorized|too many)\b""").find(lower)
    return match?.groupValues?.get(1)?.toIntOrNull()
}

private fun looksTechnical(lower: String): Boolean {
    if (lower.contains("exception") || lower.contains("stacktrace") || lower.contains("stack trace")) return true
    if (lower.contains("regex")) return true
    if (lower.contains("ktor") || lower.contains("io.ktor")) return true
    if (lower.contains("java.") || lower.contains("kotlin.") || lower.contains("javax.")) return true
    if (lower.contains("nullpointer") || lower.contains("illegalstate") || lower.contains("illegalargument")) return true
    if (Regex("""\bat [\w.$]+\(""").containsMatchIn(lower)) return true
    if (lower.contains("json") && (lower.contains("unexpected") || lower.contains("parse"))) return true
    return false
}
