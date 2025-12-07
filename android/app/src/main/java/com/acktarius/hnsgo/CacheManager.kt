package com.acktarius.hnsgo

import java.util.concurrent.ConcurrentHashMap

data class CachedRecord(val data: ByteArray, val ttl: Long)

object CacheManager {
    private val cache = ConcurrentHashMap<String, CachedRecord>()

    /**
     * Get cached DNS response using (qname, qtype, qclass) as key
     * @param name Domain name (qname)
     * @param type DNS record type (qtype)
     * @param dclass DNS class (qclass, usually DClass.IN)
     */
    fun get(name: String, type: Int, dclass: Int): ByteArray? {
        val key = "$name|$type|$dclass"
        val rec = cache[key] ?: return null
        if (System.currentTimeMillis() > rec.ttl) {
            cache.remove(key)
            return null
        }
        return rec.data
    }

    /**
     * Cache DNS response using (qname, qtype, qclass) as key
     * @param name Domain name (qname)
     * @param type DNS record type (qtype)
     * @param dclass DNS class (qclass, usually DClass.IN)
     * @param data DNS response wire format
     * @param ttlSec TTL in seconds
     */
    fun put(name: String, type: Int, dclass: Int, data: ByteArray, ttlSec: Int) {
        val key = "$name|$type|$dclass"
        cache[key] = CachedRecord(data, System.currentTimeMillis() + ttlSec * 1000L)
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
}
