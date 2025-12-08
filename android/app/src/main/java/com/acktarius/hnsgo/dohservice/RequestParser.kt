package com.acktarius.hnsgo.dohservice

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status

/**
 * Handles parsing of DoH requests (GET and POST methods per RFC 8484)
 */
object RequestParser {
    /**
     * Parse DNS query from DoH request
     * @return Pair of (queryBytes, errorResponse) - if errorResponse is not null, return it
     */
    fun parseDnsQuery(session: IHTTPSession): Pair<ByteArray?, Response?> {
        // Log connection info for debugging
        val remoteAddr = session.remoteIpAddress
        val uri = session.uri
        val headers = session.headers
        if (session.method == Method.POST) {
        } else if (session.method == Method.GET) {
        }
        
        // Health check endpoint
        if (uri == "/health" || uri == "/") {
            return Pair(null, NanoHTTPD.newFixedLengthResponse(Status.OK, "text/plain", "HNS Go DoH Server is running"))
        }
        
        if (uri != "/dns-query") {
            return Pair(null, NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found. Use /dns-query for DNS queries or /health for health check."))
        }

        // Handle both GET and POST methods (RFC 8484)
        val queryBytes: ByteArray? = when (session.method) {
            Method.GET -> parseGetRequest(session)
            Method.POST -> parsePostRequest(session)
            else -> {
                return Pair(null, NanoHTTPD.newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed"))
            }
        }
        
        if (queryBytes == null) {
            // Return error response for invalid request
            return Pair(null, NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                "application/dns-message",
                "Invalid DNS query"
            ))
        }
        
        return Pair(queryBytes, null)
    }
    
    private fun parseGetRequest(session: IHTTPSession): ByteArray? {
        // GET: DNS query is base64url-encoded in 'dns' query parameter
        // Check both parms and queryParameterString for compatibility
        val dnsParam = session.parms["dns"] 
            ?: session.queryParameterString?.let { 
                // Try to extract from query string manually if parms doesn't work
                val match = Regex("dns=([^&]*)").find(it)
                match?.groupValues?.get(1)
            }
        
        if (dnsParam.isNullOrEmpty()) {
            return null
        }
        
        return try {
            // Base64url decode (RFC 8484 uses base64url, not standard base64)
            java.util.Base64.getUrlDecoder().decode(dnsParam)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun parsePostRequest(session: IHTTPSession): ByteArray? {
        // POST: DNS query is in request body as binary (RFC 8484)
        // Content-Type should be application/dns-message
        return try {
            val contentType = session.headers["content-type"]?.lowercase()
            if (contentType != null && !contentType.contains("application/dns-message")) {
            }
            
            // Read request body - try content-length first, then read available bytes
            val contentLength = session.headers["content-length"]?.toIntOrNull()
            val buffer = if (contentLength != null && contentLength > 0) {
                // Use content-length if available
                if (contentLength > 65535) { // DNS message max size
                    return null
                }
                val buf = ByteArray(contentLength)
                var totalRead = 0
                while (totalRead < contentLength) {
                    val read = session.inputStream.read(buf, totalRead, contentLength - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                if (totalRead < contentLength) {
                }
                buf.sliceArray(0 until totalRead)
            } else {
                // No content-length, read available bytes (up to DNS max size)
                val available = session.inputStream.available()
                if (available <= 0) {
                    return null
                }
                val maxSize = 65535
                val size = minOf(available, maxSize)
                val buf = ByteArray(size)
                val totalRead = session.inputStream.read(buf)
                if (totalRead > 0) {
                    buf.sliceArray(0 until totalRead)
                } else {
                    return null
                }
            }
            
            if (buffer.isEmpty()) {
                return null
            }
            
            buffer
        } catch (e: Exception) {
            null
        }
    }
}
