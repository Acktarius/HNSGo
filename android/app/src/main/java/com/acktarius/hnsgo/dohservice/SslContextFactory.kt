package com.acktarius.hnsgo.dohservice

import android.util.Log
import com.acktarius.hnsgo.CertHelper
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Factory for creating SSL contexts for DoH/DoT servers
 */
object SslContextFactory {
    private const val SERVER_ALIAS = "localhost"
    
    /**
     * Create SSL context for DoH server with detailed logging
     */
    fun createDoHSSLContext(): SSLContext {
        val keyStore = CertHelper.generateKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "changeit".toCharArray())
        
        // Log certificate info for debugging
        logCertificateInfo(keyStore)
        
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
        return sslContext
    }
    
    /**
     * Create SSL context for DoT server (simpler, no logging)
     */
    fun createDoTSSLContext(): SSLContext {
        val keyStore = CertHelper.generateKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "changeit".toCharArray())
        
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
        return sslContext
    }
    
    private fun logCertificateInfo(keyStore: KeyStore) {
        try {
            val certChain = keyStore.getCertificateChain(SERVER_ALIAS)
            if (certChain != null && certChain.isNotEmpty()) {
                val serverCert = certChain[0] as java.security.cert.X509Certificate
                
                // Log Subject Alternative Names
                try {
                    val sanExtension = serverCert.getExtensionValue("2.5.29.17") // SAN OID
                    if (sanExtension != null) {
                    } else {
                    }
                } catch (e: Exception) {
                }
                
                if (certChain.size > 1) {
                    val caCert = certChain[1] as java.security.cert.X509Certificate
                    // Verify CA certificate has proper extensions
                    try {
                        val basicConstraints = caCert.getExtensionValue("2.5.29.19") // BasicConstraints OID
                        val keyUsage = caCert.getExtensionValue("2.5.29.15") // KeyUsage OID
                        
                        // Verify CA cert in chain matches the one from CertHelper (for debugging)
                        try {
                            val expectedCA = CertHelper.getCACertificate()
                            val caCertEncoded = caCert.encoded
                            val expectedCAEncoded = expectedCA.encoded
                            val matches = java.util.Arrays.equals(caCertEncoded, expectedCAEncoded)
                            if (!matches) {
                            }
                        } catch (e: Exception) {
                        }
                    } catch (e: Exception) {
                    }
                } else {
                }
            } else {
            }
        } catch (e: Exception) {
        }
    }
}

