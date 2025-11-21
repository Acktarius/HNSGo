package com.acktarius.hnsgo

import android.util.Log
import com.upokecenter.cbor.CBORObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tracks peer connection errors to avoid repeatedly trying failed peers
 * 
 * Peers with error count > MAX_ERRORS will be excluded from peer discovery
 * Error counts are persisted to disk and reset after successful connection
 */
object PeerErrorTracker {
    const val MAX_ERRORS = 3
    private const val ERROR_TRACKER_FILE = "peer_errors.cbor"
    
    // In-memory error tracking: peer address -> error count
    private val peerErrors = mutableMapOf<String, Int>()
    private var dataDir: File? = null
    
    /**
     * Initialize with data directory for persistence
     */
    fun init(dataDir: File) {
        this.dataDir = dataDir
    }
    
    /**
     * Load error counts from storage
     */
    suspend fun loadErrors() = withContext(Dispatchers.IO) {
        val dir = dataDir ?: return@withContext
        val errorsFile = File(dir, ERROR_TRACKER_FILE)
        
        if (!errorsFile.exists()) {
            peerErrors.clear()
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
                Log.d("PeerErrorTracker", "Loaded error counts for ${peerErrors.size} peers")
            }
        } catch (e: Exception) {
            Log.e("PeerErrorTracker", "Error loading peer errors", e)
            peerErrors.clear()
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
            
            for ((peer, count) in peerErrors) {
                errorsMap[peer] = CBORObject.FromObject(count)
            }
            
            cbor["errors"] = errorsMap
            cbor["timestamp"] = CBORObject.FromObject(System.currentTimeMillis())
            
            val data = cbor.EncodeToBytes()
            val errorsFile = File(dir, ERROR_TRACKER_FILE)
            val tempFile = File(dir, "$ERROR_TRACKER_FILE.tmp")
            
            tempFile.writeBytes(data)
            tempFile.renameTo(errorsFile)
            
            Log.d("PeerErrorTracker", "Saved error counts for ${peerErrors.size} peers")
        } catch (e: Exception) {
            Log.e("PeerErrorTracker", "Error saving peer errors", e)
        }
    }
    
    /**
     * Record a connection error for a peer
     */
    suspend fun recordError(peer: String) = withContext(Dispatchers.IO) {
        val currentCount = peerErrors.getOrDefault(peer, 0)
        val newCount = currentCount + 1
        peerErrors[peer] = newCount
        
        Log.d("PeerErrorTracker", "Recorded error for $peer (count: $newCount/$MAX_ERRORS)")
        
        if (newCount >= MAX_ERRORS) {
            Log.w("PeerErrorTracker", "Peer $peer has exceeded max errors ($MAX_ERRORS), will be excluded")
        }
        
        saveErrors()
    }
    
    /**
     * Reset error count for a peer (after successful connection)
     */
    suspend fun resetErrors(peer: String) = withContext(Dispatchers.IO) {
        if (peerErrors.remove(peer) != null) {
            Log.d("PeerErrorTracker", "Reset error count for $peer (connection successful)")
            saveErrors()
        }
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
     * Filter out peers that should be excluded
     */
    fun filterExcluded(peers: List<String>): List<String> {
        return peers.filter { !shouldExclude(it) }
    }
    
    /**
     * Clear all error tracking (for testing/reset)
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        peerErrors.clear()
        val dir = dataDir
        if (dir != null) {
            val errorsFile = File(dir, ERROR_TRACKER_FILE)
            if (errorsFile.exists()) {
                errorsFile.delete()
            }
        }
        Log.d("PeerErrorTracker", "Cleared all peer error tracking")
    }
}

