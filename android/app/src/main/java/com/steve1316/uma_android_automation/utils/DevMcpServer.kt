package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.utils.BotHold
import com.steve1316.automation_library.utils.BotService
import com.steve1316.automation_library.utils.BotStatus
import com.steve1316.automation_library.utils.ImageUtils
import com.steve1316.automation_library.utils.MediaProjectionService
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.MyAccessibilityService
import com.steve1316.automation_library.utils.SQLiteSettingsManager
import com.steve1316.uma_android_automation.bot.Campaign
import com.steve1316.uma_android_automation.bot.Game
import com.steve1316.uma_android_automation.bot.RunAnalytics
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** One remote command. It takes the request's JSON arguments and returns a JSON object, a JSON array, or a string. */
private typealias Command = suspend (JSONObject) -> Any

/**
 * Developer-only remote that lets an AI coding assistant inspect and steer the bot. Each command is `POST /<command>` with JSON arguments, and
 * every reply is `{ok: true, result}` or `{ok: false, error}`. The `scripts/uma_mcp.py` bridge turns these commands into MCP tools.
 *
 * It binds to 127.0.0.1 only and has no auth, so the PC reaches it through `adb forward tcp:9100 tcp:9100`, which the bridge runs itself.
 */
object DevMcpServer {
    private const val TAG: String = "${SharedData.loggerTag}DevMcpServer"

    /** The fixed port the server listens on. */
    const val PORT = 9100

    /** Default number of log lines returned by `logs`. */
    private const val DEFAULT_LOG_LIMIT = 100

    /** Default number of frames listed by `frames`. */
    private const val DEFAULT_FRAME_LIMIT = 200

    /** How long `hold` waits for the bot to reach its next safe point. */
    private const val HOLD_TIMEOUT_MS = 15_000L

    /** How long a tap or swipe waits afterwards, so the game can react before the next command. */
    private const val AFTER_INPUT_MS = 1_000L

    /** How recent the last request must be for the client to count as connected. The bridge pings every 5 seconds. */
    private const val CLIENT_TIMEOUT_MS = 15_000L

    /** The pause reason shown in the notification and tray while held. */
    private const val HOLD_REASON = "Held by the developer MCP server"

    /** Setting keys whose values are redacted from `settings`, matched case-insensitively. */
    private val SECRET_KEY_PARTS = listOf("token", "password", "secret")

    /** The commands served, by name. */
    private val commands: Map<String, Command> =
        mapOf(
            "ping" to { _ -> "pong" },
            "status" to { _ -> botStatus() },
            "logs" to { args -> getLogs(args) },
            "run_summary" to { _ -> RunAnalytics.buildRunSummary() ?: "No run summary yet." },
            "screenshot" to { args -> screenshot(args) },
            "find_template" to { args -> findTemplate(args) },
            "ocr_region" to { args -> ocrRegion(args) },
            "settings" to { args -> getSettings(args) },
            "stop" to { _ -> stopBot() },
            "hold" to { _ -> hold() },
            "release" to { _ -> release() },
            "tap" to { args -> tap(args) },
            "swipe" to { args -> swipe(args) },
            "frames" to { args -> listFrames(args) },
            "frame_at" to { args -> frameAt(args) },
        )

    /** The Ktor embedded server instance, or null when the server is stopped. */
    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Application context for screenshots, image utilities, and the settings database. */
    private var applicationContext: Context? = null

    /** Image utilities used when no run is active, created on first use. */
    private var fallbackImageUtils: ImageUtils? = null

    /** When the last request arrived. */
    @Volatile
    private var lastContactMs: Long = 0L

    /** Whether the server is currently running. */
    val isRunning: Boolean
        get() = server != null

    /** Whether a client sent a request within [CLIENT_TIMEOUT_MS]. */
    val isClientConnected: Boolean
        get() = isRunning && System.currentTimeMillis() - lastContactMs < CLIENT_TIMEOUT_MS

