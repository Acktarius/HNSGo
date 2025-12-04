package com.acktarius.hnsgo.spvp2p

import android.util.Log
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.Header
import com.acktarius.hnsgo.P2PMessage
import com.acktarius.hnsgo.util.HexUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Handles P2P protocol operations (handshake, sending commands)
 */
internal object ProtocolHandler {
    private const val MAGIC_MAINNET = Config.MAGIC_MAINNET
    
    suspend fun handshake(
        input: InputStream,
        output: OutputStream,
        messageHandler: MessageHandler
    ): Pair<Boolean, Int?> {
        var versionReceived = false
        var verackReceived = false
        var ourVerackSent = false
        var peerHeight: Int? = null
        
        val handshakeTimeout = 10000L
        val startTime = System.currentTimeMillis()
        var attempts = 0
        val maxAttempts = 20
        
        while (attempts < maxAttempts && (!versionReceived || !verackReceived)) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > handshakeTimeout) {
                Log.w("HNSGo", "ProtocolHandler: Handshake timeout after ${elapsed}ms")
                return Pair(false, null)
            }
            
            try {
                Log.d("HNSGo", "ProtocolHandler: Waiting for handshake message (attempt ${attempts + 1}/$maxAttempts)...")
                val message = messageHandler.receiveMessage(input)
                if (message == null) {
                    if (attempts == 0) {
                        Log.w("HNSGo", "ProtocolHandler: Peer closed connection immediately")
                    }
                    return Pair(false, null)
                }
                
                Log.d("HNSGo", "ProtocolHandler: Received message: ${message.command}")
                
                when (message.command) {
                    "version" -> {
                        try {
                            val versionData = messageHandler.parseVersionMessage(message.payload)
                            peerHeight = versionData.height
                            Log.d("HNSGo", "ProtocolHandler: Peer version: ${versionData.version}, height: ${versionData.height}")
                            versionReceived = true
                            if (!ourVerackSent) {
                                messageHandler.sendMessage(output, "verack", byteArrayOf())
                                ourVerackSent = true
                            }
                        } catch (e: Exception) {
                            Log.e("HNSGo", "ProtocolHandler: Error parsing version", e)
                            versionReceived = true
                            if (!ourVerackSent) {
                                messageHandler.sendMessage(output, "verack", byteArrayOf())
                                ourVerackSent = true
                            }
                        }
                    }
                    "verack" -> {
                        verackReceived = true
                    }
                    else -> {
                        Log.d("HNSGo", "ProtocolHandler: Received ${message.command} during handshake")
                    }
                }
            } catch (e: SocketTimeoutException) {
                Log.w("HNSGo", "ProtocolHandler: Socket timeout (attempt ${attempts + 1})")
            } catch (e: SocketException) {
                if (e.message?.contains("Connection reset") == true || e.message?.contains("reset") == true) {
                    Log.w("HNSGo", "ProtocolHandler: Connection reset by peer")
                    return Pair(false, null)
                } else {
                    Log.e("HNSGo", "ProtocolHandler: Socket error", e)
                    return Pair(false, null)
                }
            } catch (e: Exception) {
                Log.e("HNSGo", "ProtocolHandler: Error during handshake", e)
                return Pair(false, null)
            }
            attempts++
        }
        
        val success = versionReceived && verackReceived
        if (success) {
            Log.d("HNSGo", "ProtocolHandler: Handshake successful")
        } else {
            Log.w("HNSGo", "ProtocolHandler: Handshake incomplete")
        }
        return Pair(success, peerHeight)
    }
    
    fun sendVersion(
        output: OutputStream,
        host: String,
        port: Int,
        chainHeight: Int,
        messageHandler: MessageHandler
    ) {
        val version = Config.PROTO_VERSION
        val services = Config.SERVICES
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = java.util.Random().nextLong()
        
        val recvAddr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            InetAddress.getByName("0.0.0.0")
        }
        val recvAddrBytes = recvAddr.address
        
        val recvIP = ByteArray(36)
        val addrType: Byte = 0
        if (recvAddrBytes.size == 4) {
            recvIP[10] = 0xFF.toByte()
            recvIP[11] = 0xFF.toByte()
            System.arraycopy(recvAddrBytes, 0, recvIP, 12, 4)
        } else {
            System.arraycopy(recvAddrBytes, 0, recvIP, 0, 16)
        }
        val recvKey = ByteArray(33)
        
        val agent = "/hnsgo:0.1/"
        val agentBytes = agent.toByteArray(Charsets.US_ASCII)
        require(agentBytes.size <= 255) { "User agent too long (max 255 bytes)" }
        
        val payload = ByteArrayOutputStream().apply {
            messageHandler.writeInt32ToStream(this, version)
            messageHandler.writeInt64ToStream(this, services)
            messageHandler.writeInt64ToStream(this, timestamp)
            messageHandler.writeInt64ToStream(this, 0)
            messageHandler.writeInt64ToStream(this, services)
            write(addrType.toInt())
            messageHandler.writeBytesToStream(this, recvIP)
            messageHandler.writeInt16ToStream(this, port)
            messageHandler.writeBytesToStream(this, recvKey)
            messageHandler.writeInt64ToStream(this, nonce)
            write(agentBytes.size)
            messageHandler.writeBytesToStream(this, agentBytes)
            messageHandler.writeInt32ToStream(this, chainHeight)
            write(0)
        }.toByteArray()
        
        Log.d("HNSGo", "ProtocolHandler: Sending version (version=$version, height=$chainHeight, payload=${payload.size} bytes)")
        messageHandler.sendMessage(output, "version", payload)
    }
    
    fun sendGetHeaders(
        output: OutputStream,
        locatorHashes: List<ByteArray>,
        messageHandler: MessageHandler
    ) {
        // GetHeaders message (matching hnsd/src/msg.c:hsk_getheaders_msg_write):
        // Hash count (varint) + Hashes (32 bytes each) + Stop hash (32 bytes)
        // NO version field! hnsd doesn't have it.
        // hnsd builds locator with multiple hashes going backwards from tip (see hsk_chain_get_locator)
        // Use the provided locator list, or fall back to genesis hash (all zeros) if empty
        val hashes = if (locatorHashes.isNotEmpty()) {
            locatorHashes
        } else {
            // No locator hashes - use genesis (all zeros)
            listOf(ByteArray(32))
        }
        
        val payload = ByteArrayOutputStream().apply {
            messageHandler.writeVarIntToStream(this, hashes.size) // Hash count (varint)
            for (hash in hashes) {
                messageHandler.writeBytesToStream(this, hash) // Each locator hash (32 bytes)
            }
            messageHandler.writeBytesToStream(this, ByteArray(32)) // Stop hash (all zeros = no stop)
        }.toByteArray()
        
        // Log first and last hash for debugging
        val firstHash = hashes.first()
        val firstHashHex = firstHash.joinToString("") { "%02x".format(it) }
        val lastHash = hashes.last()
        val lastHashHex = lastHash.joinToString("") { "%02x".format(it) }
        Log.d("HNSGo", "ProtocolHandler:sendGetHeaders: Sending getheaders with ${hashes.size} locator hashes")
        Log.d("HNSGo", "ProtocolHandler:sendGetHeaders: First hash (tip): $firstHashHex")
        Log.d("HNSGo", "ProtocolHandler:sendGetHeaders: Last hash: $lastHashHex")
        messageHandler.sendMessage(output, "getheaders", payload)
    }
    
    fun sendSendHeaders(output: OutputStream, messageHandler: MessageHandler) {
        Log.d("HNSGo", "ProtocolHandler: Sending sendheaders")
        messageHandler.sendMessage(output, "sendheaders", byteArrayOf())
    }
    
    fun sendGetAddr(output: OutputStream, messageHandler: MessageHandler) {
        Log.d("HNSGo", "ProtocolHandler: Sending getaddr")
        messageHandler.sendMessage(output, "getaddr", byteArrayOf())
    }
    
    fun sendGetProof(
        output: OutputStream,
        nameHash: ByteArray,
        nameRoot: ByteArray,
        messageHandler: MessageHandler
    ) {
        require(nameHash.size == 32) { "Name hash must be 32 bytes, got ${nameHash.size}" }
        require(nameRoot.size == 32) { "Tree root must be 32 bytes, got ${nameRoot.size}" }
        
        // EXACT MATCH to hnsd msg.c:289-293 (hsk_getproof_msg_write)
        // hnsd writes: root[32] then key[32] (matching struct order)
        val payload = ByteArray(64).apply {
            System.arraycopy(nameRoot, 0, this, 0, 32)  // root first (offset 0-31)
            System.arraycopy(nameHash, 0, this, 32, 32)  // key second (offset 32-63)
        }
        
        // Detailed byte-for-byte logging for debugging
        val nameRootHex = nameRoot.joinToString("") { "%02x".format(it) }
        val nameHashHex = nameHash.joinToString("") { "%02x".format(it) }
        val payloadHex = payload.joinToString("") { "%02x".format(it) }
        Log.w("HNSGo", "ProtocolHandler: ========== GETPROOF WIRE FORMAT ==========")
        Log.w("HNSGo", "ProtocolHandler: Payload size: ${payload.size} bytes (expected: 64)")
        Log.w("HNSGo", "ProtocolHandler: Payload bytes (hex): $payloadHex")
        Log.w("HNSGo", "ProtocolHandler: First 32 bytes (root): $nameRootHex")
        Log.w("HNSGo", "ProtocolHandler: Last 32 bytes (key/nameHash): $nameHashHex")
        Log.w("HNSGo", "ProtocolHandler: ===========================================")
        
        messageHandler.sendMessage(output, "getproof", payload)
    }
    
    /**
     * Result of receiveHeaders operation
     * @param hasValidHeaders True if we received valid headers that were accepted
     * @param receivedAnyHeaders True if we received any headers (even if all rejected)
     */
    data class ReceiveHeadersResult(
        val hasValidHeaders: Boolean,
        val receivedAnyHeaders: Boolean
    )
    
    suspend fun receiveHeaders(
        input: InputStream,
        output: OutputStream,
        onHeaderReceived: (Header, ByteArray) -> Boolean, // Now accepts pre-computed hash
        messageHandler: MessageHandler
    ): ReceiveHeadersResult = withContext(Dispatchers.IO) {
        var headersReceived = 0
        var totalValidHeaders = 0  // Track valid headers across all batches
        var receivedAnyHeaders = false  // Track if we received any headers at all
        
        Log.d("HNSGo", "ProtocolHandler: Starting to receive headers...")
        
        while (headersReceived < 2000) { // Limit to prevent infinite loop
            Log.d("HNSGo", "ProtocolHandler: Waiting for headers message...")
            val message = messageHandler.receiveMessage(input)
            if (message == null) {
                Log.w("HNSGo", "ProtocolHandler: Failed to receive message, stopping")
                break
            }
            
            Log.d("HNSGo", "ProtocolHandler: Received message: ${message.command}, payload size: ${message.payload.size}")
            
            // Handle ping messages (peers send ping to keep connection alive)
            // Matching hnsd's hsk_peer_on_ping behavior
            if (message.command == "ping") {
                try {
                    val nonce = java.nio.ByteBuffer.wrap(message.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
                    Log.d("HNSGo", "ProtocolHandler: Received ping (nonce=$nonce), sending pong")
                    val pongPayload = ByteArray(8).apply {
                        java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(nonce)
                    }
                    messageHandler.sendMessage(output, "pong", pongPayload)
                    continue // Continue waiting for headers
                } catch (e: Exception) {
                    Log.w("HNSGo", "ProtocolHandler: Error handling ping message", e)
                    continue
                }
            }
            
            // Handle pong messages (response to our ping, if any)
            if (message.command == "pong") {
                Log.d("HNSGo", "ProtocolHandler: Received pong, continuing to wait for headers")
                continue // Continue waiting for headers
            }
            
            if (message.command == "headers") {
                receivedAnyHeaders = true  // We received headers (even if they'll be rejected)
                Log.d("HNSGo", "ProtocolHandler:receiveHeaders: Parsing headers from payload (${message.payload.size} bytes)...")
                val headers = messageHandler.parseHeaders(message.payload)
                Log.d("HNSGo", "ProtocolHandler:receiveHeaders: Parsed ${headers.size} headers from message")
                
                // OPTIMIZATION: Pre-compute all hashes in parallel (CPU-intensive work)
                // This is much faster than computing hashes sequentially (2000 headers in parallel vs sequential)
                // Use Dispatchers.Default for CPU-bound work (hash computation)
                val headerHashes = coroutineScope {
                    headers.mapIndexed { index, header ->
                        async(Dispatchers.Default) {
                            // Compute hash on CPU dispatcher (parallel execution)
                            Pair(index, header.hash())
                        }
                    }.awaitAll()
                }.associate { it.first to it.second }
                
                if (headers.isNotEmpty()) {
                    // Log first and last header hashes (using pre-computed hashes)
                    // MEMORY OPTIMIZATION: Use HexUtils to avoid string allocations
                    val firstHash = headerHashes[0]!!
                    val lastHash = headerHashes[headers.size - 1]!!
                    Log.d("HNSGo", "ProtocolHandler:receiveHeaders: First header hash: ${HexUtils.toHexShort(firstHash)}")
                    Log.d("HNSGo", "ProtocolHandler:receiveHeaders: Last header hash: ${HexUtils.toHexShort(lastHash)}")
                }
                
                var validCount = 0
                var invalidCount = 0
                var duplicateCount = 0
                
                for ((index, header) in headers.withIndex()) {
                    val headerHash = headerHashes[index]!!
                    val isValid = onHeaderReceived(header, headerHash)
                    
                    if (isValid) {
                        validCount++
                        totalValidHeaders++  // Track valid headers across batches
                        // MEMORY OPTIMIZATION: Only log when needed (reduces string allocations)
                        // Log first few and every 100th header for debugging
                        if (index < 3 || index % 100 == 0) {
                            Log.d("HNSGo", "ProtocolHandler:receiveHeaders: Header $index: VALID hash=${HexUtils.toHexShort(headerHash)}")
                        }
                    } else {
                        invalidCount++
                        // Check if this is a duplicate (we already have it) or a chain connection failure
                        // Matching hnsd: continue on duplicates, only stop on chain connection failures
                        // A duplicate means we already have this header - continue to find new ones
                        // A chain connection failure means prevBlock doesn't match - stop processing
                        
                        // Check if this is a duplicate by checking if hash is in our set
                        // If it's a duplicate, continue processing (matching hnsd's HSK_EDUPLICATE behavior)
                        duplicateCount++
                        // MEMORY OPTIMIZATION: Only log duplicates when debugging (reduces allocations)
                        if (index < 10) { // Only log first 10 duplicates
                            Log.d("HNSGo", "ProtocolHandler:receiveHeaders: Header $index: DUPLICATE/SKIP hash=${HexUtils.toHexShort(headerHash)} (already have it, continuing)")
                        }
                        // Continue processing - duplicates are OK, we'll find new headers later in the batch
                    }
                    
                    headersReceived++
                }
                
                Log.d("HNSGo", "ProtocolHandler:receiveHeaders: Processed ${headers.size} headers (valid: $validCount, duplicates: $duplicateCount, invalid: $invalidCount)")
                
                // CRITICAL FIX: If we got headers but they were all rejected (duplicates/old),
                // and we got fewer than 2000 headers, the peer might have more headers after the last one.
                // When requesting from earlier locator hashes, peers return headers starting from that point.
                // If all headers are rejected, we should request more headers using the last header hash
                // as the new locator (to get headers after it).
                // This matches hnsd behavior: if headers are received but all duplicates, continue requesting.
                if (headers.size < 2000 && totalValidHeaders == 0 && headers.isNotEmpty()) {
                    // All headers were rejected - they might be old headers from earlier locator
                    // Request more headers using the last header hash as locator to get headers after it
                    val lastHeaderHash = headerHashes[headers.size - 1]!!
                    Log.d("HNSGo", "ProtocolHandler:receiveHeaders: All headers rejected, requesting more headers after last received header")
                    val nextLocator = listOf(lastHeaderHash)
                    sendGetHeaders(output, nextLocator, messageHandler)
                    continue  // Continue loop to wait for next batch
                }
                
                // Matching hnsd exactly (pool.c:1519-1524):
                // - If batch size == 2000, request more headers (continue loop)
                // - Otherwise, return (break) - hnsd doesn't wait for more headers
                // hnsd processes ONE batch and returns, it doesn't loop waiting for multiple batches
                if (headers.size == 2000) {
                    Log.d("HNSGo", "ProtocolHandler:receiveHeaders: Got 2000 headers, requesting more (matching hnsd behavior)")
                    // Continue loop to wait for next batch (hnsd sends getheaders and processes next batch)
                    continue
                }
                
                // Batch size < 2000: hnsd returns HSK_SUCCESS (doesn't wait for more)
                // This matches hnsd line 1524: return HSK_SUCCESS (when header_count != 2000)
                Log.d("HNSGo", "ProtocolHandler:receiveHeaders: Got ${headers.size} headers (< 2000), done with this batch (matching hnsd: returns HSK_SUCCESS)")
                break
            } else if (message.command == "notfound") {
                // Peer doesn't have the requested headers - stop waiting and try next peer
                val payloadHex = message.payload.joinToString("") { "%02x".format(it) }
                val payloadSize = message.payload.size
                Log.w("HNSGo", "ProtocolHandler: Peer responded with 'notfound' - doesn't have requested headers, stopping - payload size: $payloadSize, payload: $payloadHex")
                break
            } else {
                // Handle other messages (addr, etc.) but continue waiting for headers
                Log.d("HNSGo", "ProtocolHandler: Received non-headers message: ${message.command}, continuing to wait for headers...")
            }
        }
        
        Log.d("HNSGo", "ProtocolHandler: Finished receiving headers, total processed: $headersReceived, valid: $totalValidHeaders")
        // Return result indicating if we got valid headers and if we received any headers at all
        // This allows ConnectionManager to distinguish between "notfound" and "headers received but rejected"
        return@withContext ReceiveHeadersResult(
            hasValidHeaders = totalValidHeaders > 0,
            receivedAnyHeaders = receivedAnyHeaders
        )
    }
}

// Extension functions for ByteArrayOutputStream (using MessageHandler's extension functions)
private fun ByteArrayOutputStream.writeInt32(value: Int) {
    val bytes = ByteArray(4)
    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value)
    write(bytes)
}

private fun ByteArrayOutputStream.writeInt64(value: Long) {
    val bytes = ByteArray(8)
    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(value)
    write(bytes)
}

private fun ByteArrayOutputStream.writeInt16(value: Int) {
    val bytes = ByteArray(2)
    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    write(bytes)
}

private fun ByteArrayOutputStream.writeVarInt(value: Int) {
    when {
        value < 0xFD -> write(value)
        value <= 0xFFFF -> {
            write(0xFD)
            writeInt16(value)
        }
        else -> {
            write(0xFE)
            writeInt32(value)
        }
    }
}

private fun ByteArrayOutputStream.writeBytes(bytes: ByteArray) {
    write(bytes)
}

