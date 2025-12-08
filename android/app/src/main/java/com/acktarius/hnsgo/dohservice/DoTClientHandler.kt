package com.acktarius.hnsgo.dohservice

import android.util.Log
import com.acktarius.hnsgo.dohservice.DnsResolver
import com.acktarius.hnsgo.dohservice.ResponseBuilder
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Locale

/**
 * Handles DoT (DNS over TLS) client connections
 */
object DoTClientHandler {
    /**
     * Handle a DoT client connection
     * Processes DNS queries over TLS and sends responses
     */
    fun handleClient(socket: javax.net.ssl.SSLSocket, running: () -> Boolean) {
        try {
            socket.use { sslSocket ->
                // Log TLS session info before handshake
                
                // Start handshake with error handling
                performTlsHandshake(sslSocket)
                
                val input = DataInputStream(sslSocket.inputStream)
                val output = DataOutputStream(sslSocket.outputStream)
                
                while (running() && !sslSocket.isClosed) {
                    try {
                        // Read DNS message length (2 bytes, big-endian)
                        val length = input.readUnsignedShort()
                        if (length == 0) break
                        
                        
                        // Read DNS message
                        val queryBytes = ByteArray(length)
                        input.readFully(queryBytes)
                        
                        // Parse and process DNS query
                        val query = Message(queryBytes)
                        val questions = query.getSection(Section.QUESTION)
                        val domainName = if (questions.isNotEmpty()) questions[0].name.toString() else "unknown"
                        
                        val response = processDnsQuery(query)
                        
                        // Write response length and data
                        val responseBytes = response.toWire()
                        output.writeShort(responseBytes.size)
                        output.write(responseBytes)
                        output.flush()
                    } catch (e: java.io.EOFException) {
                        break
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun performTlsHandshake(sslSocket: javax.net.ssl.SSLSocket) {
        try {
            sslSocket.startHandshake()
            
            // Log TLS session info after handshake
            val session = sslSocket.session
        } catch (e: javax.net.ssl.SSLException) {
            throw e
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * Process DNS query and return response
     * 
     * For web browsing (HTTP/HTTPS), valid answers include:
     * - A record (IPv4 address) - direct connection
     * - AAAA record (IPv6 address) - direct IPv6 connection  
     * - CNAME record - browser will automatically follow to get A/AAAA
     * 
     * The browser handles CNAME resolution automatically, so we just return
     * whatever the nameserver provides. No need to validate record types here.
     */
    private fun processDnsQuery(query: Message): Message {
        val questions = query.getSection(Section.QUESTION)
        if (questions.isEmpty()) {
            return ResponseBuilder.createErrorResponse(query, Rcode.FORMERR)
        }
        
        val q = questions[0]
        val nameStr = q.name.toString(true)
        val name = nameStr.lowercase(Locale.getDefault())
        val type = q.type
        val dclass = DClass.IN

        // Resolve DNS query using extracted resolver
        val resolutionResult = DnsResolver.resolve(name, type, dclass, query.header.id, q)
        
        return when (resolutionResult) {
            is DnsResolver.ResolutionResult.Cached -> {
                val cachedMsg = Message(resolutionResult.wireData)
                cachedMsg.header.id = query.header.id
                cachedMsg
            }
            is DnsResolver.ResolutionResult.Blocked -> {
                resolutionResult.message
            }
            is DnsResolver.ResolutionResult.Success -> {
                ResponseBuilder.copyResponseWithQueryId(resolutionResult.message, query.header.id, q)
            }
            is DnsResolver.ResolutionResult.Failure -> {
                ResponseBuilder.createErrorResponse(query, resolutionResult.rcode)
            }
        }
    }
}

