package com.acktarius.hnsgo.spvp2p

import android.util.Log
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.FullNodePeers
import com.acktarius.hnsgo.Header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
     * 3. Query peers in parallel batches (NAME_QUERY_THREADS peers per batch) for faster resolution
     * 4. Process multiple batches in sequence until success or all peers exhausted
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
    ): NameQueryResult {
        // Load full node peer errors and proof counts
        FullNodePeers.loadErrors()
        val allFullNodePeers = FullNodePeers.getAllFullNodePeers()
        val filteredPeers = FullNodePeers.filterExcluded(allFullNodePeers)
        
        if (filteredPeers.isEmpty()) {
            return NameQueryResult.Error
        }
        
        // Use hnsd's probabilistic selection algorithm
        // This selects a peer based on name hash (deterministic for same name)
        // with some randomization for load balancing
        val peersToTry = FullNodePeers.selectPeersWithHnsdAlgorithm(nameHash, filteredPeers)
        
        // Limit peers if maxPeers is specified
        val peersToTryLimited = if (maxPeers < Int.MAX_VALUE) {
            peersToTry.take(maxPeers)
        } else {
            peersToTry
        }
        
        // Query peers in parallel batches for faster resolution
        // Strategy: Query multiple batches of NAME_QUERY_THREADS peers in parallel
        // This ensures we're always querying multiple peers concurrently, not just the first batch
        val batchSize = Config.NAME_QUERY_THREADS
        
        return coroutineScope {
            // Process peers in batches - each batch queries batchSize peers in parallel
            var peerIndex = 0
            while (peerIndex < peersToTryLimited.size) {
                val batch = peersToTryLimited.drop(peerIndex).take(batchSize)
                if (batch.isEmpty()) break
                
                // Launch parallel queries for this batch
                // Each async explicitly dispatches to NAME_QUERY_DISPATCHER so each peer
                // gets its own thread from the pool, enabling true parallel execution
                val queryJobs = batch.map { peer ->
                    async(Config.NAME_QUERY_DISPATCHER) {
                        val parts = peer.split(":")
                        if (parts.size != 2) {
                            return@async Pair(peer, NameQueryResult.Error)
                        }
                        
                        val host = parts[0]
                        val port = parts[1].toIntOrNull() ?: Config.P2P_PORT
                        
                        val result = ConnectionManager.queryNameFromPeer(
                            host, port, nameHash, nameRoot, chainHeight, headerChain,
                            MessageHandler, ProtocolHandler
                        )
                        
                        Pair(peer, result)
                    }
                }
                
                // Wait for all queries in this batch to complete
                val results = queryJobs.awaitAll()
                
                // Check for first successful result in this batch
                for ((peer, result) in results) {
                    when (result) {
                        is NameQueryResult.Success -> {
                            // Record proof success (like hnsd's peer->proofs++)
                            FullNodePeers.recordProofSuccess(peer)
                            return@coroutineScope NameQueryResult.Success(result.records, result.proof)
                        }
                        is NameQueryResult.NotFound -> {
                            // For full nodes, "notfound" is an error - they should have the name tree
                            FullNodePeers.recordError(peer)
                        }
                        is NameQueryResult.Error -> {
                            // Record error
                            FullNodePeers.recordError(peer)
                        }
                    }
                }
                
                // Move to next batch if no success in this batch
                peerIndex += batchSize
            }
            
            // All peers failed
            NameQueryResult.Error
        }
    }
}

