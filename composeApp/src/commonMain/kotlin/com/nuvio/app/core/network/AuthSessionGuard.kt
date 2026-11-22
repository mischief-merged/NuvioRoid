package com.nuvio.app.core.network

import com.nuvio.app.core.auth.AuthSessionRetention
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText

/** A failed auth request that must not be treated as a revoked session. */
internal class AuthRequestKeptException(message: String) : Exception(message)

internal fun HttpClientConfig<*>.installRetainedAuthGuard() {
    install(AuthSessionGuard)
}

internal val AuthSessionGuard = createClientPlugin("AuthSessionGuard") {
    on(Send) { request ->
        val call = proceed(request)
        val path = call.request.url.encodedPath
        if (!path.contains("/auth/v1/")) return@on call
        val status = call.response.status.value
        if (status < 400) return@on call
        val body = runCatching { call.response.bodyAsText() }.getOrDefault("")
        if (authFailureIsDefinitive(status, body)) {
            AuthSessionRetention.permitStoredClear()
            call
        } else {
            throw AuthRequestKeptException("Auth request failed without a definitive session rejection ($status)")
        }
    }
}

internal fun authFailureIsDefinitive(status: Int, body: String): Boolean {
    if (status == 408 || status == 429 || status >= 500) return false
    val text = body.lowercase()
    if ("session not found" in text || "session_not_found" in text) return true
    if ("invalid refresh token" in text || "refresh token not found" in text) return true
    if ("refresh_token_not_found" in text || "invalid_grant" in text) return true
    if ("invalid session" in text) return true
    val userGone = "user" in text && ("does not exist" in text || "not found" in text || "deleted" in text)
    val foreignKey = "foreign key" in text && ("auth.users" in text || "user_id" in text)
    return userGone || foreignKey
}
