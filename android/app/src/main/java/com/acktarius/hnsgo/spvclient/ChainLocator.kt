package com.acktarius.hnsgo.spvclient

import android.util.Log
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.Header

/**
 * Helper functions for building blockchain locator lists
 * Based on hnsd's hsk_chain_get_locator implementation
 */
internal object ChainLocator {
    /**
     * Build locator list from chain tip (like hnsd's hsk_chain_get_locator)
     * Steps back exponentially: tip, tip-1, tip-2, tip-4, tip-8, tip-16, etc.
     * Stops at checkpoint or when we run out of headers
     * 
     * @param headerChain List of headers in order (oldest to newest)
     * @param currentHeight Current blockchain height
     * @param firstInMemoryHeight Height of the first header in headerChain (for trimmed chains)
     * @return List of header hashes for getheaders locator (tip first, then stepping back)
     */
    fun buildLocatorList(headerChain: List<Header>, currentHeight: Int, firstInMemoryHeight: Int? = null): List<ByteArray> {
        val locator = mutableListOf<ByteArray>()
        
        Log.d("HNSGo", "ChainLocator:buildLocatorList: Called with height=$currentHeight, chainSize=${headerChain.size}, firstInMemoryHeight=$firstInMemoryHeight")
        
        if (headerChain.isEmpty() || currentHeight <= 0) {
            Log.e("HNSGo", "ChainLocator:buildLocatorList: No headers! size=${headerChain.size}, height=$currentHeight")
            return locator
        }
        
        val checkpointHeight = Config.CHECKPOINT_HEIGHT
        
        // Calculate the actual first header height in memory
        // If firstInMemoryHeight is provided, use it (for trimmed chains)
        // Otherwise, assume headerChain[0] is at checkpointHeight (for full chains)
        val actualFirstHeight = firstInMemoryHeight ?: checkpointHeight
        
        Log.d("HNSGo", "ChainLocator:buildLocatorList: Using actualFirstHeight=$actualFirstHeight (checkpoint=$checkpointHeight)")
        
        // Headers are stored in order from actualFirstHeight
        // headerChain[0] = height actualFirstHeight
        // headerChain[1] = height actualFirstHeight + 1
        // headerChain[n] = height actualFirstHeight + n
        // So for a given height h, index = h - actualFirstHeight
        
        // EXACT MATCH to hnsd's hsk_chain_get_locator (chain.c:298-332)
        // hnsd code:
        //   int i = 0;
        //   hsk_header_t *tip = chain->tip;
        //   int64_t height = chain->height;
        //   int64_t step = 1;
        //   hsk_header_hash(tip, msg->hashes[i++]);
        //   while (height > 0) {
        //     height -= step;
        //     if (height < 0) height = 0;
        //     if (i > 10) step *= 2;
        //     if (i == sizeof(msg->hashes) - 1) height = 0;
        //     hsk_header_t *hdr = hsk_chain_get_by_height(chain, (uint32_t)height);
        //     if (!hdr) continue;  // Skip missing headers (due to checkpoint)
        //     hsk_header_hash(hdr, msg->hashes[i++]);
        //   }
        //   msg->hash_count = i;
        
        var i = 0
        var height = currentHeight
        var step = 1
        
        // First, always add the tip (matching hnsd line 306: hsk_header_hash(tip, msg->hashes[i++]))
        val tipIndex = height - actualFirstHeight
        if (tipIndex >= 0 && tipIndex < headerChain.size) {
            val tipHeader = headerChain[tipIndex]
            val tipHash = tipHeader.hash()
            locator.add(tipHash)
            i++
            Log.d("HNSGo", "ChainLocator:buildLocatorList: Added tip hash at h=$height idx=$tipIndex (firstInMemory=$actualFirstHeight)")
        } else {
            Log.e("HNSGo", "ChainLocator:buildLocatorList: Tip index OOB h=$height firstInMemory=$actualFirstHeight idx=$tipIndex size=${headerChain.size}")
            return locator // Can't build locator without tip
        }
        
        // Step back exponentially (matching hnsd lines 308-329: while (height > 0))
        // hnsd continues to height 0, but skips missing headers with continue
        // hnsd can have up to 64 hashes (sizeof(msg->hashes) - 1 = 63)
        // We limit to reasonable number to avoid huge locators
        val maxHashes = 64  // Match hnsd's msg.h: hashes[64][32]
        
        while (height > 0 && i < maxHashes) {
            height -= step
            
            if (height < 0) {
                height = 0  // Match hnsd line 311-312
            }
            
            // CRITICAL: hnsd doubles step AFTER i > 10 (line 314)
            // So i=0..10 use step=1, i=11+ use step=2,4,8,16...
            if (i > 10) {
                step *= 2  // Match hnsd line 314-315
            }
            
            // hnsd limits to sizeof(msg->hashes) - 1 (line 317)
            if (i >= maxHashes - 1) {
                height = 0  // Force to genesis (matching hnsd line 317-318)
            }
            
            // Check if we have this header (matching hnsd line 320: hsk_chain_get_by_height)
            // For checkpoint-based chains, we only have headers from checkpointHeight onwards
            // But for trimmed chains, we only have headers from actualFirstHeight onwards
            if (height < actualFirstHeight) {
                // Below first header in memory - skip (matching hnsd line 325: if (!hdr) continue)
                // But if we're at height 0, add genesis hash (all zeros)
                if (height == 0) {
                    locator.add(ByteArray(32))  // Genesis hash (all zeros)
                    i++
                    Log.d("HNSGo", "ChainLocator:buildLocatorList: Added genesis hash (all zeros)")
                }
                continue
            }
            
            val index = height - actualFirstHeight
            if (index >= 0 && index < headerChain.size) {
                val header = headerChain[index]
                val hash = header.hash()
                locator.add(hash)
                i++
                Log.d("HNSGo", "ChainLocator:buildLocatorList: Added locator hash at h=$height idx=$index")
            }
            // If header is missing, continue (matching hnsd's continue behavior)
        }
        
        return locator
    }
}