    /** The last start failure, or null when the last start succeeded. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Starts the server on 127.0.0.1 at [PORT] and turns the frame log on. Does nothing if it is already running.
     *
     * @param context Any context. The application context is kept.
     */
    fun start(context: Context) {
        if (isRunning) return

        try {
            applicationContext = context.applicationContext
            server =
                embeddedServer(CIO, host = "127.0.0.1", port = PORT) {
                    routing {
                        post("/{command}") {
                            lastContactMs = System.currentTimeMillis()
                            val response = dispatch(call.parameters["command"] ?: "", call.receiveText())
                            call.respondText(response.toString(), ContentType.Application.Json)
                        }
                    }
                }.start(wait = false)
            FrameLog.start(context.applicationContext)
            lastError = null
            Log.i(TAG, "[DevMcpServer] Started on 127.0.0.1:$PORT.")
        } catch (e: Exception) {
            Log.e(TAG, "[DevMcpServer] Failed to start: ${e.message}", e)
            lastError = e.message ?: e.toString()
            stop()
        }
    }

    /** Stops the server and the frame log and releases their references. */
    fun stop() {
        server?.stop(500, 1000)
        server = null
        FrameLog.stop()
        applicationContext = null
        fallbackImageUtils = null
        // Game.current is only kept for this server, so release the last run with it.
        Game.current = null
        Log.i(TAG, "[DevMcpServer] Stopped.")
    }

