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
        Log.d("HNSGo", "Checkpoint: Loading checkpoint at height $CHECKPOINT_HEIGHT")
        
        // Try multiple sources for checkpoint data
        val checkpointData = tryFetchCheckpoint()
        
        if (checkpointData != null) {
            val headers = parseCheckpoint(checkpointData)
            if (headers.isNotEmpty()) {
                headerChain.addAll(headers)
                val newHeight = CHECKPOINT_HEIGHT + headers.size - 1
                Log.d("HNSGo", "Checkpoint: Loaded ${headers.size} headers, chain height now: $newHeight")
                return@withContext newHeight
            } else {
                Log.w("HNSGo", "Checkpoint: Failed to parse checkpoint data")
            }
        } else {
            Log.w("HNSGo", "Checkpoint: Could not fetch checkpoint data from any source")
            Log.i("HNSGo", "Checkpoint: P2P sync will be required, or embed checkpoint data manually")
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
            Log.d("HNSGo", "Checkpoint: Using embedded checkpoint (${embeddedCheckpoint.size} bytes)")
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
                    Log.d("HNSGo", "Checkpoint: [DEBUG] Trying to fetch from $urlString")
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
                            Log.d("HNSGo", "Checkpoint: [DEBUG] Successfully fetched ${checkpointData.size} bytes")
                            return@withContext checkpointData
                        }
                    }
                    connection.disconnect()
                } catch (e: java.net.SocketTimeoutException) {
                    Log.d("HNSGo", "Checkpoint: [DEBUG] Timeout connecting to $urlString")
                    // Continue
                } catch (e: java.net.ConnectException) {
                    Log.d("HNSGo", "Checkpoint: [DEBUG] Could not connect to $urlString")
                    // Continue
                } catch (e: Exception) {
                    Log.d("HNSGo", "Checkpoint: [DEBUG] Error fetching from $urlString: ${e.message}")
                    // Continue
                }
            }
        }
        
        // Method 3: Local file (won't work on Android, kept for reference)
        // This is not applicable for mobile apps
        
        Log.d("HNSGo", "Checkpoint: No checkpoint data available")
        Log.w("HNSGo", "Checkpoint: Embedded checkpoint not implemented yet")
        Log.i("HNSGo", "Checkpoint: To bootstrap the chain autonomously:")
        Log.i("HNSGo", "  1. Embed checkpoint data from hnsd's checkpoints.h (recommended)")
        Log.i("HNSGo", "  2. Connect to working Handshake P2P peers via DHT discovery")
        if (Config.DEBUG_MODE) {
            Log.i("HNSGo", "  3. [DEBUG] Use local checkpoint server (development only)")
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
            Log.w("HNSGo", "Checkpoint: Context not initialized - call Checkpoint.init(context) first")
            return null
        }
        
        return try {
            // Load checkpoint from Android assets
            // The checkpoint.dat file should be placed in android/app/src/main/assets/
            // It's extracted from hnsd's checkpoints.h HSK_CHECKPOINT_MAIN array
            val inputStream = ctx.assets.open("checkpoint.dat")
            val checkpointData = inputStream.readBytes()
            inputStream.close()
            
            Log.d("HNSGo", "Checkpoint: Loaded ${checkpointData.size} bytes from assets/checkpoint.dat")
            checkpointData
        } catch (e: java.io.FileNotFoundException) {
            Log.w("HNSGo", "Checkpoint: checkpoint.dat not found in assets")
            Log.i("HNSGo", "Checkpoint: To add checkpoint:")
            Log.i("HNSGo", "  1. Download: https://raw.githubusercontent.com/handshake-org/hnsd/master/src/checkpoints.h")
            Log.i("HNSGo", "  2. Extract HSK_CHECKPOINT_MAIN array and convert to binary")
            Log.i("HNSGo", "  3. Save as android/app/src/main/assets/checkpoint.dat")
            null
        } catch (e: Exception) {
            Log.e("HNSGo", "Checkpoint: Error loading checkpoint from assets: ${e.message}", e)
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
            
            Log.d("HNSGo", "Checkpoint: Parsing checkpoint at height $height (expected: ${CHECKPOINT_HEIGHT})")
            
            if (height != CHECKPOINT_HEIGHT) {
                Log.w("HNSGo", "Checkpoint: Height mismatch! Got $height, expected ${CHECKPOINT_HEIGHT}")
            }
            
            // Skip chainwork (32 bytes, BIG-ENDIAN)
            offset += 32
            
            // Read exactly HSK_STORE_HEADERS_COUNT (150) headers (matching hnsd/src/store.c)
            // Each header is 236 bytes (hsk_header_read format, NOT 80-byte wire format!)
            // Format: nonce(4) + time(8) + prev_block(32) + name_root(32) + extra_nonce(24) +
            //         reserved_root(32) + witness_root(32) + merkle_root(32) + version(4) + bits(4) + mask(32) = 236 bytes
            val expectedHeaders = 150 // HSK_STORE_HEADERS_COUNT from store.h
            val headerSize = 236 // hsk_header_read format size
            val expectedSize = 4 + 32 + (expectedHeaders * headerSize) // height + chainwork + headers
            
            if (data.size < expectedSize) {
                Log.w("HNSGo", "Checkpoint: Data too small! Got ${data.size} bytes, expected at least $expectedSize bytes")
                return headers
            }
            
            var prevHeader: Header? = null
            for (i in 0 until expectedHeaders) {
                if (offset + headerSize > data.size) {
                    Log.w("HNSGo", "Checkpoint: Not enough data for header $i (offset=$offset, size=${data.size})")
                    break
                }
                val headerBytes = data.sliceArray(offset until offset + headerSize)
                try {
                    // Log raw bytes for first few headers to debug (FORCE with Log.e to ensure visibility)
                    if (i < 3) {
                        val rawHex = headerBytes.take(64).joinToString(" ") { "%02x".format(it) }
                        Log.e("HNSGo", "Checkpoint: Header $i raw bytes (first 64): $rawHex")
                        // Also log the prevBlock bytes specifically (bytes 12-43)
                        val prevBlockBytes = headerBytes.sliceArray(12 until 44)
                        val prevBlockHex = prevBlockBytes.joinToString(" ") { "%02x".format(it) }
                        Log.e("HNSGo", "Checkpoint: Header $i prevBlock raw bytes (bytes 12-43): $prevBlockHex")
                    }
                    
                    val header = parseCheckpointHeader(headerBytes)
                    
                    // Log parsed fields for first few headers
                    if (i < 3) {
                        val prevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }
                        Log.d("HNSGo", "Checkpoint: Header $i parsed prevBlock: $prevBlockHex")
                        Log.d("HNSGo", "Checkpoint: Header $i nonce: ${header.nonce}, time: ${header.time}")
                    }
                    
                    // Validate chain continuity (matching hnsd/src/store.c:182)
                    if (i > 0 && prevHeader != null) {
                        val prevHash = prevHeader.hash()
                        if (!header.prevBlock.contentEquals(prevHash)) {
                            val prevHashHex = prevHash.joinToString("") { "%02x".format(it) }
                            val prevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }
                            Log.e("HNSGo", "Checkpoint: Invalid checkpoint chain at header $i!")
                            Log.e("HNSGo", "Checkpoint: Header $i prevBlock: $prevBlockHex")
                            Log.e("HNSGo", "Checkpoint: Previous header hash: $prevHashHex")
                            Log.e("HNSGo", "Checkpoint: Headers should connect but don't match!")
                            // Continue parsing but log the error
                        } else {
                            Log.d("HNSGo", "Checkpoint: Header $i connects to previous (prevBlock matches hash)")
                        }
                    }
                    
                    headers.add(header)
                    prevHeader = header
                    offset += headerSize
                } catch (e: Exception) {
                    Log.e("HNSGo", "Checkpoint: Error parsing header $i at offset $offset", e)
                    break
                }
            }
            
            Log.d("HNSGo", "Checkpoint: Parsed ${headers.size} headers from checkpoint (expected: $expectedHeaders)")
            if (headers.size != expectedHeaders) {
                Log.w("HNSGo", "Checkpoint: Header count mismatch! Got ${headers.size}, expected $expectedHeaders")
            }
        } catch (e: Exception) {
            Log.e("HNSGo", "Checkpoint: Error parsing checkpoint data", e)
        }
        
        return headers
    }
    
    /**
     * Parse a single header from checkpoint format (236 bytes, matching hsk_header_read)
     * Format: nonce(4) + time(8) + prev_block(32) + name_root(32) + extra_nonce(24) +
     *         reserved_root(32) + witness_root(32) + merkle_root(32) + version(4) + bits(4) + mask(32)
     */
    private fun parseCheckpointHeader(headerBytes: ByteArray): Header {
        if (headerBytes.size < 236) {
            throw IllegalArgumentException("Checkpoint header must be 236 bytes, got ${headerBytes.size}")
        }
        
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        
        val nonce = buffer.int
        val time = buffer.long
        val prevBlock = ByteArray(32).apply { buffer.get(this) }
        val nameRoot = ByteArray(32).apply { buffer.get(this) } // This is treeRoot in our Header format
        val extraNonce = ByteArray(24).apply { buffer.get(this) }  // 24 bytes - MUST store full 24 bytes!
        val reservedRoot = ByteArray(32).apply { buffer.get(this) }
        val witnessRoot = ByteArray(32).apply { buffer.get(this) }
        val merkleRoot = ByteArray(32).apply { buffer.get(this) }
        val version = buffer.int
        val bits = buffer.int
        val mask = ByteArray(32).apply { buffer.get(this) }
        
        return Header(
            version = version,
            prevBlock = prevBlock,
            merkleRoot = nameRoot,  // name_root in checkpoint = merkleRoot in Header (for hash calculation)
            witnessRoot = witnessRoot,
            treeRoot = merkleRoot,  // merkle_root in checkpoint = treeRoot in Header (for hash calculation)
            reservedRoot = reservedRoot,
            time = time,
            bits = bits,
            nonce = nonce,
            extraNonce = extraNonce,  // Store full 24-byte extraNonce for correct hash calculation
            mask = mask  // Include mask field for hash calculation
        )
    }
}

