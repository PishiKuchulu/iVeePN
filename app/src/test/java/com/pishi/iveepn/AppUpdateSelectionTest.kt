package com.pishi.iveepn

import com.pishi.iveepn.util.AppUpdateChecker
import com.pishi.iveepn.util.GhAsset
import com.pishi.iveepn.util.GhRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Updater release selection against the monorepo's combined releases
 * (Windows exe + Android APK under one tag) and its old Windows-only tags.
 */
class AppUpdateSelectionTest {

    private fun combinedRelease(tag: String, apkSize: Long = 13_000_000) = GhRelease(
        tagName = tag,
        assets = listOf(
            GhAsset("iveepn_app.exe", "https://example.test/$tag/iveepn_app.exe", 9_800_000),
            GhAsset("iveepn_app.exe.sha256", "https://example.test/$tag/iveepn_app.exe.sha256", 66),
            GhAsset("iVeePN-$tag.apk", "https://example.test/$tag/iVeePN-$tag.apk", apkSize),
        ),
    )

    @Test
    fun `picks the apk of the newest combined release`() {
        val releases = listOf(combinedRelease("v1.0.9"), combinedRelease("v1.0.11"), combinedRelease("v1.0.10"))
        val update = AppUpdateChecker.selectUpdate(releases, "1.0.10")
        assertEquals("1.0.11", update?.version)
        assertEquals("iVeePN-v1.0.11.apk", update?.assetName)
    }

    @Test
    fun `no update when current is the latest`() {
        val releases = listOf(combinedRelease("v1.0.10"), combinedRelease("v1.0.9"))
        assertNull(AppUpdateChecker.selectUpdate(releases, "1.0.10"))
    }

    @Test
    fun `windows-only releases never count as updates`() {
        val windowsOnly = GhRelease(
            tagName = "v9.9.9",
            assets = listOf(GhAsset("iveepn_app.exe", "https://example.test/iveepn_app.exe", 1)),
        )
        assertNull(AppUpdateChecker.selectUpdate(listOf(windowsOnly), "1.0.10"))
    }

    @Test
    fun `drafts prereleases and non version tags are ignored`() {
        val draft = combinedRelease("v1.0.12").copy(draft = true)
        val pre = combinedRelease("v1.0.13").copy(prerelease = true)
        val oddTag = combinedRelease("release-1.0.14")
        assertNull(AppUpdateChecker.selectUpdate(listOf(draft, pre, oddTag), "1.0.10"))
    }

    @Test
    fun `non-canonical apk names are not accepted as updates`() {
        // Changed with the A6 fix: the old any-.apk fallback would offer
        // an arbitrary APK attached to a release as an update.
        val legacy = GhRelease(
            tagName = "v1.0.12",
            assets = listOf(GhAsset("app-release.apk", "https://example.test/app-release.apk", 5)),
        )
        assertNull(AppUpdateChecker.selectUpdate(listOf(legacy), "1.0.10"))
    }

    @Test
    fun `asset digest is carried into the update info for verification`() {
        val hex = "a".repeat(64)
        val release = GhRelease(
            tagName = "v1.0.12",
            assets = listOf(
                GhAsset("iVeePN-v1.0.12.apk", "https://example.test/iVeePN-v1.0.12.apk", 5, digest = "sha256:$hex"),
            ),
        )
        val update = AppUpdateChecker.selectUpdate(listOf(release), "1.0.10")
        assertEquals(hex, update?.sha256)
    }
}
