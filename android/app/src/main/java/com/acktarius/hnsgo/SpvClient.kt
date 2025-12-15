package com.acktarius.hnsgo

import com.acktarius.hnsgo.spvclient.HeaderStorage
import com.acktarius.hnsgo.spvclient.HeaderSync
import com.acktarius.hnsgo.spvclient.NameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/*
 * Portions of this file are based on Impervious fingertip project
 * (https://github.com/imperviousai/fingertip), specifically the DNSSEC
 * verification logic in internal/resolvers/dnssec/dnssec.go
 *
 * Copyright (c) Impervious AI
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 * Main SPV client facade
 * Delegates to specialized modules for header sync, storage, and name resolution
 */
object SpvClient {
    private var resolverHost: String = Config.DEBUG_RESOLVER_HOST
    private var resolverPort: Int = Config.DEBUG_RESOLVER_PORT
    
    private lateinit var dataDir: File
    private val MAX_IN_MEMORY_HEADERS = 5000
    private val headerChain = Collections.synchronizedList(mutableListOf<Header>())
    private var chainHeight: Int = 0
    private var firstInMemoryHeight: Int = 0
    private var lastNetworkHeight: Int? = null
    private var lastSavedHeight: Int = 0  // Track the last height that was actually saved to disk
    
    @Volatile
    private var initialized = false  // Prevent multiple init() calls
    
    /**
     * DEVELOPMENT ONLY: Configure external Handshake resolver for testing/comparison
     */
    fun setResolver(host: String, port: Int = Config.DEBUG_RESOLVER_PORT) {
        resolverHost = host
        resolverPort = port
    }
    
    /**
     * Get current chain height (for checking sync status)
     */
    fun getChainHeight(): Int = chainHeight
    
    /**
     * Get network height (from last sync)
     */
    fun getNetworkHeight(): Int? = lastNetworkHeight
    
    private suspend fun loadCheckpointBootstrap() {
        val checkpointHeight = Checkpoint.loadCheckpoint(headerChain, chainHeight)
        chainHeight = checkpointHeight
        if (headerChain.isNotEmpty()) {
            
            val tipHeader = headerChain.lastOrNull()
            if (tipHeader != null) {
                val tipHash = tipHeader.hash()
                val isAllZeros = tipHash.all { it == 0.toByte() }
                
                if (isAllZeros) {
                    android.util.Log.e("HNSGo", "SpvClient: CRITICAL - Tip hash is ZERO! Hash calculation may be broken")
                }
            }
            
            HeaderStorage.saveHeaderChain(dataDir, headerChain, chainHeight, lastSavedHeight, force = true)
            lastSavedHeight = chainHeight  // Update after checkpoint bootstrap save
        } else {
            android.util.Log.w("HNSGo", "SpvClient: Checkpoint bootstrap failed - no headers loaded")
            android.util.Log.w("HNSGo", "SpvClient: Cannot sync from genesis (block 0) - checkpoint required for bootstrap")
        }
    }
    
    private suspend fun syncAndValidateHeaders() {
        val networkHeight = syncHeaders()
        if (networkHeight != null) {
            HeaderSync.validateHeightAgainstNetwork(chainHeight, networkHeight)
        }
    }
    
