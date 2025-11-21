package com.acktarius.hnsgo

import android.app.*
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.SupervisorJob
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.xbill.DNS.*
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

class DohService : Service() {
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
                    val newDohServer = LocalDoHServer(filesDir)
                    Log.d("HNSGo", "Starting DoH server on port ${Config.DOH_PORT} (HTTPS)...")
                    newDohServer.start()
                    dohServer = newDohServer
                    Log.d("HNSGo", "DoH server started successfully on https://127.0.0.1:${Config.DOH_PORT}/dns-query")
                    
                    // Start DoT server (above 1024, no root required)
                    Log.d("HNSGo", "Creating LocalDoTServer...")
                    val newDotServer = LocalDoTServer(filesDir)
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
}

class LocalDoHServer(private val dataDir: File) : NanoHTTPD(Config.DOH_PORT) {
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
        val name = (nameStr as String).lowercase(Locale.getDefault())
        val type = q.type

        // Try Handshake resolution first
        val records = runBlocking { SpvClient.resolve(name) }
        
        // If not found in Handshake, fall back to regular DNS
        if (records == null) {
            return forwardToDns(msg)
        }

        // Build response with all relevant records
        val resp = org.xbill.DNS.Message(msg.header.id).apply {
            header.setFlag(Flags.QR.toInt())
            addRecord(q, Section.QUESTION)
            
            // Find records matching the query type
            val matchingRecords = records.filter { it.type == type }
            
            if (matchingRecords.isEmpty()) {
                // If no direct match, check for CNAME (which might redirect)
                val cnameRecord = records.find { it.type == 5 } // CNAME
                if (cnameRecord != null) {
                    val cnameTarget = String(cnameRecord.data).trim()
                    val cnameName = Name(cnameTarget, Name.root)
                    addRecord(CNAMERecord(q.name, DClass.IN, 3600, cnameName), Section.ANSWER)
                    
                    // Try to resolve the CNAME target if querying for A record
                    // CNAME targets are usually regular DNS domains, so use fallback resolver
                    if (type == Type.A) {
                        try {
                            val targetQuery = org.xbill.DNS.Message().apply {
                                addRecord(org.xbill.DNS.Record.newRecord(cnameName, Type.A, DClass.IN), Section.QUESTION)
                            }
                            val targetResponse = runBlocking {
                                withContext(Dispatchers.IO) {
                                    fallbackResolver.send(targetQuery)
                                }
                            }
                            val targetAnswers = targetResponse.getSection(Section.ANSWER)
                            targetAnswers.filterIsInstance<ARecord>().forEach { aRec ->
                                addRecord(ARecord(cnameName, DClass.IN, aRec.ttl, aRec.address), Section.ANSWER)
                            }
                        } catch (e: Exception) {
                            Log.e("HNSGo", "Error resolving CNAME target: $cnameTarget", e)
                        }
                    }
                } else {
                    return nxdomain(msg)
                }
            } else {
                // Add matching records
                matchingRecords.forEach { rec ->
                    when (type) {
                        Type.A -> {
                            val ip = String(rec.data).trim()
                            try {
                                addRecord(ARecord(q.name, DClass.IN, 3600, java.net.InetAddress.getByName(ip)), Section.ANSWER)
                            } catch (e: Exception) {
                                Log.e("HNSGo", "Error parsing IP address: $ip", e)
                            }
                        }
                        Type.CNAME -> {
                            val target = String(rec.data).trim()
                            val targetName = Name(target, Name.root)
                            addRecord(CNAMERecord(q.name, DClass.IN, 3600, targetName), Section.ANSWER)
                        }
                        Type.TLSA -> {
                            addRecord(TLSARecord(q.name, DClass.IN, 3600, 
                                (rec.data[0].toInt() and 0xFF), 
                                (rec.data[1].toInt() and 0xFF), 
                                (rec.data[2].toInt() and 0xFF), 
                                rec.data.sliceArray(3 until rec.data.size)), Section.ANSWER)
                        }
                    }
                }
            }
        }
        val wireData = resp.toWire()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/dns-message", ByteArrayInputStream(wireData), wireData.size.toLong())
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

class LocalDoTServer(private val dataDir: File) {
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
            val sslServerSocket = factory.createServerSocket(port, 50, bindAddress) as SSLServerSocket
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

        // Try Handshake resolution first
        val records = runBlocking { SpvClient.resolve(name) }
        
        // If not found in Handshake, fall back to regular DNS
        if (records == null) {
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

        // Build response with all relevant records
        val resp = org.xbill.DNS.Message(query.header.id).apply {
            header.setFlag(Flags.QR.toInt())
            addRecord(q, Section.QUESTION)
            
            // Find records matching the query type
            val matchingRecords = records.filter { it.type == type }
            
            if (matchingRecords.isEmpty()) {
                // If no direct match, check for CNAME (which might redirect)
                val cnameRecord = records.find { it.type == 5 } // CNAME
                if (cnameRecord != null) {
                    val cnameTarget = String(cnameRecord.data).trim()
                    val cnameName = Name(cnameTarget, Name.root)
                    addRecord(CNAMERecord(q.name, DClass.IN, 3600, cnameName), Section.ANSWER)
                    
                    // Try to resolve the CNAME target if querying for A record
                    // CNAME targets are usually regular DNS domains, so use fallback resolver
                    if (type == Type.A) {
                        try {
                            val targetQuery = org.xbill.DNS.Message().apply {
                                addRecord(org.xbill.DNS.Record.newRecord(cnameName, Type.A, DClass.IN), Section.QUESTION)
                            }
                            val targetResponse = runBlocking {
                                withContext(Dispatchers.IO) {
                                    fallbackResolver.send(targetQuery)
                                }
                            }
                            val targetAnswers = targetResponse.getSection(Section.ANSWER)
                            targetAnswers.filterIsInstance<ARecord>().forEach { aRec ->
                                addRecord(ARecord(cnameName, DClass.IN, aRec.ttl, aRec.address), Section.ANSWER)
                            }
                        } catch (e: Exception) {
                            Log.e("HNSGo", "DoT: Error resolving CNAME target: $cnameTarget", e)
                        }
                    }
                } else {
                    return createNxDomainResponse(query)
                }
            } else {
                // Add matching records
                matchingRecords.forEach { rec ->
                    when (type) {
                        Type.A -> {
                            val ip = String(rec.data).trim()
                            try {
                                addRecord(ARecord(q.name, DClass.IN, 3600, java.net.InetAddress.getByName(ip)), Section.ANSWER)
                            } catch (e: Exception) {
                                Log.e("HNSGo", "DoT: Error parsing IP address: $ip", e)
                            }
                        }
                        Type.CNAME -> {
                            val target = String(rec.data).trim()
                            val targetName = Name(target, Name.root)
                            addRecord(CNAMERecord(q.name, DClass.IN, 3600, targetName), Section.ANSWER)
                        }
                        Type.TLSA -> {
                            addRecord(TLSARecord(q.name, DClass.IN, 3600, 
                                (rec.data[0].toInt() and 0xFF), 
                                (rec.data[1].toInt() and 0xFF), 
                                (rec.data[2].toInt() and 0xFF), 
                                rec.data.sliceArray(3 until rec.data.size)), Section.ANSWER)
                        }
                    }
                }
            }
        }
        return resp
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