package com.vpnapp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Theme mode enum
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

// Global theme state with persistence
object ThemeState {
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    
    fun saveTheme(context: android.content.Context) {
        val prefs = context.getSharedPreferences("vpn_app_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("theme_mode", themeMode.name).apply()
    }
    
    fun loadTheme(context: android.content.Context) {
        val prefs = context.getSharedPreferences("vpn_app_prefs", android.content.Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
        themeMode = try {
            ThemeMode.valueOf(savedTheme ?: ThemeMode.SYSTEM.name)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = DarkBackground,
    primaryContainer = AccentBlue,
    onPrimaryContainer = TextPrimary,
    
    secondary = AccentPurple,
    onSecondary = TextPrimary,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = TextPrimary,
    
    tertiary = AccentPink,
    onTertiary = TextPrimary,
    
    background = DarkBackground,
    onBackground = TextPrimary,
    
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    
    error = DisconnectedRed,
    onError = TextPrimary,
    
    outline = TextMuted,
    outlineVariant = DarkCard
)

private val LightColorScheme = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = AccentCyan.copy(alpha = 0.2f),
    onPrimaryContainer = LightTextPrimary,
    
    secondary = AccentPurple,
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightTextPrimary,
    
    tertiary = AccentPink,
    onTertiary = Color.White,
    
    background = LightBackground,
    onBackground = LightTextPrimary,
    
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    
    error = DisconnectedRed,
    onError = Color.White,
    
    outline = LightTextMuted,
    outlineVariant = LightCard
)

// Gradient brushes for dark theme
val BackgroundGradient: Brush
    @Composable get() = if (isAppInDarkTheme()) {
        Brush.verticalGradient(colors = listOf(GradientStart, GradientMiddle, GradientEnd))
    } else {
        Brush.verticalGradient(colors = listOf(LightGradientStart, LightGradientMiddle, LightGradientEnd))
    }

val CardGradient: Brush
    @Composable get() = if (isAppInDarkTheme()) {
        Brush.linearGradient(colors = listOf(DarkCard.copy(alpha = 0.8f), DarkSurfaceVariant.copy(alpha = 0.6f)))
    } else {
        Brush.linearGradient(colors = listOf(LightCard, LightSurfaceVariant.copy(alpha = 0.6f)))
    }

val AccentGradient = Brush.horizontalGradient(
    colors = listOf(AccentCyan, AccentBlue, AccentPurple)
)

val ConnectionButtonGradient = Brush.radialGradient(
    colors = listOf(
        AccentCyan.copy(alpha = 0.3f),
        AccentCyan.copy(alpha = 0.1f),
        Color.Transparent
    )
)

// Helper to determine if app is in dark theme
@Composable
fun isAppInDarkTheme(): Boolean {
    return when (ThemeState.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

// Dynamic colors based on theme
val CurrentTextPrimary: Color
    @Composable get() = if (isAppInDarkTheme()) TextPrimary else LightTextPrimary

val CurrentTextSecondary: Color
    @Composable get() = if (isAppInDarkTheme()) TextSecondary else LightTextSecondary

val CurrentTextMuted: Color
    @Composable get() = if (isAppInDarkTheme()) TextMuted else LightTextMuted

val CurrentBackground: Color
    @Composable get() = if (isAppInDarkTheme()) DarkBackground else LightBackground

val CurrentSurface: Color
    @Composable get() = if (isAppInDarkTheme()) DarkSurface else LightSurface

val CurrentCard: Color
    @Composable get() = if (isAppInDarkTheme()) DarkCard else LightCard

val CurrentSurfaceVariant: Color
    @Composable get() = if (isAppInDarkTheme()) DarkSurfaceVariant else LightSurfaceVariant

// Accent colors - darker in light theme for better visibility
val CurrentAccent: Color
    @Composable get() = if (isAppInDarkTheme()) AccentCyan else LightAccentCyan

val CurrentAccentSecondary: Color
    @Composable get() = if (isAppInDarkTheme()) AccentBlue else LightAccentBlue

@Composable
fun VPNAppTheme(
    content: @Composable () -> Unit
) {
    val darkTheme = isAppInDarkTheme()
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val backgroundColor = if (darkTheme) DarkBackground else LightBackground
            window.statusBarColor = backgroundColor.toArgb()
            window.navigationBarColor = backgroundColor.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(18.dp),
            medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp)
        ),
        content = content
    )
}
