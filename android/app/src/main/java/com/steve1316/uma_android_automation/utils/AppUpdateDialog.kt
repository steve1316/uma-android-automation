package com.steve1316.uma_android_automation.utils

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.steve1316.uma_android_automation.BuildConfig
import com.steve1316.uma_android_automation.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The native app update dialog. In update mode it downloads the release APK with progress and hands it to Android's installer. In changelog
 * mode it is a read-only viewer for the installed version's release notes.
 *
 * @property activity The [Activity] the dialog is shown on.
 * @property updateInfo The release to show.
 * @property mode Whether to offer the update or only show the changelog.
 */
class AppUpdateDialog(
    private val activity: Activity,
    private val updateInfo: AppUpdateChecker.UpdateInfo,
    private val mode: Mode,
) {
    companion object {
        private const val MAX_SCROLL_HEIGHT_RATIO = 0.5

        /** The update dialog on screen, so a second check never opens another one over a download in progress. Main thread only. */
        var current: AppUpdateDialog? = null
            private set
    }

    /** Whether the dialog offers the update or only shows the changelog. */
    enum class Mode { UPDATE_AVAILABLE, CURRENT_CHANGELOG }

    /** The release version this dialog is for. */
    val version: String get() = updateInfo.latestVersion

    /** Whether the dialog is still on a live screen. An activity destroyed without dismissing it would otherwise leave a stale [current]. */
    val isActive: Boolean get() = dialog.isShowing && !activity.isDestroyed

    private val dialog = Dialog(activity)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val installer = AppUpdateInstaller(activity)
    private val apkAsset = AppUpdateInstaller.pickApk(updateInfo.assets, Build.SUPPORTED_ABIS.toList())
    private var downloadJob: Job? = null
    private var downloadedApk: File? = null
    private var waitingForPermission = false

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var dismissBtn: Button
    private lateinit var actionBtn: Button

    /**
     * A [ReplacementSpan] that draws a rounded rectangle behind the text, similar to GitHub's inline code pill.
     *
     * @property backgroundColor The fill color for the rounded background.
     * @property textColor The color used to draw the text on top of the background.
     * @property cornerRadius The corner radius in pixels for the rounded rectangle.
     * @property horizontalPadding The horizontal padding in pixels inside the pill.
     */
    private class RoundedBackgroundSpan(
        private val backgroundColor: Int,
        private val textColor: Int,
        private val cornerRadius: Float = 8f,
        private val horizontalPadding: Float = 6f,
    ) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val originalTypeface = paint.typeface
            paint.typeface = Typeface.MONOSPACE
            val width = (paint.measureText(text, start, end) + horizontalPadding * 2).toInt()
            paint.typeface = originalTypeface
            return width
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val originalTypeface = paint.typeface
            paint.typeface = Typeface.MONOSPACE
            val textWidth = paint.measureText(text, start, end)
            // Use font metrics for pill height so line spacing doesn't inflate it.
            val fm = paint.fontMetrics
            val pillTop = y + fm.ascent - 2f
            val pillBottom = y + fm.descent + 2f
            val rect = RectF(x, pillTop, x + textWidth + horizontalPadding * 2, pillBottom)
            val bgPaint = Paint(paint)
            bgPaint.color = backgroundColor
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
            paint.color = textColor
            canvas.drawText(text, start, end, x + horizontalPadding, y.toFloat(), paint)
            paint.typeface = originalTypeface
        }
    }

    /** Builds and shows the dialog in its first state. */
    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog.setContentView(R.layout.dialog_app_update)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        title = dialog.findViewById(R.id.dialog_title)
        subtitle = dialog.findViewById(R.id.dialog_subtitle)
        status = dialog.findViewById(R.id.dialog_status)
        progress = dialog.findViewById(R.id.dialog_progress)
        dismissBtn = dialog.findViewById(R.id.btn_dismiss)
        actionBtn = dialog.findViewById(R.id.btn_update)
        dialog.findViewById<TextView>(R.id.dialog_release_notes).text = formatReleaseNotes(updateInfo.releaseNotes)

        // Cap the ScrollView height to avoid the dialog filling the entire screen.
        val scrollView = dialog.findViewById<ScrollView>(R.id.dialog_scroll)
        scrollView.post {
            val maxHeight = (activity.resources.displayMetrics.heightPixels * MAX_SCROLL_HEIGHT_RATIO).toInt()
            if (scrollView.height > maxHeight) {
                scrollView.layoutParams = scrollView.layoutParams.apply { height = maxHeight }
            }
        }

        if (mode == Mode.CURRENT_CHANGELOG) {
            render("Changelog", "Installed version v${BuildConfig.VERSION_NAME}", dismiss = "Close", action = "View on GitHub" to ::openReleasePage)
        } else {
            current = this
            showAvailable()
            // Coming back from Android's install permission screen gives the dialog focus again.
            dialog.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { hasFocus ->
                if (hasFocus && waitingForPermission && canInstall()) {
                    waitingForPermission = false
                    startDownload()
                }
            }
        }
        dialog.setOnDismissListener {
            scope.cancel()
            if (current === this) current = null
        }
        dialog.show()

        // Set the dialog width to 85% of the screen so the content isn't squished.
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.85).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Puts the dialog into one state.
     *
     * @param titleText The title.
     * @param subtitleText The line under the title. Defaults to the release version.
     * @param statusText An explanation above the release notes, or null to hide it.
     * @param percent The download progress, or null to hide the progress bar.
     * @param dismiss The left button's label. It always closes the dialog unless [onDismiss] says otherwise.
     * @param onDismiss What the left button does instead of closing the dialog.
     * @param action The right button's label and what it does, or null to hide it.
     * @param cancelable Whether back and taps outside close the dialog.
     */
    private fun render(
        titleText: String,
        subtitleText: String = "Version $version",
        statusText: String? = null,
        percent: Int? = null,
        dismiss: String,
        onDismiss: (() -> Unit)? = null,
        action: Pair<String, () -> Unit>? = null,
        cancelable: Boolean = true,
    ) {
        title.text = titleText
        subtitle.text = subtitleText
        status.text = statusText
        status.visibility = if (statusText == null) View.GONE else View.VISIBLE
        progress.visibility = if (percent == null) View.GONE else View.VISIBLE
        if (percent != null) progress.progress = percent
        dismissBtn.text = dismiss
        dismissBtn.setOnClickListener { onDismiss?.invoke() ?: dialog.dismiss() }
        actionBtn.visibility = if (action == null) View.GONE else View.VISIBLE
        action?.let { (label, onClick) ->
            actionBtn.text = label
            actionBtn.setOnClickListener { onClick() }
        }
        dialog.setCancelable(cancelable)
    }

    /** Offers the update. */
    private fun showAvailable() {
        val size = apkAsset?.let { " (${AppUpdateInstaller.toMb(it.size)} MB)" } ?: ""
        render("Update Available", "Version $version is available$size", dismiss = "Dismiss", action = "Update" to ::onUpdate)
    }

    /** Starts the update, first asking for the install permission when Android has not granted it yet. */
    private fun onUpdate() {
        when {
            apkAsset == null ->
                showError("Update Failed", "This release has no download for this device (${Build.SUPPORTED_ABIS.firstOrNull()}). Install it from the release page instead.")
            !canInstall() -> {
                render(
                    "Allow App Updates",
                    "Allow this app to install updates",
                    statusText = "Android asks this once. Turn on \"Allow from this source\" for this app, then come back here and the download starts.",
                    dismiss = "Close",
                    action = "Open Settings" to ::openInstallSettings,
                )
            }
            else -> startDownload()
        }
    }

    /**
     * Returns whether Android lets this app install packages.
     *
     * @return True when installs are allowed. Android before 8.0 has no per-app setting, so the installer reports a refusal instead.
     */
    private fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Allow from this source" screen for this app. */
    private fun openInstallSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        waitingForPermission = true
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
    }

    /** Downloads the APK with progress, or reuses one already downloaded, then installs it. */
    private fun startDownload() {
        val asset = apkAsset ?: return
        downloadedApk?.takeIf { it.exists() }?.let { return install(it) }
        val totalMb = AppUpdateInstaller.toMb(asset.size)
        render("Downloading Update", statusText = "Downloading... 0% (0 of $totalMb MB)", percent = 0, dismiss = "Cancel", onDismiss = { downloadJob?.cancel() }, cancelable = false)
        downloadJob =
            scope.launch {
                try {
                    var shown = -1
                    val apk =
                        installer.download(asset) { done, total ->
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                activity.runOnUiThread {
                                    progress.progress = percent
                                    status.text = "Downloading... $percent% (${AppUpdateInstaller.toMb(done)} of $totalMb MB)"
                                }
                            }
                        }
                    downloadedApk = apk
                    install(apk)
                } catch (_: CancellationException) {
                    if (dialog.isShowing) showAvailable()
                } catch (e: Exception) {
                    showError("Update Failed", e.message ?: "The download failed.", retry = ::startDownload)
                }
            }
    }

    /**
     * Hands the APK to Android's installer and waits for the user to confirm on Android's Install screen.
     *
     * @param apk The downloaded APK.
     */
    private fun install(apk: File) {
        render("Installing Update", statusText = "Confirm on Android's Install screen. The app closes and reopens on the new version.", dismiss = "Close")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { installer.install(apk) }
            } catch (e: Exception) {
                showError("Update Failed", "Android could not start the install: ${e.message}", retry = { install(apk) })
            }
        }
    }

    /**
     * Shows how Android's installer finished when it did not succeed. Called on the main thread by [InstallResultReceiver].
     *
     * @param result The installer's outcome.
     */
    fun onInstallResult(result: AppUpdateInstaller.InstallResult) {
        val apk = downloadedApk ?: return
        when (result) {
            is AppUpdateInstaller.InstallResult.Aborted -> showError("Update Not Installed", "The install was cancelled.", retry = { install(apk) })
            is AppUpdateInstaller.InstallResult.Failed -> showError("Update Failed", "Android could not install the update: ${result.message} Install it from the release page instead.")
        }
    }

    /**
     * Shows a failure with a way forward.
     *
     * @param titleText The title, e.g. "Update Failed".
     * @param message What went wrong.
     * @param retry What Retry does, or null to offer the release page instead.
     */
    private fun showError(titleText: String, message: String, retry: (() -> Unit)? = null) {
        val action = retry?.let { "Retry" to it } ?: ("Open Release Page" to ::openReleasePage)
        render(titleText, statusText = message, dismiss = "Close", action = action)
    }

    /** Opens the release page in the browser and closes the dialog. */
    private fun openReleasePage() {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.url)))
        dialog.dismiss()
    }

    /**
     * Formats release notes text by styling backtick-wrapped segments with a code-like appearance (monospace font, rounded tinted background
     * and text color), similar to GitHub's inline code rendering. Also replaces leading dashes with bullet points.
     *
     * @param text The raw release notes string potentially containing backtick-wrapped text.
     * @return A [SpannableStringBuilder] with styled inline code spans.
     */
    private fun formatReleaseNotes(text: String): SpannableStringBuilder {
        // Replace leading dashes with bullet points.
        val bulletText = text.replace(Regex("(?m)^- "), "• ")

        val builder = SpannableStringBuilder()
        val codeBgColor = ContextCompat.getColor(activity, R.color.dialog_code_background)
        val codeTextColor = ContextCompat.getColor(activity, R.color.dialog_code_text)
        val density = activity.resources.displayMetrics.density
        val cornerRadius = 6f * density
        val horizontalPadding = 4f * density

        var i = 0
        while (i < bulletText.length) {
            val backtickStart = bulletText.indexOf('`', i)
            if (backtickStart == -1) {
                builder.append(bulletText, i, bulletText.length)
                break
            }
            val backtickEnd = bulletText.indexOf('`', backtickStart + 1)
            if (backtickEnd == -1) {
                builder.append(bulletText, i, bulletText.length)
                break
            }

            // Append text before the backtick.
            builder.append(bulletText, i, backtickStart)

            // Append the code content with a rounded background span.
            val codeContent = bulletText.substring(backtickStart + 1, backtickEnd)
            val spanStart = builder.length
            builder.append(codeContent)
            val spanEnd = builder.length
            builder.setSpan(RoundedBackgroundSpan(codeBgColor, codeTextColor, cornerRadius, horizontalPadding), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            i = backtickEnd + 1
        }
        return builder
    }
}
