package com.steve1316.uma_android_automation.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.events.JSEvent
import com.steve1316.automation_library.utils.MessageLog
import com.steve1316.automation_library.utils.UserStorageManager
import com.steve1316.uma_android_automation.bot.RunAnalytics
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.regex.Pattern

/**
 * Embedded WebSocket server that streams MessageLog entries in real-time to any browser on the local network. Built on Ktor Server CIO.
 *
 * When running, the server serves:
 * - HTTP GET "/" -> the log viewer HTML page (from assets).
 * - WebSocket "/" -> real-time log message streaming.
 */
object LogStreamServer {
    private const val TAG: String = "${SharedData.loggerTag}LogStreamServer"

    /** The Ktor embedded server instance. */
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Coroutine scope for managing server and background tasks. */
    private var serverScope: CoroutineScope? = null

    /** Application context for accessing assets and system services. */
    private var applicationContext: Context? = null

    /** Whether the log streaming server is currently running. */
    @Volatile
    var isRunning = false
        private set

    /** Mute flag to stop broadcasting logs after a run concludes. */
    @Volatile
    private var isMuted = false

    /** Thread-safe set of currently active WebSocket client sessions. */
    private val clients = CopyOnWriteArraySet<DefaultWebSocketServerSession>()

    /** Circular buffer for storing the most recent log messages. */
    private val messageBuffer = ArrayList<String>()

    /** Synchronization lock for thread-safe access to the message buffer. */
    private val bufferLock = Any()

    /** Maximum number of messages to retain in the history buffer. */
    private const val MAX_BUFFER_SIZE = 15000

    /** Pattern for matching logs: "00:12:34.567 \[DEBUG\] message content". */
    private val logPattern = Pattern.compile("^(\\n?)(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s*\\[(VERBOSE|DEBUG|INFO|WARN|ERROR)]\\s*(.*)", Pattern.DOTALL)

    /** Whether the bot is currently in the pre-debut phase. */
    private var isPreDebut: Boolean = false

    /** Regex pattern to detect the start of a training session. */
    private val actionTrainingPattern = Pattern.compile("\\[TRAINING] Now starting process to execute (\\w+) training")

    /** Regex pattern to detect the OCR-derived training level (1-5) for a specific stat. Emitted by analyzeTrainings when the Weight by Training Level feature is on. */
    private val trainingLevelPattern = Pattern.compile("\\[TRAINING] (\\w+) training level detected as Lvl ([1-5])\\.")

    /** Regex pattern to detect the completion of a race. */
    private val actionRacePattern = Pattern.compile("\\[RACE] Racing process for .*? is completed\\. Grade: (.*)", Pattern.CASE_INSENSITIVE)

    /** Regex pattern to detect mood recovery. */
    private val actionMoodPattern = Pattern.compile("\\[MOOD] Successfully recovered mood(?: via (.*))?", Pattern.CASE_INSENSITIVE)

    /** Regex pattern to detect successful energy recovery. */
    private val actionEnergyPattern = Pattern.compile("\\[ENERGY] Successfully recovered energy via (.*)", Pattern.CASE_INSENSITIVE)

    /** Regex pattern to detect energy level updates from item usage or emergency recovery, optionally with mood changes. */
    private val actionEnergyUpdatePattern =
        Pattern.compile(
            "(?:Trainee energy(?: and mood)? updated|Emergency recovery): (\\d+)%\\s*->\\s*(\\d+)%(?:,\\s*(\\w+)\\s*->\\s*(\\w+))?",
            Pattern.CASE_INSENSITIVE,
        )

    /** Regex pattern to detect injury detection and healing attempts. */
    private val actionInjuryPattern = Pattern.compile("\\[INJURY] Injury detected and attempted to heal")

    /** Regex pattern to extract trainee details. */
    private val traineePattern = Pattern.compile("\\[TRAINEE] ([^:]+): (.*)")

    /** Regex pattern to extract new date and turn info from bot logs. */
    private val dateNewPattern = Pattern.compile("\\[DATE] New date: (.*?) \\(Turn (\\d+)\\)", Pattern.CASE_INSENSITIVE)

    /** Regex pattern to extract detected date from OCR logs. */
    private val dateDetectedPattern = Pattern.compile("Detected date: (.*)", Pattern.CASE_INSENSITIVE)

    /** Regex pattern to extract turns remaining during extra racing. */
    private val turnsRemainingPattern = Pattern.compile("Detected day for extra racing: (\\d+)", Pattern.CASE_INSENSITIVE)

    /** Regex pattern to detect race day during extra racing. */
    private val turnsRemainingRaceDayPattern = Pattern.compile("Detected Race Day for extra racing:", Pattern.CASE_INSENSITIVE)

    /** Channel for serializing log actions to be processed by the background worker. */
    private var actionChannel: Channel<LogAction>? = null

    /** Most recent Race History calendar snapshot JSON (full schedule + win/loss results),
     *  pushed by the Smart Race Solver integration. Replayed to each new client after the
     *  log history flush so the calendar paints immediately on connect. Null until the first
     *  snapshot arrives this run. */
    @Volatile private var latestCalendarSnapshot: String? = null

    /** Latest Smart Race Solver enabled flag pushed by `Racing.kt` at run start. Replayed to
     *  each new client so the viewer can hide its Race History panel when SRS is off without
     *  waiting for any other signal. Null until the first run reports a value. */
    @Volatile private var latestSmartRaceSolverEnabled: Boolean? = null

    /** Latest run-analytics snapshot pushed by `RunAnalytics` at each turn boundary and at run
     *  end. Replayed to each new client after the log history flush so the Analytics tab paints
     *  immediately on connect. Null until the first snapshot arrives this run. */
    @Volatile private var latestAnalyticsSnapshot: String? = null

