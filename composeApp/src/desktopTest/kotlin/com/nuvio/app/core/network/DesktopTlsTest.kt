package com.nuvio.app.core.network

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import kotlin.test.*

class DesktopTlsTest {
    private fun certificate(): X509Certificate = java.nio.file.Files.newInputStream(
        java.nio.file.Path.of("src/androidMain/res/raw/isrg_root_x1.pem")
    ).use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    @Test fun enginePassesHostnameToSystemTrustForScopedInspectionCa() {
        var evaluated = ""
        val manager = MacTrustManager { _, host -> evaluated = host }
        manager.checkServerTrusted(arrayOf(certificate()), "RSA", SSLContext.getDefault().createSSLEngine("media.example", 443))
        assertEquals("media.example", evaluated)
    }

    @Test fun explicitSystemDistrustCannotFallBackToJvmRoots() {
        val manager = MacTrustManager { _, _ -> throw CertificateException("denied") }
        assertFailsWith<CertificateException> {
            manager.checkServerTrusted(arrayOf(certificate()), "RSA", SSLContext.getDefault().createSSLEngine("media.example", 443))
        }
    }
}
