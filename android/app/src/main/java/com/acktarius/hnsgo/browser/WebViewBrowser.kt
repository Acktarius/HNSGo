package com.acktarius.hnsgo.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.acktarius.hnsgo.browser.BrowserDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    
    val dnsInterceptor = remember { WebViewDnsInterceptor() }
    val database = remember { BrowserDatabase.getInstance(context) }
    val dao = remember { database.browserDao() }
    val prefs = remember { 
        context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    }
    
    val homeUrl = "file:///android_asset/webviewer/home.html"
    
    /**
     * Get search URL for the selected search engine
     */
    fun getSearchUrl(query: String): String {
        val engine = prefs.getString("search_engine", "qwant") ?: "qwant"
        val encodedQuery = Uri.encode(query)
        return when (engine) {
            "qwant" -> "https://www.qwant.com/?q=$encodedQuery"
            "duckduckgo" -> "https://duckduckgo.com/?q=$encodedQuery"
            "google" -> "https://www.google.com/search?q=$encodedQuery"
            "startpage" -> "https://www.startpage.com/sp/search?query=$encodedQuery"
            "brave" -> "https://search.brave.com/search?q=$encodedQuery"
            else -> "https://www.qwant.com/?q=$encodedQuery"
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
                    modifier = Modifier.size(20.dp)
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
                    modifier = Modifier.size(20.dp)
                )
            }
            
            IconButton(
                onClick = { webViewState?.reload() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(20.dp)
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
                    modifier = Modifier.size(20.dp)
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
                    modifier = Modifier.size(20.dp)
                )
            }
            
            IconButton(
                onClick = {
                    webViewState?.loadUrl("file:///android_asset/webviewer/home.html")
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Home",
                    modifier = Modifier.size(20.dp)
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
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            allowFileAccess = true
                            allowContentAccess = true
                        }
                        
                        // JavaScript interface to sync search engine selection and get favorites/history
                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun setSearchEngine(engine: String) {
                                prefs.edit().putString("search_engine", engine).apply()
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun getSearchEngine(): String {
                                return prefs.getString("search_engine", "qwant") ?: "qwant"
                            }
                            
                            @android.webkit.JavascriptInterface
                            fun getFavorites(): String {
                                return runBlocking {
                                    try {
                                        val favorites = dao.getAllFavoritesList()
                                        // Convert to JSON
                                        val json = favorites.joinToString(",", "[", "]") { fav ->
                                            """{"id":${fav.id},"title":"${fav.title.replace("\"", "\\\"")}","url":"${fav.url.replace("\"", "\\\"")}","favicon":"${fav.favicon ?: ""}"}"""
                                        }
                                        json
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
                                return runBlocking {
                                    try {
                                        val history = dao.getRecentHistoryList()
                                            .filter { !it.url.startsWith("file:///android_asset/") }
                                        // Convert to JSON
                                        val json = history.joinToString(",", "[", "]") { hist ->
                                            """{"id":${hist.id},"title":"${hist.title.replace("\"", "\\\"")}","url":"${hist.url.replace("\"", "\\\"")}","visitedAt":${hist.visitedAt}}"""
                                        }
                                        json
                                    } catch (e: Exception) {
                                        "[]"
                                    }
                                }
                            }
                        }, "Android")
                        
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                request?.let {
                                    // Intercept and resolve through custom DNS (DoH)
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
                                url = pageUrl ?: ""
                                // Only update URL bar if user is not actively editing
                                if (!isEditingUrl) {
                                    urlBarText = if (pageUrl == homeUrl) "" else (pageUrl ?: "")
                                }
                                
                                // Save to history (skip file:// URLs)
                                scope.launch {
                                    pageUrl?.let {
                                        // Don't save file:// URLs (home page, etc.)
                                        if (!it.startsWith("file:///android_asset/")) {
                                            val history = HistoryEntity(
                                                title = view?.title ?: "Loading...",
                                                url = it
                                            )
                                            dao.insertHistory(history)
                                        }
                                    }
                                }
                            }
                            
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                isLoading = false
                                progress = 100
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                                
                                // Update history with final title (skip file:// URLs)
                                scope.launch {
                                    pageUrl?.let {
                                        // Don't save file:// URLs (home page, etc.)
                                        if (!it.startsWith("file:///android_asset/")) {
                                            val existing = dao.getHistoryByUrl(it)
                                            val pageTitle = view?.title
                                            if (existing != null && pageTitle != null) {
                                                val updated = existing.copy(
                                                    title = pageTitle,
                                                    visitedAt = System.currentTimeMillis()
                                                )
                                                dao.insertHistory(updated)
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
