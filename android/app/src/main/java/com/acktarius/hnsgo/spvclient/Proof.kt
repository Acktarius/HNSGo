package com.acktarius.hnsgo.spvclient

import android.util.Log
import com.acktarius.hnsgo.Config
import org.bouncycastle.crypto.digests.Blake2bDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handshake Merkle proof verification (matching hnsd's proof-radix.c)
 * Implements radix tree proof parsing and verification
 */
object Proof {
    // Proof types (from hnsd proof-radix.h)
    private const val HSK_PROOF_DEADEND = 0
    private const val HSK_PROOF_SHORT = 1
    private const val HSK_PROOF_COLLISION = 2
    private const val HSK_PROOF_EXISTS = 3
    private const val HSK_PROOF_UNKNOWN = 4
    
    // Proof hash prefixes (from hnsd proof-radix.c)
    private val PROOF_SKIP = byteArrayOf(0x02)
    private val PROOF_INTERNAL = byteArrayOf(0x01)
    private val PROOF_LEAF = byteArrayOf(0x00)
    
    // Error codes (matching hnsd error.h)
    private const val HSK_EPROOFOK = 0
    private const val HSK_EBADARGS = -1
    private const val HSK_EHASHMISMATCH = -2
    private const val HSK_EENCODING = -3
    private const val HSK_ESAMEKEY = -4
    private const val HSK_ESAMEPATH = -5
    private const val HSK_ENEGDEPTH = -6
    private const val HSK_EPATHMISMATCH = -7
    private const val HSK_ETOODEEP = -8
    
    /**
     * Proof node structure (matching hsk_proof_node_t)
     */
    data class ProofNode(
        val prefix: ByteArray,
        val prefixSize: Int,  // in bits
        val node: ByteArray   // 32 bytes
    )
    
    /**
     * Parsed proof structure (matching hsk_proof_t)
     */
    data class ParsedProof(
        val type: Int,
        val depth: Int,
        val nodes: List<ProofNode>,
        val prefix: ByteArray? = null,      // For HSK_PROOF_SHORT
        val prefixSize: Int = 0,
        val left: ByteArray? = null,        // For HSK_PROOF_SHORT
        val right: ByteArray? = null,       // For HSK_PROOF_SHORT
        val nxKey: ByteArray? = null,       // For HSK_PROOF_COLLISION
        val nxHash: ByteArray? = null,      // For HSK_PROOF_COLLISION
        val value: ByteArray? = null,       // For HSK_PROOF_EXISTS
        val valueSize: Int = 0
    )
    
    /**
     * Verification result
     */
    data class VerifyResult(
        val success: Boolean,
        val exists: Boolean,
        val data: ByteArray? = null,
        val errorCode: Int = HSK_EPROOFOK
    )
    
    /**
     * Parse proof from binary data (matching hsk_proof_read)
     */
    fun parseProof(data: ByteArray): ParsedProof? {
        if (data.isEmpty()) {
            return null
        }
        
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        
        try {
            // Read field: type (2 bits) + depth (14 bits)
            if (buffer.remaining() < 2) return null
            val field = buffer.short.toInt() and 0xFFFF
            val type = field shr 14
            val depth = field and 0x3FFF  // ~(3 << 14)
            
            if (depth > 256) {
                Log.w("HNSGo", "Proof: Invalid depth: $depth")
                return null
            }
            
            // Read node count
            if (buffer.remaining() < 2) return null
            val count = buffer.short.toInt() and 0xFFFF
            
            if (count > 256) {
                Log.w("HNSGo", "Proof: Invalid node count: $count")
                return null
            }
            
            // Read bitmask for nodes with prefixes
            val mapSize = (count + 7) / 8
            if (buffer.remaining() < mapSize) return null
            val map = ByteArray(mapSize)
            buffer.get(map)
            
            // Read nodes
            val nodes = mutableListOf<ProofNode>()
            for (i in 0 until count) {
                val hasPrefix = hasBit(map, i)
                
                var prefixSize = 0
                var prefixBytes = 0
                var prefix: ByteArray? = null
                
                if (hasPrefix) {
                    // Read prefix length (variable-length encoding)
                    if (buffer.remaining() < 1) return null
                    var size = buffer.get().toInt() and 0xFF
                    
                    if (size and 0x80 != 0) {
                        // Extended length
                        size = size and 0x7F
                        if (buffer.remaining() < 1) return null
                        size = (size shl 8) or (buffer.get().toInt() and 0xFF)
                    }
                    
                    if (size == 0 || size > 256) {
                        Log.w("HNSGo", "Proof: Invalid prefix size: $size")
                        return null
                    }
                    
                    prefixSize = size
                    prefixBytes = (size + 7) / 8
                    
                    if (buffer.remaining() < prefixBytes) return null
                    prefix = ByteArray(prefixBytes)
                    buffer.get(prefix)
                }
                
                // Read node hash (32 bytes)
                if (buffer.remaining() < 32) return null
                val node = ByteArray(32)
                buffer.get(node)
                
                nodes.add(ProofNode(
                    prefix = prefix ?: ByteArray(0),
                    prefixSize = prefixSize,
                    node = node
                ))
            }
            
            // Read type-specific data
            var shortPrefix: ByteArray? = null
            var shortPrefixSize = 0
            var left: ByteArray? = null
            var right: ByteArray? = null
            var nxKey: ByteArray? = null
            var nxHash: ByteArray? = null
            var value: ByteArray? = null
            var valueSize = 0
            
            when (type) {
                HSK_PROOF_DEADEND -> {
                    // No additional data
                }
                HSK_PROOF_SHORT -> {
                    // Read prefix length
                    if (buffer.remaining() < 1) return null
                    var size = buffer.get().toInt() and 0xFF
                    
                    if (size and 0x80 != 0) {
                        size = size and 0x7F
                        if (buffer.remaining() < 1) return null
                        size = (size shl 8) or (buffer.get().toInt() and 0xFF)
                    }
                    
                    if (size == 0 || size > 256) return null
                    
                    shortPrefixSize = size
                    val prefixBytes = (size + 7) / 8
                    
                    if (buffer.remaining() < prefixBytes) return null
                    shortPrefix = ByteArray(prefixBytes)
                    buffer.get(shortPrefix)
                    
                    // Read left and right hashes
                    if (buffer.remaining() < 64) return null
                    left = ByteArray(32)
                    right = ByteArray(32)
                    buffer.get(left)
                    buffer.get(right)
                }
                HSK_PROOF_COLLISION -> {
                    // Read next key and hash
                    if (buffer.remaining() < 64) return null
                    nxKey = ByteArray(32)
                    nxHash = ByteArray(32)
                    buffer.get(nxKey)
                    buffer.get(nxHash)
                }
                HSK_PROOF_EXISTS -> {
                    // Read value size
                    if (buffer.remaining() < 2) return null
                    valueSize = buffer.short.toInt() and 0xFFFF
                    
                    if (valueSize > Config.MAX_DATA_SIZE) {
                        Log.w("HNSGo", "Proof: Value size too large: $valueSize")
                        return null
                    }
                    
                    if (buffer.remaining() < valueSize) return null
                    value = ByteArray(valueSize)
                    buffer.get(value)
                }
                else -> {
                    Log.w("HNSGo", "Proof: Unknown proof type: $type")
                    return null
                }
            }
            
            return ParsedProof(
                type = type,
                depth = depth,
                nodes = nodes,
                prefix = shortPrefix,
                prefixSize = shortPrefixSize,
                left = left,
                right = right,
                nxKey = nxKey,
                nxHash = nxHash,
                value = value,
                valueSize = valueSize
            )
        } catch (e: Exception) {
            Log.e("HNSGo", "Proof: Error parsing proof", e)
            return null
        }
    }
    
    /**
     * Verify proof against root (matching hsk_proof_verify)
     */
    fun verifyProof(
        root: ByteArray,
        key: ByteArray,
        proof: ParsedProof
    ): VerifyResult {
        if (root.size != 32 || key.size != 32) {
            return VerifyResult(false, false, errorCode = HSK_EBADARGS)
        }
        
        if (proof.depth > 256 || proof.nodes.size > 256) {
            return VerifyResult(false, false, errorCode = HSK_EBADARGS)
        }
        
        // Re-create the leaf
        val leaf = ByteArray(32)
        
        when (proof.type) {
            HSK_PROOF_DEADEND -> {
                // Empty leaf (all zeros)
                leaf.fill(0)
            }
            HSK_PROOF_SHORT -> {
                if (proof.prefix == null || proof.left == null || proof.right == null) {
                    return VerifyResult(false, false, errorCode = HSK_EBADARGS)
                }
                
                if (proofHasPrefix(proof.prefix, proof.prefixSize, key, proof.depth)) {
                    return VerifyResult(false, false, errorCode = HSK_ESAMEPATH)
                }
                
                hashInternal(proof.prefix, proof.prefixSize, proof.left, proof.right, leaf)
            }
            HSK_PROOF_COLLISION -> {
                if (proof.nxKey == null || proof.nxHash == null) {
                    return VerifyResult(false, false, errorCode = HSK_EBADARGS)
                }
                
                if (proof.nxKey.contentEquals(key)) {
                    return VerifyResult(false, false, errorCode = HSK_ESAMEKEY)
                }
                
                hashLeaf(proof.nxKey, proof.nxHash, leaf)
            }
            HSK_PROOF_EXISTS -> {
                if (proof.value == null) {
                    return VerifyResult(false, false, errorCode = HSK_EBADARGS)
                }
                
                hashValue(key, proof.value, proof.valueSize, leaf)
            }
            else -> {
                return VerifyResult(false, false, errorCode = HSK_EBADARGS)
            }
        }
        
        // Traverse tree from leaf to root
        var next = leaf
        var currentDepth = proof.depth
        
        // Traverse nodes right to left (matching hnsd)
        for (i in proof.nodes.size - 1 downTo 0) {
            val node = proof.nodes[i]
            
            if (currentDepth < node.prefixSize + 1) {
                return VerifyResult(false, false, errorCode = HSK_ENEGDEPTH)
            }
            
            currentDepth -= 1
            
            // Hash based on key bit
            val result = ByteArray(32)
            if (hasBit(key, currentDepth)) {
                hashInternal(node.prefix, node.prefixSize, node.node, next, result)
            } else {
                hashInternal(node.prefix, node.prefixSize, next, node.node, result)
            }
            next = result
            
            currentDepth -= node.prefixSize
            
            // Verify prefix matches key path
            if (!proofHasPrefix(node.prefix, node.prefixSize, key, currentDepth)) {
                return VerifyResult(false, false, errorCode = HSK_EPATHMISMATCH)
            }
        }
        
        if (currentDepth != 0) {
            return VerifyResult(false, false, errorCode = HSK_ETOODEEP)
        }
        
        // Verify final hash matches root
        if (!next.contentEquals(root)) {
            Log.w("HNSGo", "Proof: Hash mismatch - calculated: ${next.take(16).joinToString("") { "%02x".format(it) }}..., expected: ${root.take(16).joinToString("") { "%02x".format(it) }}...")
            return VerifyResult(false, false, errorCode = HSK_EHASHMISMATCH)
        }
        
        // Extract data if name exists
        if (proof.type == HSK_PROOF_EXISTS && proof.value != null) {
            val parsedData = parseNameState(proof.value, proof.valueSize)
            if (parsedData == null) {
                return VerifyResult(false, false, errorCode = HSK_EENCODING)
            }
            return VerifyResult(true, true, data = parsedData, errorCode = HSK_EPROOFOK)
        } else {
            return VerifyResult(true, false, errorCode = HSK_EPROOFOK)
        }
    }
    
    /**
     * Hash internal node (matching hsk_proof_hash_internal)
     * Blake2B(skip/internal + prefix_info + left + right)
     */
    private fun hashInternal(
        prefix: ByteArray,
        prefixSize: Int,
        left: ByteArray,
        right: ByteArray,
        out: ByteArray
    ) {
        val blake2b = Blake2bDigest(256)
        
        if (prefixSize == 0) {
            // Standard internal node
            blake2b.update(PROOF_INTERNAL, 0, 1)
            blake2b.update(left, 0, 32)
            blake2b.update(right, 0, 32)
        } else {
            // Skip node (with prefix)
            val prefixBytes = (prefixSize + 7) / 8
            val sizeBytes = ByteArray(2).apply {
                val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putShort(prefixSize.toShort())
            }
            
            blake2b.update(PROOF_SKIP, 0, 1)
            blake2b.update(sizeBytes, 0, 2)
            blake2b.update(prefix, 0, prefixBytes)
            blake2b.update(left, 0, 32)
            blake2b.update(right, 0, 32)
        }
        
        blake2b.doFinal(out, 0)
    }
    
    /**
     * Hash leaf node (matching hsk_proof_hash_leaf)
     * Blake2B(0x00 + key + hash)
     */
    private fun hashLeaf(key: ByteArray, hash: ByteArray, out: ByteArray) {
        val blake2b = Blake2bDigest(256)
        blake2b.update(PROOF_LEAF, 0, 1)
        blake2b.update(key, 0, 32)
        blake2b.update(hash, 0, 32)
        blake2b.doFinal(out, 0)
    }
    
    /**
     * Hash value (matching hsk_proof_hash_value)
     * Blake2B(value) then hash_leaf(key, hash)
     */
    private fun hashValue(key: ByteArray, value: ByteArray, valueSize: Int, out: ByteArray) {
        // First hash the value
        val blake2b = Blake2bDigest(256)
        blake2b.update(value, 0, valueSize)
        val valueHash = ByteArray(32)
        blake2b.doFinal(valueHash, 0)
        
        // Then hash as leaf
        hashLeaf(key, valueHash, out)
    }
    
    /**
     * Check if prefix matches key at given depth (matching hsk_proof_has)
     */
    private fun proofHasPrefix(prefix: ByteArray, prefixSize: Int, key: ByteArray, depth: Int): Boolean {
        val prefixBytes = (prefixSize + 7) / 8
        val maxLen = minOf(prefixSize, 256 - depth)
        
        for (i in 0 until maxLen) {
            val prefixBit = hasBit(prefix, i)
            val keyBit = hasBit(key, depth + i)
            if (prefixBit != keyBit) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Check if bit is set in byte array (matching HSK_HAS_BIT macro)
     * Bits are numbered left to right (MSB first)
     */
    private fun hasBit(data: ByteArray, bitIndex: Int): Boolean {
        val byteIndex = bitIndex shr 3
        val bitInByte = 7 - (bitIndex and 7)
        if (byteIndex >= data.size) return false
        return ((data[byteIndex].toInt() shr bitInByte) and 1) != 0
    }
    
    /**
     * Parse name state from proof value (matching hsk_parse_namestate)
     * Format: name_size (1 byte) + name (name_size bytes) + res_size (2 bytes) + resource_data (res_size bytes)
     */
    private fun parseNameState(data: ByteArray, dataLen: Int): ByteArray? {
        if (dataLen > Config.MAX_DATA_SIZE) {
            return null
        }
        
        val buffer = ByteBuffer.wrap(data, 0, dataLen).order(ByteOrder.LITTLE_ENDIAN)
        
        try {
            // Read name size
            if (buffer.remaining() < 1) return null
            val nameSize = buffer.get().toInt() and 0xFF
            
            // Read name
            if (buffer.remaining() < nameSize) return null
            val name = ByteArray(nameSize)
            buffer.get(name)
            
            // Read resource size
            if (buffer.remaining() < 2) return null
            val resSize = buffer.short.toInt() and 0xFFFF
            
            // Read resource data
            if (buffer.remaining() < resSize) return null
            val resData = ByteArray(resSize)
            buffer.get(resData)
            
            return resData
        } catch (e: Exception) {
            Log.e("HNSGo", "Proof: Error parsing name state", e)
            return null
        }
    }
}

