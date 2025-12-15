package com.acktarius.hnsgo.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * RSS Feed Interceptor
 * 
 * Detects RSS/Atom feeds and converts them to a readable HTML feed viewer.
 * Also handles RSS auto-discovery by injecting detection JavaScript into HTML pages.
 */
object InterceptRss {
    
    private const val TAG = "RSSInterceptor"
    
    /**
     * Check if a response is an RSS/Atom feed based on MIME type or content
     */
    fun isRssFeed(mimeType: String, bodyBytes: ByteArray?): Boolean {
        // Check MIME type
        val mimeLower = mimeType.lowercase()
        if (mimeLower.contains("rss") || 
            mimeLower.contains("atom") ||
            mimeLower == "application/xml" ||
            mimeLower == "text/xml") {
            
            // For XML types, check content to confirm it's actually RSS/Atom
            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                val content = String(bodyBytes, StandardCharsets.UTF_8).lowercase()
                return content.contains("<rss") || 
                       content.contains("<feed") ||
                       content.contains("xmlns=\"http://www.w3.org/2005/atom\"")
            }
            return true
        }
        return false
    }
    
    /**
     * Convert RSS/Atom feed to HTML feed viewer
     */
    fun convertFeedToHtml(bodyBytes: ByteArray, feedUrl: String): WebResourceResponse {
        val feedContent = String(bodyBytes, StandardCharsets.UTF_8)
        val html = generateFeedViewerHtml(feedContent, feedUrl)
        
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            200,
            "OK",
            mapOf("Content-Type" to "text/html; charset=UTF-8"),
            ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8))
        )
    }
    
    /**
     * Generate HTML feed viewer with parsed RSS/Atom content
     */
    private fun generateFeedViewerHtml(feedContent: String, feedUrl: String): String {
        // Parse feed (simple parsing - could be enhanced)
        val title = extractFeedTitle(feedContent)
        val items = extractFeedItems(feedContent)
        
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${title.escapeHtml()}</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        padding: 20px;
                        min-height: 100vh;
                    }
                    .container {
                        max-width: 800px;
                        margin: 0 auto;
                    }
                    .header {
                        background: white;
                        border-radius: 12px;
                        padding: 24px;
                        margin-bottom: 20px;
                        box-shadow: 0 5px 15px rgba(0,0,0,0.1);
                    }
                    .header h1 {
                        color: #333;
                        font-size: 24px;
                        margin-bottom: 8px;
                    }
                    .header .feed-url {
                        color: #666;
                        font-size: 14px;
                        word-break: break-all;
                    }
                    .feed-item {
                        background: white;
                        border-radius: 12px;
                        padding: 20px;
                        margin-bottom: 16px;
                        box-shadow: 0 5px 15px rgba(0,0,0,0.1);
                        cursor: pointer;
                        transition: transform 0.2s;
                    }
                    .feed-item:active {
                        transform: scale(0.98);
                    }
                    .feed-item-title {
                        font-size: 18px;
                        font-weight: 600;
                        color: #333;
                        margin-bottom: 8px;
                    }
                    .feed-item-date {
                        font-size: 12px;
                        color: #999;
                        margin-bottom: 8px;
                    }
                    .feed-item-description {
                        font-size: 14px;
                        color: #666;
                        line-height: 1.5;
                    }
                    .feed-item-link {
                        margin-top: 12px;
                        font-size: 12px;
                        color: #6200ee;
                        text-decoration: none;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>${title.escapeHtml()}</h1>
                        <div class="feed-url">${feedUrl.escapeHtml()}</div>
                    </div>
                    ${items.joinToString("\n") { item ->
                        """
                        <div class="feed-item" onclick="window.location.href='${item.link.escapeHtml()}'">
                            <div class="feed-item-title">${item.title.escapeHtml()}</div>
                            ${if (item.date.isNotEmpty()) "<div class=\"feed-item-date\">${item.date.escapeHtml()}</div>" else ""}
                            <div class="feed-item-description">${item.description.escapeHtml()}</div>
                            ${if (item.link.isNotEmpty()) "<a href=\"${item.link.escapeHtml()}\" class=\"feed-item-link\" onclick=\"event.stopPropagation();\">Read more →</a>" else ""}
                        </div>
                        """.trimIndent()
                    }}
                </div>
            </body>
            </html>
        """.trimIndent()
    }
    
    /**
     * Extract feed title from RSS/Atom content
     */
    private fun extractFeedTitle(content: String): String {
        // Try RSS format
        val rssTitle = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.groupValues?.get(1)?.trim()
        if (!rssTitle.isNullOrEmpty()) {
            return rssTitle.replace(Regex("<[^>]+>"), "").trim()
        }
        
        // Try Atom format
        val atomTitle = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(content)?.groupValues?.get(1)?.trim()
        if (!atomTitle.isNullOrEmpty()) {
            return atomTitle.replace(Regex("<[^>]+>"), "").trim()
        }
        
        return "RSS Feed"
    }
    
    /**
     * Feed item data class
     */
    private data class FeedItem(
        val title: String,
        val description: String,
        val link: String,
        val date: String
    )
    
    /**
     * Extract feed items from RSS/Atom content
     */
    private fun extractFeedItems(content: String): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        
        // Try RSS format (<item> tags)
        val itemPattern = Regex("<item[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        itemPattern.findAll(content).forEach { match ->
            val itemContent = match.groupValues[1]
            val title = extractTagContent(itemContent, "title") ?: ""
            val description = extractTagContent(itemContent, "description") ?: ""
            val link = extractTagContent(itemContent, "link") ?: ""
            val date = extractTagContent(itemContent, "pubDate") ?: extractTagContent(itemContent, "date") ?: ""
            
            if (title.isNotEmpty()) {
                items.add(FeedItem(
                    title = title.replace(Regex("<[^>]+>"), "").trim(),
                    description = description.replace(Regex("<[^>]+>"), "").trim().take(200),
                    link = link.trim(),
                    date = date.trim()
                ))
            }
        }
        
        // If no RSS items, try Atom format (<entry> tags)
        if (items.isEmpty()) {
            val entryPattern = Regex("<entry[^>]*>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
            entryPattern.findAll(content).forEach { match ->
                val entryContent = match.groupValues[1]
                val title = extractTagContent(entryContent, "title") ?: ""
                val summary = extractTagContent(entryContent, "summary") ?: extractTagContent(entryContent, "content") ?: ""
                val link = extractLinkHref(entryContent) ?: ""
                val date = extractTagContent(entryContent, "published") ?: extractTagContent(entryContent, "updated") ?: ""
                
                if (title.isNotEmpty()) {
                    items.add(FeedItem(
                        title = title.replace(Regex("<[^>]+>"), "").trim(),
                        description = summary.replace(Regex("<[^>]+>"), "").trim().take(200),
                        link = link.trim(),
                        date = date.trim()
                    ))
                }
            }
        }
        
        return items.take(50) // Limit to 50 items
    }
    
    /**
     * Extract content from XML tag
     */
    private fun extractTagContent(content: String, tagName: String): String? {
        val pattern = Regex("<$tagName[^>]*>(.*?)</$tagName>", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(content)?.groupValues?.get(1)?.trim()
    }
    
    /**
     * Extract href from Atom <link> tag
     */
    private fun extractLinkHref(content: String): String? {
        val pattern = Regex("<link[^>]*href=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
        return pattern.find(content)?.groupValues?.get(1)?.trim()
    }
    
    /**
     * Escape HTML special characters
     */
    private fun String.escapeHtml(): String {
        return this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
    
    /**
     * JavaScript to inject into HTML pages for RSS auto-discovery
     */
    fun getRssAutoDiscoveryScript(): String {
        return """
            (function() {
                // Find RSS/Atom links in the page
                const rssLinks = [];
                
                // Check <link> tags with rel="alternate"
                document.querySelectorAll('link[rel="alternate"]').forEach(link => {
                    const type = link.getAttribute('type');
                    const href = link.getAttribute('href');
                    if (type && (type.includes('rss') || type.includes('atom') || type.includes('xml')) && href) {
                        rssLinks.push({
                            title: link.getAttribute('title') || 'RSS Feed',
                            href: href,
                            type: type
                        });
                    }
                });
                
                // Check for common RSS link patterns
                const commonRssPaths = ['/feed', '/rss', '/atom', '/feed.xml', '/rss.xml', '/atom.xml'];
                commonRssPaths.forEach(path => {
                    const link = document.createElement('link');
                    link.href = path;
                    link.rel = 'alternate';
                    link.type = 'application/rss+xml';
                    document.head.appendChild(link);
                });
                
                // If RSS links found, notify Android
                if (rssLinks.length > 0 && typeof Android !== 'undefined' && Android.onRssFeedFound) {
                    Android.onRssFeedFound(JSON.stringify(rssLinks));
                }
            })();
        """.trimIndent()
    }
}
