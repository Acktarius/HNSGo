package com.acktarius.hnsgo.dohservice

import android.util.Log
import com.acktarius.hnsgo.AdBlockManager
import com.acktarius.hnsgo.CacheManager
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.RecursiveResolver
import com.acktarius.hnsgo.Tld
import org.xbill.DNS.DClass
import org.xbill.DNS.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver

/**
 * Core DNS resolution logic following priority flow:
 * 1. Check cache (for both allowed and blocked outcomes)
 * 2. Check ad blocking
 * 3. If ICANN TLD -> forward to Quad9
 * 4. Otherwise -> SPV resolution
 */
object DnsResolver {
    private val fallbackResolver = SimpleResolver("9.9.9.9").apply {
        // Configure resolver with timeout
        timeout = java.time.Duration.ofSeconds(5)
        setPort(53) // Standard DNS port
    }
    
    /**
     * Resolve DNS query following priority flow
     * @return Pair of (response, shouldCache) - response can be null if resolution failed
     */
    fun resolve(
        name: String,
        type: Int,
        dclass: Int,
        queryId: Int,
        originalQuestion: org.xbill.DNS.Record?
    ): ResolutionResult {

        // Priority 1: Check cache FIRST (for both allowed and blocked outcomes)
        val cached = CacheManager.get(name, type, dclass)
        if (cached != null) {
            // Validate cached response has content
            try {
                val cachedMsg = Message(cached)
                val answers = cachedMsg.getSection(Section.ANSWER)
                val rcode = cachedMsg.header.rcode
                
                // Don't return cached error responses (REFUSED, SERVFAIL, etc.) - treat as cache miss
                // Only NOERROR and NXDOMAIN are valid cached responses
                val isValidRcode = rcode == Rcode.NOERROR || rcode == Rcode.NXDOMAIN
                
                if (!isValidRcode) {
                    CacheManager.remove(name, type, dclass) // Remove invalid cache entry
                } else if (answers.isEmpty() && rcode == Rcode.NOERROR) {
                    // NOERROR with no answers is also invalid
                    CacheManager.remove(name, type, dclass) // Remove invalid cache entry
                } else {
                    return ResolutionResult.Cached(cached)
                }
            } catch (e: Exception) {
                CacheManager.remove(name, type, dclass) // Remove corrupted cache entry
            }
        }

        // Priority 2: Check ad blocking (cache miss - check if domain should be blocked)
        if (AdBlockManager.isBlocked(name)) {
            val blockedMsg = Message(queryId).apply {
                header.setFlag(org.xbill.DNS.Flags.QR.toInt())
                header.rcode = Rcode.NXDOMAIN
                if (originalQuestion != null) {
                    addRecord(originalQuestion, Section.QUESTION)
                }
            }
            val blockedWireData = blockedMsg.toWire()
            // Cache blocked domain response (NXDOMAIN) with short TTL
            CacheManager.put(name, type, dclass, blockedWireData, 60)
            return ResolutionResult.Blocked(blockedMsg)
        }

        // Priority 3: If ICANN TLD -> forward to Quad9 (9.9.9.9)
        if (Tld.isIcannDomain(name)) {
            return resolveIcannDomain(name, type, dclass, queryId)
        }

        // Priority 4: Otherwise -> SPV resolution as needed
        return resolveHandshakeDomain(name, type, dclass, queryId, originalQuestion)
    }
    
    private fun resolveIcannDomain(
        name: String,
        type: Int,
        dclass: Int,
        queryId: Int
    ): ResolutionResult {
        return try {
            // Check cache first
            val cached = CacheManager.get(name, type, dclass)
            if (cached != null) {
                return ResolutionResult.Cached(cached)
            }
            
            // Forward to Quad9
            // Use Message.newQuery() to create a proper DNS query (same format as RecursiveResolver)
            val queryName = org.xbill.DNS.Name.fromString("$name.")
            val query = org.xbill.DNS.Message.newQuery(
                org.xbill.DNS.Record.newRecord(queryName, type, dclass)
            )
            query.header.id = queryId // Set the query ID from the original request
            
            val response = try {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        fallbackResolver.send(query)
                    }
                }
            } catch (e: Exception) {
                return ResolutionResult.Failure(Rcode.SERVFAIL, "DNS query failed: ${e.message}")
            }
            
            // Log response details for debugging
            val rcode = response.header.rcode
            val answers = response.getSection(Section.ANSWER)
            
