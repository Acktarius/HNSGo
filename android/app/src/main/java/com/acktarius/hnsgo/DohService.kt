package com.acktarius.hnsgo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.util.Locale
import java.io.ByteArrayInputStream
import android.util.Log
import java.net.ServerSocket
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import okhttp3.OkHttpClient
import okhttp3.Request

class DohService : Service() {
    companion object {
        private const val MIN_REQUIRED_HEIGHT_OFFSET = 1000
        private const val SYNC_THRESHOLD_BLOCKS = 10
        private const val SYNC_CHECK_DELAY_MS = 5000L
        private const val SYNC_CHECK_INTERVAL_SECONDS = 5
        private const val MAX_SYNC_WAIT_MINUTES = 5
        private const val MAX_SYNC_WAIT_ATTEMPTS = (MAX_SYNC_WAIT_MINUTES * 60) / SYNC_CHECK_INTERVAL_SECONDS
        private const val SERVER_SOCKET_BACKLOG = 50
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var dohServer: LocalDoHServer? = null
    private var dotServer: LocalDoTServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("HNSGo", "DohService onCreate()")
        try {
            createChannel()
            Log.d("HNSGo", "Starting foreground service...")
            startForeground(1, notif("HNS Go Active"))
            Log.d("HNSGo", "Foreground service started, launching coroutine...")
            scope.launch {
                try {
                    Log.d("HNSGo", "Initializing SPV client in service...")
                    // Configure resolver to use external Handshake resolver
                    SpvClient.setResolver(Config.DEBUG_RESOLVER_HOST, Config.DEBUG_RESOLVER_PORT)
                    SpvClient.init(filesDir, this@DohService)
                    
                    // Start DoH server
                    Log.d("HNSGo", "Creating LocalDoHServer...")
                    val newDohServer = LocalDoHServer()
                    Log.d("HNSGo", "Starting DoH server on port ${Config.DOH_PORT} (HTTPS)...")
                    newDohServer.start()
                    dohServer = newDohServer
                    Log.d("HNSGo", "DoH server started successfully on https://127.0.0.1:${Config.DOH_PORT}/dns-query")
                    
                    // Start DoT server (above 1024, no root required)
                    Log.d("HNSGo", "Creating LocalDoTServer...")
                    val newDotServer = LocalDoTServer()
                    Log.d("HNSGo", "Starting DoT server on port ${Config.DOT_PORT}...")
                    try {
                        newDotServer.start()
                        dotServer = newDotServer
                        Log.d("HNSGo", "DoT server started successfully on port ${newDotServer.actualPort}")
                    } catch (e: Exception) {
                        Log.e("HNSGo", "Error starting DoT server", e)
                        Log.w("HNSGo", "DoH server on port ${Config.DOH_PORT} is still available and working.")
                        // Continue without DoT - DoH will still work
                    }
                    
                    // Now that servers are ready, send status update and test DNS resolution
                    // This ensures we test when the full system is ready
                    Log.d("HNSGo", "DoH/DoT servers ready - testing DNS resolution...")
                    sendDebugResult("DoH server ready", "Server running on https://127.0.0.1:${Config.DOH_PORT}/dns-query")
                    scope.launch {
                        testDnsResolution()
                    }
                } catch (e: Exception) {
                    Log.e("HNSGo", "Error starting DNS servers", e)
                }
            }
        } catch (e: Exception) {
            Log.e("HNSGo", "Error in DohService.onCreate()", e)
        }
    }

