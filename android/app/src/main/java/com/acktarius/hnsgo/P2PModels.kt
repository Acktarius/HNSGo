package com.acktarius.hnsgo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handshake blockchain header
 */
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
        // No validation checks - Handshake consensus rules don't require prevBlock/nameRoot to be non-zero
        // Leading zeros in hashes are normal and valid
        val headerData = com.acktarius.hnsgo.crypto.HeaderHash.HeaderData(
            nonce = nonce,
            time = time,
            prevBlock = prevBlock,
            nameRoot = merkleRoot,  // merkleRoot in Header = name_root in hnsd
            extraNonce = extraNonce,
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
        // Match hnsd wire format exactly (236 bytes):
        // nonce(4) + time(8) + prevBlock(32) + nameRoot(32) + extraNonce(24) + 
        // reservedRoot(32) + witnessRoot(32) + merkleRoot(32) + version(4) + bits(4) + mask(32) = 236 bytes
        // Note: merkleRoot in Header = name_root in hnsd, treeRoot in Header = merkle_root in hnsd
        val buffer = ByteBuffer.allocate(236).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(nonce)
        buffer.putLong(time)
        buffer.put(prevBlock)
        buffer.put(merkleRoot)  // name_root in hnsd
        buffer.put(extraNonce)
        buffer.put(reservedRoot)
        buffer.put(witnessRoot)
        buffer.put(treeRoot)  // merkle_root in hnsd
        buffer.putInt(version)
        buffer.putInt(bits)
        buffer.put(mask)  // CRITICAL: Include mask for correct hash calculation!
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

/**
 * P2P message structure
 */
data class P2PMessage(val command: String, val payload: ByteArray)

