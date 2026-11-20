package com.example.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Unified landscape adaptive scale metrics so UI never looks oversized on compact phones
 * or tiny/stretched on wide 20:9 / 21:9 phones & tablets.
 * Baseline design frame: 844dp x 390dp (modern landscape smartphone).
 */
@Immutable
data class ResponsiveUiMetrics(
    val widthDp: Dp = 844.dp,
    val heightDp: Dp = 390.dp,
    val scale: Float = 1.0f,
    val isCompactHeight: Boolean = false,
    val isWideScreen: Boolean = false
) {
    fun s(baseDp: Float): Dp = (baseDp * scale).dp
    fun sp(baseSp: Float): TextUnit = (baseSp * scale.coerceIn(0.84f, 1.18f)).sp
}

val LocalUiMetrics = compositionLocalOf { ResponsiveUiMetrics() }

@Composable
fun ResponsiveUiProvider(
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = maxWidth.value.coerceAtLeast(360f)
        val h = maxHeight.value.coerceAtLeast(300f)
        val scaleW = w / 844f
        val scaleH = h / 390f
        val computedScale = minOf(scaleW, scaleH).coerceIn(0.76f, 1.24f)

        val metrics = ResponsiveUiMetrics(
            widthDp = maxWidth,
            heightDp = maxHeight,
            scale = computedScale,
            isCompactHeight = h < 365f,
            isWideScreen = w > 900f
        )
        CompositionLocalProvider(LocalUiMetrics provides metrics) {
            content()
        }
    }
}
