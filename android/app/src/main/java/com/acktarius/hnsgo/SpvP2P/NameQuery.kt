package com.acktarius.hnsgo.spvp2p

import android.util.Log
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.HardcodedPeers
import com.acktarius.hnsgo.Header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles name queries to P2P peers
 * Manages peer selection and retry logic for domain name queries
 */
internal object NameQuery {
    /**
     * Query peers for a Handshake domain name
     * 
     * Peer selection strategy:
     * 1. Try persisted peers first (fast, from previous successful connections)
     * 2. Fall back to full peer discovery if persisted peers unavailable
     * 3. Try each peer until we get a successful response
     * 
     * @param nameHash 32-byte name hash
     * @param nameRoot 32-byte name_root from latest header (matches hnsd naming)
     *                 This is the root used for getproof queries (see hnsd pool.c:573: pool->chain.tip->name_root)
     * @param chainHeight Current chain height (for version message)
     * @param discoverPeers Function to discover peers if persisted peers unavailable
     * @return NameQueryResult indicating success, not found, or error
     */
    suspend fun queryName(
        nameHash: ByteArray,
        nameRoot: ByteArray,
        chainHeight: Int,
        headerChain: List<Header>?, // Optional: if provided, adjust root based on peer height
        discoverPeers: suspend () -> List<String>,
        maxPeers: Int = Int.MAX_VALUE
    ): NameQueryResult = withContext(Dispatchers.IO) {
        // Note: nameRoot should be from a height that peers have
        // We pass chainHeight for version message, but the actual root used
        // should be calculated to ensure peers have it
        // For name queries, use persisted peers directly (fast) instead of full discovery (slow)
        val peersToTry = try {
            HardcodedPeers.getFallbackPeers()
        } catch (e: Exception) {
            Log.w("HNSGo", "NameQuery: Error loading persisted peers, using discovery", e)
            discoverPeers()
        }
        
        if (peersToTry.isEmpty()) {
            Log.w("HNSGo", "NameQuery: No peers available for name query")
            return@withContext NameQueryResult.Error
        }
        
        // Limit peers if maxPeers is specified
        val peersToTryLimited = if (maxPeers < Int.MAX_VALUE) {
            peersToTry.take(maxPeers).also {
                Log.d("HNSGo", "NameQuery: Limited to ${it.size} peers (from ${peersToTry.size} total, maxPeers=$maxPeers)")
            }
        } else {
            peersToTry.also {
                Log.d("HNSGo", "NameQuery: Trying all ${it.size} peers for name query")
            }
        }
        
        // Try each peer until we get a response
        // Some peers may not support name queries (getproof) - they might only support header sync
        // Also, some peers may have pruned data, so we try multiple peers even if one says "notfound"
        var lastError: String? = null
        var notFoundCount = 0
        val maxNotFoundBeforeGivingUp = if (maxPeers < Int.MAX_VALUE) {
            // When limiting peers, use a smaller threshold (e.g., 3 out of 5)
            minOf(3, peersToTryLimited.size)
        } else {
            10  // Try at least 10 peers before concluding domain doesn't exist
        }
        
        for ((index, peer) in peersToTryLimited.withIndex()) {
            val parts = peer.split(":")
            if (parts.size != 2) continue
            
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: Config.P2P_PORT
            
            Log.d("HNSGo", "NameQuery: Trying peer ${index + 1}/${peersToTryLimited.size}: $host:$port")
            
            val result = ConnectionManager.queryNameFromPeer(
                host, port, nameHash, nameRoot, chainHeight, headerChain,
                MessageHandler, ProtocolHandler
            )
            
            when (result) {
                is NameQueryResult.Success -> {
                    Log.d("HNSGo", "NameQuery: Successfully queried name from $host:$port")
                    return@withContext NameQueryResult.Success(result.records, result.proof)
                }
                is NameQueryResult.NotFound -> {
                    notFoundCount++
                    Log.d("HNSGo", "NameQuery: Domain not found from $host:$port (notfound count: $notFoundCount/$maxNotFoundBeforeGivingUp)")
                    // If multiple peers say notfound, it's likely the domain doesn't exist
                    if (notFoundCount >= maxNotFoundBeforeGivingUp) {
                        Log.d("HNSGo", "NameQuery: Multiple peers ($notFoundCount) confirmed domain not found - domain likely doesn't exist")
                        return@withContext NameQueryResult.NotFound
                    }
                    // Otherwise, try next peer (peer might have pruned data)
                    continue
                }
                is NameQueryResult.Error -> {
                    lastError = "Connection or protocol error"
                    Log.d("HNSGo", "NameQuery: Error querying $host:$port (trying next peer - some peers may not support name queries)")
                    // Try next peer - some peers may not support name queries (getproof)
                    continue
                }
            }
        }
        
        Log.w("HNSGo", "NameQuery: Failed to query name from all ${peersToTryLimited.size} peers")
        
        // If we got "notfound" from multiple peers, that's a valid response (domain doesn't exist)
        // Return NotFound instead of Error in this case
        if (notFoundCount > 0) {
            Log.d("HNSGo", "NameQuery: Got $notFoundCount 'notfound' responses - returning NotFound")
            return@withContext NameQueryResult.NotFound
        }
        
        if (lastError != null) {
            Log.w("HNSGo", "NameQuery: Last error was: $lastError")
        }
        // If all peers errored (no notfound responses), that's a real problem
        return@withContext NameQueryResult.Error
    }
}

