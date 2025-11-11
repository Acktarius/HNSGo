package com.acktarius.hnsgo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import android.util.Log
import android.widget.Toast

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = androidx.compose.ui.graphics.Color.Black,
                    onBackground = androidx.compose.ui.graphics.Color.White,
                    surface = androidx.compose.ui.graphics.Color.Black,
                    onSurface = androidx.compose.ui.graphics.Color.White
                )
            ) {
                HnsGoScreen(this)
            }
        }
    }
}

enum class SyncStatus {
    IDLE, SYNCING, SYNCED, ERROR
}

@Composable
fun ExpandableCard(
    title: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clickable { if (!expanded) onExpand() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (expanded) {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded) {
                HorizontalDivider()
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HnsGoScreen(act: MainActivity) {
    var enabled by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf(SyncStatus.IDLE) }
    var syncMessage by remember { mutableStateOf("") }
    var showGuidance by remember { mutableStateOf(false) }
    var certInstalled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val typewriterFont = FontFamily.Monospace
    
    // Check certificate installation status on mount
    LaunchedEffect(Unit) {
        certInstalled = CertHelper.isCAInstalledSync(act)
        Log.d("HNSGo", "Certificate installed status: $certInstalled")
    }

    LaunchedEffect(enabled) {
        Log.d("HNSGo", "LaunchedEffect triggered, enabled=$enabled")
        if (enabled) {
            Log.d("HNSGo", "Toggle is ON, starting sync...")
            syncStatus = SyncStatus.SYNCING
            syncMessage = "Syncing headers..."
            
            scope.launch {
                try {
                    Log.d("HNSGo", "Starting SPV sync...")
                    SpvClient.init(act.filesDir)
                    Log.d("HNSGo", "SPV sync completed successfully")
                    syncStatus = SyncStatus.SYNCED
                    syncMessage = "Sync complete"
                    
                    // Start DoH server after sync
                    Log.d("HNSGo", "Starting DoH service...")
            ContextCompat.startForegroundService(act, Intent(act, DohService::class.java))
                    showGuidance = true
                } catch (e: Exception) {
                    Log.e("HNSGo", "Error during sync or service start", e)
                    syncStatus = SyncStatus.ERROR
                    syncMessage = "Sync failed: ${e.message}"
                }
            }
        } else {
            act.stopService(Intent(act, DohService::class.java))
            syncStatus = SyncStatus.IDLE
            syncMessage = ""
            showGuidance = false
        }
    }

    val learnMoreExpanded = remember { mutableStateOf(false) }
    val disclaimerExpanded = remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Main content - scrollable
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(bottom = 200.dp), // Space for fixed buttons at bottom
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "HNS Go",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = typewriterFont,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            
            Spacer(Modifier.height(32.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    if (enabled) "ON" else "OFF",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = typewriterFont,
                        fontSize = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
        }

        if (enabled) {
                Spacer(Modifier.height(32.dp))
                
                // Sync status
                when (syncStatus) {
                    SyncStatus.SYNCING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            syncMessage,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = typewriterFont,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    SyncStatus.SYNCED -> {
                        Text(
                            syncMessage,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = typewriterFont,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    SyncStatus.ERROR -> {
                        Text(
                            syncMessage,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = typewriterFont,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }

                // DNS Setup Guidance
                if (showGuidance && syncStatus == SyncStatus.SYNCED) {
                    Spacer(Modifier.height(32.dp))
                    
                    Text(
                        "DNS Setup Instructions:",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = typewriterFont,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    if (!certInstalled) {
                        Text(
                            "1. Install CA Certificate (Required for Private DNS)",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = typewriterFont,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        
                        Spacer(Modifier.height(8.dp))
                        
                        Text(
                            "Android 11+ requires manual certificate installation through Settings.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 12.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Text(
                            "Note: Certificate only needs to be installed once and persists until removed.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Text(
                            "⚠️ Important: Private DNS won't work with 'localhost' because it requires port 853, but our DoT server uses port 1853 (no root). Use DoH (port 8443) instead, or root your device to use port 853.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // Step 1 with inline button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            Text(
                                "1. Tap ",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                            Button(
                                onClick = {
                                    try {
                                        val success = CertHelper.saveCertToDownloads(act)
                                        if (success) {
                                            Log.d("HNSGo", "Certificate saved to Downloads")
                                        } else {
                                            Log.e("HNSGo", "Failed to save certificate")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("HNSGo", "Error saving certificate", e)
                                    }
                                },
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    "Save Certificate",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = typewriterFont,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                            Text(
                                " to store in downloads",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                        }
                        
                        // Step 2 with inline button
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            Text(
                                "2. Install the certificate in your ",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                            )
                            Button(
                                onClick = {
                                    try {
                                        // Open certificate installation settings directly
                                        CertHelper.openCertificateInstallSettings(act)
                                    } catch (e: Exception) {
                                        Log.e("HNSGo", "Error opening settings", e)
                                        // Fallback to general settings if specific intent fails
                                        try {
                                            act.startActivity(Intent(Settings.ACTION_SETTINGS))
                                        } catch (e2: Exception) {
                                            Log.e("HNSGo", "Error opening fallback settings", e2)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .height(32.dp)
                                    .padding(horizontal = 6.dp)
                            ) {
                                Text(
                                    "Settings",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = typewriterFont,
                                        fontSize = 11.sp
                                    ),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        
                        Text(
                            "3. Select VPN or WiFi and install the certificate 'hns-go-ca.crt' from Downloads",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        
                        Text(
                            "4. Enter name 'HNS Go CA' and tap 'OK'",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // Button to mark certificate as installed
                        Button(
                            onClick = {
                                CertHelper.markCertAsInstalled(act)
                                certInstalled = true
                                Log.d("HNSGo", "User marked certificate as installed")
                            },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Text(
                                "I've installed the certificate",
                                fontFamily = typewriterFont
                            )
                        }
                    } else {
                        Text(
                            "✓ CA Certificate is installed",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = typewriterFont,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "5. Configure DNS (DoH recommended)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = typewriterFont,
                            fontSize = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    Spacer(Modifier.height(4.dp))
                    
                    Text(
                        "Note: Private DNS (DoT) requires port 853 and won't work with localhost. Configure your browser/app to use DoH endpoint:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // DoH URL with copy button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            "https://127.0.0.1:8443/dns-query",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("DoH Endpoint", "https://127.0.0.1:8443/dns-query")
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(act, "DoH URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                    Log.d("HNSGo", "DoH URL copied to clipboard")
                                }
                        )
                        IconButton(
                            onClick = {
                                val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("DoH Endpoint", "http://127.0.0.1:8443/dns-query")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(act, "DoH URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                Log.d("HNSGo", "DoH URL copied to clipboard")
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy DoH URL",
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        "Browser Configuration:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
                    )
                    
                    Spacer(Modifier.height(4.dp))
                    
                    Text(
                        "• Firefox: Settings → Network Settings → Enable DNS over HTTPS → Custom → Paste URL above",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    
                    Text(
                        "• Chrome: Settings → Privacy and security → Security → Advanced → Use secure DNS → Custom → Paste URL",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    
                    Text(
                        "• Other apps: Check their DNS/Network settings for DoH configuration",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "6. (Optional) Enable DANE in Firefox",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = typewriterFont,
                            fontSize = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        "Firefox: about:config > security.dane.enabled = true",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontSize = 12.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                }
            }
        }
        
        // Learn More & Disclaimer - fixed at bottom above copyright
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 48.dp), // Space for copyright
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Learn More Card
            ExpandableCard(
                title = "Learn more",
                expanded = learnMoreExpanded.value,
                onExpand = { learnMoreExpanded.value = true },
                onCollapse = { learnMoreExpanded.value = false }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "How to uninstall the HNS Go certificate:",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = typewriterFont,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "1. Open Settings → Security → Encryption & credentials → User credentials",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    Text(
                        "2. Find 'HNS Go CA' → Tap → Remove",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    Text(
                        "About Private DNS:",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = typewriterFont,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Private DNS (DoT) requires port 853, but our server uses port 1853 (no root).",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    Text(
                        "Use DoH endpoint (https://127.0.0.1:8443/dns-query) in apps that support it.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    
                    Spacer(Modifier.height(12.dp))
                    
                    Text(
                        "How to revert to normal settings:",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = typewriterFont,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    
                    Text(
                        "1. Stop HNS Go service:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
                    )
                    Text(
                        "   • Toggle off the HNS Go switch in the app",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        "2. Remove CA Certificate:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
                    )
                    Text(
                        "   • Open Settings → Security → Encryption & credentials → User credentials",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    Text(
                        "   • Find 'HNS Go CA' → Tap → Remove",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        "3. Revert browser DNS settings:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
                    )
                    Text(
                        "   • Firefox: Settings → Network Settings → Disable DNS over HTTPS or set to Default",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    Text(
                        "   • Chrome: Settings → Privacy and security → Security → Use secure DNS → With your current service provider",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        "4. Revert Private DNS (if changed):",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
                    )
                    Text(
                        "   • Open Settings → Network & internet → Private DNS",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    Text(
                        "   • Select 'Off' or 'Automatic' (not 'localhost')",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                }
            }
            
                Spacer(Modifier.height(8.dp))
            
            // Disclaimer Card
            ExpandableCard(
                title = "Disclaimer",
                expanded = disclaimerExpanded.value,
                onExpand = { disclaimerExpanded.value = true },
                onCollapse = { disclaimerExpanded.value = false }
            ) {
                Text(
                    """
                    This app is provided "as is" for educational purposes only, to compensate for the lack of native support for Handshake top-level domains (TLDs) in web browsers and operating systems.
                    
                    The developer cannot be held responsible for any loss of data, damage to device, or any other consequences arising from the use of this application.
                    
                    You are solely responsible for:
                    • Installing the self-signed certificate
                    • Configuring Private DNS
                    • Understanding the security implications
                    
                    Use at your own risk.
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = typewriterFont
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        
        // Copyright at bottom
        Text(
            "© 2025 - acktarius",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = typewriterFont,
                fontSize = 12.sp
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}