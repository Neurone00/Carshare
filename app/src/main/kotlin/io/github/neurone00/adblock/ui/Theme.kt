package io.github.neurone00.adblock.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Adbrella palette: calm sky blues, a splash of teal, nothing alarming. */
object Brand {
    val Sky = Color(0xFF42A5F5)
    val SkyDeep = Color(0xFF1E88E5)
    val Teal = Color(0xFF26C6DA)
    val Cloud = Color(0xFFF3F8FD)
    val Night = Color(0xFF0F1A26)
    val Rain = Color(0xFF90CAF9)

    /** Hero gradient when the umbrella is open. */
    val OpenGradient = Brush.linearGradient(listOf(Color(0xFF4FC3F7), Color(0xFF1E88E5), Color(0xFF26C6DA)))

    /** Hero gradient when it is closed: grey drizzle. */
    val ClosedGradient = Brush.linearGradient(listOf(Color(0xFF90A4AE), Color(0xFF607D8B)))
}

private val LightScheme = lightColorScheme(
    primary = Brand.SkyDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6ECFF),
    onPrimaryContainer = Color(0xFF0B3A63),
    secondary = Brand.Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0F5F9),
    onSecondaryContainer = Color(0xFF00363C),
    background = Brand.Cloud,
    surface = Color.White,
    surfaceVariant = Color(0xFFE6EFF7),
    onSurfaceVariant = Color(0xFF44546A),
    error = Color(0xFFD84315),
)

private val DarkScheme = darkColorScheme(
    primary = Brand.Rain,
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF0D4A80),
    onPrimaryContainer = Color(0xFFD6ECFF),
    secondary = Color(0xFF80DEEA),
    onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF004F57),
    onSecondaryContainer = Color(0xFFD0F5F9),
    background = Brand.Night,
    surface = Color(0xFF17232F),
    surfaceVariant = Color(0xFF243242),
    onSurfaceVariant = Color(0xFFB7C4D3),
    error = Color(0xFFFF8A65),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AdBlockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        shapes = AppShapes,
        content = content,
    )
}
