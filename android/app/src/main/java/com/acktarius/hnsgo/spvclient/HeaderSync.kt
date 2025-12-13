package com.acktarius.hnsgo.spvclient

import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.Header
import com.acktarius.hnsgo.spvp2p.ConnectionManager
import com.acktarius.hnsgo.spvp2p.MessageHandler
import com.acktarius.hnsgo.spvp2p.ProtocolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
        val locatorHeightRange = if (locatorHashes.size > 1) {
            val minHeight = maxOf(firstInMemoryHeight, startHeight - (locatorHashes.size - 1) * 2) // Approximate
            "$minHeight-$startHeight"
        } else {
            "$startHeight"
        }
        android.util.Log.d("HNSGo", "HeaderSync: Built locator with ${locatorHashes.size} hashes from height $startHeight (firstInMemory: $firstInMemoryHeight, chainSize: ${headerChainSnapshot.size}, heightRange: ~$locatorHeightRange)")
        if (locatorHashes.isNotEmpty()) {
            val firstHashHex = locatorHashes.first().take(16).joinToString("") { "%02x".format(it) }
            val lastHashHex = if (locatorHashes.size > 1) locatorHashes.last().take(16).joinToString("") { "%02x".format(it) } else "N/A"
            android.util.Log.d("HNSGo", "HeaderSync: Locator first hash (tip): $firstHashHex..., last hash: $lastHashHex...")
        }
        
        val checkpointEndHeight = Config.CHECKPOINT_HEIGHT + 150 - 1
        if (startHeight <= checkpointEndHeight) {
        }
        
        
        var newHeadersCount = 0
        // Track current height locally to avoid issues with getChainHeight() closure timing
        // This ensures each header in a batch sees the correct expected height
        var localCurrentHeight = startHeight
        
        val syncStartTime = System.currentTimeMillis()
        android.util.Log.d("HNSGo", "HeaderSync: Starting sync from height $startHeight (network height: $knownNetworkHeight)")
        
        val syncResult = ConnectionManager.syncHeaders(
            startHeight, locatorHashes,
            { header, preComputedHash ->
                val headerProcessStartTime = System.currentTimeMillis()
                // CRITICAL: Only accept headers that connect to our tip (new headers)
                // When using earlier locator hashes, peers send headers starting from those points
                // We need to reject headers that don't connect to our tip (they're old headers)
                val expectedHeight = localCurrentHeight + 1
                
                // CRITICAL FIX: When requesting from earlier locator hashes, peers return headers
                // starting from that point. We need to:
                // 1. Skip headers that match our current tip (duplicates)
                // 2. Skip headers that are before our tip (old headers)
                // 3. Only accept headers that connect to our tip (new headers after our tip)
                
                val tipCheckStartTime = System.currentTimeMillis()
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
                val tipCheckDuration = System.currentTimeMillis() - tipCheckStartTime
                
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
                        val validateStartTime = System.currentTimeMillis()
                        val isValid = validateHeader(header, preComputedHash, headerChain, expectedHeight)
                        val validateDuration = System.currentTimeMillis() - validateStartTime
                        
                        if (isValid) {
                            val addStartTime = System.currentTimeMillis()
                            headerChain.add(header)
                            newHeadersCount++
                            localCurrentHeight++  // Update local tracker immediately
                        
                            // Add to HashSet for O(1) duplicate detection
                            synchronized(headerHashes) {
                                headerHashes.add(HashWrapper(preComputedHash))
                            }
                            
                            synchronized(headerChain) {
                                if (headerChain.size > maxInMemoryHeaders) {
                                    val trimStartTime = System.currentTimeMillis()
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
                                    val trimDuration = System.currentTimeMillis() - trimStartTime
                                    if (trimDuration > 10) {
                                        android.util.Log.d("HNSGo", "HeaderSync: Trimmed $removeCount headers in ${trimDuration}ms")
                                    }
                                }
                            }
                            
                            val addDuration = System.currentTimeMillis() - addStartTime
                            onHeaderAdded(header, expectedHeight)  // Update SpvClient's chainHeight with actual height
                            
                            val totalProcessDuration = System.currentTimeMillis() - headerProcessStartTime
                            if (totalProcessDuration > 5 || validateDuration > 3) {
                                android.util.Log.v("HNSGo", "HeaderSync: Header $expectedHeight processed in ${totalProcessDuration}ms " +
                                    "(tip check: ${tipCheckDuration}ms, validate: ${validateDuration}ms, add: ${addDuration}ms)")
                            }
                            
                            true
                        } else {
                            // Log why header was rejected for debugging
                            val lastHeader = synchronized(headerChain) { headerChain.lastOrNull() }
                            val lastHash = lastHeader?.hash()
                            val isChainMismatch = lastHash != null && !header.prevBlock.contentEquals(lastHash)
                            val isDuplicate = synchronized(headerHashes) { 
                                headerHashes.contains(HashWrapper(preComputedHash)) 
                            }
                            val reason = when {
                                isChainMismatch -> "chain connection failure (prevBlock mismatch)"
                                isDuplicate -> "duplicate (already in chain)"
                                else -> "unknown validation failure"
                            }
                            android.util.Log.w("HNSGo", "HeaderSync: Invalid header at height $expectedHeight - $reason (validation took ${validateDuration}ms)")
                            false
                        }
                    }
                }
            },
            MessageHandler, ProtocolHandler
        )
        val syncDuration = System.currentTimeMillis() - syncStartTime
        
        val networkHeightFromSync = syncResult.networkHeight
        val finalHeight = getChainHeight()
        
        android.util.Log.i("HNSGo", "HeaderSync: Sync completed in ${syncDuration}ms - " +
            "Height: $startHeight -> $finalHeight (+$newHeadersCount), " +
            "Success: ${syncResult.success}, Network height: $networkHeightFromSync")
        
        if (networkHeightFromSync != null) {
            val behind = networkHeightFromSync - finalHeight
            if (behind > 0) {
                android.util.Log.d("HNSGo", "HeaderSync: Behind network by $behind blocks")
            } else if (behind < 0) {
                android.util.Log.d("HNSGo", "HeaderSync: Ahead of network by ${-behind} blocks")
            } else {
                android.util.Log.d("HNSGo", "HeaderSync: At network height")
            }
        }
        
        when {
            syncResult.success && newHeadersCount > 0 -> {
                // MATCHING hnsd: Save when chain height is a multiple of CHECKPOINT_WINDOW (chain.c:750)
                // hnsd: if (chain->height % HSK_STORE_CHECKPOINT_WINDOW == 0) hsk_chain_checkpoint_flush(chain)
                val currentHeight = getChainHeight()
                val shouldSave = currentHeight % Config.HEADER_SAVE_CHECKPOINT_WINDOW == 0
                if (shouldSave) {
                    val saveStartTime = System.currentTimeMillis()
                    saveHeaders()
                    val saveDuration = System.currentTimeMillis() - saveStartTime
                    android.util.Log.d("HNSGo", "HeaderSync: Saved headers at height $currentHeight in ${saveDuration}ms")
                }
            }
            !syncResult.success -> {
                android.util.Log.w("HNSGo", "HeaderSync: P2P header sync failed - peers may not have headers from checkpoint")
                android.util.Log.w("HNSGo", "HeaderSync: This is normal if peers have pruned old headers")
            }
            else -> {
                android.util.Log.d("HNSGo", "HeaderSync: Sync succeeded but no new headers received")
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
        val syncStartTime = System.currentTimeMillis()
        
        android.util.Log.i("HNSGo", "HeaderSync: Starting background sync loop")
        
        while (iteration < maxIterations) {
            // Check if coroutine was cancelled (e.g., user turned off toggle)
            ensureActive()
            iteration++
            val iterationStartTime = System.currentTimeMillis()
            val heightBeforeSync = getChainHeight()
            
            android.util.Log.d("HNSGo", "HeaderSync: Iteration $iteration - Current height: $heightBeforeSync, Network height: $networkHeight")
            
            val syncCallStartTime = System.currentTimeMillis()
            val syncResult = syncHeaders()
            val syncCallDuration = System.currentTimeMillis() - syncCallStartTime
            networkHeight = syncResult ?: networkHeight
            
            val currentHeight = getChainHeight()
            val headersReceived = currentHeight > heightBeforeSync
            val newHeadersCount = currentHeight - heightBeforeSync
            val iterationDuration = System.currentTimeMillis() - iterationStartTime
            
            if (networkHeight != null) {
                val behind = networkHeight - currentHeight
                // CRITICAL: Check if we're caught up (within 10 blocks behind, or at most 2 blocks ahead)
                // Allow 2 blocks ahead to account for network propagation delays (peer might report slightly stale height)
                val isCaughtUp = behind >= -2 && behind <= 10
                
                android.util.Log.i("HNSGo", "HeaderSync: Iteration $iteration completed in ${iterationDuration}ms - " +
                    "Height: $heightBeforeSync -> $currentHeight (+$newHeadersCount), " +
                    "Network: $networkHeight (behind: $behind), " +
                    "Sync call: ${syncCallDuration}ms")
                
                if (isCaughtUp) {
                    if (behind < 0) {
                        android.util.Log.i("HNSGo", "HeaderSync: Caught up! (ahead by ${-behind} blocks)")
                    } else {
                        android.util.Log.i("HNSGo", "HeaderSync: Caught up! (behind by $behind blocks)")
                    }
                    break
                }
                if (headersReceived) {
                    android.util.Log.d("HNSGo", "HeaderSync: Received $newHeadersCount headers, continuing sync (behind: $behind)")
                } else {
                    android.util.Log.d("HNSGo", "HeaderSync: No new headers received (behind: $behind)")
                }
            } else {
                if (headersReceived) {
                    android.util.Log.d("HNSGo", "HeaderSync: Received $newHeadersCount headers, network height unknown")
                } else {
                    android.util.Log.d("HNSGo", "HeaderSync: No headers received, network height unknown (iteration $iteration)")
                    if (iteration >= 5) {
                        android.util.Log.w("HNSGo", "HeaderSync: No headers received after 5 iterations, stopping")
                        break
                    }
                }
            }
            
            val isSynced = networkHeight != null && (networkHeight - currentHeight) <= 10
            val delayMs = if (isSynced && !headersReceived && iteration > 1) {
                5 * 60 * 1000L
            } else if (!headersReceived) {
                2000L
            } else {
                500L
            }
            
            if (delayMs > 1000) {
                android.util.Log.d("HNSGo", "HeaderSync: Delaying ${delayMs}ms before next iteration")
            }
            // Check for cancellation before delay
            ensureActive()
            delay(delayMs)
        }
        
        val totalSyncDuration = System.currentTimeMillis() - syncStartTime
        if (iteration >= maxIterations) {
            android.util.Log.w("HNSGo", "HeaderSync: Background sync reached max iterations ($maxIterations) after ${totalSyncDuration}ms, stopping")
        } else {
            android.util.Log.i("HNSGo", "HeaderSync: Background sync completed after $iteration iterations in ${totalSyncDuration}ms")
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

