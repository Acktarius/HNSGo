package com.acktarius.hnsgo.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * IconButton with a glowing effect that appears briefly after clicking
 * The glow fades out over 0.2 seconds to provide visual feedback
 */
@Composable
fun GlowingIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    glowColor: Color = Color(0xFFBB86FC), // Primary purple
    glowDuration: Int = 200, // Duration in milliseconds
    icon: @Composable () -> Unit
) {
    var isGlowing by remember { mutableStateOf(false) }
    
    // Animate glow fade-out after click
    val glowAlpha by animateFloatAsState(
        targetValue = if (isGlowing) 0.8f else 0f,
        animationSpec = tween(durationMillis = glowDuration),
        label = "glow_alpha"
    )
    
    val glowSize by animateFloatAsState(
        targetValue = if (isGlowing) 1.4f else 1f,
        animationSpec = tween(durationMillis = glowDuration),
        label = "glow_size"
    )
    
    // Reset glow state after animation completes
    LaunchedEffect(isGlowing) {
        if (isGlowing) {
            delay(glowDuration.toLong())
            isGlowing = false
        }
    }
    
    Box(
        modifier = modifier.size(40.dp),
        contentAlignment = Alignment.Center
    ) {
        // Glow effect that appears and fades after click
        if (glowAlpha > 0f) {
            Box(
                modifier = Modifier
                    .size((40.dp * glowSize))
                    .graphicsLayer {
                        alpha = glowAlpha
                        shape = CircleShape
                        clip = true
                    }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                glowColor.copy(alpha = glowAlpha),
                                glowColor.copy(alpha = glowAlpha * 0.6f),
                                glowColor.copy(alpha = glowAlpha * 0.3f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }
        
        IconButton(
            onClick = {
                isGlowing = true // Trigger glow animation
                onClick() // Execute the actual click action
            },
            enabled = enabled,
            modifier = Modifier.size(40.dp)
        ) {
            icon()
        }
    }
}
