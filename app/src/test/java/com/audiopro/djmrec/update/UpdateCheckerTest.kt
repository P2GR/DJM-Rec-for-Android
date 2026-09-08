package com.audiopro.djmrec.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun comparesSemanticVersions() {
        assertTrue(UpdateChecker.isNewer("0.35.0", "0.34.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.99.9"))
        assertFalse(UpdateChecker.isNewer("0.34", "0.34.0"))
        assertFalse(UpdateChecker.isNewer("0.33.9", "0.34.0"))
    }

    @Test
    fun trustsOnlyThisRepositoriesReleaseUrls() {
        assertTrue(UpdateChecker.isTrustedReleaseUrl(
            "https://github.com/P2GR/DJM-Rec-for-Android/releases/tag/v0.42.0"))
        assertTrue(UpdateChecker.isTrustedAssetUrl(
            "https://github.com/P2GR/DJM-Rec-for-Android/releases/download/v0.42.0/app-release.apk"))
        assertFalse(UpdateChecker.isTrustedReleaseUrl("https://example.com/releases/tag/v0.42.0"))
        assertFalse(UpdateChecker.isTrustedAssetUrl(
            "https://github.com/other/repository/releases/download/v0.42.0/app-release.apk"))
    }

    @Test
    fun acceptsOnlyVersionedReleaseApkNames() {
        assertTrue(UpdateChecker.isReleaseApkName("DJM-Rec-for-Android-v0.42.0-release.apk"))
        assertFalse(UpdateChecker.isReleaseApkName("DJM-Rec-for-Android-v0.42.0-debug.apk"))
        assertFalse(UpdateChecker.isReleaseApkName("../DJM-Rec-for-Android-v0.42.0-release.apk"))
    }
}
