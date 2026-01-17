package com.expedition.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Premium Dark Color Scheme - Primary focus for motorcycle riders at night
private val ExpeditionDarkColorScheme = darkColorScheme(
    // Primary - Expedition Orange
    primary = ExpeditionOrange,
    onPrimary = Color.White,
    primaryContainer = ExpeditionOrangeDark,
    onPrimaryContainer = Color.White,
    
    // Secondary - Deep Blue
    secondary = ExpeditionBlue,
    onSecondary = Color.White,
    secondaryContainer = ExpeditionBlueDark,
    onSecondaryContainer = Color.White,
    
    // Tertiary - Electric Green
    tertiary = ExpeditionGreen,
    onTertiary = Color.White,
    tertiaryContainer = ExpeditionGreenDark,
    onTertiaryContainer = Color.White,
    
    // Background & Surface
    background = DarkBackground,
    onBackground = TextPrimaryDark,
    surface = DarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    
    // Other
    error = StatusError,
    onError = Color.White,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color.White,
    outline = Color(0xFF444C56),
    outlineVariant = Color(0xFF30363D),
    inverseSurface = LightSurface,
    inverseOnSurface = TextPrimaryLight,
    inversePrimary = ExpeditionOrangeDark,
    scrim = Color.Black
)

// Light Color Scheme - For daytime use
private val ExpeditionLightColorScheme = lightColorScheme(
    // Primary - Expedition Orange
    primary = ExpeditionOrangeDark,
    onPrimary = Color.White,
    primaryContainer = ExpeditionOrangeLight,
    onPrimaryContainer = Color(0xFF3D1400),
    
    // Secondary - Deep Blue
    secondary = ExpeditionBlue,
    onSecondary = Color.White,
    secondaryContainer = ExpeditionBlueLight,
    onSecondaryContainer = Color(0xFF001A41),
    
    // Tertiary - Electric Green
    tertiary = ExpeditionGreen,
    onTertiary = Color.White,
    tertiaryContainer = ExpeditionGreenLight,
    onTertiaryContainer = Color(0xFF002111),
    
    // Background & Surface
    background = LightBackground,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    
    // Other
    error = StatusError,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFD0D7DE),
    outlineVariant = Color(0xFFE1E4E8),
    inverseSurface = DarkSurface,
    inverseOnSurface = TextPrimaryDark,
    inversePrimary = ExpeditionOrangeLight,
    scrim = Color.Black
)

@Composable
fun ExpeditionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Disable dynamic color to use our custom brand colors
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        ExpeditionDarkColorScheme
    } else {
        ExpeditionLightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            
            // Use dark status bar background for immersive feel
            window.statusBarColor = if (darkTheme) {
                DarkBackground.toArgb()
            } else {
                LightBackground.toArgb()
            }
            
            // Set status bar icons color
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            
            // Make navigation bar match the theme
            window.navigationBarColor = if (darkTheme) {
                DarkBackground.toArgb()
            } else {
                LightBackground.toArgb()
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ExpeditionShapes,
        content = content
    )
}
