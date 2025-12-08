package com.acktarius.hnsgo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
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
                
                return@withContext verifiedPeers.toList()
            }
        } catch (e: Exception) {
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
            
        } catch (e: Exception) {
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
            
            return@withContext true
        } catch (e: java.net.SocketTimeoutException) {
            return@withContext false
        } catch (e: java.net.ConnectException) {
            return@withContext false
        } catch (e: Exception) {
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
     * Get fallback peers (verified peers from storage + hnsd seeds)
     * Includes hnsd seeds.h mainnet peers as initial fallback
     */
    fun getFallbackPeers(): List<String> {
        val allPeers = mutableListOf<String>()
        
        // Add hnsd seeds.h mainnet peers (from hnsd/src/seeds.h)
        // These are known-good Handshake nodes maintained by the community
        val hnsdSeeds = listOf(
            "165.22.151.242:12038",  // pinheadmz
            "50.116.35.178:12038",   // Falci
            "129.153.177.220:12038", // rithvikvibhu
            "107.152.33.71:12038",   // buffrr
            "159.69.46.23:12038",    // anon
            "173.255.209.126:12038", // chjj
            "74.207.247.120:12038",  // chjj
            "172.104.214.189:12038", // chjj
            "172.104.177.177:12038", // chjj
            "139.162.183.168:12038", // chjj
            "45.79.134.225:12038",   // chjj
            "104.254.246.153:12038",
            "66.94.118.127:12038",
            "165.227.93.117:12038",
            "98.47.90.111:12038",
            "85.214.33.200:12038",
            "178.18.254.92:12038",
            "152.69.186.119:12038",
            "89.58.17.86:12038",
            "76.217.158.15:12038",
            "149.102.152.193:12038",
            "139.59.211.187:12038",
            "147.124.228.198:12038",
            "108.175.4.26:12038",
            "66.29.151.42:12038",
            "205.250.112.133:12038",
            "44.229.138.206:12038",
            "81.6.58.121:12038",
            "154.26.133.109:12038",
            "140.238.164.155:12038",
            "81.169.159.30:12038",
            "50.112.123.184:12038",
            "185.232.71.108:12038",
            "207.244.233.76:12038",
            "140.238.196.136:12038",
            "193.41.237.153:12038",
            "82.223.165.215:12038",
            "212.227.68.123:12038",
            "202.61.201.15:12038",
            "168.138.31.136:12038",
            "107.152.32.250:12038",
            "108.61.206.38:12038",
            "204.99.129.8:12038",
            "73.185.40.66:12038",
            "129.153.166.244:12038",
            "137.184.156.222:12038",
            "5.161.64.49:12038",
            "176.96.136.143:12038",
            "150.136.231.120:12038",
            "54.184.104.94:12038",
            "47.108.194.84:12038",
            "45.79.95.228:12038",
            "89.163.154.217:12038",
            "139.178.67.226:12038",
            "47.242.86.29:12038",
            "99.92.204.3:12038"
        )
        
        // Add hnsd seeds first (they're known-good)
        allPeers.addAll(hnsdSeeds)
        
        // Then add verified peers from storage
        allPeers.addAll(verifiedPeers)
        
        // Remove duplicates while preserving order (hnsd seeds first)
        return allPeers.distinct().toList()
    }
    
    /**
     * Check if list is empty
     */
    fun isEmpty(): Boolean {
        return verifiedPeers.isEmpty()
    }
}

