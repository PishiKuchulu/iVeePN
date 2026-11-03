package com.pishi.iveepn.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// iVeePN design system — direction #2 "Modern Premium".
// Brand anchors come from the VPeeN desktop identity (blue #3E7AF4,
// success green #00D68F); the Material schemes below are tonal derivations
// of those anchors so text/background pairs keep WCAG-grade contrast in
// both themes (e.g. white on the light primary #3369D6 is ~5.0:1, the dark
// onPrimary on the lightened dark primary is ~7:1).
val BrandBlue = Color(0xFF3E7AF4)
val BrandGreen = Color(0xFF00D68F)

/**
 * Semantic connection-status colours, consumed by Home, the QS tile logic
 * in-app, and any status surface. [connected] is the brand green (darkened
 * one step in the light scheme so status text stays ≥4.5:1 on light
 * surfaces); [warning] is reserved for exposed/degraded states such as the
 * LAN-exposed local proxy.
 */
data class IVeePNStatusColors(
    val connected: Color,
    val connecting: Color,
    val disconnected: Color,
    val warning: Color,
)

val LightStatusColors = IVeePNStatusColors(
    connected = Color(0xFF00855F),
    connecting = Color(0xFF8A5A00),
    disconnected = Color(0xFF5F6368),
    warning = Color(0xFF9A4A00),
)

val DarkStatusColors = IVeePNStatusColors(
    connected = BrandGreen,
    connecting = Color(0xFFFFC46B),
    disconnected = Color(0xFF9AA0A6),
    warning = Color(0xFFFFB25E),
)

val LocalIVeePNStatusColors = staticCompositionLocalOf { LightStatusColors }

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3369D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE4FD),
    onPrimaryContainer = Color(0xFF0A2A5E),
    secondary = Color(0xFF00855F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9F5E4),
    onSecondaryContainer = Color(0xFF00382A),
    tertiary = Color(0xFF9A6200),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE7B8),
    onTertiaryContainer = Color(0xFF3D2700),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F9FD),
    onBackground = Color(0xFF161B26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161B26),
    surfaceVariant = Color(0xFFE3E8F2),
    onSurfaceVariant = Color(0xFF434A59),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3FA),
    surfaceContainer = Color(0xFFEAEFF8),
    surfaceContainerHigh = Color(0xFFE3EAF6),
    surfaceContainerHighest = Color(0xFFDCE4F2),
    outline = Color(0xFF737A89),
    outlineVariant = Color(0xFFC3CAD8),
    inverseSurface = Color(0xFF2B303C),
    inverseOnSurface = Color(0xFFEEF1F8),
    inversePrimary = Color(0xFFADC6FF),
    scrim = Color(0xFF000000),
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7FA6FF),
    onPrimary = Color(0xFF0A1C3F),
    primaryContainer = Color(0xFF1D3A6E),
    onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = BrandGreen,
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF00513C),
    onSecondaryContainer = Color(0xFF8CF7CE),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF3D2700),
    tertiaryContainer = Color(0xFF5C3D00),
    onTertiaryContainer = Color(0xFFFFE3AE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1119),
    onBackground = Color(0xFFE4E8F2),
    surface = Color(0xFF121722),
    onSurface = Color(0xFFE4E8F2),
    surfaceVariant = Color(0xFF232B3B),
    onSurfaceVariant = Color(0xFFB9C1D1),
    surfaceContainerLowest = Color(0xFF0A0E16),
    surfaceContainerLow = Color(0xFF161C29),
    surfaceContainer = Color(0xFF1A2231),
    surfaceContainerHigh = Color(0xFF232C3F),
    surfaceContainerHighest = Color(0xFF2C364C),
    outline = Color(0xFF8A93A5),
    outlineVariant = Color(0xFF3A4356),
    inverseSurface = Color(0xFFE4E8F2),
    inverseOnSurface = Color(0xFF232936),
    inversePrimary = Color(0xFF3369D6),
    scrim = Color(0xFF000000),
)
