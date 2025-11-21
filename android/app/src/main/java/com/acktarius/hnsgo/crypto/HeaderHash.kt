package com.acktarius.hnsgo.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.digests.SHA3Digest
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handshake header hash calculation
 * 
 * Implements hnsd's header hash algorithm (Blake2B + SHA3 + XOR)
 * Based on hnsd/src/header.c:hsk_header_cache
 * 
 * Algorithm:
 * 1. Generate padding (pad8, pad32) using hsk_header_padding
 * 2. Generate pre-hash structure using hsk_header_pre_encode
 * 3. Blake2B-512(pre) -> left[64]
 * 4. SHA3-256(pre + pad8) -> right[32]
 * 5. Blake2B-256(left + pad32 + right) -> hash[32]
 * 6. XOR hash with mask[32]
 */
object HeaderHash {
    /**
     * Calculate header hash using hnsd's algorithm
     * 
     * @param header The header data to hash
     * @return 32-byte hash
     */
    fun hash(header: HeaderData): ByteArray {
        // EXACT MATCH to hsk_header_cache in header.c:370-414
        // Step 1: Generate padding (pad8 and pad32) - MUST be generated separately!
        // hnsd calls hsk_header_padding twice: once for pad8, once for pad32
        // They are NOT the same - pad8 is 8 bytes, pad32 is 32 bytes
        val pad8 = generatePadding(header, 8)
        val pad32 = generatePadding(header, 32)
        
        // Step 2: Generate commit_hash (uses sub_hash + mask_hash)
        val commitHash = generateCommitHash(header)
        
        // Step 3: Generate pre-hash structure
        // hnsd: hsk_header_pre_encode(hdr, pre)
        // Format: nonce(4) + time(8) + pad20(20) + prev_block(32) + name_root(32) + commit_hash(32) = 128 bytes
        // Note: pad20 is generated separately (not from pad32!)
        val pad20 = generatePadding(header, 20)
        val pre = generatePreHash(header, pad20, commitHash)
        
        // Verify pre size matches hnsd
        val preSize = 4 + 8 + 20 + 32 + 32 + 32  // 128 bytes
        if (pre.size != preSize) {
            throw IllegalStateException("Pre-hash size mismatch: expected $preSize, got ${pre.size}")
        }
        
        // Step 4: Blake2B-512(pre) -> left[64]
        // hnsd: hsk_hash_blake512(pre, size, left)
        val left = ByteArray(64)
        val blake2b512 = Blake2bDigest(512)
        blake2b512.update(pre, 0, pre.size)
        blake2b512.doFinal(left, 0)
        
        // Step 5: SHA3-256(pre + pad8) -> right[32]
        // hnsd: SHA3-256(pre, size) + pad8(8) -> right
        val right = ByteArray(32)
        val sha3 = SHA3Digest(256)
        sha3.update(pre, 0, pre.size)
        sha3.update(pad8, 0, pad8.size)
        sha3.doFinal(right, 0)
        
        // Step 6: Blake2B-256(left + pad32 + right) -> hash[32]
        // hnsd: Blake2B-256(left[64] + pad32[32] + right[32]) -> hash[32]
        val hash = ByteArray(32)
        val blake2b256 = Blake2bDigest(256)
        blake2b256.update(left, 0, left.size)
        blake2b256.update(pad32, 0, pad32.size)
        blake2b256.update(right, 0, right.size)
        blake2b256.doFinal(hash, 0)
        
        // Step 7: XOR hash with mask
        // hnsd: hash[i] ^= mask[i] for i in 0..31
        for (i in hash.indices) {
            hash[i] = (hash[i].toInt() xor header.mask[i].toInt()).toByte()
        }
        
        return hash
    }
    