    override fun onDestroy() {
        Log.d("HNSGo", "DohService onDestroy()")
        scope.cancel()
        try {
            dohServer?.stop()
        } catch (e: Exception) {
            Log.e("HNSGo", "Error stopping DoH server", e)
        }
        try {
            dotServer?.stop()
        } catch (e: Exception) {
            Log.e("HNSGo", "Error stopping DoT server", e)
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("HNSGo", "DohService onStartCommand()")
        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        try {
            Log.d("HNSGo", "Creating notification channel...")
            val ch = NotificationChannel("hns", "HNS Go", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
            Log.d("HNSGo", "Notification channel created")
        } catch (e: Exception) {
            Log.e("HNSGo", "Error creating notification channel", e)
        }
    }

    private fun notif(text: String): Notification {
        // Try to get the icon resource, fallback to system icon if R class not available
        val iconId = try {
            resources.getIdentifier("ic_hns", "drawable", packageName)
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info // Fallback system icon
        }
        
        return Notification.Builder(this, "hns")
            .setContentTitle("HNS Go")
            .setContentText(text)
            .setSmallIcon(if (iconId != 0) iconId else android.R.drawable.ic_dialog_info)
            .build()
    }
    
    /**
     * Test DNS resolution after servers are ready
     * Simulates a 'dig' command by making an actual DoH query to our own server
     * This tests the full stack: DoH request → RecursiveResolver → SpvClient → blockchain
     */
    private suspend fun testDnsResolution() = withContext(Dispatchers.IO) {
        try {
            Log.d("HNSGo", "DohService: Testing DNS resolution (servers are ready)")
            
            // Wait for sync to complete before testing
            val minRequiredHeight = Config.CHECKPOINT_HEIGHT + MIN_REQUIRED_HEIGHT_OFFSET
            var currentHeight = SpvClient.getChainHeight()
            var networkHeight = SpvClient.getNetworkHeight()
            
            // Check if we have enough headers
            if (currentHeight < minRequiredHeight) {
                val status = "⚠ Sync incomplete (height: $currentHeight, need: $minRequiredHeight)"
                val result = "Header sync still in progress. Resolution may not work yet."
                sendDebugResult(status, result)
                Log.w("HNSGo", "DohService: Not enough headers synced yet (height: $currentHeight, required: $minRequiredHeight)")
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
                        Log.d("HNSGo", "DohService: Sync complete! (height: $currentHeight, network: $networkHeight, behind: $behind)")
                        break
                    } else {
                        Log.d("HNSGo", "DohService: Waiting for sync... (height: $currentHeight, network: $networkHeight, behind: $behind blocks)")
                        sendDebugResult("Waiting for sync... ($currentHeight / $networkHeight)", null)
                        delay(SYNC_CHECK_DELAY_MS)
                        attempts++
                    }
                } else {
                    // Network height not available yet, wait a bit
                    Log.d("HNSGo", "DohService: Network height not available yet, waiting...")
                    sendDebugResult("Waiting for network height...", null)
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
                    sendDebugResult(status, result)
                    Log.w("HNSGo", "DohService: Not fully synced yet (behind by $behind blocks), skipping test")
                    return@withContext
                }
            }
            
            // Test resolution by making an actual DoH query (simulating 'dig')
            val testDomain = "website.conceal"
            Log.d("HNSGo", "DohService: Simulating 'dig' query for '$testDomain' via DoH... (chain height: $currentHeight, network: $networkHeight)")
            sendDebugResult("Testing DNS resolution (dig simulation)...", null)
            
            // Create DNS query message (like 'dig website.conceal A')
            val queryName = Name.fromString("$testDomain.")
            val query = org.xbill.DNS.Message.newQuery(
                org.xbill.DNS.Record.newRecord(queryName, Type.A, DClass.IN)
            )
            val queryBytes = query.toWire()
            
            // Encode query in base64url (RFC 8484 DoH GET format)
            val queryB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(queryBytes)
            
            // Make HTTPS GET request to our own DoH server
            val dohUrl = "https://127.0.0.1:${Config.DOH_PORT}/dns-query?dns=$queryB64"
            Log.d("HNSGo", "DohService: Making DoH GET request to: $dohUrl")
            
            // Create SSL context using our CA certificate (same as DoH server uses)
            // For internal testing, we programmatically trust our CA - no system installation needed
            val caCert = CertHelper.getCACertificate()
            
            // Create a custom TrustManager that trusts certificates signed by our CA
            val trustManager = object : javax.net.ssl.X509TrustManager {
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
                        Log.d("HNSGo", "DohService: Server certificate verified successfully (signature valid)")
                        return
                    } catch (e: Exception) {
                        Log.d("HNSGo", "DohService: Direct signature verification failed, checking chain", e)
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
                            Log.d("HNSGo", "DohService: Server certificate verified (CA in chain)")
                            return
                        } catch (e: Exception) {
                            Log.e("HNSGo", "DohService: Certificate verification failed even with CA in chain", e)
                        }
                    }
                    
                    // If signature verification failed, check issuer/subject as diagnostic
                    val serverIssuer = serverCert.issuerX500Principal
                    val caSubject = caCert.subjectX500Principal
                    Log.d("HNSGo", "DohService: Verifying server cert - issuer: $serverIssuer, CA subject: $caSubject")
                    
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
            
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)
            
