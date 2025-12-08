package com.acktarius.hnsgo.spvp2p

import android.util.Log
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.DnsSeedDiscovery
import com.acktarius.hnsgo.FullNodePeers
import com.acktarius.hnsgo.HardcodedPeers
import com.acktarius.hnsgo.Header
import com.acktarius.hnsgo.P2PMessage
import com.acktarius.hnsgo.PeerErrorTracker
import com.acktarius.hnsgo.PeerDiscovery
import com.acktarius.hnsgo.SpvClient
import com.acktarius.hnsgo.spvclient.NameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Result of connectAndSync operation
 * @param success True if headers were received
 * @param peerHeight Peer's reported height (if available)
 * @param wasError True if this was an actual error (connection/handshake failure), false if valid protocol response (notfound, etc.)
 */
data class SyncAttemptResult(
    val success: Boolean,
    val peerHeight: Int?,
    val wasError: Boolean
)

/**
 * Handles connection management and high-level P2P operations
 */
internal object ConnectionManager {
    private var dataDir: File? = null
    
    fun init(dataDir: File) {
        this.dataDir = dataDir
        HardcodedPeers.init(dataDir)
        PeerErrorTracker.init(dataDir)
        FullNodePeers.init(dataDir)
    }
    
    suspend fun discoverPeers(): List<String> = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        val allPeers = mutableSetOf<String>()
        
        val verifiedPeers = HardcodedPeers.loadPeers()
        
        val dnsPeers = try {
            withTimeoutOrNull(30000L) {
                DnsSeedDiscovery.discoverPeersFromDnsSeeds()
            } ?: emptyList()
        } catch (e: TimeoutCancellationException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        
        if (dnsPeers.isNotEmpty()) {
            allPeers.addAll(dnsPeers)
            
            try {
                if (HardcodedPeers.isEmpty()) {
                    HardcodedPeers.addPeers(dnsPeers)
                } else {
                    HardcodedPeers.addPeers(dnsPeers)
                }
            } catch (e: Exception) {
            }
        }
        
        if (allPeers.isEmpty()) {
            val fallbackPeers = HardcodedPeers.getFallbackPeers()
            if (fallbackPeers.isNotEmpty()) {
                allPeers.addAll(fallbackPeers)
            } else {
            }
        }
        
        val peerList = allPeers.toList()
        return@withContext peerList
    }
    
