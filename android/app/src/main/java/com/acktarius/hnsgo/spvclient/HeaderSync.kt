package com.acktarius.hnsgo.spvclient

import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.Header
import com.acktarius.hnsgo.spvp2p.ConnectionManager
import com.acktarius.hnsgo.spvp2p.MessageHandler
import com.acktarius.hnsgo.spvp2p.ProtocolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Arrays

/**
 * Wrapper for ByteArray to use in HashSet
 * ByteArray doesn't implement proper equals/hashCode for HashSet membership
 */
private data class HashWrapper(val hash: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HashWrapper) return false
        return Arrays.equals(hash, other.hash)
    }
    
    override fun hashCode(): Int {
        return Arrays.hashCode(hash)
    }
}

/**
 * Handles header synchronization from P2P network
 * Manages the sync process, validation, and background syncing
 * Uses HashSet for O(1) duplicate detection (matching hnsd's hash map approach)
 */
object HeaderSync {
    private const val MAX_HEIGHT_DIFF = 10000
    
    // HashSet for O(1) duplicate detection (matching hnsd's chain->hashes approach)
    private val headerHashes = Collections.synchronizedSet(HashSet<HashWrapper>())
    
    /**
     * Initialize header hash set from existing headers
     * Call this when loading headers from disk to populate the HashSet
     */
    fun initializeHashSet(headerChain: List<Header>) {
        synchronized(headerHashes) {
            headerHashes.clear()
            for (header in headerChain) {
                headerHashes.add(HashWrapper(header.hash()))
            }
        }
    }
    
