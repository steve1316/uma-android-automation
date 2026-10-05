package com.steve1316.uma_android_automation.utils

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for reading a GitHub release and comparing it to the installed version. The dialog itself is checked on-device. */
@DisplayName("App update checker")
class AppUpdateCheckerTest {
    @Test
    @DisplayName("A release is read from its tag, page link and body, with CRLF line endings normalized")
    fun testParseRelease() {
        val json = """{"tag_name": "v5.8.9", "html_url": "https://github.com/x/y/releases/tag/v5.8.9", "body": "v5.8.9 - Changelog\r\n\r\nNew\r\n---\r\n- Add `X`.\r\n"}"""
        val info = AppUpdateChecker.parseRelease(json)!!
        assertEquals("5.8.9", info.latestVersion)
        assertEquals("https://github.com/x/y/releases/tag/v5.8.9", info.url)
        assertEquals("v5.8.9 - Changelog\n\nNew\n---\n- Add `X`.", info.releaseNotes)
    }

    @Test
    @DisplayName("A release without a tag or page link is rejected")
    fun testParseReleaseMissingFields() {
        assertNull(AppUpdateChecker.parseRelease("""{"html_url": "https://github.com/x", "body": ""}"""))
        assertNull(AppUpdateChecker.parseRelease("""{"tag_name": "v5.8.9", "body": ""}"""))
    }

    @Test
    @DisplayName("Versions compare numerically segment by segment")
    fun testIsNewerVersion() {
        assertTrue(AppUpdateChecker.isNewerVersion("5.8.10", "5.8.9"))
        assertTrue(AppUpdateChecker.isNewerVersion("5.9", "5.8.9"))
        assertFalse(AppUpdateChecker.isNewerVersion("5.8.8", "5.8.8"))
        assertFalse(AppUpdateChecker.isNewerVersion("5.8.7", "5.8.8"))
    }

    @Test
    @DisplayName("A release's APK files are read with their size and download link")
    fun testParseReleaseAssets() {
        val json =
            """{"tag_name": "v5.8.9", "html_url": "https://github.com/x", "body": "", "assets": [
                {"name": "v5.8.9-UmaAndroidAutomation-x86_64-release.apk", "size": 114622856, "browser_download_url": "https://github.com/x/a.apk"}]}"""
        assertEquals(
            listOf(AppUpdateChecker.ReleaseAsset("v5.8.9-UmaAndroidAutomation-x86_64-release.apk", 114622856L, "https://github.com/x/a.apk")),
            AppUpdateChecker.parseRelease(json)!!.assets,
        )
    }

    @Test
    @DisplayName("An update is held while the bot runs and skipped when the app is current")
    fun testDecide() {
        assertEquals(AppUpdateChecker.Decision.SHOW, AppUpdateChecker.decide("5.8.9", "5.8.8", botRunning = false))
        assertEquals(AppUpdateChecker.Decision.HOLD, AppUpdateChecker.decide("5.8.9", "5.8.8", botRunning = true))
        assertEquals(AppUpdateChecker.Decision.UP_TO_DATE, AppUpdateChecker.decide("5.8.8", "5.8.8", botRunning = true))
    }

    @Test
    @DisplayName("GitHub's generated pull request list and changelog link are cut from the notes")
    fun testParseReleaseCutsGeneratedNotes() {
        val body = "v5.8.9 - Changelog\r\n\r\nFixes\r\n---\r\n- Fix X.\r\n\r\n## Pull Requests\r\n* PR by @a in https://github.com/x/pull/1\r\n\r\n\r\n**Full Changelog**: https://github.com/x/compare/a...b"
        val json = JSONObject().put("tag_name", "v5.8.9").put("html_url", "https://github.com/x").put("body", body).toString()
        assertEquals("v5.8.9 - Changelog\n\nFixes\n---\n- Fix X.", AppUpdateChecker.parseRelease(json)!!.releaseNotes)
        val linkOnly = JSONObject().put("tag_name", "v5.8.9").put("html_url", "https://github.com/x").put("body", "- Fix X.\n\n**Full Changelog**: https://github.com/x").toString()
        assertEquals("- Fix X.", AppUpdateChecker.parseRelease(linkOnly)!!.releaseNotes)
    }
}