    suspend fun recordSuccessfulPeer(peer: String) = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        if (DnsSeedDiscovery.isValidPeerAddress(peer)) {
            HardcodedPeers.addPeers(listOf(peer))
            PeerErrorTracker.resetErrors(peer)
        }
    }
    
    suspend fun connectWithRetry(
        host: String,
        port: Int,
        maxRetries: Int = 3
    ): Socket? = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                val socket = Socket()
                // Configure socket for P2P (matching hnsd/libuv best practices)
                // TCP_NODELAY: Disable Nagle's algorithm for low-latency P2P communication
                socket.tcpNoDelay = true
                // Keep-alive: Detect dead connections faster (important for long-lived P2P connections)
                socket.keepAlive = true
                // Connect timeout: 10 seconds (from Config.P2P_CONNECT_TIMEOUT_MS)
                socket.connect(InetSocketAddress(host, port), Config.P2P_CONNECT_TIMEOUT_MS)
                // Socket read timeout: 30 seconds (from Config.P2P_SOCKET_TIMEOUT_MS)
                socket.soTimeout = Config.P2P_SOCKET_TIMEOUT_MS
                return@withContext socket
            } catch (e: ConnectException) {
                lastException = e
                if (attempt < maxRetries) {
                    delay(1000L * attempt)
                }
            } catch (e: SocketTimeoutException) {
                lastException = e
                if (attempt < maxRetries) {
                    delay(1000L * attempt)
                }
            } catch (e: Exception) {
                lastException = e
                break
            }
        }
        
        null
    }
    
    internal suspend fun connectAndSync(
        host: String,
        port: Int,
        startHeight: Int,
        locatorHashes: List<ByteArray>,
        onHeaderReceived: (Header, ByteArray) -> Boolean, // Now accepts pre-computed hash
        messageHandler: MessageHandler,
        protocolHandler: ProtocolHandler
    ): SyncAttemptResult = withContext(Config.HEADER_SYNC_DISPATCHER) {
        var socket: Socket? = null
        var peerHeight: Int? = null // Preserve peer height across exceptions
        try {
            socket = connectWithRetry(host, port)
            if (socket == null) {
                // Connection failure - this is an error
                return@withContext SyncAttemptResult(false, null, wasError = true)
            }
            
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            
            protocolHandler.sendVersion(output, host, port, startHeight, messageHandler)
            val (handshakeSuccess, extractedPeerHeight, peerServices) = protocolHandler.handshake(input, output, messageHandler)
            peerHeight = extractedPeerHeight // Store for use in exception handlers
            
            // Log peer height even if handshake fails (useful for network height tracking)
            if (peerHeight != null) {
            }
            
            // Record verified full nodes (peers with NETWORK service flag) even during header sync
            // This helps prioritize them for future name queries
            if (handshakeSuccess && peerServices != null && (peerServices and 1L) != 0L) {
                val peerAddress = "$host:$port"
                FullNodePeers.recordVerifiedFullNode(peerAddress)
            }
            
            if (!handshakeSuccess) {
                // Handshake failure - this is an error
                return@withContext SyncAttemptResult(false, peerHeight, wasError = true)
            }
            
            // Follow hnsd protocol order: sendheaders -> getaddr -> getheaders
            protocolHandler.sendSendHeaders(output, messageHandler)
            protocolHandler.sendGetAddr(output, messageHandler)
            // Try requesting headers starting with the tip hash
            // If peer returns notfound, try progressively earlier hashes from the locator
            // This handles peers that have pruned headers before our tip
            var headersReceived = false
            var locatorIndex = 0
            val maxLocatorAttempts = minOf(locatorHashes.size, 10) // Try up to 10 different locator hashes
            
            
            while (!headersReceived && locatorIndex < maxLocatorAttempts) {
                val currentLocator = if (locatorIndex == 0) {
                    locatorHashes  // First attempt: use full locator
                } else {
                    // Subsequent attempts: use single hash from locator (progressively earlier)
                    listOf(locatorHashes[locatorIndex])
                }
                
                if (locatorIndex > 0) {
                    val hashHex = currentLocator.first().joinToString("") { "%02x".format(it) }
                }
                
                protocolHandler.sendGetHeaders(output, currentLocator, messageHandler)
                val result = protocolHandler.receiveHeaders(input, output, onHeaderReceived, messageHandler)
                
                // CRITICAL FIX: Distinguish between "notfound" (peer doesn't have headers) 
                // and "headers received but all rejected" (peer has old headers, not new ones)
                // If we got headers but they were all rejected, stop trying earlier hashes - 
                // the peer doesn't have headers after our tip
                if (result.hasValidHeaders) {
                    headersReceived = true
                } else if (result.receivedAnyHeaders) {
                    // We received headers but they were all rejected (old/duplicates)
                    // This means the peer has those headers but not the ones we need
                    // Trying even earlier hashes won't help - stop and try next peer
                    break  // Stop trying earlier hashes, try next peer
                } else {
                    // "notfound" - peer doesn't have the requested hash, try earlier
                    locatorIndex++
                }
            }
            
            // NOTE: hnsd does NOT use genesis fallback - it just keeps retrying with the same locator
            // However, when starting from checkpoint, peers may have pruned old headers
            // So we use genesis fallback ONLY when at checkpoint height (one-time bootstrap)
            // Matching hnsd behavior: hnsd would just keep retrying with same locator, trying different peers
            val checkpointEndHeight = Config.CHECKPOINT_HEIGHT + 150 - 1  // Checkpoint has 150 headers
            val isAtCheckpoint = startHeight == checkpointEndHeight  // Only at exact checkpoint end
            
            if (!headersReceived && isAtCheckpoint && locatorHashes.isNotEmpty() && locatorHashes.first().any { it != 0.toByte() }) {
                val genesisLocator = listOf(ByteArray(32))  // Genesis hash (all zeros)
                protocolHandler.sendGetHeaders(output, genesisLocator, messageHandler)
                val genesisResult = protocolHandler.receiveHeaders(input, output, onHeaderReceived, messageHandler)
                headersReceived = genesisResult.hasValidHeaders
                
                if (headersReceived) {
                } else {
                }
            } else if (!headersReceived) {
                // Matching hnsd: just log and try next peer with same locator
            }
            
            if (headersReceived) {
                recordSuccessfulPeer("$host:$port")
                return@withContext SyncAttemptResult(true, peerHeight, wasError = false)
            } else {
                // Got notfound or no headers - valid protocol response, not an error
                return@withContext SyncAttemptResult(false, peerHeight, wasError = false)
            }
        } catch (e: UnknownHostException) {
            return@withContext SyncAttemptResult(false, peerHeight, wasError = true)
        } catch (e: java.io.IOException) {
            return@withContext SyncAttemptResult(false, peerHeight, wasError = true)
        } catch (e: Exception) {
            return@withContext SyncAttemptResult(false, peerHeight, wasError = true)
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
            }
        }
    }
    
    suspend fun queryNameFromPeer(
        host: String,
        port: Int,
        nameHash: ByteArray,
        nameRoot: ByteArray, // Fallback root if headerChain not available
        chainHeight: Int,
        headerChain: List<Header>?, // Optional: if provided, calculate root based on peer height
        messageHandler: MessageHandler,
        protocolHandler: ProtocolHandler
    ): NameQueryResult = withContext(Config.NAME_QUERY_DISPATCHER) {
        var socket: Socket? = null
        try {
            socket = connectWithRetry(host, port)
            if (socket == null) {
                return@withContext NameQueryResult.Error
            }
            
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            
            protocolHandler.sendVersion(output, host, port, chainHeight, messageHandler)
            val (handshakeSuccess, peerHeight, peerServices) = protocolHandler.handshake(input, output, messageHandler)
            if (!handshakeSuccess) {
                return@withContext NameQueryResult.Error
            }
            
            
            // CRITICAL: Validate heights - peer must be within 2 blocks of our height
            // and our height must be within 2 blocks of network height
            if (peerHeight == null) {
                return@withContext NameQueryResult.Error
            }
            
            // CRITICAL: Check if peer has NETWORK service flag (hsd only uses peers with services & NETWORK for name queries)
            // NETWORK = 1 << 0 = 1 (bit 0 set indicates full node that can serve name proofs)
            // SPV nodes don't have this flag, so they can't serve name proofs
            if (peerServices == null || (peerServices and 1L) == 0L) {
                return@withContext NameQueryResult.Error
            }
            
            // Record this peer as a verified full node (has NETWORK flag)
            // This dynamically builds our list of verified full nodes
            val peerAddress = "$host:$port"
            FullNodePeers.recordVerifiedFullNode(peerAddress)
            
            // Check 1: Our local height should be within 2 blocks of network height
            val networkHeight = SpvClient.getNetworkHeight()
            if (networkHeight != null) {
                val ourHeightDiff = kotlin.math.abs(chainHeight - networkHeight)
                if (ourHeightDiff > 2) {
                    return@withContext NameQueryResult.Error
                }
            } else {
            }
            
            // Check 2: Peer height should be within 2 blocks of our local height
            val peerHeightDiff = kotlin.math.abs(peerHeight - chainHeight)
            if (peerHeightDiff > 2) {
                return@withContext NameQueryResult.Error
            }
            
            // CRITICAL: Match hnsd's exact handshake flow (pool.c:1351-1363)
            // hnsd sends sendheaders -> getaddr -> getheaders after handshake
            // This ensures the peer knows we're ready and follows the expected protocol
            protocolHandler.sendSendHeaders(output, messageHandler)
            
            // Send getaddr (matching hnsd pool.c:1357)
            protocolHandler.sendGetAddr(output, messageHandler)
            
            // Send getheaders (matching hnsd pool.c:1363)
            // For name queries, we don't need a full locator - just send empty (uses genesis)
            // This matches hnsd's handshake flow even though we don't need the headers
            protocolHandler.sendGetHeaders(output, emptyList(), messageHandler)
            
            // Consume any responses to getaddr/getheaders before sending getproof
            // Peers may send addr or headers messages, and we should read them to avoid blocking
            var consumedMessages = 0
            val maxConsumeAttempts = 5
            while (consumedMessages < maxConsumeAttempts) {
                val message = withTimeoutOrNull(100) {
                    messageHandler.receiveMessage(input)
                }
                if (message == null) {
                    break  // No more messages or timeout
                }
                consumedMessages++
                // Don't process these messages, just consume them to avoid blocking
            }
            if (consumedMessages > 0) {
            }
            
            // CRITICAL: Use actual chain root (from hsk_chain_safe_root) + name_hash
            // hnsd logs confirm: root=chain_root, name_hash=name_hash (DIFFERENT values!)
            // hnsd pool.c:521: const uint8_t *root = hsk_chain_safe_root(&pool->chain);
            // hnsd pool.c:565: return hsk_peer_send_getproof(peer, req->hash, root);
            // Wire format: root[32] (chain root) + key[32] (name_hash)
            // From hnsd logs: root=d9b6650a... name_hash=3739aae9... (different!)
            val rootToUse = nameRoot  // Use actual chain root from hsk_chain_safe_root
            
            val rootHex = rootToUse.joinToString("") { "%02x".format(it) }
            val nameHashHex = nameHash.joinToString("") { "%02x".format(it) }
            
            protocolHandler.sendGetProof(output, nameHash, rootToUse, messageHandler)
            
            var proofMessage: P2PMessage? = null
            var attempts = 0
            val maxAttempts = 20  // Allow more attempts to handle ping/pong messages
            
            while (attempts < maxAttempts && proofMessage == null) {
                val message = messageHandler.receiveMessage(input)
                if (message == null) {
                    break
                }
                
                
                when (message.command) {
                    "proof" -> {
                        proofMessage = message
                        break
                    }
                    "notfound" -> {
                        return@withContext NameQueryResult.NotFound
                    }
                    "ping" -> {
                        // Handle ping (peer sends ping to keep connection alive)
                        try {
                            val nonce = java.nio.ByteBuffer.wrap(message.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
                            val pongPayload = ByteArray(8).apply {
                                java.nio.ByteBuffer.wrap(this).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(nonce)
                            }
                            messageHandler.sendMessage(output, "pong", pongPayload)
                            // Continue waiting for proof
                        } catch (e: Exception) {
                        }
                    }
                    "pong" -> {
                        // Response to our ping (if any) - just continue waiting
                    }
                    "addr" -> {
                        // Peer may send addr messages - just continue waiting
                    }
                    "inv" -> {
                        // Peer may send inv (inventory) messages - just continue waiting
                    }
                    else -> {
                    }
                }
                attempts++
            }
            
            if (proofMessage == null) {
                return@withContext NameQueryResult.Error
            }
            
            val (resourceRecords, proof) = messageHandler.parseProofMessage(proofMessage.payload)
            if (resourceRecords.isEmpty() && proof == null) {
                return@withContext NameQueryResult.Error
            }
            
            return@withContext NameQueryResult.Success(resourceRecords, proof)
        } catch (e: Exception) {
            return@withContext NameQueryResult.Error
        } finally {
            socket?.close()
        }
    }
    
    suspend fun syncHeaders(
        startHeight: Int,
        locatorHashes: List<ByteArray>,
        onHeaderReceived: (Header, ByteArray) -> Boolean, // Now accepts pre-computed hash
        messageHandler: MessageHandler,
        protocolHandler: ProtocolHandler
    ): SyncResult = withContext(Config.HEADER_SYNC_DISPATCHER) {
        
        val discoveredPeers = try {
            withTimeoutOrNull(15000L) {
                discoverPeers()
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        
        var uniquePeers = discoveredPeers.distinct()
        
        PeerErrorTracker.loadErrors()
        var filteredPeers = PeerErrorTracker.filterExcluded(uniquePeers)
        val excludedCount = uniquePeers.size - filteredPeers.size
        val excludedPeers = uniquePeers.filter { PeerErrorTracker.shouldExclude(it) }
        
        if (excludedCount > 0) {
        }
        
        // If 50% or more peers are excluded, replace them with new peers
        val exclusionRatio = if (uniquePeers.isNotEmpty()) excludedCount.toDouble() / uniquePeers.size else 0.0
        if (exclusionRatio >= 0.5 && uniquePeers.isNotEmpty()) {
            
            // Retry peer discovery to get fresh peers
            val newPeers = try {
                withTimeoutOrNull(15000L) {
                    discoverPeers()
                } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            
            if (newPeers.isNotEmpty()) {
                // Remove excluded peers and add new ones that aren't already in the list
                val newUniquePeers = (uniquePeers - excludedPeers.toSet() + newPeers).distinct()
                
                // Clear errors for excluded peers (they're being replaced)
                excludedPeers.forEach { excludedPeer ->
                    PeerErrorTracker.resetErrors(excludedPeer)
                }
                
                val newPeersAdded = newUniquePeers.size - (uniquePeers.size - excludedPeers.size)
                uniquePeers = newUniquePeers
                filteredPeers = PeerErrorTracker.filterExcluded(uniquePeers)
            } else {
                // If we can't get new peers, at least clear errors to retry
                excludedPeers.forEach { excludedPeer ->
                    PeerErrorTracker.resetErrors(excludedPeer)
                }
                filteredPeers = uniquePeers
            }
        } else if (filteredPeers.isEmpty() && uniquePeers.isNotEmpty()) {
            // If all peers are excluded (but less than 50% threshold), clear errors and retry
            PeerErrorTracker.clearAll()
            filteredPeers = uniquePeers // Retry all peers
        }
        
        
        var maxNetworkHeight: Int? = null
        
        for (peer in filteredPeers) {
            val parts = peer.split(":")
            if (parts.size != 2) continue
            
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: Config.P2P_PORT
            
            
            val result = connectAndSync(
                host, port, startHeight, locatorHashes,
                onHeaderReceived, messageHandler, protocolHandler
            )
            
            if (result.peerHeight != null) {
                if (maxNetworkHeight == null || result.peerHeight > maxNetworkHeight) {
                    maxNetworkHeight = result.peerHeight
                }
            }
            
            if (result.success) {
                return@withContext SyncResult(true, maxNetworkHeight)
            }
            
            // Only record errors for actual failures, not valid protocol responses (notfound, etc.)
            if (result.wasError) {
                PeerErrorTracker.recordError(peer)
            } else {
            }
        }
        
        return@withContext SyncResult(false, maxNetworkHeight)
    }
}

// Internal data classes for ConnectionManager
sealed class NameQueryResult {
    data class Success(val records: List<ByteArray>, val proof: ByteArray?) : NameQueryResult()
    object NotFound : NameQueryResult()
    object Error : NameQueryResult()
}

data class SyncResult(
    val success: Boolean,
    val networkHeight: Int? = null
)

