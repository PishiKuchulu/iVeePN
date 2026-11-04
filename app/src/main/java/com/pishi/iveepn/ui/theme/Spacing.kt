package com.pishi.iveepn.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens on a strict 4/8dp scale. Every screen pads and spaces from
 * these tokens so rhythm is identical across Home, Locations, Settings and
 * Logs instead of each screen inventing its own values.
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp

    /** Horizontal padding for screen content. */
    val screenPadding: Dp = 16.dp

    /** Inner padding for cards. */
    val cardPadding: Dp = 16.dp

    /** Minimum height for any tappable control or row (accessibility). */
    val touchTarget: Dp = 48.dp

    /** Readable content never stretches wider than this (tablets/foldables). */
    val contentMaxWidth: Dp = 520.dp
}

/**
 * Soft UI shape scale: generously rounded at every step — 24dp is the
 * default card radius (the medium slot), sheets and dialogs go to 32dp.
 */
val IVeePNShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