    /**
     * Generate padding: pad[i] = prev_block[i % 32] ^ name_root[i % 32]
     * Based on hsk_header_padding in header.c:356-363
     */
    private fun generatePadding(header: HeaderData, size: Int): ByteArray {
        val pad = ByteArray(size)
        for (i in 0 until size) {
            pad[i] = (header.prevBlock[i % 32].toInt() xor header.nameRoot[i % 32].toInt()).toByte()
        }
        return pad
    }
    
    /**
     * Generate commit_hash = Blake2B-256(sub_hash + mask_hash)
     * Based on hsk_header_commit_hash in header.c:341-353
     */
    private fun generateCommitHash(header: HeaderData): ByteArray {
        // Generate sub_hash = Blake2B-256(sub_header)
        val subHash = generateSubHash(header)
        
        // Generate mask_hash = Blake2B-256(prev_block + mask)
        val maskHash = generateMaskHash(header)
        
        // Generate commit_hash = Blake2B-256(sub_hash + mask_hash)
        val commitHash = ByteArray(32)
        val blake2b = Blake2bDigest(256)
        blake2b.update(subHash, 0, subHash.size)
        blake2b.update(maskHash, 0, maskHash.size)
        blake2b.doFinal(commitHash, 0)
        
        return commitHash
    }
    
    /**
     * Generate sub_hash = Blake2B-256(sub_header)
     * Based on hsk_header_sub_hash in header.c:324-329
     * Sub-header: extra_nonce(24) + reserved_root(32) + witness_root(32) + merkle_root(32) + version(4) + bits(4)
     */
    private fun generateSubHash(header: HeaderData): ByteArray {
        val subSize = 24 + 32 + 32 + 32 + 4 + 4  // 128 bytes
        val sub = ByteArray(subSize)
        val buffer = ByteBuffer.wrap(sub).order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put(header.extraNonce)
        buffer.put(header.reservedRoot)
        buffer.put(header.witnessRoot)
        buffer.put(header.merkleRoot)
        buffer.putInt(header.version)
        buffer.putInt(header.bits)
        
        val subHash = ByteArray(32)
        val blake2b = Blake2bDigest(256)
        blake2b.update(sub, 0, sub.size)
        blake2b.doFinal(subHash, 0)
        
        return subHash
    }
    
    /**
     * Generate mask_hash = Blake2B-256(prev_block + mask)
     * Based on hsk_header_mask_hash in header.c:332-338
     */
    private fun generateMaskHash(header: HeaderData): ByteArray {
        val maskHash = ByteArray(32)
        val blake2b = Blake2bDigest(256)
        blake2b.update(header.prevBlock, 0, header.prevBlock.size)
        blake2b.update(header.mask, 0, header.mask.size)
        blake2b.doFinal(maskHash, 0)
        return maskHash
    }
    
    /**
     * Generate pre-hash structure
     * Based on hsk_header_pre_encode in header.c:274-289
     * Format: nonce(4) + time(8) + pad20(20) + prev_block(32) + name_root(32) + commit_hash(32) = 128 bytes
     */
    private fun generatePreHash(header: HeaderData, pad20: ByteArray, commitHash: ByteArray): ByteArray {
        val preSize = 4 + 8 + 20 + 32 + 32 + 32  // 128 bytes
        val pre = ByteArray(preSize)
        val buffer = ByteBuffer.wrap(pre).order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.putInt(header.nonce)
        buffer.putLong(header.time)
        buffer.put(pad20)
        buffer.put(header.prevBlock)
        buffer.put(header.nameRoot)
        buffer.put(commitHash)
        
        return pre
    }
    
    /**
     * Header data structure matching hsk_header_t
     */
    data class HeaderData(
        val nonce: Int,
        val time: Long,
        val prevBlock: ByteArray,
        val nameRoot: ByteArray,
        val extraNonce: ByteArray, // 24 bytes
        val reservedRoot: ByteArray,
        val witnessRoot: ByteArray,
        val merkleRoot: ByteArray,
        val version: Int,
        val bits: Int,
        val mask: ByteArray
    )
}
