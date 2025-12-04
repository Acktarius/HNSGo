package com.acktarius.hnsgo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.KeyChain
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

object CertHelper {
    private const val SERVER_ALIAS = "localhost" // Matches server cert CN
    // Complete X.500 name with all required fields for Android recognition
    private const val CA_NAME = "CN=HNS Go CA, O=HNS Go, OU=Certificate Authority, L=Internet, ST=Global, C=US"
    private const val SERVER_NAME = "CN=localhost"
    private const val PREFS_NAME = "hnsgo_prefs"
    private const val KEY_CERT_INSTALLED = "cert_installed"
    
    // Use fixed seed for deterministic certificate generation
    // This ensures the same CA is always generated
    private val FIXED_SEED = "HNSGo-Cert-Seed-2025".toByteArray()
    
    // Certificate generation constants
    private const val RSA_KEY_SIZE = 2048
    private const val CERT_SERIAL_BITS = 64
    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private const val HOURS_PER_DAY = 24
    private const val DAYS_PER_YEAR = 365
    private const val MILLIS_PER_DAY = HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MILLIS_PER_SECOND
    private const val MILLIS_PER_YEAR = DAYS_PER_YEAR * MILLIS_PER_DAY
    private const val CERT_VALIDITY_DAYS_BEFORE = 1
    private const val CA_CERT_VALIDITY_YEARS = 10
    private const val SERVER_CERT_VALIDITY_YEARS = 1
    
    // Cache CA certificate and key to ensure consistency across all calls
    @Volatile
    private var cachedCA: Pair<X509Certificate, PrivateKey>? = null
    
    /**
     * Get or generate the CA certificate and key (cached for consistency)
     */
    private fun getOrGenerateCA(): Pair<X509Certificate, PrivateKey> {
        return cachedCA ?: synchronized(this) {
            cachedCA ?: generateCA().also { cachedCA = it }
        }
    }
    
    /**
     * Generate CA certificate and key pair
     * Returns: Pair of (CA Certificate, CA Private Key)
     */
    private fun generateCA(): Pair<X509Certificate, PrivateKey> {
        val random = SecureRandom.getInstance("SHA1PRNG").apply {
            setSeed(FIXED_SEED)
        }
        
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(RSA_KEY_SIZE, random)
        }.generateKeyPair()
        
        val subject = X500Name(CA_NAME)
        val now = System.currentTimeMillis()
        val notBefore = Date(now - CERT_VALIDITY_DAYS_BEFORE * MILLIS_PER_DAY)
        val notAfter = Date(now + CA_CERT_VALIDITY_YEARS * MILLIS_PER_YEAR)
        
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(CERT_SERIAL_BITS, random),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        
        // Mark as CA certificate with BasicConstraints
        val basicConstraints = org.bouncycastle.asn1.x509.BasicConstraints(true)
        builder.addExtension(
            Extension.basicConstraints,
            true,
            basicConstraints
        )
        
        // Add KeyUsage extension for CA certificate
        val keyUsage = KeyUsage(
            KeyUsage.keyCertSign or KeyUsage.cRLSign
        )
        builder.addExtension(
            Extension.keyUsage,
            true,
            keyUsage
        )
        
