package com.example.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val VanguardDarkColorScheme = darkColorScheme(
    primary = RtxEmerald,
    onPrimary = ObsidianVoid,
    primaryContainer = RtxEmeraldDark,
    onPrimaryContainer = TextPrimary,
    secondary = TacticalAmber,
    onSecondary = ObsidianVoid,
    tertiary = IceCyan,
    onTertiary = ObsidianVoid,
    background = ObsidianVoid,
    onBackground = TextPrimary,
    surface = TacticalSurface,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedGunmetal,
    onSurfaceVariant = TextSecondary,
    outline = BorderSteel,
    error = ThreatCrimson,
    onError = TextPrimary
)

val VanguardShapes = Shapes(
    small = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
    medium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
    large = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)
)

@Composable
fun VanguardTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VanguardDarkColorScheme,
        typography = VanguardTypography,
        shapes = VanguardShapes,
        content = content
    )
}
