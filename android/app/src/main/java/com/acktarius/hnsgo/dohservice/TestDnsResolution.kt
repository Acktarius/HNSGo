package com.acktarius.hnsgo.dohservice

import android.content.Context
import android.content.Intent
import android.util.Log
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.SpvClient
import com.acktarius.hnsgo.CertHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/**
 * Handles DNS resolution testing functionality
 * Simulates a 'dig' command by making an actual DoH query to our own server
 */
object TestDnsResolution {
    private const val MIN_REQUIRED_HEIGHT_OFFSET = 1000
    private const val SYNC_THRESHOLD_BLOCKS = 10
    private const val SYNC_CHECK_DELAY_MS = 5000L
    private const val SYNC_CHECK_INTERVAL_SECONDS = 5
    private const val MAX_SYNC_WAIT_MINUTES = 5
    private const val MAX_SYNC_WAIT_ATTEMPTS = (MAX_SYNC_WAIT_MINUTES * 60) / SYNC_CHECK_INTERVAL_SECONDS

    /**
     * Test DNS resolution after servers are ready
     * This tests the full stack: DoH request → RecursiveResolver → SpvClient → blockchain
     */
    suspend fun testDnsResolution(context: Context, packageName: String) = withContext(Dispatchers.IO) {
        try {
            
            // Wait for sync to complete before testing
            val minRequiredHeight = Config.CHECKPOINT_HEIGHT + MIN_REQUIRED_HEIGHT_OFFSET
            var currentHeight = SpvClient.getChainHeight()
            var networkHeight = SpvClient.getNetworkHeight()
            
            // Check if we have enough headers
            if (currentHeight < minRequiredHeight) {
                val status = "⚠ Sync incomplete (height: $currentHeight, need: $minRequiredHeight)"
                val result = "Header sync still in progress. Resolution may not work yet."
                sendDebugResult(context, packageName, status, result)
                return@withContext
            }
            
            // Wait for sync to complete (within SYNC_THRESHOLD_BLOCKS blocks of network tip)
            var attempts = 0
            while (attempts < MAX_SYNC_WAIT_ATTEMPTS) {
                currentHeight = SpvClient.getChainHeight()
                networkHeight = SpvClient.getNetworkHeight()
                
                if (networkHeight != null) {
                    val behind = networkHeight - currentHeight
                    // CRITICAL: Match HeaderSync logic - allow being 2 blocks ahead (network propagation delays)
                    val isCaughtUp = behind >= -2 && behind <= SYNC_THRESHOLD_BLOCKS
                    
                    if (isCaughtUp) {
                        break
                    } else {
                        sendDebugResult(context, packageName, "Waiting for sync... ($currentHeight / $networkHeight)", null)
                        delay(SYNC_CHECK_DELAY_MS)
                        attempts++
                    }
                } else {
                    // Network height not available yet, wait a bit
                    sendDebugResult(context, packageName, "Waiting for network height...", null)
                    delay(SYNC_CHECK_DELAY_MS)
                    attempts++
                }
            }
            
            // Final check - only test if we're synced
            currentHeight = SpvClient.getChainHeight()
            networkHeight = SpvClient.getNetworkHeight()
            
            if (networkHeight != null) {
                val behind = networkHeight - currentHeight
                // CRITICAL: Match HeaderSync logic - allow being 2 blocks ahead (network propagation delays)
                val isCaughtUp = behind >= -2 && behind <= SYNC_THRESHOLD_BLOCKS
                
                if (!isCaughtUp) {
                    val status = "⚠ Still syncing (height: $currentHeight, network: $networkHeight, behind: $behind)"
                    val result = "Waiting for full sync before testing. Resolution may fail if too far behind."
                    sendDebugResult(context, packageName, status, result)
                    return@withContext
                }
            }
            
            // Test resolution by making an actual DoH query (simulating 'dig')
            val testDomain = "website.conceal"
            sendDebugResult(context, packageName, "Testing DNS resolution (dig simulation)...", null)
            
            // Create DNS query message (like 'dig website.conceal A')
            val queryName = Name.fromString("$testDomain.")
            val query = org.xbill.DNS.Message.newQuery(
                Record.newRecord(queryName, Type.A, DClass.IN)
            )
            val queryBytes = query.toWire()
            
            // Encode query in base64url (RFC 8484 DoH GET format)
            val queryB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(queryBytes)
            
            // Make HTTPS GET request to our own DoH server
            val dohUrl = "https://127.0.0.1:${Config.DOH_PORT}/dns-query?dns=$queryB64"
            
            // Create SSL context using our CA certificate (same as DoH server uses)
            // For internal testing, we programmatically trust our CA - no system installation needed
            val caCert = CertHelper.getCACertificate()
            
            // Create a custom TrustManager that trusts certificates signed by our CA
            val trustManager = createTrustManager(caCert)
            
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)
            
            val client = okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true } // Trust localhost (we control both ends)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val request = okhttp3.Request.Builder()
                .url(dohUrl)
                .header("Accept", "application/dns-message")
                .get()
                .build()
            
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                sendDebugResult(context, packageName, "✗ Query failed", "Connection error: ${e.message}")
                return@withContext
            }
            
            try {
                if (response.isSuccessful && response.body != null) {
                    val responseBytes = response.body!!.bytes()
                    val dnsResponse = try {
                        org.xbill.DNS.Message(responseBytes)
                    } catch (e: Exception) {
                        sendDebugResult(context, packageName, "✗ Query failed", "Invalid DNS response")
                        return@withContext
                    }
                    
                    val answers = dnsResponse.getSection(Section.ANSWER)
                    if (answers.isNotEmpty()) {
                        processDnsAnswers(answers, testDomain, context, packageName)
                    } else {
                        val rcode = dnsResponse.rcode
                        if (rcode == Rcode.NXDOMAIN) {
                            sendDebugResult(context, packageName, "✗ Domain not found", "$testDomain -> NXDOMAIN")
                        } else {
                            sendDebugResult(context, packageName, "⚠ No answer", "$testDomain -> RCODE: $rcode")
                        }
                    }
                } else {
                    sendDebugResult(context, packageName, "✗ Query failed", "HTTP ${response.code}: ${response.message}")
                }
            } finally {
                response.close()
            }
        } catch (e: Exception) {
            sendDebugResult(context, packageName, "✗ Query failed", "Error: ${e.message}")
        }
    }

    private fun createTrustManager(caCert: java.security.cert.X509Certificate): javax.net.ssl.X509TrustManager {
        return object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {
                // Not needed for client
            }
            
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty()) {
                    throw java.security.cert.CertificateException("No server certificate chain")
                }
                
                // The server cert (chain[0]) should be signed by our CA
                val serverCert = chain[0]
                
                // First, try to verify the signature - this is the real test
                // If the signature verifies, the certificate is valid regardless of DN ordering
                try {
                    serverCert.verify(caCert.publicKey)
                    return
                } catch (e: Exception) {
                }
                
                // Check if CA cert is in the chain (some servers send full chain)
                val caInChain = chain.any { cert ->
                    try {
                        // Compare by encoded form (most reliable) or by DN string
                        java.util.Arrays.equals(cert.encoded, caCert.encoded) ||
                        cert.subjectX500Principal.name.equals(caCert.subjectX500Principal.name, ignoreCase = true)
                    } catch (e: Exception) {
                        false
                    }
                }
                
                if (caInChain) {
                    // CA is in chain, try to verify server cert with CA from chain
                    try {
                        serverCert.verify(caCert.publicKey)
                        return
                    } catch (e: Exception) {
                    }
                }
                
                // If signature verification failed, check issuer/subject as diagnostic
                val serverIssuer = serverCert.issuerX500Principal
                val caSubject = caCert.subjectX500Principal
                
                // Compare DNs by encoded form (most reliable) or by normalized string
                // X500Principal.equals() should handle different ordering, but if it doesn't,
                // we compare the encoded byte arrays which are canonical
                val issuerMatches = try {
                    // Try encoded form comparison (most reliable)
                    java.util.Arrays.equals(serverIssuer.getEncoded(), caSubject.getEncoded()) ||
                    // Fallback to object equality
                    serverIssuer == caSubject ||
                    // Last resort: case-insensitive string comparison
                    serverIssuer.name.equals(caSubject.name, ignoreCase = true)
                } catch (e: Exception) {
                    // If encoding fails, fall back to string comparison
                    serverIssuer.name.equals(caSubject.name, ignoreCase = true)
                }
                
                if (!issuerMatches) {
                    throw java.security.cert.CertificateException("Server certificate issuer ($serverIssuer) does not match CA subject ($caSubject)")
                }
                
                // If we get here, DN matches but signature verification failed
                throw java.security.cert.CertificateException("Server certificate signature verification failed")
            }
            
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                return arrayOf(caCert)
            }
        }
    }

    private fun processDnsAnswers(
        answers: List<Record>,
        testDomain: String,
        context: Context,
        packageName: String
    ) {
        // Check for A record first (direct IP address)
        val aRecord = answers.find { it is ARecord } as? ARecord
        if (aRecord != null && aRecord.address != null) {
            val ipAddress = aRecord.address.hostAddress
            if (ipAddress != null) {
                val result = "$testDomain -> $ipAddress"
                sendDebugResult(context, packageName, "✓ DNS resolution successful", result)
            } else {
                sendDebugResult(context, packageName, "⚠ No A record", "$testDomain -> A record has null address")
            }
        } else {
            // Check for CNAME, AAAA, or other usable records (browser will follow CNAME)
            val cnameRecord = answers.find { it.type == Type.CNAME }
            val aaaaRecord = answers.find { it.type == Type.AAAA }
            
            when {
                cnameRecord != null -> {
                    // CNAME record - browser will follow it automatically
                    val target = cnameRecord.rdataToString()
                    val result = "$testDomain -> CNAME -> $target"
                    sendDebugResult(context, packageName, "✓ DNS resolution successful (CNAME)", result)
                }
                aaaaRecord != null -> {
                    // AAAA record (IPv6)
                    val ipAddress = aaaaRecord.rdataToString()
                    val result = "$testDomain -> $ipAddress (AAAA)"
                    sendDebugResult(context, packageName, "✓ DNS resolution successful (AAAA)", result)
                }
                else -> {
                    // No A, CNAME, or AAAA record - log what we got
                    val recordTypes = answers.map { "${Type.string(it.type)}(${it.rdataToString()})" }.joinToString(", ")
                    sendDebugResult(context, packageName, "⚠ No A/CNAME/AAAA record", "$testDomain -> Records: $recordTypes")
                }
            }
        }
    }

    /**
     * Send debug query result to MainActivity via broadcast
     */
    private fun sendDebugResult(context: Context, packageName: String, status: String, result: String?) {
        try {
            val intent = Intent("com.acktarius.hnsgo.DEBUG_QUERY_RESULT").apply {
                putExtra("status", status)
                putExtra("result", result)
                setPackage(packageName) // Make it explicit
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
        }
    }
}

