package com.nuvio.app.core.network

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_home_server_blocked
import nuvio.composeapp.generated.resources.network_https_required
import nuvio.composeapp.generated.resources.network_response_too_large
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.jetbrains.compose.resources.getString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

internal object TrustedHomeServers {
    private val store by lazy { DesktopStorage.store("network_trust") }
    fun hosts(): Set<String> = store.getString("home_hosts").orEmpty().lines().filter(String::isNotBlank).toSet()
    fun contains(host: String): Boolean = canonicalHost(host) in hosts()
    fun add(address: String): Boolean {
        val host = canonicalHost(address) ?: return false
        store.putString("home_hosts", (hosts() + host).sorted().joinToString("\n"))
        return true
    }
    fun remove(host: String) = store.putString("home_hosts", (hosts() - host).sorted().joinToString("\n"))
}

internal fun canonicalHost(input: String): String? {
    val value = input.trim()
    val url = (if ("://" in value) value else "http://$value").toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.host.trimEnd('.')
}

internal const val MetadataMaxBytes = 8 * 1024 * 1024
internal const val SubtitleMaxBytes = 64 * 1024 * 1024
internal const val PluginMaxBytes = 2 * 1024 * 1024
internal const val ArtworkMaxBytes = 8 * 1024 * 1024

internal class NetworkPolicyException(message: String) : java.net.ProtocolException(message)

internal fun networkBlocked(): IOException = NetworkPolicyException(runBlocking { getString(Res.string.network_home_server_blocked) })
internal fun httpsRequired(): IOException = NetworkPolicyException(runBlocking { getString(Res.string.network_https_required) })
internal fun responseTooLarge(): IOException = IOException(runBlocking { getString(Res.string.network_response_too_large) })

/** Addresses that must never be reached accidentally through a scraper or a download. */
internal fun isLocalDestination(address: InetAddress): Boolean {
    val b = address.address.map { it.toInt() and 255 }
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return true
    if (b.size == 4) return b[0] == 0 || b[0] >= 224 || (b[0] == 100 && b[1] in 64..127) ||
        (b[0] == 192 && b[1] == 0 && b[2] == 0) || (b[0] == 198 && b[1] in 18..19)
    // Reject local, transition and translation ranges that can hide an IPv4 destination.
    return (b[0] and 0xfe) == 0xfc || b.take(12).all { it == 0 } ||
        (b[0] == 0x20 && b[1] == 0x02) ||
        (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) ||
        (b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b)
}

internal class DestinationPolicy(
    private val trusted: (String) -> Boolean = TrustedHomeServers::contains,
    private val resolver: Dns = DesktopIPv4FirstDns(),
) {
    fun check(host: String, address: InetAddress) {
        if (isLocalDestination(address) && !trusted(host.trimEnd('.'))) throw networkBlocked()
    }
    val dns = object : Dns {
        override fun lookup(host: String): List<InetAddress> = resolver.lookup(host).also { addresses ->
            if (addresses.isEmpty()) throw networkBlocked()
            addresses.forEach { check(host, it) }
        }.map { InetAddress.getByAddress(host, it.address) }
    }
    // OkHttp connects to the already resolved address. Check that exact address here, including
    // numeric URLs (which bypass Dns), without a second name lookup or a proxy doing the lookup.
    val sockets: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                val target = endpoint as? InetSocketAddress ?: throw networkBlocked()
                val address = target.address ?: throw networkBlocked()
                check(target.hostString, address)
                super.connect(target, timeout)
            }
        }
        override fun createSocket(host: String, port: Int): Socket = throw networkBlocked()
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = throw networkBlocked()
        override fun createSocket(host: InetAddress, port: Int): Socket = throw networkBlocked()
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = throw networkBlocked()
    }
}

internal fun guardedHttpClient(
    httpsOnly: Boolean = false,
    policy: DestinationPolicy = DestinationPolicy(),
    tlsTrust: javax.net.ssl.X509TrustManager? = DesktopTlsTrust.manager,
): OkHttpClient.Builder {
    fun checkScheme(url: HttpUrl) { if (httpsOnly && !url.isHttps) throw httpsRequired() }
    return OkHttpClient.Builder().apply {
        if (tlsTrust != null) {
            val context = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tlsTrust), null) }
            sslSocketFactory(context.socketFactory, tlsTrust)
            if (tlsTrust is MacKeychainTrustManager) {
                // Recheck the requested hostname and prevent HTTP/2 coalescing across hosts whose
                // Keychain trust settings may differ, even when their certificates share a SAN.
                hostnameVerifier { host, session ->
                    runCatching {
                        tlsTrust.verify(session.peerCertificates.map { it as java.security.cert.X509Certificate }.toTypedArray(), host)
                    }.isSuccess
                }
            }
        }
    }
        .proxy(Proxy.NO_PROXY)
        .dns(policy.dns)
        .socketFactory(policy.sockets)
        .addInterceptor { chain ->
            checkScheme(chain.request().url)
            chain.proceed(chain.request())
        }
        .addNetworkInterceptor { chain ->
            // Runs for every redirect and pooled connection, before any request bytes are sent.
            checkScheme(chain.request().url)
            val peer = chain.connection()?.socket()?.inetAddress ?: throw networkBlocked()
            policy.check(chain.request().url.host, peer)
            chain.proceed(chain.request())
        }
}

/** Enforces the cap while streaming, including chunked and decompressed responses. */
internal fun ResponseBody.readBounded(limit: Int): ByteArray {
    require(limit >= 0)
    if (contentLength() > limit) throw responseTooLarge()
    return byteStream().use { input ->
        val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
        val buffer = ByteArray(8192)
        var remaining = limit
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
            if (count < 0) break
            if (count > remaining) throw responseTooLarge()
            output.write(buffer, 0, count)
            remaining -= count
        }
        output.toByteArray()
    }
}
