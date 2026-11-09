package com.pishi.iveepn

import com.pishi.iveepn.data.SettingsStore.SplitTunnelMode
import com.pishi.iveepn.vpn.splitTunnelPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelTest {

    private val own = "com.pishi.iveepn"

    @Test
    fun allModeBypassesOnlyOwnApp() {
        val plan = splitTunnelPlan(
            SplitTunnelMode.ALL,
            excludedApps = setOf("a.b"),
            includedApps = setOf("c.d"),
            ownPackage = own,
        )
        assertTrue(plan.allowedApps.isEmpty())
        assertEquals(setOf(own), plan.disallowedApps)
    }

    @Test
    fun excludeModeBypassesSelectedPlusOwn() {
        val plan = splitTunnelPlan(
            SplitTunnelMode.EXCLUDE_SELECTED,
            excludedApps = setOf("a.b", "c.d"),
            includedApps = emptySet(),
            ownPackage = own,
        )
        assertTrue(plan.allowedApps.isEmpty())
        assertEquals(setOf("a.b", "c.d", own), plan.disallowedApps)
    }

    @Test
    fun onlyModeAllowsExactlyTheSelection() {
        val plan = splitTunnelPlan(
            SplitTunnelMode.ONLY_SELECTED,
            excludedApps = setOf("x.y"),
            includedApps = setOf("a.b", "c.d"),
            ownPackage = own,
        )
        assertEquals(setOf("a.b", "c.d"), plan.allowedApps)
        assertTrue(plan.disallowedApps.isEmpty())
    }

    @Test
    fun onlyModeNeverRoutesOwnApp() {
        val plan = splitTunnelPlan(
            SplitTunnelMode.ONLY_SELECTED,
            excludedApps = emptySet(),
            includedApps = setOf("a.b", own),
            ownPackage = own,
        )
        assertEquals(setOf("a.b"), plan.allowedApps)
    }

    @Test
    fun onlyModeWithEmptySelectionFallsBackToAllApps() {
        val plan = splitTunnelPlan(
            SplitTunnelMode.ONLY_SELECTED,
            excludedApps = emptySet(),
            includedApps = emptySet(),
            ownPackage = own,
        )
        assertTrue(plan.allowedApps.isEmpty())
        assertEquals(setOf(own), plan.disallowedApps)
    }

    @Test
    fun onlyModeWithOnlyOwnAppSelectedFallsBackToAllApps() {
        val plan = splitTunnelPlan(
            SplitTunnelMode.ONLY_SELECTED,
            excludedApps = emptySet(),
            includedApps = setOf(own),
            ownPackage = own,
        )
        assertTrue(plan.allowedApps.isEmpty())
        assertEquals(setOf(own), plan.disallowedApps)
    }

    @Test
    fun storedModeParsingDefaultsToExclude() {
        assertEquals(SplitTunnelMode.ALL, SplitTunnelMode.fromStored("ALL"))
        assertEquals(SplitTunnelMode.ONLY_SELECTED, SplitTunnelMode.fromStored("ONLY_SELECTED"))
        assertEquals(SplitTunnelMode.EXCLUDE_SELECTED, SplitTunnelMode.fromStored(null))
        assertEquals(SplitTunnelMode.EXCLUDE_SELECTED, SplitTunnelMode.fromStored("nonsense"))
    }
}
