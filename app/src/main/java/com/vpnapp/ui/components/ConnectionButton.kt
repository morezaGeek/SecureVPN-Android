package com.vpnapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vpnapp.data.ConnectionState
import com.vpnapp.ui.theme.*

@Composable
fun ConnectionButton(
    connectionState: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 180.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale = animateFloatAsState(if (pressed) 0.97f else 1f,
        tween(120), label = "powerPress")
    val targetColor = when (connectionState) {
        ConnectionState.CONNECTED -> ConnectedGreen
        ConnectionState.CONNECTING, ConnectionState.DISCONNECTING -> ConnectingAmber
        ConnectionState.ERROR -> DisconnectedRed
        else -> CurrentAccent
    }
    // Read animation values in drawing/layer phases, avoiding per-frame recomposition.
    val color = animateColorAsState(targetColor, tween(180), label = "powerColor")
    val busy = connectionState == ConnectionState.CONNECTING ||
        connectionState == ConnectionState.DISCONNECTING
    val dark = isAppInDarkTheme()
    val buttonBrush = remember(dark) {
        Brush.radialGradient(if (dark) listOf(DarkSurfaceVariant, DarkCard, DarkSurface)
            else listOf(LightSurfaceVariant, LightCard, LightSurface))
    }
    // Keep the measured size stable across states and keep rings within its bounds.
    Box(modifier = modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color.value.copy(alpha = 0.05f), radius = this.size.minDimension * 0.48f)
            drawCircle(color.value.copy(alpha = 0.08f), radius = this.size.minDimension * 0.40f)
        }
        if (busy) ConnectingRing(Modifier.fillMaxSize(), color)
        Box(
            modifier = Modifier.fillMaxSize(0.70f)
                .graphicsLayer { scaleX = pressScale.value; scaleY = pressScale.value }
                .clip(CircleShape).background(buttonBrush)
                .clickable(enabled = enabled && !busy, interactionSource = interactionSource,
                    indication = rememberRipple(color = targetColor), onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(color.value, radius = this.size.minDimension / 2 - 4.dp.toPx(),
                    style = Stroke(3.dp.toPx()))
            }
            Icon(Icons.Default.Power,
                contentDescription = when (connectionState) {
                    ConnectionState.CONNECTED -> "Disconnect"
                    ConnectionState.DISCONNECTING -> "Disconnecting..."
                    ConnectionState.CONNECTING -> "Connecting..."
                    else -> "Connect"
                }, modifier = Modifier.fillMaxSize(0.36f), tint = targetColor)
        }
    }
}

@Composable
private fun ConnectingRing(modifier: Modifier, color: State<Color>) {
    // Only this layer moves. Leaving the busy state disposes its animation.
    val transition = rememberInfiniteTransition(label = "connectionProgress")
    val angle = transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "rotation")
    Canvas(modifier.padding(6.dp).graphicsLayer { rotationZ = angle.value }) {
        drawArc(color.value.copy(alpha = 0.55f), -90f, 80f, false,
            style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
