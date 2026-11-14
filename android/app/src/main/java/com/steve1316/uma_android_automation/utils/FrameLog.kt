package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.events.JSEvent
import com.steve1316.automation_library.events.StartEvent
import com.steve1316.automation_library.utils.BotService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Developer-only frame log. While the dev MCP server runs, it saves the frame the bot last grabbed for each INFO, WARN, or ERROR log line, at
 * most one per second. A frame is skipped when the bot has not grabbed a new one since the last save.
 *
 * Each file is named by the log line's own elapsed stamp plus a slug of its text, like `00-12-34-567_handling-training-event.jpg`. So a log
 * line's time finds its frame directly. Each run gets its own session folder under `filesDir/dev_frames`.
 */
object FrameLog {
    private const val TAG: String = "${SharedData.loggerTag}FrameLog"

    /** The least time between two saved frames. */
    private const val MIN_INTERVAL_MS = 1000L

    /** The resize factor applied before saving. */
    private const val SCALE = 0.5

    /** The JPEG quality used when saving. */
    private const val JPEG_QUALITY = 80

    /** Most bytes saved per session. Later frames in that session are dropped. */
    private const val SESSION_CAP_BYTES = 300L * 1024 * 1024

    /** How many session folders are kept, counting the current one. */
    private const val SESSIONS_KEPT = 3

    /** Most frames waiting for the writer. Newer frames are dropped while it is full. Each one holds a full-size bitmap, so it stays small. */
    private const val QUEUE_SIZE = 3

    /** Most characters of the log text kept in a file name. */
    private const val SLUG_LENGTH = 40

    /** Splits a message log line into its elapsed stamp, level, and text. */
    private val LINE_REGEX = Regex("""^\n?(\d{2}):(\d{2}):(\d{2})\.(\d{3}) \[(\w+)] (.*)""")

    /** Matches a frame file name and captures its stamp and slug. */
    private val FILE_REGEX = Regex("""^(\d{2})-(\d{2})-(\d{2})-(\d{3})_(.*)\.jpg$""")

    /** Matches a stamp passed to `frame_at`, with or without milliseconds. */
    private val STAMP_REGEX = Regex("""^(\d{1,2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?$""")

    /** Matches the bracketed tags at the start of log text, like `[INFO]`, which the slug leaves out. */
    private val TAG_REGEX = Regex("""\[[^\]]*]""")

    /** Matches runs of characters that are not safe in a slug. */
    private val UNSAFE_SLUG_REGEX = Regex("[^a-z0-9]+")

    /** The levels whose lines save a frame. */
    private val SAVED_LEVELS = setOf("INFO", "WARN", "ERROR")

    /** The folder holding every session, or null while the frame log is off. */
    @Volatile
    private var root: File? = null

    /** The current session's folder, created on the first save of a run. */
    @Volatile
    private var sessionDir: File? = null

    /** Bytes saved in the current session. */
    @Volatile
    private var sessionBytes: Long = 0L

    /** Frames saved in the current session. */
    @Volatile
    var framesSaved: Int = 0
        private set

    /** The last frame the bot grabbed. */
    @Volatile
    private var latest: Bitmap? = null

    /** Whether [latest] was already handed to the writer, so an unchanged screen is not saved twice. */
    @Volatile
    private var isLatestQueued: Boolean = false

    /** When the last frame was handed to the writer. */
    @Volatile
    private var lastQueuedAtMs: Long = 0L

