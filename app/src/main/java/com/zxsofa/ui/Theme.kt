package com.zxsofa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object ZxColors {
    val Red = Color(0xFFD80000)
    val Yellow = Color(0xFFF2D000)
    val Green = Color(0xFF00C000)
    val Cyan = Color(0xFF00C8E8)
    val Background = Color(0xFF0E0E10)
    val Surface = Color(0xFF1A1A1E)
    val SurfaceHigh = Color(0xFF26262C)
}

private val scheme = darkColorScheme(
    primary = ZxColors.Cyan,
    onPrimary = Color.Black,
    secondary = ZxColors.Yellow,
    onSecondary = Color.Black,
    tertiary = ZxColors.Green,
    error = ZxColors.Red,
    background = ZxColors.Background,
    surface = ZxColors.Surface,
    surfaceVariant = ZxColors.SurfaceHigh,
    surfaceContainer = ZxColors.Surface,
    surfaceContainerHigh = ZxColors.SurfaceHigh,
    surfaceContainerHighest = ZxColors.SurfaceHigh,
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFFB5B5BD),
)

@Composable
fun ZXSofaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

/** Las cuatro franjas del arcoíris del Spectrum. */
@Composable
fun RainbowStripes(modifier: Modifier = Modifier, stripe: Dp = 6.dp) {
    Row(modifier.height(stripe * 2)) {
        listOf(ZxColors.Red, ZxColors.Yellow, ZxColors.Green, ZxColors.Cyan).forEach {
            Box(Modifier.width(stripe * 2).fillMaxHeight().background(it))
        }
    }
}
