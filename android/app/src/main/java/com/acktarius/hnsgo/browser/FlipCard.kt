package com.acktarius.hnsgo.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Flip card composable that animates between two content views
 * @param frontContent Content shown on the front (Regular Mode)
 * @param backContent Content shown on the back (Lazy Mode)
 * @param isFlipped Whether the card is currently flipped
 * @param onFlip Callback when flip is triggered
 * @param showSwitchButton Whether to show the switch button (only when sync is complete)
 */
@Composable
fun FlipCard(
    frontContent: @Composable () -> Unit,
    backContent: @Composable () -> Unit,
    isFlipped: Boolean,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    showSwitchButton: Boolean = true
) {
    val animatedRotationY by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "flipRotation"
    )
    
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    Box(modifier = modifier.fillMaxSize()) {
        // Front card (Regular Mode)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = animatedRotationY
                    cameraDistance = 8f * density.density
                }
                .zIndex(if (animatedRotationY < 90f) 1f else 0f)
        ) {
            if (animatedRotationY < 90f) {
                frontContent()
            }
        }
        
        // Back card (Lazy Mode)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = animatedRotationY + 180f
                    cameraDistance = 8f * density.density
                }
                .zIndex(if (animatedRotationY >= 90f) 1f else 0f)
        ) {
            if (animatedRotationY >= 90f) {
                backContent()
            }
        }
        
        // Floating action button to flip (only show when sync is complete)
        if (showSwitchButton) {
            FloatingActionButton(
                onClick = onFlip,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = if (isFlipped) {
                    androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
                } else {
                    androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (isFlipped) {
                    androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = if (isFlipped) "Switch to Regular Mode" else "Switch to Easy Mode"
                )
            }
        }
    }
}
