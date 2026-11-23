package com.nuvio.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

internal actual fun createApiHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine { preconfigured = guardedHttpClient().build() }
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}

internal actual fun createBackendHttpEngine(): io.ktor.client.engine.HttpClientEngine? = OkHttp.create {
    preconfigured = guardedHttpClient().build()
}
