package com.acktarius.hnsgo.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acktarius.hnsgo.CertHelper
import com.acktarius.hnsgo.MainActivity

@Composable
fun Step1Certificate(
    certInstalled: Boolean,
    onCertInstalledChange: (Boolean) -> Unit,
    act: MainActivity,
    typewriterFont: FontFamily
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(0.95f)
    ) {
        Checkbox(
            checked = certInstalled,
            onCheckedChange = { checked ->
                if (checked) {
                    CertHelper.markCertAsInstalled(act)
                }
                onCertInstalledChange(checked)
            }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Step 1: Install CA Certificate",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = typewriterFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
    }
    
    if (!certInstalled) {
        Column(
            modifier = Modifier.fillMaxWidth(0.95f)
        ) {
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
                verticalAlignment = Alignment.CenterVertically
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
                            } else {
                                Toast.makeText(act, "Failed to save certificate", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
            
            // Step 1.2: Open certificate settings
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "1.2 Tap the button below, to go to Security and privacy settings, from there, select More security & Privacy → Encryption & Credentials → Install a certificate",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = typewriterFont,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(Modifier.height(4.dp))
            
            Button(
                onClick = {
                    try {
                        CertHelper.openCertificateInstallSettings(act)
                    } catch (e: Exception) {
                        Toast.makeText(act, "Error opening settings", Toast.LENGTH_SHORT).show()
                        try {
                            act.startActivity(Intent(Settings.ACTION_SETTINGS))
                        } catch (e2: Exception) {
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Security & Privacy settings",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = typewriterFont,
                        fontSize = 11.sp
                    )
                )
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Step 1.3: Install from storage
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "1.3 Tap on 'CA certificate' → 'Install anyway'",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = typewriterFont,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Step 1.4: Install from storage
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "1.4 locate 'hns-go-ca.crt' in Downloads and select it",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = typewriterFont,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(Modifier.height(8.dp))
            // Step 1.5: Enter certificate name
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "1.5 Enter name 'HNS Go CA' → Tap OK",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = typewriterFont,
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f)
                )
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
        }
    } else {
        Spacer(Modifier.height(4.dp))
        
        Text(
            "✓ CA Certificate is installed",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = typewriterFont,
                fontSize = 10.sp
            ),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        )
    }
}

