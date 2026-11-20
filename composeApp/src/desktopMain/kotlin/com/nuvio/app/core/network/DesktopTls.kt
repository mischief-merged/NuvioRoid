package com.nuvio.app.core.network

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

internal object DesktopTls {
    val trustManager: X509TrustManager by lazy {
        if (System.getProperty("os.name").orEmpty().contains("mac", true)) MacTrustManager(MacCertificateEvaluator()::check)
        else TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
    val context: SSLContext by lazy { context(trustManager) }
    fun install(manager: X509TrustManager = trustManager) {
        val configured = context(manager)
        SSLContext.setDefault(configured)
        HttpsURLConnection.setDefaultSSLSocketFactory(configured.socketFactory)
    }
    fun context(manager: X509TrustManager): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(manager), null)
    }
    fun clientBuilder(manager: X509TrustManager = trustManager): okhttp3.OkHttpClient.Builder =
        okhttp3.OkHttpClient.Builder().sslSocketFactory(context(manager).socketFactory, manager)
}

/** The operating system evaluates the chain for this host, including scoped user trust and explicit denial. */
internal class MacTrustManager(private val evaluate: (Array<X509Certificate>, String) -> Unit) : X509ExtendedTrustManager() {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit = throw CertificateException("Client trust is not supported")
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = checkClientTrusted(chain, authType)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String): Unit = throw CertificateException("Server name is required")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) =
        check(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost)
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
        check(chain, authType, engine.peerHost)

    private fun check(chain: Array<X509Certificate>, authType: String, host: String?) {
        if (chain.isEmpty() || authType.isBlank() || host.isNullOrBlank()) throw CertificateException("Incomplete server identity")
        chain.first().checkValidity()
        try { evaluate(chain, host) }
        catch (error: CertificateException) { throw error }
        catch (_: Exception) { throw CertificateException("System certificate trust is unavailable") }
        catch (_: LinkageError) { throw CertificateException("System certificate trust is unavailable") }
    }
}

/** Optional anchors are used by isolated tests only. Normal clients use the system Keychains without overriding trust settings. */
internal class MacCertificateEvaluator(private val testAnchors: List<ByteArray>? = null) {
    private interface Security : Library {
        fun SecCertificateCreateWithData(allocator: Pointer?, data: Pointer): Pointer?
        fun SecPolicyCreateSSL(server: Byte, hostname: Pointer): Pointer?
        fun SecTrustCreateWithCertificates(certificates: Pointer, policy: Pointer, trust: PointerByReference): Int
        fun SecTrustSetAnchorCertificates(trust: Pointer, certificates: Pointer): Int
        fun SecTrustSetAnchorCertificatesOnly(trust: Pointer, only: Byte): Int
        fun SecTrustEvaluateWithError(trust: Pointer, error: PointerByReference?): Byte
    }
    private interface Core : Library {
        fun CFDataCreate(allocator: Pointer?, bytes: ByteArray, length: NativeLong): Pointer?
        fun CFStringCreateWithCString(allocator: Pointer?, value: String, encoding: Int): Pointer?
        fun CFArrayCreate(allocator: Pointer?, values: Array<Pointer>, count: NativeLong, callbacks: Pointer): Pointer?
        fun CFRelease(value: Pointer)
    }
    private val security by lazy { Native.load("/System/Library/Frameworks/Security.framework/Security", Security::class.java) }
    private val corePath = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
    private val core by lazy { Native.load(corePath, Core::class.java) }
    private val arrayCallbacks by lazy { NativeLibrary.getInstance(corePath).getGlobalVariableAddress("kCFTypeArrayCallBacks") }

    fun check(chain: Array<X509Certificate>, host: String) {
        val owned = mutableListOf<Pointer>()
        fun own(pointer: Pointer?): Pointer = (pointer ?: throw CertificateException("Could not evaluate server trust")).also(owned::add)
        fun certificates(encoded: List<ByteArray>): Pointer {
            val values = encoded.map { bytes ->
                val data = own(core.CFDataCreate(null, bytes, NativeLong(bytes.size.toLong())))
                own(security.SecCertificateCreateWithData(null, data))
            }.toTypedArray()
            return own(core.CFArrayCreate(null, values, NativeLong(values.size.toLong()), arrayCallbacks))
        }
        try {
            val chainCertificates = certificates(chain.map { it.encoded })
            val hostname = own(core.CFStringCreateWithCString(null, host, 0x08000100))
            val policy = own(security.SecPolicyCreateSSL(1, hostname))
            val result = PointerByReference()
            if (security.SecTrustCreateWithCertificates(chainCertificates, policy, result) != 0) throw CertificateException("Could not create server trust")
            val trust = own(result.value)
            testAnchors?.let {
                if (security.SecTrustSetAnchorCertificates(trust, certificates(it)) != 0 ||
                    security.SecTrustSetAnchorCertificatesOnly(trust, 1) != 0) throw CertificateException("Could not set test trust")
            }
            if (security.SecTrustEvaluateWithError(trust, null).toInt() == 0) throw CertificateException("Server certificate is not trusted")
        } finally { owned.asReversed().forEach(core::CFRelease) }
    }
}