        // Add SubjectKeyIdentifier extension (required for CA certificates)
        val extensionUtils = JcaX509ExtensionUtils()
        val subjectKeyIdentifier = extensionUtils.createSubjectKeyIdentifier(keyPair.public)
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            subjectKeyIdentifier
        )
        
        // Add AuthorityKeyIdentifier (same as SubjectKeyIdentifier for self-signed CA)
        val authorityKeyIdentifier = extensionUtils.createAuthorityKeyIdentifier(keyPair.public)
        builder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            authorityKeyIdentifier
        )
        
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(keyPair.private)
        val cert = JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))
        
        return cert to keyPair.private
    }
    
    /**
     * Generate server certificate signed by CA
     * Returns: Pair of (Server Certificate, Server Private Key)
     */
    private fun generateServerCert(
        caCert: X509Certificate,
        caKey: PrivateKey
    ): Pair<X509Certificate, PrivateKey> {
        val random = SecureRandom.getInstance("SHA1PRNG").apply {
            setSeed(FIXED_SEED)
        }
        
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(RSA_KEY_SIZE, random)
        }.generateKeyPair()
        
        val issuer = X500Name(caCert.subjectX500Principal.name)
        val subject = X500Name(SERVER_NAME)
        val now = System.currentTimeMillis()
        val notBefore = Date(now - CERT_VALIDITY_DAYS_BEFORE * MILLIS_PER_DAY)
        val notAfter = Date(now + SERVER_CERT_VALIDITY_YEARS * MILLIS_PER_YEAR)
        
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger(CERT_SERIAL_BITS, random),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        
        // Add Subject Alternative Names for localhost, 127.0.0.1, and ::1
        val ipv4 = java.net.InetAddress.getByName("127.0.0.1").address
        val ipv6 = java.net.InetAddress.getByName("::1").address
        
        val altNames = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.dNSName, "localhost"),
                GeneralName(GeneralName.iPAddress, DEROctetString(ipv4)),
                GeneralName(GeneralName.iPAddress, DEROctetString(ipv6))
            )
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            altNames
        )
        
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(caKey)
        val cert = JcaX509CertificateConverter()
            .getCertificate(builder.build(signer))
        
        return cert to keyPair.private
    }
    
    /**
     * Generate KeyStore for SSL context (used by DoT server)
     * This maintains compatibility with DohService.createSSLContext()
     */
    fun generateKeyStore(password: CharArray = "changeit".toCharArray()): KeyStore {
        // Get or generate CA with its key (cached for consistency)
        val (caCert, caKey) = getOrGenerateCA()
        // Generate server cert signed by CA
        val (serverCert, serverKey) = generateServerCert(caCert, caKey)
        
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        // Store server cert with CA in chain
        ks.setKeyEntry(
            SERVER_ALIAS,
            serverKey,
            password,
            arrayOf(serverCert, caCert)
        )
        return ks
    }
    
    /**
     * Install CA certificate for user trust (for Private DNS)
     * Opens Android's certificate installer with DER-encoded CA cert
     * 
     * The certificate must be properly formatted as a CA certificate with:
     * - BasicConstraints with CA=true
     * - KeyUsage with keyCertSign and cRLSign
     * - Self-signed (issuer == subject)
     */
    fun installCert(context: Context) {
        try {
            val (caCert, _) = getOrGenerateCA()
            
            // Validate certificate before installation
            // Ensure it's a proper CA certificate
            // BasicConstraints OID: 2.5.29.19
            val basicConstraintsOid = Extension.basicConstraints.id.toString()
            val basicConstraints = caCert.getExtensionValue(basicConstraintsOid)
            if (basicConstraints == null) {
                android.util.Log.e("HNSGo", "CA certificate missing BasicConstraints extension")
                throw IllegalStateException("Certificate is missing required CA extensions")
            }
            
            // Verify it's self-signed (required for CA certs)
            if (caCert.issuerX500Principal != caCert.subjectX500Principal) {
                android.util.Log.e("HNSGo", "CA certificate is not self-signed")
                throw IllegalStateException("CA certificate must be self-signed")
            }
            
            // Get DER-encoded bytes
            val derBytes = caCert.encoded
            if (derBytes == null || derBytes.isEmpty()) {
                android.util.Log.e("HNSGo", "Failed to encode certificate")
                throw IllegalStateException("Failed to encode certificate")
            }
            
            android.util.Log.d("HNSGo", "Installing CA certificate: ${derBytes.size} bytes")
            
            // Create installation intent
            val intent = KeyChain.createInstallIntent()
            // KeyChain.EXTRA_CERTIFICATE expects DER-encoded certificate bytes (not PEM)
            intent.putExtra(KeyChain.EXTRA_CERTIFICATE, derBytes)
            intent.putExtra(KeyChain.EXTRA_NAME, "HNS Go CA")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            
            // Verify intent can be resolved
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                android.util.Log.d("HNSGo", "Certificate installation intent started")
            } else {
                android.util.Log.e("HNSGo", "No activity found to handle certificate installation")
                throw IllegalStateException("Certificate installer not available")
            }
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "Error installing certificate", e)
            throw e
        }
    }
    
    /**
     * Get CA certificate as DER-encoded bytes (for installation)
     */
    private fun getCADerBytes(): ByteArray {
        val (caCert, _) = getOrGenerateCA()
        // Return DER-encoded bytes (not PEM)
        return caCert.encoded
    }
    
    /**
     * Check if CA certificate is installed
     * Uses SharedPreferences to track installation status
     * Also tries to verify by checking if certificate matches pattern "HNS*Go*CA" or similar
     */
    fun isCAInstalledSync(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val markedAsInstalled = prefs.getBoolean(KEY_CERT_INSTALLED, false)
            
            if (markedAsInstalled) {
                android.util.Log.d("HNSGo", "Certificate marked as installed in preferences")
                return true
            }
            
            // Try to verify by checking certificate store (if possible)
            // Since we can't enumerate certificates directly, we'll use a heuristic
            // Check if we can find certificates matching our pattern
            false
        } catch (e: Exception) {
            android.util.Log.d("HNSGo", "Error checking certificate installation: ${e.message}")
            false
        }
    }
    
    /**
     * Mark certificate as installed in preferences
     */
    fun markCertAsInstalled(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_CERT_INSTALLED, true).apply()
            android.util.Log.d("HNSGo", "Certificate marked as installed")
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "Error marking certificate as installed", e)
        }
    }
    
    /**
     * Clear certificate installation status (for testing or re-installation)
     */
    fun clearCertInstallStatus(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_CERT_INSTALLED, false).apply()
            android.util.Log.d("HNSGo", "Certificate installation status cleared")
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "Error clearing certificate status", e)
        }
    }
    
    /**
     * Get certificate fingerprint for comparison
     */
    private fun getCertificateFingerprint(cert: X509Certificate): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val fingerprint = digest.digest(cert.encoded)
            fingerprint.joinToString(":") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * Get CA certificate as PEM string (for export/debugging)
     */
    /**
     * Get the CA certificate (for SSL trust configuration)
     */
    fun getCACertificate(): X509Certificate {
        val (caCert, _) = getOrGenerateCA()
        return caCert
    }
    
    fun getCAPem(): String {
        val (caCert, _) = getOrGenerateCA()
        val encoded = caCert.encoded
        val base64 = java.util.Base64.getEncoder().encodeToString(encoded)
        return "-----BEGIN CERTIFICATE-----\n" +
                base64.chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----"
    }
    
    /**
     * Save CA certificate directly to Downloads folder
     * Returns true if successful, false otherwise
     */
    fun saveCertToDownloads(context: Context): Boolean {
        return try {
            val derBytes = getCADerBytes()
            val fileName = "hns-go-ca.crt"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ use MediaStore API
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(derBytes)
                    }
                    android.util.Log.d("HNSGo", "Certificate saved to Downloads via MediaStore")
                    return true
                } else {
                    android.util.Log.e("HNSGo", "Failed to create file in Downloads")
                    return false
                }
            } else {
                // Android 9 and below - use traditional file system
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                
                val certFile = File(downloadsDir, fileName)
                FileOutputStream(certFile).use { fos ->
                    fos.write(derBytes)
                }
                android.util.Log.d("HNSGo", "Certificate saved to Downloads: ${certFile.absolutePath}")
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("HNSGo", "Error saving certificate", e)
            false
        }
    }
    
    /**
     * Open Settings to install certificate manually (Android 11+)
     * Tries multiple approaches to open certificate installation page
     */
    fun openCertificateInstallSettings(context: Context) {
        // Prioritize opening the certificate installation settings page directly
        // This avoids showing alert dialogs and goes straight to where users need to be
        val intents = listOf(
            // Try to open encryption & credentials settings directly (Android 9+)
            // This is the page where users can install CA certificates
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Intent("android.settings.ENCRYPTION_AND_CREDENTIALS")
            } else null,
            // Try security settings (Android 8 and below)
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
            // Fallback to general settings
            Intent(android.provider.Settings.ACTION_SETTINGS)
        ).filterNotNull()
        
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    android.util.Log.d("HNSGo", "Opened certificate settings with intent: ${intent.action}")
                    return
                }
            } catch (e: Exception) {
                android.util.Log.d("HNSGo", "Failed to open with intent: ${intent.action}", e)
                continue
            }
        }
        
        android.util.Log.e("HNSGo", "Could not open certificate installation settings")
    }
}