    /**
     * Sync headers from Handshake P2P network
     */
    suspend fun syncHeaders(
        headerChain: MutableList<Header>,
        getChainHeight: () -> Int,
        firstInMemoryHeight: Int,
        maxInMemoryHeaders: Int,
        onHeaderAdded: (Header, Int) -> Unit,  // Header and its actual height
        saveHeaders: suspend () -> Unit,
        knownNetworkHeight: Int? = null  // Optional: if we know network height, reject headers ahead of it
    ): Int? = withContext(Config.HEADER_SYNC_DISPATCHER) {
        
        val startHeight = getChainHeight()
        
        // Initialize HashSet if empty (first sync after loading from disk)
        synchronized(headerHashes) {
            if (headerHashes.isEmpty() && headerChain.isNotEmpty()) {
                for (header in headerChain) {
                    headerHashes.add(HashWrapper(header.hash()))
                }
            }
        }
        
        val headerChainSnapshot = synchronized(headerChain) {
            headerChain.toList()
        }
        
        if (headerChainSnapshot.isNotEmpty()) {
            val lastHeader = headerChainSnapshot.last()
            val lastHash = lastHeader.hash()
            val hashHex = lastHash.joinToString("") { byte -> "%02x".format(byte) }
            val hashIsZero = lastHash.all { byte -> byte == 0.toByte() }
            val prevBlockHex = lastHeader.prevBlock.take(16).joinToString("") { byte -> "%02x".format(byte) }
            val nameRootHex = lastHeader.nameRoot.take(16).joinToString("") { byte -> "%02x".format(byte) }
            val maskHex = lastHeader.mask.take(16).joinToString("") { byte -> "%02x".format(byte) }
        }
        
        val locatorHashes = ChainLocator.buildLocatorList(headerChainSnapshot, startHeight, firstInMemoryHeight)
        
        val checkpointEndHeight = Config.CHECKPOINT_HEIGHT + 150 - 1
        if (startHeight <= checkpointEndHeight) {
        }
        
        
        var newHeadersCount = 0
        // Track current height locally to avoid issues with getChainHeight() closure timing
        // This ensures each header in a batch sees the correct expected height
        var localCurrentHeight = startHeight
        
        val syncResult = ConnectionManager.syncHeaders(
            startHeight, locatorHashes,
            { header, preComputedHash ->
                // CRITICAL: Only accept headers that connect to our tip (new headers)
                // When using earlier locator hashes, peers send headers starting from those points
                // We need to reject headers that don't connect to our tip (they're old headers)
                val expectedHeight = localCurrentHeight + 1
                
                // CRITICAL FIX: When requesting from earlier locator hashes, peers return headers
                // starting from that point. We need to:
                // 1. Skip headers that match our current tip (duplicates)
                // 2. Skip headers that are before our tip (old headers)
                // 3. Only accept headers that connect to our tip (new headers after our tip)
                
                val tipInfo = synchronized(headerChain) {
                    if (headerChain.isEmpty()) {
                        Triple(false, false, null as ByteArray?)
                    } else {
                        val tipHeader = headerChain.last()
                        val tipHash = tipHeader.hash()
                        val isCurrentTip = preComputedHash.contentEquals(tipHash)
                        val connectsToTip = header.prevBlock.contentEquals(tipHash)
                        Triple(isCurrentTip, connectsToTip, tipHash)
                    }
                }
                
                val (isCurrentTip, connectsToTip, tipHash) = tipInfo
                
                if (isCurrentTip) {
                    // This is our current tip - skip it, we need headers AFTER it
                    // Continue processing - there might be new headers after this in the batch
                    false  // Reject but continue processing batch
                } else if (!connectsToTip) {
                    // This header doesn't connect to our tip
                    // It's either an old header (from earlier in the batch) or a chain mismatch
                    // If it's old, continue processing - there might be new headers later in the batch
                    // If it's a mismatch, we should stop, but we can't distinguish easily
                    // So we'll continue processing and let validateHeader catch chain mismatches
                    false  // Reject but continue processing batch
                } else {
                    // CRITICAL: Reject headers that are ahead of known network height (matching hnsd behavior)
                    // hnsd/hsd only accept headers up to the network height to prevent being ahead
                    if (knownNetworkHeight != null && expectedHeight > knownNetworkHeight) {
                        false  // Reject but continue processing batch
                    } else {
                        // Second check: validate the header (duplicate check, etc.)
                        if (validateHeader(header, preComputedHash, headerChain, expectedHeight)) {
                            headerChain.add(header)
                            newHeadersCount++
                            localCurrentHeight++  // Update local tracker immediately
                        
                        // Add to HashSet for O(1) duplicate detection
                        synchronized(headerHashes) {
                            headerHashes.add(HashWrapper(preComputedHash))
                        }
                        
                        synchronized(headerChain) {
                            if (headerChain.size > maxInMemoryHeaders) {
                                val removeCount = headerChain.size - maxInMemoryHeaders
                                // Remove old headers from HashSet when trimming
                                synchronized(headerHashes) {
                                    for (i in 0 until removeCount) {
                                        val removedHeader = headerChain[i]
                                        headerHashes.remove(HashWrapper(removedHeader.hash()))
                                    }
                                }
                                for (i in 0 until removeCount) {
                                    headerChain.removeAt(0)
                                }
                                // Calculate new firstInMemoryHeight after trimming
                                val newFirstInMemory = localCurrentHeight - headerChain.size + 1
                            }
                        }
                        
                            onHeaderAdded(header, expectedHeight)  // Update SpvClient's chainHeight with actual height
                            true
                        } else {
                            android.util.Log.w("HNSGo", "HeaderSync: Invalid header at height $expectedHeight")
                            false
                        }
                    }
                }
            },
            MessageHandler, ProtocolHandler
        )
        
        val networkHeightFromSync = syncResult.networkHeight
        val finalHeight = getChainHeight()
        if (networkHeightFromSync != null) {
            val behind = networkHeightFromSync - finalHeight
            if (behind > 0) {
            } else if (behind < 0) {
            } else {
            }
        }
        
        when {
            syncResult.success && newHeadersCount > 0 -> {
                // IMPROVED SAVE FREQUENCY: Save more regularly to preserve progress
                // Save every N new headers during active sync, or every N headers in chain
                // This ensures progress is saved even if app is closed
                val shouldSave = newHeadersCount >= Config.HEADER_SAVE_FREQUENCY_THRESHOLD || 
                                 headerChain.size % Config.HEADER_SAVE_CHAIN_INTERVAL == 0
                if (shouldSave) {
                    saveHeaders()
                }
            }
            !syncResult.success -> {
                android.util.Log.w("HNSGo", "HeaderSync: P2P header sync failed - peers may not have headers from checkpoint")
                android.util.Log.w("HNSGo", "HeaderSync: This is normal if peers have pruned old headers")
            }
            else -> {
            }
        }
        
        return@withContext syncResult.networkHeight
    }
    
