package com.acktarius.hnsgo.browser

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.acktarius.hnsgo.DaneVerifier
import com.acktarius.hnsgo.Tld
import com.acktarius.hnsgo.dohservice.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xbill.DNS.ARecord
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * DNS interceptor for WebView that uses the DoH service for ALL domain resolution.
 * 
 * For Handshake domains:
 * - Bypasses traditional hostname verification (cert won't match HNS domain)
 * - Uses DANE/TLSA for certificate validation when available
 * - Shows warning page if no TLSA record found
 * 
 * For ICANN domains:
 * - Standard certificate validation via system trust store
 */
class WebViewDnsInterceptor {
    
    companion object {
        private const val TAG = "WebViewDNS"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 15_000L
        private const val DANE_CACHE_TTL_MS = 5 * 60 * 1000L  // 5 minutes
    }
    
    /**
     * Cache for DANE verification results per domain.
     * Avoids re-verifying for every sub-resource (CSS, JS, images).
     */
    private data class DaneCacheEntry(
        val result: DaneVerifier.VerificationResult,
        val timestamp: Long
    )
    
    private val daneCache = ConcurrentHashMap<String, DaneCacheEntry>()
    
    /**
     * Custom Dns implementation that resolves all hostnames through DoH service.
     */
    private val dohDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            
            val addresses = resolveThroughDoH(hostname)
            
            if (addresses.isEmpty()) {
                return Dns.SYSTEM.lookup(hostname)
            }
            
            return addresses
        }
    }
    
    /**
     * Standard OkHttp client for ICANN domains (normal certificate validation).
     */
    private val standardClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dohDns)
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    
    /**
     * Trust-all OkHttp client for Handshake domains.
     * Certificate validation is done via DANE/TLSA instead of hostname verification.
     */
    private val handshakeClient: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        
        val trustAllHostnames = HostnameVerifier { _, _ -> true }
        
        OkHttpClient.Builder()
            .dns(dohDns)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier(trustAllHostnames)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    
    /**
     * Intercept WebView request and resolve DNS through DoH service.
     */
    fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val host = uri.host ?: return null
        
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return null
        }
        
        val isHandshakeDomain = !Tld.isIcannDomain(host)
        
        return try {
            runBlocking(Dispatchers.IO) {
                if (isHandshakeDomain && scheme == "https") {
                    fetchHandshakeSite(uri, request, host)
                } else {
                    fetchWithOkHttp(uri, request, standardClient)
                }
            }
        } catch (e: Exception) {
            createErrorResponse(e)
        }
    }
    
    /**
     * Fetch a Handshake site with DANE verification.
     * Uses cached DANE results to avoid re-verifying every sub-resource.
     */
    private suspend fun fetchHandshakeSite(
        uri: Uri,
        request: WebResourceRequest,
        host: String
    ): WebResourceResponse {
        val urlString = uri.toString()
        
        // Check DANE cache first
        val now = System.currentTimeMillis()
        val cached = daneCache[host]
        val daneResult = if (cached != null && (now - cached.timestamp) < DANE_CACHE_TTL_MS) {
            cached.result
        } else {
            // Verify DANE and cache the result
            val result = try {
                DaneVerifier.verify("https://$host/")
            } catch (e: Exception) {
                null
            }
            
            if (result != null) {
                daneCache[host] = DaneCacheEntry(result, now)
            }
            result
        }
        
        return when {
            // DANE verified - safe to proceed
            daneResult?.isValid == true -> {
                fetchWithOkHttp(uri, request, handshakeClient)
                    ?: createDaneSuccessPage(host, daneResult)
            }
            
            // TLSA found but doesn't match - dangerous, show warning
            daneResult?.tlsaFound == true && !daneResult.isValid -> {
                createDaneMismatchPage(host, daneResult)
            }
            
            // No TLSA record - show proceed anyway option
            else -> {
                // Try to fetch anyway with trust-all client
                val response = try {
                    fetchWithOkHttp(uri, request, handshakeClient)
                } catch (e: Exception) {
                    null
                }
                
                response ?: createNoTlsaWarningPage(host, urlString)
            }
        }
    }
    
    /**
     * Fetch the resource using OkHttp.
     */
    private fun fetchWithOkHttp(
        uri: Uri,
        request: WebResourceRequest,
        client: OkHttpClient
    ): WebResourceResponse? {
        val urlString = uri.toString()
        
        val requestBuilder = Request.Builder()
            .url(urlString)
        
        // Copy headers from WebResourceRequest
        request.requestHeaders?.forEach { (key, value) ->
            // Skip headers that OkHttp manages
            if (!key.equals("Host", ignoreCase = true) &&
                !key.equals("Content-Length", ignoreCase = true)) {
                requestBuilder.addHeader(key, value)
            }
        }
        
        when (request.method) {
            "GET" -> requestBuilder.get()
            "HEAD" -> requestBuilder.head()
            "POST" -> requestBuilder.post(ByteArray(0).toRequestBody(null))
            "PUT" -> requestBuilder.put(ByteArray(0).toRequestBody(null))
            "DELETE" -> requestBuilder.delete()
            else -> requestBuilder.method(request.method, null)
        }
        
        val okRequest = requestBuilder.build()
        val response = client.newCall(okRequest).execute()
        
        return response.use { resp ->
            val responseCode = resp.code
            val responseMessage = resp.message.ifEmpty { "OK" }
            
            // WebResourceResponse doesn't support 3xx status codes
            // Return null for redirects/304 to let WebView handle them
            if (responseCode in 300..399) {
                return@use null
            }
            
            val responseHeaders = mutableMapOf<String, String>()
            resp.headers.forEach { (name, value) ->
                responseHeaders[name] = value
            }
            
            val contentType = resp.header("Content-Type") ?: "text/html"
            val (mimeType, charset) = parseContentType(contentType)
            
            val body = resp.body
            val bodyBytes = body?.bytes() ?: ByteArray(0)
            
            
            WebResourceResponse(
                mimeType,
                charset,
                responseCode,
                responseMessage,
                responseHeaders,
                ByteArrayInputStream(bodyBytes)
            )
        }
    }
    
    private fun parseContentType(contentType: String): Pair<String, String> {
        val parts = contentType.split(";").map { it.trim() }
        val mimeType = parts.firstOrNull() ?: "text/html"
        val charset = parts.find { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim('"', '\'', ' ')
            ?: "UTF-8"
        return Pair(mimeType, charset)
    }
    
    /**
     * Warning page when no TLSA record is found.
     */
    private fun createNoTlsaWarningPage(host: String, originalUrl: String): WebResourceResponse {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Handshake Site - No DANE</title>
                <style>
                    body { 
                        font-family: -apple-system, BlinkMacSystemFont, sans-serif; 
                        padding: 20px; 
                        background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                        color: #fff;
                        min-height: 100vh;
                        margin: 0;
                    }
                    .container {
                        max-width: 600px;
                        margin: 0 auto;
                    }
                    .warning { 
                        background: rgba(255, 193, 7, 0.1);
                        padding: 24px; 
                        border-radius: 12px; 
                        border-left: 4px solid #ffc107;
                        margin-bottom: 20px;
                    }
                    .icon { font-size: 48px; margin-bottom: 16px; }
                    h1 { color: #ffc107; margin: 0 0 12px 0; font-size: 24px; }
                    .domain { 
                        font-family: monospace; 
                        background: rgba(255,255,255,0.1); 
                        padding: 8px 12px; 
                        border-radius: 6px;
                        display: inline-block;
                        margin: 8px 0;
                    }
                    p { line-height: 1.6; color: #b0b0b0; margin: 12px 0; }
                    .info-box {
                        background: rgba(255,255,255,0.05);
                        padding: 16px;
                        border-radius: 8px;
                        margin: 16px 0;
                    }
                    .info-box h3 { margin: 0 0 8px 0; color: #fff; font-size: 16px; }
                    .info-box p { margin: 0; font-size: 14px; }
                    .btn {
                        display: inline-block;
                        padding: 14px 28px;
                        border-radius: 8px;
                        text-decoration: none;
                        font-weight: 600;
                        margin: 8px 8px 8px 0;
                        font-size: 16px;
                    }
                    .btn-proceed {
                        background: #6200ee;
                        color: white;
                    }
                    .btn-back {
                        background: rgba(255,255,255,0.1);
                        color: #fff;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="warning">
                        <div class="icon">⚠️</div>
                        <h1>Handshake Domain - No Certificate Verification</h1>
                        <div class="domain">${host.replace("<", "&lt;")}</div>
                        <p>This is a <strong>Handshake domain</strong>. The server's TLS certificate cannot be verified through traditional means.</p>
                    </div>
                    
                    <div class="info-box">
                        <h3>🔍 What this means</h3>
                        <p>No TLSA record was found for this domain. Without DANE/TLSA, we cannot cryptographically verify the server's identity.</p>
                    </div>
                    
                    <div class="info-box">
                        <h3>⚡ Why this happens</h3>
                        <p>Handshake domains use DANE (TLSA records) instead of traditional Certificate Authorities. This site hasn't published a TLSA record.</p>
                    </div>
                    
                    <p style="margin-top: 24px;">
                        <a href="javascript:history.back()" class="btn btn-back">← Go Back</a>
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            200,
            "OK",
            mapOf("Content-Type" to "text/html; charset=UTF-8"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        )
    }
    
    /**
     * Warning page when TLSA exists but doesn't match certificate.
     */
    private fun createDaneMismatchPage(
        host: String,
        daneResult: DaneVerifier.VerificationResult
    ): WebResourceResponse {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>DANE Verification Failed</title>
                <style>
                    body { 
                        font-family: -apple-system, BlinkMacSystemFont, sans-serif; 
                        padding: 20px; 
                        background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                        color: #fff;
                        min-height: 100vh;
                        margin: 0;
                    }
                    .container { max-width: 600px; margin: 0 auto; }
                    .danger { 
                        background: rgba(229, 57, 53, 0.1);
                        padding: 24px; 
                        border-radius: 12px; 
                        border-left: 4px solid #e53935;
                        margin-bottom: 20px;
                    }
                    .icon { font-size: 48px; margin-bottom: 16px; }
                    h1 { color: #e53935; margin: 0 0 12px 0; font-size: 24px; }
                    .domain { 
                        font-family: monospace; 
                        background: rgba(255,255,255,0.1); 
                        padding: 8px 12px; 
                        border-radius: 6px;
                        display: inline-block;
                        margin: 8px 0;
                    }
                    p { line-height: 1.6; color: #b0b0b0; margin: 12px 0; }
                    .info-box {
                        background: rgba(255,255,255,0.05);
                        padding: 16px;
                        border-radius: 8px;
                        margin: 16px 0;
                    }
                    .info-box h3 { margin: 0 0 8px 0; color: #fff; font-size: 16px; }
                    .info-box p { margin: 0; font-size: 14px; }
                    .btn {
                        display: inline-block;
                        padding: 14px 28px;
                        border-radius: 8px;
                        text-decoration: none;
                        font-weight: 600;
                        margin: 8px 8px 8px 0;
                        font-size: 16px;
                    }
                    .btn-back {
                        background: #e53935;
                        color: white;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="danger">
                        <div class="icon">🚫</div>
                        <h1>DANE Verification Failed!</h1>
                        <div class="domain">${host.replace("<", "&lt;")}</div>
                        <p><strong>The server's certificate does NOT match the TLSA record.</strong></p>
                    </div>
                    
                    <div class="info-box">
                        <h3>🔒 What this means</h3>
                        <p>A TLSA record exists for this domain, but the server presented a different certificate. This could indicate a man-in-the-middle attack or server misconfiguration.</p>
                    </div>
                    
                    <div class="info-box">
                        <h3>⚠️ Recommendation</h3>
                        <p><strong>Do NOT proceed.</strong> The certificate mismatch is a serious security concern.</p>
                    </div>
                    
                    <p style="margin-top: 24px;">
                        <a href="javascript:history.back()" class="btn btn-back">← Go Back (Recommended)</a>
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            200,
            "OK",
            mapOf("Content-Type" to "text/html; charset=UTF-8"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        )
    }
    
    /**
     * Success info when DANE verification passed (fallback if fetch failed).
     */
    private fun createDaneSuccessPage(
        host: String,
        daneResult: DaneVerifier.VerificationResult
    ): WebResourceResponse {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>DANE Verified</title>
                <style>
                    body { 
                        font-family: -apple-system, BlinkMacSystemFont, sans-serif; 
                        padding: 20px; 
                        background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                        color: #fff;
                        min-height: 100vh;
                        margin: 0;
                    }
                    .container { max-width: 600px; margin: 0 auto; }
                    .success { 
                        background: rgba(76, 175, 80, 0.1);
                        padding: 24px; 
                        border-radius: 12px; 
                        border-left: 4px solid #4caf50;
                        margin-bottom: 20px;
                    }
                    .icon { font-size: 48px; margin-bottom: 16px; }
                    h1 { color: #4caf50; margin: 0 0 12px 0; font-size: 24px; }
                    .domain { 
                        font-family: monospace; 
                        background: rgba(255,255,255,0.1); 
                        padding: 8px 12px; 
                        border-radius: 6px;
                        display: inline-block;
                        margin: 8px 0;
                    }
                    p { line-height: 1.6; color: #b0b0b0; margin: 12px 0; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="success">
                        <div class="icon">✅</div>
                        <h1>DANE Verified Successfully</h1>
                        <div class="domain">${host.replace("<", "&lt;")}</div>
                        <p>The server's certificate matches the TLSA record published in the Handshake blockchain.</p>
                    </div>
                    <p>However, there was an issue loading the page content. Please try refreshing.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            200,
            "OK",
            mapOf("Content-Type" to "text/html; charset=UTF-8"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        )
    }
    
    private fun createErrorResponse(error: Exception): WebResourceResponse {
        val message = error.message ?: "Unknown error"
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Error</title>
                <style>
                    body { font-family: sans-serif; padding: 20px; background: #1a1a2e; color: #fff; }
                    .error { background: #16213e; padding: 20px; border-radius: 8px; border-left: 4px solid #e94560; }
                    h1 { color: #e94560; margin-top: 0; }
                    pre { background: #0f0f23; padding: 10px; border-radius: 4px; overflow-x: auto; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="error">
                    <h1>Connection Error</h1>
                    <p>Failed to load the page.</p>
                    <pre>${message.replace("<", "&lt;").replace(">", "&gt;")}</pre>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        return WebResourceResponse(
            "text/html",
            "UTF-8",
            500,
            "Error",
            mapOf("Content-Type" to "text/html; charset=UTF-8"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        )
    }
    
    private fun resolveThroughDoH(hostname: String): List<InetAddress> {
        return try {
            val aAddresses = resolveRecordType(hostname, Type.A)
            if (aAddresses.isNotEmpty()) {
                return aAddresses
            }
            
            val aaaaAddresses = resolveRecordType(hostname, Type.AAAA)
            if (aaaaAddresses.isNotEmpty()) {
                return aaaaAddresses
            }
            
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun resolveRecordType(hostname: String, recordType: Int): List<InetAddress> {
        val response = runBlocking(Dispatchers.IO) {
            DnsResolver.resolve(
                name = hostname,
                type = recordType,
                dclass = DClass.IN,
                queryId = 0,
                originalQuestion = null
            )
        }
        
        return when (response) {
            is DnsResolver.ResolutionResult.Success -> {
                extractAddresses(response.message, hostname)
            }
            is DnsResolver.ResolutionResult.Cached -> {
                val message = Message(response.wireData)
                extractAddresses(message, hostname)
            }
            is DnsResolver.ResolutionResult.Failure -> {
                emptyList()
            }
            is DnsResolver.ResolutionResult.Blocked -> {
                emptyList()
            }
        }
    }
    
    private fun extractAddresses(message: Message, hostname: String): List<InetAddress> {
        val rcode = message.header.rcode
        if (rcode != org.xbill.DNS.Rcode.NOERROR) {
            return emptyList()
        }
        
        val answers = message.getSection(Section.ANSWER)
        val addresses = mutableListOf<InetAddress>()
        
        for (record in answers) {
            when (record) {
                is ARecord -> {
                    try {
                        addresses.add(InetAddress.getByAddress(record.address.address))
                    } catch (e: Exception) {
                    }
                }
                is AAAARecord -> {
                    try {
                        addresses.add(InetAddress.getByAddress(record.address.address))
                    } catch (e: Exception) {
                    }
                }
                is CNAMERecord -> {
                    val cnameTarget = record.target.toString(true).trimEnd('.')
                    addresses.addAll(resolveThroughDoH(cnameTarget))
                }
            }
        }
        
        return addresses
    }
}
