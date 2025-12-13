package com.acktarius.hnsgo.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acktarius.hnsgo.MainActivity
import com.acktarius.hnsgo.adblocker.AdBlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Step4AdBlocking(
    step4Completed: Boolean,
    onStep4CompletedChange: (Boolean) -> Unit,
    adBlockingEnabled: Boolean,
    onAdBlockingEnabledChange: (Boolean) -> Unit,
    adBlockingLoading: Boolean,
    onAdBlockingLoadingChange: (Boolean) -> Unit,
    privacyModeEnabled: Boolean,
    onPrivacyModeEnabledChange: (Boolean) -> Unit,
    act: MainActivity,
    scope: CoroutineScope,
    typewriterFont: FontFamily
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(0.95f)
    ) {
        Checkbox(
            checked = step4Completed,
            onCheckedChange = onStep4CompletedChange
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Step 4: Enable Ad Blocking",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = typewriterFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
    }
    
    if (!step4Completed) {
        Spacer(Modifier.height(8.dp))
        
        Text(
            "Block ads and trackers by using a blacklist from known references:",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = typewriterFont,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
        )
        
        Spacer(Modifier.height(8.dp))
        
        // Row 1: Main toggle switch
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Switch(
                checked = adBlockingEnabled,
                onCheckedChange = { newValue ->
                    if (newValue) {
                        // Enable ad blocking and refresh blacklist when turned ON
                        AdBlockManager.enable()
                        onAdBlockingEnabledChange(true)
                        onAdBlockingLoadingChange(true)
                        scope.launch(Dispatchers.IO) {
                            try {
                                AdBlockManager.refreshBlacklist()
                                withContext(Dispatchers.Main) {
                                    onAdBlockingLoadingChange(false)
                                    Toast.makeText(act, "Ad blocking enabled", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    onAdBlockingLoadingChange(false)
                                    AdBlockManager.disable()
                                    onAdBlockingEnabledChange(false)
                                    Toast.makeText(act, "Error loading blacklist: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        AdBlockManager.disable()
                        onAdBlockingEnabledChange(false)
                        onPrivacyModeEnabledChange(false)  // Privacy mode requires ad blocking
                        Toast.makeText(act, "Ad blocking disabled", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !adBlockingLoading
            )
            Spacer(Modifier.width(16.dp))
            if (adBlockingLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (adBlockingEnabled) "ON" else "OFF",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = typewriterFont,
                    fontSize = 14.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "(list is updated once toggled on, sources: StevenBlack, OISD, AdGuard)",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = typewriterFont,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(Modifier.height(8.dp))
        
        // Row 2: Privacy Mode Toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Switch(
                checked = privacyModeEnabled,
                onCheckedChange = { newValue ->
                    onPrivacyModeEnabledChange(newValue)
                    AdBlockManager.setPrivacyMode(newValue)
                    Toast.makeText(act, "Privacy mode ${if (newValue) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
                },
                enabled = adBlockingEnabled && !adBlockingLoading
            )
            Spacer(Modifier.width(16.dp))
            Text(
                "Stricter Mode minimize tracking",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = typewriterFont,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

