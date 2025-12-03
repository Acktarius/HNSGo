package com.acktarius.hnsgo.spvp2p

import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.HardcodedPeers
import android.util.Log
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
        discoverPeers: suspend () -> List<String>
    ): NameQueryResult = withContext(Dispatchers.IO) {
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
        
        Log.d("HNSGo", "NameQuery: Trying ${peersToTry.size} peers for name query")
        
        // Try each peer until we get a response
        // Some peers may not support name queries (getproof) - they might only support header sync
        // Also, some peers may have pruned data, so we try multiple peers even if one says "notfound"
        var lastError: String? = null
        var notFoundCount = 0
        val maxNotFoundBeforeGivingUp = 3  // Try at least 3 peers before concluding domain doesn't exist
        
        for ((index, peer) in peersToTry.withIndex()) {
            val parts = peer.split(":")
            if (parts.size != 2) continue
            
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: Config.P2P_PORT
            
            Log.d("HNSGo", "NameQuery: Trying peer ${index + 1}/${peersToTry.size}: $host:$port")
            
            val result = ConnectionManager.queryNameFromPeer(
                host, port, nameHash, nameRoot, chainHeight,
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
        
        Log.w("HNSGo", "NameQuery: Failed to query name from all ${peersToTry.size} peers")
        if (lastError != null) {
            Log.w("HNSGo", "NameQuery: Last error was: $lastError")
        }
        // If we got "notfound" from at least one peer, that's a valid response (domain doesn't exist)
        // But if all peers errored, that's a real problem
        return@withContext NameQueryResult.Error
    }
}

