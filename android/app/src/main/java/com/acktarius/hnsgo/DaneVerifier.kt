package com.acktarius.hnsgo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.ARecord
import org.xbill.DNS.Section
import org.xbill.DNS.TLSARecord
import org.xbill.DNS.Type
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * DANE (DNS-Based Authentication of Named Entities) Verifier
 * 
 * Verifies that a server's TLS certificate matches TLSA records published in DNS.
 * This provides trustless certificate verification for Handshake domains.
 */
object DaneVerifier {
    private const val DEFAULT_HTTPS_PORT = 443
    private const val CONNECTION_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 10000
    
    /**
     * DANE verification result
     */
    data class VerificationResult(
        val isValid: Boolean,
        val message: String,
        val tlsaFound: Boolean = false,
        val certificateFound: Boolean = false
    )
    
    /**
     * Verify DANE for a given URL
     * 
     * @param urlString URL to verify (e.g., "https://nathan.woodbur/")
     * @return VerificationResult indicating if DANE check passed
     */
    suspend fun verify(urlString: String): VerificationResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val host = url.host
            val port = url.port.takeIf { it != -1 } ?: DEFAULT_HTTPS_PORT
            
            if (port != DEFAULT_HTTPS_PORT) {
                return@withContext VerificationResult(
                    isValid = false,
                    message = "DANE verification only supported for HTTPS (port 443)",
                    tlsaFound = false,
                    certificateFound = false
                )
            }
            
            
            // Step 1: Resolve TLSA record
            val tlsaRecord = resolveTLSA(host, port)
            if (tlsaRecord == null) {
                return@withContext VerificationResult(
                    isValid = false,
                    message = "DANE status: cannot verify this certificate against TLSA. Browse at your own risk.",
                    tlsaFound = false,
                    certificateFound = false
                )
            }
            
            // Log TLSA record found (details logged in verification)
            
            // Step 2: Get server certificate
            val serverCert = getServerCertificate(host, port)
            if (serverCert == null) {
                return@withContext VerificationResult(
                    isValid = false,
                    message = "DANE status: cannot verify this certificate against TLSA. Browse at your own risk.",
                    tlsaFound = true,
                    certificateFound = false
                )
            }
            
            
            // Step 3: Verify certificate matches TLSA
            val matches = verifyCertificateMatchesTLSA(serverCert, tlsaRecord)
            
