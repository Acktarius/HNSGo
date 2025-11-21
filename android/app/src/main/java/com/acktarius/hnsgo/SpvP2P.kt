package com.acktarius.hnsgo

import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log

/**
 * Handshake P2P protocol implementation for SPV header sync
 * Based on Handshake protocol (similar to Bitcoin P2P)
 */
object SpvP2P {
    // Handshake network constants (from hnsd constants.h)
    // Use constants from Config.kt for consistency
    private const val MAGIC_MAINNET = Config.MAGIC_MAINNET
    private const val DEFAULT_PORT = Config.P2P_PORT
    private const val PROTO_VERSION = Config.PROTO_VERSION
    
    // Note: Brontide (ChaCha20-Poly1305 encryption) is OPTIONAL
    // hnsd uses brontide only when connecting to peers with pubkeys (hsk_addr_has_key)
    // For regular IP addresses (like we use), hnsd uses plain TCP on port 12038
    // We use plain TCP - brontide is not required for our use case
    
    // Data directory for peer persistence (set during initialization)
    private var dataDir: File? = null
    
    /**
     * Initialize peer discovery with data directory for persistence
     */
    fun init(dataDir: File) {
        this.dataDir = dataDir
        HardcodedPeers.init(dataDir)
        PeerErrorTracker.init(dataDir)
    }
    
    // DHT peer discovery instance (lazy initialization)
    private var peerDiscovery: PeerDiscovery? = null
    
    /**
     * Discover peers using the new logic flow from .cursorrules:
     * 1. Query DNS seeds first (aoihakkila.com, easyhandshake.com)
     * 2. Load and verify hardcoded peers (from storage, max 10)
     * 3. If hardcoded list is empty, populate with first 10 discovered peers
     * 4. Always use hardcoded peers as fallback if DNS fails
     * 5. Try DHT discovery if DNS and hardcoded peers both fail
     * 
     * Returns list of discovered peer addresses (host:port format)
     */
    suspend fun discoverPeers(): List<String> = withContext(Dispatchers.IO) {
        val allPeers = mutableSetOf<String>()
        
        // Step 1: Load verified peers from storage
        val verifiedPeers = HardcodedPeers.loadPeers()
        
        // Step 2: Verify existing peers (remove failed ones)
        if (verifiedPeers.isNotEmpty()) {
            HardcodedPeers.verifyAllPeers()
        }
        
        // Step 3: Query DNS seeds first (PRIMARY METHOD)
        Log.d("HNSGo", "SpvP2P: Starting peer discovery - querying DNS seeds first")
        val dnsPeers = try {
            withTimeoutOrNull(30000L) { // 30 second timeout for DNS queries (multiple servers may be tried)
                DnsSeedDiscovery.discoverPeersFromDnsSeeds()
            } ?: emptyList()
        } catch (e: TimeoutCancellationException) {
            Log.w("HNSGo", "SpvP2P: DNS seed discovery timed out after 30 seconds")
            emptyList()
        } catch (e: Exception) {
            Log.w("HNSGo", "SpvP2P: Error querying DNS seeds: ${e.message}")
            emptyList()
        }
        
        if (dnsPeers.isNotEmpty()) {
            Log.d("HNSGo", "SpvP2P: Found ${dnsPeers.size} peers from DNS seeds")
            allPeers.addAll(dnsPeers)
            
            // If hardcoded list is empty, populate with first 10 discovered peers
            try {
                if (HardcodedPeers.isEmpty()) {
                    Log.d("HNSGo", "SpvP2P: Hardcoded list is empty, populating with first 10 DNS-discovered peers")
                    HardcodedPeers.addPeers(dnsPeers)
                } else {
                    // Add new peers to hardcoded list (up to max 10)
                    HardcodedPeers.addPeers(dnsPeers)
                }
            } catch (e: Exception) {
                Log.e("HNSGo", "SpvP2P: Error adding peers to hardcoded list: ${e.message}", e)
                // Continue anyway - we still have peers in allPeers
            }
        } else {
            Log.w("HNSGo", "SpvP2P: DNS seed discovery failed or returned no peers")
            Log.d("HNSGo", "SpvP2P: Will use persisted fallback peers from previous session (if available)")
        }
        
        // Step 4: Always add verified hardcoded peers as fallback
        // This ensures we use persisted peers even if DNS discovery completely fails
        val fallbackPeers = HardcodedPeers.getFallbackPeers()
        if (fallbackPeers.isNotEmpty()) {
            allPeers.addAll(fallbackPeers)
            Log.d("HNSGo", "SpvP2P: Added ${fallbackPeers.size} verified fallback peers from local storage")
        } else {
            Log.d("HNSGo", "SpvP2P: No persisted fallback peers available in local storage")
        }
        
        // Step 5: If still no peers, try DHT discovery (but it needs bootstrap nodes)
        if (allPeers.isEmpty()) {
            Log.w("HNSGo", "SpvP2P: No peers from DNS or hardcoded list, DHT discovery requires bootstrap nodes")
            Log.w("HNSGo", "SpvP2P: Need initial bootstrap peer IPs to start DHT discovery")
            // TODO: Add known Handshake peer IPs as bootstrap nodes for DHT
            // For now, DHT discovery is skipped since we have no bootstrap nodes
        }
        
        val peerList = allPeers.toList()
        val dnsCount = dnsPeers.size
        val fallbackCount = fallbackPeers.size
        
        Log.d("HNSGo", "SpvP2P: Peer discovery complete: ${peerList.size} total peers (DNS: $dnsCount, Fallback: $fallbackCount)")
        
        if (peerList.isEmpty()) {
            Log.e("HNSGo", "SpvP2P: CRITICAL - No peers discovered! App cannot sync without peers.")
            Log.e("HNSGo", "SpvP2P: Solutions:")
            Log.e("HNSGo", "SpvP2P:   1. Add known Handshake peer IPs to Config as bootstrap nodes")
            Log.e("HNSGo", "SpvP2P:   2. Find correct DNS seed domains that actually serve peer IPs")
            Log.e("HNSGo", "SpvP2P:   3. Use external resolver (setResolver) for development")
        }
        
        return@withContext peerList
    }
    
