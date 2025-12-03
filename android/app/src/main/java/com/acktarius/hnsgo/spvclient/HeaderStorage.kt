package com.acktarius.hnsgo.spvclient

import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.Header
import com.upokecenter.cbor.CBORObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Handles header chain storage and loading
 * Manages persistence of headers to disk and loading them back into memory
 */
object HeaderStorage {
    private val sha256 = MessageDigest.getInstance("SHA-256")
    
    /**
     * Load header chain from disk
     * Only loads the last MAX_IN_MEMORY_HEADERS to avoid OOM
     */
    suspend fun loadHeaderChain(
        dataDir: File,
        headerChain: MutableList<Header>,
        maxInMemoryHeaders: Int
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val headersFile = File(dataDir, Config.HEADERS_FILE)
        val incrementalFile = File(dataDir, "${Config.HEADERS_FILE}.incremental")
        val metadataFile = File(dataDir, "${Config.HEADERS_FILE}.meta")
        
        // Load from main file (now saves only latest 150 headers, matching hnsd)
        if (!headersFile.exists()) {
            android.util.Log.d("HNSGo", "HeaderStorage: No existing header chain found")
            return@withContext Pair(0, Config.CHECKPOINT_HEIGHT)
        }
        
        try {
            val data = headersFile.readBytes()
            val cbor = CBORObject.DecodeFromBytes(data)
            
            val headersArray = cbor["headers"]
            if (headersArray != null) {
                val totalCount = headersArray.size()
                val storedHeight = cbor["height"]?.AsInt32()
                
                // MATCHING hnsd: We only save latest 150 headers, so stored height is the actual network height
                // Don't calculate from checkpoint - we don't have all headers from checkpoint anymore
                
                // Use stored height directly (it's the actual network height we synced to)
                val finalHeight = if (storedHeight != null && storedHeight > 0 && storedHeight <= Config.MAX_VALID_HEIGHT) {
                    storedHeight
                } else {
                    // Fallback: if stored height is invalid, calculate from saved headers
                    // This should only happen if file is corrupted
                    val fallbackHeight = if (totalCount > 0) {
                        // We only have 150 headers, so we can't calculate full height
                        // Use a reasonable estimate: assume we're close to network tip
                        android.util.Log.w("HNSGo", "HeaderStorage: Stored height missing or invalid, using fallback calculation")
                        Config.CHECKPOINT_HEIGHT + totalCount - 1
                    } else {
                        0
                    }
                    fallbackHeight
                }
                
                val firstInMemoryHeight = loadHeadersIntoMemory(
                    headersArray, totalCount, headerChain, maxInMemoryHeaders, finalHeight
                )
                
                return@withContext Pair(finalHeight, firstInMemoryHeight)
            }
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "HeaderStorage: Error loading header chain", e)
        }
        
