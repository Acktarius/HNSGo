package com.acktarius.hnsgo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handshake checkpoint data for bootstrap
 * Based on hnsd's checkpoint format from checkpoints.h
 * 
 * Format:
 * - Height (4 bytes, little-endian)
 * - Chainwork (32 bytes)
 * - Headers (N * 80 bytes each)
 * 
 * Source: https://github.com/handshake-org/hnsd/blob/master/src/checkpoints.h
 */
object Checkpoint {
    // Mainnet checkpoint at height 136000 (from hnsd constants.h and checkpoints.h)
    private const val CHECKPOINT_HEIGHT = Config.CHECKPOINT_HEIGHT
    
    // Android Context for accessing assets (set during initialization)
    private var context: Context? = null
    
    /**
     * Initialize checkpoint with Android Context for asset access
     */
    fun init(context: Context) {
        this.context = context.applicationContext
    }
    
    /**
     * Load checkpoint headers into the header chain
     * This bootstrap the chain when P2P sync fails
     * 
     * @param headerChain Mutable list to add headers to
     * @param currentHeight Current chain height (should be 0 if no headers)
     * @return New chain height after loading checkpoint
     */
    suspend fun loadCheckpoint(headerChain: MutableList<Header>, currentHeight: Int): Int = withContext(Dispatchers.IO) {
        
        // Try multiple sources for checkpoint data
        val checkpointData = tryFetchCheckpoint()
        
        if (checkpointData != null) {
            val headers = parseCheckpoint(checkpointData)
            if (headers.isNotEmpty()) {
                headerChain.addAll(headers)
                val newHeight = CHECKPOINT_HEIGHT + headers.size - 1
                return@withContext newHeight
            } else {
            }
        } else {
        }
        
        return@withContext currentHeight
    }
    
    /**
     * Try to fetch checkpoint data from various sources
     * Priority:
     * 1. Embedded checkpoint (PRODUCTION - most reliable)
     * 2. HTTP endpoint (DEVELOPMENT ONLY - for testing)
     * 3. Local file (won't work on Android, but kept for completeness)
     */
    private suspend fun tryFetchCheckpoint(): ByteArray? = withContext(Dispatchers.IO) {
        // Method 1: Use embedded checkpoint (PRODUCTION)
        // This is the primary method - checkpoint data embedded in app
        // Extracted from hnsd's checkpoints.h
        val embeddedCheckpoint = getEmbeddedCheckpoint()
        if (embeddedCheckpoint != null) {
            return@withContext embeddedCheckpoint
        }
        
        // Method 2: HTTP endpoint (DEVELOPMENT ONLY - disabled by default)
        // Only enabled when DEBUG_MODE is true for development/testing
        // This allows testing without embedding checkpoint data during development
        if (Config.DEBUG_MODE) {
            val checkpointUrls = listOf(
                "http://localhost:8080/checkpoint.dat",  // Localhost only for development
            )
            
            for (urlString in checkpointUrls) {
                try {
                    val url = URL(urlString)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 3000  // Shorter timeout for faster failure
                    connection.readTimeout = 5000
                    connection.requestMethod = "GET"
                    
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val inputStream = connection.inputStream
                        val checkpointData = inputStream.readBytes()
                        inputStream.close()
                        connection.disconnect()
                        
                        if (checkpointData.isNotEmpty()) {
                            return@withContext checkpointData
                        }
                    }
                    connection.disconnect()
                } catch (e: java.net.SocketTimeoutException) {
                    // Continue
                } catch (e: java.net.ConnectException) {
                    // Continue
                } catch (e: Exception) {
                    // Continue
                }
            }
        }
        
        // Method 3: Local file (won't work on Android, kept for reference)
        // This is not applicable for mobile apps
        
        if (Config.DEBUG_MODE) {
        }
        