    /** Latest owned-skills snapshot pushed after the Skills tab is read. Replayed to each new client after the log history flush so the Skills panel paints
     *  immediately on connect. Null until the first snapshot arrives this run. */
    @Volatile private var latestSkillsSnapshot: String? = null

    /**
     * Represents a parsed log entry for structured transmission.
     *
     * @property newline Leading newline if present.
     * @property timestamp Extracted HH:mm:ss.SSS timestamp.
     * @property level Log level (DEBUG, INFO, WARN, ERROR, VERBOSE).
     * @property text The actual log message content.
     * @property action Identified bot action (training, race, injury, mood).
     * @property trainee Trainee detailed info (category and raw data).
     * @property dateInfo Extracted date and turn information.
     * @property energyInfo Extracted energy level update (from/to percentages).
     * @property trainingLevel Per-stat training level detection emitted during training analysis, shape: {stat, level}.
     */
    private data class LogEntry(
        val newline: String,
        val timestamp: String,
        val level: String,
        val text: String,
        val action: String? = null,
        val trainee: JSONObject? = null,
        val dateInfo: JSONObject? = null,
        val energyInfo: JSONObject? = null,
        val trainingLevel: JSONObject? = null,
    ) {
        /** Converts the log entry into a JSON object for WebSocket transmission. */
        fun toJSON(): JSONObject {
            return JSONObject().apply {
                put("newline", newline)
                put("timestamp", timestamp)
                put("level", level)
                put("message", text)
                action?.let { put("action", it) }
                trainee?.let { put("trainee", it) }
                dateInfo?.let { put("dateInfo", it) }
                energyInfo?.let { put("energyInfo", it) }
                trainingLevel?.let { put("trainingLevel", it) }
            }
        }
    }

    /** Sealed class representing different log actions to ensure sequential processing. */
    private sealed class LogAction {
        /**
         * Registers a new client session for log streaming.
         *
         * @property session The active WebSocket server session.
         */
        data class NewClient(val session: DefaultWebSocketServerSession) : LogAction()

        /**
         * Broadcasts a log message to all connected clients.
         *
         * @property message The raw log message to broadcast.
         */
        data class Broadcast(val message: String) : LogAction()

        /** Clears the history buffer and resets the mute flag. */
        object Clear : LogAction()

        /**
         * Broadcasts a Race History calendar snapshot (planned schedule + win/loss results)
         * to all connected clients. Distinct from [Broadcast] so calendar payloads never end
         * up in the log replay buffer.
         *
         * @property json Pre-serialized calendar snapshot JSON sent verbatim to clients with a `CAL:` prefix.
         */
        data class BroadcastCalendar(val json: String) : LogAction()

        /**
         * Broadcasts the Smart Race Solver enabled flag to all connected clients. Distinct from
         * [Broadcast] so the framing message never lands in the log replay buffer.
         *
         * @property enabled True when SRS is enabled for the current run.
         */
        data class BroadcastSmartRaceSolverState(val enabled: Boolean) : LogAction()

        /**
         * Broadcasts a run-analytics snapshot to all connected clients. Distinct from [Broadcast]
         * so analytics payloads never end up in the log replay buffer.
         *
         * @property json Pre-serialized analytics snapshot JSON sent verbatim to clients with an `ANALYTICS:` prefix.
         */
        data class BroadcastAnalytics(val json: String) : LogAction()

        /**
         * Broadcasts an owned-skills snapshot to all connected clients. Distinct from [Broadcast] so the framing message never lands in the log replay buffer.
         *
         * @property json Pre-serialized skills snapshot JSON sent verbatim to clients with a `SKILLS:` prefix.
         */
        data class BroadcastSkills(val json: String) : LogAction()
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /** Resets the mute flag and clears the buffer to allow log broadcasting for a new run. */
    fun resetMute() {
        Log.i(TAG, "[LogStreamServer] Log stream mute reset requested.")
        serverScope?.launch {
            actionChannel?.send(LogAction.Clear)
        }
    }