        Pair(0, Config.CHECKPOINT_HEIGHT)
    }
    
    private suspend fun loadHeadersIntoMemory(
        headersArray: CBORObject,
        totalCount: Int,
        headerChain: MutableList<Header>,
        maxInMemoryHeaders: Int,
        chainHeight: Int
    ): Int = withContext(Dispatchers.IO) {
        synchronized(headerChain) {
            headerChain.clear()
        }
        
        // MATCHING hnsd: We only save latest 150 headers, so first header is at (chainHeight - totalCount + 1)
        // Not CHECKPOINT_HEIGHT + startIndex, because we don't store all headers from checkpoint
        val startIndex = maxOf(0, totalCount - maxInMemoryHeaders)
        val firstInMemoryHeight = chainHeight - totalCount + 1 + startIndex
        
        android.util.Log.d(
            "HNSGo",
            "HeaderStorage: Loading from disk (total: $totalCount headers, height: $chainHeight)"
        )
        android.util.Log.d(
            "HNSGo",
            "HeaderStorage: Loading last ${totalCount - startIndex} headers into memory " +
                "(array indices $startIndex to ${totalCount - 1}, heights $firstInMemoryHeight to $chainHeight)"
        )
        
        synchronized(headerChain) {
            for (i in startIndex until totalCount) {
                val headerObj = headersArray[i]
                val headerBytes = headerObj.GetByteString()
                val header = parseHeaderFromBytes(headerBytes)
                headerChain.add(header)
            }
        }
        
        android.util.Log.d(
            "HNSGo",
            "HeaderStorage: Loaded ${headerChain.size} headers into memory " +
                "(heights $firstInMemoryHeight to $chainHeight, matching hnsd: only latest headers saved)"
        )
        
        firstInMemoryHeight
    }
    
    /**
     * Save header chain to disk
     * MEMORY OPTIMIZATION: Only saves new headers (incremental save)
     * Avoids rewriting entire chain on every save - uses append-only file for new headers
     */
    suspend fun saveHeaderChain(
        dataDir: File,
        headerChain: List<Header>,
        chainHeight: Int,
        lastSavedHeight: Int = 0
    ) = withContext(Dispatchers.IO) {
        try {
            val headersFile = File(dataDir, Config.HEADERS_FILE)
            val incrementalFile = File(dataDir, "${Config.HEADERS_FILE}.incremental")
            val tempFile = File(dataDir, "${Config.HEADERS_FILE}.tmp")
            
            val newHeadersCount = chainHeight - lastSavedHeight
            if (newHeadersCount <= 0) {
                android.util.Log.d("HNSGo", "HeaderStorage: No new headers to save (height: $chainHeight, lastSaved: $lastSavedHeight)")
                return@withContext
            }
            
            // MATCHING hnsd: Only save the latest 150 headers (HSK_STORE_HEADERS_COUNT)
            // This prevents OOM and matches hnsd's behavior - SPV clients don't need all historical headers
            synchronized(headerChain) {
                if (headerChain.isEmpty()) {
                    android.util.Log.w("HNSGo", "HeaderStorage: No headers in memory to save")
                    return@withContext
                }
                
                // Save only the latest 150 headers (matching hnsd's HSK_STORE_HEADERS_COUNT)
                val headersToSave = headerChain.takeLast(Config.CHECKPOINT_HEADERS_COUNT)
                
                if (headersToSave.isEmpty()) {
                    android.util.Log.w("HNSGo", "HeaderStorage: No headers to save after filtering")
                    return@withContext
                }
                
                // Create new array with only latest 150 headers (don't append - replace)
                val headersArray = CBORObject.NewArray()
                for (header in headersToSave) {
                    headersArray.Add(CBORObject.FromObject(header.toBytes()))
                }
                
                val cbor = CBORObject.NewMap()
                cbor["headers"] = headersArray
                cbor["height"] = CBORObject.FromObject(chainHeight)
                cbor["timestamp"] = CBORObject.FromObject(System.currentTimeMillis())
                
                val data = cbor.EncodeToBytes()
                tempFile.writeBytes(data)
                tempFile.renameTo(headersFile)
                
                val checksum = sha256.digest(data)
                sha256.reset()
                File(dataDir, Config.HEADERS_CHECKSUM).writeBytes(checksum)
                
                android.util.Log.d("HNSGo", "HeaderStorage: Saved ${headersToSave.size} latest headers (matching hnsd: only latest ${Config.CHECKPOINT_HEADERS_COUNT} headers, height: $chainHeight)")
                
                // Clean up old incremental file if it exists (no longer needed)
                if (incrementalFile.exists()) {
                    incrementalFile.delete()
                    android.util.Log.d("HNSGo", "HeaderStorage: Cleaned up old incremental file")
                }
            }
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("HNSGo", "HeaderStorage: OutOfMemoryError saving headers - skipping save", e)
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "HeaderStorage: Error saving header chain", e)
        }
    }
    
    /**
     * Get header at specific height
     * Loads from memory if available, otherwise reads from disk
     */
    suspend fun getHeaderAtHeight(
        dataDir: File,
        headerChain: List<Header>,
        height: Int,
        firstInMemoryHeight: Int,
        chainHeight: Int
    ): Header? = withContext(Dispatchers.IO) {
        if (height >= firstInMemoryHeight && height <= chainHeight) {
            val index = height - firstInMemoryHeight
            synchronized(headerChain) {
                if (index >= 0 && index < headerChain.size) {
                    return@withContext headerChain[index]
                }
            }
        }
        
        loadHeaderFromDisk(dataDir, height)
    }
    
    private suspend fun loadHeaderFromDisk(dataDir: File, height: Int): Header? = withContext(Dispatchers.IO) {
        val headersFile = File(dataDir, Config.HEADERS_FILE)
        if (!headersFile.exists()) {
            return@withContext null
        }
        
        try {
            val data = headersFile.readBytes()
            val cbor = CBORObject.DecodeFromBytes(data)
            val headersArray = cbor["headers"]
            
            if (headersArray != null) {
                val index = height - Config.CHECKPOINT_HEIGHT
                if (index >= 0 && index < headersArray.size()) {
                    val headerObj = headersArray[index]
                    val headerBytes = headerObj.GetByteString()
                    return@withContext parseHeaderFromBytes(headerBytes)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "HeaderStorage: Error loading header at height $height from disk", e)
        }
        
        null
    }
    
    /**
     * Parse header from stored bytes (hnsd wire format - 236 bytes)
     */
    fun parseHeaderFromBytes(bytes: ByteArray): Header {
        require(bytes.size == 236) { 
            "Header must be 236 bytes (hnsd wire format), got ${bytes.size} bytes" 
        }
        
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
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
}

