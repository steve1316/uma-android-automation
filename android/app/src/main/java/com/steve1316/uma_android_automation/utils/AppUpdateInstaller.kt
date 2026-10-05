package com.steve1316.uma_android_automation.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK into the app's cache and hands it to Android's [PackageInstaller]. The install result comes back through
 * [InstallResultReceiver].
 *
 * @property context Any context of this app.
 */
class AppUpdateInstaller(private val context: Context) {
    companion object {
        private const val DIR_NAME = "app-update"
        private const val TIMEOUT_MS = 15_000
        private const val BUFFER_BYTES = 64 * 1024

        /**
         * Picks the release APK built for this device, preferring the ABIs in the order the device lists them.
         *
         * @param assets The files attached to the release.
         * @param supportedAbis The device ABIs, most preferred first (see [Build.SUPPORTED_ABIS]).
         * @return The matching APK, or null when the release has none for this device.
         */
        internal fun pickApk(assets: List<AppUpdateChecker.ReleaseAsset>, supportedAbis: List<String>): AppUpdateChecker.ReleaseAsset? =
            supportedAbis.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.name.endsWith("-$abi-release.apk") } }

        /**
         * Deletes any APK left behind by an earlier download or install.
         *
         * @param context Any context of this app.
         */
        fun clearLeftovers(context: Context) {
            File(context.cacheDir, DIR_NAME).deleteRecursively()
        }

        /**
         * Formats a byte count as whole megabytes for the dialog.
         *
         * @param bytes The byte count.
         * @return The size in MB, rounded down.
         */
        internal fun toMb(bytes: Long): Long = bytes / 1_000_000
    }

    /** How Android's installer finished, when it did not succeed. A successful install ends the app instead. */
    sealed interface InstallResult {
        /** The user backed out of Android's Install screen. */
        data object Aborted : InstallResult

        /**
         * Android refused or failed the install.
         *
         * @property message Android's reason.
         */
        data class Failed(val message: String) : InstallResult
    }

    /**
     * Downloads an APK into the app's cache. The partial file is deleted on any failure or cancellation.
     *
     * @param asset The APK to download.
     * @param onProgress Called off the main thread with the bytes downloaded so far and the total.
     * @return The downloaded file, with its size checked against the release.
     * @throws IOException When storage is short, the download fails, or the size does not match.
     */
    suspend fun download(asset: AppUpdateChecker.ReleaseAsset, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
            // The installer copies the APK into its own session, so the update briefly needs twice its size.
            if (dir.usableSpace < asset.size * 2) throw IOException("Not enough storage to download the update (needs ${toMb(asset.size * 2)} MB).")
            val file = File(dir, asset.name)
            val connection = URL(asset.url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("The download failed with HTTP ${connection.responseCode}.")
                var done = 0L
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, asset.size)
                        }
                    }
                }
                if (done != asset.size) throw IOException("The download was incomplete (${toMb(done)} of ${toMb(asset.size)} MB).")
                file
            } catch (e: Throwable) {
                file.delete()
                throw e
            } finally {
                connection.disconnect()
            }
        }

    /**
     * Hands a downloaded APK to Android's installer, which then asks the user to confirm.
     *
     * @param apk The downloaded APK.
     * @throws IOException When the install session cannot be written.
     */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite(apk.name, 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            // The installer fills in the status extras, so the intent has to stay mutable.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(context, InstallResultReceiver::class.java)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender)
        }
    }
}
