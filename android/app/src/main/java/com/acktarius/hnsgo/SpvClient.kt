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
    
    /**
     * DEVELOPMENT ONLY: Configure external Handshake resolver for testing/comparison
     */
    fun setResolver(host: String, port: Int = Config.DEBUG_RESOLVER_PORT) {
        resolverHost = host
        resolverPort = port
        android.util.Log.d("HNSGo", "SpvClient: [DEV] External resolver set to $host:$port (development only)")
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
        android.util.Log.d("HNSGo", "SpvClient: No headers found, attempting checkpoint bootstrap...")
        val checkpointHeight = Checkpoint.loadCheckpoint(headerChain, chainHeight)
        chainHeight = checkpointHeight
        if (headerChain.isNotEmpty()) {
            android.util.Log.d("HNSGo", "SpvClient: Loaded ${headerChain.size} headers from checkpoint, chain height: $chainHeight")
            
            val tipHeader = headerChain.lastOrNull()
            if (tipHeader != null) {
                val tipHash = tipHeader.hash()
                val isAllZeros = tipHash.all { it == 0.toByte() }
                
                if (isAllZeros) {
                    android.util.Log.e("HNSGo", "SpvClient: CRITICAL - Tip hash is ZERO! Hash calculation may be broken")
                }
            }
            
            HeaderStorage.saveHeaderChain(dataDir, headerChain, chainHeight, firstInMemoryHeight)
        } else {
            android.util.Log.w("HNSGo", "SpvClient: Checkpoint bootstrap failed - no headers loaded")
            android.util.Log.w("HNSGo", "SpvClient: Cannot sync from genesis (block 0) - checkpoint required for bootstrap")
        }
    }
    
    private suspend fun syncAndValidateHeaders() {
        android.util.Log.d("HNSGo", "SpvClient: Syncing additional headers from P2P starting from height $chainHeight")
        val networkHeight = syncHeaders()
        if (networkHeight != null) {
            HeaderSync.validateHeightAgainstNetwork(chainHeight, networkHeight)
        }
    }
    
    private suspend fun syncHeaders(): Int? = withContext(Dispatchers.IO) {
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
            android.util.Log.d("HNSGo", "SpvClient: Updating firstInMemoryHeight from $firstInMemoryHeight to $calculatedFirstInMemory (headers trimmed)")
            firstInMemoryHeight = calculatedFirstInMemory
        }
        
        val networkHeight = HeaderSync.syncHeaders(
            headerChain = headerChain,
            getChainHeight = { chainHeight },
            firstInMemoryHeight = firstInMemoryHeight,
            maxInMemoryHeaders = MAX_IN_MEMORY_HEADERS,
            onHeaderAdded = { _ ->
                chainHeight++
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
                HeaderStorage.saveHeaderChain(dataDir, headerChain, chainHeight, currentFirstInMemory)
                Unit
            }
        )
        
        if (networkHeight != null) {
            lastNetworkHeight = networkHeight
        }
        
        networkHeight
    }
    
    suspend fun init(dir: File, context: android.content.Context) {
        dataDir = dir
        
        Checkpoint.init(context)
        
        val (loadedHeight, loadedFirstInMemory) = HeaderStorage.loadHeaderChain(
            dataDir, headerChain, MAX_IN_MEMORY_HEADERS
        )
        chainHeight = loadedHeight
        firstInMemoryHeight = loadedFirstInMemory
        
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
            android.util.Log.e("HNSGo", "SpvClient: Cannot proceed without checkpoint. Please ensure checkpoint.dat is embedded in assets.")
        }
        
        android.util.Log.d("HNSGo", "SpvClient: Initialized with ${headerChain.size} headers (height: $chainHeight)")
        
        if (headerChain.isEmpty()) {
            android.util.Log.w("HNSGo", "SpvClient: WARNING - No headers available!")
            android.util.Log.w("HNSGo", "SpvClient: Domain resolution will fail until headers are synced")
            android.util.Log.w("HNSGo", "SpvClient: To bootstrap autonomously:")
            android.util.Log.w("HNSGo", "  1. Embed checkpoint data from hnsd's checkpoints.h (recommended)")
            android.util.Log.w("HNSGo", "  2. Connect to working Handshake P2P peers via DNS seed discovery")
            if (Config.DEBUG_MODE) {
                android.util.Log.w("HNSGo", "  3. [DEBUG] Use external resolver: SpvClient.setResolver(host, port)")
            }
        }
    }
    
    /**
     * Continue syncing headers in background until caught up
     */
    suspend fun continueSync(): Int? = withContext(Dispatchers.IO) {
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
                HeaderStorage.saveHeaderChain(dataDir, headerChain, chainHeight, currentFirstInMemory)
                Unit
            }
        )
    }
    
    /**
     * Resolve Handshake domain to DNS records
     */
    suspend fun resolve(name: String): List<Record>? = withContext(Dispatchers.IO) {
        NameResolver.resolve(
            name = name,
            headerChain = headerChain,
            chainHeight = chainHeight,
            resolverHost = resolverHost,
            resolverPort = resolverPort
        )
    }
}
