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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles P2P protocol operations (handshake, sending commands)
 */
internal object ProtocolHandler {
    private const val MAGIC_MAINNET = Config.MAGIC_MAINNET
    
    suspend fun handshake(
        input: InputStream,
        output: OutputStream,
        messageHandler: MessageHandler
    ): Triple<Boolean, Int?, Long?> {
        var versionReceived = false
        var verackReceived = false
        var ourVerackSent = false
        var peerHeight: Int? = null
        var peerServices: Long? = null
        
        // hsd uses 5 seconds, hnsd uses 10 seconds - use 5 seconds to match full nodes
        val handshakeTimeout = 5000L
        val startTime = System.currentTimeMillis()
        var attempts = 0
        val maxAttempts = 20
        
        while (attempts < maxAttempts && (!versionReceived || !verackReceived)) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > handshakeTimeout) {
                return Triple(false, null, null)
            }
            
            try {
                val message = messageHandler.receiveMessage(input)
                if (message == null) {
                    if (attempts == 0) {
                    }
                    return Triple(false, null, null)
                }
                
                when (message.command) {
                    "version" -> {
                        try {
                            val versionData = messageHandler.parseVersionMessage(message.payload)
                            peerHeight = versionData.height
                            peerServices = versionData.services
                            versionReceived = true
                            if (!ourVerackSent) {
                                messageHandler.sendMessage(output, "verack", byteArrayOf())
                                ourVerackSent = true
                            }
                        } catch (e: Exception) {
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
                    "ping" -> {
                        // Handle ping during handshake (matching hnsd's hsk_peer_handle_ping)
                        // Respond with pong but continue handshake
                        try {
                            if (message.payload.size >= 8) {
                                val nonce = ByteBuffer.wrap(message.payload.sliceArray(0..7))
                                    .order(ByteOrder.LITTLE_ENDIAN).long
                                val pongPayload = ByteArray(8).apply {
                                    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putLong(nonce)
                                }
                                messageHandler.sendMessage(output, "pong", pongPayload)
                            }
                        } catch (e: Exception) {
                        }
                        // Continue handshake - don't break
                    }
                    else -> {
                        // Ignore other messages during handshake
                    }
                }
            } catch (e: SocketTimeoutException) {
            } catch (e: SocketException) {
                if (e.message?.contains("Connection reset") == true || e.message?.contains("reset") == true) {
                    return Triple(false, null, null)
                } else {
                    return Triple(false, null, null)
                }
            } catch (e: Exception) {
                return Triple(false, null, null)
            }
            attempts++
        }
        
        val success = versionReceived && verackReceived
        if (!success) {
        }
        return Triple(success, peerHeight, peerServices)
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
        
        messageHandler.sendMessage(output, "getheaders", payload)
    }
    
    fun sendSendHeaders(output: OutputStream, messageHandler: MessageHandler) {
        messageHandler.sendMessage(output, "sendheaders", byteArrayOf())
    }
    
    fun sendGetAddr(output: OutputStream, messageHandler: MessageHandler) {
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
        
        // EXACT MATCH to hnsd msg.c:hsk_getproof_msg_write (lines 289-293)
        // hnsd struct definition (hsk_getproof_msg_t):
        //   uint8_t cmd;
        //   uint8_t root[32];  // First field after cmd
        //   uint8_t key[32];   // Second field after cmd
        // hsk_getproof_msg_write() serialization order (CONFIRMED):
        //   write_bytes(data, msg->root, 32);  // root first
        //   write_bytes(data, msg->key, 32);    // key second
        // Wire format: root[32] + key[32] = 64 bytes total
        val payload = ByteArray(64).apply {
            System.arraycopy(nameRoot, 0, this, 0, 32)  // root first (offset 0-31) - matches struct field order
            System.arraycopy(nameHash, 0, this, 32, 32)  // key second (offset 32-63)
        }
        
        // Detailed byte-for-byte logging for debugging
        val nameRootHex = nameRoot.joinToString("") { "%02x".format(it) }
        val nameHashHex = nameHash.joinToString("") { "%02x".format(it) }
        val payloadHex = payload.joinToString("") { "%02x".format(it) }
        
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
        
        while (headersReceived < 2000) { // Limit to prevent infinite loop
            val message = messageHandler.receiveMessage(input)
            if (message == null) {
                break
            }
            
            // Handle ping messages (peers send ping to keep connection alive)
            // Matching hnsd's hsk_peer_on_ping behavior
            if (message.command == "ping") {
                try {
                    val nonce = java.nio.ByteBuffer.wrap(message.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
                    val pongPayload = ByteArray(8).apply {
                        java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(nonce)
                    }
                    messageHandler.sendMessage(output, "pong", pongPayload)
                    continue // Continue waiting for headers
                } catch (e: Exception) {
                    continue
                }
            }
            
            // Handle pong messages (response to our ping, if any)
            if (message.command == "pong") {
                continue // Continue waiting for headers
            }
            
            // Handle inv (inventory) messages (full nodes send these to announce new blocks/txs)
            // We're SPV, so we ignore them and continue waiting for headers
            if (message.command == "inv") {
                continue // Continue waiting for headers
            }
            
            if (message.command == "headers") {
                receivedAnyHeaders = true  // We received headers (even if they'll be rejected)
                val headers = messageHandler.parseHeaders(message.payload)
                
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
                
                var validCount = 0
                var invalidCount = 0
                var duplicateCount = 0
                
                for ((index, header) in headers.withIndex()) {
                    val headerHash = headerHashes[index]!!
                    val isValid = onHeaderReceived(header, headerHash)
                    
                    if (isValid) {
                        validCount++
                        totalValidHeaders++  // Track valid headers across batches
                    } else {
                        invalidCount++
                        // Check if this is a duplicate (we already have it) or a chain connection failure
                        // Matching hnsd: continue on duplicates, only stop on chain connection failures
                        duplicateCount++
                        // Continue processing - duplicates are OK, we'll find new headers later in the batch
                    }
                    
                    headersReceived++
                }
                
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
                    val nextLocator = listOf(lastHeaderHash)
                    sendGetHeaders(output, nextLocator, messageHandler)
                    continue  // Continue loop to wait for next batch
                }
                
                // Matching hnsd exactly (pool.c:1519-1524):
                // - If batch size == 2000, request more headers (continue loop)
                // - Otherwise, return (break) - hnsd doesn't wait for more headers
                if (headers.size == 2000) {
                    // Continue loop to wait for next batch (hnsd sends getheaders and processes next batch)
                    continue
                }
                
                // Batch size < 2000: hnsd returns HSK_SUCCESS (doesn't wait for more)
                break
            } else if (message.command == "notfound") {
                // Peer doesn't have the requested headers - stop waiting and try next peer
                val payloadHex = message.payload.joinToString("") { "%02x".format(it) }
                val payloadSize = message.payload.size
                break
            }
        }
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

