package com.acktarius.hnsgo.spvp2p

import android.util.Log
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.Header
import com.acktarius.hnsgo.P2PMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles P2P message parsing and serialization
 */
internal object MessageHandler {
    // Handshake message command codes (from hnsd msg.h - EXACT MATCH!)
    private const val HSK_MSG_VERSION = 0
    private const val HSK_MSG_VERACK = 1
    private const val HSK_MSG_PING = 2
    private const val HSK_MSG_PONG = 3
    private const val HSK_MSG_GETADDR = 4
    private const val HSK_MSG_ADDR = 5
    private const val HSK_MSG_GETHEADERS = 10  // FIXED: was 6, should be 10 (from msg.h:19)
    private const val HSK_MSG_HEADERS = 11     // FIXED: was 7, should be 11 (from msg.h:20)
    private const val HSK_MSG_SENDHEADERS = 12 // FIXED: was 8, should be 12 (from msg.h:21)
    private const val HSK_MSG_NOTFOUND = 22
    private const val HSK_MSG_GETPROOF = 26
    private const val HSK_MSG_PROOF = 27
    
    private const val MAGIC_MAINNET = Config.MAGIC_MAINNET
    
    fun commandToCode(command: String): Int {
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
            "notfound" -> HSK_MSG_NOTFOUND
            "getproof" -> HSK_MSG_GETPROOF
            "proof" -> HSK_MSG_PROOF
            else -> throw IllegalArgumentException("Unknown command: $command")
        }
    }
    
    fun codeToCommand(code: Int): String {
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
            HSK_MSG_NOTFOUND -> "notfound"
            HSK_MSG_GETPROOF -> "getproof"
            HSK_MSG_PROOF -> "proof"
            else -> "unknown"
        }
    }
    
    fun sendMessage(output: OutputStream, command: String, payload: ByteArray) {
        // Handshake message format (matching hnsd/src/pool.c:hsk_peer_send EXACTLY):
        // Magic (4 bytes) + Command (1 byte) + Size (4 bytes) + Payload (size bytes)
        val cmdCode = commandToCode(command)
        
        val message = ByteArray(9 + payload.size).apply {
            writeInt32(MAGIC_MAINNET, this, 0)
            this[4] = cmdCode.toByte()
            writeInt32(payload.size, this, 5)
            System.arraycopy(payload, 0, this, 9, payload.size)
        }
        
        val debugBytes = message.take(20).joinToString(" ") { "%02x".format(it) }
        Log.d("HNSGo", "MessageHandler: Sending message: cmd=$command (code=$cmdCode), size=${message.size}, first 20 bytes: $debugBytes")
        
        output.write(message)
        output.flush()
    }
    
    suspend fun receiveMessage(input: InputStream): P2PMessage? = withContext(Dispatchers.IO) {
        try {
            // Step 1: Read message header (9 bytes: magic + cmd + size)
            // Matching hnsd's hsk_peer_parse_hdr behavior
            val header = ByteArray(9)
            var read = 0
            val headerTimeout = 30000L // 30 seconds for header
            val headerStartTime = System.currentTimeMillis()
            
            while (read < 9) {
                val elapsed = System.currentTimeMillis() - headerStartTime
                if (elapsed > headerTimeout) {
                    Log.w("HNSGo", "MessageHandler: Timeout reading message header (${elapsed}ms)")
                    return@withContext null
                }
                
                // InputStream.read() blocks until data is available or EOF
                // Socket timeout is set to 30 seconds, so this will throw SocketTimeoutException if no data
                val n = input.read(header, read, 9 - read)
                if (n == -1) {
                    Log.d("HNSGo", "MessageHandler: EOF while reading message header")
                    return@withContext null
                }
                read += n
            }
            
            val magic = readInt32(header, 0)
            if (magic != MAGIC_MAINNET) {
                Log.w("HNSGo", "MessageHandler: Invalid magic: 0x${magic.toString(16)} (expected: 0x${MAGIC_MAINNET.toString(16)})")
                return@withContext null
            }
            
            val cmdCode = header[4].toInt() and 0xFF
            val length = readInt32(header, 5)
            
            if (length > Config.MAX_MESSAGE_SIZE) {
                Log.w("HNSGo", "MessageHandler: Message too large: $length bytes (max: ${Config.MAX_MESSAGE_SIZE})")
                return@withContext null
            }
            
            val command = codeToCommand(cmdCode)
            Log.d("HNSGo", "MessageHandler: Received message header: $command (code=$cmdCode, payload size=$length)")
            
            // Step 2: Read payload (matching hnsd's buffering behavior)
            // hnsd allocates buffer and reads in chunks until complete
            val payload = ByteArray(length)
            read = 0
            val payloadTimeout = 60000L // 60 seconds for payload (larger messages may take longer)
            val payloadStartTime = System.currentTimeMillis()
            
            while (read < length) {
                val elapsed = System.currentTimeMillis() - payloadStartTime
                if (elapsed > payloadTimeout) {
                    Log.w("HNSGo", "MessageHandler: Timeout reading payload (read $read of $length bytes in ${elapsed}ms)")
                    return@withContext null
                }
                
                // InputStream.read() blocks until data is available or EOF
                // Socket timeout is set to 30 seconds, so this will throw SocketTimeoutException if no data
                val n = input.read(payload, read, length - read)
                if (n == -1) {
                    Log.w("HNSGo", "MessageHandler: EOF while reading payload (read $read of $length bytes)")
                    return@withContext null
                }
                read += n
            }
            
            Log.d("HNSGo", "MessageHandler: Successfully received complete message: $command (${payload.size} bytes)")
            return@withContext P2PMessage(command, payload)
        } catch (e: java.net.SocketTimeoutException) {
            Log.w("HNSGo", "MessageHandler: Socket timeout while receiving message", e)
            return@withContext null
        } catch (e: java.io.IOException) {
            Log.w("HNSGo", "MessageHandler: I/O error receiving message: ${e.message}")
            return@withContext null
        } catch (e: Exception) {
            Log.e("HNSGo", "MessageHandler: Error receiving message", e)
            return@withContext null
        }
    }
    
    data class VersionInfo(val version: Int, val services: Long, val height: Int)
    
    fun parseVersionMessage(payload: ByteArray): VersionInfo {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.int
        val services = buffer.long
        buffer.position(buffer.position() + 8) // Skip timestamp
        buffer.position(buffer.position() + 88) // Skip netaddr
        buffer.position(buffer.position() + 8) // Skip nonce
        val agentSize = buffer.get().toInt() and 0xFF
        buffer.position(buffer.position() + agentSize) // Skip agent
        val height = buffer.int
        return VersionInfo(version, services, height)
    }
    
    fun parseProofMessage(payload: ByteArray): Pair<List<ByteArray>, ByteArray?> {
        try {
            if (payload.size < 64) {
                Log.w("HNSGo", "MessageHandler: Proof message too short: ${payload.size} bytes (need at least 64)")
                return Pair(emptyList(), null)
            }
            
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val root = ByteArray(32).apply { buffer.get(this) }
            val key = ByteArray(32).apply { buffer.get(this) }
            
            Log.d("HNSGo", "MessageHandler: Proof message root: ${root.joinToString("") { "%02x".format(it) }.take(16)}...")
            Log.d("HNSGo", "MessageHandler: Proof message key: ${key.joinToString("") { "%02x".format(it) }.take(16)}...")
            
            val proofBytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
            return Pair(emptyList(), proofBytes)
        } catch (e: Exception) {
            Log.e("HNSGo", "MessageHandler: Error parsing proof message", e)
            return Pair(emptyList(), null)
        }
    }
    
    fun parseHeaders(payload: ByteArray): List<Header> {
        val headers = mutableListOf<Header>()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        
        try {
            val count = readVarInt(buffer)
            Log.d("HNSGo", "MessageHandler: Parsing $count headers (236 bytes each)")
            
            for (i in 0 until count) {
                if (buffer.remaining() < 236) {
                    Log.w("HNSGo", "MessageHandler: Not enough data for header $i (remaining: ${buffer.remaining()})")
                    break
                }
                val headerBytes = ByteArray(236).apply { buffer.get(this) }
                val header = parseHeader(headerBytes)
                headers.add(header)
            }
        } catch (e: Exception) {
            Log.e("HNSGo", "MessageHandler: Error parsing headers", e)
        }
        
        return headers
    }
    
    fun parseHeader(headerBytes: ByteArray): Header {
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val nonce = buffer.int
        val time = buffer.long
        val prevBlock = ByteArray(32).apply { buffer.get(this) }
        val nameRoot = ByteArray(32).apply { buffer.get(this) }
        val extraNonce = ByteArray(24).apply { buffer.get(this) }
        val reservedRoot = ByteArray(32).apply { buffer.get(this) }
        val witnessRoot = ByteArray(32).apply { buffer.get(this) }
        val merkleRoot = ByteArray(32).apply { buffer.get(this) }
        val version = buffer.int
        val bits = buffer.int
        val mask = ByteArray(32).apply { buffer.get(this) }
        
        return Header(
            version = version,
            prevBlock = prevBlock,
            nameRoot = nameRoot,
            witnessRoot = witnessRoot,
            merkleRoot = merkleRoot,
            reservedRoot = reservedRoot,
            time = time,
            bits = bits,
            nonce = nonce,
            extraNonce = extraNonce,
            mask = mask
        )
    }
    
    // Helper functions
    fun writeInt32(value: Int, array: ByteArray, offset: Int) {
        ByteBuffer.wrap(array, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }
    
    fun readInt32(array: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(array, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
    
    fun ByteArrayOutputStream.writeInt32(value: Int) {
        val bytes = ByteArray(4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        write(bytes)
    }
    
    fun ByteArrayOutputStream.writeInt64(value: Long) {
        val bytes = ByteArray(8)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
        write(bytes)
    }
    
    fun ByteArrayOutputStream.writeVarInt(value: Int) {
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
    
    fun ByteArrayOutputStream.writeInt16(value: Int) {
        val bytes = ByteArray(2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        write(bytes)
    }
    
    fun ByteArrayOutputStream.writeBytes(bytes: ByteArray) {
        write(bytes)
    }
    
    fun readVarInt(buffer: ByteBuffer): Int {
        val first = buffer.get().toInt() and 0xFF
        return when (first) {
            in 0..0xFC -> first
            0xFD -> buffer.short.toInt() and 0xFFFF
            0xFE -> buffer.int
            else -> throw IllegalArgumentException("Invalid varint")
        }
    }
    
    // Helper functions for ProtocolHandler to use extension functions
    fun writeInt32ToStream(stream: ByteArrayOutputStream, value: Int) {
        stream.writeInt32(value)
    }
    
    fun writeInt64ToStream(stream: ByteArrayOutputStream, value: Long) {
        stream.writeInt64(value)
    }
    
    fun writeInt16ToStream(stream: ByteArrayOutputStream, value: Int) {
        stream.writeInt16(value)
    }
    
    fun writeVarIntToStream(stream: ByteArrayOutputStream, value: Int) {
        stream.writeVarInt(value)
    }
    
    fun writeBytesToStream(stream: ByteArrayOutputStream, bytes: ByteArray) {
        stream.writeBytes(bytes)
    }
}

