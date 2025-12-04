package com.acktarius.hnsgo.util

/**
 * Memory-efficient hex string utilities
 * Avoids creating temporary strings for logging
 */
object HexUtils {
    private const val HEX_CHARS = "0123456789abcdef"
    
    /**
     * Convert ByteArray to hex string (memory-efficient)
     * Only creates string when needed (lazy evaluation)
     */
    fun toHex(bytes: ByteArray, maxLength: Int = Int.MAX_VALUE): String {
        val len = minOf(bytes.size, maxLength)
        val sb = StringBuilder(len * 2)
        for (i in 0 until len) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX_CHARS[v shr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }
    
    /**
     * Convert ByteArray to hex string with ellipsis for long arrays
     * Optimized for logging (only shows first N bytes)
     */
    fun toHexShort(bytes: ByteArray, maxBytes: Int = 16): String {
        if (bytes.size <= maxBytes) {
            return toHex(bytes)
        }
        return "${toHex(bytes, maxBytes)}..."
    }
}

