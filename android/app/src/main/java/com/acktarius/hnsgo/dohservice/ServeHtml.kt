package com.acktarius.hnsgo.dohservice

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.ByteArrayInputStream

/**
 * Handles serving HTML files and images from Android assets
 */
object ServeHtml {
    // Context for accessing assets (set during initialization)
    private var context: Context? = null
    
    /**
     * Initialize ServeHtml with Android Context for asset access
     */
    fun init(context: Context) {
        this.context = context.applicationContext
    }
    
    /**
     * Serve HTML guide file
     */
    fun serveIndexHtml(): Response {
        return serveAssetFile("html/index.html", "text/html")
    }
    
    /**
     * Serve blocked page for blacklisted domains
     */
    fun serveBlockedHtml(): Response {
        return serveAssetFile("html/blocked.html", "text/html")
    }
    
    /**
     * Serve screenshot image file
     */
    fun serveScreenshot(filename: String): Response {
        val mimeType = when {
            filename.endsWith(".png", ignoreCase = true) -> "image/png"
            filename.endsWith(".jpg", ignoreCase = true) || filename.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            filename.endsWith(".gif", ignoreCase = true) -> "image/gif"
            else -> "application/octet-stream"
        }
        return serveAssetFile("html/screenshot/$filename", mimeType)
    }
    
    /**
     * Serve file from Android assets
     */
    private fun serveAssetFile(assetPath: String, mimeType: String): Response {
        val ctx = context ?: run {
            return NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Server not initialized")
        }
        
        return try {
            val inputStream = ctx.assets.open(assetPath)
            val fileData = inputStream.readBytes()
            inputStream.close()
            NanoHTTPD.newFixedLengthResponse(Status.OK, mimeType, ByteArrayInputStream(fileData), fileData.size.toLong())
        } catch (e: java.io.FileNotFoundException) {
            NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "File not found: $assetPath")
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error serving file: ${e.message}")
        }
    }
}