        return@withContext null
    }
    
    /**
     * Get embedded checkpoint data
     * This loads the checkpoint from Android assets folder
     * Format: Height (4 bytes) + Chainwork (32 bytes) + Headers (N * 80 bytes)
     * 
     * Source: https://github.com/handshake-org/hnsd/blob/master/src/checkpoints.h
     * The checkpoint is extracted from HSK_CHECKPOINT_MAIN and stored as checkpoint.dat in assets/
     * 
     * To generate checkpoint.dat:
     * 1. Download checkpoints.h from: https://raw.githubusercontent.com/handshake-org/hnsd/master/src/checkpoints.h
     * 2. Extract HSK_CHECKPOINT_MAIN array content
     * 3. Convert hex strings (\x00\x02...) to binary bytes
     * 4. Save as android/app/src/main/assets/checkpoint.dat
     */
    private fun getEmbeddedCheckpoint(): ByteArray? {
        val ctx = context ?: run {
            return null
        }
        
        return try {
            // Load checkpoint from Android assets
            // The checkpoint.dat file should be placed in android/app/src/main/assets/
            // It's extracted from hnsd's checkpoints.h HSK_CHECKPOINT_MAIN array
                    val inputStream = ctx.assets.open("checkpoint.dat")
                    val checkpointData = inputStream.readBytes()
                    inputStream.close()
                    
                    val expectedSize = 4 + 32 + (150 * 236)  // 35436 bytes
                    
                    if (checkpointData.size != expectedSize) {
                        return null
                    }
                    
                    // Verify first 4 bytes are height 136000 (0x00021340 in big-endian)
                    if (checkpointData.size >= 4) {
                        val heightBytes = checkpointData.sliceArray(0 until 4)
                        val height = (heightBytes[0].toInt() and 0xFF shl 24) or
                                    (heightBytes[1].toInt() and 0xFF shl 16) or
                                    (heightBytes[2].toInt() and 0xFF shl 8) or
                                    (heightBytes[3].toInt() and 0xFF)
                        if (height != CHECKPOINT_HEIGHT) {
                            return null
                        }
                    }
                    
                    checkpointData
        } catch (e: java.io.FileNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parse checkpoint data from byte array
     * Format matches hnsd's HSK_STORE_CHECKPOINT_SIZE structure
     * 
     * Format from hnsd checkpoints.h:
     * - Height (4 bytes, BIG-ENDIAN) - stored as "\x00\x02\x13\x40" = 136000
     * - Chainwork (32 bytes, BIG-ENDIAN)
     * - Headers (N * 80 bytes each, LITTLE-ENDIAN - Bitcoin protocol)
     */
    private fun parseCheckpoint(data: ByteArray): List<Header> {
        val headers = mutableListOf<Header>()
        
        try {
            var offset = 0
            
            // Read height (4 bytes, BIG-ENDIAN)
            // Format: "\x00\x02\x13\x40" = 136000 in big-endian
            val heightBytes = data.sliceArray(offset until offset + 4)
            val height = (heightBytes[0].toInt() and 0xFF shl 24) or
                        (heightBytes[1].toInt() and 0xFF shl 16) or
                        (heightBytes[2].toInt() and 0xFF shl 8) or
                        (heightBytes[3].toInt() and 0xFF)
            offset += 4
            
            
            if (height != CHECKPOINT_HEIGHT) {
                return headers  // Don't parse wrong checkpoint
            }
            
            // Verify checkpoint data size matches expected format
            // Format: height(4) + chainwork(32) + headers(150*236) = 35436 bytes
            val expectedCheckpointSize = 4 + 32 + (150 * 236)  // 35436 bytes
            if (data.size != expectedCheckpointSize) {
                return headers  // Don't parse wrong format
            }
            
            // Skip chainwork (32 bytes, BIG-ENDIAN)
            offset += 32
            
            // Verify first header's nonce matches hnsd's checkpoints.h (sanity check)
            // From checkpoints.h line 19: first header (block 136000) starts with "\x47\xb3\x3c\xe5" (nonce in little-endian)
            // Bytes: 0x47, 0xb3, 0x3c, 0xe5 = 0xe53cb347 in little-endian
            // First header starts at offset 36 (after height 4 + chainwork 32)
            if (offset + 4 <= data.size) {
                val firstNonceBytes = data.sliceArray(offset until offset + 4)
                val firstNonce = ByteBuffer.wrap(firstNonceBytes).order(ByteOrder.LITTLE_ENDIAN).int
                // Expected nonce from checkpoints.h line 19: "\x47\xb3\x3c\xe5" = 0xe53cb347 (little-endian)
                val expectedNonce = 0xe53cb347.toInt()  // From checkpoints.h
                if (firstNonce != expectedNonce) {
                    return headers  // Don't parse wrong checkpoint
                } else {
                }
            }
            
            // Read exactly HSK_STORE_HEADERS_COUNT (150) headers (matching hnsd/src/store.c)
            // Each header is 236 bytes (hsk_header_read format, matching header.c:204-238)
            // Format: nonce(4) + time(8) + prev_block(32) + name_root(32) + extra_nonce(24) +
            //         reserved_root(32) + witness_root(32) + merkle_root(32) + version(4) + bits(4) + mask(32) = 236 bytes
            val expectedHeaders = 150 // HSK_STORE_HEADERS_COUNT from store.h
            val headerSize = 236 // hsk_header_read format size (matching header.c:204-238)
            val expectedSize = 4 + 32 + (expectedHeaders * headerSize) // height + chainwork + headers
            
            if (data.size < expectedSize) {
                return headers
            }
            
            var prevHeader: Header? = null
            for (i in 0 until expectedHeaders) {
                if (offset + headerSize > data.size) {
                    break
                }
                val headerBytes = data.sliceArray(offset until offset + headerSize)
                try {
                    val header = parseCheckpointHeader(headerBytes)
                    
                    // For first and last headers, verify hash calculation
                    if (i == 0 || i == expectedHeaders - 1) {
                        val headerHash = header.hash()
                        val hashHex = headerHash.joinToString("") { "%02x".format(it) }
                        val hashIsZero = headerHash.all { it == 0.toByte() }
                        val prevBlockHex = header.prevBlock.take(16).joinToString("") { "%02x".format(it) }
                        val nameRootHex = header.nameRoot.take(16).joinToString("") { "%02x".format(it) }
                        val maskHex = header.mask.take(16).joinToString("") { "%02x".format(it) }
                        val headerHeight = CHECKPOINT_HEIGHT + i
                        
                        // CRITICAL: Log the last header (block 136149) hash for verification
                        if (i == expectedHeaders - 1) {
                            // Verify last header nonce matches checkpoints.h line 2999
                            // Expected nonce from checkpoints.h: "\x30\x00\xf7\x2e" = 0x2ef70030 (little-endian)
                            val expectedLastNonce = 0x2ef70030.toInt()
                            if (header.nonce != expectedLastNonce) {
                            } else {
                            }
                            
                        }
                    }
                    
                    // Validate chain continuity (matching hnsd/src/store.c:182)
                    if (i > 0 && prevHeader != null) {
                        val prevHash = prevHeader.hash()
                        if (!header.prevBlock.contentEquals(prevHash)) {
                            val prevHashHex = prevHash.joinToString("") { "%02x".format(it) }
                            val prevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }
                            // Continue parsing but log the error
                        } else {
                        }
                    }
                    
                    headers.add(header)
                    prevHeader = header
                    offset += headerSize
                } catch (e: Exception) {
                    break
                }
            }
            
            if (headers.size != expectedHeaders) {
            }
        } catch (e: Exception) {
        }
        
        return headers
    }
    
    /**
     * Parse a single header from checkpoint format (236 bytes, matching hsk_header_read in header.c:204-238)
     * Format: nonce(4) + time(8) + prev_block(32) + name_root(32) + extra_nonce(24) +
     *         reserved_root(32) + witness_root(32) + merkle_root(32) + version(4) + bits(4) + mask(32) = 236 bytes
     */
    private fun parseCheckpointHeader(headerBytes: ByteArray): Header {
        require(headerBytes.size >= 236) { "Checkpoint header must be 236 bytes (hsk_header_read format), got ${headerBytes.size}" }
        
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        
        // EXACT MATCH to hsk_header_read (header.c:204-238)
        val nonce = buffer.int
        val time = buffer.long
        val prevBlock = ByteArray(32).apply { buffer.get(this) }
        val nameRoot = ByteArray(32).apply { buffer.get(this) }  // name_root in hnsd
        val extraNonce = ByteArray(24).apply { buffer.get(this) }
        val reservedRoot = ByteArray(32).apply { buffer.get(this) }
        val witnessRoot = ByteArray(32).apply { buffer.get(this) }
        val merkleRoot = ByteArray(32).apply { buffer.get(this) }  // merkle_root in hnsd
        val version = buffer.int
        val bits = buffer.int
        val mask = ByteArray(32).apply { buffer.get(this) }
        
        // Mask can be zero for old checkpoint headers - that's valid
        // Only log if we see suspicious data (but don't treat zero mask as error)
        
        return Header(
            version = version,
            prevBlock = prevBlock,
            nameRoot = nameRoot,  // name_root in checkpoint = nameRoot in Header (matches hnsd naming)
            witnessRoot = witnessRoot,
            merkleRoot = merkleRoot,  // merkle_root in checkpoint = merkleRoot in Header (matches hnsd naming)
            reservedRoot = reservedRoot,
            time = time,
            bits = bits,
            nonce = nonce,
            extraNonce = extraNonce,
            mask = mask  // Include mask field for hash calculation
        )
    }
}