            val client = okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true } // Trust localhost (we control both ends)
                .build()
            
            val request = okhttp3.Request.Builder()
                .url(dohUrl)
                .header("Accept", "application/dns-message")
                .get()
                .build()
            
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                Log.e("HNSGo", "DohService: Error making DoH request", e)
                sendDebugResult("✗ Query failed", "Connection error: ${e.message}")
                return@withContext
            }
            
            try {
                if (response.isSuccessful && response.body != null) {
                    val responseBytes = response.body!!.bytes()
                    val dnsResponse = try {
                        org.xbill.DNS.Message(responseBytes)
                    } catch (e: Exception) {
                        Log.e("HNSGo", "DohService: Error parsing DNS response", e)
                        sendDebugResult("✗ Query failed", "Invalid DNS response")
                        return@withContext
                    }
                    
                    val answers = dnsResponse.getSection(Section.ANSWER)
                    if (answers.isNotEmpty()) {
                        val aRecord = answers.find { it is ARecord } as? ARecord
                        if (aRecord != null && aRecord.address != null) {
                            val ipAddress = aRecord.address.hostAddress
                            if (ipAddress != null) {
                                val result = "$testDomain -> $ipAddress"
                                sendDebugResult("✓ DNS resolution successful", result)
                                Log.d("HNSGo", "DohService: $testDomain resolved to $ipAddress via DoH")
                            } else {
                                sendDebugResult("⚠ No A record", "$testDomain -> A record has null address")
                                Log.w("HNSGo", "DohService: $testDomain A record has null address")
                            }
                        } else {
                            sendDebugResult("⚠ No A record", "$testDomain -> No A record in response")
                            Log.w("HNSGo", "DohService: $testDomain response has no A record")
                        }
                    } else {
                        val rcode = dnsResponse.rcode
                        if (rcode == Rcode.NXDOMAIN) {
                            sendDebugResult("✗ Domain not found", "$testDomain -> NXDOMAIN")
                            Log.w("HNSGo", "DohService: $testDomain -> NXDOMAIN")
                        } else {
                            sendDebugResult("⚠ No answer", "$testDomain -> RCODE: $rcode")
                            Log.w("HNSGo", "DohService: $testDomain -> RCODE: $rcode")
                        }
                    }
                } else {
                    sendDebugResult("✗ Query failed", "HTTP ${response.code}: ${response.message}")
                    Log.e("HNSGo", "DohService: DoH query failed - HTTP ${response.code}")
                }
            } finally {
                response.close()
            }
        } catch (e: Exception) {
            sendDebugResult("✗ Query failed", "Error: ${e.message}")
            Log.e("HNSGo", "DohService: Error testing DNS resolution via DoH", e)
        }
    }
    
    /**
     * Send debug query result to MainActivity via broadcast
     */
    private fun sendDebugResult(status: String, result: String?) {
        try {
            val intent = Intent("com.acktarius.hnsgo.DEBUG_QUERY_RESULT").apply {
                putExtra("status", status)
                putExtra("result", result)
                setPackage(packageName) // Make it explicit
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e("HNSGo", "Error sending debug result broadcast", e)
        }
    }
}

class LocalDoHServer : NanoHTTPD(Config.DOH_PORT) {
    private val fallbackResolver = SimpleResolver("9.9.9.9") // Quad9 DNS (privacy-focused, blocks malware)
    
    init {
        // Enable HTTPS using our CA certificate
        try {
            val sslContext = createSSLContext()
            // Use makeSecure to enable HTTPS in NanoHTTPD
            makeSecure(sslContext.serverSocketFactory, null)
            Log.d("HNSGo", "DoH server configured for HTTPS with HNS Go CA certificate")
        } catch (e: Exception) {
            Log.e("HNSGo", "Error configuring HTTPS for DoH server", e)
            throw e
        }
    }
    