    /**
     * Continue syncing headers in background until caught up
     */
    suspend fun continueSync(
        headerChain: MutableList<Header>,
        getChainHeight: () -> Int,
        syncHeaders: suspend () -> Int?,
        saveHeaders: suspend () -> Unit
    ): Int? = withContext(Config.HEADER_SYNC_DISPATCHER) {
        var networkHeight: Int? = null
        val maxIterations = 1000
        var iteration = 0
        
        while (iteration < maxIterations) {
            iteration++
            val heightBeforeSync = getChainHeight()
            
            val syncResult = syncHeaders()
            networkHeight = syncResult ?: networkHeight
            
            val currentHeight = getChainHeight()
            val headersReceived = currentHeight > heightBeforeSync
            val newHeadersCount = currentHeight - heightBeforeSync
            
            if (networkHeight != null) {
                val behind = networkHeight - currentHeight
                // CRITICAL: Check if we're caught up (within 10 blocks behind, or at most 2 blocks ahead)
                // Allow 2 blocks ahead to account for network propagation delays (peer might report slightly stale height)
                val isCaughtUp = behind >= -2 && behind <= 10
                
                if (isCaughtUp) {
                    if (behind < 0) {
                    } else {
                    }
                    break
                }
                if (headersReceived) {
                } else {
                }
            } else {
                if (headersReceived) {
                } else {
                    if (iteration >= 5) {
                        break
                    }
                }
            }
            
            val isSynced = networkHeight != null && (networkHeight - currentHeight) <= 10
            if (isSynced && !headersReceived && iteration > 1) {
                delay(5 * 60 * 1000L)
            } else if (!headersReceived) {
                delay(2000)
            } else {
                delay(500)
            }
        }
        
        if (iteration >= maxIterations) {
            android.util.Log.w("HNSGo", "HeaderSync: Background sync reached max iterations ($maxIterations), stopping")
        }
        
        return@withContext networkHeight
    }
    
    /**
     * Validate header against chain
     * Uses HashSet for O(1) duplicate detection (matching hnsd's approach)
     * @param preComputedHash Pre-computed hash to avoid recomputation (can be null for backward compatibility)
     */
    private fun validateHeader(header: Header, preComputedHash: ByteArray, headerChain: MutableList<Header>, expectedHeight: Int): Boolean {
        val headerHash = preComputedHash // Use pre-computed hash
        val hashWrapper = HashWrapper(headerHash)
        
        val lastHeader: Header
        val lastHash: ByteArray
        
        synchronized(headerChain) {
            if (headerChain.isEmpty()) {
                return true
            }
            
            lastHeader = headerChain.last()
            lastHash = lastHeader.hash()
            
            // CRITICAL: Check chain connection FIRST (matching hnsd's behavior)
            // If prevBlock doesn't match, this is a chain mismatch, not a duplicate
            // Only check for duplicates if the header actually connects to our chain
            if (!header.prevBlock.contentEquals(lastHash)) {
                // Chain connection failure - return false immediately
                // This is different from a duplicate (duplicate means we have it, chain failure means wrong chain)
                if (expectedHeight % 100 == 0 || expectedHeight < 200) {
                    val lastHashHex = lastHash.joinToString("") { "%02x".format(it) }
                    val prevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }
                    android.util.Log.w("HNSGo", "HeaderSync: Header prevBlock mismatch at height $expectedHeight (CHAIN CONNECTION FAILURE)")
                }
                return false
            }
            
            val checkpointEndHeight = Config.CHECKPOINT_HEIGHT + 150 - 1
            if (expectedHeight == checkpointEndHeight + 1) {
                val checkpointLastHash = lastHeader.hash()
                if (!header.prevBlock.contentEquals(checkpointLastHash)) {
                    android.util.Log.e("HNSGo", "HeaderSync: CRITICAL - First post-checkpoint header doesn't match checkpoint!")
                    android.util.Log.e("HNSGo", "HeaderSync: This indicates checkpoint mismatch or chain reorganization")
                } else {
                }
            }
            
            // O(1) duplicate check using HashSet (matching hnsd's hsk_map_has(&chain->hashes, hash))
            // Only check for duplicates AFTER verifying chain connection
            synchronized(headerHashes) {
                if (headerHashes.contains(hashWrapper)) {
                    val hashHex = headerHash.joinToString("") { "%02x".format(it) }
                    return false
                }
            }
        }
        
        return true
    }
    
    /**
     * Validate height against network
     */
    fun validateHeightAgainstNetwork(chainHeight: Int, networkHeight: Int?) {
        if (networkHeight == null) return
        
        val heightDiff = chainHeight - networkHeight
        if (heightDiff > MAX_HEIGHT_DIFF) {
            android.util.Log.e("HNSGo", "HeaderSync: CRITICAL - Height mismatch detected!")
            android.util.Log.e("HNSGo", "HeaderSync: Our height: $chainHeight, Network height: $networkHeight, Difference: $heightDiff")
            android.util.Log.e("HNSGo", "HeaderSync: Header data appears corrupted. Consider clearing app data.")
        } else if (heightDiff < -MAX_HEIGHT_DIFF) {
            android.util.Log.w("HNSGo", "HeaderSync: We're significantly behind network ($heightDiff blocks) - will continue syncing")
        }
    }
}

