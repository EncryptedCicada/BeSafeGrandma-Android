package com.citrushack.besafegrandma.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette used on Android < 12 (no dynamic color support)
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A6B3C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F5D0),
    secondary = Color(0xFF386554),
    background = Color(0xFFF5F5F7),
    surface = Color.White,
    onSurface = Color(0xFF1D1D1F),
    onBackground = Color(0xFF1D1D1F),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6DEBA0),
    onPrimary = Color(0xFF003920),
    primaryContainer = Color(0xFF00522F),
    secondary = Color(0xFF81CCA9),
    background = Color(0xFF0F1511),
    surface = Color(0xFF1A1F1C),
    onSurface = Color(0xFFE1E3E0),
    onBackground = Color(0xFFE1E3E0),
)

@Composable
fun BeSafeGrandmaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Dynamic color — adapts to the user's wallpaper on Android 12+
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}