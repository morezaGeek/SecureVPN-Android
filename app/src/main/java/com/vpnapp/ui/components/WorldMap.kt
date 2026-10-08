package com.vpnapp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.vpnapp.data.ConnectionState
import com.vpnapp.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WorldMapBackground(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
) {
    // Keep the full-screen decoration static while the small progress ring moves.
    val glowAlpha = if (connectionState == ConnectionState.CONNECTED) 0.5f else 0.4f

    val primaryColor = when (connectionState) {
        ConnectionState.CONNECTED -> ConnectedGreen
        ConnectionState.CONNECTING -> ConnectingAmber
        else -> CurrentAccent
    }
    
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        // Cache paths to avoid object allocation (fixes high CPU)
        val worldPaths = remember(width, height) {
            if (width == 0f || height == 0f) return@remember emptyList<Path>()
            
            val northAmerica = Path().apply {
                moveTo(width * 0.1f, height * 0.25f)
                cubicTo(width * 0.15f, height * 0.2f, width * 0.25f, height * 0.15f, width * 0.28f, height * 0.3f)
                cubicTo(width * 0.3f, height * 0.45f, width * 0.22f, height * 0.5f, width * 0.15f, height * 0.45f)
                close()
            }
            val europe = Path().apply {
                moveTo(width * 0.45f, height * 0.2f)
                cubicTo(width * 0.52f, height * 0.18f, width * 0.55f, height * 0.25f, width * 0.53f, height * 0.35f)
                cubicTo(width * 0.48f, height * 0.38f, width * 0.42f, height * 0.32f, width * 0.45f, height * 0.2f)
                close()
            }
            val asia = Path().apply {
                moveTo(width * 0.55f, height * 0.2f)
                cubicTo(width * 0.75f, height * 0.15f, width * 0.85f, height * 0.25f, width * 0.8f, height * 0.45f)
                cubicTo(width * 0.7f, height * 0.5f, width * 0.6f, height * 0.4f, width * 0.55f, height * 0.2f)
                close()
            }
            val africa = Path().apply {
                moveTo(width * 0.48f, height * 0.4f)
                cubicTo(width * 0.52f, height * 0.38f, width * 0.55f, height * 0.5f, width * 0.52f, height * 0.65f)
                cubicTo(width * 0.48f, height * 0.7f, width * 0.44f, height * 0.55f, width * 0.48f, height * 0.4f)
                close()
            }
            val australia = Path().apply {
                moveTo(width * 0.78f, height * 0.6f)
                cubicTo(width * 0.85f, height * 0.58f, width * 0.88f, height * 0.68f, width * 0.82f, height * 0.72f)
                cubicTo(width * 0.76f, height * 0.7f, width * 0.75f, height * 0.63f, width * 0.78f, height * 0.6f)
                close()
            }
            listOf(northAmerica, europe, asia, africa, australia)
        }

        val gridPaths = remember(width, height) {
            if (width == 0f || height == 0f) return@remember emptyList<Path>()
            val paths = mutableListOf<Path>()
            val lineCount = 12
            for (i in 0..lineCount) {
                val x = width * i.toFloat() / lineCount.toFloat()
                paths.add(Path().apply {
                    moveTo(x, 0f)
                    var yStep = 0
                    while (yStep <= 100) {
                        val yPos = height * yStep.toFloat() / 100f
                        val curve = sin(yStep.toFloat() * 0.03f) * width * 0.02f
                        lineTo(x + curve, yPos)
                        yStep += 10
                    }
                })
            }
            for (i in 0..8) {
                val y = height * i.toFloat() / 8f
                paths.add(Path().apply {
                    moveTo(0f, y)
                    var xStep = 0
                    while (xStep <= 100) {
                        val xPos = width * xStep.toFloat() / 100f
                        val curve = cos(xStep.toFloat() * 0.05f) * height * 0.01f
                        lineTo(xPos, y + curve)
                        xStep += 10
                    }
                })
            }
            paths
        }
        
        val locations = remember(width, height) {
            listOf(
                Offset(width * 0.2f, height * 0.35f),   // US East
                Offset(width * 0.15f, height * 0.4f),   // US West
                Offset(width * 0.48f, height * 0.28f),  // UK
                Offset(width * 0.5f, height * 0.25f),   // Netherlands
                Offset(width * 0.52f, height * 0.3f),   // Germany
                Offset(width * 0.82f, height * 0.35f),  // Japan
                Offset(width * 0.75f, height * 0.5f),   // Singapore
                Offset(width * 0.82f, height * 0.65f),  // Australia
            )
        }
        
        // Use drawWithCache to prevent CPU overhead during recomposition
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    onDrawBehind {


                        
                        // Draw grid lines
                        val gridColor = MapLines.copy(alpha = glowAlpha * 0.2f)
                        gridPaths.forEach { path ->
                            drawPath(path = path, color = gridColor, style = Stroke(width = 0.5f))
                        }
                        
                        // Draw world map outline
                        val outlineColor = primaryColor.copy(alpha = glowAlpha * 0.4f)
                        worldPaths.forEach { path ->
                            drawPath(path = path, color = outlineColor, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
                        }
                        
                        // Draw connection nodes
                        drawServerNodes(primaryColor, 1f, connectionState, locations)
                    }
                }
        )
    }
}

private fun DrawScope.drawServerNodes(
    color: Color,
    pulse: Float,
    connectionState: ConnectionState,
    locations: List<Offset>
) {
    val glowColor = color.copy(alpha = 0.3f * pulse)
    val dotAlphaColor = color.copy(alpha = 0.8f)
    val dotRadius = 4f * (0.8f + pulse * 0.2f)
    
    locations.forEach { offset ->
        // Glow effect - radial gradients are expensive if recreated, but at 60fps with new alpha it's often unavoidable.
        // However, we minimize other allocations.
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to glowColor,
                1.0f to Color.Transparent,
                center = offset,
                radius = 20f
            ),
            radius = 20f,
            center = offset
        )
        
        // Core dot
        drawCircle(
            color = dotAlphaColor,
            radius = dotRadius,
            center = offset
        )
    }
    
    // Connection line when connected
    if (connectionState == ConnectionState.CONNECTED && locations.isNotEmpty()) {
        val startPoint = Offset(size.width * 0.5f, size.height * 0.5f) // Center
        val endPoint = locations[0] // Connect to first server
        
        drawLine(
            brush = Brush.linearGradient(
                0.0f to color.copy(alpha = 0.6f),
                1.0f to color.copy(alpha = 0.1f),
                start = startPoint,
                end = endPoint
            ),
            start = startPoint,
            end = endPoint,
            strokeWidth = 2f * pulse,
            cap = StrokeCap.Round
        )
    }
}