    private fun createSSLContext(): SSLContext {
        val keyStore = CertHelper.generateKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "changeit".toCharArray())
        
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
        return sslContext
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/dns-query" || session.method != Method.GET)
            return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")

        val dnsB64 = session.queryParameterString?.removePrefix("dns=") ?: return bad()
        val query = java.util.Base64.getUrlDecoder().decode(dnsB64)
        val msg = try { Message(query) } catch (e: Exception) { return bad() }
        val questions = msg.getSection(Section.QUESTION)
        if (questions.isEmpty()) return bad()
        val q = questions[0]
        val nameStr = q.name.toString(true)
        // CRITICAL: Handshake TLDs can contain symbols and mixed case!
        // We MUST NOT normalize - preserve the exact name as received
        // DNS queries are case-insensitive, but Handshake name hashing is case-sensitive
        val name = nameStr as String
        val type = q.type

        // Try Handshake recursive resolution first
        val response = runBlocking { 
            RecursiveResolver.resolve(name, type)
        }
        
        // If recursive resolution succeeded, return the response
        if (response != null) {
            // Copy response but preserve original query ID
            val resp = org.xbill.DNS.Message(msg.header.id).apply {
                header.setFlag(Flags.QR.toInt())
                header.rcode = response.rcode
                if (response.header.getFlag(Flags.AA.toInt())) {
                    header.setFlag(Flags.AA.toInt())
                }
                
                // Copy question
                val questions = response.getSection(Section.QUESTION)
                if (questions.isNotEmpty()) {
                    addRecord(questions[0], Section.QUESTION)
                } else {
                    addRecord(q, Section.QUESTION)
                }
                
                // Copy answer section
                val answers = response.getSection(Section.ANSWER)
                for (record in answers) {
                    addRecord(record, Section.ANSWER)
                }
                
                // Copy authority section
                val authority = response.getSection(Section.AUTHORITY)
                for (record in authority) {
                    addRecord(record, Section.AUTHORITY)
                }
                
                // Copy additional section
                val additional = response.getSection(Section.ADDITIONAL)
                for (record in additional) {
                    addRecord(record, Section.ADDITIONAL)
                }
            }
            
            val wireData = resp.toWire()
            return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/dns-message", ByteArrayInputStream(wireData), wireData.size.toLong())
        }
        
        // If not found in Handshake, fall back to regular DNS
        return forwardToDns(msg)
    }

    private fun forwardToDns(query: org.xbill.DNS.Message): Response {
        return try {
            val questions = query.getSection(Section.QUESTION)
            val domainName = if (questions.isNotEmpty()) questions[0].name.toString() else "unknown"
            Log.d("HNSGo", "Forwarding to regular DNS (not found in Handshake): $domainName")
            val response = runBlocking {
                withContext(Dispatchers.IO) {
                    fallbackResolver.send(query)
                }
            }
            val wireData = response.toWire()
            NanoHTTPD.newFixedLengthResponse(Status.OK, "application/dns-message", ByteArrayInputStream(wireData), wireData.size.toLong())
        } catch (e: Exception) {
            Log.e("HNSGo", "Error forwarding DNS query", e)
            nxdomain(query)
        }
    }

    private fun bad() = NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Bad request")
    private fun nxdomain(q: org.xbill.DNS.Message): Response {
        val questions = q.getSection(Section.QUESTION)
        val resp = org.xbill.DNS.Message(q.header.id).apply {
            header.setFlag(Flags.QR.toInt())
            header.rcode = Rcode.NXDOMAIN
            if (questions.isNotEmpty()) {
                addRecord(questions[0], Section.QUESTION)
            }
        }
        val wireData = resp.toWire()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/dns-message", ByteArrayInputStream(wireData), wireData.size.toLong())
    }
}

class LocalDoTServer {
    companion object {
        private const val SERVER_SOCKET_BACKLOG = 50
    }
    
    private val fallbackResolver = SimpleResolver("9.9.9.9") // Quad9 DNS
    private var serverSocket: SSLServerSocket? = null
    private var running = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var actualPort: Int = Config.DOT_PORT // Port above 1024 (no root required)

