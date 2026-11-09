package com.pishi.iveepn.vpn

import com.pishi.iveepn.data.SettingsStore.SplitTunnelMode

/**
 * The concrete per-app routing a TUN configuration applies, derived
 * from the split-tunnel settings. Exactly one of the two sets is
 * non-empty in effect: Android treats an allow-list as authoritative
 * (only those apps are routed), so the plan never mixes both forms.
 *
 * The app itself is never routed through its own tunnel: its control
 * traffic (update checks, reserve-domain fetches, the upstream
 * connection itself) must stay on the underlying network. In
 * allow-list mode it is silently dropped from the user's selection;
 * in the other modes it is always on the bypass list.
 */
internal data class SplitTunnelPlan(
    val allowedApps: Set<String>,
    val disallowedApps: Set<String>,
)

internal fun splitTunnelPlan(
    mode: SplitTunnelMode,
    excludedApps: Set<String>,
    includedApps: Set<String>,
    ownPackage: String,
): SplitTunnelPlan = when (mode) {
    SplitTunnelMode.ALL ->
        SplitTunnelPlan(
            allowedApps = emptySet(),
            disallowedApps = setOf(ownPackage),
        )

    SplitTunnelMode.EXCLUDE_SELECTED ->
        SplitTunnelPlan(
            allowedApps = emptySet(),
            disallowedApps = excludedApps + ownPackage,
        )

    SplitTunnelMode.ONLY_SELECTED -> {
        val allowed = includedApps - ownPackage
        if (allowed.isEmpty()) {
            // An allow-list with nothing effectively selected would
            // leave the tunnel serving no app at all. Fall back to the
            // default all-apps routing instead of a dead tunnel; the
            // settings UI also refuses to save this state.
            SplitTunnelPlan(
                allowedApps = emptySet(),
                disallowedApps = setOf(ownPackage),
            )
        } else {
            SplitTunnelPlan(
                allowedApps = allowed,
                disallowedApps = emptySet(),
            )
        }
    }
}
