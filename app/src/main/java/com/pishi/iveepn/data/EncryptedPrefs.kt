package com.pishi.iveepn.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

private const val TAG = "EncryptedPrefs"

/**
 * EncryptedSharedPreferences can fail permanently when the keystore
 * entry or the stored ciphertext is corrupted (a known failure mode
 * of security-crypto), and letting that exception escape from
 * Application.onCreate would be an unrecoverable crash loop on every
 * launch.
 *
 * Recovery strategy, fail CLOSED: try to open the encrypted store; on
 * a likely-corruption failure, delete the corrupted store and retry
 * once (the guest session is simply re-established afterwards). If it
 * still fails — or the failure does not look like corruption — the
 * last resort is a session-only in-memory store: the app keeps
 * working for this launch, but NOTHING is written to disk, so the
 * session must be re-established after restart.
 *
 * There is deliberately NO plaintext fallback any more. A previous
 * incarnation of this code fell back to
 * `getSharedPreferences(..._plain_fallback)` on the second failure,
 * which wrote the install UDID and the guest access token to disk
 * UNENCRYPTED — and no code ever read that file back or deleted it,
 * so the plaintext copy sat there indefinitely. Any leftover
 * `_plain_fallback` file from that version is deleted best-effort
 * whenever this function runs (it was never read by any version, so
 * deletion loses nothing).
 */
fun createEncryptedPrefsWithRecovery(context: Context, name: String): SharedPreferences {
    val appContext = context.applicationContext
    fun build(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // Legacy plaintext fallback files from the old fail-open path are
    // never read by any version: deleting them removes plaintext
    // tokens without losing anything the app can use.
    fun deleteLegacyPlaintextFallback() {
        runCatching { appContext.deleteSharedPreferences("${name}_plain_fallback") }
    }

    try {
        return build().also { deleteLegacyPlaintextFallback() }
    } catch (first: Exception) {
        AppLogger.w(TAG, "encrypted store '$name' could not be opened", first)
        if (first !is GeneralSecurityException && first !is IOException) {
            // Not a corrupted store: a transient failure (keystore not
            // yet unlocked, device in a restricted state, …). Deleting
            // the store would wipe the UDID and guest token for no
            // reason — skip the reset and fall through to the
            // session-only in-memory store for this launch instead.
            AppLogger.w(
                TAG,
                "failure does not look like store corruption; keeping the stored data and " +
                    "using a session-only in-memory store for this launch",
            )
            deleteLegacyPlaintextFallback()
            return InMemorySharedPreferences()
        }
        AppLogger.w(TAG, "encrypted store '$name' looks corrupted; resetting it")
    }
    runCatching { appContext.deleteSharedPreferences(name) }
    try {
        return build().also { deleteLegacyPlaintextFallback() }
    } catch (second: Exception) {
        AppLogger.e(
            TAG,
            "encrypted store '$name' still unavailable after reset; using a session-only " +
                "in-memory store — nothing is written to disk, and the session must be " +
                "re-entered after restart",
            second,
        )
        deleteLegacyPlaintextFallback()
        return InMemorySharedPreferences()
    }
}