    fun start() {
        try {
            Log.d("HNSGo", "Setting up SSL context for DoT...")
            val sslContext = createSSLContext()
            val factory = sslContext.serverSocketFactory
            val bindAddress = java.net.InetAddress.getByName("127.0.0.1")
            
            // Use Config.DOT_PORT (above 1024, no root required)
            // Note: Android Private DNS expects port 853, so this won't work with Private DNS directly
            // Users would need to use port forwarding or configure apps to use this port
            val port = Config.DOT_PORT
            Log.d("HNSGo", "Creating SSL server socket on 127.0.0.1:$port...")
            val sslServerSocket = factory.createServerSocket(port, SERVER_SOCKET_BACKLOG, bindAddress) as SSLServerSocket
            sslServerSocket.needClientAuth = false
            sslServerSocket.enabledCipherSuites = sslServerSocket.supportedCipherSuites
            (sslServerSocket as? java.net.ServerSocket)?.reuseAddress = true
            serverSocket = sslServerSocket
            actualPort = port
            val boundAddress = sslServerSocket.localSocketAddress
            Log.d("HNSGo", "DoT server socket successfully bound to: $boundAddress")
            Log.d("HNSGo", "DoT server listening on 127.0.0.1:$port")
            
            running = true
            scope.launch {
                while (running) {
                    try {
                        Log.d("HNSGo", "DoT: Waiting for connection...")
                        val clientSocket = serverSocket?.accept() as? javax.net.ssl.SSLSocket
                        if (clientSocket != null) {
                            Log.d("HNSGo", "DoT: Connection accepted from ${clientSocket.remoteSocketAddress}")
                            scope.launch {
                                handleClient(clientSocket)
                            }
                        } else {
                            Log.w("HNSGo", "DoT: Accepted socket is null")
                        }
                    } catch (e: Exception) {
                        if (running) {
                            Log.e("HNSGo", "Error accepting DoT connection", e)
                        }
                    }
                }
            }
            Log.d("HNSGo", "DoT server started successfully on port 853")
        } catch (e: Exception) {
            Log.e("HNSGo", "Error starting DoT server", e)
            throw e
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("HNSGo", "Error closing DoT server socket", e)
        }
        scope.cancel()
    }

