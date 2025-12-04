package com.acktarius.hnsgo

import java.util.concurrent.ConcurrentHashMap

data class CachedRecord(val data: ByteArray, val ttl: Long)

object CacheManager {
    private val cache = ConcurrentHashMap<String, CachedRecord>()

    fun get(name: String, type: Int): ByteArray? {
        val key = "$name|$type"
        val rec = cache[key] ?: return null
        if (System.currentTimeMillis() > rec.ttl) {
            cache.remove(key)
            return null
        }
        return rec.data
    }

    fun put(name: String, type: Int, data: ByteArray, ttlSec: Int) {
        val key = "$name|$type"
        cache[key] = CachedRecord(data, System.currentTimeMillis() + ttlSec * 1000L)
    }
}
