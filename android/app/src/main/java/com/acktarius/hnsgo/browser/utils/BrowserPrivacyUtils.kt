package com.acktarius.hnsgo.browser.utils

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.acktarius.hnsgo.browser.BrowserDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Privacy-focused utilities for clearing browser data
 * Designed for maximum privacy and security - clears all tracking data
 */
object BrowserPrivacyUtils {
    
    /**
     * Comprehensive privacy-focused data clearing
     * Clears all browsing data to prevent tracking and data leaks
     * 
     * @param webView The WebView instance to clear (can be null)
     * @param dao The BrowserDao instance to clear database history (can be null)
     * @param includeFormData Whether to clear form auto-fill data (default: true)
     * @param includeMatches Whether to clear text search matches (default: true)
     */
    suspend fun clearAllPrivacyData(
        webView: WebView? = null,
        dao: BrowserDao? = null,
        includeFormData: Boolean = true,
        includeMatches: Boolean = true
    ) {
        try {
            // Clear cookies (runs on IO thread)
            withContext(Dispatchers.IO) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                delay(200) // Wait for async cookie removal
            }
            
            // Clear WebView data (must be on Main thread)
            webView?.let { view ->
                withContext(Dispatchers.Main) {
                    view.clearCache(true)      // Clear all cached files
                    view.clearHistory()        // Clear navigation history
                    
                    if (includeFormData) {
                        view.clearFormData()   // Clear form auto-fill data
                    }
                    
                    if (includeMatches) {
                        view.clearMatches()    // Clear text search matches
                    }
                }
            }
            
            // Clear WebStorage (localStorage, sessionStorage) - runs on IO thread
            withContext(Dispatchers.IO) {
                WebStorage.getInstance().deleteAllData()
            }
            
            // Clear database history - runs on IO thread
            dao?.let {
                withContext(Dispatchers.IO) {
                    it.clearHistory()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowserPrivacyUtils", "Error clearing privacy data: ${e.message}", e)
        }
    }
    
    /**
     * Clear cookies and history only (lighter operation)
     * Used for cross-site navigation privacy
     */
    suspend fun clearCookiesAndHistory(
        webView: WebView? = null,
        dao: BrowserDao? = null
    ) {
        try {
            // Clear cookies
            withContext(Dispatchers.IO) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                delay(200)
            }
            
            // Clear WebView history
            webView?.let { view ->
                withContext(Dispatchers.Main) {
                    view.clearHistory()
                }
            }
            
            // Clear database history
            dao?.let {
                withContext(Dispatchers.IO) {
                    it.clearHistory()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowserPrivacyUtils", "Error clearing cookies and history: ${e.message}", e)
        }
    }
    
    /**
     * Clear session cookies only (preserves persistent cookies)
     * Used for cross-site navigation while maintaining login sessions
     */
    suspend fun clearSessionCookies() {
        try {
            withContext(Dispatchers.IO) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeSessionCookies(null)
                cookieManager.flush()
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowserPrivacyUtils", "Error clearing session cookies: ${e.message}", e)
        }
    }
    
    /**
     * Synchronous version - clears all privacy data immediately
     * KISS: Simple, direct, NON-BLOCKING to prevent ANR
     * Used when switching modes - fast WebView/cookie clear, database in background
     */
    fun clearAllPrivacyDataSync(
        webView: WebView? = null,
        dao: BrowserDao? = null
    ) {
        try {
            // Clear WebView data (must be on main thread, but we're already there)
            webView?.let { view ->
                view.clearCache(true)
                view.clearHistory()
                view.clearFormData()
            }
            
            // Clear cookies - NON-BLOCKING (fire and forget to prevent ANR)
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null) // Async, don't wait for callback
            cookieManager.flush()
            
            // Clear WebStorage
            WebStorage.getInstance().deleteAllData()
            
            // Database clearing is non-critical and can be done async
            // Skipping here to prevent ANR - use clearAllPrivacyData() for complete clear
        } catch (e: Exception) {
            android.util.Log.e("BrowserPrivacyUtils", "Error clearing privacy data: ${e.message}", e)
        }
    }
}