    private fun createSSLContext(): SSLContext {
        val keyStore = CertHelper.generateKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "changeit".toCharArray())
        
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, tmf.trustManagers, null)
        return sslContext
    }

    private fun handleClient(socket: javax.net.ssl.SSLSocket) {
        try {
            Log.d("HNSGo", "DoT: New client connection from ${socket.remoteSocketAddress}")
            socket.use { sslSocket ->
                // Log TLS session info before handshake
                Log.d("HNSGo", "DoT: Starting TLS handshake...")
                Log.d("HNSGo", "DoT: Enabled protocols: ${sslSocket.enabledProtocols.joinToString()}")
                Log.d("HNSGo", "DoT: Enabled cipher suites: ${sslSocket.enabledCipherSuites.take(5).joinToString()}")
                
                // Start handshake with error handling
                try {
                    sslSocket.startHandshake()
                    
                    // Log TLS session info after handshake
                    val session = sslSocket.session
                    Log.d("HNSGo", "DoT: TLS handshake completed successfully")
                    Log.d("HNSGo", "DoT: Protocol: ${session.protocol}, Cipher: ${session.cipherSuite}")
                    Log.d("HNSGo", "DoT: Peer principal: ${session.peerPrincipal?.name ?: "none"}")
                } catch (e: javax.net.ssl.SSLException) {
                    Log.e("HNSGo", "DoT: TLS handshake failed", e)
                    Log.e("HNSGo", "DoT: SSL error message: ${e.message}")
                    throw e
                } catch (e: Exception) {
                    Log.e("HNSGo", "DoT: Unexpected error during TLS handshake", e)
                    throw e
                }
                
                val input = DataInputStream(sslSocket.inputStream)
                val output = DataOutputStream(sslSocket.outputStream)
                
                while (running && !sslSocket.isClosed) {
                    try {
                        // Read DNS message length (2 bytes, big-endian)
                        val length = input.readUnsignedShort()
                        if (length == 0) break
                        
                        Log.d("HNSGo", "DoT: Received DNS query, length=$length")
                        
                        // Read DNS message
                        val queryBytes = ByteArray(length)
                        input.readFully(queryBytes)
                        
                        // Parse and process DNS query
                        val query = org.xbill.DNS.Message(queryBytes)
                        val questions = query.getSection(Section.QUESTION)
                        val domainName = if (questions.isNotEmpty()) questions[0].name.toString() else "unknown"
                        Log.d("HNSGo", "DoT: Processing query for: $domainName")
                        
                        val response = processDnsQuery(query)
                        
                        // Write response length and data
                        val responseBytes = response.toWire()
                        output.writeShort(responseBytes.size)
                        output.write(responseBytes)
                        output.flush()
                        Log.d("HNSGo", "DoT: Sent response, length=${responseBytes.size}")
                    } catch (e: java.io.EOFException) {
                        Log.d("HNSGo", "DoT: Client disconnected (EOF)")
                        break
                    } catch (e: Exception) {
                        Log.e("HNSGo", "Error handling DoT query", e)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HNSGo", "Error in DoT client handler", e)
        }
    }

    private fun processDnsQuery(query: org.xbill.DNS.Message): org.xbill.DNS.Message {
        val questions = query.getSection(Section.QUESTION)
        if (questions.isEmpty()) {
            return createErrorResponse(query, Rcode.FORMERR)
        }
        
        val q = questions[0]
        val nameStr = q.name.toString(true)
        val name = nameStr.lowercase(Locale.getDefault())
        val type = q.type

        // Try Handshake recursive resolution first
        val response = runBlocking { 
            RecursiveResolver.resolve(name, type)
        }
        
        // If recursive resolution succeeded, return the response
        if (response != null) {
            // Copy response but preserve original query ID
            val resp = org.xbill.DNS.Message(query.header.id).apply {
                header.setFlag(Flags.QR.toInt())
                header.rcode = response.rcode
                if (response.header.getFlag(Flags.AA.toInt())) {
                    header.setFlag(Flags.AA.toInt())
                }
                
                // Copy question
                val responseQuestions = response.getSection(Section.QUESTION)
                if (responseQuestions.isNotEmpty()) {
                    addRecord(responseQuestions[0], Section.QUESTION)
                } else {
                    addRecord(q, Section.QUESTION)
                }
                
                // Copy answer section
                val answers = response.getSection(Section.ANSWER)
                for (record in answers) {
                    addRecord(record, Section.ANSWER)
                }
                
                // Copy authority section
                val authority = response.getSection(Section.AUTHORITY)
                for (record in authority) {
                    addRecord(record, Section.AUTHORITY)
                }
                
                // Copy additional section
                val additional = response.getSection(Section.ADDITIONAL)
                for (record in additional) {
                    addRecord(record, Section.ADDITIONAL)
                }
            }
            
            return resp
        }
        
        // If not found in Handshake, fall back to regular DNS
        return try {
            val queryQuestions = query.getSection(Section.QUESTION)
            val domainName = if (queryQuestions.isNotEmpty()) queryQuestions[0].name.toString() else "unknown"
            Log.d("HNSGo", "DoT: Forwarding to regular DNS (not found in Handshake): $domainName")
            runBlocking {
                withContext(Dispatchers.IO) {
                    fallbackResolver.send(query)
                }
            }
        } catch (e: Exception) {
            Log.e("HNSGo", "Error forwarding DoT DNS query", e)
            createNxDomainResponse(query)
        }
    }

    private fun createNxDomainResponse(query: org.xbill.DNS.Message): org.xbill.DNS.Message {
        val questions = query.getSection(Section.QUESTION)
        val resp = org.xbill.DNS.Message(query.header.id).apply {
            header.setFlag(Flags.QR.toInt())
            header.rcode = Rcode.NXDOMAIN
            if (questions.isNotEmpty()) {
                addRecord(questions[0], Section.QUESTION)
            }
        }
        return resp
    }

    private fun createErrorResponse(query: org.xbill.DNS.Message, rcode: Int): org.xbill.DNS.Message {
        val resp = org.xbill.DNS.Message(query.header.id).apply {
            header.setFlag(Flags.QR.toInt())
            header.rcode = rcode
        }
        return resp
    }
}
