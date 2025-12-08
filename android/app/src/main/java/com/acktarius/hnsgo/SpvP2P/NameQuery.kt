package com.acktarius.hnsgo.spvp2p

import android.util.Log
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.FullNodePeers
import com.acktarius.hnsgo.Header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    ): NameQueryResult = withContext(Config.NAME_QUERY_DISPATCHER) {
        // Load full node peer errors and proof counts
        FullNodePeers.loadErrors()
        val allFullNodePeers = FullNodePeers.getAllFullNodePeers()
        val filteredPeers = FullNodePeers.filterExcluded(allFullNodePeers)
        
        if (filteredPeers.isEmpty()) {
            return@withContext NameQueryResult.Error
        }
        
        // Use hnsd's probabilistic selection algorithm
        // This selects a peer based on name hash (deterministic for same name)
        // with some randomization for load balancing
        val peersToTry = FullNodePeers.selectPeersWithHnsdAlgorithm(nameHash, filteredPeers)
        
        // Limit peers if maxPeers is specified
        val peersToTryLimited = if (maxPeers < Int.MAX_VALUE) {
            peersToTry.take(maxPeers).also {
            }
        } else {
            peersToTry.also {
            }
        }
        
        // Try each peer until we get a response
        // For full nodes, "notfound" is counted as an error because they should have the name tree
        var lastError: String? = null
        
        for ((index, peer) in peersToTryLimited.withIndex()) {
            val parts = peer.split(":")
            if (parts.size != 2) continue
            
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: Config.P2P_PORT
            
            
            val result = ConnectionManager.queryNameFromPeer(
                host, port, nameHash, nameRoot, chainHeight, headerChain,
                MessageHandler, ProtocolHandler
            )
            
            when (result) {
                is NameQueryResult.Success -> {
                    // Record proof success (like hnsd's peer->proofs++)
                    FullNodePeers.recordProofSuccess(peer)
                    return@withContext NameQueryResult.Success(result.records, result.proof)
                }
                is NameQueryResult.NotFound -> {
                    // For full nodes, "notfound" is an error - they should have the name tree
                    FullNodePeers.recordError(peer)
                    // Try next peer
                    continue
                }
                is NameQueryResult.Error -> {
                    lastError = "Connection or protocol error"
                    // Record error and try next peer
                    FullNodePeers.recordError(peer)
                    continue
                }
            }
        }
        
        
        if (lastError != null) {
        }
        return@withContext NameQueryResult.Error
    }
}

