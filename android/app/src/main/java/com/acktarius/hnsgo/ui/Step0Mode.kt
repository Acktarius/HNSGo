package com.acktarius.hnsgo.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
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
fun Step0Mode(
    onSwitchToEasyMode: () -> Unit,
    typewriterFont: FontFamily
) {
    Spacer(Modifier.height(4.dp))
    
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Left-aligned "Geek mode" text
        Text(
            "Geek mode ...",
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = typewriterFont,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        
        // Right-aligned "Easy mode" with tiled icon
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    "Easy mode ",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = typewriterFont,
                        fontSize = 14.sp
                    ),
                    color = androidx.compose.ui.graphics.Color(0xFF757575) // Grey
                )
                Box(
                    modifier = Modifier
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(6.dp)
                        .clickable { onSwitchToEasyMode() }
                ) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = "Switch to easy mode",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
