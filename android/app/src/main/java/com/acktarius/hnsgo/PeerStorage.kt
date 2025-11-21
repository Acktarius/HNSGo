package com.acktarius.hnsgo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.upokecenter.cbor.CBORObject

/**
 * Peer storage for persisting discovered peers
 * 
 * Stores successful peers for future fallback list updates.
 * Format: List of "IP:port" strings stored as CBOR
 */
object PeerStorage {
    /**
     * Load persisted peers from disk
     */
    suspend fun loadPeers(dataDir: File): List<String> = withContext(Dispatchers.IO) {
        val peersFile = File(dataDir, Config.PEERS_FILE)
        if (!peersFile.exists()) {
            Log.d("PeerStorage", "No persisted peers file found")
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
                    if (peer != null && DnsSeedDiscovery.isValidPeerAddress(peer)) {
                        peers.add(peer)
                    }
                }
                Log.d("PeerStorage", "Loaded ${peers.size} persisted peers")
                return@withContext peers
            }
        } catch (e: Exception) {
            Log.e("PeerStorage", "Error loading persisted peers", e)
        }
        
        return@withContext emptyList()
    }
    
    /**
     * Save peers to disk for persistence
     */
    suspend fun savePeers(dataDir: File, peers: List<String>) = withContext(Dispatchers.IO) {
        try {
            val cbor = CBORObject.NewMap()
            val peersArray = CBORObject.NewArray()
            
            // Only save valid peer addresses
            for (peer in peers) {
                if (DnsSeedDiscovery.isValidPeerAddress(peer)) {
                    peersArray.Add(CBORObject.FromObject(peer))
                }
            }
            
            cbor["peers"] = peersArray
            cbor["timestamp"] = CBORObject.FromObject(System.currentTimeMillis())
            cbor["count"] = CBORObject.FromObject(peersArray.size())
            
            val data = cbor.EncodeToBytes()
            val peersFile = File(dataDir, Config.PEERS_FILE)
            val tempFile = File(dataDir, "${Config.PEERS_FILE}.tmp")
            
            tempFile.writeBytes(data)
            tempFile.renameTo(peersFile)
            
            Log.d("PeerStorage", "Saved ${peersArray.size()} peers to disk")
        } catch (e: Exception) {
            Log.e("PeerStorage", "Error saving peers", e)
        }
    }
    
    /**
     * Add a successful peer to the persisted list
     */
    suspend fun addSuccessfulPeer(dataDir: File, peer: String) = withContext(Dispatchers.IO) {
        if (!DnsSeedDiscovery.isValidPeerAddress(peer)) {
            return@withContext
        }
        
        val existingPeers = loadPeers(dataDir).toMutableSet()
        existingPeers.add(peer)
        
        // Limit to reasonable number (e.g., 50 peers max)
        val peersToSave = existingPeers.take(50).toList()
        savePeers(dataDir, peersToSave)
        
        Log.d("PeerStorage", "Added successful peer: $peer (total: ${peersToSave.size})")
    }
}

