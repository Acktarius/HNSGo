package com.acktarius.hnsgo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Collections

/**
 * Simple ad blocking manager using blacklists from known references.
 * KISS approach: downloads and maintains a set of blocked domains.
 * Blacklist is stored in local storage as a text file.
 */
object AdBlockManager {
    private val blockedDomains = Collections.synchronizedSet(mutableSetOf<String>())
    private var enabled = false
    private var context: Context? = null
    private const val BLACKLIST_FILE = "adblock_blacklist.txt"
    private const val PREFS_NAME = "adblock_prefs"
    private const val PREFS_KEY_ENABLED = "enabled"
    private const val PREFS_KEY_PRIVACY_MODE = "privacy_mode"
    
    // Known ad block list sources (using popular, well-maintained lists)
    // Base lists: good balance of blocking and compatibility
    private val BASE_BLACKLIST_URLS = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://someonewhocares.org/hosts/zero/hosts"
    )
    
    // Privacy/strict mode: additional lists for stricter telemetry blocking
    // OISD (OISD Blocklist) - comprehensive, well-maintained, blocks ads + telemetry
    // Many people report "OISD + AdGuard DNS filter" as a sweet spot for strong blocking
    // without too much breakage.
    private val PRIVACY_BLACKLIST_URLS = listOf(
        "https://dbl.oisd.nl/basic/",  // OISD Basic - good balance
        "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"  // AdGuard DNS filter
        // Uncomment for even stricter blocking:
        // "https://dbl.oisd.nl/"  // OISD Full - very strict, may break some sites
    )
    
    // Combined list - can be toggled between base and privacy mode
    private var usePrivacyMode = false
    
    /**
     * Get the active blacklist URLs based on current mode
     */
    private fun getBlacklistUrls(): List<String> {
        return if (usePrivacyMode) {
            BASE_BLACKLIST_URLS + PRIVACY_BLACKLIST_URLS
        } else {
            BASE_BLACKLIST_URLS
        }
    }
    
    /**
     * Enable privacy mode (stricter blocking with additional lists)
     */
    fun setPrivacyMode(enabled: Boolean) {
        usePrivacyMode = enabled
        savePrivacyModeState()
        Log.d("HNSGo", "AdBlockManager: Privacy mode ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Check if privacy mode is enabled
     */
    fun isPrivacyModeEnabled(): Boolean = usePrivacyMode
    
    /**
     * Initialize with context (should be called from MainActivity or DohService)
     */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        loadFromStorage()
        loadEnabledState()
        loadPrivacyModeState()
    }
    
    /**
     * Check if a domain should be blocked
     */
    fun isBlocked(domain: String): Boolean {
        if (!enabled) return false
        
        val normalizedDomain = domain.lowercase().trim()
        if (normalizedDomain.isEmpty()) return false
        
        // Check exact match
        if (blockedDomains.contains(normalizedDomain)) {
            return true
        }
        
        // Check subdomain matches (e.g., "ads.example.com" matches "example.com" in blacklist)
        val parts = normalizedDomain.split(".")
        for (i in parts.indices) {
            val subdomain = parts.subList(i, parts.size).joinToString(".")
            if (blockedDomains.contains(subdomain)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Refresh the blacklist from known sources and save to local storage
     */
    suspend fun refreshBlacklist() = withContext(Dispatchers.IO) {
        val newBlockedDomains = mutableSetOf<String>()
        val urls = getBlacklistUrls()
        
        Log.d("HNSGo", "AdBlockManager: Refreshing blacklist (privacy mode: $usePrivacyMode, ${urls.size} sources)")
        
        for (urlString in urls) {
            try {
                Log.d("HNSGo", "AdBlockManager: Fetching blacklist from $urlString")
                val url = URL(urlString)
                val connection = url.openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                connection.getInputStream().bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { processHostsLine(it, newBlockedDomains) }
                    }
                }
                
                Log.d("HNSGo", "AdBlockManager: Loaded ${newBlockedDomains.size} domains from $urlString")
            } catch (e: Exception) {
                Log.w("HNSGo", "AdBlockManager: Failed to fetch from $urlString: ${e.message}")
                // Continue with other sources
            }
        }
        
        synchronized(blockedDomains) {
            blockedDomains.clear()
            blockedDomains.addAll(newBlockedDomains)
        }
        
        // Save to local storage
        saveToStorage(newBlockedDomains)
        
        enabled = true
        saveEnabledState()
        Log.d("HNSGo", "AdBlockManager: Blacklist refreshed, total domains: ${blockedDomains.size}")
    }
    
    /**
     * Save blacklist to local storage as text file
     */
    private fun saveToStorage(domains: Set<String>) {
        val ctx = context ?: return
        try {
            val file = File(ctx.filesDir, BLACKLIST_FILE)
            file.bufferedWriter().use { writer ->
                domains.forEach { domain ->
                    writer.write(domain)
                    writer.newLine()
                }
            }
            Log.d("HNSGo", "AdBlockManager: Saved ${domains.size} domains to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("HNSGo", "AdBlockManager: Failed to save blacklist to storage", e)
        }
    }
    
    /**
     * Load blacklist from local storage
     */
    private fun loadFromStorage() {
        val ctx = context ?: return
        try {
            val file = File(ctx.filesDir, BLACKLIST_FILE)
            if (!file.exists()) {
                Log.d("HNSGo", "AdBlockManager: No saved blacklist found, will download on first enable")
                return
            }
            
            val loadedDomains = mutableSetOf<String>()
            file.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val domain = line.trim()
                    if (domain.isNotEmpty()) {
                        loadedDomains.add(domain)
                    }
                }
            }
            
            synchronized(blockedDomains) {
                blockedDomains.clear()
                blockedDomains.addAll(loadedDomains)
            }
            
            Log.d("HNSGo", "AdBlockManager: Loaded ${blockedDomains.size} domains from storage")
        } catch (e: Exception) {
            Log.e("HNSGo", "AdBlockManager: Failed to load blacklist from storage", e)
        }
    }
    
    /**
     * Save enabled state to SharedPreferences
     */
    private fun saveEnabledState() {
        val ctx = context ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREFS_KEY_ENABLED, enabled).apply()
        } catch (e: Exception) {
            Log.e("HNSGo", "AdBlockManager: Failed to save enabled state", e)
        }
    }
    
    /**
     * Load enabled state from SharedPreferences
     */
    private fun loadEnabledState() {
        val ctx = context ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            enabled = prefs.getBoolean(PREFS_KEY_ENABLED, false)
            Log.d("HNSGo", "AdBlockManager: Loaded enabled state: $enabled")
        } catch (e: Exception) {
            Log.e("HNSGo", "AdBlockManager: Failed to load enabled state", e)
        }
    }
    
    /**
     * Save privacy mode state to SharedPreferences
     */
    private fun savePrivacyModeState() {
        val ctx = context ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(PREFS_KEY_PRIVACY_MODE, usePrivacyMode).apply()
        } catch (e: Exception) {
            Log.e("HNSGo", "AdBlockManager: Failed to save privacy mode state", e)
        }
    }
    
    /**
     * Load privacy mode state from SharedPreferences
     */
    private fun loadPrivacyModeState() {
        val ctx = context ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            usePrivacyMode = prefs.getBoolean(PREFS_KEY_PRIVACY_MODE, false)
            Log.d("HNSGo", "AdBlockManager: Loaded privacy mode state: $usePrivacyMode")
        } catch (e: Exception) {
            Log.e("HNSGo", "AdBlockManager: Failed to load privacy mode state", e)
        }
    }
    
    /**
     * Process a line from a hosts file format
     * Format: IP_ADDRESS domain1 domain2 ...
     * We ignore the IP and extract domains
     */
    private fun processHostsLine(line: String, blockedDomains: MutableSet<String>) {
        val trimmed = line.trim()
        
        // Skip comments and empty lines
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return
        }
        
        // Parse hosts file format: IP domain1 domain2 ...
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 2) {
            return
        }
        
        // Skip the IP address (first part), add all domains
        for (i in 1 until parts.size) {
            val domain = parts[i].lowercase().trim()
            if (domain.isNotEmpty() && !domain.startsWith("#")) {
                blockedDomains.add(domain)
            }
        }
    }
    
    /**
     * Disable ad blocking
     * Note: Blacklist file is kept on disk for faster re-enable, but cleared from memory
     */
    fun disable() {
        enabled = false
        synchronized(blockedDomains) {
            blockedDomains.clear()
        }
        saveEnabledState()
        Log.d("HNSGo", "AdBlockManager: Ad blocking disabled, blacklist cleared from memory")
    }
    
    /**
     * Check if ad blocking is enabled
     */
    fun isEnabled(): Boolean = enabled
    
    /**
     * Get the number of blocked domains
     */
    fun getBlockedDomainCount(): Int = blockedDomains.size
}

