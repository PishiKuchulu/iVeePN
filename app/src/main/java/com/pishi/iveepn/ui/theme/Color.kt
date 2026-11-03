package com.pishi.iveepn.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Placeholder UI — original iVeePN design lands in P4.
// Neutral status colours only; no brand palette yet.
data class IVeePNStatusColors(
    val connected: Color,
    val connecting: Color,
    val disconnected: Color,
)

val LightStatusColors = IVeePNStatusColors(
    connected = Color(0xFF1B6E2F),
    connecting = Color(0xFF8A5A00),
    disconnected = Color(0xFF5F6368),
)

val DarkStatusColors = IVeePNStatusColors(
    connected = Color(0xFF7BDF92),
    connecting = Color(0xFFFFC46B),
    disconnected = Color(0xFF9AA0A6),
)

val LocalIVeePNStatusColors = staticCompositionLocalOf { LightStatusColors }
