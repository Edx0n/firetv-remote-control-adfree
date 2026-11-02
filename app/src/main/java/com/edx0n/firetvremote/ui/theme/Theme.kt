package com.edx0n.firetvremote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFF9900)
val Ink = Color(0xFF232F3E)
val ButtonFace = Color(0xFF242932)
val ButtonFacePressed = Color(0xFF353C48)
val DpadRing = Color(0xFF1C2028)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1C1300),
    primaryContainer = Color(0xFF4A3300),
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Color(0xFF9DB7DA),
    onSecondary = Color(0xFF0B1D33),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF14171C),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF242932),
    onSurfaceVariant = Color(0xFFAAB1BC),
    surfaceContainer = Color(0xFF1A1E24),
    surfaceContainerHigh = Color(0xFF20252C),
    surfaceContainerHighest = Color(0xFF2A3038),
    outline = Color(0xFF4A515C),
    error = Color(0xFFFF7B72),
)

@Composable
fun FireRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