            if (matches) {
                VerificationResult(
                    isValid = true,
                    message = "DANE status: ✓ Certificate matches TLSA record. Safe to open in Firefox.\n\nNote: Firefox will show a certificate warning (this is normal for Handshake sites). Click 'Advanced' → 'Accept the Risk' - Firefox will remember your choice.",
                    tlsaFound = true,
                    certificateFound = true
                )
            } else {
                VerificationResult(
                    isValid = false,
                    message = "DANE status: ✗ Certificate does NOT match TLSA record. Do not proceed.",
                    tlsaFound = true,
                    certificateFound = true
                )
            }
            
        } catch (e: Exception) {
            VerificationResult(
                isValid = false,
                message = "DANE status: cannot verify this certificate against TLSA. Browse at your own risk.",
                tlsaFound = false,
                certificateFound = false
            )
        }
    }
    
    /**
     * Resolve TLSA record for a domain and port
     * Format: _port._tcp.domain (e.g., _443._tcp.nathan.woodbur)
     */
    private suspend fun resolveTLSA(host: String, port: Int): TLSARecord? = withContext(Dispatchers.IO) {
        try {
            val tlsaName = "_$port._tcp.$host"
            
            // Use RecursiveResolver to resolve TLSA record via Handshake
            // TLSA is DNS record type 52 (RFC 6698)
            val dnsMessage = RecursiveResolver.resolve(tlsaName, 52)
            if (dnsMessage == null) {
                return@withContext null
            }
            
            val answers = dnsMessage.getSection(Section.ANSWER)
            val tlsaRecords = answers.filterIsInstance<TLSARecord>()
            
            if (tlsaRecords.isEmpty()) {
                return@withContext null
            }
            
            // Return first TLSA record (RFC 6698 allows multiple, but we use first)
            val tlsa = tlsaRecords[0]
            // Log TLSA record found
            return@withContext tlsa
            
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get server certificate by connecting to HTTPS endpoint
     * For Handshake domains, resolves the domain to IP first using our resolver
     */
    private suspend fun getServerCertificate(host: String, port: Int): X509Certificate? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Resolve hostname to IP address (for Handshake domains, use our resolver)
            val ipAddress = resolveHostToIP(host)
            if (ipAddress == null) {
                return@withContext null
            }
            
            
            // Create a trust-all manager for fetching certificate (we verify via DANE)
            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustAllManager as TrustManager), null)
            
            // Connect to IP address directly, but set Host header for SNI
            val url = URL("https://$ipAddress:$port")
            val connection = url.openConnection() as HttpsURLConnection
            connection.sslSocketFactory = sslContext.socketFactory
            // Set Host header for SNI (Server Name Indication) - critical for correct certificate
            connection.setRequestProperty("Host", host)
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                // Verify hostname matches certificate (but we trust all certs for DANE verification)
                val peerHost = session.peerHost
                true // Accept any hostname since we verify via DANE
            }
            connection.connectTimeout = CONNECTION_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            
            // Trigger SSL handshake by attempting to read response
            // This is necessary because connect() doesn't always trigger the handshake
            try {
                val responseCode = connection.responseCode
            } catch (e: Exception) {
                // Even if response fails, we might have the certificate from the handshake
            }
            
            // Get certificate from the SSL session (available after handshake)
            val certificates = connection.serverCertificates
            if (certificates.isNotEmpty() && certificates[0] is X509Certificate) {
                val cert = certificates[0] as X509Certificate
                return@withContext cert
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Resolve hostname to IP address, using Handshake resolver for Handshake domains
     */
    private suspend fun resolveHostToIP(host: String): String? = withContext(Dispatchers.IO) {
        try {
            // Try Handshake resolution first
            val dnsMessage = RecursiveResolver.resolve(host, Type.A)
            if (dnsMessage != null) {
                val answers = dnsMessage.getSection(Section.ANSWER)
                val aRecords = answers.filterIsInstance<ARecord>()
                if (aRecords.isNotEmpty()) {
                    val ip = aRecords[0].address.hostAddress
                    return@withContext ip
                }
            }
            
            // Fallback to system DNS for regular domains
            try {
                val addresses = java.net.InetAddress.getAllByName(host)
                if (addresses.isNotEmpty()) {
                    // Prefer IPv4
                    val ipv4 = addresses.find { it is java.net.Inet4Address }
                    if (ipv4 != null) {
                        return@withContext ipv4.hostAddress
                    }
                    // Fallback to first address
                    return@withContext addresses[0].hostAddress
                }
            } catch (e: java.net.UnknownHostException) {
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Verify that server certificate matches TLSA record
     * 
     * Supports:
     * - Usage 3 (DANE-EE): Certificate usage (end entity)
     * - Selector 0: Full certificate
     * - Selector 1: SubjectPublicKeyInfo
     * - MatchType 0: No hash (full data)
     * - MatchType 1: SHA-256 hash
     * - MatchType 2: SHA-512 hash
     */
    private fun verifyCertificateMatchesTLSA(cert: X509Certificate, tlsa: TLSARecord): Boolean {
        try {
            // Parse TLSA record from wire format
            // TLSA format: [1 byte usage][1 byte selector][1 byte matchType][variable data]
            // Get the rdata portion from the wire format
            val wireData = tlsa.toWire(Section.ANSWER)
            // Wire format: [name][type(2)][class(2)][ttl(4)][rdlength(2)][rdata...]
            // Skip to rdata section
            val nameBytes = tlsa.name.toWireCanonical()
            val headerLen = nameBytes.size + 10 // name + type(2) + class(2) + ttl(4) + rdlength(2)
            
            if (wireData.size < headerLen + 2) {
                return false
            }
            
            val rdlength = ((wireData[headerLen - 2].toInt() and 0xFF) shl 8) or (wireData[headerLen - 1].toInt() and 0xFF)
            if (wireData.size < headerLen + rdlength || rdlength < 3) {
                return false
            }
            
            val rdata = wireData.sliceArray(headerLen until headerLen + rdlength)
            
            if (rdata.size < 3) {
                return false
            }
            
            val usage = rdata[0].toInt() and 0xFF
            val selector = rdata[1].toInt() and 0xFF
            val matchType = rdata[2].toInt() and 0xFF
            val tlsaData = rdata.sliceArray(3 until rdata.size)
            
            
            // Only support Usage 3 (DANE-EE: Domain-issued certificate)
            if (usage != 3) {
                return false
            }
            
            // Get data to match based on selector
            val certData = when (selector) {
                0 -> {
                    // Full certificate
                    cert.encoded
                }
                1 -> {
                    // SubjectPublicKeyInfo
                    cert.publicKey.encoded
                }
                else -> {
                    return false
                }
            }
            
            // Compare based on match type
            val matches = when (matchType) {
                0 -> {
                    // No hash - compare full data
                    java.util.Arrays.equals(certData, tlsaData)
                }
                1 -> {
                    // SHA-256 hash
                    val sha256 = MessageDigest.getInstance("SHA-256")
                    val certHash = sha256.digest(certData)
                    java.util.Arrays.equals(certHash, tlsaData)
                }
                2 -> {
                    // SHA-512 hash
                    val sha512 = MessageDigest.getInstance("SHA-512")
                    val certHash = sha512.digest(certData)
                    java.util.Arrays.equals(certHash, tlsaData)
                }
                else -> {
                    false
                }
            }
            return matches
            
        } catch (e: Exception) {
            return false
        }
    }
}

