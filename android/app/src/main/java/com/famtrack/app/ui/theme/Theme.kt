package com.famtrack.app.ui.theme

import android.app.Activity
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

private val LightColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = TealDark,
    secondary = TealDark,
    onSecondary = Color.White,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = TealDark,
    tertiary = Coral,
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFFEFF1F3),
    onSurfaceVariant = TextSecondary,
    error = Coral,
    onError = Color.White,
    outline = Divider
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8ED8E1),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF0D4F5B),
    onPrimaryContainer = Color(0xFFB5F2F6),
    secondary = Color(0xFF78D7D4),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF07504F),
    onSecondaryContainer = Color(0xFF9AF2EF),
    tertiary = Color(0xFFFFB870),
    background = Color(0xFF0C1820),
    onBackground = Color(0xFFE5F1F5),
    surface = Color(0xFF12242E),
    onSurface = Color(0xFFE5F1F5),
    surfaceVariant = Color(0xFF203640),
    onSurfaceVariant = Color(0xFFB5C8CE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF84969D)
)

@Composable
fun FamTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}