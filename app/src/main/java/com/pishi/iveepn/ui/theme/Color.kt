package com.pishi.iveepn.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// iVeePN design system — "Soft UI" (owner-approved direction, Penpot file
// "iVeePN Soft UI", from the owner's reference photo): a pale grey page
// (#EEF0F3), pure-white rounded cards, ink text (#14181D) and a bright
// teal accent (#2FBFAE) with a pale teal wash (#DFF6F2) for selected and
// highlighted surfaces. The dark scheme is the same system on deep slate
// so both themes share one visual language.
//
// Contrast notes: the bright teal is a graphic/fill accent — filled
// controls pair it with near-black teal ink (onPrimary, ~6:1). Where teal
// must carry TEXT on white (tabs, text buttons, the selected check), the
// deeper SoftTealDeep is used instead (~5.9:1 on white). The photo's
// #9AA0A6 grey is decorative only; the scheme's onSurfaceVariant is a
// deepened derivative so secondary text stays ≥4.5:1.
val SoftTeal = Color(0xFF2FBFAE)
val SoftTealDeep = Color(0xFF0B6E5D)
val SoftTealPale = Color(0xFFDFF6F2)
val SoftBackground = Color(0xFFEEF0F3)
val SoftInk = Color(0xFF14181D)

// Legacy brand anchors from the VPeeN desktop identity, kept for the
// status palette (dark-theme connected green).
val BrandBlue = Color(0xFF3E7AF4)
val BrandGreen = Color(0xFF00D68F)

/**
 * Semantic connection-status colours, consumed by Home, the QS tile logic
 * in-app, and any status surface. [connected] is a deep teal-green in the
 * light scheme so status text stays ≥4.5:1 on light surfaces; [warning] is
 * reserved for exposed/degraded states such as the LAN-exposed local
 * proxy.
 */
data class IVeePNStatusColors(
    val connected: Color,
    val connecting: Color,
    val disconnected: Color,
    val warning: Color,
)

val LightStatusColors = IVeePNStatusColors(
    connected = Color(0xFF0B7A55),
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
    primary = SoftTeal,
    onPrimary = Color(0xFF06302A),
    primaryContainer = SoftTealPale,
    onPrimaryContainer = Color(0xFF0B3B34),
    secondary = SoftTealDeep,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEEE6),
    onSecondaryContainer = Color(0xFF06382C),
    tertiary = Color(0xFF9A6200),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE7B8),
    onTertiaryContainer = Color(0xFF3D2700),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = SoftBackground,
    onBackground = SoftInk,
    surface = Color(0xFFFFFFFF),
    onSurface = SoftInk,
    surfaceVariant = Color(0xFFE2E7EC),
    onSurfaceVariant = Color(0xFF4A5560),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FA),
    surfaceContainer = Color(0xFFEDF0F3),
    surfaceContainerHigh = Color(0xFFE4E9EE),
    surfaceContainerHighest = Color(0xFFDAE0E7),
    outline = Color(0xFF8A94A0),
    outlineVariant = Color(0xFFD5DBE1),
    inverseSurface = Color(0xFF232A31),
    inverseOnSurface = Color(0xFFEDF1F5),
    inversePrimary = Color(0xFF7FE3D3),
    scrim = Color(0xFF000000),
)

val DarkColorScheme = darkColorScheme(
    primary = SoftTeal,
    onPrimary = Color(0xFF06302A),
    primaryContainer = Color(0xFF0E3A34),
    onPrimaryContainer = Color(0xFFB9EFE6),
    secondary = Color(0xFF4FD1BC),
    onSecondary = Color(0xFF06382C),
    secondaryContainer = Color(0xFF0C4A3E),
    onSecondaryContainer = Color(0xFFA9EADB),
    tertiary = Color(0xFFFFC46B),
    onTertiary = Color(0xFF3D2700),
    tertiaryContainer = Color(0xFF5C3D00),
    onTertiaryContainer = Color(0xFFFFE3AE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1318),
    onBackground = Color(0xFFE9EEF3),
    surface = Color(0xFF1B232C),
    onSurface = Color(0xFFE9EEF3),
    surfaceVariant = Color(0xFF26313C),
    onSurfaceVariant = Color(0xFFA9B4BF),
    surfaceContainerLowest = Color(0xFF0A0E12),
    surfaceContainerLow = Color(0xFF151C23),
    surfaceContainer = Color(0xFF1B232C),
    surfaceContainerHigh = Color(0xFF232E39),
    surfaceContainerHighest = Color(0xFF2C3947),
    outline = Color(0xFF5A6874),
    outlineVariant = Color(0xFF2C3844),
    inverseSurface = Color(0xFFE9EEF3),
    inverseOnSurface = Color(0xFF1B232C),
    inversePrimary = SoftTealDeep,
    scrim = Color(0xFF000000),
)