    /**
     * Record a successful peer connection for future use
     * Adds to hardcoded verified peers list and resets error count
     */
    suspend fun recordSuccessfulPeer(peer: String) = withContext(Dispatchers.IO) {
        if (DnsSeedDiscovery.isValidPeerAddress(peer)) {
            HardcodedPeers.addPeers(listOf(peer))
            PeerErrorTracker.resetErrors(peer)
            Log.d("HNSGo", "SpvP2P: Recorded successful peer: $peer")
        }
    }
    
    /**
     * Query a Handshake peer for domain resource records
     * @param nameHash SHA256 hash of the domain name
     * @return Pair of (resource records, Merkle proof) or null if not found
     */
    suspend fun queryName(
        nameHash: ByteArray
    ): Pair<List<ByteArray>, ByteArray?>? = withContext(Dispatchers.IO) {
        // Get list of peers to try (discovered via DNS seeds, hardcoded, or persisted)
        val peersToTry = try {
            withTimeoutOrNull(5000L) { // 5 second timeout for peer discovery
                discoverPeers()
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w("HNSGo", "SpvP2P: Error discovering peers for name query: ${e.message}")
            emptyList()
        }
        
        // Remove duplicates
        val uniquePeers = peersToTry.distinct()
        
        Log.d("HNSGo", "SpvP2P: Querying name from ${uniquePeers.size} peers")
        
        // Try each peer
        for (peerAddr in uniquePeers) {
            try {
                val parts = peerAddr.split(":")
                val host = parts[0]
                val port = if (parts.size > 1) parts[1].toInt() else DEFAULT_PORT
                
                Log.d("HNSGo", "SpvP2P: Querying name from $host:$port")
                
                val result = queryNameFromPeer(host, port, nameHash)
                if (result != null) {
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.e("HNSGo", "SpvP2P: Error querying name from peer $peerAddr", e)
                continue
            }
        }
        null
    }
    
    /**
     * Query a specific peer for domain name data
     */
    private suspend fun queryNameFromPeer(
        host: String,
        port: Int,
        nameHash: ByteArray
    ): Pair<List<ByteArray>, ByteArray?>? = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(host, port), 10000)
            socket.soTimeout = 30000
            
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            
            // Handshake (for name queries, we don't have chain height, use 0)
            sendVersion(output, host, port, 0)
            if (!handshake(input, output)) {
                Log.w("HNSGo", "SpvP2P: Handshake failed for name query")
                return@withContext null
            }
            
            // Send getname message (if Handshake protocol supports it)
            // TODO: Implement proper getname message format
            // For now, this is a placeholder
            Log.d("HNSGo", "SpvP2P: Sending name query for hash: ${nameHash.joinToString("") { "%02x".format(it) }.take(16)}...")
            
            // Note: Handshake P2P protocol may not have a direct "getname" message
            // We might need to query the UTXO set or use a different approach
            // This is a placeholder for the actual implementation
            
            return@withContext null
        } catch (e: Exception) {
            Log.e("HNSGo", "SpvP2P: Error querying name from peer", e)
            return@withContext null
        } finally {
            socket?.close()
        }
    }
    
    /**
     * Connect to a Handshake peer and sync headers
     * Uses new peer discovery flow: DNS seeds → hardcoded fallback → persisted peers
     */
    suspend fun syncHeaders(
        startHeight: Int = 0,
        latestHeaderHash: ByteArray? = null, // Hash of latest header for getheaders locator
        onHeaderReceived: (Header) -> Boolean // Return false to stop syncing
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d("HNSGo", "SpvP2P: Starting header sync from height $startHeight")
        
        // Discover peers using new flow: DNS seeds → hardcoded → persisted
        val discoveredPeers = try {
            withTimeoutOrNull(15000L) { // 15 second timeout for discovery
                discoverPeers()
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w("HNSGo", "SpvP2P: Peer discovery failed: ${e.message}")
            emptyList()
        }
        
        // Remove duplicates
        val uniquePeers = discoveredPeers.distinct()
        
        // Load error tracking and filter out peers with too many errors
        PeerErrorTracker.loadErrors()
        val filteredPeers = PeerErrorTracker.filterExcluded(uniquePeers)
        val excludedCount = uniquePeers.size - filteredPeers.size
        
        if (excludedCount > 0) {
            Log.d("HNSGo", "SpvP2P: Excluded $excludedCount peers with too many errors (>${PeerErrorTracker.MAX_ERRORS})")
        }
        
        Log.d("HNSGo", "SpvP2P: Trying ${filteredPeers.size} peers (from DNS seeds, hardcoded fallback, and persisted peers)")
        
        // Try each peer
        for ((peerIndex, peerAddr) in filteredPeers.withIndex()) {
            try {
                val parts = peerAddr.split(":")
                val host = parts[0]
                val port = if (parts.size > 1) parts[1].toInt() else DEFAULT_PORT
                
                Log.d("HNSGo", "SpvP2P: [${peerIndex + 1}/${uniquePeers.size}] Attempting connection to $host:$port")
                
                // Try with retries and exponential backoff
                // Pass latestHeaderHash from syncHeaders to connectWithRetry
                val success = connectWithRetry(host, port, startHeight, latestHeaderHash, onHeaderReceived)
                if (success) {
                    Log.d("HNSGo", "SpvP2P: Successfully synced from $host:$port")
                    // Record successful peer for future use
                    recordSuccessfulPeer("$host:$port")
                    // Reset error count for this peer (successful connection)
                    PeerErrorTracker.resetErrors("$host:$port")
                    return@withContext true
                } else {
                    Log.w("HNSGo", "SpvP2P: Failed to sync from $host:$port after retries, trying next peer")
                    // Record error for this peer
                    PeerErrorTracker.recordError("$host:$port")
                    val errorCount = PeerErrorTracker.getErrorCount("$host:$port")
                    if (errorCount >= PeerErrorTracker.MAX_ERRORS) {
                        Log.w("HNSGo", "SpvP2P: Peer $host:$port has exceeded max errors, will be excluded from future attempts")
                    }
                }
            } catch (e: Exception) {
                Log.e("HNSGo", "SpvP2P: Error with peer $peerAddr", e)
                continue
            }
        }
        
        Log.w("HNSGo", "SpvP2P: All peers failed. P2P sync unavailable.")
        Log.i("HNSGo", "SpvP2P: App will use checkpoint/bootstrap or external resolver")
        return@withContext false
    }
    
    /**
     * Connect with exponential backoff retry
     */
    private suspend fun connectWithRetry(
        host: String,
        port: Int,
        startHeight: Int,
        latestHeaderHash: ByteArray?,
        onHeaderReceived: (Header) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        var delay = Config.P2P_RETRY_BASE_DELAY_MS
        
        val maxRetries = Config.P2P_MAX_RETRIES
        for (attempt in 1..maxRetries) {
            try {
                Log.d("HNSGo", "SpvP2P: Connection attempt $attempt/$maxRetries to $host:$port")
                
                val success = connectAndSync(host, port, startHeight, latestHeaderHash, onHeaderReceived)
                if (success) {
                    return@withContext true
                }
                
                // If not last attempt, wait with exponential backoff
                if (attempt < maxRetries) {
                    Log.d("HNSGo", "SpvP2P: Retrying in ${delay}ms...")
                    kotlinx.coroutines.delay(delay)
                    delay *= 2  // Exponential backoff
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w("HNSGo", "SpvP2P: Connection timeout to $host:$port (attempt $attempt/$maxRetries)")
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(delay)
                    delay *= 2
                }
            } catch (e: java.net.ConnectException) {
                Log.w("HNSGo", "SpvP2P: Connection refused to $host:$port (attempt $attempt/$maxRetries) - peer may be offline")
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(delay)
                    delay *= 2
                }
            } catch (e: Exception) {
                Log.e("HNSGo", "SpvP2P: Connection error to $host:$port (attempt $attempt/$maxRetries)", e)
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(delay)
                    delay *= 2
                }
            }
        }
        
        Log.w("HNSGo", "SpvP2P: All $maxRetries attempts failed for $host:$port")
        return@withContext false
    }
    
    private fun parseSeedNode(seed: String): Pair<String, Int> {
        // Format: identity@host:port or identity@host
        val parts = seed.split("@")
        if (parts.size != 2) throw IllegalArgumentException("Invalid seed format: $seed")
        
        val address = parts[1]
        val hostPort = address.split(":")
        val host = hostPort[0]
        val port = if (hostPort.size > 1) hostPort[1].toInt() else DEFAULT_PORT
        
        return host to port
    }
    
    private suspend fun connectAndSync(
        host: String,
        port: Int,
        startHeight: Int,
        latestHeaderHash: ByteArray?,
        onHeaderReceived: (Header) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        val startTime = System.currentTimeMillis()
        
        try {
            Log.d("HNSGo", "SpvP2P: Creating socket connection to $host:$port")
            socket = Socket()
            
            // Set connection timeout
            Log.d("HNSGo", "SpvP2P: Connecting with ${Config.P2P_CONNECT_TIMEOUT_MS}ms timeout...")
            socket.connect(InetSocketAddress(host, port), Config.P2P_CONNECT_TIMEOUT_MS)
            socket.soTimeout = Config.P2P_SOCKET_TIMEOUT_MS
            
            val connectTime = System.currentTimeMillis() - startTime
            Log.d("HNSGo", "SpvP2P: Connected to $host:$port in ${connectTime}ms")
            
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            
            // In hnsd (pool.c:1784-1807), reading starts IMMEDIATELY after connect (uv_read_start)
            // BEFORE sending version. However, with blocking I/O, we send version first.
            // The peer may send their version immediately, so we need to read quickly.
            Log.d("HNSGo", "SpvP2P: Sending version message...")
            sendVersion(output, host, port, startHeight) // Send actual chain height
            Log.d("HNSGo", "SpvP2P: Version message sent, waiting for peer response...")
            
            // Wait for version/verack (handshake will read incoming messages)
            Log.d("HNSGo", "SpvP2P: Waiting for handshake...")
            if (!handshake(input, output)) {
                Log.w("HNSGo", "SpvP2P: Handshake failed")
                return@withContext false
            }
            Log.d("HNSGo", "SpvP2P: Handshake successful")
            
            // After handshake, hnsd sends: sendheaders, getaddr, then getheaders
            // (see hsk_peer_handle_version in pool.c:1335-1347)
            sendSendHeaders(output)
            sendGetAddr(output)
            
            // Request headers (matching hnsd's hsk_peer_send_getheaders)
            Log.d("HNSGo", "SpvP2P: Requesting headers...")
            sendGetHeaders(output, latestHeaderHash)
            
            // Receive headers
            Log.d("HNSGo", "SpvP2P: Waiting for headers...")
            val result = receiveHeaders(input, onHeaderReceived)
            Log.d("HNSGo", "SpvP2P: Header sync result: $result")
            return@withContext result
            
        } catch (e: java.net.SocketTimeoutException) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w("HNSGo", "SpvP2P: Connection timeout to $host:$port after ${elapsed}ms")
            Log.d("HNSGo", "SpvP2P: Possible causes: peer offline, firewall blocking, or network issue")
            return@withContext false
        } catch (e: java.net.ConnectException) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w("HNSGo", "SpvP2P: Connection refused to $host:$port after ${elapsed}ms")
            Log.d("HNSGo", "SpvP2P: Peer is not accepting connections on port $port")
            return@withContext false
        } catch (e: java.net.UnknownHostException) {
            Log.w("HNSGo", "SpvP2P: Unknown host $host - DNS resolution failed")
            return@withContext false
        } catch (e: java.io.IOException) {
            Log.e("HNSGo", "SpvP2P: I/O error connecting to $host:$port", e)
            return@withContext false
        } catch (e: Exception) {
            Log.e("HNSGo", "SpvP2P: Unexpected error connecting to $host:$port", e)
            return@withContext false
        } finally {
            try {
                socket?.close()
                Log.d("HNSGo", "SpvP2P: Socket closed for $host:$port")
            } catch (e: Exception) {
                Log.d("HNSGo", "SpvP2P: Error closing socket", e)
            }
        }
    }
    
    private fun sendVersion(output: OutputStream, host: String, port: Int, chainHeight: Int = 0) {
        // Handshake version message format (matching hnsd implementation)
        // Based on hnsd/src/msg.c:hsk_version_msg_write and hnsd/src/pool.c:hsk_peer_send_version
        val version = Config.PROTO_VERSION  // HSK_PROTO_VERSION = 1 (not Bitcoin's 70015!)
        val services = Config.SERVICES  // HSK_SERVICES = 0 (no relay or fullnode service)
        val timestamp = System.currentTimeMillis() / 1000
        // Use random nonce (hnsd uses hsk_nonce() which returns random uint64)
        val nonce = java.util.Random().nextLong()
        
        // Convert host to IPv4/IPv6 address
        val recvAddr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            InetAddress.getByName("0.0.0.0") // Fallback
        }
        val recvAddrBytes = recvAddr.address // IPv4: 4 bytes, IPv6: 16 bytes
        
        // Handshake netaddr format: time (8) + services (8) + type (1) + ip[36] + port (2) + key[33] = 88 bytes
        // For IPv4: type=0, ip[0-15] = IPv4 mapped to IPv6 (::ffff:IPv4), ip[16-35] = zeros
        // For IPv6: type=0, ip[0-15] = IPv6 address, ip[16-35] = zeros
        // key[33] = all zeros (no pubkey for regular IP addresses)
        val recvIP = ByteArray(36)
        val addrType: Byte = 0 // 0 for IPv4/IPv6
        if (recvAddrBytes.size == 4) {
            // IPv4 mapped to IPv6: ::ffff:IPv4
            // ip[0-9] = zeros, ip[10-11] = 0xFF, ip[12-15] = IPv4, ip[16-35] = zeros
            recvIP[10] = 0xFF.toByte()
            recvIP[11] = 0xFF.toByte()
            System.arraycopy(recvAddrBytes, 0, recvIP, 12, 4)
        } else {
            // IPv6: copy first 16 bytes, rest are zeros
            System.arraycopy(recvAddrBytes, 0, recvIP, 0, 16)
        }
        val recvKey = ByteArray(33) // All zeros (no pubkey)
        
        val agent = "/hnsgo:0.1/"  // Format: /name:version/ (matching hnsd format)
        val agentBytes = agent.toByteArray(Charsets.US_ASCII)
        if (agentBytes.size > 255) {
            throw IllegalArgumentException("User agent too long (max 255 bytes)")
        }
        
        val payload = ByteArrayOutputStream().apply {
            // Version message payload (matching hnsd/src/msg.c:hsk_version_msg_write):
            writeInt32(version)              // uint32_t version (little-endian)
            writeInt64(services)             // uint64_t services (little-endian)
            writeInt64(timestamp)            // uint64_t time (little-endian)
            
            // hsk_netaddr_t remote (88 bytes):
            writeInt64(0)                    // uint64_t time (little-endian) - can be 0 for new connections
            writeInt64(services)             // uint64_t services (little-endian)
            write(addrType.toInt())          // uint8_t type (0 for IPv4/IPv6)
            writeBytes(recvIP)                // uint8_t ip[36] (IPv4 mapped to IPv6 in first 16 bytes)
            writeInt16(port)                 // uint16_t port (little-endian)
            writeBytes(recvKey)              // uint8_t key[33] (all zeros)
            
            // Nonce and agent:
            writeInt64(nonce)                // uint64_t nonce (little-endian)
            write(agentBytes.size)           // uint8_t size (1 byte, NOT varint!)
            writeBytes(agentBytes)           // char agent[size] (ASCII)
            
            // Height and no_relay:
            writeInt32(chainHeight)           // uint32_t height (little-endian) - actual chain height
            write(0)                         // uint8_t no_relay (0 = false, 1 = true) - set to 0 for Bitcoin compatibility
        }.toByteArray()
        
        // Debug: log full payload in hex for debugging
        val payloadHex = payload.joinToString(" ") { "%02x".format(it) }
        Log.d("HNSGo", "SpvP2P: Sending version message (version=$version, services=$services, host=$host, port=$port, height=$chainHeight, payload=${payload.size} bytes)")
        Log.d("HNSGo", "SpvP2P: Version payload (full): $payloadHex")
        sendMessage(output, "version", payload)
    }
    
    private suspend fun handshake(input: InputStream, output: OutputStream): Boolean {
        var versionReceived = false
        var verackReceived = false
        var ourVerackSent = false
        
        // Standard Bitcoin/Handshake handshake flow:
        // 1. Both sides send version (we already sent ours)
        // 2. Both wait for the other's version
        // 3. Both send verack after receiving version
        
        // Receive version and verack with timeout
        val handshakeTimeout = 10000L // 10 seconds for handshake
        val startTime = System.currentTimeMillis()
        var attempts = 0
        val maxAttempts = 20 // Allow more attempts for handshake
        
        while (attempts < maxAttempts && (!versionReceived || !verackReceived)) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > handshakeTimeout) {
                Log.w("HNSGo", "SpvP2P: Handshake timeout after ${elapsed}ms (version: $versionReceived, verack: $verackReceived)")
                return false
            }
            
            try {
                Log.d("HNSGo", "SpvP2P: Waiting for handshake message (attempt ${attempts + 1}/$maxAttempts, elapsed: ${elapsed}ms)...")
                val message = receiveMessage(input)
                if (message == null) {
                    // EOF means peer closed connection - this is a protocol rejection
                    if (attempts == 0) {
                        Log.w("HNSGo", "SpvP2P: Peer closed connection immediately after our version message")
                        Log.w("HNSGo", "SpvP2P: This indicates protocol mismatch or peer rejected our version")
                        Log.w("HNSGo", "SpvP2P: Possible causes: wrong message format, invalid fields, or peer doesn't accept SPV clients")
                    }
                    // Don't continue retrying if peer closed connection
                    return false
                }
                
                Log.d("HNSGo", "SpvP2P: Received message: ${message.command} (payload size: ${message.payload.size} bytes)")
                
                when (message.command) {
                    "version" -> {
                        Log.d("HNSGo", "SpvP2P: Version message received, parsing...")
                        try {
                            // Parse version message to get peer info
                            val versionData = parseVersionMessage(message.payload)
                            Log.d("HNSGo", "SpvP2P: Peer version: ${versionData.version}, services: ${versionData.services}, height: ${versionData.height}")
                            versionReceived = true
                            // Send verack after receiving version (standard protocol)
                            if (!ourVerackSent) {
                                sendMessage(output, "verack", byteArrayOf())
                                ourVerackSent = true
                                Log.d("HNSGo", "SpvP2P: Sent verack after receiving peer's version")
                            }
                        } catch (e: Exception) {
                            Log.e("HNSGo", "SpvP2P: Error parsing version message: ${e.message}", e)
                            // Still mark as received to continue handshake
                            versionReceived = true
                            if (!ourVerackSent) {
                                sendMessage(output, "verack", byteArrayOf())
                                ourVerackSent = true
                            }
                        }
                    }
                    "verack" -> {
                        Log.d("HNSGo", "SpvP2P: Verack message received")
                        verackReceived = true
                    }
                    else -> {
                        Log.d("HNSGo", "SpvP2P: Received unexpected message during handshake: ${message.command}")
                        // Some peers send other messages during handshake, continue waiting
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w("HNSGo", "SpvP2P: Socket timeout waiting for handshake message (attempt ${attempts + 1})")
                // Continue trying
            } catch (e: java.net.SocketException) {
                if (e.message?.contains("Connection reset") == true || e.message?.contains("reset") == true) {
                    Log.w("HNSGo", "SpvP2P: Connection reset by peer during handshake (attempt ${attempts + 1})")
                    Log.w("HNSGo", "SpvP2P: Peer may have rejected our version message or protocol mismatch")
                    Log.w("HNSGo", "SpvP2P: This is normal - some peers may reject SPV clients or have strict requirements")
                    // Connection reset means peer closed connection - don't retry
                    return false
                } else {
                    Log.e("HNSGo", "SpvP2P: Socket error during handshake: ${e.message}", e)
                    return false
                }
            } catch (e: Exception) {
                Log.e("HNSGo", "SpvP2P: Error receiving handshake message: ${e.message}", e)
                return false
            }
            attempts++
        }
        
        val success = versionReceived && verackReceived
        val elapsed = System.currentTimeMillis() - startTime
        if (success) {
            Log.d("HNSGo", "SpvP2P: Handshake successful in ${elapsed}ms (version: $versionReceived, verack: $verackReceived)")
        } else {
            Log.w("HNSGo", "SpvP2P: Handshake incomplete after ${elapsed}ms (version: $versionReceived, verack: $verackReceived, attempts: $attempts)")
            Log.w("HNSGo", "SpvP2P: Possible causes: protocol mismatch, peer disconnected, or network issue")
        }
        return success
    }
    
    /**
     * Parse version message to extract peer information
     */
    private data class VersionInfo(val version: Int, val services: Long, val height: Int)
    
    private fun parseVersionMessage(payload: ByteArray): VersionInfo {
        // Parse Handshake version message (matching hnsd/src/msg.c:hsk_version_msg_read):
        // version (4) + services (8) + time (8) + netaddr (88) + nonce (8) + agent (1+size) + height (4) + no_relay (1)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.int
        val services = buffer.long
        val timestamp = buffer.long
        
        // Skip hsk_netaddr_t remote (88 bytes): time (8) + services (8) + type (1) + ip[36] + port (2) + key[33]
        buffer.position(buffer.position() + 88)
        
        val nonce = buffer.long
        // Read user agent: 1 byte size + ASCII string
        val userAgentLen = buffer.get().toInt() and 0xFF
        val userAgent = ByteArray(userAgentLen)
        buffer.get(userAgent)
        // Read height
        val height = buffer.int
        // Skip no_relay (1 byte)
        buffer.get()
        
        return VersionInfo(version, services, height)
    }
    
    private fun sendGetHeaders(output: OutputStream, latestHeaderHash: ByteArray?) {
        // GetHeaders message (matching hnsd/src/msg.c:hsk_getheaders_msg_write):
        // Hash count (varint) + Hashes (32 bytes each) + Stop hash (32 bytes)
        // NO version field! hnsd doesn't have it.
        // hnsd builds locator with multiple hashes going backwards from tip (see hsk_chain_get_locator)
        // For now, use latest header hash if available, otherwise genesis hash (all zeros)
        val locatorHash = latestHeaderHash ?: ByteArray(32) // Genesis hash (all zeros)
        
        val payload = ByteArrayOutputStream().apply {
            writeVarInt(1) // Hash count (varint, 1 hash for now)
            writeBytes(locatorHash) // Block locator hash (32 bytes)
            writeBytes(ByteArray(32)) // Stop hash (all zeros = no stop)
        }.toByteArray()
        
        Log.d("HNSGo", "SpvP2P: Sending getheaders (locator: ${locatorHash.take(8).joinToString("") { "%02x".format(it) }}...)")
        sendMessage(output, "getheaders", payload)
    }
    
    private fun sendSendHeaders(output: OutputStream) {
        // SendHeaders message (matching hnsd) - empty payload
        Log.d("HNSGo", "SpvP2P: Sending sendheaders")
        sendMessage(output, "sendheaders", byteArrayOf())
    }
    
    private fun sendGetAddr(output: OutputStream) {
        // GetAddr message (matching hnsd) - empty payload
        Log.d("HNSGo", "SpvP2P: Sending getaddr")
        sendMessage(output, "getaddr", byteArrayOf())
    }
    
    private suspend fun receiveHeaders(
        input: InputStream,
        onHeaderReceived: (Header) -> Boolean
    ): Boolean {
        var headersReceived = 0
        
        Log.d("HNSGo", "SpvP2P: Starting to receive headers...")
        
        while (headersReceived < 2000) { // Limit to prevent infinite loop
            Log.d("HNSGo", "SpvP2P: Waiting for headers message...")
            val message = receiveMessage(input)
            if (message == null) {
                Log.w("HNSGo", "SpvP2P: Failed to receive message, stopping")
                break
            }
            
            Log.d("HNSGo", "SpvP2P: Received message: ${message.command}, payload size: ${message.payload.size}")
            
            if (message.command == "headers") {
                Log.d("HNSGo", "SpvP2P: Parsing headers from payload...")
                val headers = parseHeaders(message.payload)
                Log.d("HNSGo", "SpvP2P: Parsed ${headers.size} headers")
                
                for (header in headers) {
                    if (!onHeaderReceived(header)) {
                        Log.d("HNSGo", "SpvP2P: Header processing stopped by callback")
                        return true // Stop requested
                    }
                    headersReceived++
                    if (headersReceived % 100 == 0) {
                        Log.d("HNSGo", "SpvP2P: Received $headersReceived headers so far...")
                    }
                }
                
                if (headers.isEmpty()) {
                    Log.d("HNSGo", "SpvP2P: Empty headers message, no more headers")
                    break // No more headers
                }
            } else {
                Log.d("HNSGo", "SpvP2P: Received non-headers message: ${message.command}, continuing...")
            }
        }
        
        Log.d("HNSGo", "SpvP2P: Finished receiving headers, total: $headersReceived")
        return headersReceived > 0
    }
    
    private fun parseHeaders(payload: ByteArray): List<Header> {
        val headers = mutableListOf<Header>()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        
        try {
            val count = readVarInt(buffer)
            Log.d("HNSGo", "SpvP2P: Parsing $count headers (236 bytes each)")
            
            for (i in 0 until count) {
                // Handshake block header format (236 bytes, matching hsk_header_read in header.c:204-238)
                // Format: nonce(4) + time(8) + prev_block(32) + name_root(32) + extra_nonce(24) +
                //         reserved_root(32) + witness_root(32) + merkle_root(32) + version(4) + bits(4) + mask(32)
                if (buffer.remaining() < 236) {
                    Log.w("HNSGo", "SpvP2P: Not enough bytes for header ${i + 1}/$count (need 236, have ${buffer.remaining()})")
                    break
                }
                
                val headerBytes = ByteArray(236)
                buffer.get(headerBytes)
                
                // Debug: log first few bytes of header to verify parsing
                if (i < 3) {
                    val debugHex = headerBytes.take(20).joinToString(" ") { "%02x".format(it) }
                    Log.d("HNSGo", "SpvP2P: Header $i raw bytes (first 20): $debugHex")
                }
                
                val header = parseHeader(headerBytes)
                
                // Debug: log parsed prevBlock for first few headers
                if (i < 3) {
                    val prevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }
                    Log.d("HNSGo", "SpvP2P: Header $i parsed prevBlock: ${prevBlockHex.take(16)}...")
                    
                    // Debug: Log full header bytes for byte-by-byte comparison
                    val headerBytesHex = headerBytes.joinToString(" ") { "%02x".format(it) }
                    Log.d("HNSGo", "SpvP2P: Header $i raw bytes (full 236 bytes): $headerBytesHex")
                    
                    // Debug: Log parsed fields for verification
                    Log.d("HNSGo", "SpvP2P: Header $i parsed fields:")
                    Log.d("HNSGo", "  - nonce: ${header.nonce} (0x%08x, bytes: %s)".format(
                        header.nonce,
                        headerBytes.sliceArray(0..3).joinToString(" ") { "%02x".format(it) }
                    ))
                    Log.d("HNSGo", "  - time: ${header.time} (0x%016x, bytes: %s)".format(
                        header.time,
                        headerBytes.sliceArray(4..11).joinToString(" ") { "%02x".format(it) }
                    ))
                    Log.d("HNSGo", "  - prevBlock (bytes 12-43): ${header.prevBlock.joinToString(" ") { "%02x".format(it) }}")
                    Log.d("HNSGo", "  - nameRoot (bytes 44-75): ${header.merkleRoot.joinToString(" ") { "%02x".format(it) }}")
                    Log.d("HNSGo", "  - extraNonce (bytes 76-99): ${headerBytes.sliceArray(76..99).joinToString(" ") { "%02x".format(it) }}")
                    Log.d("HNSGo", "  - reservedRoot (bytes 100-131): ${header.reservedRoot.joinToString(" ") { "%02x".format(it) }}")
                    Log.d("HNSGo", "  - witnessRoot (bytes 132-163): ${header.witnessRoot.joinToString(" ") { "%02x".format(it) }}")
                    Log.d("HNSGo", "  - merkleRoot (bytes 164-195): ${header.treeRoot.joinToString(" ") { "%02x".format(it) }}")
                    Log.d("HNSGo", "  - version (bytes 196-199): ${header.version} (0x%08x, bytes: %s)".format(
                        header.version,
                        headerBytes.sliceArray(196..199).joinToString(" ") { "%02x".format(it) }
                    ))
                    Log.d("HNSGo", "  - bits (bytes 200-203): ${header.bits} (0x%08x, bytes: %s)".format(
                        header.bits,
                        headerBytes.sliceArray(200..203).joinToString(" ") { "%02x".format(it) }
                    ))
                    Log.d("HNSGo", "  - mask (bytes 204-235): ${header.mask.joinToString(" ") { "%02x".format(it) }}")
                }
                
                headers.add(header)
            }
        } catch (e: Exception) {
            Log.e("HNSGo", "SpvP2P: Error parsing headers", e)
        }
        
        return headers
    }
    
    private fun parseHeader(headerBytes: ByteArray): Header {
        // Parse Handshake header (236 bytes, matching hsk_header_read in header.c:204-238)
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        
        val nonce = buffer.int                    // 4 bytes
        val time = buffer.long                    // 8 bytes
        val prevBlock = ByteArray(32).apply { buffer.get(this) }  // 32 bytes
        val nameRoot = ByteArray(32).apply { buffer.get(this) }  // 32 bytes (this is merkleRoot in our Header class)
        val extraNonce = ByteArray(24).apply { buffer.get(this) }  // 24 bytes - MUST store full 24 bytes!
        val reservedRoot = ByteArray(32).apply { buffer.get(this) }  // 32 bytes
        val witnessRoot = ByteArray(32).apply { buffer.get(this) }  // 32 bytes
        val merkleRoot = ByteArray(32).apply { buffer.get(this) }  // 32 bytes (this is treeRoot in our Header class)
        val version = buffer.int                   // 4 bytes
        val bits = buffer.int                      // 4 bytes
        val mask = ByteArray(32).apply { buffer.get(this) }  // 32 bytes (required for hash calculation)
        
        return Header(
            version = version,
            prevBlock = prevBlock,
            merkleRoot = nameRoot,  // name_root maps to merkleRoot
            witnessRoot = witnessRoot,
            treeRoot = merkleRoot,  // merkle_root maps to treeRoot
            reservedRoot = reservedRoot,
            time = time,
            bits = bits,
            nonce = nonce,
            extraNonce = extraNonce,  // Store full 24-byte extraNonce for correct hash calculation
            mask = mask  // Include mask field for hash calculation
        )
    }
    
    // Handshake message command codes (from hnsd/src/msg.h)
    private const val HSK_MSG_VERSION = 0
    private const val HSK_MSG_VERACK = 1
    private const val HSK_MSG_PING = 2
    private const val HSK_MSG_PONG = 3
    private const val HSK_MSG_GETADDR = 4
    private const val HSK_MSG_ADDR = 5
    private const val HSK_MSG_GETHEADERS = 10
    private const val HSK_MSG_HEADERS = 11
    private const val HSK_MSG_SENDHEADERS = 12
    private const val HSK_MSG_GETPROOF = 26
    private const val HSK_MSG_PROOF = 27
    
    private fun commandToCode(command: String): Int {
        return when (command.lowercase()) {
            "version" -> HSK_MSG_VERSION
            "verack" -> HSK_MSG_VERACK
            "ping" -> HSK_MSG_PING
            "pong" -> HSK_MSG_PONG
            "getaddr" -> HSK_MSG_GETADDR
            "addr" -> HSK_MSG_ADDR
            "getheaders" -> HSK_MSG_GETHEADERS
            "headers" -> HSK_MSG_HEADERS
            "sendheaders" -> HSK_MSG_SENDHEADERS
            "getproof" -> HSK_MSG_GETPROOF
            "proof" -> HSK_MSG_PROOF
            else -> throw IllegalArgumentException("Unknown command: $command")
        }
    }
    
    private fun codeToCommand(code: Int): String {
        return when (code) {
            HSK_MSG_VERSION -> "version"
            HSK_MSG_VERACK -> "verack"
            HSK_MSG_PING -> "ping"
            HSK_MSG_PONG -> "pong"
            HSK_MSG_GETADDR -> "getaddr"
            HSK_MSG_ADDR -> "addr"
            HSK_MSG_GETHEADERS -> "getheaders"
            HSK_MSG_HEADERS -> "headers"
            HSK_MSG_SENDHEADERS -> "sendheaders"
            HSK_MSG_GETPROOF -> "getproof"
            HSK_MSG_PROOF -> "proof"
            else -> "unknown"
        }
    }
    
    private fun sendMessage(output: OutputStream, command: String, payload: ByteArray) {
        // Handshake message format (matching hnsd/src/pool.c:hsk_peer_send EXACTLY):
        // Magic (4 bytes) + Command (1 byte) + Size (4 bytes) + Payload (size bytes)
        // NO checksum! This is what hnsd uses and it works.
        val cmdCode = commandToCode(command)
        
        val message = ByteArray(9 + payload.size).apply {
            // Magic (4 bytes, little-endian) - line 1204 in hnsd
            writeInt32(MAGIC_MAINNET, this, 0)
            // Command (1 byte) - line 1207 in hnsd: write_u8(&buf, msg->cmd)
            this[4] = cmdCode.toByte()
            // Size (4 bytes, little-endian) - line 1210 in hnsd
            writeInt32(payload.size, this, 5)
            // Payload - line 1213 in hnsd: hsk_msg_write(msg, &buf)
            System.arraycopy(payload, 0, this, 9, payload.size)
        }
        
        // Debug: log first 20 bytes of message
        val debugBytes = message.take(20).joinToString(" ") { "%02x".format(it) }
        Log.d("HNSGo", "SpvP2P: Sending message: cmd=$command (code=$cmdCode), size=${message.size}, first 20 bytes: $debugBytes")
        
        output.write(message)
        output.flush()
    }
    
    private suspend fun receiveMessage(input: InputStream): P2PMessage? = withContext(Dispatchers.IO) {
        try {
            // Handshake message header (matching hnsd/src/pool.c:hsk_peer_parse_hdr EXACTLY):
            // Magic (4) + Command (1) + Size (4) = 9 bytes
            val header = ByteArray(9)
            var read = 0
            while (read < 9) {
                val n = input.read(header, read, 9 - read)
                if (n == -1) {
                    Log.d("HNSGo", "SpvP2P: EOF while reading message header")
                    return@withContext null
                }
                read += n
            }
            
            val magic = readInt32(header, 0)
            if (magic != MAGIC_MAINNET) {
                Log.w("HNSGo", "SpvP2P: Invalid magic: 0x${magic.toString(16)} (expected: 0x${MAGIC_MAINNET.toString(16)})")
                return@withContext null
            }
            
            val cmdCode = header[4].toInt() and 0xFF
            val length = readInt32(header, 5)
            
            if (length > Config.MAX_MESSAGE_SIZE) { // HSK_MAX_MESSAGE (8MB)
                Log.w("HNSGo", "SpvP2P: Message too large: $length bytes (max: ${Config.MAX_MESSAGE_SIZE})")
                return@withContext null
            }
            
            val command = codeToCommand(cmdCode)
            Log.d("HNSGo", "SpvP2P: Received message: $command (code=$cmdCode, size=$length)")
            
            val payload = ByteArray(length)
            read = 0
            while (read < length) {
                val n = input.read(payload, read, length - read)
                if (n == -1) {
                    Log.w("HNSGo", "SpvP2P: EOF while reading payload (read $read of $length bytes)")
                    return@withContext null
                }
                read += n
            }
            
            // No checksum verification in Handshake protocol (hnsd doesn't verify checksum)
            return@withContext P2PMessage(command, payload)
        } catch (e: Exception) {
            Log.e("HNSGo", "SpvP2P: Error receiving message", e)
            return@withContext null
        }
    }
    
    // Helper functions
    private fun writeInt32(value: Int, array: ByteArray, offset: Int) {
        ByteBuffer.wrap(array, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }
    
    private fun readInt32(array: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(array, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
    
    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        val bytes = ByteArray(4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        write(bytes)
    }
    
    private fun ByteArrayOutputStream.writeInt64(value: Long) {
        val bytes = ByteArray(8)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
        write(bytes)
    }
    
    private fun ByteArrayOutputStream.writeVarString(str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        writeVarInt(bytes.size)
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
    
    private fun ByteArrayOutputStream.writeInt16(value: Int) {
        val bytes = ByteArray(2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        write(bytes)
    }
    
    private fun ByteArrayOutputStream.writeBytes(bytes: ByteArray) {
        write(bytes)
    }
    
    private fun readVarInt(buffer: ByteBuffer): Int {
        val first = buffer.get().toInt() and 0xFF
        return when (first) {
            in 0..0xFC -> first
            0xFD -> buffer.short.toInt() and 0xFFFF
            0xFE -> buffer.int
            else -> throw IllegalArgumentException("Invalid varint")
        }
    }
    
    private fun doubleSha256(data: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val first = md.digest(data)
        md.reset()
        return md.digest(first)
    }
}

data class Header(
    val version: Int,
    val prevBlock: ByteArray,
    val merkleRoot: ByteArray,  // This is name_root in hnsd
    val witnessRoot: ByteArray,
    val treeRoot: ByteArray,  // This is merkle_root in hnsd
    val reservedRoot: ByteArray,
    val time: Long,
    val bits: Int,
    val nonce: Int,
    val extraNonce: ByteArray,  // 24 bytes - MUST store full 24 bytes for correct hash calculation!
    val mask: ByteArray = ByteArray(32)  // Added mask field for hash calculation
) {
    fun hash(): ByteArray {
        // Use hnsd's hash algorithm (Blake2B + SHA3 + XOR)
        // Convert to HeaderHash.HeaderData format
        // IMPORTANT: extraNonce is now stored as full 24-byte ByteArray (not Long)
        // This is critical - hnsd uses ALL 24 bytes in hash calculation, not just the last 8!
        
        val headerData = com.acktarius.hnsgo.crypto.HeaderHash.HeaderData(
            nonce = nonce,
            time = time,
            prevBlock = prevBlock,
            nameRoot = merkleRoot,  // merkleRoot in Header = name_root in hnsd
            extraNonce = extraNonce,  // Use full 24-byte extraNonce directly
            reservedRoot = reservedRoot,
            witnessRoot = witnessRoot,
            merkleRoot = treeRoot,  // treeRoot in Header = merkle_root in hnsd
            version = version,
            bits = bits,
            mask = mask
        )
        
        return com.acktarius.hnsgo.crypto.HeaderHash.hash(headerData)
    }
    
    fun toBytes(): ByteArray {
        // Match parseHeader format exactly:
        // version(4) + prevBlock(32) + merkleRoot(32) + witnessRoot(32) + treeRoot(32) + 
        // reservedRoot(32) + time(4) + bits(4) + nonce(4) + extraNonce(24) = 200 bytes
        // Note: extraNonce is now 24 bytes (was 8 bytes as Long)
        val buffer = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(version)
        buffer.put(prevBlock)
        buffer.put(merkleRoot)
        buffer.put(witnessRoot)
        buffer.put(treeRoot)
        buffer.put(reservedRoot)
        buffer.putInt(time.toInt()) // time stored as int in wire format
        buffer.putInt(bits)
        buffer.putInt(nonce)
        buffer.put(extraNonce)  // Store full 24-byte extraNonce
        return buffer.array()
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Header
        return prevBlock.contentEquals(other.prevBlock)
    }
    
    override fun hashCode(): Int {
        return prevBlock.contentHashCode()
    }
}

data class P2PMessage(val command: String, val payload: ByteArray)

