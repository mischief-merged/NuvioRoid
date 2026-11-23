package com.nuvio.app.core.ui

import coil3.network.NetworkClient
import coil3.network.NetworkHeaders
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import com.nuvio.app.core.network.ArtworkMaxBytes
import com.nuvio.app.core.network.guardedHttpClient
import com.nuvio.app.core.network.readBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.TimeUnit

/** Keeps Coil's normal cache while limiting the decoded HTTP body before it reaches a decoder. */
internal class BoundedArtworkClient : NetworkClient {
    private val client = guardedHttpClient().callTimeout(45, TimeUnit.SECONDS).build()
    override suspend fun <T> executeRequest(request: NetworkRequest, block: suspend (NetworkResponse) -> T): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url).method(request.method, null)
        request.headers.asMap().forEach { (key, values) -> values.forEach { builder.addHeader(key, it) } }
        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.readBounded(ArtworkMaxBytes) ?: ByteArray(0)
            val headers = NetworkHeaders.Builder()
            response.headers.forEach { (key, value) -> headers.add(key, value) }
            block(NetworkResponse(
                code = response.code,
                requestMillis = response.sentRequestAtMillis,
                responseMillis = response.receivedResponseAtMillis,
                headers = headers.build(),
                body = NetworkResponseBody(Buffer().write(bytes)),
            ))
        }
    }
}
