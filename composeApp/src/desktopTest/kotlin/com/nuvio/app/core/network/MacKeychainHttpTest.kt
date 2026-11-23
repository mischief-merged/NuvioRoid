package com.nuvio.app.core.network

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import okhttp3.Request
import kotlin.test.*

class MacKeychainHttpTest {
    private fun localPolicy() = DestinationPolicy(trusted = { true }, resolver = object : okhttp3.Dns {
        override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.1"))
    })
    @Test fun `API and backend engines use Keychain verification by default on macOS`() {
        if (!System.getProperty("os.name").contains("Mac", true)) return
        val client = createApiHttpClient()
        val backend = createBackendHttpEngine()
        try {
            assertIs<MacKeychainTrustManager>(guardedHttpClient().build().x509TrustManager)
            for (engine in listOf(client.engine, backend)) {
                val config = engine!!.config as io.ktor.client.engine.okhttp.OkHttpConfig
                assertIs<MacKeychainTrustManager>(config.preconfigured!!.x509TrustManager)
            }
            com.nuvio.app.features.player.desktop.NativePlayerBridge.ensureLoaded()
            assertFalse(com.nuvio.app.features.player.desktop.NativePlayerBridge.verifyServerCertificates(emptyArray(), "localhost"))
        } finally { client.close(); backend?.close() }
    }

    @Test fun `guarded JVM HTTPS uses the system evaluator and the requested hostname`() {
        LocalTlsServer().use { tls ->
            tls.server.createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("ok".toByteArray()) }
            }
            val evaluations = AtomicInteger()
            val native = MacKeychainTrustManager(evaluate = { chain, host ->
                evaluations.incrementAndGet()
                val certificates = chain.map { CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as X509Certificate }.toTypedArray()
                tls.trustManager.checkServerTrusted(certificates, "RSA")
                host == "localhost"
            }, issuers = { tls.trustManager.acceptedIssuers })
            val client = guardedHttpClient(policy = localPolicy(), tlsTrust = native).build()
            client.newCall(Request.Builder().url(tls.url).build()).execute().use { assertEquals("ok", it.body!!.string()) }
            assertTrue(evaluations.get() > 0)
            assertFailsWith<SSLHandshakeException> {
                client.newCall(Request.Builder().url("https://127.0.0.1:${tls.server.address.port}").build()).execute().close()
            }
            val denied = MacKeychainTrustManager({ _, _ -> false }, { tls.trustManager.acceptedIssuers })
            assertFailsWith<SSLHandshakeException> {
                guardedHttpClient(policy = localPolicy(), tlsTrust = denied).build()
                    .newCall(Request.Builder().url(tls.url).build()).execute().close()
            }
        }
    }
}
