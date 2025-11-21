package com.acktarius.hnsgo

import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.net.Socket
import java.net.InetSocketAddress
import java.net.InetAddress
import com.upokecenter.cbor.CBORObject

/**
 * Verified fallback peer list for Handshake network
 * 
 * This is a dynamic list (max 10 peers) stored in local storage:
 * - Loaded from storage at startup
 * - Verified each session (ping/connect test)
 * - If empty, populated with first 10 discovered peers
 * - Failed peers are removed, new ones added
 * - Always used as fallback if DNS seeds fail
 * 
 * Format: "IP:12038" (mainnet port)
 */
object HardcodedPeers {
    // Maximum number of fallback peers to maintain (from Config)
    private const val MAX_PEERS = Config.MAX_FALLBACK_PEERS
    private const val MAINNET_PORT = Config.MAINNET_PORT
    
    // In-memory cache of verified peers
    private val verifiedPeers = mutableListOf<String>()
    private var dataDir: File? = null
    
    /**
     * Initialize with data directory for persistence
     */
    fun init(dataDir: File) {
        this.dataDir = dataDir
    }
    
    /**
     * Load verified peers from storage
     */
    suspend fun loadPeers(): List<String> = withContext(Dispatchers.IO) {
        val dir = dataDir ?: return@withContext emptyList()
        val peersFile = File(dir, Config.PEERS_FILE)
        
        if (!peersFile.exists()) {
            Log.d("HardcodedPeers", "No persisted peers file found - will populate on first discovery")
            verifiedPeers.clear()
            return@withContext emptyList()
        }
        
        try {
            val data = peersFile.readBytes()
            val cbor = CBORObject.DecodeFromBytes(data)
            
            val peersArray = cbor["peers"]
            if (peersArray != null) {
                val peers = mutableListOf<String>()
                val count = peersArray.size()
                for (i in 0 until count) {
                    val peer = peersArray[i].AsString()
                    if (peer != null && isValidPeerAddress(peer)) {
                        peers.add(peer)
                    }
                }
                
                // Limit to MAX_PEERS
                verifiedPeers.clear()
                verifiedPeers.addAll(peers.take(MAX_PEERS))
                
                Log.d("HardcodedPeers", "Loaded ${verifiedPeers.size} verified peers from storage")
                return@withContext verifiedPeers.toList()
            }
        } catch (e: Exception) {
            Log.e("HardcodedPeers", "Error loading peers from storage", e)
        }
        
        verifiedPeers.clear()
        return@withContext emptyList()
    }
    
    /**
     * Save verified peers to storage
     */
    private suspend fun savePeers() = withContext(Dispatchers.IO) {
        val dir = dataDir ?: return@withContext
        
        try {
            val cbor = CBORObject.NewMap()
            val peersArray = CBORObject.NewArray()
            
            for (peer in verifiedPeers) {
                peersArray.Add(CBORObject.FromObject(peer))
            }
            
            cbor["peers"] = peersArray
            cbor["timestamp"] = CBORObject.FromObject(System.currentTimeMillis())
            cbor["count"] = CBORObject.FromObject(verifiedPeers.size)
            
            val data = cbor.EncodeToBytes()
            val peersFile = File(dir, Config.PEERS_FILE)
            val tempFile = File(dir, "${Config.PEERS_FILE}.tmp")
            
            tempFile.writeBytes(data)
            tempFile.renameTo(peersFile)
            
            Log.d("HardcodedPeers", "Saved ${verifiedPeers.size} verified peers to storage")
        } catch (e: Exception) {
            Log.e("HardcodedPeers", "Error saving peers", e)
        }
    }
    
    /**
     * Validate that a peer address is properly formatted
     */
    private fun isValidPeerAddress(address: String): Boolean {
        return try {
            val parts = address.split(":")
            if (parts.size != 2) return false
            
            val ip = parts[0]
            val port = parts[1].toInt()
            
            // Validate IP address
            InetAddress.getByName(ip) != null && port > 0 && port <= 65535
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Verify a peer by attempting to connect
     * Returns true if peer is reachable, false otherwise
     * 
     * Note: This is a basic connectivity test. Full handshake verification
     * should be done during actual P2P sync attempts.
     */
    suspend fun verifyPeer(peer: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidPeerAddress(peer)) {
            return@withContext false
        }
        
        try {
            val parts = peer.split(":")
            val host = parts[0]
            val port = if (parts.size > 1) parts[1].toInt() else MAINNET_PORT
            
            // Quick connection test (short timeout)
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 3000) // 3 second timeout
            socket.close()
            
            Log.d("HardcodedPeers", "Peer verified (connectivity): $peer")
            return@withContext true
        } catch (e: java.net.SocketTimeoutException) {
            Log.d("HardcodedPeers", "Peer verification timeout: $peer")
            return@withContext false
        } catch (e: java.net.ConnectException) {
            Log.d("HardcodedPeers", "Peer verification refused: $peer")
            return@withContext false
        } catch (e: Exception) {
            Log.d("HardcodedPeers", "Peer verification failed: $peer - ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Verify all peers in the list, remove failed ones
     */
    suspend fun verifyAllPeers() = withContext(Dispatchers.IO) {
        if (verifiedPeers.isEmpty()) {
            return@withContext
        }
        
        Log.d("HardcodedPeers", "Verifying ${verifiedPeers.size} peers...")
        val validPeers = mutableListOf<String>()
        
        // Verify peers in parallel (but limit concurrency)
        val verificationJobs = verifiedPeers.map { peer ->
            async {
                if (verifyPeer(peer)) {
                    peer
                } else {
                    null
                }
            }
        }
        
        val results = verificationJobs.awaitAll()
        validPeers.addAll(results.filterNotNull())
        
        val removedCount = verifiedPeers.size - validPeers.size
        if (removedCount > 0) {
            Log.d("HardcodedPeers", "Removed $removedCount failed peers")
        }
        
        verifiedPeers.clear()
        verifiedPeers.addAll(validPeers)
        
        // Save updated list
        savePeers()
    }
    
    /**
     * Add peers to the list (up to MAX_PEERS)
     * If list is empty, populate with first discovered peers
     */
    suspend fun addPeers(newPeers: List<String>) = withContext(Dispatchers.IO) {
        val validPeers = newPeers.filter { isValidPeerAddress(it) }
        
        // If list is empty, populate with first discovered peers
        if (verifiedPeers.isEmpty()) {
            val peersToAdd = validPeers.take(MAX_PEERS)
            verifiedPeers.addAll(peersToAdd)
            Log.d("HardcodedPeers", "Populated empty list with ${peersToAdd.size} peers")
            savePeers()
            return@withContext
        }
        
        // Add new peers up to MAX_PEERS, avoiding duplicates
        for (peer in validPeers) {
            if (verifiedPeers.size >= MAX_PEERS) break
            if (!verifiedPeers.contains(peer)) {
                verifiedPeers.add(peer)
            }
        }
        
        // Trim to MAX_PEERS if needed
        if (verifiedPeers.size > MAX_PEERS) {
            verifiedPeers.removeAt(verifiedPeers.size - 1)
        }
        
        savePeers()
    }
    
    /**
     * Get fallback peers (verified peers from storage)
     */
    fun getFallbackPeers(): List<String> {
        return verifiedPeers.toList()
    }
    
    /**
     * Check if list is empty
     */
    fun isEmpty(): Boolean {
        return verifiedPeers.isEmpty()
    }
}

