package com.steve1316.uma_android_automation.utils

import android.app.Activity
import com.steve1316.automation_library.utils.BotService
import com.steve1316.uma_android_automation.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks for app updates against the latest published GitHub release. A newer release opens [AppUpdateDialog], which downloads and installs
 * it in the app.
 *
 * @property activity The [Activity] context used to display the update dialog.
 */
class AppUpdateChecker(private val activity: Activity) {
    companion object {
        private const val RELEASES_API_URL = "https://api.github.com/repos/steve1316/uma-android-automation/releases"
        private const val TIMEOUT_MS = 10_000

        /**
         * Reads the fields the dialog and installer need from a GitHub release.
         *
         * @param json The release JSON from the GitHub API.
         * @return The parsed [UpdateInfo], or null if the tag or page link is missing.
         */
        internal fun parseRelease(json: String): UpdateInfo? {
            val release = JSONObject(json)
            val version = release.optString("tag_name").removePrefix("v")
            val url = release.optString("html_url")
            if (version.isBlank() || url.isBlank()) return null
            val assetsJson = release.optJSONArray("assets") ?: JSONArray()
            val assets =
                (0 until assetsJson.length()).map {
                    val asset = assetsJson.getJSONObject(it)
                    ReleaseAsset(asset.optString("name"), asset.optLong("size"), asset.optString("browser_download_url"))
                }
            // GitHub's generated pull request list and changelog link come after the changelog and are not part of it.
            val notes =
                release
                    .optString("body")
                    .replace("\r\n", "\n")
                    .lineSequence()
                    .takeWhile { !it.startsWith("## ") && !it.startsWith("**Full Changelog**") }
                    .joinToString("\n")
                    .trim()
            return UpdateInfo(version, url, notes, assets)
        }

        /**
         * Compares two semver-style version strings segment by segment (e.g. "5.4.8" > "5.4.7").
         *
         * @param latest The version of the latest release.
         * @param current The current app version string from [BuildConfig.VERSION_NAME].
         * @return True if [latest] is strictly newer than [current].
         */
        internal fun isNewerVersion(latest: String, current: String): Boolean {
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }

        /**
         * Decides what to do with the latest release. Installing ends the app, so a running bot holds the offer back.
         *
         * @param latest The version of the latest release.
         * @param current The installed app version.
         * @param botRunning Whether the bot is running right now.
         * @return Whether to show the update, hold it, or report the app as current.
         */
        internal fun decide(latest: String, current: String, botRunning: Boolean): Decision =
            when {
                !isNewerVersion(latest, current) -> Decision.UP_TO_DATE
                botRunning -> Decision.HOLD
                else -> Decision.SHOW
            }
    }

    /**
     * One downloadable file attached to a release.
     *
     * @property name The file name, e.g. "v5.8.8-UmaAndroidAutomation-x86_64-release.apk".
     * @property size The file size in bytes.
     * @property url The direct download link.
     */
    data class ReleaseAsset(
        val name: String,
        val size: Long,
        val url: String,
    )

    /**
     * What the update dialog shows for one release.
     *
     * @property latestVersion The release version without the leading "v".
     * @property url The release page.
     * @property releaseNotes The release body with LF line endings.
     * @property assets The files attached to the release.
     */
    data class UpdateInfo(
        val latestVersion: String,
        val url: String,
        val releaseNotes: String,
        val assets: List<ReleaseAsset> = emptyList(),
    )

    /** What [checkForUpdate] decided for the latest release. */
    enum class Decision { SHOW, HOLD, UP_TO_DATE }

    /**
     * Result of [checkForUpdate].
     *
     * @property decision What was done with the latest release.
     * @property version The latest release version.
     */
    data class CheckResult(
        val decision: Decision,
        val version: String,
    )

    /**
     * Fetches one GitHub release off the main thread.
     *
     * @param path The path under the releases API, e.g. "latest" or "tags/v5.8.8".
     * @return The parsed release.
     * @throws IOException When GitHub cannot be reached, answers with an error, or the release is incomplete.
     */
    private suspend fun fetchRelease(path: String): UpdateInfo =
        withContext(Dispatchers.IO) {
            val connection = URL("$RELEASES_API_URL/$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub returned HTTP ${connection.responseCode}.")
                parseRelease(connection.inputStream.bufferedReader().use { it.readText() }) ?: throw IOException("The GitHub release is missing its version.")
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Fetches the latest release and opens the update dialog when it is newer and the bot is not running. An update dialog that is already
     * open is left alone, so a download in progress is never interrupted.
     *
     * @return What was decided for the latest release.
     * @throws IOException When GitHub cannot be reached or the release cannot be read.
     */
    suspend fun checkForUpdate(): CheckResult {
        AppUpdateDialog.current?.takeIf { it.isActive }?.let { return CheckResult(Decision.SHOW, it.version) }
        AppUpdateInstaller.clearLeftovers(activity)
        val updateInfo = fetchRelease("latest")
        val decision = decide(updateInfo.latestVersion, BuildConfig.VERSION_NAME, BotService.isRunning)
        if (decision == Decision.SHOW) AppUpdateDialog(activity, updateInfo, AppUpdateDialog.Mode.UPDATE_AVAILABLE).show()
        return CheckResult(decision, updateInfo.latestVersion)
    }

    /**
     * Shows the release notes of the installed version as a read-only changelog. Falls back to the latest release when the installed
     * version has no release, such as a local build. Network failures are ignored.
     */
    fun showCurrentChangelog() {
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                val updateInfo =
                    try {
                        fetchRelease("tags/v${BuildConfig.VERSION_NAME}")
                    } catch (_: IOException) {
                        fetchRelease("latest")
                    }
                AppUpdateDialog(activity, updateInfo, AppUpdateDialog.Mode.CURRENT_CHANGELOG).show()
            } catch (_: Exception) {
                // Silently ignore network or parsing failures.
            }
        }
    }
}
