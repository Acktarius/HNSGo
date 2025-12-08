package com.acktarius.hnsgo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import android.util.Log

/**
 * Kademlia DHT-based peer discovery for Handshake network
 * 
 * Based on hnsd's peer discovery implementation:
 * - Uses UDP for DHT operations (port 12038)
 * - Uses TCP for P2P communication (port 12038)
 * - Implements iterative neighbor lookups
 * - Maintains routing table of known peers
 * - Exchanges peer addresses via addr messages
 * 
 * Reference: https://github.com/handshake-org/hnsd/blob/master/src/dht.c
 */
class PeerDiscovery {
    companion object {
        // DHT uses same port as P2P but UDP instead of TCP
        private const val DHT_PORT = 12038
        private const val DHT_MAGIC = 0x5b6ef2d3.toInt() // Same as P2P magic
        
        // Kademlia constants
        private const val K_BUCKET_SIZE = 20 // Number of nodes per bucket
        private const val ALPHA = 3 // Concurrency parameter for lookups
        private const val BUCKET_COUNT = 256 // Number of buckets (one per bit)
        
        // Timeouts
        private const val DHT_QUERY_TIMEOUT_MS = 5000L
        private const val DHT_PING_TIMEOUT_MS = 3000L
        
        // Peer expiration
        private const val PEER_EXPIRY_SECONDS = 3600L // 1 hour
    }
    
    private val sha256 = MessageDigest.getInstance("SHA-256")
    private var socket: DatagramSocket? = null
    private var nodeId: ByteArray? = null
    private val routingTable = ConcurrentHashMap<Int, MutableList<PeerNode>>()
    private val knownPeers = ConcurrentHashMap<String, PeerNode>()
    private val requestIdCounter = AtomicInteger(0)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<DHTResponse>>()
    private var discoveryScope: CoroutineScope? = null
    private var isRunning = false
    
    /**
     * Peer node in the DHT network
     */
    data class PeerNode(
        val nodeId: ByteArray,
        val address: InetSocketAddress,
        var lastSeen: Long = System.currentTimeMillis(),
        var lastPing: Long = 0,
        var isBootstrap: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PeerNode
            return nodeId.contentEquals(other.nodeId) && address == other.address
        }
        