    private suspend fun syncHeaders(): Int? = withContext(Config.HEADER_SYNC_DISPATCHER) {
        // Calculate firstInMemoryHeight dynamically from current chain state
        // This handles the case where headers were trimmed (firstInMemoryHeight becomes stale)
        // Formula: firstInMemoryHeight = chainHeight - headerChain.size + 1
        // But we need to ensure it's at least checkpointHeight
        val calculatedFirstInMemory = synchronized(headerChain) {
            if (headerChain.isEmpty()) {
                Config.CHECKPOINT_HEIGHT
            } else {
                maxOf(Config.CHECKPOINT_HEIGHT, chainHeight - headerChain.size + 1)
            }
        }
        
        // Update firstInMemoryHeight if it changed (headers were trimmed)
        if (calculatedFirstInMemory != firstInMemoryHeight) {
            firstInMemoryHeight = calculatedFirstInMemory
        }
        
        // Pass known network height to prevent accepting headers ahead of network (matching hnsd behavior)
        // hnsd/hsd reject headers that are ahead of the known network height
        val networkHeight = HeaderSync.syncHeaders(
            headerChain = headerChain,
            getChainHeight = { chainHeight },
            firstInMemoryHeight = firstInMemoryHeight,
            maxInMemoryHeaders = MAX_IN_MEMORY_HEADERS,
            onHeaderAdded = { _, actualHeight ->
                // CRITICAL: Set chainHeight to the actual height of the header, not just increment
                // This ensures we report the correct height to peers (not ahead of network)
                chainHeight = actualHeight
            },
            saveHeaders = suspend {
                // Calculate current firstInMemoryHeight dynamically (may have changed due to trimming)
                val currentFirstInMemory = synchronized(headerChain) {
                    if (headerChain.isEmpty()) {
                        Config.CHECKPOINT_HEIGHT
                    } else {
                        maxOf(Config.CHECKPOINT_HEIGHT, chainHeight - headerChain.size + 1)
                    }
                }
                // Force save on checkpoint boundaries to ensure progress is saved
                // Check if we're at a checkpoint boundary OR if lastSavedHeight is behind the current checkpoint boundary
                // This handles the case where checkpoint save was triggered but chainHeight advanced before save executed
                val currentCheckpoint = (chainHeight / Config.HEADER_SAVE_CHECKPOINT_WINDOW) * Config.HEADER_SAVE_CHECKPOINT_WINDOW
                val lastSavedCheckpoint = (lastSavedHeight / Config.HEADER_SAVE_CHECKPOINT_WINDOW) * Config.HEADER_SAVE_CHECKPOINT_WINDOW
                val isCheckpointSave = (chainHeight % Config.HEADER_SAVE_CHECKPOINT_WINDOW == 0) || (currentCheckpoint > lastSavedCheckpoint)
                val saveSucceeded = HeaderStorage.saveHeaderChain(dataDir, headerChain, chainHeight, lastSavedHeight, force = isCheckpointSave)
                if (saveSucceeded) {
                    // Only update lastSavedHeight if save actually succeeded
                    lastSavedHeight = chainHeight
                } else {
                    android.util.Log.w("HNSGo", "SpvClient: Save was skipped or failed, not updating lastSavedHeight")
                }
                Unit
            },
            knownNetworkHeight = lastNetworkHeight  // Pass known network height to reject headers ahead of it (matching hnsd behavior)
        )
        
        if (networkHeight != null) {
            lastNetworkHeight = networkHeight
            // CRITICAL: Cap chainHeight to never exceed network height
            // This prevents us from reporting a height ahead of the network (which causes peer rejections)
            if (chainHeight > networkHeight) {
                android.util.Log.w("HNSGo", "SpvClient: Capping chainHeight from $chainHeight to network height $networkHeight (we were ahead of network)")
                chainHeight = networkHeight
                // Also need to remove any headers that are ahead of network height
                synchronized(headerChain) {
                    val firstHeaderHeight = chainHeight - headerChain.size + 1
                    val headersToKeep = headerChain.filterIndexed { index, _ ->
                        val headerHeight = firstHeaderHeight + index
                        headerHeight <= networkHeight
                    }
                    if (headersToKeep.size < headerChain.size) {
                        val removed = headerChain.size - headersToKeep.size
                        android.util.Log.w("HNSGo", "SpvClient: Removing $removed headers that are ahead of network height")
                        headerChain.clear()
                        headerChain.addAll(headersToKeep)
                    }
                }
            }
        }
        
        networkHeight
    }
    
    suspend fun init(dir: File, context: android.content.Context) {
        // Prevent multiple initializations (both MainActivity and DohService call init)
        if (initialized) {
            return
        }
        initialized = true
        
        dataDir = dir
        
        Checkpoint.init(context)
        
        val (loadedHeight, loadedFirstInMemory) = HeaderStorage.loadHeaderChain(
            dataDir, headerChain, MAX_IN_MEMORY_HEADERS
        )
        chainHeight = loadedHeight
        firstInMemoryHeight = loadedFirstInMemory
        lastSavedHeight = loadedHeight  // Initialize lastSavedHeight to the loaded height
        
        // Initialize HashSet for O(1) duplicate detection (matching hnsd's approach)
        if (headerChain.isNotEmpty()) {
            HeaderSync.initializeHashSet(headerChain)
        }
        
        SpvP2P.init(dir)
        
        if (headerChain.isEmpty()) {
            loadCheckpointBootstrap()
            // Initialize HashSet after checkpoint bootstrap
            if (headerChain.isNotEmpty()) {
                HeaderSync.initializeHashSet(headerChain)
            }
        }
        
        if (headerChain.isNotEmpty()) {
            syncAndValidateHeaders()
        } else {
            android.util.Log.e("HNSGo", "SpvClient: CRITICAL - No headers available! Checkpoint bootstrap failed.")
        }
        
        
        if (headerChain.isEmpty()) {
            android.util.Log.w("HNSGo", "SpvClient: WARNING - No headers available!")
            android.util.Log.w("HNSGo", "SpvClient: To bootstrap autonomously:")
            android.util.Log.w("HNSGo", "  2. Connect to working Handshake P2P peers via DNS seed discovery")
            if (Config.DEBUG_MODE) {
            }
        }
    }
    
