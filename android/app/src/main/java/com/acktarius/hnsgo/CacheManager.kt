package com.acktarius.hnsgo

import java.util.concurrent.ConcurrentHashMap

/**
 * Cached DNS record with block-based access tracking
 * 
 * @param data DNS response wire format
 * @param expiryTimestamp When TTL expires (wall-clock time in milliseconds)
 * @param accessCount36Blocks Count of accesses in current 36-block window
 * @param lastCountResetHeight Block height when counter was last reset
 * @param lastAccessHeight Block height of last access
 */
data class CachedRecord(
    val data: ByteArray,
    val expiryTimestamp: Long,  // When TTL expires (wall-clock time)
    var accessCount36Blocks: Int = 0,  // Count in current 36-block window
    var lastCountResetHeight: Int = 0,  // Block height when counter was last reset
    var lastAccessHeight: Int = 0  // Block height of last access
)

object CacheManager {
    private val cache = ConcurrentHashMap<String, CachedRecord>()

    /**
     * Get cached DNS response using (qname, qtype, qclass) as key
     * Tracks access count per 36-block window (no lazy expiration)
     * 
     * @param name Domain name (qname)
     * @param type DNS record type (qtype)
     * @param dclass DNS class (qclass, usually DClass.IN)
     * @param currentHeight Current blockchain height (for access tracking)
     * @return Cached data or null if not found
     */
    fun get(name: String, type: Int, dclass: Int, currentHeight: Int): ByteArray? {
        val key = "$name|$type|$dclass"
        val rec = cache[key] ?: return null
        
        // Check if we've crossed a 36-block boundary since last reset
        val blocksSinceReset = currentHeight - rec.lastCountResetHeight
        if (blocksSinceReset >= Config.TREE_INTERVAL) {
            // Reset counter for new 36-block window
            rec.accessCount36Blocks = 0
            rec.lastCountResetHeight = currentHeight
        }
        
        // Increment access count and update last access height
        rec.accessCount36Blocks++
        rec.lastAccessHeight = currentHeight
        
        return rec.data
    }

    /**
     * Cache DNS response using (qname, qtype, qclass) as key
     * 
     * @param name Domain name (qname)
     * @param type DNS record type (qtype)
     * @param dclass DNS class (qclass, usually DClass.IN)
     * @param data DNS response wire format
     * @param ttlSec TTL in seconds
     * @param currentHeight Current blockchain height (for access tracking initialization)
     */
    fun put(name: String, type: Int, dclass: Int, data: ByteArray, ttlSec: Int, currentHeight: Int) {
        val key = "$name|$type|$dclass"
        cache[key] = CachedRecord(
            data = data,
            expiryTimestamp = System.currentTimeMillis() + ttlSec * 1000L,
            accessCount36Blocks = 0,  // Will be incremented on first access
            lastCountResetHeight = currentHeight,
            lastAccessHeight = currentHeight
        )
    }

    /**
     * Remove cached DNS response using (qname, qtype, qclass) as key
     * @param name Domain name (qname)
     * @param type DNS record type (qtype)
     * @param dclass DNS class (qclass, usually DClass.IN)
     */
    fun remove(name: String, type: Int, dclass: Int) {
        val key = "$name|$type|$dclass"
        cache.remove(key)
    }

    /**
     * Cleanup expired cache entries based on 36-block interval policy
     * Runs every 36 blocks:
     * - Expired entries with accessCount36Blocks > 2: prefetch (refresh in background)
     * - Expired entries with accessCount36Blocks <= 2: remove
     * - Non-expired entries: do nothing
     * 
     * @param currentHeight Current blockchain height
     * @param prefetchCallback Function to call for prefetching popular expired entries
     *                         Parameters: (name: String, type: Int, dclass: Int)
     *                         Should resolve the name to refresh the cache
     */
    suspend fun cleanupExpiredEntries(
        currentHeight: Int,
        prefetchCallback: suspend (String, Int, Int) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val keysToRemove = mutableListOf<String>()
        val keysToPrefetch = mutableListOf<Triple<String, Int, Int>>()
        
        // Process all cache entries
        cache.forEach { (key, record) ->
            val isExpired = now > record.expiryTimestamp
            
            if (isExpired) {
                // Check if we've crossed a 36-block boundary since last reset
                val blocksSinceReset = currentHeight - record.lastCountResetHeight
                if (blocksSinceReset >= Config.TREE_INTERVAL) {
                    // Reset counter for new window before evaluation
                    record.accessCount36Blocks = 0
                    record.lastCountResetHeight = currentHeight
                }
                
                // Evaluate based on access count in current 36-block window
                if (record.accessCount36Blocks > 2) {
                    // Popular expired entry - prefetch to refresh
                    val parts = key.split("|")
                    if (parts.size == 3) {
                        keysToPrefetch.add(Triple(parts[0], parts[1].toInt(), parts[2].toInt()))
                    }
                } else {
                    // Unpopular expired entry - remove
                    keysToRemove.add(key)
                }
            }
            // Non-expired entries: do nothing (keep in cache)
        }
        
        // Remove unpopular expired entries
        keysToRemove.forEach { key ->
            cache.remove(key)
        }
        
        // Prefetch popular expired entries (in background)
        if (keysToPrefetch.isNotEmpty()) {
            keysToPrefetch.forEach { (name, type, dclass) ->
                try {
                    prefetchCallback(name, type, dclass)
                } catch (e: Exception) {
                }
            }
        }
        
        if (keysToRemove.isNotEmpty() || keysToPrefetch.isNotEmpty()) {
        }
    }
    
    /**
     * Get all cache keys (for debugging/monitoring)
     */
    fun getAllKeys(): Set<String> = cache.keys.toSet()
    
    /**
     * Get cache size (for debugging/monitoring)
     */
    fun size(): Int = cache.size
}
