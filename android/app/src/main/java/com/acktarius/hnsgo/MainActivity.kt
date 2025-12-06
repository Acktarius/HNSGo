package com.acktarius.hnsgo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
    var debugQueryResult by remember { mutableStateOf<String?>(null) }
    var debugQueryStatus by remember { mutableStateOf("") }
    var step2Completed by remember { mutableStateOf(false) }
    var step3Completed by remember { mutableStateOf(false) }
    var daneUrl by remember { mutableStateOf("") }
    var daneVerifying by remember { mutableStateOf(false) }
    var daneResult by remember { mutableStateOf<DaneVerifier.VerificationResult?>(null) }
    val scope = rememberCoroutineScope()
    val typewriterFont = FontFamily.Monospace
    
    // Check certificate installation status on mount
    LaunchedEffect(Unit) {
        certInstalled = CertHelper.isCAInstalledSync(act)
        Log.d("HNSGo", "Certificate installed status: $certInstalled")
    }
    
    // Listen for debug query results from DohService
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    val status = intent?.getStringExtra("status") ?: ""
                    val result = intent?.getStringExtra("result")
                    // BroadcastReceiver callbacks run on main thread, so we can update state directly
                    debugQueryStatus = status
                    debugQueryResult = result
                    Log.d("HNSGo", "MainActivity: Received debug query result - status: $status, result: $result")
                } catch (e: Exception) {
                    Log.e("HNSGo", "Error handling debug query result", e)
                }
            }
        }
        try {
            // Use RECEIVER_NOT_EXPORTED since this is an internal broadcast (our service to our activity)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                act.registerReceiver(receiver, android.content.IntentFilter("com.acktarius.hnsgo.DEBUG_QUERY_RESULT"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                act.registerReceiver(receiver, android.content.IntentFilter("com.acktarius.hnsgo.DEBUG_QUERY_RESULT"))
            }
        } catch (e: Exception) {
            Log.e("HNSGo", "Error registering broadcast receiver", e)
        }
        
        // Cleanup on dispose
        onDispose {
            try {
                act.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Receiver might not be registered or already unregistered
                Log.d("HNSGo", "Error unregistering receiver (may already be unregistered): ${e.message}")
            }
        }
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
                    // Configure resolver to use external Handshake resolver
                    SpvClient.setResolver(Config.DEBUG_RESOLVER_HOST, Config.DEBUG_RESOLVER_PORT)
                    SpvClient.init(act.filesDir, act)
                    
                    // Check if we're caught up with network
                    val networkHeight = SpvClient.getNetworkHeight()
                    val ourHeight = SpvClient.getChainHeight()
                    
                    if (networkHeight != null && ourHeight < networkHeight) {
                        val behind = networkHeight - ourHeight
                        syncStatus = SyncStatus.SYNCING
                        syncMessage = "Syncing... ($ourHeight / $networkHeight)"
                        Log.d("HNSGo", "SPV sync in progress: $ourHeight / $networkHeight (behind by $behind blocks)")
                    } else {
                        syncStatus = SyncStatus.SYNCED
                        syncMessage = "Sync complete"
                        Log.d("HNSGo", "SPV sync completed successfully (height: $ourHeight)")
                    }
                    
                    // Continue syncing headers in background to catch up to network
                    // This is important because resolution may fail if we're too far behind peers
                    scope.launch(Dispatchers.IO) {
                        try {
                            // Start a coroutine to periodically update UI during sync
                            val uiUpdateJob = scope.launch(Dispatchers.IO) {
                                while (true) {
                                    delay(5000) // Update every 5 seconds
                                    val currentHeight = SpvClient.getChainHeight()
                                    val currentNetworkHeight = SpvClient.getNetworkHeight()
                                    
                                    withContext(Dispatchers.Main) {
                                        if (currentNetworkHeight != null) {
                                            val stillBehind = currentNetworkHeight - currentHeight
                                            if (stillBehind <= 10) {
                                                syncStatus = SyncStatus.SYNCED
                                                syncMessage = "Sync complete"
                                            } else {
                                                syncStatus = SyncStatus.SYNCING
                                                syncMessage = "Syncing... ($currentHeight / $currentNetworkHeight)"
                                            }
                                        } else {
                                            syncStatus = SyncStatus.SYNCING
                                            syncMessage = "Syncing... ($currentHeight / ?)"
                                        }
                                    }
                                }
                            }
                            
                            // Continue syncing in background (non-blocking) - this will loop until caught up
                            val updatedNetworkHeight = SpvClient.continueSync()
                            val updatedOurHeight = SpvClient.getChainHeight()
                            
                            // Cancel UI update job
                            uiUpdateJob.cancel()
                            
                            // Final UI update
                            if (updatedNetworkHeight != null) {
                                val stillBehind = updatedNetworkHeight - updatedOurHeight
                                if (stillBehind <= 10) { // Within 10 blocks = caught up
                                    withContext(Dispatchers.Main) {
                                        syncStatus = SyncStatus.SYNCED
                                        syncMessage = "Sync complete"
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        syncStatus = SyncStatus.SYNCING
                                        syncMessage = "Syncing... ($updatedOurHeight / $updatedNetworkHeight)"
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("HNSGo", "Background sync error (non-critical): ${e.message}")
                        }
                    }
                    
                    // Start DoH server - debug query will happen in service after servers are ready
                    Log.d("HNSGo", "Starting DoH service...")
                    ContextCompat.startForegroundService(act, Intent(act, DohService::class.java))
                    showGuidance = true
                    
                    // Set initial debug status - will be updated by service when ready
                    debugQueryStatus = "Waiting for DoH server..."
                    debugQueryResult = "Service starting, will test resolution when ready"
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
            debugQueryResult = null
            debugQueryStatus = ""
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
                    onCheckedChange = { newValue ->
                        enabled = newValue
                        // Explicitly handle state change to avoid OVERRIDE_UNSET warning
                        Log.d("HNSGo", "Switch state changed to: $newValue")
                    }
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
                    
                    // Step 1: Install CA Certificate
                    Text(
                        "Step 1: Install CA Certificate",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = typewriterFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    if (!certInstalled) {
                        Text(
                            "The CA certificate must be installed in Android's trust store for Firefox to trust the DoH server.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // Step 1.1: Save certificate
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            Text(
                                "1.1 Tap ",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            Button(
                                onClick = {
                                    try {
                                        val success = CertHelper.saveCertToDownloads(act)
                                        if (success) {
                                            Toast.makeText(act, "Certificate saved to Downloads", Toast.LENGTH_SHORT).show()
                                            Log.d("HNSGo", "Certificate saved to Downloads")
                                        } else {
                                            Toast.makeText(act, "Failed to save certificate", Toast.LENGTH_SHORT).show()
                                            Log.e("HNSGo", "Failed to save certificate")
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
                                " to Downloads",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        Text(
                            "1.2 Tap the button below to open certificate installation settings",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Button(
                            onClick = {
                                try {
                                    CertHelper.openCertificateInstallSettings(act)
                                } catch (e: Exception) {
                                    Log.e("HNSGo", "Error opening settings", e)
                                    Toast.makeText(act, "Error opening settings", Toast.LENGTH_SHORT).show()
                                    try {
                                        act.startActivity(Intent(Settings.ACTION_SETTINGS))
                                    } catch (e2: Exception) {
                                        Log.e("HNSGo", "Error opening fallback settings", e2)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            Text(
                                "Open Certificate Settings",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                )
                            )
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        Text(
                            "1.3 Tap 'Install from storage' → Select 'hns-go-ca.crt' from Downloads",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Text(
                            "1.4 When prompted, select 'CA certificate' (NOT 'VPN & app user certificate' or 'Wi-Fi')",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Text(
                            "⚠️ Important: You must select 'CA certificate' for Firefox to trust the DoH server",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Text(
                            "1.5 Enter name 'HNS Go CA' → Tap OK",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                        
                        Spacer(Modifier.height(8.dp))
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            Button(
                                onClick = {
                                    CertHelper.markCertAsInstalled(act)
                                    certInstalled = true
                                    Toast.makeText(act, "Marked as installed", Toast.LENGTH_SHORT).show()
                                    Log.d("HNSGo", "User marked certificate as installed")
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    "I've Installed It",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = typewriterFont,
                                        fontSize = 11.sp
                                    )
                                )
                            }
                        }
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Text(
                            "⚠️ Certificate must be installed before Firefox can use DoH",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                        )
                    } else {
                        Text(
                            "✓ CA Certificate is installed",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = typewriterFont,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // Step 2: Enable Third-Party CA in Firefox
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Checkbox(
                            checked = step2Completed,
                            onCheckedChange = { step2Completed = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Step 2: Enable Third-Party CA in Firefox (Required)",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = typewriterFont,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    if (!step2Completed) {
                        Spacer(Modifier.height(8.dp))
                        
                        Text(
                            "Firefox requires an additional setting to trust user-installed CA certificates:",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                "2.1 Open Firefox → Settings → About Firefox",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            
                            Spacer(Modifier.height(4.dp))
                            
                            Text(
                                "2.2 Tap the Firefox logo 7 times to enable Secret Settings",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            
                            Spacer(Modifier.height(4.dp))
                            
                            Text(
                                "2.3 Go back to Settings → Enable 'Use third party CA certificates'",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            
                            Spacer(Modifier.height(4.dp))
                            
                            Text(
                                "⚠️ Without this step, Firefox will reject the DoH server certificate",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // Step 3: Configure Firefox DoH
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Checkbox(
                            checked = step3Completed,
                            onCheckedChange = { step3Completed = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Step 3: Configure Firefox DoH",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = typewriterFont,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    if (!step3Completed) {
                        Spacer(Modifier.height(8.dp))
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                "3.1 Open Firefox → Settings → Network Settings",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            
                            Spacer(Modifier.height(4.dp))
                            
                            Text(
                                "3.2 Enable 'DNS over HTTPS' → Select 'Custom'",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            
                            Spacer(Modifier.height(4.dp))
                            
                            Text(
                                "3.3 Paste the DoH URL below:",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // DoH URL with copy button
                        Card(
                            modifier = Modifier.fillMaxWidth(0.95f),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Text(
                                    "https://127.0.0.1:${Config.DOH_PORT}/dns-query",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = typewriterFont,
                                        fontSize = 11.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("DoH Endpoint", "https://127.0.0.1:${Config.DOH_PORT}/dns-query")
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(act, "DoH URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                            Log.d("HNSGo", "DoH URL copied to clipboard")
                                        }
                                )
                                IconButton(
                                    onClick = {
                                        val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("DoH Endpoint", "https://127.0.0.1:${Config.DOH_PORT}/dns-query")
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
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                "3.4 Save settings and restart Firefox",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            
                            Spacer(Modifier.height(8.dp))
                            
                            Text(
                                "3.5 After restarting Firefox, visit this URL to accept the certificate:",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // Health check URL with copy and open buttons
                        Card(
                            modifier = Modifier.fillMaxWidth(0.95f),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "https://127.0.0.1:${Config.DOH_PORT}/health",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = typewriterFont,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("Health Check URL", "https://127.0.0.1:${Config.DOH_PORT}/health")
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(act, "Health check URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                            }
                                    )
                                    IconButton(
                                        onClick = {
                                            val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Health Check URL", "https://127.0.0.1:${Config.DOH_PORT}/health")
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(act, "Health check URL copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = "Copy Health Check URL",
                                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(Modifier.height(4.dp))
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(start = 8.dp)
                        ) {
                            Text(
                                "This will trigger Firefox to show a certificate warning. Click 'Advanced' → 'Accept the Risk and Continue'.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                            )
                            
                            Spacer(Modifier.height(4.dp))
                            
                            Text(
                                "⚠️ Firefox must accept the certificate before DoH queries will work",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                            )
                        }
                        
                        Spacer(Modifier.height(8.dp))                        
                        
                        Text(
                            "To verify: Open Firefox → about:networking#dns → Check if queries show your DoH URL",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = typewriterFont,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        "DANE Security Inspector",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = typewriterFont,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        "Verify DANE (TLSA) certificate for Handshake websites before opening in Firefox:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = daneUrl,
                        onValueChange = { daneUrl = it },
                        label = {
                            Text(
                                "URL (e.g., https://nathan.woodburn/)",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        ),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = typewriterFont,
                            fontSize = 11.sp
                        ),
                        enabled = !daneVerifying
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (daneUrl.isNotBlank()) {
                                    daneVerifying = true
                                    daneResult = null
                                    scope.launch {
                                        try {
                                            val result = DaneVerifier.verify(daneUrl)
                                            daneResult = result
                                        } catch (e: Exception) {
                                            Log.e("HNSGo", "DANE verification error", e)
                                            daneResult = DaneVerifier.VerificationResult(
                                                isValid = false,
                                                message = "DANE status: cannot verify this certificate against TLSA. Browse at your own risk.",
                                                tlsaFound = false,
                                                certificateFound = false
                                            )
                                        } finally {
                                            daneVerifying = false
                                        }
                                    }
                                }
                            },
                            enabled = !daneVerifying && daneUrl.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (daneVerifying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                "Verify DANE",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                )
                            )
                        }
                        
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(daneUrl))
                                    act.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(act, "Error opening URL: ${e.message}", Toast.LENGTH_SHORT).show()
                                    Log.e("HNSGo", "Error opening URL in Firefox", e)
                                }
                            },
                            enabled = daneResult?.isValid == true && !daneVerifying,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "Open in Firefox",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = typewriterFont,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                    
                    if (daneResult != null) {
                        Spacer(Modifier.height(12.dp))
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(0.9f),
                            colors = CardDefaults.cardColors(
                                containerColor = if (daneResult!!.isValid) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                } else {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                }
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    daneResult!!.message,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = typewriterFont,
                                        fontSize = 11.sp,
                                        fontWeight = if (daneResult!!.isValid) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (daneResult!!.isValid) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                                    }
                                )
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))

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
                        "Use DoH endpoint (https://127.0.0.1:${Config.DOH_PORT}/dns-query) in apps that support it.",
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
