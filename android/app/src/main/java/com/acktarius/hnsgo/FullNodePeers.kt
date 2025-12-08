package com.acktarius.hnsgo

import android.util.Log
import com.upokecenter.cbor.CBORObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages full node peers for name queries (from hnsd seeds.h)
 * 
 * These are known full nodes that maintain the name tree and can answer getproof queries.
 * We track errors for these peers, and count "notfound" as an error because these peers
 * should have the name tree and should not return notfound for valid queries.
 */
object FullNodePeers {
    private const val FULL_NODE_PEERS_FILE = "fullnode_peers.cbor"
    private const val MAX_ERRORS = 3
    
    // In-memory tracking: peer address -> error count
    private val peerErrors = mutableMapOf<String, Int>()
    // In-memory tracking: peer address -> proof success count (like hnsd's peer->proofs)
    private val peerProofs = mutableMapOf<String, Int>()
    // In-memory tracking: verified full nodes (peers that have NETWORK service flag)
    private val verifiedFullNodes = mutableSetOf<String>()
    private var dataDir: File? = null
    
    /**
     * Initialize with data directory for persistence
     */
    fun init(dataDir: File) {
        this.dataDir = dataDir
    }
    
    /**
     * Record that a peer has been verified as a full node (has NETWORK service flag)
     * This dynamically builds our list of verified full nodes
     */
    suspend fun recordVerifiedFullNode(peer: String) = withContext(Dispatchers.IO) {
        if (verifiedFullNodes.add(peer)) {
            saveVerifiedFullNodes()
        }
    }
    
