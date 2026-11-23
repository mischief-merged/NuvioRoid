package com.nuvio.app.core.network

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class LocalTlsServer : Closeable {
    val directory = Files.createTempDirectory("network-tls-test")
    val certFile = directory.resolve("root.pem")
    val leafFile = directory.resolve("server.pem")
    val context: SSLContext
    val trustManager: X509TrustManager
    val server: HttpsServer
    val url: String get() = "https://localhost:${server.address.port}"
    init {
        val password = "test-only-password".toCharArray()
        val keyFile = directory.resolve("server.p12")
        val rootFile = directory.resolve("root.p12")
        val requestFile = directory.resolve("server.csr")
        fun keytool(vararg arguments: String) {
            val log = Files.createTempFile(directory, "keytool-", ".log")
            val command = listOf("${System.getProperty("java.home")}/bin/keytool") + arguments + listOf("-storepass", String(password), "-noprompt")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), Files.readString(log))
        }
        keytool("-genkeypair", "-alias", "root", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=Local test root", "-ext", "BC:critical=ca:true", "-ext", "KU:critical=keyCertSign,cRLSign", "-storetype", "PKCS12", "-keystore", rootFile.toString())
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-storetype", "PKCS12", "-keystore", keyFile.toString())
        keytool("-certreq", "-alias", "server", "-keystore", keyFile.toString(), "-file", requestFile.toString())
        keytool("-gencert", "-alias", "root", "-keystore", rootFile.toString(), "-infile", requestFile.toString(), "-outfile", leafFile.toString(), "-rfc", "-validity", "2", "-ext", "SAN=dns:localhost", "-ext", "EKU=serverAuth", "-ext", "KU:critical=digitalSignature,keyEncipherment", "-ext", "BC:critical=ca:false")
        keytool("-exportcert", "-alias", "root", "-keystore", rootFile.toString(), "-rfc", "-file", certFile.toString())
        keytool("-importcert", "-alias", "root", "-keystore", keyFile.toString(), "-file", certFile.toString())
        keytool("-importcert", "-alias", "server", "-keystore", keyFile.toString(), "-file", leafFile.toString())
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keyFile).use { load(it, password) } }
        val roots = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(rootFile).use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        trustManager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
        context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(context)
            start()
        }
    }
    override fun close() { server.stop(0) }
}
