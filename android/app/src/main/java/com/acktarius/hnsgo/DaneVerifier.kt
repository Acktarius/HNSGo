package com.acktarius.hnsgo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.acktarius.hnsgo.SpvClient
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.ARecord
import org.xbill.DNS.Section
import org.xbill.DNS.TLSARecord
import org.xbill.DNS.Type
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIServerName
import javax.net.ssl.SNIHostName
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
     * DANE inspection result with detailed information
     */
    data class VerificationResult(
        val isValid: Boolean,
        val message: String,
        val tlsaFound: Boolean = false,
        val certificateFound: Boolean = false,
        // Enhanced inspection details
        val status: DANEStatus = DANEStatus.ERROR,
        val matchedRecord: MatchedTLSA? = null,
        val certInfo: CertificateInfo? = null,
        val securityNotes: List<String> = emptyList()
    ) {
        enum class DANEStatus {
            DANE_OK,           // Valid DANE match
            DANE_MISMATCH,     // TLSA present but doesn't match
            NO_TLSA,           // No TLSA record found
            ERROR              // Error during inspection
        }
        
        data class MatchedTLSA(
            val usage: Int,           // 2 (DANE-TA) or 3 (DANE-EE)
            val selector: Int,         // 0 (full cert) or 1 (SPKI)
            val matchingType: Int,     // 0 (exact), 1 (SHA-256), 2 (SHA-512)
            val index: Int            // Index in TLSA record list
        )
        
        data class CertificateInfo(
            val subject: String,
            val issuer: String,
            val spkiFingerprint: String,  // SHA-256 of SubjectPublicKeyInfo
            val validFrom: String,
            val validTo: String,
            val isSelfSigned: Boolean
        )
    }
    
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
            
            
            // Step 1: Resolve all TLSA records
            val tlsaRecords = resolveAllTLSA(host, port)
            if (tlsaRecords.isEmpty()) {
                return@withContext VerificationResult(
                    isValid = false,
                    message = "DANE status: No TLSA record found. Cannot verify certificate.",
                    tlsaFound = false,
                    certificateFound = false,
                    status = VerificationResult.DANEStatus.NO_TLSA
                )
            }
            
            // Step 2: Get full certificate chain
            val certChain = getServerCertificateChain(host, port)
            if (certChain.isEmpty()) {
                return@withContext VerificationResult(
                    isValid = false,
                    message = "DANE status: Cannot fetch server certificate chain.",
                    tlsaFound = true,
                    certificateFound = false,
                    status = VerificationResult.DANEStatus.ERROR
                )
            }
            
            val endEntityCert = certChain[0]
            val certInfo = extractCertificateInfo(endEntityCert)
            
            // Step 3: Verify certificate chain against all TLSA records
            val matchResult = verifyCertificateChainMatchesTLSA(certChain, tlsaRecords)
            
            val securityNotes = mutableListOf<String>()
            if (certInfo.isSelfSigned) {
                securityNotes.add("Certificate is self-signed (DANE-EE with self-signed cert, DNSSEC-validated)")
            }
            if (matchResult.matchedRecord != null) {
                securityNotes.add("TLSA record ${matchResult.matchedRecord.index + 1} matched (Usage ${matchResult.matchedRecord.usage}, Selector ${matchResult.matchedRecord.selector}, MatchType ${matchResult.matchedRecord.matchingType})")
            } else {
                securityNotes.add("TLSA present but does not match current certificate chain")
            }
            
            if (matchResult.isValid) {
                VerificationResult(
                    isValid = true,
                    message = "DANE status: ✓ Certificate matches TLSA record. Safe to open in Firefox.\n\nNote: Firefox will show a certificate warning, click 'Advanced' → 'Accept the Risk'",
                    tlsaFound = true,
                    certificateFound = true,
                    status = VerificationResult.DANEStatus.DANE_OK,
                    matchedRecord = matchResult.matchedRecord,
                    certInfo = certInfo,
                    securityNotes = securityNotes
                )
            } else {
                VerificationResult(
                    isValid = false,
                    message = "DANE status: ✗ Certificate does NOT match TLSA record. Do not proceed.",
                    tlsaFound = true,
                    certificateFound = true,
                    status = VerificationResult.DANEStatus.DANE_MISMATCH,
                    certInfo = certInfo,
                    securityNotes = securityNotes
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
     * Resolve all TLSA records for a domain and port
     * Format: _port._tcp.domain (e.g., _443._tcp.nathan.woodbur)
     * Checks cache first before querying blockchain
     */
    private suspend fun resolveAllTLSA(host: String, port: Int): List<TLSARecord> = withContext(Dispatchers.IO) {
        try {
            val tlsaName = "_$port._tcp.$host"
            val tlsaType = Type.TLSA  // 52 (RFC 6698)
            val tlsaClass = DClass.IN
            
            // Step 1: Check cache first
            val currentHeight = SpvClient.getChainHeight()
            val cached = CacheManager.get(tlsaName, tlsaType, tlsaClass, currentHeight)
            if (cached != null) {
                try {
                    val cachedMessage = Message(cached)
                    val answers = cachedMessage.getSection(Section.ANSWER)
                    val tlsaRecords = answers.filterIsInstance<TLSARecord>()
                    if (tlsaRecords.isNotEmpty()) {
                        return@withContext tlsaRecords
                    }
                } catch (e: Exception) {
                    // Corrupted cache entry, remove it and continue
                    CacheManager.remove(tlsaName, tlsaType, tlsaClass)
                }
            }
            
            // Step 2: Cache miss - resolve from blockchain via RecursiveResolver
            val dnsMessage = RecursiveResolver.resolve(tlsaName, tlsaType)
            if (dnsMessage == null) {
                return@withContext emptyList()
            }
            
            val answers = dnsMessage.getSection(Section.ANSWER)
            val tlsaRecords = answers.filterIsInstance<TLSARecord>()
            
            // Step 3: Cache the result if we got TLSA records
            if (tlsaRecords.isNotEmpty()) {
                val ttl = answers.minOfOrNull { it.ttl }?.toInt() ?: Config.DNS_CACHE_TTL_SECONDS
                val wireData = dnsMessage.toWire()
                val currentHeight = SpvClient.getChainHeight()
                CacheManager.put(tlsaName, tlsaType, tlsaClass, wireData, ttl, currentHeight)
                
                // Log TLSA records for debugging
                tlsaRecords.forEachIndexed { index, tlsa ->
                    val parsed = parseTLSA(tlsa)
                    if (parsed != null) {
                        val hashHex = parsed.data.joinToString("") { "%02x".format(it) }
                    }
                }
            }
            
            return@withContext tlsaRecords
            
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get full server certificate chain by connecting to HTTPS endpoint
     * For Handshake domains, resolves the domain to IP first using our resolver
     */
    private suspend fun getServerCertificateChain(host: String, port: Int): List<X509Certificate> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Resolve hostname to IP address (for Handshake domains, use our resolver)
            val ipAddress = resolveHostToIP(host)
            if (ipAddress == null) {
                return@withContext emptyList()
            }
            
            
            // Create a trust-all manager for fetching certificate (we verify via DANE)
            val trustAllManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            
            // Create SSL context with SNI support
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustAllManager as TrustManager), null)
            
            // Use SSLSocket directly to properly set SNI (Server Name Indication)
            // HttpsURLConnection doesn't always set SNI correctly when using IP address
            val socketFactory = sslContext.socketFactory
            val socket = socketFactory.createSocket(ipAddress, port) as javax.net.ssl.SSLSocket
            
            // Set SNI hostname - this is critical for getting the correct certificate
            val sslParams = socket.sslParameters
            val sniHostNames = listOf(javax.net.ssl.SNIHostName(host))
            sslParams.serverNames = sniHostNames
            socket.sslParameters = sslParams
            
            // Start handshake
            socket.startHandshake()
            
            // Get certificate chain from the SSL session
            val session = socket.session
            val certChain = session.peerCertificates.mapNotNull { it as? X509Certificate }
            
            socket.close()
            
            if (certChain.isEmpty()) {
            } else {
                
                // Log certificate details for debugging
                certChain.forEachIndexed { index, cert ->
                    val subject = cert.subjectX500Principal.name
                    val issuer = cert.issuerX500Principal.name
                    
                    // Calculate SPKI fingerprint (SHA-256 of SubjectPublicKeyInfo)
                    val spkiBytes = cert.publicKey.encoded
                    val sha256 = MessageDigest.getInstance("SHA-256")
                    val spkiHash = sha256.digest(spkiBytes)
                    val spkiFingerprint = spkiHash.joinToString("") { "%02x".format(it) }
                    
                    // Calculate full cert fingerprint
                    val certHash = sha256.digest(cert.encoded)
                    val certFingerprint = certHash.joinToString("") { "%02x".format(it) }
                    
                }
            }
            
            return@withContext certChain
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Resolve hostname to IP address, using Handshake resolver for Handshake domains
     * Checks cache first before querying blockchain
     */
    private suspend fun resolveHostToIP(host: String): String? = withContext(Dispatchers.IO) {
        try {
            val aType = Type.A
            val aClass = DClass.IN
            
            // Step 1: Check cache first
            val currentHeight = SpvClient.getChainHeight()
            val cached = CacheManager.get(host, aType, aClass, currentHeight)
            if (cached != null) {
                try {
                    val cachedMessage = Message(cached)
                    val answers = cachedMessage.getSection(Section.ANSWER)
                    val aRecords = answers.filterIsInstance<ARecord>()
                    if (aRecords.isNotEmpty()) {
                        val ip = aRecords[0].address.hostAddress
                        return@withContext ip
                    }
                } catch (e: Exception) {
                    // Corrupted cache entry, remove it and continue
                    CacheManager.remove(host, aType, aClass)
                }
            }
            
            // Step 2: Cache miss - resolve from blockchain
            val dnsMessage = RecursiveResolver.resolve(host, aType)
            if (dnsMessage != null) {
                val answers = dnsMessage.getSection(Section.ANSWER)
                val aRecords = answers.filterIsInstance<ARecord>()
                if (aRecords.isNotEmpty()) {
                    val ip = aRecords[0].address.hostAddress
                    
                    // Step 3: Cache the result
                    val ttl = answers.minOfOrNull { it.ttl }?.toInt() ?: Config.DNS_CACHE_TTL_SECONDS
                    CacheManager.put(host, aType, aClass, dnsMessage.toWire(), ttl, currentHeight)
                    
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
     * Verify that certificate chain matches any TLSA record
     * 
     * Supports:
     * - Usage 2 (DANE-TA): Trust anchor (CA certificate in chain)
     * - Usage 3 (DANE-EE): Certificate usage (end entity)
     * - Selector 0: Full certificate
     * - Selector 1: SubjectPublicKeyInfo
     * - MatchType 0: No hash (full data)
     * - MatchType 1: SHA-256 hash
     * - MatchType 2: SHA-512 hash
     */
    private fun verifyCertificateChainMatchesTLSA(
        certChain: List<X509Certificate>,
        tlsaRecords: List<TLSARecord>
    ): MatchResult {
        if (certChain.isEmpty() || tlsaRecords.isEmpty()) {
            return MatchResult(false, null)
        }
        
        val endEntityCert = certChain[0]
        
        // Check each TLSA record against the certificate chain
        for ((index, tlsa) in tlsaRecords.withIndex()) {
            val parsed = parseTLSA(tlsa)
            if (parsed == null) {
                continue
            }
            
            val tlsaHashHex = parsed.data.joinToString("") { "%02x".format(it) }
            
            // Determine which certificate to check based on usage
            val certToCheck = when (parsed.usage) {
                2 -> {
                    // DANE-TA: Check CA certificates in chain (skip end entity)
                    certChain.drop(1).firstOrNull()
                }
                3 -> {
                    // DANE-EE: Check end entity certificate
                    endEntityCert
                }
                else -> {
                    // Unsupported usage
                    continue
                }
            }
            
            if (certToCheck == null) {
                continue
            }
            
            // Calculate what we're comparing
            val certData = when (parsed.selector) {
                0 -> certToCheck.encoded  // Full certificate
                1 -> certToCheck.publicKey.encoded  // SubjectPublicKeyInfo
                else -> {
                    continue
                }
            }
            
            val sha256 = MessageDigest.getInstance("SHA-256")
            val certHash = when (parsed.matchingType) {
                0 -> certData  // Exact match
                1 -> sha256.digest(certData)  // SHA-256
                2 -> MessageDigest.getInstance("SHA-512").digest(certData)  // SHA-512
                else -> {
                    continue
                }
            }
            val certHashHex = certHash.joinToString("") { "%02x".format(it) }
            
            // Verify match
            val matches = verifyCertificateMatchesTLSA(certToCheck, parsed)
            
            if (matches) {
                return MatchResult(
                    true,
                    VerificationResult.MatchedTLSA(
                        usage = parsed.usage,
                        selector = parsed.selector,
                        matchingType = parsed.matchingType,
                        index = index
                    )
                )
            }
        }
        
        return MatchResult(false, null)
    }
    
    /**
     * Parsed TLSA record data
     */
    private data class ParsedTLSA(
        val usage: Int,
        val selector: Int,
        val matchingType: Int,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ParsedTLSA
            if (usage != other.usage) return false
            if (selector != other.selector) return false
            if (matchingType != other.matchingType) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }
        
        override fun hashCode(): Int {
            var result = usage
            result = 31 * result + selector
            result = 31 * result + matchingType
            result = 31 * result + data.contentHashCode()
            return result
        }
    }
    
    /**
     * Match result from verification
     */
    private data class MatchResult(
        val isValid: Boolean,
        val matchedRecord: VerificationResult.MatchedTLSA?
    )
    
    /**
     * Parse TLSA record from wire format
     */
    private fun parseTLSA(tlsa: TLSARecord): ParsedTLSA? {
        try {
            val wireData = tlsa.toWire(Section.ANSWER)
            val nameBytes = tlsa.name.toWireCanonical()
            val headerLen = nameBytes.size + 10
            
            if (wireData.size < headerLen + 2) {
                return null
            }
            
            val rdlength = ((wireData[headerLen - 2].toInt() and 0xFF) shl 8) or (wireData[headerLen - 1].toInt() and 0xFF)
            if (wireData.size < headerLen + rdlength || rdlength < 3) {
                return null
            }
            
            val rdata = wireData.sliceArray(headerLen until headerLen + rdlength)
            if (rdata.size < 3) {
                return null
            }
            
            val usage = rdata[0].toInt() and 0xFF
            val selector = rdata[1].toInt() and 0xFF
            val matchType = rdata[2].toInt() and 0xFF
            val tlsaData = rdata.sliceArray(3 until rdata.size)
            
            return ParsedTLSA(usage, selector, matchType, tlsaData)
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Verify that a certificate matches a parsed TLSA record
     */
    private fun verifyCertificateMatchesTLSA(cert: X509Certificate, parsed: ParsedTLSA): Boolean {
        try {
            // Get data to match based on selector
            val certData = when (parsed.selector) {
                0 -> cert.encoded  // Full certificate
                1 -> cert.publicKey.encoded  // SubjectPublicKeyInfo
                else -> return false
            }
            
            // Compare based on match type
            return when (parsed.matchingType) {
                0 -> java.util.Arrays.equals(certData, parsed.data)  // Exact match
                1 -> {
                    val sha256 = MessageDigest.getInstance("SHA-256")
                    val certHash = sha256.digest(certData)
                    java.util.Arrays.equals(certHash, parsed.data)
                }
                2 -> {
                    val sha512 = MessageDigest.getInstance("SHA-512")
                    val certHash = sha512.digest(certData)
                    java.util.Arrays.equals(certHash, parsed.data)
                }
                else -> false
            }
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Extract certificate information for inspection report
     */
    private fun extractCertificateInfo(cert: X509Certificate): VerificationResult.CertificateInfo {
        val subject = cert.subjectX500Principal.name
        val issuer = cert.issuerX500Principal.name
        val isSelfSigned = subject == issuer
        
        // Calculate SPKI fingerprint (SHA-256 of SubjectPublicKeyInfo)
        // This matches OpenSSL: openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256
        val spkiBytes = cert.publicKey.encoded
        val sha256 = MessageDigest.getInstance("SHA-256")
        val spkiHash = sha256.digest(spkiBytes)
        val spkiFingerprint = spkiHash.joinToString("") { "%02x".format(it) }  // Lowercase hex, no colons (matches OpenSSL format)
        
        val validFrom = cert.notBefore.toString()
        val validTo = cert.notAfter.toString()
        
        return VerificationResult.CertificateInfo(
            subject = subject,
            issuer = issuer,
            spkiFingerprint = spkiFingerprint,
            validFrom = validFrom,
            validTo = validTo,
            isSelfSigned = isSelfSigned
        )
    }
}