    /**
     * Get all full node peers, prioritizing verified full nodes (those with NETWORK flag)
     * PRIORITY ORDER:
     * 1. Verified full nodes (dynamically discovered - have NETWORK flag)
     * 2. Known full nodes from seeds.h (hardcoded list)
     * 3. Other discovered nodes from seeds.h
     */
    fun getAllFullNodePeers(): List<String> {
        // PRIORITY 1: Verified full nodes (dynamically discovered - have NETWORK flag)
        val verified = verifiedFullNodes.toList()
        
        // PRIORITY 2: Known full nodes from hnsd seeds.h (with @ format)
        val knownFullNodes = listOf(
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
            "45.79.134.225:12038"    // chjj
        )
        
        // PRIORITY 2: Other discovered nodes from hnsd seeds.h (plain IPs)
        val otherDiscoveredNodes = listOf(
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
        
        // Combine all peers, prioritizing verified full nodes first
        // Remove verified peers from hardcoded lists to avoid duplicates
        val hardcodedPeers = (knownFullNodes + otherDiscoveredNodes).filter { it !in verified }
        return (verified + hardcodedPeers).distinct()
    }
    
    /**
     * Load error counts from storage
     */
    suspend fun loadErrors() = withContext(Dispatchers.IO) {
        val dir = dataDir ?: return@withContext
        val errorsFile = File(dir, FULL_NODE_PEERS_FILE)
        
        if (!errorsFile.exists()) {
            peerErrors.clear()
            peerProofs.clear()
            verifiedFullNodes.clear()
            return@withContext
        }
        
        try {
            val data = errorsFile.readBytes()
            val cbor = CBORObject.DecodeFromBytes(data)
            
            val errorsMap = cbor["errors"]
            if (errorsMap != null) {
                peerErrors.clear()
                val keys = errorsMap.getKeys()
                for (key in keys) {
                    val peer = key.AsString()
                    val count = errorsMap[key]?.AsInt32() ?: 0
                    if (peer != null && count > 0) {
                        peerErrors[peer] = count
                    }
                }
            }
            
            // Load proof counts (successful proof responses)
            val proofsMap = cbor["proofs"]
            if (proofsMap != null) {
                peerProofs.clear()
                val keys = proofsMap.getKeys()
                for (key in keys) {
                    val peer = key.AsString()
                    val count = proofsMap[key]?.AsInt32() ?: 0
                    if (peer != null && count > 0) {
                        peerProofs[peer] = count
                    }
                }
            }
            
            // Load verified full nodes (peers with NETWORK flag)
            val verifiedMap = cbor["verified"]
            if (verifiedMap != null) {
                verifiedFullNodes.clear()
                val keys = verifiedMap.getKeys()
                for (key in keys) {
                    val peer = key.AsString()
                    val verified = verifiedMap[key]?.AsBoolean() ?: false
                    if (peer != null && verified) {
                        verifiedFullNodes.add(peer)
                    }
                }
            }
        } catch (e: Exception) {
            peerErrors.clear()
            peerProofs.clear()
            verifiedFullNodes.clear()
        }
    }
    
    /**
     * Save error counts to storage
     */
    private suspend fun saveErrors() = withContext(Dispatchers.IO) {
        val dir = dataDir ?: return@withContext
        
        try {
            val cbor = CBORObject.NewMap()
            val errorsMap = CBORObject.NewMap()
            val proofsMap = CBORObject.NewMap()
            val verifiedMap = CBORObject.NewMap()
            
            for ((peer, count) in peerErrors) {
                errorsMap[peer] = CBORObject.FromObject(count)
            }
            
            for ((peer, count) in peerProofs) {
                proofsMap[peer] = CBORObject.FromObject(count)
            }
            
            for (peer in verifiedFullNodes) {
                verifiedMap[peer] = CBORObject.FromObject(true)
            }
            
            cbor["errors"] = errorsMap
            cbor["proofs"] = proofsMap
            cbor["verified"] = verifiedMap
            cbor["timestamp"] = CBORObject.FromObject(System.currentTimeMillis())
            
            val data = cbor.EncodeToBytes()
            val peersFile = File(dir, FULL_NODE_PEERS_FILE)
            val tempFile = File(dir, "$FULL_NODE_PEERS_FILE.tmp")
            
            tempFile.writeBytes(data)
            tempFile.renameTo(peersFile)
            
        } catch (e: Exception) {
        }
    }
    
    /**
     * Save verified full nodes to storage
     */
    private suspend fun saveVerifiedFullNodes() = withContext(Dispatchers.IO) {
        saveErrors() // Reuse saveErrors to save everything including verified nodes
    }
    
    /**
     * Record an error for a full node peer
     * For full nodes, "notfound" is also counted as an error because they should have the name tree
     */
    suspend fun recordError(peer: String) = withContext(Dispatchers.IO) {
        val currentCount = peerErrors.getOrDefault(peer, 0)
        val newCount = currentCount + 1
        peerErrors[peer] = newCount
        
        
        if (newCount >= MAX_ERRORS) {
        }
        
        saveErrors()
    }
    
    /**
     * Reset error count for a peer (after successful query)
     */
    suspend fun resetErrors(peer: String) = withContext(Dispatchers.IO) {
        if (peerErrors.remove(peer) != null) {
            saveErrors()
        }
    }
    
    /**
     * Record a successful proof response (like hnsd's peer->proofs++)
     * This tracks which peers are good at returning proofs
     */
    suspend fun recordProofSuccess(peer: String) = withContext(Dispatchers.IO) {
        val currentCount = peerProofs.getOrDefault(peer, 0)
        val newCount = currentCount + 1
        peerProofs[peer] = newCount
        
        
        // Also reset error count on proof success
        resetErrors(peer)
        
        saveErrors()
    }
    
    /**
     * Get proof count for a peer (number of successful proof responses)
     */
    fun getProofCount(peer: String): Int {
        return peerProofs.getOrDefault(peer, 0)
    }
    
    /**
     * Check if a peer is verified (has NETWORK service flag)
     */
    fun isVerified(peer: String): Boolean {
        return peer in verifiedFullNodes
    }
    
    /**
     * Check if a peer should be excluded (error count > MAX_ERRORS)
     */
    fun shouldExclude(peer: String): Boolean {
        val errorCount = peerErrors.getOrDefault(peer, 0)
        return errorCount >= MAX_ERRORS
    }
    
    /**
     * Get error count for a peer
     */
    fun getErrorCount(peer: String): Int {
        return peerErrors.getOrDefault(peer, 0)
    }
    
    /**
     * Get peers sorted by reputation (like hnsd's peer selection)
     * Sorts by: verified status (verified first), then proof count (descending), then error count (ascending)
     * Peers with NETWORK service flag (verified) are prioritized
     */
    fun getPeersSortedByReputation(): List<String> {
        val allPeers = getAllFullNodePeers()
        return allPeers.sortedWith(
            compareByDescending<String> { isVerified(it) }  // Verified peers first
                .thenByDescending { getProofCount(it) }      // Then by proof count
                .thenBy { getErrorCount(it) }                 // Then by error count (lower is better)
        )
    }
    
    /**
     * Get peers sorted by error count (lower is better) - kept for backward compatibility
     */
    fun getPeersSortedByErrorCount(): List<String> {
        return getPeersSortedByReputation()
    }
    
    /**
     * Select peer deterministically based on name hash (like hnsd's name_hash[0] % total)
     * Same name hash will always select the same peer index
     * 
     * @param nameHash 32-byte name hash
     * @param peers List of available peers
     * @return Index into peers list, or -1 if peers is empty
     */
    fun selectDeterministicPeer(nameHash: ByteArray, peers: List<String>): Int {
        if (peers.isEmpty()) return -1
        // Use first byte of name hash to deterministically select peer (like hnsd)
        val index = (nameHash[0].toInt() and 0xFF) % peers.size
        return index
    }
    
    /**
     * Select peers using hnsd's probabilistic algorithm
     * Returns a list with the selected peer first, followed by others sorted by reputation
     * 
     * Algorithm (matching hnsd pool.c:495-504):
     * - 20% chance: random peer
     * - 10% chance: second_best (by proof count)
     * - 10% chance: first_best (by proof count)
     * - 60% chance: deterministic (based on name_hash[0])
     * 
     * @param nameHash 32-byte name hash
     * @param peers List of available peers (should already be filtered and sorted by reputation)
     * @return List with selected peer first, then others
     */
    fun selectPeersWithHnsdAlgorithm(nameHash: ByteArray, peers: List<String>): List<String> {
        if (peers.isEmpty()) return emptyList()
        if (peers.size == 1) return peers
        
        // Sort peers by: verified status (verified first), then proof count (descending), then error count (ascending)
        // This prioritizes peers with NETWORK service flag (service 3) that we've verified
        val sortedPeers = peers.sortedWith(
            compareByDescending<String> { isVerified(it) }  // Verified peers first
                .thenByDescending { getProofCount(it) }      // Then by proof count
                .thenBy { getErrorCount(it) }                // Then by error count (lower is better)
        )
        
        // Find first_best and second_best (by proof count)
        val firstBest = sortedPeers.firstOrNull()
        val secondBest = if (sortedPeers.size > 1) sortedPeers[1] else null
        
        // Deterministic selection (default fallback)
        val deterministicIndex = selectDeterministicPeer(nameHash, sortedPeers)
        val deterministic = if (deterministicIndex >= 0) sortedPeers[deterministicIndex] else null
        
        // Probabilistic selection (matching hnsd pool.c:495-504)
        // Uses separate random checks like hnsd does
        val selected = when {
            // 20% chance: random peer (hsk_random() % 5 == 0)
            (0 until 5).random() == 0 -> {
                val randomIndex = (0 until sortedPeers.size).random()
                sortedPeers[randomIndex]
            }
            // 10% chance: second_best (hsk_random() % 10 == 0)
            secondBest != null && (0 until 10).random() == 0 -> secondBest
            // 10% chance: first_best (hsk_random() % 10 == 0)
            firstBest != null && (0 until 10).random() == 0 -> firstBest
            // Default: deterministic (based on name_hash[0])
            else -> deterministic ?: sortedPeers.firstOrNull()
        }
        
        if (selected == null) return sortedPeers
        
        // Return selected peer first, then others
        val others = sortedPeers.filter { it != selected }
        return listOf(selected) + others
    }
    
    /**
     * Filter out excluded peers
     * If all peers are excluded, reset all error counts and return all peers (to prevent total blacklist)
     */
    suspend fun filterExcluded(peers: List<String>): List<String> = withContext(Dispatchers.IO) {
        val filtered = peers.filter { !shouldExclude(it) }
        
        if (filtered.isEmpty() && peers.isNotEmpty()) {
            // All peers are excluded - reset all error counts to prevent total blacklist
            peerErrors.clear()
            saveErrors() // Persist the reset
            return@withContext peers
        }
        
        if (filtered.size < peers.size) {
            val excludedCount = peers.size - filtered.size
        }
        
        return@withContext filtered
    }
}

