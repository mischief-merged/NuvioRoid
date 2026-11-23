package com.nuvio.app.core.network

import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/** The hostname and complete peer chain go to the operating system on every handshake. */
internal class MacKeychainTrustManager(
    private val evaluate: (Array<ByteArray>, String) -> Boolean,
    private val issuers: () -> Array<X509Certificate>,
) : X509ExtendedTrustManager() {
    fun verify(chain: Array<out X509Certificate>, host: String?) {
        if (host.isNullOrBlank() || chain.isEmpty()) throw CertificateException("Missing TLS peer identity")
        val accepted = try { evaluate(chain.map { it.encoded }.toTypedArray(), host) }
        catch (error: Exception) { throw CertificateException("System certificate verification is unavailable", error) }
        if (!accepted) throw CertificateException("System certificate verification failed")
    }
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) =
        verify(chain, (socket as? SSLSocket)?.handshakeSession?.peerHost)
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) =
        verify(chain, engine.handshakeSession?.peerHost ?: engine.peerHost)
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
        throw CertificateException("Missing TLS peer identity")
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
        throw CertificateException("Client certificates are not supported")
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) = checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) = checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = issuers()
}

internal object DesktopTlsTrust {
    val manager: MacKeychainTrustManager? by lazy {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) null
        else MacKeychainTrustManager(
            evaluate = { chain, host ->
                NativePlayerBridge.ensureLoaded()
                NativePlayerBridge.verifyServerCertificates(chain, host)
            },
            // These certificates help OkHttp assemble chains after the native trust check.
            // Inclusion here does not confer trust; scoped settings are evaluated per connection.
            issuers = {
                NativePlayerBridge.ensureLoaded()
                val factory = java.security.cert.CertificateFactory.getInstance("X.509")
                NativePlayerBridge.systemTrustCertificates().map {
                    factory.generateCertificate(it.inputStream()) as X509Certificate
                }.toTypedArray()
            },
        )
    }
}
