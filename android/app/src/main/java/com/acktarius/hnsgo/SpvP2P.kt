package com.acktarius.hnsgo

import com.acktarius.hnsgo.spvp2p.ConnectionManager
import com.acktarius.hnsgo.spvp2p.NameQuery
import com.acktarius.hnsgo.spvp2p.NameQueryResult as Spvp2pNameQueryResult
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handshake P2P protocol implementation for SPV header sync
 * Based on Handshake protocol (similar to Bitcoin P2P)
 * 
 * This is a facade that delegates to specialized classes:
 * - ConnectionManager: Connection management and high-level operations
 * - ProtocolHandler: Protocol operations (handshake, sending commands)
 * - MessageHandler: Message parsing and serialization
 * - NameQuery: Domain name query logic with peer selection
 */
object SpvP2P {
    /**
     * Result of a name query - distinguishes between "not found" (valid response) and errors
     */
    sealed class NameQueryResult {
        data class Success(val records: List<ByteArray>, val proof: ByteArray?) : NameQueryResult()
        object NotFound : NameQueryResult() // Valid protocol response - domain doesn't exist
        object Error : NameQueryResult() // Actual error (connection failure, etc.)
    }
    
    /**
     * Initialize peer discovery with data directory for persistence
     */
    fun init(dataDir: File) {
        ConnectionManager.init(dataDir)
    }
    
    /**
     * Discover peers using the new logic flow from .cursorrules:
     * 1. Query DNS seeds first (aoihakkila.com, easyhandshake.com)
     * 2. Load and verify hardcoded peers (from storage, max 10)
     * 3. If hardcoded list is empty, populate with first 10 discovered peers
     * 4. Always use hardcoded peers as fallback if DNS fails
     * 5. Try DHT discovery if DNS and hardcoded peers both fail
     * 
     * Returns list of discovered peer addresses (host:port format)
     */
    suspend fun discoverPeers(): List<String> = withContext(Dispatchers.IO) {
        ConnectionManager.discoverPeers()
    }
    
    /**
     * Record a successful peer connection for future use
     * Adds to hardcoded verified peers list and resets error count
     */
    suspend fun recordSuccessfulPeer(peer: String) = withContext(Dispatchers.IO) {
        ConnectionManager.recordSuccessfulPeer(peer)
    }
    
    /**
     * Query peers for a Handshake domain name
     * @param nameHash 32-byte name hash
     * @param nameRoot 32-byte name_root from latest header (matches hnsd naming)
     *                 This is the root used for getproof queries (see hnsd pool.c:573: pool->chain.tip->name_root)
     * @param chainHeight Current chain height (for version message)
     * @return NameQueryResult indicating success, not found, or error
     */
    suspend fun queryName(
        nameHash: ByteArray,
        nameRoot: ByteArray,
        chainHeight: Int = 0,
        headerChain: List<Header>? = null, // Optional: if provided, adjust root based on peer height
        maxPeers: Int = Int.MAX_VALUE
    ): NameQueryResult = withContext(Dispatchers.IO) {
        val result = NameQuery.queryName(nameHash, nameRoot, chainHeight, headerChain, { discoverPeers() }, maxPeers)
        
        // Convert internal NameQueryResult to public SpvP2P.NameQueryResult
        when (result) {
            is Spvp2pNameQueryResult.Success -> {
                NameQueryResult.Success(result.records, result.proof)
            }
            is Spvp2pNameQueryResult.NotFound -> {
                NameQueryResult.NotFound
            }
            is Spvp2pNameQueryResult.Error -> {
                NameQueryResult.Error
            }
        }
    }
}
