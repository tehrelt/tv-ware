package com.tvware.launcher.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object LauncherColors {
    val BgBlack = Color(0xFF0A0A0B)
    val BgGraphiteTop = Color(0xFF1A1A1C)
    val BgGraphiteBottom = Color(0xFF101012)
    val SurfaceCard = Color(0xFF1C1C1E)
    val SurfaceElevated = Color(0xFF2C2C2E)
    val FocusSurface = Color(0xFFF5F5F7)
    val FocusContent = Color(0xFF141415)
    val Accent = Color(0xFF0A84FF)
    val VpnActive = Color(0xFF4CC38A)
    val VpnActiveOnLight = Color(0xFF16A34A)
    val VpnInactive = Color(0xFF85858B)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFFA1A1A6)
    val TextMuted = Color(0xFF85858B)
    val BorderSubtle = Color(0x14F5F5F7)
    val FocusRing = Color(0xFFF5F5F7)
}

private val DarkColors = darkColorScheme(
    primary = LauncherColors.Accent,
    onPrimary = LauncherColors.TextPrimary,
    surface = LauncherColors.SurfaceCard,
    onSurface = LauncherColors.TextPrimary,
    surfaceVariant = LauncherColors.SurfaceElevated,
    onSurfaceVariant = LauncherColors.TextSecondary,
    background = LauncherColors.BgBlack,
    onBackground = LauncherColors.TextPrimary,
)

private val LauncherTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Medium,
        color = LauncherColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = LauncherColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        color = LauncherColors.TextPrimary,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        color = LauncherColors.TextMuted,
    ),
)

@Composable
fun TvLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = LauncherTypography,
        content = content,
    )
}