package com.acktarius.hnsgo.adblocker

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
 * 
 * Priority order:
 * 1. Check Bandw whitelist -> allow (skip ad blocker)
 * 2. Check Bandw blacklist -> NXDOMAIN (skip ad blocker)
 * 3. Check downloaded blacklist -> block if found
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
    // BASE lists: Used when ad blocking (toggle 1) is enabled
    // Good balance of blocking and compatibility
    private val BASE_BLACKLIST_URLS = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://someonewhocares.org/hosts/zero/hosts"
    )
    
    // PRIVACY lists: Additional lists added when privacy mode (toggle 2) is enabled
    // Only used when both toggle 1 (ad blocking) AND toggle 2 (privacy) are ON
    // OISD (OISD Blocklist) - comprehensive, well-maintained, blocks ads + telemetry

    private val PRIVACY_BLACKLIST_URLS = listOf(
        "https://dbl.oisd.nl/basic/",  // OISD Basic - good balance
        "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"  // AdGuard DNS filter
        // Uncomment for even stricter blocking:
        // "https://dbl.oisd.nl/"  // OISD Full - very strict, may break some sites
    )
    
    // Privacy mode state - only meaningful when ad blocking is enabled
    private var usePrivacyMode = false
    
    /**
     * Get the blacklist URLs to download
     * Always returns BASE_BLACKLIST_URLS + PRIVACY_BLACKLIST_URLS
     * (download both so privacy mode can be toggled without re-downloading)
     */
    private fun getBlacklistUrls(): List<String> {
        // Always download both BASE and PRIVACY lists
        // Privacy mode toggle just controls whether to use PRIVACY lists, but they're already downloaded
        return BASE_BLACKLIST_URLS + PRIVACY_BLACKLIST_URLS
    }
    
    /**
     * Enable privacy mode (stricter blocking with additional lists)
     * Note: Privacy mode only works when ad blocking is enabled
     * When enabled, adds PRIVACY_BLACKLIST_URLS to BASE_BLACKLIST_URLS
     */
    fun setPrivacyMode(enabled: Boolean) {
        if (enabled && !this.enabled) {
            return
        }
        usePrivacyMode = enabled
        savePrivacyModeState()
    }
    
    /**
     * Check if privacy mode is enabled
     */
    fun isPrivacyModeEnabled(): Boolean = usePrivacyMode
    
    /**
     * Initialize with context (should be called from MainActivity or DohService)
     * Ensures state starts as false on first launch
     */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        loadFromStorage()
        // Load saved state (defaults to false if not found)
        loadEnabledState()
        loadPrivacyModeState()
        // Ensure privacy mode is off if ad blocking is off
        if (!enabled && usePrivacyMode) {
            usePrivacyMode = false
            savePrivacyModeState()
        }
    }
    
    /**
     * Check if a domain should be blocked
     * Priority:
     * 1. Bandw whitelist -> Whitelisted (trusted domain, skip ad blocker, allow DNS resolution)
     * 2. Bandw blacklist -> Blacklisted (return NXDOMAIN immediately, skip ad blocker)
     * 3. Ad blocker blacklist -> Blocked (return NXDOMAIN if found in downloaded lists)
     * 4. Otherwise -> Allowed (continue to normal DNS resolution)
     * 
     * @return BlockResult indicating the blocking decision
     */
    fun checkBlocked(domain: String): BlockResult {
        // Priority 1: Check Bandw whitelist first
        val bandwResult = Bandw.check(domain)
        when (bandwResult) {
            Bandw.WhitelistResult.Whitelisted -> return BlockResult.Whitelisted
            Bandw.WhitelistResult.Blacklisted -> return BlockResult.Blacklisted
            Bandw.WhitelistResult.Neither -> {
                // Continue to ad blocker check
            }
        }
        
        // Priority 2: Check ad blocker (only if enabled and not in Bandw lists)
        if (!enabled) {
            return BlockResult.Allowed
        }
        
        val normalizedDomain = domain.lowercase().trim()
        if (normalizedDomain.isEmpty()) {
            return BlockResult.Allowed
        }
        
        // Check exact match
        if (blockedDomains.contains(normalizedDomain)) {
            return BlockResult.Blocked
        }
        
        // Check subdomain matches (e.g., "ads.example.com" matches "example.com" in blacklist)
        val parts = normalizedDomain.split(".")
        for (i in parts.indices) {
            val subdomain = parts.subList(i, parts.size).joinToString(".")
            if (blockedDomains.contains(subdomain)) {
                return BlockResult.Blocked
            }
        }
        
        return BlockResult.Allowed
    }
    
    /**
     * Result of blocking check
     */
    sealed class BlockResult {
        /** Domain is whitelisted - trusted domain we know is not harmful, skip ad blocker and allow DNS resolution */
        object Whitelisted : BlockResult()
        
        /** Domain is blacklisted (hardcoded) - block immediately */
        object Blacklisted : BlockResult()
        
        /** Domain is blocked by ad blocker */
        object Blocked : BlockResult()
        
        /** Domain is allowed */
        object Allowed : BlockResult()
    }
    
    /**
     * Refresh the blacklist from known sources and save to local storage
     */
    suspend fun refreshBlacklist() = withContext(Dispatchers.IO) {
        val newBlockedDomains = mutableSetOf<String>()
        val urls = getBlacklistUrls()
        
        
        for (urlString in urls) {
            try {
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
                
            } catch (e: Exception) {
                // Continue with other sources
            }
        }
        
        synchronized(blockedDomains) {
            blockedDomains.clear()
            blockedDomains.addAll(newBlockedDomains)
        }
        
        // Save to local storage
        saveToStorage(newBlockedDomains)
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
        } catch (e: Exception) {
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
            
        } catch (e: Exception) {
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
        }
    }
    
    /**
     * Load enabled state from SharedPreferences
     * Defaults to false if not found (first launch)
     */
    private fun loadEnabledState() {
        val ctx = context ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            enabled = prefs.getBoolean(PREFS_KEY_ENABLED, false)
        } catch (e: Exception) {
            enabled = false
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
        }
    }
    
    /**
     * Load privacy mode state from SharedPreferences
     * Defaults to false if not found (first launch)
     */
    private fun loadPrivacyModeState() {
        val ctx = context ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            usePrivacyMode = prefs.getBoolean(PREFS_KEY_PRIVACY_MODE, false)
        } catch (e: Exception) {
            usePrivacyMode = false
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
     * Enable ad blocking (sets the flag, doesn't download - use refreshBlacklist() for that)
     */
    fun enable() {
        enabled = true
        saveEnabledState()
    }
    
    /**
     * Disable ad blocking
     * Note: Blacklist file is kept on disk for faster re-enable, but cleared from memory
     * Also disables privacy mode when ad blocking is disabled
     */
    fun disable() {
        enabled = false
        usePrivacyMode = false  // Privacy mode requires ad blocking to be enabled
        synchronized(blockedDomains) {
            blockedDomains.clear()
        }
        saveEnabledState()
        savePrivacyModeState()
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

