package com.acktarius.hnsgo.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Firefox utility functions
 * Provides functions to check if Firefox is installed and get its package name
 * Uses intent resolution for future-proof detection of Firefox variants
 */
object FirefoxUtils {
    // Known Firefox package name prefixes and variants
    private val FIREFOX_PACKAGE_PREFIXES = listOf(
        "org.mozilla.firefox",        // Stable Firefox
        "org.mozilla.firefox_beta",   // Beta Firefox
        "org.mozilla.fenix",          // Nightly/Dev builds
        "org.mozilla.focus",          // Firefox Focus
        "org.mozilla.fennec_fdroid"   // F-Droid Firefox
    )
    
    /**
     * Check if Firefox is installed on the device
     * Uses intent resolution to detect any Firefox variant
     * 
     * @param context Android context
     * @return true if Firefox is installed, false otherwise
     */
    fun isFirefoxInstalled(context: Context): Boolean {
        return getFirefoxPackageName(context) != null
    }
    
    /**
     * Get Firefox package name if installed
     * Uses queryIntentActivities to safely detect Firefox without triggering restrictions
     * 
     * @param context Android context
     * @return Firefox package name if installed, null otherwise
     */
    fun getFirefoxPackageName(context: Context): String? {
        val packageManager = context.packageManager
        
        // Method 1: Try to resolve intent for each known Firefox package directly
        // This works even if Firefox isn't in the general handler list
        for (packageName in FIREFOX_PACKAGE_PREFIXES) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
                    setPackage(packageName)
                }
                val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                if (resolveInfo != null) {
                    Log.d("FirefoxUtils", "Found Firefox via direct package resolution: $packageName")
                    return packageName
                }
            } catch (e: Exception) {
                // Continue to next package
            }
        }
        
        // Method 2: Query all handlers for HTTPS URLs
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            @Suppress("DEPRECATION")
            val handlers = packageManager.queryIntentActivities(intent, 0)
            
            Log.d("FirefoxUtils", "Found ${handlers.size} handlers for https:// URLs")
            
            // Log all package names for debugging
            val allPackages = handlers.mapNotNull { it.activityInfo?.packageName }
            Log.d("FirefoxUtils", "Handler packages: ${allPackages.joinToString(", ")}")
            
            // Look for known Firefox packages among handlers
            val foundPackage = allPackages.firstOrNull { packageName ->
                val matches = FIREFOX_PACKAGE_PREFIXES.any { prefix ->
                    packageName == prefix || packageName.startsWith("$prefix.")
                }
                if (packageName.startsWith("org.mozilla.")) {
                    Log.d("FirefoxUtils", "Found org.mozilla.* package: $packageName (matches: $matches)")
                }
                matches
            }
            
            if (foundPackage != null) {
                Log.d("FirefoxUtils", "Found Firefox via intent resolution: $foundPackage")
                return foundPackage
            }
            
            // Also check for any org.mozilla.* package as fallback
            val anyMozilla = allPackages.firstOrNull { it.startsWith("org.mozilla.") }
            if (anyMozilla != null) {
                Log.d("FirefoxUtils", "Found org.mozilla.* package (not in known list): $anyMozilla")
                return anyMozilla
            }
        } catch (e: Exception) {
            Log.w("FirefoxUtils", "Error during intent resolution", e)
        }
        
        Log.d("FirefoxUtils", "Firefox not found")
        return null
    }
    
}