    /**
     * Subscriber for MessageLog events via EventBus.
     *
     * Broadcasts each received log message to all currently connected WebSocket clients.
     *
     * @param event The [JSEvent] object containing the log message metadata and content.
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onMessageLogEvent(event: JSEvent) {
        // Only process events that are identified as MessageLog entries.
        if (event.eventName == "MessageLog") {
            broadcast(event.message)
        }
    }

    /**
     * Subscriber for MediaProjectionService events via EventBus.
     *
     * Stops the log server if the projection service is stopped via notification or UI.
     *
     * @param event The JSEvent object containing the event details.
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onMediaProjectionEvent(event: JSEvent) {
        if (event.eventName == "MediaProjectionService" && event.message == "Not Running") {
            Log.i(TAG, "[LogStreamServer] MediaProjectionService stopped. Initiating LogStreamServer shutdown.")
            stop()
        }
    }

    /**
     * Retrieves the device's local IP address as a human-readable string.
     *
     * Prioritizes actual network interfaces over the WifiManager, which can be unreliable or restricted on newer Android versions.
     *
     * @param context The application context.
     * @return The device's local IP address or "0.0.0.0" if unavailable.
     */
    @SuppressLint("DefaultLocale")
    fun getDeviceIpAddress(context: Context): String {
        val foundIps = mutableListOf<String>()
        try {
            // Enumerate all network interfaces on the device.
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addresses = Collections.list(intf.inetAddresses)
                for (address in addresses) {
                    // Skip loopback addresses to find real network IPs.
                    if (!address.isLoopbackAddress) {
                        val strAddress = address.hostAddress
                        val isIPv4 = strAddress?.indexOf(':')!! < 0
                        if (isIPv4) {
                            foundIps.add(strAddress)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[WARN] getDeviceIpAddress:: Could not determine IP address via NetworkInterface: ${e.message}")
        }

        if (foundIps.isNotEmpty()) {
            Log.d(TAG, "[DEBUG] getDeviceIpAddress:: Found local IPs: $foundIps")

            // Prioritize private network address ranges common in local networks.
            val bestIp =
                foundIps.find { it.startsWith("192.168.") }
                    ?: foundIps.find { it.startsWith("10.") && it != "10.0.2.15" }
                    ?: foundIps.find { it.startsWith("172.16.") || it.startsWith("172.31.") }
                    ?: foundIps[0]

            return bestIp
        }

        // Fallback to the WifiManager for older devices or if the interface scan fails.
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress

            if (ipInt != 0) {
                // Convert the integer representation of the IP address to a dotted string.
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "[WARN] getDeviceIpAddress:: Could not determine WiFi IP address via WifiManager: ${e.message}")
        }

        return "0.0.0.0"
    }

    /**
     * Serves the log_viewer.html page from the Android assets directory.
     *
     * @param call The Ktor application call to respond to.
     * @param context The application context used for accessing assets.
     */
    private suspend fun serveLogViewerHtml(call: ApplicationCall, context: Context) {
        try {
            val htmlStream = context.assets.open("log_viewer.html")
            val html = htmlStream.bufferedReader().use { it.readText() }
            call.respondText(html, ContentType.Text.Html)
        } catch (e: IOException) {
            Log.e(TAG, "[ERROR] serveLogViewerHtml:: Failed to load log_viewer.html asset: ${e.message}")
            call.respondText(
                "Failed to load log viewer page.",
                ContentType.Text.Plain,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    /**
     * Handles an individual WebSocket session lifecycle.
     *
     * Enqueues a NewClient action to ensure sequential history delivery relative to live broadcasts.
     *
     * @param session The active WebSocket server session.
     */
    private suspend fun handleWebSocketSession(session: DefaultWebSocketServerSession) {
        Log.d(TAG, "[DEBUG] WebSocket client connection initiated.")

        // Enqueue the registration action to the background worker.
        actionChannel?.send(LogAction.NewClient(session))

        try {
            // Keep the session alive until the client disconnects.
            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    if (text == "CMD:REFRESH_IMAGES") {
                        sendDebugImages(session)
                    } else if (text == "CMD:RESET_ANALYTICS") {
                        applicationContext?.let { RunAnalytics.discardHistory(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[WARN] handleWebSocketSession:: WebSocket session exception: ${e.message}")
        } finally {
            clients.remove(session)
            Log.d(TAG, "[DEBUG] handleWebSocketSession:: WebSocket client disconnected.")
        }
    }

    /**
     * Performs history synchronization for a new client session.
     *
     * Called by the background worker to ensure no live logs are sent before history is done.
     *
     * @param session The active WebSocket server session.
     */
    private suspend fun handleNewClientAction(session: DefaultWebSocketServerSession) {
        try {
            val historyToSync =
                synchronized(bufferLock) {
                    // Send chronological (oldest to newest).
                    messageBuffer.toList()
                }

            if (historyToSync.isNotEmpty()) {
                val jsonArray = JSONArray()
                for (msg in historyToSync) {
                    jsonArray.put(parseLogToJSON(msg))
                }
                session.send(Frame.Text("HB:$jsonArray"))
            }
            session.send(Frame.Text("HISTORY_DONE"))

            // Replay the most recent Race History calendar snapshot (if any) so the viewer
            // paints its calendar panel without waiting for the next race result.
            latestCalendarSnapshot?.let { snapshot ->
                try {
                    session.send(Frame.Text("CAL:$snapshot"))
                } catch (e: Exception) {
                    Log.w(TAG, "[WARN] handleNewClientAction:: Failed to replay calendar snapshot: ${e.message}")
                }
            }

            // Replay the most recent Smart Race Solver enabled flag (if any) so the viewer can
            // hide its Race History panel immediately when SRS is off, without flashing the
            // empty calendar grid first.
            latestSmartRaceSolverEnabled?.let { enabled ->
                try {
                    session.send(Frame.Text("SRS_STATE:$enabled"))
                } catch (e: Exception) {
                    Log.w(TAG, "[WARN] handleNewClientAction:: Failed to replay SRS state: ${e.message}")
                }
            }

            // Replay the most recent run-analytics snapshot (if any) so the Analytics tab paints
            // immediately on a mid-run browser refresh without waiting for the next turn.
            latestAnalyticsSnapshot?.let { snapshot ->
                try {
                    session.send(Frame.Text("ANALYTICS:$snapshot"))
                } catch (e: Exception) {
                    Log.w(TAG, "[WARN] handleNewClientAction:: Failed to replay analytics snapshot: ${e.message}")
                }
            }

            // Replay the most recent owned-skills snapshot (if any) so the Skills panel paints immediately on a mid-run browser refresh.
            latestSkillsSnapshot?.let { snapshot ->
                try {
                    session.send(Frame.Text("SKILLS:$snapshot"))
                } catch (e: Exception) {
                    Log.w(TAG, "[WARN] handleNewClientAction:: Failed to replay skills snapshot: ${e.message}")
                }
            }

            // Now that history is synced, add to clients for real-time broadcasts.
            clients.add(session)
            Log.d(TAG, "[DEBUG] handleNewClientAction:: WebSocket client history sync complete and added to active set.")
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] handleNewClientAction:: Failed to sync history for new client: ${e.message}")
        }
    }

    /**
     * Scans the temp directory, compresses found images, and sends them to the client.
     *
     * @param session The active WebSocket server session.
     */
    private suspend fun sendDebugImages(session: DefaultWebSocketServerSession) {
        val context = applicationContext ?: return
        val tempDir = File(context.filesDir, "temp")
        if (!tempDir.exists() || !tempDir.isDirectory) {
            Log.w(TAG, "[WARN] sendDebugImages:: Temp directory does not exist or is not a directory: ${tempDir.absolutePath}")
            return
        }

        val imageFiles =
            tempDir.listFiles { _, name ->
                name.lowercase().endsWith(".png") ||
                    name.lowercase().endsWith(".jpg") ||
                    name.lowercase().endsWith(".jpeg") ||
                    name.lowercase().endsWith(".webp")
            } ?: return

        Log.d(TAG, "[DEBUG] sendDebugImages:: Found ${imageFiles.size} image files in temp directory.")

        // Send newest first so the most recent capture lands at the top of the viewer grid.
        for (file in imageFiles.sortedByDescending { it.lastModified() }) {
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val outputStream = ByteArrayOutputStream()
                    // Compress to 50% quality JPEG.
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
                    val byteArray = outputStream.toByteArray()
                    val base64Image = Base64.encodeToString(byteArray, Base64.DEFAULT)

                    val json =
                        JSONObject().apply {
                            put("type", "image")
                            put("name", file.name)
                            put("timestamp", file.lastModified())
                            put("data", base64Image)
                        }
                    session.send(Frame.Text(json.toString()))
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[ERROR] sendDebugImages:: Failed to send image ${file.name}: ${e.message}")
            }
        }

        // Signal completion of image batch transmission.
        session.send(Frame.Text(JSONObject().apply { put("type", "image_batch_done") }.toString()))
    }

    /** Metadata for a single saved log file, sourced from either the SAF tree or the legacy logs dir. */
    private data class LogFileEntry(
        /** File name including the .txt extension. */
        val name: String,
        /** File size in bytes. */
        val size: Long,
        /** Last-modified timestamp in epoch milliseconds. */
        val modified: Long,
    )

    /**
     * Lists the completed-session .txt log files. Honors the user-selected SAF folder when one is configured and falls back to the
     * legacy getExternalFilesDir/logs path otherwise, so the viewer reads from wherever [MessageLog] actually wrote them.
     *
     * @param context Context used to resolve the storage location.
     * @return The log file entries, unsorted.
     */
    private fun listLogFiles(context: Context): List<LogFileEntry> {
        val storage = UserStorageManager.getInstance(context)
        if (storage.isConfigured()) {
            return storage.listFiles("logs")
                .filter { it.isFile && it.name?.lowercase()?.endsWith(".txt") == true }
                .map { LogFileEntry(it.name ?: "", it.length(), it.lastModified()) }
        }
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        if (!logsDir.exists() || !logsDir.isDirectory) return emptyList()
        return (logsDir.listFiles { _, name -> name.lowercase().endsWith(".txt") } ?: emptyArray())
            .map { LogFileEntry(it.name, it.length(), it.lastModified()) }
    }

    /**
     * Reads the text content of a single saved log file by name, honoring the SAF folder when configured.
     *
     * @param context Context used to resolve the storage location.
     * @param name The log file name to read.
     * @return The file content, or null when the file does not exist or could not be read.
     */
    private fun readLogFile(context: Context, name: String): String? {
        return UserStorageManager.getInstance(context).openInputStream("logs", name)?.use { it.bufferedReader().readText() }
    }

    /**
     * Parses a raw log string into a JSON object.
     *
     * @param message The raw log message to parse.
     * @return A [LogEntry] containing the parsed log data.
     */
    private fun parseLogToJSON(message: String): JSONObject {
        val matcher = logPattern.matcher(message)
        return if (matcher.find()) {
            val newline = matcher.group(1) ?: ""
            val timestamp = matcher.group(2) ?: ""
            val level = matcher.group(3) ?: "DEBUG"
            val text = matcher.group(4) ?: ""

            // Extract high-level details for dashboard updates.
            val action = detectAction(text)
            val trainee = parseTraineeInfo(text)
            val dateInfo =
                if (text.contains("[DATE]") ||
                    text.contains("New date:", ignoreCase = true) ||
                    text.contains("Detected date:", ignoreCase = true) ||
                    text.contains("Detected day for extra racing:", ignoreCase = true) ||
                    text.contains("Detected Race Day for extra racing:", ignoreCase = true) ||
                    text.contains("Turn", ignoreCase = true)
                ) {
                    parseDateInfo(text)
                } else {
                    null
                }

            val energyInfo = parseEnergyInfo(text)
            val trainingLevel = parseTrainingLevel(text)

            LogEntry(newline, timestamp, level, text, action, trainee, dateInfo, energyInfo, trainingLevel).toJSON()
        } else {
            // Fallback: detect level and treat entire message as text.
            val level =
                when {
                    message.contains("[ERROR]") -> "ERROR"
                    message.contains("[WARN]") -> "WARN"
                    message.contains("[INFO]") -> "INFO"
                    message.contains("[VERBOSE]") -> "VERBOSE"
                    else -> "DEBUG"
                }
            LogEntry("", "", level, message).toJSON()
        }
    }

    /**
     * Detects common bot actions for session counters.
     *
     * @param text The log message text to check.
     * @return The action name if detected, otherwise null.
     */
    private fun detectAction(text: String): String? {
        val trainingMatcher = actionTrainingPattern.matcher(text)
        if (trainingMatcher.find()) {
            val stat = trainingMatcher.group(1)?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
            return if (stat.isNotEmpty()) "training:$stat" else "training"
        }

        val raceMatcher = actionRacePattern.matcher(text)
        if (raceMatcher.find()) {
            val rawGrade = raceMatcher.group(1)?.trim()?.uppercase() ?: ""
            val normalizedGrade =
                when (rawGrade) {
                    "OP" -> "OP"
                    "PRE_OP" -> "Pre-OP"
                    "G1", "G2", "G3" -> rawGrade
                    else -> rawGrade.lowercase().replaceFirstChar { it.uppercase() }
                }
            return if (normalizedGrade.isNotEmpty()) "race:$normalizedGrade" else "race"
        }

        val moodMatcher = actionMoodPattern.matcher(text)
        if (moodMatcher.find()) {
            val type =
                when (moodMatcher.group(1)?.lowercase()) {
                    "recreation date" -> "Date"
                    "summer rest" -> "Summer"
                    else -> "Recreation"
                }
            return "mood:$type"
        }

        val energyMatcher = actionEnergyPattern.matcher(text)
        if (energyMatcher.find()) {
            val type =
                when (energyMatcher.group(1)?.lowercase()) {
                    "recreation date" -> "Date"
                    "summer rest" -> "Summer"
                    "rest" -> "Rest"
                    else -> "Rest"
                }
            return "energy:$type"
        }

        return when {
            actionInjuryPattern.matcher(text).find() -> "injury"
            else -> null
        }
    }

    /**
     * Parses trainee detailed info for the dashboard.
     *
     * @param text The log message text to check.
     * @return A [JSONObject] containing the trainee details if detected, otherwise null.
     */
    private fun parseTraineeInfo(text: String): JSONObject? {
        val matcher = traineePattern.matcher(text)
        return if (matcher.find()) {
            JSONObject().apply {
                put("category", matcher.group(1))
                put("data", matcher.group(2))
            }
        } else {
            null
        }
    }

    /**
     * Parses date and turn information for the dashboard.
     *
     * @param text The log message text to check.
     * @return A [JSONObject] containing the date and turn if detected, otherwise null.
     */
    private fun parseDateInfo(text: String): JSONObject? {
        // Priority 1: Main bot date update log.
        val newDateMatcher = dateNewPattern.matcher(text)
        if (newDateMatcher.find()) {
            val date = newDateMatcher.group(1)?.trim() ?: ""
            val turn = newDateMatcher.group(2) ?: ""

            // Update current phase state.
            isPreDebut = (turn.toIntOrNull() ?: 13) <= 12

            return JSONObject().apply {
                put("date", date)
                put("turn", turn)
            }
        }

        // Priority 2: Pre-Debut translation.
        // If we detect turns remaining during pre-debut, we can calculate the absolute turn and date.
        val turnsRemainingMatcher = turnsRemainingPattern.matcher(text)
        if (turnsRemainingMatcher.find()) {
            if (isPreDebut) {
                val turnsLeft = turnsRemainingMatcher.group(1)?.toIntOrNull() ?: -1
                if (turnsLeft != -1) {
                    val turn = (12 - turnsLeft).coerceIn(1, 12)
                    val date = dateFromDay(turn)
                    return JSONObject().apply {
                        put("date", date)
                        put("turn", turn.toString())
                    }
                }
            }
        }

        val turnsRemainingRaceDayMatcher = turnsRemainingRaceDayPattern.matcher(text)
        if (turnsRemainingRaceDayMatcher.find()) {
            if (isPreDebut) {
                val turn = 12
                val date = dateFromDay(turn)
                return JSONObject().apply {
                    put("date", date)
                    put("turn", turn.toString())
                }
            }
        }

        // Priority 3: Raw OCR date detection log.
        val detectedDateMatcher = dateDetectedPattern.matcher(text)
        if (detectedDateMatcher.find()) {
            val date = detectedDateMatcher.group(1)?.trim() ?: ""
            // If it's the Pre-Debut string, ignore it and wait for the turn calculation log.
            if (date.contains("debut", ignoreCase = true)) {
                isPreDebut = true
                return null
            }

            isPreDebut = false

            return JSONObject().apply {
                put("date", date)
            }
        }

        // Priority 4: Fallback for any log containing turn info like "... (Turn X)".
        val turnOnlyPattern = Pattern.compile("(.*?)\\s*\\(Turn (\\d+)\\)", Pattern.CASE_INSENSITIVE)
        val turnMatcher = turnOnlyPattern.matcher(text)
        if (turnMatcher.find()) {
            val prefix = turnMatcher.group(1) ?: ""
            val turn = turnMatcher.group(2) ?: ""

            // Update state based on absolute turn number.
            isPreDebut = (turn.toIntOrNull() ?: 13) <= 12

            // Try to extract date from prefix if it looks like a date string.
            // Common pattern: "fromDateString:: Detected Junior Year Early January (Turn 1)"
            val date =
                if (prefix.contains("Detected ", ignoreCase = true)) {
                    prefix.substringAfter("Detected ", "").trim()
                } else {
                    ""
                }

            return JSONObject().apply {
                if (date.isNotEmpty()) put("date", date)
                put("turn", turn)
            }
        }

        return null
    }

    /**
     * Parses energy level update information for the dashboard.
     *
     * @param text The log message text to check.
     * @return A [JSONObject] containing the from/to energy percentages if detected, otherwise null.
     */
    private fun parseEnergyInfo(text: String): JSONObject? {
        val matcher = actionEnergyUpdatePattern.matcher(text)
        return if (matcher.find()) {
            JSONObject().apply {
                put("from", matcher.group(1)?.toIntOrNull() ?: 0)
                put("to", matcher.group(2)?.toIntOrNull() ?: 0)
                matcher.group(3)?.let { fromMood ->
                    matcher.group(4)?.let { toMood ->
                        put("moodFrom", fromMood)
                        put("moodTo", toMood)
                    }
                }
            }
        } else {
            null
        }
    }

    /**
     * Parses a "training level detected" log line into a structured dashboard payload.
     *
     * Emitted by analyzeTrainings when the Weight Score by Training Level feature is enabled. The stat is normalized to title case
     * to match the action-counter sub-type format ("Speed", "Stamina", "Power", "Guts", "Wit").
     *
     * @param text The log message text to check.
     * @return A [JSONObject] with `stat` (String) and `level` (Int 1-5) if detected, otherwise null.
     */
    private fun parseTrainingLevel(text: String): JSONObject? {
        val matcher = trainingLevelPattern.matcher(text)
        return if (matcher.find()) {
            val stat = matcher.group(1)?.lowercase()?.replaceFirstChar { it.uppercase() } ?: return null
            val level = matcher.group(2)?.toIntOrNull() ?: return null
            JSONObject().apply {
                put("stat", stat)
                put("level", level)
            }
        } else {
            null
        }
    }

    /**
     * Converts a turn number to a descriptive date string.
     *
     * @param day The turn number (day) to convert.
     * @return The descriptive date string.
     */
    private fun dateFromDay(day: Int): String {
        val d = day - 1
        val y = d / 24
        val m = (d % 24) / 2
        val p = d % 2

        val years = listOf("Junior Year", "Classic Year", "Senior Year")
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val phases = listOf("Early", "Late")

        val yearStr = years.getOrElse(y) { "" }
        val monthStr = months.getOrElse(m) { "" }
        val phaseStr = phases.getOrElse(p) { "" }

        return "$yearStr $phaseStr $monthStr"
    }

    /** Clears all messages from the history buffer. */
    private fun clearBuffer() {
        synchronized(bufferLock) {
            messageBuffer.clear()
        }
    }

    /**
     * Handles a clear action by clearing the history buffer and notifying all clients.
     *
     * Called by the background worker.
     */
    private suspend fun handleClearAction() {
        Log.i(TAG, "[LogStreamServer] Executing log clear action.")
        clearBuffer()

        // Drop the cached calendar so the new run starts with no replay.
        latestCalendarSnapshot = null

        // Drop the cached SRS-enabled flag so the next run's Racing init is the source of truth.
        latestSmartRaceSolverEnabled = null

        // Drop the cached analytics snapshot so the new run starts with no replay.
        latestAnalyticsSnapshot = null

        // Drop the cached skills snapshot so the new run starts with no replay.
        latestSkillsSnapshot = null

        // Ensure we are unmuted for the new run.
        isMuted = false

        // Signal all connected clients to clear their displays.
        for (client in clients) {
            try {
                client.send(Frame.Text("CMD:CLEAR"))
            } catch (_: Exception) {
                clients.remove(client)
            }
        }
    }

    /**
     * Pre-fills the history buffer with a given list of existing log messages.
     *
     * @param history A list of log message strings to initialize the buffer.
     */
    private fun populateBuffer(history: List<String>) {
        synchronized(bufferLock) {
            messageBuffer.clear()
            // Only retain the last messages of the maximum buffer size to avoid overflow.
            val start = (history.size - MAX_BUFFER_SIZE).coerceAtLeast(0)
            for (i in start until history.size) {
                messageBuffer.add(history[i])
            }
            Log.d(TAG, "[DEBUG] populateBuffer:: Populated buffer with ${messageBuffer.size} historical logs.")
        }
    }

    /**
     * Enqueues a log message for sequential processing.
     *
     * @param message The log message to broadcast.
     */
    private fun broadcast(message: String) {
        if (!isRunning) return

        // Enqueue the broadcast action to ensure it is processed chronologically relative to new sessions.
        serverScope?.launch {
            actionChannel?.send(LogAction.Broadcast(message))
        }
    }

    /**
     * Pushes a Race History calendar snapshot to all connected clients and caches it for
     * replay to clients that connect later. Called by the Smart Race Solver integration
     * after every race result and once at solver init.
     *
     * @param json Pre-serialized calendar snapshot JSON (`{currentTurn, decisions, results}`).
     */
    fun broadcastCalendarSnapshot(json: String) {
        latestCalendarSnapshot = json
        if (!isRunning) return
        serverScope?.launch {
            actionChannel?.send(LogAction.BroadcastCalendar(json))
        }
    }

    /**
     * Sends a calendar snapshot to all currently connected clients with the `CAL:` framing
     * prefix the viewer uses to route the payload to its calendar renderer. Skipped silently
     * when no clients are connected.
     *
     * @param json The pre-serialized calendar snapshot JSON.
     */
    private suspend fun handleCalendarBroadcast(json: String) {
        if (clients.isEmpty()) return
        val frame = "CAL:$json"
        for (client in clients) {
            try {
                client.send(Frame.Text(frame))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Pushes the Smart Race Solver enabled flag to all connected clients and caches it for replay
     * to clients that connect later. Called once per run by `Racing.kt` when the captured value
     * is read from settings.
     *
     * @param enabled True when the Smart Race Solver feature is on for the current run.
     */
    fun broadcastSmartRaceSolverEnabled(enabled: Boolean) {
        latestSmartRaceSolverEnabled = enabled
        if (!isRunning) return
        serverScope?.launch {
            actionChannel?.send(LogAction.BroadcastSmartRaceSolverState(enabled))
        }
    }

    /**
     * Pushes a run-analytics snapshot to all connected clients and caches it for replay to
     * clients that connect later. Called by `RunAnalytics` at each turn boundary and at run end.
     *
     * @param json Pre-serialized analytics snapshot JSON.
     */
    fun broadcastAnalyticsSnapshot(json: String) {
        latestAnalyticsSnapshot = json
        if (!isRunning) return
        serverScope?.launch {
            actionChannel?.send(LogAction.BroadcastAnalytics(json))
        }
    }

    /**
     * Caches and broadcasts an owned-skills snapshot to all connected clients and replays it to clients that connect later. Called after the Skills tab is read.
     *
     * @param json Pre-serialized skills snapshot JSON.
     */
    fun broadcastSkillsSnapshot(json: String) {
        latestSkillsSnapshot = json
        if (!isRunning) return
        serverScope?.launch {
            actionChannel?.send(LogAction.BroadcastSkills(json))
        }
    }

    /**
     * Sends the Smart Race Solver state to all currently connected clients with the `SRS_STATE:`
     * framing prefix. Payload is the lowercase boolean (`true` or `false`).
     *
     * @param enabled The enabled flag to broadcast.
     */
    private suspend fun handleSmartRaceSolverStateBroadcast(enabled: Boolean) {
        if (clients.isEmpty()) return
        val frame = "SRS_STATE:$enabled"
        for (client in clients) {
            try {
                client.send(Frame.Text(frame))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Sends a run-analytics snapshot to all currently connected clients with the `ANALYTICS:`
     * framing prefix the viewer uses to route the payload to its Analytics tab. Skipped silently
     * when no clients are connected.
     *
     * @param json The pre-serialized analytics snapshot JSON.
     */
    private suspend fun handleAnalyticsBroadcast(json: String) {
        if (clients.isEmpty()) return
        val frame = "ANALYTICS:$json"
        for (client in clients) {
            try {
                client.send(Frame.Text(frame))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Sends an owned-skills snapshot to all currently connected clients with the `SKILLS:` framing prefix the viewer uses to route the payload to its Skills panel.
     * Skipped silently when no clients are connected.
     *
     * @param json The pre-serialized skills snapshot JSON.
     */
    private suspend fun handleSkillsBroadcast(json: String) {
        if (clients.isEmpty()) return
        val frame = "SKILLS:$json"
        for (client in clients) {
            try {
                client.send(Frame.Text(frame))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Processes a broadcast action by updating the buffer and sending to all active clients.
     *
     * Called by the background worker.
     *
     * @param message The log message to broadcast.
     */
    private suspend fun handleBroadcastAction(message: String) {
        if (isMuted) return

        // Update history buffer.
        synchronized(bufferLock) {
            messageBuffer.add(message)
            if (messageBuffer.size > MAX_BUFFER_SIZE) {
                messageBuffer.removeAt(0)
            }
        }

        // Detect end-of-run markers to mute subsequent logs after this one is buffered/sent.
        if (message.contains("Campaign main loop exiting") || message.contains("Total runtime of")) {
            Log.i(TAG, "[LogStreamServer] End of run detected in logs. Muting subsequent broadcasts.")
            isMuted = true
        }

        // Skip transmission if no clients.
        if (clients.isEmpty()) return

        // Broadcast as JSON to all active clients.
        val jsonResponse = parseLogToJSON(message).toString()
        for (client in clients) {
            try {
                client.send(Frame.Text(jsonResponse))
            } catch (_: Exception) {
                clients.remove(client)
            }
        }
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Starts the log streaming server on the specified port and registers with EventBus.
     *
     * @param context The application context used for accessing assets and system services.
     * @param port The network port number to listen on.
     */
    fun start(context: Context, port: Int) {
        // Prevent starting multiple instances of the server.
        if (isRunning) {
            Log.i(TAG, "[LogStreamServer] Server is already running.")
            return
        }

        try {
            applicationContext = context.applicationContext
            serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            actionChannel = Channel(Channel.UNLIMITED)

            // Start the core log worker that serializes all history syncs and live broadcasts.
            serverScope?.launch {
                actionChannel?.let { channel ->
                    for (action in channel) {
                        when (action) {
                            is LogAction.NewClient -> {
                                handleNewClientAction(action.session)
                            }

                            is LogAction.Broadcast -> {
                                handleBroadcastAction(action.message)
                            }

                            is LogAction.Clear -> {
                                handleClearAction()
                            }

                            is LogAction.BroadcastCalendar -> {
                                handleCalendarBroadcast(action.json)
                            }

                            is LogAction.BroadcastSmartRaceSolverState -> {
                                handleSmartRaceSolverStateBroadcast(action.enabled)
                            }

                            is LogAction.BroadcastAnalytics -> {
                                handleAnalyticsBroadcast(action.json)
                            }

                            is LogAction.BroadcastSkills -> {
                                handleSkillsBroadcast(action.json)
                            }
                        }
                    }
                }
            }

            // Bind to all interfaces so devices on the local network can connect.
            server =
                embeddedServer(CIO, host = "0.0.0.0", port = port) {
                    // Install the WebSockets plugin with default configuration.
                    install(WebSockets)

                    routing {
                        // Serve the main log viewer HTML application.
                        get("/") {
                            serveLogViewerHtml(call, context)
                        }
                        get("/index.html") {
                            serveLogViewerHtml(call, context)
                        }

                        // Provide a health check endpoint for monitoring the server status.
                        get("/health") {
                            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                        }

                        // Serve the full message log for download.
                        get("/logs/download") {
                            try {
                                val fullLogs = MessageLog.getMessageLogCopy().joinToString("\n")

                                // Name the download exactly like a saved log file, stamp and no-name form alike, so the Event Log Visualizer reads
                                // the trainee back instead of treating the fallback as a name. Mirrors MessageLog.saveLogToFile().
                                val datePart = SimpleDateFormat("yyyy-MM-dd HH_mm_ss", Locale.getDefault()).format(Date())
                                val prefix = MessageLog.logFileNamePrefix
                                val fileName = if (prefix.isEmpty()) "log @ $datePart" else "${prefix}_$datePart"
                                call.response.header(
                                    HttpHeaders.ContentDisposition,
                                    "attachment; filename=\"$fileName.txt\"",
                                )
                                call.respondText(fullLogs, ContentType.Text.Plain)
                            } catch (e: Exception) {
                                Log.e(TAG, "[ERROR] /logs/download:: Failed to generate log download: ${e.message}")
                                call.respondText(
                                    "Failed to generate log download.",
                                    ContentType.Text.Plain,
                                    HttpStatusCode.InternalServerError,
                                )
                            }
                        }

                        // List every completed-session .txt log file in the /logs/ directory, sorted most-recent-first.
                        get("/logs/files") {
                            try {
                                val filesArray = JSONArray()
                                var totalSize = 0L

                                for (entry in listLogFiles(context).sortedByDescending { it.modified }) {
                                    totalSize += entry.size
                                    filesArray.put(
                                        JSONObject().apply {
                                            put("name", entry.name)
                                            put("size", entry.size)
                                            put("modified", entry.modified)
                                        },
                                    )
                                }

                                val response =
                                    JSONObject().apply {
                                        put("files", filesArray)
                                        put("count", filesArray.length())
                                        put("totalSize", totalSize)
                                    }
                                call.respondText(response.toString(), ContentType.Application.Json)
                            } catch (e: Exception) {
                                Log.e(TAG, "[ERROR] /logs/files:: Failed to list log files: ${e.message}")
                                call.respondText(
                                    """{"files":[],"count":0,"totalSize":0,"error":"Failed to list log files."}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.InternalServerError,
                                )
                            }
                        }

                        // Serve a single log file by name. Inline by default, or as a download attachment when ?download=1.
                        get("/logs/files/{filename}") {
                            try {
                                val raw = call.parameters["filename"]
                                if (raw.isNullOrEmpty()) {
                                    call.respondText("Missing filename.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                                    return@get
                                }
                                // Reject path-traversal characters and any name not ending in .txt.
                                if (raw.contains('/') ||
                                    raw.contains('\\') ||
                                    raw.contains("..") ||
                                    raw.contains('\u0000') ||
                                    !raw.lowercase().endsWith(".txt")
                                ) {
                                    call.respondText("Invalid filename.", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                                    return@get
                                }

                                // Whitelist against the actual directory listing - the canonical guarantee against any encoded payload.
                                val available = listLogFiles(context).map { it.name }.toSet()
                                if (raw !in available) {
                                    call.respondText("File not found.", ContentType.Text.Plain, HttpStatusCode.NotFound)
                                    return@get
                                }

                                val content = readLogFile(context, raw)
                                if (content == null) {
                                    call.respondText("File not found.", ContentType.Text.Plain, HttpStatusCode.NotFound)
                                    return@get
                                }

                                val isDownload = call.request.queryParameters["download"] == "1"
                                if (isDownload) {
                                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$raw\"")
                                } else {
                                    call.response.header(HttpHeaders.ContentDisposition, "inline")
                                }
                                call.respondText(content, ContentType.Text.Plain)
                            } catch (e: Exception) {
                                Log.e(TAG, "[ERROR] /logs/files/{filename}:: Failed to serve log file: ${e.message}")
                                call.respondText(
                                    "Failed to serve log file.",
                                    ContentType.Text.Plain,
                                    HttpStatusCode.InternalServerError,
                                )
                            }
                        }

                        // Handle WebSocket connections on root path (matches the HTML client).
                        webSocket("/") {
                            handleWebSocketSession(this)
                        }
                    }
                }

            // Start the server without blocking the calling thread.
            server?.start(wait = false)
            isRunning = true

            // Register with EventBus to receive real-time log messages.
            if (!EventBus.getDefault().isRegistered(this)) {
                EventBus.getDefault().register(this)
            }

            // Determine and log the device IP address for easy access.
            val ip = getDeviceIpAddress(context)
            Log.i(TAG, "[LogStreamServer] Log stream server started on http://$ip:$port")

            // Populate the initial buffer from existing logs so late-joining clients see the history.
            populateBuffer(MessageLog.getMessageLogCopy())

            Log.d(TAG, "[DEBUG] start:: LogStreamServer registered with EventBus: ${EventBus.getDefault().isRegistered(this)}")
            MessageLog.i(TAG, "[INFO] Remote Log Viewer started at http://$ip:$port")
        } catch (e: Exception) {
            // Handle cases where the server fails to start.
            MessageLog.e(TAG, "[ERROR] start:: Failed to start Remote Log Viewer: ${e.message}")
            isRunning = false
            serverScope?.cancel()
            serverScope = null
            actionChannel?.close()
            actionChannel = null
        }
    }

    /** Stops the log streaming server, clears the buffer, and unregisters from EventBus. */
    fun stop() {
        if (!isRunning) return

        try {
            // Unregister to stop receiving log events.
            EventBus.getDefault().unregister(this)
        } catch (_: Exception) {
            // Ignore exceptions if the server was not registered.
        }

        // Shutdown the Ktor server instance with a brief grace period.
        server?.stop(500, 1000)
        server = null
        applicationContext = null

        // Cancel the coroutine scope to clean up background tasks.
        serverScope?.cancel()
        serverScope = null

        // Close the action channel.
        actionChannel?.close()
        actionChannel = null

        // Clear the message buffer to free up memory.
        clearBuffer()

        // Disconnect all tracked clients.
        clients.clear()

        isRunning = false
        Log.i(TAG, "[LogStreamServer] Log stream server stopped.")
    }
}
