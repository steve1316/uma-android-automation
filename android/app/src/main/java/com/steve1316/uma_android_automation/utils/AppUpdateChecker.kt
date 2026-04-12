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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.DisplayMetrics
import android.util.Xml
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.steve1316.uma_android_automation.BuildConfig
import com.steve1316.uma_android_automation.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URL

/**
 * Checks for app updates by fetching and parsing the remote update.xml hosted on GitHub. If a newer version is detected, a custom dialog is
 * shown with release notes and a link to download.
 *
 * @property activity The [Activity] context used to display the update dialog.
 */
class AppUpdateChecker(private val activity: Activity) {
    companion object {
        private const val UPDATE_XML_URL =
            "https://raw.githubusercontent.com/steve1316/uma-android-automation/refs/heads/master/android/app/update.xml"
        private const val MAX_SCROLL_HEIGHT_RATIO = 0.5
    }

    data class UpdateInfo(
        val latestVersion: String,
        val url: String,
        val releaseNotes: String,
    )

    /**
     * Fetches the remote update XML and shows the update dialog if a newer version is available.
     *
     * @param forceShow If true, always shows the dialog regardless of version comparison. Useful for testing the dialog UI.
     */
    fun checkForUpdate(forceShow: Boolean = false) {
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            try {
                val updateInfo =
                    withContext(Dispatchers.IO) {
                        URL(UPDATE_XML_URL).openStream().use { parseUpdateXml(it) }
                    } ?: return@launch

                if (forceShow || isNewerVersion(updateInfo.latestVersion, BuildConfig.VERSION_NAME)) {
                    showUpdateDialog(updateInfo)
                }
            } catch (_: Exception) {
                // Silently ignore network or parsing failures.
            }
        }
    }

    /**
     * Parses the update XML stream and extracts version, URL, and release notes.
     *
     * @param inputStream The raw XML input stream from the remote update file.
     * @return The parsed [UpdateInfo], or null if any required fields are missing.
     */
    private fun parseUpdateXml(inputStream: InputStream): UpdateInfo? {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)

        var latestVersion: String? = null
        var url: String? = null
        var releaseNotes: String? = null
        var currentTag: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> currentTag = parser.name
                XmlPullParser.TEXT -> {
                    val text = parser.text
                    when (currentTag) {
                        "latestVersion" -> latestVersion = text.trim()
                        "url" -> url = text.trim()
                        "releaseNotes" -> releaseNotes = text.trim()
                    }
                }
                XmlPullParser.END_TAG -> currentTag = null
            }
            eventType = parser.next()
        }

        return if (latestVersion != null && url != null && releaseNotes != null) {
            UpdateInfo(latestVersion, url, releaseNotes)
        } else {
            null
        }
    }

    /**
     * Compares two semver-style version strings segment by segment (e.g. "5.4.8" > "5.4.7").
     *
     * @param latest The latest version string from the remote update XML.
     * @param current The current app version string from [BuildConfig.VERSION_NAME].
     * @return True if [latest] is strictly newer than [current].
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
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

    /**
     * Formats release notes text by styling backtick-wrapped segments with a code-like appearance (monospace font, rounded tinted background
     * and text color), similar to GitHub's inline code rendering. Also replaces leading dashes with bullet points.
     *
     * @param text The raw release notes string potentially containing backtick-wrapped text.
     * @return A [SpannableStringBuilder] with styled inline code spans.
     */
    private fun formatReleaseNotes(text: String): SpannableStringBuilder {
        // Replace leading dashes with bullet points.
        val bulletText = text.replace(Regex("(?m)^- "), "\u2022 ")

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

    /**
     * Displays the custom update dialog with release notes and Update/Dismiss buttons.
     *
     * @param updateInfo The parsed update metadata to display in the dialog.
     */
    private fun showUpdateDialog(updateInfo: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = Dialog(activity)
        dialog.setContentView(R.layout.dialog_app_update)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.findViewById<TextView>(R.id.dialog_subtitle).text =
            "Version ${updateInfo.latestVersion} is available"
        dialog.findViewById<TextView>(R.id.dialog_release_notes).text =
            formatReleaseNotes(updateInfo.releaseNotes)

        // Cap the ScrollView height to avoid the dialog filling the entire screen.
        val scrollView = dialog.findViewById<ScrollView>(R.id.dialog_scroll)
        scrollView.post {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getMetrics(metrics)
            val maxHeight = (metrics.heightPixels * MAX_SCROLL_HEIGHT_RATIO).toInt()
            if (scrollView.height > maxHeight) {
                scrollView.layoutParams = scrollView.layoutParams.apply { height = maxHeight }
            }
        }

        dialog.findViewById<Button>(R.id.btn_dismiss).setOnClickListener {
            dialog.dismiss()
        }

        dialog.findViewById<Button>(R.id.btn_update).setOnClickListener {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.url)))
            dialog.dismiss()
        }

        dialog.show()

        // Set the dialog width to 85% of the screen so the content isn't squished.
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getMetrics(metrics)
        dialog.window?.setLayout((metrics.widthPixels * 0.85).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
