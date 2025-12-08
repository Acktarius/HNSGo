package com.acktarius.hnsgo.dohservice

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section
import java.io.ByteArrayInputStream

/**
 * Builds DNS responses for DoH/DoT servers
 */
object ResponseBuilder {
    /**
     * Create a proper DNS-formatted error response (RFC 8484)
     * Always returns Content-Type: application/dns-message with a valid DNS message
     */
    fun createDnsErrorResponse(rcode: Int, errorMessage: String, originalQuery: Message? = null): NanoHTTPD.Response {
        return try {
            val queryId = originalQuery?.header?.id ?: 0
            val resp = Message(queryId).apply {
                header.setFlag(Flags.QR.toInt())
                header.rcode = rcode
                // Include original question if available
                if (originalQuery != null) {
                    val questions = originalQuery.getSection(Section.QUESTION)
                    if (questions.isNotEmpty()) {
                        addRecord(questions[0], Section.QUESTION)
                    }
                }
            }
            val wireData = resp.toWire()
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, // DNS errors still use HTTP 200 with error in DNS rcode
                "application/dns-message",
                ByteArrayInputStream(wireData),
                wireData.size.toLong()
            )
        } catch (e: Exception) {
            // Fallback to plain text error
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "text/plain",
                "Error: $errorMessage"
            )
        }
    }
    
    /**
     * Create NXDOMAIN response
     */
    fun createNxDomainResponse(query: Message): Message {
        val questions = query.getSection(Section.QUESTION)
        return Message(query.header.id).apply {
            header.setFlag(Flags.QR.toInt())
            header.rcode = Rcode.NXDOMAIN
            if (questions.isNotEmpty()) {
                addRecord(questions[0], Section.QUESTION)
            }
        }
    }
    
    /**
     * Create error response with specific rcode
     */
    fun createErrorResponse(query: Message, rcode: Int): Message {
        return Message(query.header.id).apply {
            header.setFlag(Flags.QR.toInt())
            header.rcode = rcode
        }
    }
    
    /**
     * Build HTTP response from DNS message
     */
    fun buildHttpResponse(dnsMessage: Message): NanoHTTPD.Response {
        val wireData = dnsMessage.toWire()
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/dns-message",
            ByteArrayInputStream(wireData),
            wireData.size.toLong()
        )
    }
    
    /**
     * Build HTTP response from cached wire data, updating query ID
     */
    fun buildHttpResponseFromCache(cached: ByteArray, queryId: Int): NanoHTTPD.Response {
        return try {
            val cachedMsg = Message(cached)
            
            // Log cached response details for debugging
            val answers = cachedMsg.getSection(Section.ANSWER)
            val questions = cachedMsg.getSection(Section.QUESTION)
            val questionName = if (questions.isNotEmpty()) questions[0].name.toString() else "unknown"
            val rcode = cachedMsg.header.rcode
            
            
            if (answers.isEmpty() && rcode == Rcode.NOERROR) {
            }
            
            cachedMsg.header.id = queryId
            val updatedWireData = cachedMsg.toWire()
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/dns-message",
                ByteArrayInputStream(updatedWireData),
                updatedWireData.size.toLong()
            )
        } catch (e: Exception) {
            // Fallback: return cached response as-is (might work if query ID happens to match)
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/dns-message",
                ByteArrayInputStream(cached),
                cached.size.toLong()
            )
        }
    }
    
    /**
     * Copy DNS response preserving query ID
     */
    fun copyResponseWithQueryId(response: Message, queryId: Int, originalQuestion: org.xbill.DNS.Record?): Message {
        return Message(queryId).apply {
            header.setFlag(Flags.QR.toInt())
            header.rcode = response.rcode
            if (response.header.getFlag(Flags.AA.toInt())) {
                header.setFlag(Flags.AA.toInt())
            }
            
            // Copy question
            val responseQuestions = response.getSection(Section.QUESTION)
            if (responseQuestions.isNotEmpty()) {
                addRecord(responseQuestions[0], Section.QUESTION)
            } else if (originalQuestion != null) {
                addRecord(originalQuestion, Section.QUESTION)
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
    }
}

