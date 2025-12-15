package com.acktarius.hnsgo.spvclient

import com.acktarius.hnsgo.CacheManager
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.Header
import com.acktarius.hnsgo.dohservice.DnsResolver
import com.acktarius.hnsgo.spvp2p.ConnectionManager
import com.acktarius.hnsgo.spvp2p.MessageHandler
import com.acktarius.hnsgo.spvp2p.ProtocolHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope as CoroutineScopeType
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
    
    // Mutex to prevent concurrent sync operations (race condition fix)
    private val syncMutex = Mutex()
    
    // HashSet for O(1) duplicate detection (matching hnsd's chain->hashes approach)
    private val headerHashes = Collections.synchronizedSet(HashSet<HashWrapper>())
    
    // Track last block received time and last getheaders sent time (matching hnsd's pool->block_time and pool->getheaders_time)
    // Used for periodic getheaders resend when no progress (matching hnsd pool.c:807-811)
    @Volatile
    private var lastBlockTime: Long = 0  // When we last received a block (matching hnsd's pool->block_time)
    
    @Volatile
    private var lastGetHeadersTime: Long = 0  // When we last sent getheaders (matching hnsd's pool->getheaders_time)
    
    /**
     * Reset block time tracking (called when sync starts)
     */
    fun resetBlockTime() {
        lastBlockTime = System.currentTimeMillis()
    }
    
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
        // Prevent concurrent sync operations - only one sync at a time
        syncMutex.withLock {
        
        // Track the last checkpoint height we saved at to avoid duplicate saves
        var lastCheckpointSaveHeight = (getChainHeight() / Config.HEADER_SAVE_CHECKPOINT_WINDOW) * Config.HEADER_SAVE_CHECKPOINT_WINDOW
        
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
        
        // CRITICAL FIX: Use actual tip from chain, not getChainHeight()
        // getChainHeight() might be out of sync if headers were added but chainHeight wasn't updated
        // The actual tip is the last header in the chain snapshot
        val actualTipHeight = if (headerChainSnapshot.isNotEmpty()) {
            // Calculate actual tip height from chain snapshot
            // headerChain[0] is at firstInMemoryHeight, so last header is at firstInMemoryHeight + size - 1
            val calculatedTipHeight = firstInMemoryHeight + headerChainSnapshot.size - 1
            // Use the maximum of getChainHeight() and calculated tip to handle edge cases
            maxOf(startHeight, calculatedTipHeight)
        } else {
            startHeight
        }
        
        if (headerChainSnapshot.isNotEmpty()) {
            val lastHeader = headerChainSnapshot.last()
            val lastHash = lastHeader.hash()
            val hashHex = lastHash.joinToString("") { byte -> "%02x".format(byte) }
            val hashIsZero = lastHash.all { byte -> byte == 0.toByte() }
            val prevBlockHex = lastHeader.prevBlock.take(16).joinToString("") { byte -> "%02x".format(byte) }
            val nameRootHex = lastHeader.nameRoot.take(16).joinToString("") { byte -> "%02x".format(byte) }
            val maskHex = lastHeader.mask.take(16).joinToString("") { byte -> "%02x".format(byte) }
            
            // Log if there's a mismatch between getChainHeight() and actual tip
            if (actualTipHeight != startHeight) {
                android.util.Log.w("HNSGo", "HeaderSync: Chain height mismatch - getChainHeight()=$startHeight, actual tip=$actualTipHeight (chainSize=${headerChainSnapshot.size}, firstInMemory=$firstInMemoryHeight)")
            }
        }
        
        // Build locator using actual tip height, not getChainHeight()
        val locatorHashes = ChainLocator.buildLocatorList(headerChainSnapshot, actualTipHeight, firstInMemoryHeight)
        val locatorHeightRange = if (locatorHashes.size > 1) {
            val minHeight = maxOf(firstInMemoryHeight, actualTipHeight - (locatorHashes.size - 1) * 2) // Approximate
            "$minHeight-$actualTipHeight"
        } else {
            "$actualTipHeight"
        }
        android.util.Log.d("HNSGo", "HeaderSync: Built locator with ${locatorHashes.size} hashes from height $actualTipHeight (getChainHeight=$startHeight, firstInMemory: $firstInMemoryHeight, chainSize: ${headerChainSnapshot.size}, heightRange: ~$locatorHeightRange)")
        if (locatorHashes.isNotEmpty()) {
            val firstHashHex = locatorHashes.first().take(16).joinToString("") { "%02x".format(it) }
            val lastHashHex = if (locatorHashes.size > 1) locatorHashes.last().take(16).joinToString("") { "%02x".format(it) } else "N/A"
            android.util.Log.d("HNSGo", "HeaderSync: Locator first hash (tip): $firstHashHex..., last hash: $lastHashHex...")
        }
        
        val checkpointEndHeight = Config.CHECKPOINT_HEIGHT + 150 - 1
        if (startHeight <= checkpointEndHeight) {
        }
        
        
        var newHeadersCount = 0
        var duplicateAdvanceCount = 0  // Track how many times we advanced due to duplicates
        // Track current height locally to avoid issues with getChainHeight() closure timing
        // CRITICAL: Use actual tip height, not getChainHeight() which might be out of sync
        // This ensures each header in a batch sees the correct expected height
        var localCurrentHeight = actualTipHeight
        
        val syncStartTime = System.currentTimeMillis()
        android.util.Log.d("HNSGo", "HeaderSync: Starting sync from height $actualTipHeight (getChainHeight=$startHeight, network height: $knownNetworkHeight)")
        
        // Update getheaders time when we send getheaders (matching hnsd pool.c:1299)
        lastGetHeadersTime = System.currentTimeMillis()
        
        val messageHandler = MessageHandler
        val protocolHandler = ProtocolHandler
        
        // Capture firstInMemoryHeight for use in duplicate checking closure and validation
        val capturedFirstInMemoryHeight = firstInMemoryHeight
        
        val syncResult = ConnectionManager.syncHeaders(
            actualTipHeight, locatorHashes,
            header@{ header, preComputedHash ->
                val headerProcessStartTime = System.currentTimeMillis()
                // MATCHING hnsd behavior exactly (chain.c:577-612, pool.c:1470-1502):
                // hnsd's hsk_chain_add checks for duplicates FIRST (hsk_map_has(&chain->hashes, hash))
                // BEFORE checking chain connection (prev block check)
                // This is critical: if a peer sends our current tip, we detect it as duplicate, not chain failure
                val expectedHeight = localCurrentHeight + 1
                
                // CRITICAL: Check for duplicates FIRST (matching hnsd's order)
                // BUT: If the header is not in our chain at the expected height, it might be on a different branch
                // In that case, we should allow it to be processed (not treat as duplicate) to support reorgs
                val duplicateCheckStartTime = System.currentTimeMillis()
                val isInHashSet = synchronized(headerHashes) {
                    headerHashes.contains(HashWrapper(preComputedHash))
                }
                val duplicateCheckDuration = System.currentTimeMillis() - duplicateCheckStartTime
                
                // Check if this header is actually in our chain at the expected height
                // If it's in hash set but NOT in chain at expected height, it's on a different branch
                // We should allow it to be processed (not treat as duplicate) to support chain reorgs
                val isInChainAtExpectedHeight = synchronized(headerChain) {
                    if (headerChain.isEmpty() || expectedHeight < capturedFirstInMemoryHeight) {
                        false
                    } else {
                        val expectedIndex = expectedHeight - capturedFirstInMemoryHeight
                        if (expectedIndex >= 0 && expectedIndex < headerChain.size) {
                            val chainHeaderAtHeight = headerChain[expectedIndex]
                            val chainHeaderHash = chainHeaderAtHeight.hash()
                            Arrays.equals(chainHeaderHash, preComputedHash)
                        } else {
                            false
                        }
                    }
                }
                
                // CRITICAL: Also check if header is in chain at tip (near-tip duplicate handling)
                // If we're at the tip and receive the same header, it's a benign duplicate
                val isAtTip = synchronized(headerChain) {
                    if (headerChain.isNotEmpty() && expectedHeight == localCurrentHeight + 1) {
                        val tipHeader = headerChain.last()
                        val tipHash = tipHeader.hash()
                        Arrays.equals(tipHash, preComputedHash)
                    } else {
                        false
                    }
                }
                
                // Get current chain state for logging
                val (chainBestHeight, chainBestHash) = synchronized(headerChain) {
                    if (headerChain.isEmpty()) {
                        Pair(0, ByteArray(32))
                    } else {
                        val lastHeader = headerChain.last()
                        val lastHeight = capturedFirstInMemoryHeight + headerChain.size - 1
                        Pair(lastHeight, lastHeader.hash())
                    }
                }
                
                val headerHashHex = preComputedHash.joinToString("") { "%02x".format(it) }.take(16)
                val chainBestHashHex = chainBestHash.joinToString("") { "%02x".format(it) }.take(16)
                
                // CRITICAL FIX: Only treat as duplicate if it's in hash set AND in chain at expected height
                // If it's in hash set but NOT in chain at expected height, it's on a different branch
                // We should allow it to be processed to support chain reorgs
                if (isInHashSet && (isInChainAtExpectedHeight || isAtTip)) {
                    // TRUE DUPLICATE: Header is in hash set AND in chain at expected height OR at tip
                    // This means we already have it on our main chain - advance past it
                    val duplicateType = when {
                        isInChainAtExpectedHeight -> "at expected height"
                        isAtTip -> "at tip (near-tip duplicate)"
                        else -> "unknown"
                    }
                    android.util.Log.d("HNSGo", "HeaderSync: Duplicate at height $expectedHeight ($duplicateType) - chainBestHeight=$chainBestHeight (hash=$chainBestHashHex...), localCurrentHeight=$localCurrentHeight, headerHash=$headerHashHex...")
                    
                    // This duplicate is already in our chain at this height - we're already at this height
                    // Advance localCurrentHeight to reflect this
                    if (expectedHeight > localCurrentHeight) {
                        android.util.Log.w("HNSGo", "HeaderSync: Duplicate header at height $expectedHeight is in chain - advancing from $localCurrentHeight to $expectedHeight (sync state was behind chain state)")
                        localCurrentHeight = expectedHeight  // Advance to this height
                        duplicateAdvanceCount++  // Track that we advanced due to duplicate
                        onHeaderAdded(header, expectedHeight)  // Update SpvClient's chainHeight to reflect we're at this height
                        
                        // IMPORTANT: Also add to headerChain so the locator tip updates
                        // Without this, headerChain.last() returns the same hash and the locator never changes
                        synchronized(headerChain) {
                            headerChain.add(header)
                        }
                    } else {
                        // Already at this height - this is fine, just continue
                        android.util.Log.d("HNSGo", "HeaderSync: Duplicate header at height $expectedHeight is in chain - already at this height, continuing")
                    }
                    // Return true so it counts as valid (progress, not an error)
                    // This allows the batch to continue processing and marks it as having valid headers
                    return@header true
                } else if (isInHashSet && !isInChainAtExpectedHeight) {
                    // Header is in hash set but NOT in chain at expected height
                    // This means it's on a different branch (side chain or fork)
                    // CRITICAL FIX: Don't reject it - allow it to be processed to support chain reorgs
                    val headerPrevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }.take(16)
                    val isPrevBlockAllZeros = header.prevBlock.all { it == 0.toByte() }
                    val prevBlockStatus = if (isPrevBlockAllZeros) " (ALL ZEROS - INVALID!)" else ""
                    android.util.Log.w("HNSGo", "HeaderSync: Header at height $expectedHeight is in hash set but NOT in chain at height - FORK DETECTED")
                    android.util.Log.w("HNSGo", "HeaderSync: chainBestHeight=$chainBestHeight (hash=$chainBestHashHex...), headerHash=$headerHashHex..., headerPrevBlock=$headerPrevBlockHex...$prevBlockStatus, localCurrentHeight=$localCurrentHeight")
                    
                    // Check if prevBlock exists in chain to validate fork
                    var prevBlockInChain = false
                    var prevBlockAtHeight: Int? = null
                    synchronized(headerChain) {
                        for (i in headerChain.indices) {
                            val chainHeader = headerChain[i]
                            val chainHeaderHash = chainHeader.hash()
                            if (header.prevBlock.contentEquals(chainHeaderHash)) {
                                prevBlockInChain = true
                                prevBlockAtHeight = capturedFirstInMemoryHeight + i
                                break
                            }
                        }
                    }
                    if (prevBlockInChain && prevBlockAtHeight != null) {
                        android.util.Log.w("HNSGo", "HeaderSync: Fork validation - prevBlock found at height $prevBlockAtHeight in chain")
                    } else {
                        android.util.Log.w("HNSGo", "HeaderSync: Fork validation - prevBlock NOT found in chain (will check hash set)")
                    }
                    
                    // Check if this is the next expected header
                    // If it is, we should adopt the network's chain (simple "network wins" for now)
                    if (expectedHeight == localCurrentHeight + 1) {
                        // This is the next header after our tip - adopt network's chain
                        // We'll allow it to be processed even if it doesn't connect to our tip
                        // (because we're on a fork and the network's chain is different)
                        android.util.Log.w("HNSGo", "HeaderSync: Adopting network's header at height $expectedHeight (network's chain differs - will cause reorg)")
                        // Mark that we're adopting a fork - skip the "connects to tip" check
                        // Fall through to process it, but we'll skip the tip connection check
                    } else {
                        // Not the next header - might be an old side chain header
                        android.util.Log.d("HNSGo", "HeaderSync: Header at height $expectedHeight is on different branch but not next header - allowing processing anyway")
                    }
                    // Continue to process it (will skip tip check if it's the next header)
                } else {
                    // Not in hash set - this is a completely new header
                    // Process it normally
                }
                
                // Process header (either new, or on different branch that we're adopting)
                if (!isInHashSet || !isInChainAtExpectedHeight) {
                    // Not a duplicate (or duplicate on different branch) - check if it connects to our tip
                    // EXCEPTION: If it's the next expected header and we detected a fork, skip tip check
                    val isForkAdoption = isInHashSet && !isInChainAtExpectedHeight && expectedHeight == localCurrentHeight + 1
                    
                    val tipCheckStartTime = System.currentTimeMillis()
                    val connectsToTip = if (isForkAdoption) {
                        // We're adopting network's chain - skip tip check
                        true
                    } else {
                        synchronized(headerChain) {
                            if (headerChain.isEmpty()) {
                                true  // No tip to check against
                            } else {
                                val tipHeader = headerChain.last()
                                val tipHash = tipHeader.hash()
                                header.prevBlock.contentEquals(tipHash)
                            }
                        }
                    }
                    val tipCheckDuration = System.currentTimeMillis() - tipCheckStartTime
                    
                    if (!connectsToTip && !isForkAdoption) {
                        // This header doesn't connect to our tip
                        // It's an old header from earlier in the batch (when using earlier locator hashes)
                        // Skip it - there might be new headers later in the batch
                        if (expectedHeight <= localCurrentHeight + 5) {
                            val prevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }.take(16)
                            android.util.Log.d("HNSGo", "HeaderSync: Header at height $expectedHeight rejected - doesn't connect to tip (prevBlock: $prevBlockHex...)")
                        }
                        false  // Reject but continue processing batch
                    } else {
                        if (isForkAdoption) {
                            android.util.Log.w("HNSGo", "HeaderSync: Processing fork adoption at height $expectedHeight - skipping tip connection check")
                        }
                        // Header connects to tip and is not a duplicate - validate it
                        // CRITICAL: Reject headers that are ahead of known network height (matching hnsd behavior)
                        if (knownNetworkHeight != null && expectedHeight > knownNetworkHeight) {
                            false  // Reject but continue processing batch
                        } else {
                            // Validate the header (additional checks)
                            val validateStartTime = System.currentTimeMillis()
                            val isValid = validateHeader(header, preComputedHash, headerChain, expectedHeight, isForkAdoption, firstInMemoryHeight)
                            val validateDuration = System.currentTimeMillis() - validateStartTime
                            
                            if (isValid) {
                                val addStartTime = System.currentTimeMillis()
                                
                                // Handle reorg if this is a fork adoption
                                if (isForkAdoption) {
                                    // Find where the fork branches off (prevBlock location in chain)
                                    var forkPointHeight: Int? = null
                                    var forkPointIndex: Int? = null
                                    synchronized(headerChain) {
                                        for (i in headerChain.indices) {
                                            val chainHeader = headerChain[i]
                                            val chainHeaderHash = chainHeader.hash()
                                            if (header.prevBlock.contentEquals(chainHeaderHash)) {
                                                forkPointHeight = capturedFirstInMemoryHeight + i
                                                forkPointIndex = i
                                                break
                                            }
                                        }
                                    }
                                    
                                    if (forkPointHeight != null && forkPointIndex != null) {
                                        // Found fork point in memory - check chainwork before reorging
                                        synchronized(headerChain) {
                                            // Calculate work for old branch (headers from fork point + 1 to tip)
                                            // These are the headers that will be replaced
                                            var oldBranchWork = java.math.BigInteger.ZERO
                                            val headersToRemove = headerChain.size - (forkPointIndex + 1)
                                            if (headersToRemove > 0) {
                                                for (i in (forkPointIndex + 1) until headerChain.size) {
                                                    val oldHeader = headerChain[i]
                                                    oldBranchWork = oldBranchWork.add(calculateHeaderWork(oldHeader.bits))
                                                }
                                            }
                                            
                                            // Calculate work for new branch (just the new header)
                                            val newBranchWork = calculateHeaderWork(header.bits)
                                            
                                            // Only reorg if new branch has more work (matching Bitcoin/hnsd consensus)
                                            // Since both branches share the same history up to fork point, we only compare work from fork point onwards
                                            if (newBranchWork > oldBranchWork) {
                                                // Remove headers from fork point onwards (keep fork point, remove everything after)
                                                if (headersToRemove > 0) {
                                                    // Remove from hash set
                                                    synchronized(headerHashes) {
                                                        for (i in (forkPointIndex + 1) until headerChain.size) {
                                                            val removedHeader = headerChain[i]
                                                            headerHashes.remove(HashWrapper(removedHeader.hash()))
                                                        }
                                                    }
                                                    // Remove from chain (remove from end to avoid index shifting)
                                                    for (i in 0 until headersToRemove) {
                                                        headerChain.removeAt(headerChain.size - 1)
                                                    }
                                                    
                                                    android.util.Log.w("HNSGo", "HeaderSync: Reorg performed - removed $headersToRemove headers from height ${forkPointHeight + 1} onwards (fork point at height $forkPointHeight, new branch work > old branch work)")
                                                    
                                                    // Update local height to fork point
                                                    localCurrentHeight = forkPointHeight
                                                } else {
                                                    android.util.Log.w("HNSGo", "HeaderSync: Fork adoption - no headers to remove (fork point is at tip, height=$forkPointHeight)")
                                                }
                                            } else {
                                                // New branch has less or equal work - don't reorg
                                                // This means our current chain is better, reject the fork header
                                                android.util.Log.w("HNSGo", "HeaderSync: Fork header rejected - old branch work >= new branch work (old: $oldBranchWork, new: $newBranchWork)")
                                                // Don't add the header - return false to reject it
                                                return@header false
                                            }
                                        }
                                    } else {
                                        // Fork point not found in memory - might be before firstInMemoryHeight
                                        // Check if prevBlock is in hash set (we've seen it before, just not in memory)
                                        val prevBlockInHashSet = synchronized(headerHashes) {
                                            headerHashes.contains(HashWrapper(header.prevBlock))
                                        }
                                        
                                        if (prevBlockInHashSet) {
                                            // prevBlock exists but not in memory - it's before firstInMemoryHeight
                                            // For SPV, we can't calculate full chainwork, so use "network wins" heuristic
                                            // Accept the fork header - the chain will naturally reorg as we sync more headers
                                            android.util.Log.w("HNSGo", "HeaderSync: Fork adoption - prevBlock in hash set but not in memory (before firstInMemoryHeight=$capturedFirstInMemoryHeight), accepting header (reorg will happen naturally)")
                                        } else {
                                            // This shouldn't happen if validateHeader worked correctly
                                            android.util.Log.e("HNSGo", "HeaderSync: Fork adoption - prevBlock not found in chain or hash set (this should have been rejected in validateHeader)")
                                            return@header false
                                        }
                                    }
                                }
                                
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
                                    
                                    // Update last block time when we receive a header (matching hnsd pool.c:1516)
                                    lastBlockTime = System.currentTimeMillis()

                                    // CRITICAL: Check for checkpoint save immediately after adding header
                                // This ensures we save at exact checkpoint boundaries (2000, 4000, 6000, etc.)
                                // MATCHING hnsd: Save when chain height is a multiple of CHECKPOINT_WINDOW (chain.c:750)
                                if (expectedHeight % Config.HEADER_SAVE_CHECKPOINT_WINDOW == 0 && expectedHeight > lastCheckpointSaveHeight) {
                                    lastCheckpointSaveHeight = expectedHeight
                                    android.util.Log.d("HNSGo", "HeaderSync: Checkpoint boundary reached at height $expectedHeight - triggering save")
                                    // Launch coroutine to call suspend function (we're already in a coroutine context via withContext)
                                    CoroutineScope(Config.HEADER_SYNC_DISPATCHER).launch {
                                        try {
                                            saveHeaders()
                                            android.util.Log.d("HNSGo", "HeaderSync: Successfully saved checkpoint at height $expectedHeight")
                                        } catch (e: Exception) {
                                            android.util.Log.e("HNSGo", "HeaderSync: Failed to save checkpoint at height $expectedHeight", e)
                                        }
                                    }
                                }
                                
                                val totalProcessDuration = System.currentTimeMillis() - headerProcessStartTime
                                
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
                } else {
                    // This case should never happen - if isInHashSet && isInChainAtExpectedHeight,
                    // we already returned true earlier. But compiler needs this for type safety.
                    android.util.Log.e("HNSGo", "HeaderSync: UNEXPECTED - reached else branch that should be unreachable")
                    true
                }
            },
            messageHandler,
            protocolHandler
        )
        val syncDuration = System.currentTimeMillis() - syncStartTime
        
        val networkHeightFromSync = syncResult.networkHeight
        val finalHeight = getChainHeight()
        
        android.util.Log.i("HNSGo", "HeaderSync: Sync completed in ${syncDuration}ms - " +
            "Height: $startHeight -> $finalHeight (+$newHeadersCount new, +$duplicateAdvanceCount duplicates), " +
            "Success: ${syncResult.success}, Network height: $networkHeightFromSync")
        
        // If we only got duplicates (no new headers) but advanced, we're stuck getting headers we already have
        if (newHeadersCount == 0 && duplicateAdvanceCount > 0) {
            // Check if the tip hash actually changed (if not, we're just recognizing same header at different height)
            val currentTipHash = synchronized(headerChain) {
                if (headerChain.isNotEmpty()) {
                    headerChain.last().hash()
                } else {
                    null
                }
            }
            val startTipHash = if (headerChainSnapshot.isNotEmpty()) {
                headerChainSnapshot.last().hash()
            } else {
                null
            }
            
            val tipHashChanged = currentTipHash != null && startTipHash != null && 
                !Arrays.equals(currentTipHash, startTipHash)
            
            android.util.Log.w("HNSGo", "HeaderSync: Only got duplicates (advanced $duplicateAdvanceCount times) - peer is sending headers we already have")
            if (!tipHashChanged) {
                android.util.Log.w("HNSGo", "HeaderSync: Tip hash unchanged - we're recognizing same header at different height, not getting new headers")
                android.util.Log.w("HNSGo", "HeaderSync: This suggests peer is sending headers we already have - may need to try different peer or wait")
            } else {
                android.util.Log.w("HNSGo", "HeaderSync: Tip hash changed - next iteration should use new tip hash")
            }
            android.util.Log.w("HNSGo", "HeaderSync: Next iteration will use updated tip (height $finalHeight) - should get new headers")
        }
        
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
        
        // Check for checkpoint save regardless of sync success/failure
        // MATCHING hnsd: Save when chain height is a multiple of CHECKPOINT_WINDOW (chain.c:750)
        // hnsd: if (chain->height % HSK_STORE_CHECKPOINT_WINDOW == 0) hsk_chain_checkpoint_flush(chain)
        val currentHeight = getChainHeight()
        val shouldSaveCheckpoint = currentHeight % Config.HEADER_SAVE_CHECKPOINT_WINDOW == 0
        android.util.Log.d("HNSGo", "HeaderSync: End of sync batch - currentHeight=$currentHeight, shouldSaveCheckpoint=$shouldSaveCheckpoint (mod ${Config.HEADER_SAVE_CHECKPOINT_WINDOW} = ${currentHeight % Config.HEADER_SAVE_CHECKPOINT_WINDOW})")
        
        when {
            syncResult.success && newHeadersCount > 0 -> {
                if (shouldSaveCheckpoint) {
                    android.util.Log.d("HNSGo", "HeaderSync: Checkpoint save triggered at height $currentHeight (mod ${Config.HEADER_SAVE_CHECKPOINT_WINDOW} == 0)")
                    val saveStartTime = System.currentTimeMillis()
                    try {
                        saveHeaders()
                        val saveDuration = System.currentTimeMillis() - saveStartTime
                        android.util.Log.d("HNSGo", "HeaderSync: Successfully saved headers at height $currentHeight in ${saveDuration}ms")
                    } catch (e: Exception) {
                        android.util.Log.e("HNSGo", "HeaderSync: Failed to save headers at height $currentHeight", e)
                    }
                } else {
                    val nextSaveAt = ((currentHeight / Config.HEADER_SAVE_CHECKPOINT_WINDOW) + 1) * Config.HEADER_SAVE_CHECKPOINT_WINDOW
                    val blocksUntilSave = nextSaveAt - currentHeight
                    if (blocksUntilSave <= 10) {
                        android.util.Log.d("HNSGo", "HeaderSync: Height $currentHeight, next save at $nextSaveAt (in $blocksUntilSave blocks)")
                    }
                }
            }
            !syncResult.success -> {
                // Always check for checkpoint save on failure too
                if (shouldSaveCheckpoint) {
                    android.util.Log.d("HNSGo", "HeaderSync: Checkpoint save triggered at height $currentHeight after sync failure (mod ${Config.HEADER_SAVE_CHECKPOINT_WINDOW} == 0)")
                    try {
                        saveHeaders()
                        android.util.Log.d("HNSGo", "HeaderSync: Successfully saved checkpoint at height $currentHeight after sync failure")
                    } catch (e: Exception) {
                        android.util.Log.e("HNSGo", "HeaderSync: Failed to save checkpoint at height $currentHeight after sync failure", e)
                    }
                } else if (currentHeight > 0) {
                    // Only save on failure if not a checkpoint (to avoid excessive saves)
                    // Checkpoint saves are more important and will happen above
                    android.util.Log.w("HNSGo", "HeaderSync: P2P header sync failed - not saving (not checkpoint boundary, height=$currentHeight)")
                }
                android.util.Log.w("HNSGo", "HeaderSync: P2P header sync failed - peers may not have headers from checkpoint")
                android.util.Log.w("HNSGo", "HeaderSync: This is normal if peers have pruned old headers")
            }
            else -> {
                // Check for checkpoint save even when no new headers received
                if (shouldSaveCheckpoint) {
                    android.util.Log.d("HNSGo", "HeaderSync: Checkpoint save triggered at height $currentHeight (no new headers but checkpoint boundary)")
                    try {
                        saveHeaders()
                        android.util.Log.d("HNSGo", "HeaderSync: Successfully saved checkpoint at height $currentHeight")
                    } catch (e: Exception) {
                        android.util.Log.e("HNSGo", "HeaderSync: Failed to save checkpoint at height $currentHeight", e)
                    }
                } else {
                    android.util.Log.d("HNSGo", "HeaderSync: Sync succeeded but no new headers received")
                }
            }
        }
        
        syncResult.networkHeight
        }  // End of syncMutex.withLock
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
        var lastCacheCleanupHeight = getChainHeight()
        var consecutiveDuplicateOnlyIterations = 0  // Track consecutive iterations with only duplicates
        
        android.util.Log.i("HNSGo", "HeaderSync: Starting background sync loop")
        
        // Initialize block time when sync starts (matching hnsd pool.c:174)
        resetBlockTime()
        
        while (iteration < maxIterations) {
            // Check if coroutine was cancelled (e.g., user turned off toggle)
            ensureActive()
            iteration++
            val iterationStartTime = System.currentTimeMillis()
            val heightBeforeSync = getChainHeight()
            
            android.util.Log.d("HNSGo", "HeaderSync: Iteration $iteration - Current height: $heightBeforeSync, Network height: $networkHeight")
            
            // MATCHING hnsd pool.c:807-811: Periodic getheaders resend if no progress
            // If no block received in 10 minutes, and no getheaders sent in 5 minutes, resend getheaders
            val now = System.currentTimeMillis()
            val blockTime = lastBlockTime
            val getHeadersTime = lastGetHeadersTime
            
            if (blockTime > 0 && now > blockTime + Config.P2P_BLOCK_TIMEOUT_MS) {
                if (getHeadersTime == 0L || now > getHeadersTime + Config.P2P_GETHEADERS_RESEND_TIMEOUT_MS) {
                    android.util.Log.w("HNSGo", "HeaderSync: No progress in 10 minutes, resending getheaders (matching hnsd pool.c:807-811)")
                    // Force a resend by updating lastGetHeadersTime - syncHeaders() will be called below
                    // This ensures we resend getheaders to all peers in the next sync attempt
                    lastGetHeadersTime = now
                }
            }
            
            val syncCallStartTime = System.currentTimeMillis()
            android.util.Log.d("HNSGo", "HeaderSync: Calling syncHeaders() for iteration $iteration...")
            val syncResult = syncHeaders()
            val syncCallDuration = System.currentTimeMillis() - syncCallStartTime
            android.util.Log.d("HNSGo", "HeaderSync: syncHeaders() returned after ${syncCallDuration}ms - networkHeight=${syncResult}")
            networkHeight = syncResult ?: networkHeight
            
            val currentHeight = getChainHeight()
            val headersReceived = currentHeight > heightBeforeSync
            val newHeadersCount = currentHeight - heightBeforeSync
            val iterationDuration = System.currentTimeMillis() - iterationStartTime
            
            // Check if tip hash changed to detect if we actually added new headers vs only got duplicates
            val currentTipHash = synchronized(headerChain) {
                if (headerChain.isNotEmpty()) {
                    headerChain.last().hash()
                } else {
                    null
                }
            }
            val previousTipHash = if (iteration > 1) {
                // Get tip hash from previous iteration (stored in a variable)
                // For now, we'll track this by checking if height advanced but tip hash didn't change
                null  // Will be set below
            } else {
                null
            }
            
            // Track consecutive iterations with only duplicates (no actual new headers)
            // If we only get duplicates, we're recognizing headers we already have, not getting new ones
            // We can detect this by checking if height advanced but tip hash didn't change
            // (This is a heuristic - if tip hash changes, we definitely added new headers)
            if (headersReceived && currentTipHash != null) {
                // Check if this is the same tip hash as before (stored from previous iteration)
                // For simplicity, track if we're stuck: if height keeps advancing but we're not catching up to network
                if (networkHeight != null && (networkHeight - currentHeight) >= 300) {
                    // We're still far behind - if we only advanced by 1 and tip hash might not have changed, we might be stuck
                    // Add a small delay every 10 iterations to allow peer rotation
                    if (iteration % 10 == 0) {
                        android.util.Log.d("HNSGo", "HeaderSync: Still far behind network (${networkHeight - currentHeight} blocks) - adding small delay for peer rotation")
                        delay(500)
                    }
                }
            }
            
            // Check if we've advanced 36 blocks since last cache cleanup
            val blocksSinceCleanup = currentHeight - lastCacheCleanupHeight
            if (blocksSinceCleanup >= Config.TREE_INTERVAL) {
                // Run cache cleanup every 36 blocks (async, non-blocking)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        CacheManager.cleanupExpiredEntries(currentHeight) { name, type, dclass ->
                            // Prefetch callback: resolve the name to refresh cache
                            // Use DnsResolver to trigger resolution (will update cache)
                            DnsResolver.resolve(name, type, dclass, 0, null)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("HNSGo", "HeaderSync: Cache cleanup failed: ${e.message}")
                    }
                }
                lastCacheCleanupHeight = currentHeight
            }
            
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
     * Calculate chainwork for a header branch
     * Chainwork is cumulative proof-of-work: sum of (2^256 / (target + 1)) for each header
     * For SPV, we calculate work for headers in memory and estimate for earlier headers
     * @param headerChain The chain to calculate work for
     * @param tipHeight The height of the tip header
     * @param firstInMemoryHeight First height in memory
     * @return Chainwork as BigInteger (for comparison)
     */
    private fun calculateChainwork(headerChain: List<Header>, tipHeight: Int, firstInMemoryHeight: Int): java.math.BigInteger {
        // For SPV clients, we only have recent headers in memory
        // Calculate work for headers we have, and estimate for earlier headers based on average difficulty
        var totalWork = java.math.BigInteger.ZERO
        
        // Calculate work for each header in memory
        // Work = 2^256 / (target + 1)
        // Target is calculated from bits field (compact representation)
        synchronized(headerChain) {
            for (i in headerChain.indices) {
                val header = headerChain[i]
                val height = firstInMemoryHeight + i
                val work = calculateHeaderWork(header.bits)
                totalWork = totalWork.add(work)
            }
        }
        
        // For headers before firstInMemoryHeight, estimate work based on average difficulty
        // This is an approximation, but sufficient for SPV fork comparison
        if (firstInMemoryHeight > 0 && headerChain.isNotEmpty()) {
            // Calculate average work per header from in-memory headers
            val avgWork = if (headerChain.isNotEmpty()) {
                totalWork.divide(java.math.BigInteger.valueOf(headerChain.size.toLong()))
            } else {
                java.math.BigInteger.ZERO
            }
            
            // Estimate work for earlier headers
            val estimatedWork = avgWork.multiply(java.math.BigInteger.valueOf(firstInMemoryHeight.toLong()))
            totalWork = totalWork.add(estimatedWork)
        }
        
        return totalWork
    }
    
    /**
     * Calculate proof-of-work for a single header
     * Work = 2^256 / (target + 1)
     * Target is calculated from bits (compact representation, matching Bitcoin/hnsd)
     */
    private fun calculateHeaderWork(bits: Int): java.math.BigInteger {
        // Extract mantissa and exponent from compact representation
        val mantissa = bits and 0x007fffff
        val exponent = (bits shr 24) and 0xff
        
        if (exponent <= 3) {
            // Target = mantissa >> (8 * (3 - exponent))
            val shift = 8 * (3 - exponent)
            val target = java.math.BigInteger.valueOf(mantissa.toLong()).shiftRight(shift)
            // Work = 2^256 / (target + 1)
            val two256 = java.math.BigInteger.ONE.shiftLeft(256)
            return two256.divide(target.add(java.math.BigInteger.ONE))
        } else {
            // Target = mantissa << (8 * (exponent - 3))
            val shift = 8 * (exponent - 3)
            val target = java.math.BigInteger.valueOf(mantissa.toLong()).shiftLeft(shift)
            // Work = 2^256 / (target + 1)
            val two256 = java.math.BigInteger.ONE.shiftLeft(256)
            return two256.divide(target.add(java.math.BigInteger.ONE))
        }
    }
    
    /**
     * Validate header against chain
     * Uses HashSet for O(1) duplicate detection (matching hnsd's approach)
     * @param preComputedHash Pre-computed hash to avoid recomputation (can be null for backward compatibility)
     * @param isForkAdoption If true, we're adopting a fork - check if prevBlock exists anywhere, not just at tip
     * @param firstInMemoryHeight First height in memory (for searching chain)
     */
    private fun validateHeader(header: Header, preComputedHash: ByteArray, headerChain: MutableList<Header>, expectedHeight: Int, isForkAdoption: Boolean = false, firstInMemoryHeight: Int = 0): Boolean {
        // NOTE: Duplicate check is now done in onHeaderReceived BEFORE calling validateHeader
        // This matches hnsd's behavior where hsk_chain_add checks duplicates first
        // validateHeader now only does additional validation (chain connection was already checked)
        
        synchronized(headerChain) {
            if (headerChain.isEmpty()) {
                return true
            }
            
            val lastHeader = headerChain.last()
            val lastHash = lastHeader.hash()
            
            if (isForkAdoption) {
                // FORK ADOPTION MODE: Check if prevBlock exists anywhere in the chain
                // This is the correct behavior for forks - the header's parent may not be our current tip
                // but it should exist somewhere in our chain (or in the hash set if we've seen it before)
                
                // Validate prevBlock is not all zeros (except for genesis block at height 0)
                val isPrevBlockAllZeros = header.prevBlock.all { it == 0.toByte() }
                if (isPrevBlockAllZeros && expectedHeight > 0) {
                    val prevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }.take(16)
                    android.util.Log.w("HNSGo", "HeaderSync: Fork header at height $expectedHeight rejected - prevBlock is all zeros (invalid, prevBlock: $prevBlockHex...)")
                    return false
                }
                
                // First, check if prevBlock matches any header in our chain
                var prevBlockFound = false
                var prevBlockHeight: Int? = null
                
                // Search through the chain to find prevBlock
                for (i in headerChain.indices) {
                    val chainHeader = headerChain[i]
                    val chainHeaderHash = chainHeader.hash()
                    if (header.prevBlock.contentEquals(chainHeaderHash)) {
                        prevBlockFound = true
                        prevBlockHeight = firstInMemoryHeight + i
                        break
                    }
                }
                
                // Also check hash set (might be in a side chain we've seen but not stored)
                if (!prevBlockFound) {
                    synchronized(headerHashes) {
                        // Check if prevBlock hash is in our hash set
                        // This means we've seen this header before (even if not in main chain)
                        prevBlockFound = headerHashes.contains(HashWrapper(header.prevBlock))
                    }
                }
                
                // Special case: if prevBlock is all zeros and expectedHeight is 0, it's the genesis block
                if (!prevBlockFound && isPrevBlockAllZeros && expectedHeight == 0) {
                    prevBlockFound = true
                    prevBlockHeight = -1  // Genesis has no parent
                }
                
                if (!prevBlockFound) {
                    // prevBlock doesn't exist in our chain or hash set
                    // This means the fork header's parent is unknown - reject it
                    val prevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }.take(16)
                    android.util.Log.w("HNSGo", "HeaderSync: Fork header at height $expectedHeight rejected - prevBlock not found in chain (prevBlock: $prevBlockHex...)")
                    return false
                }
                
                // prevBlock exists - this is a valid fork header
                // Log the fork adoption details
                val prevBlockHex = header.prevBlock.joinToString("") { "%02x".format(it) }.take(16)
                val tipHashHex = lastHash.joinToString("") { "%02x".format(it) }.take(16)
                if (prevBlockHeight != null && prevBlockHeight >= 0) {
                    android.util.Log.w("HNSGo", "HeaderSync: Fork adoption validated - header at height $expectedHeight connects to height $prevBlockHeight (prevBlock: $prevBlockHex..., tip: $tipHashHex...)")
                } else if (prevBlockHeight == -1) {
                    android.util.Log.w("HNSGo", "HeaderSync: Fork adoption validated - header at height $expectedHeight is genesis block (prevBlock: $prevBlockHex..., tip: $tipHashHex...)")
                } else {
                    android.util.Log.w("HNSGo", "HeaderSync: Fork adoption validated - header at height $expectedHeight connects to known header (prevBlock: $prevBlockHex..., tip: $tipHashHex...)")
                }
                
                // Chainwork-based fork selection: only accept fork if it has more work
                // This is the proper way to choose between branches (matching Bitcoin/hnsd)
                return true  // Will check chainwork before reorging
            } else {
                // NORMAL MODE: Check chain connection (matching hnsd's prev block check)
                // If prevBlock doesn't match tip, this is a chain mismatch
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