            // Check if Quad9 refused the query
            if (rcode == Rcode.REFUSED) {
                // Try system DNS as fallback
                return try {
                    val systemResolver = SimpleResolver().apply {
                        timeout = java.time.Duration.ofSeconds(5)
                    }
                    val systemResponse = runBlocking {
                        withContext(Dispatchers.IO) {
                            systemResolver.send(query)
                        }
                    }
                    val systemRcode = systemResponse.header.rcode
                    val systemAnswers = systemResponse.getSection(Section.ANSWER)
                    
                    if (systemRcode == Rcode.NOERROR && systemAnswers.isNotEmpty()) {
                        val ttl = systemAnswers.minOfOrNull { it.ttl }?.toInt() ?: Config.DNS_CACHE_TTL_SECONDS
                        val wireData = systemResponse.toWire()
                        CacheManager.put(name, type, dclass, wireData, ttl)
                    }
                    ResolutionResult.Success(systemResponse)
                } catch (e: Exception) {
                    ResolutionResult.Failure(Rcode.SERVFAIL, "Both Quad9 and system DNS failed: ${e.message}")
                }
            }
            
            // Only cache successful responses (NOERROR with answers, or NXDOMAIN)
            val isValidResponse = (rcode == Rcode.NOERROR && answers.isNotEmpty()) || rcode == Rcode.NXDOMAIN
            
            if (isValidResponse) {
                val ttl = if (answers.isNotEmpty()) {
                    answers.minOfOrNull { it.ttl }?.toInt() ?: Config.DNS_CACHE_TTL_SECONDS
                } else {
                    Config.DNS_CACHE_TTL_SECONDS
                }
                val wireData = response.toWire()
                CacheManager.put(name, type, dclass, wireData, ttl)
            } else {
            }
            
            ResolutionResult.Success(response)
        } catch (e: Exception) {
            ResolutionResult.Failure(Rcode.SERVFAIL, "ICANN DNS resolution failed: ${e.message}")
        }
    }
    
    private fun resolveHandshakeDomain(
        name: String,
        type: Int,
        dclass: Int,
        queryId: Int,
        originalQuestion: org.xbill.DNS.Record?
    ): ResolutionResult {
        // Try Handshake recursive resolution (with timeout to avoid hanging)
        val response = try {
            runBlocking { 
                withTimeoutOrNull(Config.HANDSHAKE_RESOLUTION_TIMEOUT_MS) {
                    RecursiveResolver.resolve(name, type)
                }
            }
        } catch (e: Exception) {
            null
        }
    
        if (response != null) {
            
            // Cache successful Handshake resolution
            val answers = response.getSection(Section.ANSWER)
            val ttl = if (answers.isNotEmpty()) {
                answers.minOfOrNull { it.ttl }?.toInt() ?: Config.DNS_CACHE_TTL_SECONDS
            } else {
                Config.DNS_CACHE_TTL_SECONDS
            }
            val wireData = response.toWire()
            CacheManager.put(name, type, dclass, wireData, ttl)
            
            return ResolutionResult.Success(response)
        }
        
        // SPV resolution failed
        CacheManager.remove(name, type, dclass)
        return ResolutionResult.Failure(Rcode.SERVFAIL, "Handshake resolution failed - P2P queries timed out or TLD not found")
    }
    
    /**
     * Forward DNS query to upstream resolver (Quad9)
     */
    fun forwardToDns(query: Message, name: String?): Message {
        val questions = query.getSection(Section.QUESTION)
        val domainName = name ?: (if (questions.isNotEmpty()) questions[0].name.toString() else "unknown")
        val queryType = if (questions.isNotEmpty()) questions[0].type else Type.A
        val queryClass = DClass.IN
        
        // Check cache first
        val cached = CacheManager.get(domainName, queryType, queryClass)
        if (cached != null) {
            return Message(cached)
        }
        
        
        // Query upstream DNS (9.9.9.9)
        val response = runBlocking {
            withContext(Dispatchers.IO) {
                fallbackResolver.send(query)
            }
        }
        
        // Cache the response
        val answers = response.getSection(Section.ANSWER)
        val ttl = if (answers.isNotEmpty()) {
            answers.minOfOrNull { it.ttl }?.toInt() ?: Config.DNS_CACHE_TTL_SECONDS
        } else {
            Config.DNS_CACHE_TTL_SECONDS
        }
        
        val wireData = response.toWire()
        CacheManager.put(domainName, queryType, queryClass, wireData, ttl)
        
        return response
    }
    
    /**
     * Result of DNS resolution
     */
    sealed class ResolutionResult {
        data class Cached(val wireData: ByteArray) : ResolutionResult()
        data class Blocked(val message: Message) : ResolutionResult()
        data class Success(val message: Message) : ResolutionResult()
        data class Failure(val rcode: Int, val errorMessage: String) : ResolutionResult()
    }
}

