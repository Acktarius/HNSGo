package com.acktarius.hnsgo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acktarius.hnsgo.Config
import com.acktarius.hnsgo.util.FirefoxUtils

@Composable
fun Step3FirefoxDoH(
    step3Completed: Boolean,
    onStep3CompletedChange: (Boolean) -> Unit,
    act: Context,
    firefoxAvailable: Boolean,
    typewriterFont: FontFamily
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(0.95f)
    ) {
        Checkbox(
            checked = step3Completed,
            onCheckedChange = onStep3CompletedChange
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
                .fillMaxWidth(0.95f)
        ) {
            Text(
                "3.1 Open Firefox → Settings → Privacy and security",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = typewriterFont,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            )
            
            Spacer(Modifier.height(4.dp))
            
            Text(
                "3.2 Enable 'DNS over HTTPS' → Select 'Max Protection'",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = typewriterFont,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
            )
            
            Spacer(Modifier.height(4.dp))
            
            Text(
                "3.3 Choose provider 'Custom' and paste the DoH URL below:",
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
                        }
                )
                IconButton(
                    onClick = {
                        val clipboard = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("DoH Endpoint", "https://127.0.0.1:${Config.DOH_PORT}/dns-query")
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(act, "DoH URL copied to clipboard", Toast.LENGTH_SHORT).show()
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
        ) {
            Text(
                "3.4 Quit (Force Stop) and restart Firefox",
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
        
        // Add button to open Firefox directly if available
        if (firefoxAvailable) {
            Spacer(Modifier.height(8.dp))
            
            Button(
                onClick = {
                    try {
                        val firefoxPackage = FirefoxUtils.getFirefoxPackageName(act)
                        val healthUrl = "https://127.0.0.1:${Config.DOH_PORT}/health"
                        
                        requireNotNull(firefoxPackage) { "Firefox package not found" }
                        
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(healthUrl)).apply {
                            setPackage(firefoxPackage)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        act.startActivity(intent)
                        Toast.makeText(act, "Opening Firefox...", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(act, "Error opening Firefox: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(0.9f)
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
        
        Spacer(Modifier.height(4.dp))
        
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
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
}