    /**
     * Runs one command and wraps its outcome, so a bad call never takes the server down.
     *
     * @param name The command name from the path.
     * @param body The request body, a JSON object of arguments or empty.
     * @returns `{ok: true, result}` or `{ok: false, error}`.
     */
    private suspend fun dispatch(name: String, body: String): JSONObject {
        val command = commands[name] ?: return failure("Unknown command \"$name\". Known: ${commands.keys.joinToString()}.")
        return try {
            JSONObject().put("ok", true).put("result", command(if (body.isBlank()) JSONObject() else JSONObject(body)))
        } catch (e: Exception) {
            Log.w(TAG, "[DevMcpServer] Command $name failed: ${e.message}", e)
            failure(e.message ?: e.toString())
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Inspection

    /**
     * Builds the `status` payload.
     *
     * @returns The status as JSON.
     */
    private fun botStatus(): JSONObject {
        val snapshot = BotStatus.snapshot()
        val json =
            JSONObject()
                .put("botRunning", BotService.isRunning)
                .put("screenCaptureRunning", MediaProjectionService.isRunning)
                .put("hold", BotHold.pauseState.name)
                .put("frameLog", JSONObject().put("session", FrameLog.sessionName ?: JSONObject.NULL).put("framesSaved", FrameLog.framesSaved))
                .put(
                    "progress",
                    JSONObject()
                        .put("current", snapshot.current)
                        .put("total", snapshot.total)
                        .put("label", snapshot.label)
                        .put("detail", snapshot.detail)
                        .put("outcome", snapshot.outcome?.name ?: JSONObject.NULL)
                        .put("reason", snapshot.reason)
                        .put("elapsedSeconds", BotStatus.elapsedMs / 1000),
                )

        val game = Game.current ?: return json.put("game", JSONObject.NULL)
        val gameJson = JSONObject().put("scenario", game.scenario).put("runMode", game.runMode)
        val campaign = game.task as? Campaign
        if (campaign != null) {
            val trainee = campaign.trainee
            gameJson
                .put("date", campaign.date.toString())
                .put(
                    "trainee",
                    JSONObject()
                        .put("name", trainee.name)
                        .put("stats", trainee.stats.toString())
                        .put("energy", trainee.energy)
                        .put("mood", trainee.mood.name)
                        .put("skillPoints", trainee.skillPoints)
                        .put("fans", trainee.fans)
                        .put("positiveStatuses", JSONArray(trainee.currentPositiveStatuses.toList()))
                        .put("negativeStatuses", JSONArray(trainee.currentNegativeStatuses.toList())),
                )
        }
        return json.put("game", gameJson)
    }

    /**
     * Builds the `logs` payload from the message log.
     *
     * @param args The arguments: `since`, `limit`, and `contains`.
     * @returns The selected lines, the total line count, and the index to pass as `since` next time.
     */
    private fun getLogs(args: JSONObject): JSONObject {
        val lines = MessageLog.getMessageLogCopy()
        val limit = (args.intArg("limit") ?: DEFAULT_LOG_LIMIT).coerceAtLeast(1)
        val contains = args.stringArg("contains")
        val since = args.intArg("since")?.coerceIn(0, lines.size)

        val matching = ((since ?: 0) until lines.size).filter { contains.isNullOrEmpty() || lines[it].contains(contains, ignoreCase = true) }
        val selected = if (since != null) matching.take(limit) else matching.takeLast(limit)

        // When paging forward, stop the cursor after the last returned line so nothing is skipped. Otherwise the caller has seen everything.
        val nextIndex = if (since != null && matching.size > limit) selected.last() + 1 else lines.size
        return JSONObject().put("total", lines.size).put("nextIndex", nextIndex).put("lines", JSONArray(selected.map { lines[it] }))
    }

    /**
     * Captures the current frame as a scaled JPEG.
     *
     * @param args The arguments: `scale`.
     * @returns The base64 image with its sizes.
     */
    private suspend fun screenshot(args: JSONObject): JSONObject {
        val scale = (args.doubleArg("scale") ?: 0.5).coerceIn(0.1, 1.0)
        val bitmap = captureFrame() ?: throw IllegalStateException("Screen capture is not running. Start the bot from the app first.")

        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        val bytes =
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        if (scaled !== bitmap) scaled.recycle()
        return imageReply(bytes, "Frame ${bitmap.width}x${bitmap.height}, sent at ${scaled.width}x${scaled.height}. Multiply image coordinates by ${"%.2f".format(1 / scale)} for screen pixels.")
    }

    /**
     * Runs one template match on the current frame.
     *
     * @param args The arguments: `name`, `confidence`, and `region`.
     * @returns The match result as JSON.
     */
    private suspend fun findTemplate(args: JSONObject): JSONObject {
        val name = args.stringArg("name") ?: throw IllegalArgumentException("`name` is required.")
        val confidence = args.doubleArg("confidence") ?: 0.0
        val region = args.optJSONArray("region")?.takeIf { it.length() == 4 }?.let { array -> IntArray(4) { array.optInt(it) } } ?: intArrayOf(0, 0, 0, 0)
        // The library aborts every match loop with an InterruptedException unless the bot is running.
        val game = Game.current
        if (game == null || !BotService.isRunning) throw IllegalStateException("Template matching only works while the bot runs. Start it from the overlay first.")

        val (point, _) =
            try {
                withContext(Dispatchers.IO) { game.imageUtils.findImage(name, tries = 1, confidence = confidence, region = region, suppressError = true) }
            } catch (e: java.io.FileNotFoundException) {
                throw IllegalArgumentException("Unknown template \"$name\". Use a path under assets/images without the extension, like \"components/button/back\".")
            }
        return JSONObject()
            .put("template", name)
            .put("found", point != null)
            .put("x", point?.x ?: JSONObject.NULL)
            .put("y", point?.y ?: JSONObject.NULL)
    }

    /**
     * Runs OCR on a rectangle of the current frame.
     *
     * @param args The arguments: `x`, `y`, `width`, `height`, `grayscale`, and `threshold`.
     * @returns The text read, or a note that nothing was read.
     */
    private suspend fun ocrRegion(args: JSONObject): String {
        val crop = listOf("x", "y", "width", "height").map { args.intArg(it) ?: throw IllegalArgumentException("`$it` is required.") }.toIntArray()
        val source = captureFrame() ?: throw IllegalStateException("Screen capture is not running. Start the bot from the app first.")

        val result =
            withContext(Dispatchers.IO) {
                imageUtils().findText(crop, grayscale = args.boolArg("grayscale") ?: true, thresh = args.boolArg("threshold") ?: true, sourceBitmap = source, debugName = "mcp_ocr")
            }
        return result.ifEmpty { "(no text read)" }
    }

    /**
     * Reads the saved settings rows, redacting secrets.
     *
     * @param args The arguments: `category`.
     * @returns The settings grouped by category.
     */
    private fun getSettings(args: JSONObject): JSONObject {
        val context = requireContext()
        val category = args.stringArg("category")
        val db = SQLiteSettingsManager.getInstance(context).readableDatabase
        val query = if (category != null) "SELECT category, key, value FROM settings WHERE category = ? ORDER BY key" else "SELECT category, key, value FROM settings ORDER BY category, key"

        val result = JSONObject()
        db.rawQuery(query, if (category != null) arrayOf(category) else null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(1)
                val isSecret = SECRET_KEY_PARTS.any { key.contains(it, ignoreCase = true) }
                val group = result.optJSONObject(cursor.getString(0)) ?: JSONObject().also { result.put(cursor.getString(0), it) }
                group.put(key, if (isSecret) "<redacted>" else cursor.getString(2))
            }
        }
        return result
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Control

    /**
     * Stops the bot and its screen capture, the same as pressing Stop in the app.
     *
     * @returns What happened.
     */
    private fun stopBot(): String {
        val context = requireContext()
        if (!MediaProjectionService.isRunning) return "Screen capture is not running, so the bot is already stopped."
        // Stopping the capture stops BotService and posts the "Not Running" event the app's UI listens for.
        context.startService(MediaProjectionService.getStopIntent(context))
        return "Stop requested."
    }

    /**
     * Pauses the bot at its next safe point and waits up to [HOLD_TIMEOUT_MS] for it to land.
     *
     * @returns The hold state.
     */
    private suspend fun hold(): JSONObject {
        if (!BotService.isRunning) throw IllegalStateException("The bot is not running, so there is nothing to hold.")
        if (BotHold.pauseState == BotHold.PauseState.NONE) BotHold.requestPause(HOLD_REASON)
        if (BotHold.pauseState == BotHold.PauseState.NONE) throw IllegalStateException("The bot has not reached its first safe point yet. Try again in a moment.")

        val deadline = System.currentTimeMillis() + HOLD_TIMEOUT_MS
        while (BotHold.pauseState == BotHold.PauseState.REQUESTED && System.currentTimeMillis() < deadline) delay(100)
        val held = BotHold.pauseState == BotHold.PauseState.PAUSED
        return JSONObject().put(
            "held",
            held,
        ).put("state", BotHold.pauseState.name).put("note", if (held) "Held. Call release when done." else "Still finishing its step. It holds at the next safe point.")
    }

    /**
     * Resumes a held bot. It re-reads the current screen first, so input sent while held is picked up.
     *
     * @returns The hold state.
     */
    private fun release(): JSONObject {
        BotHold.resume()
        return JSONObject().put("held", false).put("state", BotHold.pauseState.name)
    }

    /**
     * Taps a point while the bot is held.
     *
     * @param args The arguments: `x` and `y` in screen pixels.
     * @returns Whether the tap was sent.
     */
    private suspend fun tap(args: JSONObject): JSONObject {
        val x = args.doubleArg("x") ?: throw IllegalArgumentException("`x` is required.")
        val y = args.doubleArg("y") ?: throw IllegalArgumentException("`y` is required.")
        return sendInput { it.tap(x, y) }
    }

    /**
     * Swipes between two points while the bot is held.
     *
     * @param args The arguments: `x1`, `y1`, `x2`, `y2` in screen pixels, and `durationMs`.
     * @returns Whether the swipe was sent.
     */
    private suspend fun swipe(args: JSONObject): JSONObject {
        val points = listOf("x1", "y1", "x2", "y2").map { args.doubleArg(it)?.toFloat() ?: throw IllegalArgumentException("`$it` is required.") }
        val duration = (args.intArg("durationMs") ?: 500).coerceIn(50, 5_000).toLong()
        return sendInput { it.swipe(points[0], points[1], points[2], points[3], duration) }
    }

    /**
     * Sends one gesture while the bot is held, then waits [AFTER_INPUT_MS] so the game can react. Input while the bot runs would race its own taps.
     *
     * @param gesture Sends the gesture and returns whether it was dispatched.
     * @returns Whether the gesture was sent.
     */
    private suspend fun sendInput(gesture: (MyAccessibilityService) -> Boolean): JSONObject {
        if (BotHold.pauseState != BotHold.PauseState.PAUSED) throw IllegalStateException("Hold the bot first with `hold`. Input while it runs would race its own taps.")
        val sent = withContext(Dispatchers.IO) { gesture(MyAccessibilityService.getInstance()) }
        delay(AFTER_INPUT_MS)
        return JSONObject().put("sent", sent)
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Frame log

    /**
     * Lists the saved frames of one session.
     *
     * @param args The arguments: `session`, `since` and `until` as log stamps, and `limit`.
     * @returns The session, every session name, and the matching frames' stamps and slugs.
     */
    private fun listFrames(args: JSONObject): JSONObject {
        val session = args.stringArg("session")
        val since = args.stringArg("since")?.let(FrameLog::parseStamp)
        val until = args.stringArg("until")?.let(FrameLog::parseStamp)
        val limit = (args.intArg("limit") ?: DEFAULT_FRAME_LIMIT).coerceAtLeast(1)

        val all = FrameLog.frames(session)
        val matching = all.filter { (since == null || it.elapsedMs >= since) && (until == null || it.elapsedMs <= until) }
        val selected = if (since != null) matching.take(limit) else matching.takeLast(limit)
        return JSONObject()
            .put("session", all.firstOrNull()?.file?.parentFile?.name ?: session ?: JSONObject.NULL)
            .put("sessions", JSONArray(FrameLog.sessions()))
            .put("total", matching.size)
            .put("frames", JSONArray(selected.map { JSONObject().put("stamp", it.stamp).put("slug", it.slug) }))
    }

    /**
     * Returns the last saved frame at or before a log stamp.
     *
     * @param args The arguments: `time` as a log stamp, and `session`.
     * @returns The base64 image with its stamp and slug.
     */
    private fun frameAt(args: JSONObject): JSONObject {
        val time = FrameLog.parseStamp(args.stringArg("time") ?: throw IllegalArgumentException("`time` is required, like 00:12:34.567."))
        val frames = FrameLog.frames(args.stringArg("session"))
        if (frames.isEmpty()) throw IllegalStateException("No frames saved in that session yet.")
        val frame = frames.lastOrNull { it.elapsedMs <= time } ?: throw IllegalArgumentException("The first frame is at ${frames.first().stamp}, after that time.")
        return imageReply(frame.file.readBytes(), "Frame at ${frame.stamp} (${frame.slug}) in session ${frame.file.parentFile?.name}, saved at half scale.")
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Helpers

    /**
     * Takes a fresh frame off the main thread.
     *
     * @returns The frame, or null when screen capture is not running.
     */
    private suspend fun captureFrame(): Bitmap? {
        if (!MediaProjectionService.isRunning) return null
        return withContext(Dispatchers.IO) { MediaProjectionService.takeScreenshotNow() }
    }

    /**
     * Picks the image utilities to use. The live run's utilities carry the app's scenario-specific overrides, so they win while the bot runs.
     *
     * @returns The run's image utilities, or a shared fallback instance.
     */
    private fun imageUtils(): ImageUtils {
        val game = Game.current
        if (game != null && BotService.isRunning) return game.imageUtils
        return fallbackImageUtils ?: ImageUtils(requireContext()).also { fallbackImageUtils = it }
    }

    /**
     * Returns the kept application context.
     *
     * @returns The context.
     * @throws IllegalStateException If the server is stopped.
     */
    private fun requireContext(): Context = applicationContext ?: throw IllegalStateException("The server has no context.")

    /**
     * Builds an image reply that the bridge turns into MCP image content.
     *
     * @param jpeg The JPEG bytes.
     * @param note A line describing the image.
     * @returns `{image, mimeType, note}` with the image in base64.
     */
    private fun imageReply(jpeg: ByteArray, note: String): JSONObject =
        JSONObject().put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP)).put("mimeType", "image/jpeg").put("note", note)

    /**
     * Builds a failure reply.
     *
     * @param message The error message.
     * @returns `{ok: false, error}`.
     */
    private fun failure(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

    /**
     * Reads one integer argument.
     *
     * @param key The argument name.
     * @returns The value, or null when it is missing or not a number.
     */
    private fun JSONObject.intArg(key: String): Int? = (opt(key) as? Number)?.toInt()

    /**
     * Reads one number argument.
     *
     * @param key The argument name.
     * @returns The value, or null when it is missing or not a number.
     */
    private fun JSONObject.doubleArg(key: String): Double? = (opt(key) as? Number)?.toDouble()

    /**
     * Reads one boolean argument.
     *
     * @param key The argument name.
     * @returns The value, or null when it is missing or not a boolean.
     */
    private fun JSONObject.boolArg(key: String): Boolean? = opt(key) as? Boolean

    /**
     * Reads one string argument.
     *
     * @param key The argument name.
     * @returns The value, or null when it is missing, empty, or not a string.
     */
    private fun JSONObject.stringArg(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotEmpty() }
}
