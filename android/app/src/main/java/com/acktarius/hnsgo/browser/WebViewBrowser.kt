package com.acktarius.hnsgo.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.acktarius.hnsgo.browser.BrowserDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewBrowser(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var webViewState by remember { mutableStateOf<WebView?>(null) }
    var url by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var urlBarText by remember { mutableStateOf("") }
    var isEditingUrl by remember { mutableStateOf(false) }  // Track if user is editing URL bar
    var currentFavicon by remember { mutableStateOf<String?>(null) }  // Track current page favicon
    
    val dnsInterceptor = remember { WebViewDnsInterceptor() }
    val database = remember { BrowserDatabase.getInstance(context) }
    val dao = remember { database.browserDao() }
    val prefs = remember { 
        context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    }
    
    val homeUrl = "file:///android_asset/webviewer/home.html"
    val primaryPurple = Color(0xFF6200EE) // Primary purple color
    
    /**
     * Get search URL for the selected search engine
     */
    fun getSearchUrl(query: String): String {
        val engine = prefs.getString("search_engine", "duckduckgo") ?: "duckduckgo"
        val encodedQuery = Uri.encode(query)
        return when (engine) {
            "duckduckgo" -> "https://duckduckgo.com/?q=$encodedQuery"
            "google" -> "https://www.google.com/search?q=$encodedQuery"
            "startpage" -> "https://www.startpage.com/sp/search?query=$encodedQuery"
            "brave" -> "https://search.brave.com/search?q=$encodedQuery"
            else -> "https://duckduckgo.com/?q=$encodedQuery"
        }
    }
    
    // Load home page when WebView is ready
    LaunchedEffect(webViewState) {
        webViewState?.let { view ->
            if (url.isEmpty()) {
                urlBarText = "" // Keep URL bar empty for home page
                url = homeUrl
                view.loadUrl(homeUrl)
            }
        }
    }
    
    Column(modifier = modifier.fillMaxSize()) {
        // Navigation bar (reduced height, no URL input)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .zIndex(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Left side: Navigation controls
            IconButton(
                onClick = { webViewState?.goBack() },
                enabled = canGoBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(20.dp),
                    tint = primaryPurple
                )
            }
            
            IconButton(
                onClick = { webViewState?.goForward() },
                enabled = canGoForward,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = "Forward",
                    modifier = Modifier.size(20.dp),
                    tint = primaryPurple
                )
            }
            
            IconButton(
                onClick = { webViewState?.reload() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(20.dp),
                    tint = primaryPurple
                )
            }
            
            // Middle: Spacer
            Spacer(modifier = Modifier.weight(1f))
            
            // Right side: Double arrow, Star, Home
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = "Switch to Geek Mode",
                    modifier = Modifier.size(20.dp),
                    tint = primaryPurple
                )
            }
            
            IconButton(
                onClick = {
                    scope.launch {
                        val favorite = dao.getHistoryByUrl(url)?.let {
                            FavoriteEntity(
                                title = it.title,
                                url = it.url
                            )
                        } ?: FavoriteEntity(
                            title = webViewState?.title ?: "Untitled",
                            url = url
                        )
                        dao.insertFavorite(favorite)
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Add to favorites",
                    modifier = Modifier.size(20.dp),
                    tint = primaryPurple
                )
            }
            
            IconButton(
                onClick = {
                    webViewState?.loadUrl("file:///android_asset/webviewer/home.html")
                    // Refresh history when navigating to home
                    scope.launch {
                        kotlinx.coroutines.delay(300) // Wait for page to load
                        webViewState?.evaluateJavascript("if(typeof loadRecentTabs === 'function') loadRecentTabs();", null)
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Home",
                    modifier = Modifier.size(20.dp),
                    tint = primaryPurple
                )
            }
        }
        
        // Progress bar (between nav bar and URL bar)
        if (isLoading && progress < 100) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = androidx.compose.ui.graphics.Color(0xFF6200EE) // Purple primary
            )
        }
        
        // URL bar underneath navigation bar
        OutlinedTextField(
            value = urlBarText,
            onValueChange = { 
                urlBarText = it
                isEditingUrl = true  // User is editing
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            shape = RoundedCornerShape(20.dp),
            singleLine = true,
            placeholder = { Text("Enter URL or search") },
            leadingIcon = {
                if (urlBarText.isNotEmpty()) {
                    IconButton(onClick = { 
                        urlBarText = ""
                        isEditingUrl = true  // User is editing
                    }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            trailingIcon = {
                IconButton(
                    onClick = {
                        isEditingUrl = false  // Done editing, navigating
                        val input = urlBarText.trim()
                        val finalUrl = when {
                            // Already a full URL
                            input.startsWith("http://") || 
                            input.startsWith("https://") || 
                            input.startsWith("file://") -> input
                            // Looks like a domain (contains dot, no spaces)
                            input.contains(".") && !input.contains(" ") -> "https://$input"
                            // Otherwise, search with selected search engine
                            else -> getSearchUrl(input)
                        }
                        webViewState?.loadUrl(finalUrl)
                    }
                ) {
                    Text("Go")
                }
            }
        )
        
        // WebView
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        
                        setBackgroundColor(0x00000000) // Transparent background
                        
                        settings.apply {
                            // JavaScript & Storage - Required for modern websites to function
                            // We intercept all requests through our DoH resolver for privacy
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            
                            // Set realistic mobile browser User-Agent to avoid 403 errors from sites like Qwant
                            // This makes the WebView appear as a regular Chrome mobile browser
                            // Must match the User-Agent used in WebViewDnsInterceptor
                            userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            
                            // Security: Block mixed content (HTTP on HTTPS pages)
                            // This prevents insecure resources from loading on secure pages
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            
                            // Privacy: Restrict file access to prevent sites from accessing device files
                            allowFileAccess = false
                            allowContentAccess = false
                            
                            // Privacy: Disable geolocation API (sites can't access device location)
                            setGeolocationEnabled(false)
                            
                            // Security: Prevent JavaScript from automatically opening windows (popups)
                            javaScriptCanOpenWindowsAutomatically = false
                            
                            // Cache settings
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            
                            // Display settings
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                        }
                        
                        // Privacy: Cookie and tracking prevention
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true) // Accept first-party cookies (needed for site functionality)
                        cookieManager.setAcceptThirdPartyCookies(this, false) // Block third-party cookies (tracking)
                        
                        // JavaScript interface to sync search engine selection and get favorites/history
                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun setSearchEngine(engine: String) {
                                prefs.edit().putString("search_engine", engine).apply()
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun getSearchEngine(): String {
                                return prefs.getString("search_engine", "duckduckgo") ?: "duckduckgo"
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun getFavorites(): String {
                                return runBlocking(Dispatchers.IO) {
                                    try {
                                        val favorites = dao.getAllFavoritesList()
                                        // Convert to JSON in parallel with string building
                                        withContext(Dispatchers.Default) {
                                            favorites.joinToString(",", "[", "]") { fav ->
                                                """{"id":${fav.id},"title":"${fav.title.replace("\"", "\\\"")}","url":"${fav.url.replace("\"", "\\\"")}","favicon":"${fav.favicon ?: ""}"}"""
                                            }
                                        }
                                    } catch (e: Exception) {
                                        "[]"
                                    }
                                }
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun deleteFavorite(id: Long): Boolean {
                                return runBlocking {
                                    try {
                                        val favorites = dao.getAllFavoritesList()
                                        val favorite = favorites.find { it.id == id }
                                        if (favorite != null) {
                                            dao.deleteFavorite(favorite)
                                            true
                                        } else {
                                            false
                                        }
                                    } catch (e: Exception) {
                                        false
                                    }
                                }
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun getRecentHistory(): String {
                                return runBlocking(Dispatchers.IO) {
                                    try {
                                        // Test database access
                                        val allHistory = dao.getRecentHistoryList()
                                        val history = allHistory.filter { !it.url.startsWith("file:///android_asset/") }
                                        // Log for debugging (count only, no URLs)
                                        android.util.Log.d("WebViewBrowser", "History query: found ${allHistory.size} total, ${history.size} after filter")
                                        
                                        // If empty, try to verify database is working
                                        if (allHistory.isEmpty()) {
                                            android.util.Log.d("WebViewBrowser", "History is empty - database may have been cleared or not initialized")
                                        }
                                        
                                        // Convert to JSON in parallel with string building
                                        withContext(Dispatchers.Default) {
                                            if (history.isEmpty()) {
                                                "[]"
                                            } else {
                                                history.joinToString(",", "[", "]") { hist ->
                                                    val faviconEscaped = hist.favicon?.replace("\"", "\\\"")?.replace("\n", "") ?: ""
                                                    """{"id":${hist.id},"title":"${hist.title.replace("\"", "\\\"")}","url":"${hist.url.replace("\"", "\\\"")}","favicon":"$faviconEscaped","visitedAt":${hist.visitedAt}}"""
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("WebViewBrowser", "History query failed: ${e.message}", e)
                                        "[]"
                                    }
                                }
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun onRssFeedFound(rssLinksJson: String) {
                                // RSS feeds discovered - could be used for future features
                                // (e.g., showing RSS icon in navigation bar, feed subscriptions, etc.)
                                // For now, we just acknowledge the discovery
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun refreshHistory() {
                                // Trigger history refresh on home page
                                scope.launch(Dispatchers.Main) {
                                    webViewState?.evaluateJavascript("if(typeof loadRecentTabs === 'function') loadRecentTabs();", null)
                                }
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun clearCookiesAndHistory(): Boolean {
                                return runBlocking {
                                    try {
                                        // Parallelize independent operations for better performance
                                        val cookieJob = async(Dispatchers.IO) {
                                            try {
                                                val cookieManager = CookieManager.getInstance()
                                                cookieManager.removeAllCookies(null)
                                                cookieManager.flush()
                                                delay(200) // Wait for async cookie removal
                                                true
                                            } catch (e: Exception) {
                                                false
                                            }
                                        }
                                        
                                        val cacheJob = async(Dispatchers.Main) {
                                            try {
                                                webViewState?.let { view ->
                                                    view.clearCache(true)
                                                    view.clearHistory()
                                                }
                                                true
                                            } catch (e: Exception) {
                                                false
                                            }
                                        }
                                        
                                        val storageJob = async(Dispatchers.IO) {
                                            try {
                                                WebStorage.getInstance().deleteAllData()
                                                true
                                            } catch (e: Exception) {
                                                false
                                            }
                                        }
                                        
                                        val databaseJob = async(Dispatchers.IO) {
                                            try {
                                                dao.clearHistory()
                                                android.util.Log.d("WebViewBrowser", "History cleared successfully")
                                                true
                                            } catch (e: Exception) {
                                                android.util.Log.e("WebViewBrowser", "History clear failed: ${e.message}")
                                                false
                                            }
                                        }
                                        
                                        // Wait for all operations to complete in parallel
                                        val results = awaitAll(cookieJob, cacheJob, storageJob, databaseJob)
                                        
                                        // Return true if at least some operations succeeded
                                        results.any { it }
                                    } catch (e: Exception) {
                                        false
                                    }
                                }
                            }
                        }, "Android")
                        
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                // Allow all navigation - don't block same-origin or fragment changes
                                // This is critical for JavaScript navigation and in-page links
                                return false // Let WebView handle the navigation
                            }
                            
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                request?.let {
                                    // Intercept and resolve through custom DNS (DoH)
                                    // This handles ALL requests: HTML, JS, CSS, images, etc.
                                    val response = dnsInterceptor.interceptRequest(it)
                                    if (response != null) {
                                        // Return DoH-resolved response
                                        return response
                                    }
                                }
                                // Fall back to system DNS for non-Handshake domains
                                return super.shouldInterceptRequest(view, request)
                            }
                            
                            override fun onPageStarted(
                                view: WebView?,
                                pageUrl: String?,
                                favicon: Bitmap?
                            ) {
                                isLoading = true
                                progress = 0
                                
                                // Privacy: Clear cookies on cross-site navigation for maximum anonymity
                                // This prevents tracking across different websites
                                // Exception: Allow cookies for qwant.com and api.qwant.com
                                pageUrl?.let { newUrl ->
                                    val newHost = Uri.parse(newUrl).host
                                    val currentHost = Uri.parse(url).host
                                    
                                    // Qwant domains that should preserve cookies
                                    val qwantDomains = setOf("qwant.com", "www.qwant.com", "api.qwant.com", "lite.qwant.com")
                                    val isQwantDomain = { host: String? -> 
                                        host != null && qwantDomains.any { host == it || host.endsWith(".$it") }
                                    }
                                    
                                    // Clear cookies when navigating to a different domain
                                    // But preserve cookies if navigating to/from Qwant domains
                                    if (newHost != null && currentHost != null && newHost != currentHost) {
                                        val preserveCookies = isQwantDomain(newHost) || isQwantDomain(currentHost)
                                        if (!preserveCookies) {
                                            CookieManager.getInstance().removeSessionCookies(null)
                                            CookieManager.getInstance().flush()
                                        }
                                    }
                                }
                                
                                url = pageUrl ?: ""
                                // Generate favicon URL from page URL (fallback if onReceivedIcon doesn't fire)
                                pageUrl?.let { url ->
                                    try {
                                        if (!url.startsWith("file:///android_asset/") && 
                                            (url.startsWith("http://") || url.startsWith("https://"))) {
                                            val uri = Uri.parse(url)
                                            val faviconUrl = "${uri.scheme}://${uri.host}/favicon.ico"
                                            currentFavicon = faviconUrl
                                        }
                                    } catch (e: Exception) {
                                        currentFavicon = null
                                    }
                                }
                                
                                // Only update URL bar if user is not actively editing
                                if (!isEditingUrl) {
                                    urlBarText = if (pageUrl == homeUrl) "" else (pageUrl ?: "")
                                }
                                
                                // Save to history (skip file:// URLs) - async, non-blocking
                                scope.launch {
                                    pageUrl?.let {
                                        // Don't save file:// URLs (home page, etc.)
                                        if (!it.startsWith("file:///android_asset/")) {
                                            try {
                                                // Get title on main thread (WebView requires main thread)
                                                val pageTitle = withContext(Dispatchers.Main) {
                                                    view?.title ?: "Loading..."
                                                }
                                                
                                                // Then do database operations on IO thread
                                                withContext(Dispatchers.IO) {
                                                    // Check if URL already exists to prevent duplicates
                                                    val existing = dao.getHistoryByUrl(it)
                                                    val favicon = currentFavicon // Capture current favicon
                                                    if (existing == null) {
                                                        // Insert new entry only if it doesn't exist
                                                        val history = HistoryEntity(
                                                            title = pageTitle,
                                                            url = it,
                                                            favicon = favicon
                                                        )
                                                        dao.insertHistory(history)
                                                    } else {
                                                        // Update existing entry timestamp to move it to top
                                                        val updated = existing.copy(
                                                            visitedAt = System.currentTimeMillis(),
                                                            favicon = favicon ?: existing.favicon // Keep existing favicon if new one not available
                                                        )
                                                        dao.insertHistory(updated)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                // Log error for debugging (no URL in message)
                                                android.util.Log.e("WebViewBrowser", "History insertion in onPageStarted failed: ${e.message}", e)
                                            }
                                        }
                                    }
                                }
                            }
                            
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                isLoading = false
                                progress = 100
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                                
                                // Refresh history if we're on the home page
                                pageUrl?.let {
                                    if (it.startsWith("file:///android_asset/webviewer/home.html")) {
                                        scope.launch {
                                            delay(100) // Small delay to ensure page is ready
                                            view?.evaluateJavascript("if(typeof loadRecentTabs === 'function') loadRecentTabs();", null)
                                        }
                                    }
                                }
                                
                                // Inject RSS auto-discovery script for HTML pages
                                pageUrl?.let {
                                    if (!it.startsWith("file:///android_asset/") && 
                                        (it.startsWith("http://") || it.startsWith("https://"))) {
                                        val rssScript = InterceptRss.getRssAutoDiscoveryScript()
                                        view?.evaluateJavascript(rssScript, null)
                                        
                                        // Discover favicon URL from page HTML
                                        view?.evaluateJavascript("""
                                            (function() {
                                                var link = document.querySelector('link[rel="icon"], link[rel="shortcut icon"], link[rel="apple-touch-icon"]');
                                                if (link && link.href) {
                                                    return link.href;
                                                }
                                                // Fallback to standard location
                                                var url = new URL(window.location.href);
                                                return url.origin + '/favicon.ico';
                                            })();
                                        """.trimIndent()) { faviconUrl ->
                                            if (faviconUrl != null && faviconUrl != "null" && faviconUrl.isNotEmpty()) {
                                                // Remove quotes from JavaScript string result
                                                val cleanUrl = faviconUrl.removeSurrounding("\"")
                                                currentFavicon = cleanUrl
                                                
                                                // Update history with discovered favicon
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            val existing = dao.getHistoryByUrl(it)
                                                            if (existing != null) {
                                                                val updated = existing.copy(
                                                                    favicon = cleanUrl
                                                                )
                                                                dao.insertHistory(updated)
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        android.util.Log.e("WebViewBrowser", "Favicon update failed: ${e.message}", e)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // Update history with final title (skip file:// URLs) - async, non-blocking
                                scope.launch {
                                    pageUrl?.let {
                                        // Don't save file:// URLs (home page, etc.)
                                        if (!it.startsWith("file:///android_asset/")) {
                                            try {
                                                // Get title on main thread (WebView requires main thread)
                                                val pageTitle = withContext(Dispatchers.Main) {
                                                    view?.title
                                                }
                                                
                                                // Then do database operations on IO thread
                                                withContext(Dispatchers.IO) {
                                                    val existing = dao.getHistoryByUrl(it)
                                                    val favicon = currentFavicon // Capture current favicon
                                                    if (existing != null && pageTitle != null) {
                                                        // Update existing entry with final title, favicon, and current timestamp
                                                        val updated = existing.copy(
                                                            title = pageTitle,
                                                            favicon = favicon ?: existing.favicon, // Keep existing favicon if new one not available
                                                            visitedAt = System.currentTimeMillis()
                                                        )
                                                        dao.insertHistory(updated)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                // Log error for debugging (no URL in message)
                                                android.util.Log.e("WebViewBrowser", "History update in onPageFinished failed: ${e.message}", e)
                                            }
                                        }
                                    }
                                }
                            }
                            
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                isLoading = false
                                if (request?.isForMainFrame == true) {
                                    view?.loadDataWithBaseURL(
                                        null,
                                        getErrorHtml(error?.description?.toString() ?: "Unknown error"),
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                }
                            }
                        }
                        
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                            
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                // Title is updated in onPageFinished
                            }
                            
                            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                                // Favicon received - try to discover actual favicon URL from page HTML
                                view?.url?.let { url ->
                                    if (!url.startsWith("file:///android_asset/") && 
                                        (url.startsWith("http://") || url.startsWith("https://"))) {
                                        // Try to get favicon URL from page HTML
                                        view.evaluateJavascript("""
                                            (function() {
                                                var link = document.querySelector('link[rel="icon"], link[rel="shortcut icon"], link[rel="apple-touch-icon"]');
                                                if (link && link.href) {
                                                    return link.href;
                                                }
                                                // Fallback to standard location
                                                var url = new URL(window.location.href);
                                                return url.origin + '/favicon.ico';
                                            })();
                                        """.trimIndent()) { faviconUrl ->
                                            if (faviconUrl != null && faviconUrl != "null" && faviconUrl.isNotEmpty()) {
                                                // Remove quotes from JavaScript string result
                                                val cleanUrl = faviconUrl.removeSurrounding("\"")
                                                currentFavicon = cleanUrl
                                            } else {
                                                // Fallback: generate from URL
                                                try {
                                                    val uri = Uri.parse(url)
                                                    currentFavicon = "${uri.scheme}://${uri.host}/favicon.ico"
                                                } catch (e: Exception) {
                                                    currentFavicon = null
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // Privacy & Security: Explicitly deny all permission requests
                            // This prevents websites from accessing camera, microphone, Bluetooth, etc.
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                // Deny all permission requests for maximum privacy and security
                                // This includes: camera, microphone, Bluetooth, protected media, etc.
                                request?.deny()
                            }
                        }
                        
                        webViewState = this
                        
                        // Load home page immediately when WebView is created
                        loadUrl("file:///android_asset/webviewer/home.html")
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // Update WebView state if needed
                }
            )
            
            // Loading indicator
            if (isLoading && progress == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            // Privacy: Clear all cookies and data when browser is closed
            // This ensures no tracking data persists between sessions
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            
            // Clear WebView cache and storage
            webViewState?.clearCache(true)
            webViewState?.clearHistory()
            WebStorage.getInstance().deleteAllData()
            
            webViewState?.destroy()
        }
    }
}

private fun getErrorHtml(error: String?): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body {
                    font-family: sans-serif;
                    padding: 20px;
                    text-align: center;
                    background: #f5f5f5;
                }
                .error-container {
                    background: white;
                    padding: 30px;
                    border-radius: 10px;
                    margin-top: 50px;
                }
                h1 { color: #d32f2f; }
                p { color: #666; }
            </style>
        </head>
        <body>
            <div class="error-container">
                <h1>⚠️ Page Not Found</h1>
                <p>${error ?: "Unable to load the page"}</p>
            </div>
        </body>
        </html>
    """.trimIndent()
}
