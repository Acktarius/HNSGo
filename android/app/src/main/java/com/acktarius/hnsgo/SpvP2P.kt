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
    // Mainnet magic: 0x5b6ef2d3 (from HSK_MAGIC in constants.h)
    private const val MAGIC_MAINNET = 0x5b6ef2d3.toInt()
    
    // Handshake default port (from HSK_PORT in constants.h)
    private const val DEFAULT_PORT = 12038
    
    // Protocol version (from HSK_PROTO_VERSION)
    private const val PROTO_VERSION = 1
    
    // Handshake seed nodes (mainnet)
    // Note: Public seed nodes may not be accessible.
    // Try local hnsd first (if running), then fallback to public seeds
    private val SEED_NODES = listOf(
        // Local hnsd (if running on same network)
        "aorsxa4ylaacshipyjkfbvzfkh3jhh4yowtoqdt64nzemqtiw2whk@192.168.1.8:12038",
        // Public seed nodes (may not be accessible)
        "aorsxa4ylaacshipyjkfbvzfkh3jhh4yowtoqdt64nzemqtiw2whk@104.131.10.108",
        "aorsxa4ylaacshipyjkfbvzfkh3jhh4yowtoqdt64nzemqtiw2whk@104.131.10.109"
    )
    
    /**
     * Try to discover peers via DNS seeds (future enhancement)
     * For now, P2P sync may fail if seed nodes are not accessible.
     * The app can still work with an external Handshake resolver (e.g., local hnsd).
     */
    private suspend fun discoverPeers(): List<String> = withContext(Dispatchers.IO) {
        // TODO: Implement DNS seed discovery
        // Handshake may have DNS seeds similar to Bitcoin
        emptyList()
    }
    
    /**
     * Query a Handshake peer for domain resource records
     * @param nameHash SHA256 hash of the domain name
     * @return Pair of (resource records, Merkle proof) or null if not found
     */
    suspend fun queryName(
        nameHash: ByteArray
    ): Pair<List<ByteArray>, ByteArray?>? = withContext(Dispatchers.IO) {
        // Try each seed node
        for (seed in SEED_NODES) {
            try {
                val (host, port) = parseSeedNode(seed)
                Log.d("HNSGo", "SpvP2P: Querying name from $host:$port")
                
                val result = queryNameFromPeer(host, port, nameHash)
                if (result != null) {
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.e("HNSGo", "SpvP2P: Error querying name from seed $seed", e)
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
            
            // Handshake
            sendVersion(output, host, port)
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
     * Tries each seed node with exponential backoff retry
     */
    suspend fun syncHeaders(
        startHeight: Int = 0,
        onHeaderReceived: (Header) -> Boolean // Return false to stop syncing
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d("HNSGo", "SpvP2P: Starting header sync from height $startHeight")
        Log.d("HNSGo", "SpvP2P: Trying ${SEED_NODES.size} seed nodes")
        
        for ((seedIndex, seed) in SEED_NODES.withIndex()) {
            try {
                val (host, port) = parseSeedNode(seed)
                Log.d("HNSGo", "SpvP2P: [${seedIndex + 1}/${SEED_NODES.size}] Attempting connection to $host:$port")
                
                // Try with retries and exponential backoff
                val success = connectWithRetry(host, port, startHeight, onHeaderReceived)
                if (success) {
                    Log.d("HNSGo", "SpvP2P: Successfully synced from $host:$port")
                    return@withContext true
                } else {
                    Log.w("HNSGo", "SpvP2P: Failed to sync from $host:$port, trying next seed")
                }
            } catch (e: Exception) {
                Log.e("HNSGo", "SpvP2P: Error with seed $seed", e)
                continue
            }
        }
        
        Log.w("HNSGo", "SpvP2P: All seed nodes failed. P2P sync unavailable.")
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
        onHeaderReceived: (Header) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        var delay = Config.P2P_RETRY_BASE_DELAY_MS
        
        val maxRetries = Config.P2P_MAX_RETRIES
        for (attempt in 1..maxRetries) {
            try {
                Log.d("HNSGo", "SpvP2P: Connection attempt $attempt/$maxRetries to $host:$port")
                
                val success = connectAndSync(host, port, startHeight, onHeaderReceived)
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
            
            // Send version message
            Log.d("HNSGo", "SpvP2P: Sending version message...")
            sendVersion(output, host, port)
            Log.d("HNSGo", "SpvP2P: Version message sent")
            
            // Wait for version/verack
            Log.d("HNSGo", "SpvP2P: Waiting for handshake...")
            if (!handshake(input, output)) {
                Log.w("HNSGo", "SpvP2P: Handshake failed")
                return@withContext false
            }
            Log.d("HNSGo", "SpvP2P: Handshake successful")
            
            // Request headers
            Log.d("HNSGo", "SpvP2P: Requesting headers from height $startHeight...")
            sendGetHeaders(output, startHeight)
            
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
    
    private fun sendVersion(output: OutputStream, host: String, port: Int) {
        // Handshake version message format (based on Bitcoin P2P protocol)
        val version = 70015 // Protocol version
        val services = 0L
        val timestamp = System.currentTimeMillis() / 1000
        val nonce = System.currentTimeMillis()
        
        val payload = ByteArrayOutputStream().apply {
            writeInt32(version)
            writeInt64(services)
            writeInt64(timestamp)
            // addr_recv (26 bytes: services + IPv6 + port)
            writeInt64(0) // services
            writeBytes(ByteArray(16)) // IPv6 (all zeros = IPv4)
            writeInt16(port) // port (network byte order)
            // addr_from (26 bytes: services + IPv6 + port)
            writeInt64(0) // services
            writeBytes(ByteArray(16)) // IPv6 (all zeros = IPv4)
            writeInt16(0) // port
            writeInt64(nonce)
            // User agent
            writeVarString("HNSGo/1.0")
            // Height
            writeInt32(0)
        }.toByteArray()
        
        sendMessage(output, "version", payload)
    }
    
    private suspend fun handshake(input: InputStream, output: OutputStream): Boolean {
        var versionReceived = false
        var verackReceived = false
        
        // Receive version and verack
        var attempts = 0
        while (attempts < 10 && (!versionReceived || !verackReceived)) {
            Log.d("HNSGo", "SpvP2P: Waiting for message (attempt $attempts)...")
            val message = receiveMessage(input)
            if (message == null) {
                Log.w("HNSGo", "SpvP2P: Failed to receive message")
                return false
            }
            
            Log.d("HNSGo", "SpvP2P: Received message: ${message.command}")
            
            when (message.command) {
                "version" -> {
                    Log.d("HNSGo", "SpvP2P: Version received, sending verack...")
                    versionReceived = true
                    sendMessage(output, "verack", byteArrayOf())
                }
                "verack" -> {
                    Log.d("HNSGo", "SpvP2P: Verack received")
                    verackReceived = true
                }
                else -> {
                    Log.d("HNSGo", "SpvP2P: Received unexpected message: ${message.command}")
                }
            }
            attempts++
        }
        
        val success = versionReceived && verackReceived
        Log.d("HNSGo", "SpvP2P: Handshake complete - version: $versionReceived, verack: $verackReceived")
        return success
    }
    
    private fun sendGetHeaders(output: OutputStream, startHeight: Int) {
        // GetHeaders message - request headers starting from startHeight
        val payload = ByteArrayOutputStream().apply {
            writeInt32(1) // Version
            writeInt32(1) // Hash count
            // Block locator (start from genesis or checkpoint)
            writeBytes(ByteArray(32)) // Placeholder - should be actual block hash
            writeBytes(ByteArray(32)) // Stop hash (all zeros = no stop)
        }.toByteArray()
        
        sendMessage(output, "getheaders", payload)
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
            
            for (i in 0 until count) {
                // Handshake block header format (80 bytes + 1 byte for tx count)
                val headerBytes = ByteArray(80)
                buffer.get(headerBytes)
                
                val header = parseHeader(headerBytes)
                headers.add(header)
                
                // Skip tx count (1 byte) - SPV doesn't need transaction data
                if (buffer.hasRemaining()) {
                    buffer.get()
                }
            }
        } catch (e: Exception) {
            Log.e("HNSGo", "SpvP2P: Error parsing headers", e)
        }
        
        return headers
    }
    
    private fun parseHeader(headerBytes: ByteArray): Header {
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        
        val version = buffer.int
        val prevBlock = ByteArray(32).apply { buffer.get(this) }
        val merkleRoot = ByteArray(32).apply { buffer.get(this) }
        val witnessRoot = ByteArray(32).apply { buffer.get(this) }
        val treeRoot = ByteArray(32).apply { buffer.get(this) }
        val reservedRoot = ByteArray(32).apply { buffer.get(this) }
        val time = buffer.int.toLong()
        val bits = buffer.int
        val nonce = buffer.int
        val extraNonce = buffer.long
        
        return Header(
            version = version,
            prevBlock = prevBlock,
            merkleRoot = merkleRoot,
            witnessRoot = witnessRoot,
            treeRoot = treeRoot,
            reservedRoot = reservedRoot,
            time = time,
            bits = bits,
            nonce = nonce,
            extraNonce = extraNonce
        )
    }
    
    private fun sendMessage(output: OutputStream, command: String, payload: ByteArray) {
        val commandBytes = command.toByteArray(Charsets.US_ASCII)
        val commandPadded = ByteArray(12).apply {
            System.arraycopy(commandBytes, 0, this, 0, minOf(commandBytes.size, 12))
        }
        
        val message = ByteArray(24 + payload.size).apply {
            // Magic
            writeInt32(MAGIC_MAINNET, this, 0)
            // Command
            System.arraycopy(commandPadded, 0, this, 4, 12)
            // Length
            writeInt32(payload.size, this, 16)
            // Checksum (first 4 bytes of double SHA256)
            val checksum = doubleSha256(payload).take(4).toByteArray()
            System.arraycopy(checksum, 0, this, 20, 4)
            // Payload
            System.arraycopy(payload, 0, this, 24, payload.size)
        }
        
        output.write(message)
        output.flush()
    }
    
    private suspend fun receiveMessage(input: InputStream): P2PMessage? = withContext(Dispatchers.IO) {
        try {
            val header = ByteArray(24)
            var read = 0
            while (read < 24) {
                val n = input.read(header, read, 24 - read)
                if (n == -1) return@withContext null
                read += n
            }
            
            val magic = readInt32(header, 0)
            if (magic != MAGIC_MAINNET) {
                Log.w("HNSGo", "SpvP2P: Invalid magic: ${magic.toString(16)}")
                return@withContext null
            }
            
            val command = String(header, 4, 12).trim { it <= ' ' }
            val length = readInt32(header, 16)
            val checksum = ByteArray(4).apply { System.arraycopy(header, 20, this, 0, 4) }
            
            val payload = ByteArray(length)
            read = 0
            while (read < length) {
                val n = input.read(payload, read, length - read)
                if (n == -1) return@withContext null
                read += n
            }
            
            // Verify checksum
            val calculatedChecksum = doubleSha256(payload).take(4).toByteArray()
            if (!calculatedChecksum.contentEquals(checksum)) {
                Log.w("HNSGo", "SpvP2P: Invalid checksum")
                return@withContext null
            }
            
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
    val merkleRoot: ByteArray,
    val witnessRoot: ByteArray,
    val treeRoot: ByteArray,
    val reservedRoot: ByteArray,
    val time: Long,
    val bits: Int,
    val nonce: Int,
    val extraNonce: Long
) {
    fun hash(): ByteArray {
        // Calculate block hash (double SHA256 of header)
        val headerBytes = toBytes()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val first = md.digest(headerBytes)
        md.reset()
        return md.digest(first)
    }
    
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(version)
        buffer.put(prevBlock)
        buffer.put(merkleRoot)
        buffer.put(witnessRoot)
        buffer.put(treeRoot)
        buffer.put(reservedRoot)
        buffer.putInt(time.toInt())
        buffer.putInt(bits)
        buffer.putInt(nonce)
        buffer.putLong(extraNonce)
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

