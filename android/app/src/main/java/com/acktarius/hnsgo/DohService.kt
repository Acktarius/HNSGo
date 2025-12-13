package com.acktarius.hnsgo

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section
import java.io.ByteArrayInputStream
import android.util.Log
import javax.net.ssl.SSLServerSocket
import com.acktarius.hnsgo.dohservice.RequestParser
import com.acktarius.hnsgo.dohservice.ServeHtml
import com.acktarius.hnsgo.dohservice.DnsResolver
import com.acktarius.hnsgo.dohservice.ResponseBuilder
import com.acktarius.hnsgo.dohservice.SslContextFactory
import com.acktarius.hnsgo.dohservice.TestDnsResolution
import com.acktarius.hnsgo.dohservice.NotificationHelper
import com.acktarius.hnsgo.dohservice.DoTClientHandler
import com.acktarius.hnsgo.adblocker.AdBlockManager

class DohService : Service() {
    companion object {
        private const val SERVER_SOCKET_BACKLOG = 50
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var dohServer: LocalDoHServer? = null
    private var dotServer: LocalDoTServer? = null

    override fun onCreate() {
        super.onCreate()
        try {
            NotificationHelper.createChannel(this)
            startForeground(1, NotificationHelper.createNotification(this, "HNS Go Active"))
            scope.launch {
                try {
                    // Configure resolver to use external Handshake resolver
                    SpvClient.setResolver(Config.DEBUG_RESOLVER_HOST, Config.DEBUG_RESOLVER_PORT)
                    SpvClient.init(filesDir, this@DohService)
                    
                    // Initialize AdBlockManager
                    AdBlockManager.init(this@DohService)
                    
                    // Initialize ServeHtml with context for asset access
                    ServeHtml.init(this@DohService)
                    
                    // Start DoH server
                    val newDohServer = LocalDoHServer()
                    newDohServer.start()
                    dohServer = newDohServer
                    
                    // Start DoT server (above 1024, no root required)
                    val newDotServer = LocalDoTServer()
                    try {
                        newDotServer.start()
                        dotServer = newDotServer
                    } catch (e: Exception) {
                        // Continue without DoT - DoH will still work
                    }
                    
                    // Now that servers are ready, send status update
                    sendDebugResult("DoH server ready", "Server running on https://127.0.0.1:${Config.DOH_PORT}/dns-query")
                    
                    // DEVELOPMENT FEATURE: DNS resolution testing (currently disabled)
                    // scope.launch {
                    //     TestDnsResolution.testDnsResolution(this@DohService, packageName)
                    // }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try {
            dohServer?.cleanup()
            dohServer?.stop()
        } catch (e: Exception) {
        }
        try {
            dotServer?.stop()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Send debug query result to MainActivity via broadcast
     * Note: This is kept here for backward compatibility, but TestDnsResolution has its own version
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
        }
    }
}

class LocalDoHServer : NanoHTTPD(Config.DOH_PORT) {
    // Coroutine scope for async DNS resolution (avoids blocking NanoHTTPD threads)
    private val resolverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // Enable HTTPS using our CA certificate
        try {
            val sslContext = SslContextFactory.createDoHSSLContext()
            // Use makeSecure to enable HTTPS in NanoHTTPD
            makeSecure(sslContext.serverSocketFactory, null)
        } catch (e: Exception) {
            throw e
        }
    }
    
    fun cleanup() {
        resolverScope.cancel()
    }

    override fun serve(session: IHTTPSession): Response {
        try {
            // Parse DNS query from request
            val (queryBytes, errorResponse) = RequestParser.parseDnsQuery(session)
            if (errorResponse != null) {
                return errorResponse
            }
            if (queryBytes == null) {
                // RequestParser already logged the error, return appropriate error response
                return NanoHTTPD.newFixedLengthResponse(
                    Status.BAD_REQUEST,
                    "application/dns-message",
                    "Invalid DNS query"
                )
            }

            // Parse DNS message
            val msg = try { 
                Message(queryBytes)
            } catch (e: Exception) { 
                val queryId = if (queryBytes.size >= 2) {
                    ((queryBytes[0].toInt() and 0xFF) shl 8) or (queryBytes[1].toInt() and 0xFF)
                } else {
                    0
                }
                val errorMsg = Message(queryId).apply {
                    header.setFlag(Flags.QR.toInt())
                    header.rcode = Rcode.FORMERR
                }
                val wireData = errorMsg.toWire()
                return NanoHTTPD.newFixedLengthResponse(
                    Status.OK,
                    "application/dns-message",
                    ByteArrayInputStream(wireData),
                    wireData.size.toLong()
                )
            }
            
            val questions = msg.getSection(Section.QUESTION)
            if (questions.isEmpty()) {
                return ResponseBuilder.createDnsErrorResponse(Rcode.FORMERR, "No questions in DNS query", msg)
            }
            
            val q = questions[0]
            val nameStr = q.name.toString(true)
            // CRITICAL: Handshake TLDs can contain symbols and mixed case!
            // We MUST NOT normalize - preserve the exact name as received
            val name = nameStr as String
            val type = q.type
            val dclass = DClass.IN

            // Resolve DNS query using extracted resolver (async to avoid blocking NanoHTTPD thread)
            // Use runBlocking with timeout to wait for result, but this blocks the thread
            // TODO: Consider making serve() async or using a different approach
            val resolutionResult = DnsResolver.resolve(name, type, dclass, msg.header.id, q)
            
            return when (resolutionResult) {
                is DnsResolver.ResolutionResult.Cached -> {
                    ResponseBuilder.buildHttpResponseFromCache(resolutionResult.wireData, msg.header.id)
                }
                is DnsResolver.ResolutionResult.Blocked -> {
                    ResponseBuilder.buildHttpResponse(resolutionResult.message)
                }
                is DnsResolver.ResolutionResult.Success -> {
                    val resp = ResponseBuilder.copyResponseWithQueryId(resolutionResult.message, msg.header.id, q)
                    val wireData = resp.toWire()
                    
                    // Check if socket is still open before sending response
                    try {
                        ResponseBuilder.buildHttpResponse(resp)
                    } catch (e: java.net.SocketException) {
                        if (e.message?.contains("closed") == true || e.message?.contains("Socket is closed") == true) {
                            throw e
                        }
                        throw e
                    }
                }
                is DnsResolver.ResolutionResult.Failure -> {
                    ResponseBuilder.createDnsErrorResponse(resolutionResult.rcode, resolutionResult.errorMessage, msg)
                }
            }
        } catch (e: java.net.SocketException) {
            // Socket was closed - likely Firefox rejected the certificate or closed connection
            // Re-throw to let NanoHTTPD handle it gracefully
            throw e
        } catch (e: javax.net.ssl.SSLException) {
            // SSL handshake failed - log removed for privacy
            throw e
        } catch (e: Exception) {
            // Return a generic error response
            return NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Internal server error")
        }
    }

}

class LocalDoTServer {
    companion object {
        private const val SERVER_SOCKET_BACKLOG = 50
    }
    private var serverSocket: SSLServerSocket? = null
    private var running = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var actualPort: Int = Config.DOT_PORT // Port above 1024 (no root required)

    fun start() {
        try {
            val sslContext = SslContextFactory.createDoTSSLContext()
            val factory = sslContext.serverSocketFactory
            val bindAddress = java.net.InetAddress.getByName("127.0.0.1")
            
            // Use Config.DOT_PORT (above 1024, no root required)
            // Note: Android Private DNS expects port 853, so this won't work with Private DNS directly
            // Users would need to use port forwarding or configure apps to use this port
            val port = Config.DOT_PORT
            val sslServerSocket = factory.createServerSocket(port, SERVER_SOCKET_BACKLOG, bindAddress) as SSLServerSocket
            sslServerSocket.needClientAuth = false
            sslServerSocket.enabledCipherSuites = sslServerSocket.supportedCipherSuites
            (sslServerSocket as? java.net.ServerSocket)?.reuseAddress = true
            serverSocket = sslServerSocket
            actualPort = port
            val boundAddress = sslServerSocket.localSocketAddress
            
            running = true
            scope.launch {
                while (running) {
                    try {
                        val clientSocket = serverSocket?.accept() as? javax.net.ssl.SSLSocket
                        if (clientSocket != null) {
                            scope.launch {
                                DoTClientHandler.handleClient(clientSocket) { running }
                            }
                        } else {
                        }
                    } catch (e: Exception) {
                        if (running) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw e
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        scope.cancel()
    }


}
