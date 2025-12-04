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
     * @param nameRoot 32-byte tree_root from latest header (treeRoot = merkle_root in hnsd wire format)
     *                 This is the root used for getproof queries, NOT name_root
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
        for (peer in peersToTry) {
            val parts = peer.split(":")
            if (parts.size != 2) continue
            
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: Config.P2P_PORT
            
            Log.d("HNSGo", "NameQuery: Trying peer $host:$port")
            
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
                    Log.d("HNSGo", "NameQuery: Domain not found (valid protocol response from $host:$port)")
                    return@withContext NameQueryResult.NotFound
                }
                is NameQueryResult.Error -> {
                    Log.d("HNSGo", "NameQuery: Error querying $host:$port, trying next peer")
                    // Try next peer
                    continue
                }
            }
        }
        
        Log.w("HNSGo", "NameQuery: Failed to query name from all ${peersToTry.size} peers")
        return@withContext NameQueryResult.Error
    }
}

