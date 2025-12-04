package com.acktarius.hnsgo.spvp2p

import android.util.Log
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.DnsSeedDiscovery
import com.acktarius.hnsgo.HardcodedPeers
import com.acktarius.hnsgo.Header
import com.acktarius.hnsgo.P2PMessage
import com.acktarius.hnsgo.PeerErrorTracker
import com.acktarius.hnsgo.PeerDiscovery
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
    }
    
    suspend fun discoverPeers(): List<String> = withContext(Dispatchers.IO) {
        val allPeers = mutableSetOf<String>()
        
        val verifiedPeers = HardcodedPeers.loadPeers()
        
        if (verifiedPeers.isNotEmpty()) {
            HardcodedPeers.verifyAllPeers()
        }
        
        Log.d("HNSGo", "ConnectionManager: Starting peer discovery - querying DNS seeds first")
        val dnsPeers = try {
            withTimeoutOrNull(30000L) {
                DnsSeedDiscovery.discoverPeersFromDnsSeeds()
            } ?: emptyList()
        } catch (e: TimeoutCancellationException) {
            Log.w("HNSGo", "ConnectionManager: DNS seed discovery timed out")
            emptyList()
        } catch (e: Exception) {
            Log.w("HNSGo", "ConnectionManager: Error querying DNS seeds: ${e.message}")
            emptyList()
        }
        
        if (dnsPeers.isNotEmpty()) {
            Log.d("HNSGo", "ConnectionManager: Found ${dnsPeers.size} peers from DNS seeds")
            allPeers.addAll(dnsPeers)
            
            try {
                if (HardcodedPeers.isEmpty()) {
                    Log.d("HNSGo", "ConnectionManager: Hardcoded list is empty, populating with first 10 DNS-discovered peers")
                    HardcodedPeers.addPeers(dnsPeers)
                } else {
                    HardcodedPeers.addPeers(dnsPeers)
                }
            } catch (e: Exception) {
                Log.e("HNSGo", "ConnectionManager: Error adding DNS peers to hardcoded list", e)
            }
        }
        
        if (allPeers.isEmpty()) {
            Log.w("HNSGo", "ConnectionManager: No peers from DNS seeds, using hardcoded fallback")
            val fallbackPeers = HardcodedPeers.getFallbackPeers()
            if (fallbackPeers.isNotEmpty()) {
                Log.d("HNSGo", "ConnectionManager: Using ${fallbackPeers.size} hardcoded fallback peers")
                allPeers.addAll(fallbackPeers)
            } else {
                Log.w("HNSGo", "ConnectionManager: No hardcoded peers available")
            }
        }
        
        val peerList = allPeers.toList()
        Log.d("HNSGo", "ConnectionManager: Total peers discovered: ${peerList.size}")
        return@withContext peerList
    }
    
    suspend fun recordSuccessfulPeer(peer: String) = withContext(Dispatchers.IO) {
        if (DnsSeedDiscovery.isValidPeerAddress(peer)) {
            HardcodedPeers.addPeers(listOf(peer))
            PeerErrorTracker.resetErrors(peer)
            Log.d("HNSGo", "ConnectionManager: Recorded successful peer: $peer")
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
                socket.connect(InetSocketAddress(host, port), 10000)
                socket.soTimeout = 30000
                Log.d("HNSGo", "ConnectionManager: Connected to $host:$port on attempt $attempt")
                return@withContext socket
            } catch (e: ConnectException) {
                lastException = e
                Log.d("HNSGo", "ConnectionManager: Connection attempt $attempt/$maxRetries failed: ${e.message}")
                if (attempt < maxRetries) {
                    delay(1000L * attempt)
                }
            } catch (e: SocketTimeoutException) {
                lastException = e
                Log.d("HNSGo", "ConnectionManager: Connection timeout on attempt $attempt/$maxRetries")
                if (attempt < maxRetries) {
                    delay(1000L * attempt)
                }
            } catch (e: Exception) {
                lastException = e
                Log.e("HNSGo", "ConnectionManager: Unexpected error connecting to $host:$port", e)
                break
            }
        }
        
        Log.w("HNSGo", "ConnectionManager: Failed to connect to $host:$port after $maxRetries attempts")
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
    ): SyncAttemptResult = withContext(Dispatchers.IO) {
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
            
            Log.d("HNSGo", "ConnectionManager: Starting handshake for header sync to $host:$port")
            protocolHandler.sendVersion(output, host, port, startHeight, messageHandler)
            val (handshakeSuccess, extractedPeerHeight) = protocolHandler.handshake(input, output, messageHandler)
            peerHeight = extractedPeerHeight // Store for use in exception handlers
            
            // Log peer height even if handshake fails (useful for network height tracking)
            if (peerHeight != null) {
                Log.d("HNSGo", "ConnectionManager: Peer $host:$port reported height: $peerHeight")
            }
            
            if (!handshakeSuccess) {
                Log.w("HNSGo", "ConnectionManager: Handshake failed for header sync")
                // Handshake failure - this is an error
                return@withContext SyncAttemptResult(false, peerHeight, wasError = true)
            }
            
            Log.d("HNSGo", "ConnectionManager: Handshake successful, requesting headers")
            // Follow hnsd protocol order: sendheaders -> getaddr -> getheaders
            protocolHandler.sendSendHeaders(output, messageHandler)
            protocolHandler.sendGetAddr(output, messageHandler)
            // Try requesting headers starting with the tip hash
            // If peer returns notfound, try progressively earlier hashes from the locator
            // This handles peers that have pruned headers before our tip
            var headersReceived = false
            var locatorIndex = 0
            val maxLocatorAttempts = minOf(locatorHashes.size, 10) // Try up to 10 different locator hashes
            
            Log.d("HNSGo", "ConnectionManager: Starting locator loop with ${locatorHashes.size} hashes, max attempts: $maxLocatorAttempts")
            
            while (!headersReceived && locatorIndex < maxLocatorAttempts) {
                Log.d("HNSGo", "ConnectionManager: Locator loop iteration: index=$locatorIndex, max=$maxLocatorAttempts, headersReceived=$headersReceived")
                val currentLocator = if (locatorIndex == 0) {
                    locatorHashes  // First attempt: use full locator
                } else {
                    // Subsequent attempts: use single hash from locator (progressively earlier)
                    listOf(locatorHashes[locatorIndex])
                }
                
                if (locatorIndex > 0) {
                    val hashHex = currentLocator.first().joinToString("") { "%02x".format(it) }
                    Log.d("HNSGo", "ConnectionManager: Trying earlier locator hash (attempt ${locatorIndex + 1}/$maxLocatorAttempts): ${hashHex.take(16)}...")
                }
                
                protocolHandler.sendGetHeaders(output, currentLocator, messageHandler)
                val result = protocolHandler.receiveHeaders(input, output, onHeaderReceived, messageHandler)
                
                // CRITICAL FIX: Distinguish between "notfound" (peer doesn't have headers) 
                // and "headers received but all rejected" (peer has old headers, not new ones)
                // If we got headers but they were all rejected, stop trying earlier hashes - 
                // the peer doesn't have headers after our tip
                if (result.hasValidHeaders) {
                    headersReceived = true
                    Log.d("HNSGo", "ConnectionManager: Successfully received valid headers with locator index $locatorIndex")
                } else if (result.receivedAnyHeaders) {
                    // We received headers but they were all rejected (old/duplicates)
                    // This means the peer has those headers but not the ones we need
                    // Trying even earlier hashes won't help - stop and try next peer
                    Log.d("HNSGo", "ConnectionManager: Received headers but all rejected (old/duplicates) at locator index $locatorIndex - peer doesn't have headers after our tip")
                    break  // Stop trying earlier hashes, try next peer
                } else {
                    // "notfound" - peer doesn't have the requested hash, try earlier
                    Log.d("HNSGo", "ConnectionManager: Got notfound with locator index $locatorIndex, trying earlier hash")
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
                Log.d("HNSGo", "ConnectionManager: Got notfound at checkpoint height $startHeight, trying genesis fallback (one-time bootstrap)")
                Log.d("HNSGo", "ConnectionManager: This is a workaround for peers that pruned old headers - hnsd would just keep retrying")
                val genesisLocator = listOf(ByteArray(32))  // Genesis hash (all zeros)
                protocolHandler.sendGetHeaders(output, genesisLocator, messageHandler)
                val genesisResult = protocolHandler.receiveHeaders(input, output, onHeaderReceived, messageHandler)
                headersReceived = genesisResult.hasValidHeaders
                
                if (headersReceived) {
                    Log.d("HNSGo", "ConnectionManager: Genesis fallback successful!")
                } else {
                    Log.d("HNSGo", "ConnectionManager: Genesis fallback also failed - will retry with same locator (matching hnsd)")
                }
            } else if (!headersReceived) {
                // Matching hnsd: just log and try next peer with same locator
                Log.d("HNSGo", "ConnectionManager: Got notfound at height $startHeight after trying $locatorIndex locator hashes (max was $maxLocatorAttempts) - will retry with next peer")
            }
            
            if (headersReceived) {
                recordSuccessfulPeer("$host:$port")
                return@withContext SyncAttemptResult(true, peerHeight, wasError = false)
            } else {
                // Got notfound or no headers - valid protocol response, not an error
                Log.d("HNSGo", "ConnectionManager: No headers received (notfound or empty) - valid protocol response")
                return@withContext SyncAttemptResult(false, peerHeight, wasError = false)
            }
        } catch (e: UnknownHostException) {
            Log.w("HNSGo", "ConnectionManager: Unknown host $host")
            return@withContext SyncAttemptResult(false, peerHeight, wasError = true)
        } catch (e: java.io.IOException) {
            Log.e("HNSGo", "ConnectionManager: I/O error connecting to $host:$port", e)
            return@withContext SyncAttemptResult(false, peerHeight, wasError = true)
        } catch (e: Exception) {
            Log.e("HNSGo", "ConnectionManager: Unexpected error connecting to $host:$port", e)
            return@withContext SyncAttemptResult(false, peerHeight, wasError = true)
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.d("HNSGo", "ConnectionManager: Error closing socket", e)
            }
        }
    }
    
    suspend fun queryNameFromPeer(
        host: String,
        port: Int,
        nameHash: ByteArray,
        nameRoot: ByteArray,
        chainHeight: Int,
        messageHandler: MessageHandler,
        protocolHandler: ProtocolHandler
    ): NameQueryResult = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = connectWithRetry(host, port)
            if (socket == null) {
                return@withContext NameQueryResult.Error
            }
            
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            
            Log.d("HNSGo", "ConnectionManager: Starting handshake for name query to $host:$port (height: $chainHeight)")
            protocolHandler.sendVersion(output, host, port, chainHeight, messageHandler)
            val (handshakeSuccess, _) = protocolHandler.handshake(input, output, messageHandler)
            if (!handshakeSuccess) {
                Log.w("HNSGo", "ConnectionManager: Handshake failed for name query")
                return@withContext NameQueryResult.Error
            }
            
            Log.d("HNSGo", "ConnectionManager: Sending getproof for name hash")
            protocolHandler.sendGetProof(output, nameHash, nameRoot, messageHandler)
            
            var proofMessage: P2PMessage? = null
            var attempts = 0
            val maxAttempts = 5
            
            while (attempts < maxAttempts && proofMessage == null) {
                val message = messageHandler.receiveMessage(input)
                if (message == null) {
                    break
                }
                
                Log.d("HNSGo", "ConnectionManager: Received message: ${message.command}")
                
                if (message.command == "proof") {
                    proofMessage = message
                    break
                } else if (message.command == "notfound") {
                    Log.d("HNSGo", "ConnectionManager: Domain not found (valid protocol response)")
                    return@withContext NameQueryResult.NotFound
                } else {
                    attempts++
                }
            }
            
            if (proofMessage == null) {
                Log.w("HNSGo", "ConnectionManager: No proof message received")
                return@withContext NameQueryResult.Error
            }
            
            val (resourceRecords, proof) = messageHandler.parseProofMessage(proofMessage.payload)
            if (resourceRecords.isEmpty() && proof == null) {
                Log.w("HNSGo", "ConnectionManager: Empty proof response")
                return@withContext NameQueryResult.Error
            }
            
            Log.d("HNSGo", "ConnectionManager: Received proof with ${resourceRecords.size} resource records")
            return@withContext NameQueryResult.Success(resourceRecords, proof)
        } catch (e: Exception) {
            Log.e("HNSGo", "ConnectionManager: Error querying name from peer", e)
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
    ): SyncResult = withContext(Dispatchers.IO) {
        Log.d("HNSGo", "ConnectionManager: Starting header sync from height $startHeight")
        
        val discoveredPeers = try {
            withTimeoutOrNull(15000L) {
                discoverPeers()
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w("HNSGo", "ConnectionManager: Peer discovery failed: ${e.message}")
            emptyList()
        }
        
        var uniquePeers = discoveredPeers.distinct()
        
        PeerErrorTracker.loadErrors()
        var filteredPeers = PeerErrorTracker.filterExcluded(uniquePeers)
        val excludedCount = uniquePeers.size - filteredPeers.size
        val excludedPeers = uniquePeers.filter { PeerErrorTracker.shouldExclude(it) }
        
        if (excludedCount > 0) {
            Log.d("HNSGo", "ConnectionManager: Excluded $excludedCount peers with too many errors")
        }
        
        // If 50% or more peers are excluded, replace them with new peers
        val exclusionRatio = if (uniquePeers.isNotEmpty()) excludedCount.toDouble() / uniquePeers.size else 0.0
        if (exclusionRatio >= 0.5 && uniquePeers.isNotEmpty()) {
            Log.w("HNSGo", "ConnectionManager: ${(exclusionRatio * 100).toInt()}% of peers excluded, replacing with new peers")
            
            // Retry peer discovery to get fresh peers
            val newPeers = try {
                withTimeoutOrNull(15000L) {
                    discoverPeers()
                } ?: emptyList()
            } catch (e: Exception) {
                Log.w("HNSGo", "ConnectionManager: Peer discovery retry failed: ${e.message}")
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
                Log.d("HNSGo", "ConnectionManager: Replaced ${excludedPeers.size} excluded peers with $newPeersAdded new peers")
                uniquePeers = newUniquePeers
                filteredPeers = PeerErrorTracker.filterExcluded(uniquePeers)
            } else {
                Log.w("HNSGo", "ConnectionManager: No new peers discovered, clearing errors for excluded peers")
                // If we can't get new peers, at least clear errors to retry
                excludedPeers.forEach { excludedPeer ->
                    PeerErrorTracker.resetErrors(excludedPeer)
                }
                filteredPeers = uniquePeers
            }
        } else if (filteredPeers.isEmpty() && uniquePeers.isNotEmpty()) {
            // If all peers are excluded (but less than 50% threshold), clear errors and retry
            Log.w("HNSGo", "ConnectionManager: All peers excluded, clearing error counts to retry")
            PeerErrorTracker.clearAll()
            filteredPeers = uniquePeers // Retry all peers
        }
        
        Log.d("HNSGo", "ConnectionManager: Trying ${filteredPeers.size} peers (from ${uniquePeers.size} total)")
        
        var maxNetworkHeight: Int? = null
        
        for (peer in filteredPeers) {
            val parts = peer.split(":")
            if (parts.size != 2) continue
            
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: Config.P2P_PORT
            
            Log.d("HNSGo", "ConnectionManager: Trying peer $host:$port")
            
            val result = connectAndSync(
                host, port, startHeight, locatorHashes,
                onHeaderReceived, messageHandler, protocolHandler
            )
            
            if (result.peerHeight != null) {
                if (maxNetworkHeight == null || result.peerHeight > maxNetworkHeight) {
                    maxNetworkHeight = result.peerHeight
                    Log.d("HNSGo", "ConnectionManager: Updated max network height to $maxNetworkHeight from $host:$port")
                }
            }
            
            if (result.success) {
                Log.d("HNSGo", "ConnectionManager: Successfully synced from $host:$port (network height: $maxNetworkHeight)")
                return@withContext SyncResult(true, maxNetworkHeight)
            }
            
            // Only record errors for actual failures, not valid protocol responses (notfound, etc.)
            if (result.wasError) {
                PeerErrorTracker.recordError(peer)
            } else {
                Log.d("HNSGo", "ConnectionManager: Peer $peer responded with valid protocol response (notfound/no headers), not recording error")
            }
        }
        
        Log.w("HNSGo", "ConnectionManager: Failed to sync from all peers (max network height seen: $maxNetworkHeight)")
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