    /**
     * Continue syncing headers in background until caught up
     */
    suspend fun continueSync(): Int? = withContext(Config.HEADER_SYNC_DISPATCHER) {
        HeaderSync.continueSync(
            headerChain = headerChain,
            getChainHeight = { chainHeight },
            syncHeaders = suspend {
                syncHeaders()
            },
            saveHeaders = suspend {
                // Calculate current firstInMemoryHeight dynamically (may have changed due to trimming)
                val currentFirstInMemory = synchronized(headerChain) {
            if (headerChain.isEmpty()) {
                        Config.CHECKPOINT_HEIGHT
                } else {
                        maxOf(Config.CHECKPOINT_HEIGHT, chainHeight - headerChain.size + 1)
                    }
                }
                // Force save on checkpoint boundaries to ensure progress is saved
                // Check if we're at a checkpoint boundary OR if lastSavedHeight is behind the current checkpoint boundary
                val currentCheckpoint = (chainHeight / Config.HEADER_SAVE_CHECKPOINT_WINDOW) * Config.HEADER_SAVE_CHECKPOINT_WINDOW
                val lastSavedCheckpoint = (lastSavedHeight / Config.HEADER_SAVE_CHECKPOINT_WINDOW) * Config.HEADER_SAVE_CHECKPOINT_WINDOW
                val isCheckpointSave = (chainHeight % Config.HEADER_SAVE_CHECKPOINT_WINDOW == 0) || (currentCheckpoint > lastSavedCheckpoint)
                // Capture chainHeight at save time to avoid race condition (chainHeight may advance during save)
                val heightAtSaveTime = chainHeight
                val saveSucceeded = HeaderStorage.saveHeaderChain(dataDir, headerChain, heightAtSaveTime, lastSavedHeight, force = isCheckpointSave)
                if (saveSucceeded) {
                    // Only update lastSavedHeight to the height we actually saved (not current chainHeight which may have advanced)
                    lastSavedHeight = heightAtSaveTime
                } else {
                    android.util.Log.w("HNSGo", "SpvClient: Save was skipped or failed, not updating lastSavedHeight")
                }
                Unit
            }
        )
    }
    
    /**
     * Force save headers to disk (e.g., when user stops sync)
     * This ensures progress is preserved even if we haven't reached the save threshold
     */
    suspend fun forceSaveHeaders() = withContext(Config.HEADER_SYNC_DISPATCHER) {
        // Check if SpvClient has been initialized (dataDir is set)
        if (!::dataDir.isInitialized) {
            return@withContext
        }
        
        val currentFirstInMemory = synchronized(headerChain) {
            if (headerChain.isEmpty()) {
                Config.CHECKPOINT_HEIGHT
            } else {
                maxOf(Config.CHECKPOINT_HEIGHT, chainHeight - headerChain.size + 1)
            }
        }
        val saveSucceeded = HeaderStorage.saveHeaderChain(dataDir, headerChain, chainHeight, lastSavedHeight, force = true)
        if (saveSucceeded) {
            lastSavedHeight = chainHeight  // Update lastSavedHeight after successful force save
        } else {
            android.util.Log.w("HNSGo", "SpvClient: Force save was skipped or failed at height $chainHeight")
        }
    }
    
    /**
     * Resolve Handshake domain to DNS records
     */
    suspend fun resolve(name: String): List<Record>? = withContext(Config.NAME_QUERY_DISPATCHER) {
        NameResolver.resolve(
            name = name,
            headerChain = headerChain,
            chainHeight = chainHeight
        )
    }
}
