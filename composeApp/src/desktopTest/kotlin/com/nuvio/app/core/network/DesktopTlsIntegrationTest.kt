package com.nuvio.app.core.network

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopTlsIntegrationTest {
    private fun fixture(expired: Boolean = false, test: (SSLContext, X509Certificate) -> Unit) {
        val directory = Files.createTempDirectory("tls-test")
        try {
            val file = directory.resolve("server.p12")
            val args = mutableListOf(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-ext", "SAN=dns:localhost", "-ext", "EKU=serverAuth", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", "test-only", "-keypass", "test-only", "-noprompt")
            if (expired) args.addAll(listOf("-startdate", "-4d"))
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(file).use { load(it, "test-only".toCharArray()) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "test-only".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            test(context, keys.getCertificate("server") as X509Certificate)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun nativeTrustChecksHostnameAndRejectsUntrustedCertificates() = fixture { _, certificate ->
        val evaluator = MacCertificateEvaluator(listOf(certificate.encoded))
        evaluator.check(arrayOf(certificate), "localhost")
        assertFailsWith<CertificateException> { evaluator.check(arrayOf(certificate), "wrong.example") }
        assertFailsWith<CertificateException> { MacCertificateEvaluator().check(arrayOf(certificate), "localhost") }
    }

    @Test fun expiredCertificateRemainsRejectedWithATrustedAnchor() = fixture(expired = true) { _, certificate ->
        val manager = MacTrustManager(MacCertificateEvaluator(listOf(certificate.encoded))::check)
        assertFailsWith<CertificateException> {
            manager.checkServerTrusted(arrayOf(certificate), "RSA", SSLContext.getDefault().createSSLEngine("localhost", 443))
        }
    }

    @Test fun javaOkHttpAndKtorClientsUseHostScopedSystemEvaluation() = fixture { context, certificate ->
        val evaluator = MacCertificateEvaluator(listOf(certificate.encoded))
        val hosts = mutableListOf<String>()
        val manager = MacTrustManager { chain, host ->
            synchronized(hosts) { hosts.add(host) }
            if (host != "localhost") throw CertificateException("Outside trust scope")
            evaluator.check(chain, host)
        }
        val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(context)
            createContext("/") { exchange ->
                val body = "connected".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val url = "https://localhost:${server.address.port}/"
        val previousContext = SSLContext.getDefault()
        val previousFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            DesktopTls.install(manager)
            assertEquals("connected", URI(url).toURL().openStream().bufferedReader().use { it.readText() })
            val javaClient = java.net.http.HttpClient.newBuilder().sslContext(DesktopTls.context(manager)).build()
            val result = javaClient.send(java.net.http.HttpRequest.newBuilder(URI(url)).build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            assertEquals("connected", result.body())
            val okhttp = DesktopTls.clientBuilder(manager).build()
            try {
                okhttp.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { assertEquals("connected", it.body!!.string()) }
            } finally { okhttp.connectionPool.evictAll(); okhttp.dispatcher.executorService.shutdown() }
            runBlocking {
                val ktor = HttpClient(desktopHttpEngine(manager))
                try { assertEquals("connected", ktor.get(url).bodyAsText()) }
                finally { ktor.close() }
            }
            assertTrue(hosts.size >= 4)
            assertTrue(hosts.all { it == "localhost" })
        } finally {
            SSLContext.setDefault(previousContext)
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory)
            server.stop(0)
        }
    }
}
