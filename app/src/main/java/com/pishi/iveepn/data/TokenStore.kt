package com.pishi.iveepn.data

import android.content.Context
import com.pishi.iveepn.data.model.VeePNServer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** Cached guest access token (F-01/F-17). */
data class GuestToken(
    val access: String,
    val fetchedAtMs: Long,
)

/** Cached per-region server list (F-03). */
data class CachedServerList(
    val servers: List<VeePNServer>,
    val fetchedAtMs: Long,
)

/**
 * Secure store for the VeePN guest session (F-17): the stable install id,
 * the guest access token, the reserve-domain cache and per-region server
 * lists. Backed by EncryptedSharedPreferences via
 * [createEncryptedPrefsWithRecovery], which fails closed to a session-only
 * in-memory store — never plaintext on disk.
 */
class TokenStore(context: Context) {

    private val prefs = createEncryptedPrefsWithRecovery(context, "iveepn_tokens")

    /** Stable per-install UUID, generated once (sent to VeePN at launch). */
    val udid: String
        get() {
            prefs.getString(KEY_UDID, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UDID, fresh).apply()
            return fresh
        }

    fun guestToken(): GuestToken? {
        val access = prefs.getString(KEY_GUEST_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return GuestToken(access, prefs.getLong(KEY_GUEST_FETCHED_AT, 0L))
    }

    fun saveGuestToken(access: String, fetchedAtMs: Long) {
        prefs.edit()
            .putString(KEY_GUEST_ACCESS, access)
            .putLong(KEY_GUEST_FETCHED_AT, fetchedAtMs)
            .apply()
    }

    fun clearGuestToken() {
        prefs.edit().remove(KEY_GUEST_ACCESS).remove(KEY_GUEST_FETCHED_AT).apply()
    }

    fun cachedReserveDomains(): Pair<List<String>, Long>? {
        val raw = prefs.getString(KEY_RESERVE_DOMAINS, null)?.takeIf { it.isNotBlank() } ?: return null
        val fetchedAt = prefs.getLong(KEY_RESERVE_FETCHED_AT, 0L)
        val domains = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return if (domains.isEmpty()) null else domains to fetchedAt
    }

    fun saveReserveDomains(domains: List<String>, fetchedAtMs: Long) {
        prefs.edit()
            .putString(KEY_RESERVE_DOMAINS, domains.joinToString("\n"))
            .putLong(KEY_RESERVE_FETCHED_AT, fetchedAtMs)
            .apply()
    }

    fun cachedServerList(region: String): CachedServerList? {
        val raw = prefs.getString(serverListKey(region), null)?.takeIf { it.isNotBlank() } ?: return null
        val fetchedAt = prefs.getLong(serverListTimeKey(region), 0L)
        val servers = runCatching { Json.decodeFromString(ListSerializer(VeePNServer.serializer()), raw) }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return CachedServerList(servers, fetchedAt)
    }

    fun saveServerList(region: String, servers: List<VeePNServer>, fetchedAtMs: Long) {
        if (servers.isEmpty()) return
        val raw = Json.encodeToString(ListSerializer(VeePNServer.serializer()), servers)
        prefs.edit()
            .putString(serverListKey(region), raw)
            .putLong(serverListTimeKey(region), fetchedAtMs)
            .apply()
    }

    private fun serverListKey(region: String) = "servers_${region.lowercase()}"
    private fun serverListTimeKey(region: String) = "servers_at_${region.lowercase()}"

    companion object {
        private const val KEY_UDID = "udid"
        private const val KEY_GUEST_ACCESS = "guest_access"
        private const val KEY_GUEST_FETCHED_AT = "guest_fetched_at"
        private const val KEY_RESERVE_DOMAINS = "reserve_domains"
        private const val KEY_RESERVE_FETCHED_AT = "reserve_fetched_at"
    }
}
