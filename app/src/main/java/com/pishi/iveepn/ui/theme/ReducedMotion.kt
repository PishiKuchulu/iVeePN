package com.pishi.iveepn.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True unless the user has turned animations off system-wide (animator
 * duration scale 0 — the "Remove animations" accessibility setting).
 * Decorative infinite animations must check this and render a static state
 * instead; functional progress indication is unaffected.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f) != 0f
    }
}
