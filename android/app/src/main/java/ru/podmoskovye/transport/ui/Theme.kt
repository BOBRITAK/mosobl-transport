package ru.podmoskovye.transport.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Палитра приложения: тёмно-синий и графит, яркие цвета линий.
object Pal {
    val Bg = Color(0xFF070B14)
    val BgElevated = Color(0xFF0D1420)
    val Panel = Color(0xFF131B29)
    val PanelHigh = Color(0xFF1A2334)
    val Line = Color(0x1FFFFFFF)
    val LineStrong = Color(0x33FFFFFF)
    val Text = Color(0xFFEAF0FA)
    val Muted = Color(0xFF94A3B8)
    val Muted2 = Color(0xFF6D7C93)
    val Accent = Color(0xFFFF3A55)
    val AccentSoft = Color(0xFFFF6B7F)
    val Ok = Color(0xFF2FBF71)
    val Warn = Color(0xFFFFB020)
}

@Composable
fun TransportTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Pal.Accent,
        onPrimary = Color.White,
        secondary = Pal.AccentSoft,
        background = Pal.Bg,
        onBackground = Pal.Text,
        surface = Pal.Panel,
        onSurface = Pal.Text,
        surfaceVariant = Pal.PanelHigh,
        onSurfaceVariant = Pal.Muted,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography.copy(
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
            labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, fontWeight = FontWeight.Medium),
        ),
        content = content,
    )
}

fun lineColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    Pal.Muted
}
