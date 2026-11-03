package com.pishi.iveepn.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** In-app language choice (Settings → Language). */
enum class AppLanguage {
    SYSTEM,
    FA,
    EN,
}

/**
 * Resolves the language tag the UI should render with.
 *
 * Product rule (foundation): Persian is the default language. When the user
 * has never made a choice (the preference is unset), the app runs in
 * Persian — UNLESS the system locale is English, in which case it follows
 * the system and runs in English. MainActivity persists that resolved
 * default as an explicit choice on first launch, so from then on only an
 * explicit SYSTEM choice follows the device locale.
 */
fun SettingsStore.resolvedLanguageTag(): String = when (appLanguage) {
    AppLanguage.FA -> "fa"
    AppLanguage.EN -> "en"
    AppLanguage.SYSTEM -> Locale.getDefault().language.ifBlank { "en" }
    null -> if (Locale.getDefault().language == "en") "en" else "fa"
}

/**
 * Returns a context whose configuration carries the app language (locale +
 * layout direction). Used by the activity (attachBaseContext), the VPN
 * service, the QS tile and the widget so every user-visible surface renders
 * in the chosen language, not just the Compose screens.
 */
fun Context.withAppLocale(store: SettingsStore): Context {
    val locale = Locale.forLanguageTag(store.resolvedLanguageTag())
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return createConfigurationContext(config)
}