    /** The single writer thread, with a bounded queue that drops new frames when full. */
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE_SIZE), ThreadPoolExecutor.DiscardPolicy())

    /** A frame saved on disk. */
    data class Frame(
        /** The elapsed time of its log line, in milliseconds. */
        val elapsedMs: Long,
        /** Its log line stamp, as `HH:MM:SS.mmm`. */
        val stamp: String,
        /** The slug of its log line's text. */
        val slug: String,
        /** The file on disk. */
        val file: File,
    )

    /** Whether the frame log is on. */
    val isActive: Boolean
        get() = root != null

    /** The current session's name, or null before the first save. */
    val sessionName: String?
        get() = sessionDir?.name

    /**
     * Turns the frame log on. Does nothing if it is already on.
     *
     * @param context Any context, used for the app's files folder.
     */
    fun start(context: Context) {
        if (isActive) return
        root = File(context.filesDir, "dev_frames")
        EventBus.getDefault().register(this)
    }

    /** Turns the frame log off and drops its frame references. Frames already saved stay on disk. */
    fun stop() {
        if (!isActive) return
        EventBus.getDefault().unregister(this)
        root = null
        sessionDir = null
        latest = null
    }

    /**
     * Keeps the frame the bot just grabbed, so the next log line can save it.
     *
     * @param frame The bot's source frame.
     */
    fun offerFrame(frame: Bitmap) {
        if (!isActive || frame === latest) return
        latest = frame
        isLatestQueued = false
    }

    /**
     * Starts a new session when a run starts. The priority puts it ahead of `StartModule`, whose handler blocks the posting thread until
     * the run ends, so a default-priority reset would only land after the run.
     *
     * @param event The start event.
     */
    @Subscribe(priority = 1)
    fun onStartEvent(event: StartEvent) {
        if (event.message != "Entry Point ON") return
        sessionDir = null
        sessionBytes = 0L
        framesSaved = 0
        latest = null
    }

    /**
     * Queues the latest frame for a log line. This runs on the logging thread inside the message log lock, so the cheap checks come before
     * the regex.
     *
     * @param event The event carrying the log line.
     */
    @Subscribe
    fun onJSEvent(event: JSEvent) {
        if (event.eventName != "MessageLog" || !BotService.isRunning) return
        val frame = latest ?: return
        val now = System.currentTimeMillis()
        if (isLatestQueued || now - lastQueuedAtMs < MIN_INTERVAL_MS) return

        val (h, m, s, ms, level, text) = LINE_REGEX.find(event.message)?.destructured ?: return
        if (level !in SAVED_LEVELS) return
        isLatestQueued = true
        lastQueuedAtMs = now
        writer.execute { save(frame, "$h-$m-$s-$ms", slugify(text)) }
    }

    /**
     * Lists the session folders, oldest first.
     *
     * @returns The session names.
     */
    fun sessions(): List<String> = root?.listFiles { file -> file.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    /**
     * Lists the frames of one session in time order.
     *
     * @param session The session name. Defaults to the current or newest session.
     * @returns The frames, or an empty list when the session does not exist.
     */
    fun frames(session: String? = null): List<Frame> {
        val dir = resolveSession(session) ?: return emptyList()
        return dir.listFiles()
            ?.mapNotNull { file ->
                val (h, m, s, ms, slug) = FILE_REGEX.find(file.name)?.destructured ?: return@mapNotNull null
                Frame(toMs(h, m, s, ms), "$h:$m:$s.$ms", slug, file)
            }
            ?.sortedBy { it.elapsedMs } ?: emptyList()
    }

    /**
     * Parses a log stamp such as `00:12:34.567` or `00:12:34`.
     *
     * @param stamp The stamp.
     * @returns The elapsed time in milliseconds.
     * @throws IllegalArgumentException If the stamp is not in that form.
     */
    fun parseStamp(stamp: String): Long {
        val (h, m, s, ms) = STAMP_REGEX.find(stamp.trim())?.destructured ?: throw IllegalArgumentException("Use a log stamp like 00:12:34.567, not \"$stamp\".")
        return toMs(h, m, s, ms.padEnd(3, '0'))
    }

    /**
     * Saves one frame, scaled down, then prunes old sessions when this save opened a new one. Runs on the writer thread.
     *
     * @param frame The frame to save.
     * @param stamp The log line's stamp with dashes.
     * @param slug The slug of the log line's text.
     */
    private fun save(frame: Bitmap, stamp: String, slug: String) {
        try {
            if (frame.isRecycled || sessionBytes >= SESSION_CAP_BYTES) return
            val dir = sessionDir ?: openSession() ?: return
            val scaled = Bitmap.createScaledBitmap(frame, (frame.width * SCALE).toInt(), (frame.height * SCALE).toInt(), true)
            val file = File(dir, "${stamp}_$slug.jpg")
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (scaled !== frame) scaled.recycle()
            sessionBytes += file.length()
            framesSaved++
        } catch (e: Exception) {
            Log.w(TAG, "[FrameLog] Failed to save a frame: ${e.message}")
        }
    }

    /**
     * Creates the current session's folder and deletes the oldest sessions beyond [SESSIONS_KEPT].
     *
     * @returns The new folder, or null while the frame log is off.
     */
    private fun openSession(): File? {
        val base = root ?: return null
        val dir = File(base, SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())).apply { mkdirs() }
        sessionDir = dir
        sessions().dropLast(SESSIONS_KEPT).forEach { File(base, it).deleteRecursively() }
        return dir
    }

    /**
     * Finds a session folder by name.
     *
     * @param session The session name, or null for the current or newest session.
     * @returns The folder, or null when there is none.
     */
    private fun resolveSession(session: String?): File? {
        val base = root ?: return null
        if (session == null) return sessionDir ?: sessions().lastOrNull()?.let { File(base, it) }
        return File(base, session).takeIf { it.isDirectory && it.parentFile == base }
    }

    /**
     * Turns log text into a short file-safe slug.
     *
     * @param text The log text.
     * @returns The slug, or `line` when nothing is left.
     */
    private fun slugify(text: String): String =
        text.lowercase().replace(TAG_REGEX, " ").replace(UNSAFE_SLUG_REGEX, "-").trim('-').take(SLUG_LENGTH).trimEnd('-').ifEmpty { "line" }

    /**
     * Converts stamp parts to milliseconds.
     *
     * @param h Hours.
     * @param m Minutes.
     * @param s Seconds.
     * @param ms Milliseconds, as three digits.
     * @returns The total in milliseconds.
     */
    private fun toMs(h: String, m: String, s: String, ms: String): Long = ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
}
