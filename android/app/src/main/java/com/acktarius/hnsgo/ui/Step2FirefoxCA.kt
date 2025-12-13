package com.acktarius.hnsgo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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

@Composable
fun Step2FirefoxCA(
    step2Completed: Boolean,
    onStep2CompletedChange: (Boolean) -> Unit,
    typewriterFont: FontFamily
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(0.95f)
    ) {
        Checkbox(
            checked = step2Completed,
            onCheckedChange = onStep2CompletedChange
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Step 2: Enable Third-Party CA in Firefox",
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
                .fillMaxWidth(0.95f)
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
                "2.3 Go back to Settings → Secret Settings → Enable 'Use third party CA certificates'",
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
}

