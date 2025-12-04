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
                
                // CRITICAL: For checkpoint-based chains, height should be CHECKPOINT_HEIGHT + totalCount - 1
                val expectedHeight = if (totalCount > 0) {
                    Config.CHECKPOINT_HEIGHT + totalCount - 1
                } else {
                    0
                }
                
                val maxValidHeight = 500000
                val maxReasonableDiffBase = 1000
                
                // Use stored height if valid, otherwise use calculated height from checkpoint
                val finalHeight = if (storedHeight != null && storedHeight > 0 && storedHeight <= maxValidHeight) {
                    val heightDiff = kotlin.math.abs(storedHeight - expectedHeight)
                    val maxReasonableDiff = maxOf(maxReasonableDiffBase, expectedHeight / 10)
                    
                    if (heightDiff > maxReasonableDiff) {
                        android.util.Log.w("HNSGo", "HeaderStorage: Stored height $storedHeight differs significantly from expected $expectedHeight (diff: $heightDiff), using calculated height")
                        expectedHeight
                    } else {
                        storedHeight
                    }
                } else {
                    android.util.Log.w("HNSGo", "HeaderStorage: Stored height missing or invalid, calculating from checkpoint: $expectedHeight")
                    expectedHeight
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
        
        val startIndex = maxOf(0, totalCount - maxInMemoryHeaders)
        val firstInMemoryHeight = Config.CHECKPOINT_HEIGHT + startIndex
        
        android.util.Log.d(
            "HNSGo",
            "HeaderStorage: Loading from disk (total: $totalCount, height: $chainHeight)"
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
                "(heights $firstInMemoryHeight to $chainHeight)"
        )
        if (firstInMemoryHeight > Config.CHECKPOINT_HEIGHT) {
            android.util.Log.d(
                "HNSGo",
                "HeaderStorage: Older headers (${Config.CHECKPOINT_HEIGHT} to ${firstInMemoryHeight - 1}) remain on disk"
            )
        }
        
        firstInMemoryHeight
    }
    
    /**
     * Save header chain to disk
     * MEMORY OPTIMIZATION: Only saves new headers (incremental save)
     * Avoids rewriting entire chain on every save
     */
    suspend fun saveHeaderChain(
        dataDir: File,
        headerChain: List<Header>,
        chainHeight: Int,
        lastSavedHeight: Int = 0
    ) = withContext(Dispatchers.IO) {
        try {
            val headersFile = File(dataDir, Config.HEADERS_FILE)
            val tempFile = File(dataDir, "${Config.HEADERS_FILE}.tmp")
            
            // OPTIMIZATION: If we have existing file and only new headers, append instead of rewrite
            val newHeadersCount = chainHeight - lastSavedHeight
            val shouldAppend = headersFile.exists() && newHeadersCount > 0 && newHeadersCount < headerChain.size
            
            if (shouldAppend) {
                // Incremental save: Load existing, append new headers
                val existingData = headersFile.readBytes()
                val existingCbor = CBORObject.DecodeFromBytes(existingData)
                val existingHeadersArray = existingCbor["headers"] ?: CBORObject.NewArray()
                
                // Only add new headers (from lastSavedHeight onwards)
                synchronized(headerChain) {
                    val startIndex = maxOf(0, headerChain.size - newHeadersCount)
                    for (i in startIndex until headerChain.size) {
                        existingHeadersArray.Add(CBORObject.FromObject(headerChain[i].toBytes()))
                    }
                }
                
                val cbor = CBORObject.NewMap()
                cbor["headers"] = existingHeadersArray
                cbor["height"] = CBORObject.FromObject(chainHeight)
                cbor["timestamp"] = CBORObject.FromObject(System.currentTimeMillis())
                
                val data = cbor.EncodeToBytes()
                tempFile.writeBytes(data)
                tempFile.renameTo(headersFile)
                
                val checksum = sha256.digest(data)
                sha256.reset()
                File(dataDir, Config.HEADERS_CHECKSUM).writeBytes(checksum)
                
                android.util.Log.d("HNSGo", "HeaderStorage: Incrementally saved $newHeadersCount new headers (total: $chainHeight)")
            } else {
                // Full save: Rewrite entire chain (first save or major changes)
                val headersArray = CBORObject.NewArray()
                
                synchronized(headerChain) {
                    for (header in headerChain) {
                        headersArray.Add(CBORObject.FromObject(header.toBytes()))
                    }
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
                
                android.util.Log.d("HNSGo", "HeaderStorage: Saved $chainHeight headers to disk (full save)")
            }
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
            merkleRoot = nameRoot,
            witnessRoot = witnessRoot,
            treeRoot = merkleRoot,
            reservedRoot = reservedRoot,
            time = time,
            bits = bits,
            nonce = nonce,
            extraNonce = extraNonce,
            mask = mask
        )
    }
}

