package com.acktarius.hnsgo.adblocker

/**
 * Hardcoded whitelist and blacklist for ad blocking.
 * 
 * Logic:
 * - Whitelist: Domain is allowed, skip ad blocker check entirely
 * - Blacklist: Domain should return NXDOMAIN immediately, no ad blocker check needed
 * - Neither: Run normal ad blocker check
 */
object Bandw {
    /**
     * Hardcoded whitelist - domains that should never be blocked
     * These are typically essential services or domains that should always resolve
     */
    private val WHITELIST = setOf(
        "home.conceal",
        "website.conceal",
        "conceal.network",
        "localhost.localdomain",
        "local"
        // Add more whitelisted domains as needed
    ).map { it.lowercase() }.toSet()
    
    /**
     * Hardcoded blacklist - domains that should always return NXDOMAIN
     * These are known malicious or unwanted domains that should be blocked immediately
     */
    private val BLACKLIST: Set<String> = setOf(
        "s3.amazonaws.com",
        "freshmarketer.com",
        "events.reddit.com",
        "business-api.tiktok.com",
        "log.byteoversea.com",
        // Add hardcoded blacklisted domains as needed
    ).map { it.lowercase() }.toSet()
    
    /**
     * Check if domain is in whitelist
     * @return true if domain (or any parent domain) is whitelisted
     */
    fun isWhitelisted(domain: String): Boolean {
        val normalizedDomain = domain.lowercase().trim()
        if (normalizedDomain.isEmpty()) return false
        
        // Check exact match
        if (WHITELIST.contains(normalizedDomain)) {
            return true
        }
        
        // Check subdomain matches (e.g., "sub.example.com" matches "example.com" in whitelist)
        val parts = normalizedDomain.split(".")
        for (i in parts.indices) {
            val subdomain = parts.subList(i, parts.size).joinToString(".")
            if (WHITELIST.contains(subdomain)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Check if domain is in blacklist
     * @return true if domain (or any parent domain) is blacklisted
     */
    fun isBlacklisted(domain: String): Boolean {
        val normalizedDomain = domain.lowercase().trim()
        if (normalizedDomain.isEmpty()) return false
        
        // Check exact match
        if (BLACKLIST.contains(normalizedDomain)) {
            return true
        }
        
        // Check subdomain matches (e.g., "ads.example.com" matches "example.com" in blacklist)
        val parts = normalizedDomain.split(".")
        for (i in parts.indices) {
            val subdomain = parts.subList(i, parts.size).joinToString(".")
            if (BLACKLIST.contains(subdomain)) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Get whitelist check result
     * @return WhitelistResult indicating if domain should be allowed, blocked, or checked by ad blocker
     */
    fun check(domain: String): WhitelistResult {
        if (isWhitelisted(domain)) {
            return WhitelistResult.Whitelisted
        }
        if (isBlacklisted(domain)) {
            return WhitelistResult.Blacklisted
        }
        return WhitelistResult.Neither
    }
    
    /**
     * Result of whitelist/blacklist check
     */
    sealed class WhitelistResult {
        /** Domain is whitelisted - allow it, skip ad blocker */
        object Whitelisted : WhitelistResult()
        
        /** Domain is blacklisted - return NXDOMAIN immediately, skip ad blocker */
        object Blacklisted : WhitelistResult()
        
        /** Domain is neither whitelisted nor blacklisted - run ad blocker check */
        object Neither : WhitelistResult()
    }
}