        override fun hashCode(): Int {
            var result = nodeId.contentHashCode()
            result = 31 * result + address.hashCode()
            return result
        }
    }
    
    /**
     * DHT message types (based on hnsd dht.c)
     */
    private enum class DHTMessageType(val value: Int) {
        PING(0),
        PONG(1),
        FIND_NODE(2),
        NODES(3),
        GET_PEERS(4),
        PEERS(5),
        ANNOUNCE_PEER(6)
    }
    
    /**
     * DHT message structure
     */
    private data class DHTMessage(
        val type: DHTMessageType,
        val requestId: Int,
        val target: ByteArray? = null,
        val nodes: List<PeerNode>? = null,
        val peers: List<InetSocketAddress>? = null
    )
    
    /**
     * DHT response
     */
    private data class DHTResponse(
        val nodes: List<PeerNode>,
        val peers: List<InetSocketAddress>
    )
    
    /**
     * Initialize peer discovery
     */
    suspend fun init() = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        if (isRunning) {
            return@withContext
        }
        
        try {
            // Generate random node ID
            nodeId = generateNodeId()
            
            // Create UDP socket for DHT
            socket = DatagramSocket().apply {
                soTimeout = DHT_QUERY_TIMEOUT_MS.toInt()
                reuseAddress = true
            }
            
            // Initialize routing table
            for (i in 0 until BUCKET_COUNT) {
                routingTable[i] = mutableListOf()
            }
            
            // Start receiver coroutine
            discoveryScope = CoroutineScope(Config.PEER_DISCOVERY_DISPATCHER + SupervisorJob())
            discoveryScope?.launch {
                receiveLoop()
            }
            
            isRunning = true
        } catch (e: Exception) {
            throw e
        }
    }
    
    /**
     * Start peer discovery with bootstrap nodes
     */
    suspend fun startDiscovery(bootstrapNodes: List<String>): List<PeerNode> = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        if (!isRunning) {
            init()
        }
        
        
        // Add bootstrap nodes to routing table
        val bootstrapPeers = mutableListOf<PeerNode>()
        for (seed in bootstrapNodes) {
            try {
                val (host, port) = parseSeedNode(seed)
                val address = InetSocketAddress(host, port)
                
                // Generate node ID for bootstrap node (or use identity from seed)
                val nodeId = extractNodeIdFromSeed(seed) ?: generateNodeId()
                val peer = PeerNode(nodeId, address, isBootstrap = true)
                
                addToRoutingTable(peer)
                bootstrapPeers.add(peer)
                
            } catch (e: Exception) {
            }
        }
        
        // Perform iterative neighbor lookup to discover more peers
        val discoveredPeers = mutableListOf<PeerNode>()
        discoveredPeers.addAll(bootstrapPeers)
        
        // Query bootstrap nodes for their neighbors (with timeout handling)
        var dhtSuccessCount = 0
        for (bootstrap in bootstrapPeers) {
            try {
                val neighbors = withTimeoutOrNull(DHT_QUERY_TIMEOUT_MS * 2) {
                    findNode(bootstrap, nodeId!!)
                }
                
                if (neighbors != null && neighbors.isNotEmpty()) {
                    dhtSuccessCount++
                    for (neighbor in neighbors) {
                        if (!discoveredPeers.contains(neighbor)) {
                            addToRoutingTable(neighbor)
                            discoveredPeers.add(neighbor)
                        }
                    }
                } else {
                }
            } catch (e: Exception) {
            }
        }
        
        // Only perform iterative lookup if we got at least one successful DHT response
        if (dhtSuccessCount > 0) {
            try {
                val morePeers = withTimeoutOrNull(DHT_QUERY_TIMEOUT_MS * 10) {
                    iterativeLookup(nodeId!!)
                }
                
                if (morePeers != null) {
                    for (peer in morePeers) {
                        if (!discoveredPeers.contains(peer)) {
                            addToRoutingTable(peer)
                            discoveredPeers.add(peer)
                        }
                    }
                }
            } catch (e: Exception) {
            }
        } else {
        }
        
        return@withContext discoveredPeers
    }
    
    /**
     * Find nodes close to target ID using iterative lookup
     */
    private suspend fun iterativeLookup(targetId: ByteArray): List<PeerNode> = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        val closestNodes = mutableListOf<PeerNode>()
        val queriedNodes = mutableSetOf<String>()
        val candidates = mutableListOf<PeerNode>()
        
        // Start with nodes from routing table closest to target
        val initialNodes = getClosestNodes(targetId, ALPHA)
        candidates.addAll(initialNodes)
        
        while (candidates.isNotEmpty()) {
            // Get ALPHA closest nodes that haven't been queried
            val toQuery = candidates
                .filter { !queriedNodes.contains(it.address.toString()) }
                .sortedBy { distance(it.nodeId, targetId) }
                .take(ALPHA)
            
            if (toQuery.isEmpty()) break
            
            // Query each node in parallel
            val results = toQuery.map { node ->
                async {
                    try {
                        queriedNodes.add(node.address.toString())
                        findNode(node, targetId)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
            
            // Add discovered nodes to candidates
            for (result in results) {
                for (newNode in result) {
                    if (!candidates.contains(newNode) && !queriedNodes.contains(newNode.address.toString())) {
                        candidates.add(newNode)
                    }
                }
            }
            
            // Update closest nodes
            candidates.sortBy { distance(it.nodeId, targetId) }
            closestNodes.clear()
            closestNodes.addAll(candidates.take(K_BUCKET_SIZE))
        }
        
        return@withContext closestNodes
    }
    
    /**
     * Find nodes close to target ID from a specific node
     */
    private suspend fun findNode(from: PeerNode, targetId: ByteArray): List<PeerNode> = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        try {
            val requestId = requestIdCounter.incrementAndGet()
            val deferred = CompletableDeferred<DHTResponse>()
            pendingRequests[requestId] = deferred
            
            // Send FIND_NODE message
            val message = DHTMessage(DHTMessageType.FIND_NODE, requestId, targetId)
            sendDHTMessage(from.address, message)
            
            // Wait for response with timeout
            val response = withTimeoutOrNull(DHT_QUERY_TIMEOUT_MS) {
                deferred.await()
            }
            
            pendingRequests.remove(requestId)
            
            if (response != null) {
                // Update last seen
                from.lastSeen = System.currentTimeMillis()
                return@withContext response.nodes
            } else {
                return@withContext emptyList()
            }
        } catch (e: Exception) {
            pendingRequests.remove(requestIdCounter.get())
            return@withContext emptyList()
        }
    }
    
    /**
     * Ping a node to check if it's alive
     */
    suspend fun ping(node: PeerNode): Boolean = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        try {
            val requestId = requestIdCounter.incrementAndGet()
            val deferred = CompletableDeferred<DHTResponse>()
            pendingRequests[requestId] = deferred
            
            val message = DHTMessage(DHTMessageType.PING, requestId)
            sendDHTMessage(node.address, message)
            
            val response = withTimeoutOrNull(DHT_PING_TIMEOUT_MS) {
                deferred.await()
            }
            
            pendingRequests.remove(requestId)
            
            if (response != null) {
                node.lastSeen = System.currentTimeMillis()
                node.lastPing = System.currentTimeMillis()
                return@withContext true
            }
            
            return@withContext false
        } catch (e: Exception) {
            return@withContext false
        }
    }
    
    /**
     * Get discovered peers suitable for P2P connection
     */
    fun getDiscoveredPeers(): List<PeerNode> {
        val peers = mutableListOf<PeerNode>()
        val now = System.currentTimeMillis()
        
        for (bucket in routingTable.values) {
            for (peer in bucket) {
                // Only return peers that haven't expired
                if (now - peer.lastSeen < PEER_EXPIRY_SECONDS * 1000) {
                    peers.add(peer)
                }
            }
        }
        
        return peers.distinct()
    }
    
    /**
     * Get closest nodes to target ID
     */
    private fun getClosestNodes(targetId: ByteArray, count: Int): List<PeerNode> {
        val allNodes = mutableListOf<PeerNode>()
        for (bucket in routingTable.values) {
            allNodes.addAll(bucket)
        }
        
        return allNodes
            .sortedBy { distance(it.nodeId, targetId) }
            .take(count)
    }
    
    /**
     * Add node to routing table
     */
    private fun addToRoutingTable(node: PeerNode) {
        val bucketIndex = getBucketIndex(node.nodeId)
        val bucket = routingTable[bucketIndex] ?: mutableListOf()
        
        // Check if node already exists
        val existing = bucket.find { it.nodeId.contentEquals(node.nodeId) }
        if (existing != null) {
            existing.lastSeen = node.lastSeen
            return
        }
        
        // Add to bucket if not full
        if (bucket.size < K_BUCKET_SIZE) {
            bucket.add(node)
            knownPeers[node.address.toString()] = node
        } else {
            // Bucket is full, ping oldest node
            val oldest = bucket.minByOrNull { it.lastPing }
            if (oldest != null) {
                CoroutineScope(Config.PEER_DISCOVERY_DISPATCHER).launch {
                    if (!ping(oldest)) {
                        // Remove oldest and add new node
                        bucket.remove(oldest)
                        bucket.add(node)
                        knownPeers.remove(oldest.address.toString())
                        knownPeers[node.address.toString()] = node
                    }
                }
            }
        }
    }
    
    /**
     * Get bucket index for node ID
     */
    private fun getBucketIndex(nodeId: ByteArray): Int {
        // XOR distance determines bucket
        val ourId = this.nodeId ?: return 0
        var firstDiffBit = 0
        
        for (i in 0 until minOf(ourId.size, nodeId.size)) {
            val xor = (ourId[i].toInt() and 0xFF) xor (nodeId[i].toInt() and 0xFF)
            if (xor != 0) {
                firstDiffBit = i * 8 + (7 - java.lang.Integer.numberOfLeadingZeros(xor))
                break
            }
        }
        
        return minOf(firstDiffBit, BUCKET_COUNT - 1)
    }
    
    /**
     * Calculate XOR distance between two node IDs
     */
    private fun distance(id1: ByteArray, id2: ByteArray): Long {
        var dist = 0L
        for (i in 0 until minOf(id1.size, id2.size)) {
            val xor = (id1[i].toInt() and 0xFF) xor (id2[i].toInt() and 0xFF)
            dist = (dist shl 8) or (xor.toLong() and 0xFF)
        }
        return dist
    }
    
    /**
     * Send DHT message via UDP
     */
    private suspend fun sendDHTMessage(address: InetSocketAddress, message: DHTMessage) = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        val socket = this@PeerDiscovery.socket ?: return@withContext
        
        try {
            val payload = serializeDHTMessage(message)
            val packet = DatagramPacket(payload, payload.size, address)
            socket.send(packet)
            
        } catch (e: Exception) {
        }
    }
    
    /**
     * Receive loop for DHT messages
     */
    private suspend fun receiveLoop() = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        val socket = this@PeerDiscovery.socket ?: return@withContext
        val buffer = ByteArray(8192)
        
        while (isRunning) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                
                val message = deserializeDHTMessage(packet.data, packet.length)
                if (message != null) {
                    handleDHTMessage(packet.address, packet.port, message)
                }
            } catch (e: SocketTimeoutException) {
                // Timeout is expected, continue
                continue
            } catch (e: Exception) {
                if (isRunning) {
                }
            }
        }
    }
    
    /**
     * Handle incoming DHT message
     */
    private suspend fun handleDHTMessage(address: InetAddress, port: Int, message: DHTMessage) = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        when (message.type) {
            DHTMessageType.PING -> {
                // Respond with PONG
                val response = DHTMessage(DHTMessageType.PONG, message.requestId)
                sendDHTMessage(InetSocketAddress(address, port), response)
            }
            
            DHTMessageType.FIND_NODE -> {
                // Find closest nodes to target
                val target = message.target ?: nodeId ?: return@withContext
                val closest = getClosestNodes(target, K_BUCKET_SIZE)
                
                val response = DHTMessage(
                    DHTMessageType.NODES,
                    message.requestId,
                    nodes = closest
                )
                sendDHTMessage(InetSocketAddress(address, port), response)
            }
            
            DHTMessageType.PONG, DHTMessageType.NODES, DHTMessageType.PEERS -> {
                // Response to our request
                val deferred = pendingRequests.remove(message.requestId)
                if (deferred != null) {
                    val response = DHTResponse(
                        nodes = message.nodes ?: emptyList(),
                        peers = message.peers ?: emptyList()
                    )
                    deferred.complete(response)
                }
            }
            
            else -> {
            }
        }
    }
    
    /**
     * Serialize DHT message to bytes
     */
    private fun serializeDHTMessage(message: DHTMessage): ByteArray {
        val buffer = ByteArrayOutputStream()
        
        // Magic (4 bytes)
        writeInt32(buffer, DHT_MAGIC)
        
        // Message type (1 byte)
        buffer.write(message.type.value)
        
        // Request ID (4 bytes)
        writeInt32(buffer, message.requestId)
        
        // Target (32 bytes, optional)
        if (message.target != null) {
            buffer.write(message.target)
        }
        
        // Nodes (varint count + node data)
        if (message.nodes != null) {
            writeVarInt(buffer, message.nodes.size)
            for (node in message.nodes) {
                buffer.write(node.nodeId)
                writeAddress(buffer, node.address)
            }
        }
        
        // Peers (varint count + peer addresses)
        if (message.peers != null) {
            writeVarInt(buffer, message.peers.size)
            for (peer in message.peers) {
                writeAddress(buffer, peer)
            }
        }
        
        return buffer.toByteArray()
    }
    
    /**
     * Deserialize DHT message from bytes
     */
    private fun deserializeDHTMessage(data: ByteArray, length: Int): DHTMessage? {
        try {
            val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            
            // Magic (4 bytes)
            val magic = buffer.int
            if (magic != DHT_MAGIC) {
                return null
            }
            
            // Message type (1 byte)
            val typeValue = buffer.get().toInt() and 0xFF
            val type = DHTMessageType.values().find { it.value == typeValue } ?: return null
            
            // Request ID (4 bytes)
            val requestId = buffer.int
            
            // Target (32 bytes, optional, only for FIND_NODE)
            val target = if (type == DHTMessageType.FIND_NODE && buffer.remaining() >= 32) {
                ByteArray(32).apply { buffer.get(this) }
            } else null
            
            // Nodes (varint count + node data)
            val nodes = if (type == DHTMessageType.NODES && buffer.hasRemaining()) {
                val count = readVarInt(buffer)
                mutableListOf<PeerNode>().apply {
                    for (i in 0 until count) {
                        if (buffer.remaining() >= 32 + 18) { // nodeId + address
                            val nodeId = ByteArray(32).apply { buffer.get(this) }
                            val address = readAddress(buffer)
                            if (address != null) {
                                add(PeerNode(nodeId, address))
                            }
                        }
                    }
                }
            } else null
            
            // Peers (varint count + peer addresses)
            val peers = if (type == DHTMessageType.PEERS && buffer.hasRemaining()) {
                val count = readVarInt(buffer)
                mutableListOf<InetSocketAddress>().apply {
                    for (i in 0 until count) {
                        val address = readAddress(buffer)
                        if (address != null) {
                            add(address)
                        }
                    }
                }
            } else null
            
            return DHTMessage(type, requestId, target, nodes, peers)
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Write address to buffer (IPv4: 4 bytes + 2 bytes port, IPv6: 16 bytes + 2 bytes port)
     */
    private fun writeAddress(buffer: ByteArrayOutputStream, address: InetSocketAddress) {
        val addr = address.address
        if (addr is Inet4Address) {
            buffer.write(addr.address)
            writeInt16(buffer, address.port)
        } else if (addr is Inet6Address) {
            buffer.write(addr.address)
            writeInt16(buffer, address.port)
        }
    }
    
    /**
     * Read address from buffer
     */
    private fun readAddress(buffer: ByteBuffer): InetSocketAddress? {
        return try {
            if (buffer.remaining() >= 6) {
                // Try IPv4 first (4 bytes + 2 bytes port)
                val ipBytes = ByteArray(4)
                buffer.get(ipBytes)
                val port = buffer.short.toInt() and 0xFFFF
                val ip = InetAddress.getByAddress(ipBytes)
                InetSocketAddress(ip, port)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Generate random node ID
     */
    private fun generateNodeId(): ByteArray {
        val random = ByteArray(32)
        java.security.SecureRandom().nextBytes(random)
        return random
    }
    
    /**
     * Extract node ID from seed string (identity@host:port format)
     */
    private fun extractNodeIdFromSeed(seed: String): ByteArray? {
        return try {
            val parts = seed.split("@")
            if (parts.size == 2) {
                val identity = parts[0]
                // Identity is base32 encoded node ID, decode it
                // For now, hash it to get 32 bytes
                sha256.reset()
                sha256.digest(identity.toByteArray(Charsets.UTF_8))
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parse seed node string (identity@host:port or identity@host)
     */
    private fun parseSeedNode(seed: String): Pair<String, Int> {
        val parts = seed.split("@")
        require(parts.size == 2) { "Invalid seed format: $seed" }
        
        val address = parts[1]
        val hostPort = address.split(":")
        val host = hostPort[0]
        val port = if (hostPort.size > 1) hostPort[1].toInt() else DHT_PORT
        
        return host to port
    }
    
    /**
     * Discover peers via DNS seeds (fallback method)
     */
    suspend fun discoverPeersFromDNS(): List<String> = withContext(Config.PEER_DISCOVERY_DISPATCHER) {
        // TODO: Implement DNS seed discovery similar to Bitcoin
        // Handshake may have DNS seeds like:
        // - seed.handshake.org
        // - seed1.handshake.org
        // etc.
        emptyList()
    }
    
    /**
     * Cleanup and shutdown
     */
    fun shutdown() {
        isRunning = false
        socket?.close()
        discoveryScope?.cancel()
        routingTable.clear()
        knownPeers.clear()
        pendingRequests.clear()
    }
    
    // Helper functions for binary serialization
    private fun writeInt32(buffer: ByteArrayOutputStream, value: Int) {
        val bytes = ByteArray(4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        buffer.write(bytes)
    }
    
    private fun writeInt16(buffer: ByteArrayOutputStream, value: Int) {
        val bytes = ByteArray(2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        buffer.write(bytes)
    }
    
    private fun writeVarInt(buffer: ByteArrayOutputStream, value: Int) {
        when {
            value < 0xFD -> buffer.write(value)
            value <= 0xFFFF -> {
                buffer.write(0xFD)
                writeInt16(buffer, value)
            }
            else -> {
                buffer.write(0xFE)
                writeInt32(buffer, value)
            }
        }
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
}


