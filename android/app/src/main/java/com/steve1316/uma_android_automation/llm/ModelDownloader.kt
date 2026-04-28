package com.steve1316.uma_android_automation.llm

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Fetches the generative model file (e.g. Qwen 2.5 1.5B Instruct `.gguf` ~1.1 GB) from a remote URL into
 * app-private storage using Android [DownloadManager], so the APK stays lean and the download shows up in the
 * system notification shade with cancel and pause support.
 *
 * Downloads land at [modelFile] under `context.getExternalFilesDir("llm")`, which is app-private - no storage
 * permission required. Delete via [delete] when the user wants to reclaim space.
 *
 * @property context Application context.
 */
class ModelDownloader(private val context: Context) {
    /** System [DownloadManager] handle used to enqueue, query, and cancel downloads. */
    private val dm: DownloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    companion object {
        /** Logger tag for this class. */
        private const val TAG = "${SharedData.loggerTag}ModelDownloader"

        /** Subdirectory of `getExternalFilesDir(...)` that holds downloaded `.gguf` model files. */
        private const val LLM_DIR = "llm"

        /** Subdirectory holding the on-demand MiniLM embedder (`minilm-l6-v2-int8.onnx`). Separate from [LLM_DIR] so [listModels] only enumerates GGUF chat models. */
        private const val EMBEDDER_DIR = "llm/embedder"

        /** Filename of the embedder ONNX, mirrored on the JS side via the search/UI constants. */
        private const val EMBEDDER_FILENAME = "minilm-l6-v2-int8.onnx"

        /** Interval between [DownloadManager] cursor polls while a download is in flight. */
        private const val POLL_INTERVAL_MS = 500L
    }

    /**
     * Base directory for `.gguf` model files.
     *
     * Uses app-private external storage (`getExternalFilesDir`) rather than `filesDir` because DownloadManager runs
     * in a separate system process and cannot write into `/data/data/<pkg>/` ("Unsupported path" error). Still
     * app-scoped - no storage permission and auto-deleted on uninstall - just a different filesystem.
     */
    private val baseDir: File by lazy {
        context.getExternalFilesDir(LLM_DIR) ?: File(context.filesDir, LLM_DIR).also { it.mkdirs() }
    }

    /** Directory holding the on-demand embedder ONNX. Mirrors [baseDir]'s fallback semantics for parity. */
    private val embedderDir: File by lazy {
        context.getExternalFilesDir(EMBEDDER_DIR) ?: File(context.filesDir, EMBEDDER_DIR).also { it.mkdirs() }
    }

    /**
     * Resolve the destination [File] for [filename] inside the model directory.
     *
     * @param filename Bare file name (no directory components).
     * @return Absolute [File] pointing at `baseDir/filename`; not guaranteed to exist.
     */
    fun fileFor(filename: String): File = File(baseDir, filename)

    /**
     * List every `.gguf` model file present on-device, most recently modified first.
     *
     * @return Non-null list of downloaded `.gguf` files; empty when none exist or [baseDir] is unreadable.
     */
    fun listModels(): List<File> =
        baseDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && f.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Return the preferred active model file. If [preferredFilename] matches a downloaded file, that one is used;
     * otherwise the most recently modified `.gguf` is returned so a fresh install-and-download flow still works
     * without an explicit selection step.
     *
     * @param preferredFilename Optional user-selected filename to prefer when present.
     * @return The matching or most recently downloaded model file, or null if none are present.
     */
    fun currentModelFile(preferredFilename: String? = null): File? {
        if (!preferredFilename.isNullOrBlank()) {
            val preferred = fileFor(preferredFilename)
            if (preferred.isFile && preferred.length() > 0) return preferred
        }
        return listModels().firstOrNull()
    }

    /**
     * Check whether any model is downloaded.
     *
     * @return true if at least one non-empty `.gguf` model file is present on-device.
     */
    fun isDownloaded(): Boolean = listModels().isNotEmpty()

    /**
     * One state emission from [download]. Consumers switch UI between indeterminate / progress / error / complete.
     *
     * @property bytesDownloaded Bytes received so far. Zero for [Failed] emissions.
     * @property bytesTotal Total expected bytes, or -1 when the server did not advertise Content-Length.
     * @property status One of the [DownloadManager.STATUS_*] constants. [Failed] remaps unknown codes to STATUS_FAILED.
     * @property failureReason DownloadManager reason code for [Failed] only; null otherwise.
     */
    sealed class State {
        object Pending : State()

        /**
         * Active download with byte-level progress.
         *
         * Emitted repeatedly by [download] as DownloadManager reports new totals; consumers should drive a
         * determinate progress bar from these fields.
         *
         * @property bytesDownloaded Bytes received so far.
         * @property bytesTotal Total expected bytes, or -1 when the server did not advertise Content-Length
         *   (in which case progress can only be shown as indeterminate).
         */
        data class Running(val bytesDownloaded: Long, val bytesTotal: Long) : State()

        /**
         * Download paused by DownloadManager (e.g. waiting for Wi-Fi, queued behind another download, or
         * device idle). Same byte semantics as [Running]; consumers typically render this identically with a
         * "paused" badge.
         *
         * @property bytesDownloaded Bytes received so far before the pause.
         * @property bytesTotal Total expected bytes, or -1 when unknown.
         */
        data class Paused(val bytesDownloaded: Long, val bytesTotal: Long) : State()

        /**
         * Terminal failure emission. Stops the [download] flow; no further [State]s follow.
         *
         * @property failureReason Raw [DownloadManager] reason code (`ERROR_*` / `PAUSED_*` constants).
         *   Unknown statuses from [query] collapse to [DownloadManager.ERROR_UNKNOWN] so callers always have
         *   a defined value to switch on.
         */
        data class Failed(val failureReason: Int) : State()

        object Complete : State()
    }

    /**
     * Start downloading [url] into [modelFile], replacing any existing file. Emits [State]s until the download
     * succeeds, fails, or is cancelled. Cancelling the consuming coroutine cancels the underlying DownloadManager
     * request.
     *
     * @param url HTTPS URL of the model file.
     * @param filename Bare destination filename inside [baseDir]; any existing file with this name is replaced.
     * @param authToken Optional Bearer token sent in the `Authorization` header for gated downloads.
     * @return Cold [Flow] that begins the download when collected.
     */
    fun download(url: String, filename: String, authToken: String? = null): Flow<State> =
        downloadTo(
            url = url,
            destination = fileFor(filename),
            title = "Uma Chat Model",
            description = "Downloading the on-device chatbot model.",
            authToken = authToken,
        )

    /**
     * Resolved destination file for the on-demand MiniLM embedder ONNX. Always returns the same path so
     * [downloadEmbedder], [isEmbedderDownloaded], and [deleteEmbedder] agree.
     */
    fun embedderFile(): File = File(embedderDir, EMBEDDER_FILENAME)

    /** True when the embedder ONNX is fully downloaded and non-empty. */
    fun isEmbedderDownloaded(): Boolean = embedderFile().let { it.isFile && it.length() > 0 }

    /**
     * Start downloading the MiniLM embedder ONNX from [url] into [embedderFile]. Same flow semantics as
     * [download]; the system notification is labeled distinctly so the user can tell the two downloads apart
     * if both run back-to-back.
     */
    fun downloadEmbedder(url: String): Flow<State> =
        downloadTo(
            url = url,
            destination = embedderFile(),
            title = "Ask the Docs Engine",
            description = "Downloading the on-device documentation chatbot's embedder.",
            authToken = null,
        )

    /** Remove the downloaded embedder ONNX, if present. */
    fun deleteEmbedder(): Boolean = embedderFile().let { if (it.isFile) it.delete() else false }

    /**
     * Generic [DownloadManager]-backed download into an explicit [destination]. Both the GGUF chat-model path
     * and the embedder path go through here.
     *
     * @param url HTTPS URL of the file to download.
     * @param destination Absolute target file; replaced if it already exists.
     * @param title Title shown in the system notification shade.
     * @param description Description shown beneath the title in the notification shade.
     * @param authToken Optional Bearer token sent in the `Authorization` header for gated downloads.
     */
    private fun downloadTo(
        url: String,
        destination: File,
        title: String,
        description: String,
        authToken: String? = null,
    ): Flow<State> =
        flow {
            destination.parentFile?.mkdirs()
            // Only replace this specific destination if it already exists; siblings stay on-device so other
            // downloads (e.g. additional GGUF variants) survive a single replace.
            if (destination.exists()) destination.delete()
            val request =
                DownloadManager.Request(Uri.parse(url))
                    .setTitle(title)
                    .setDescription(description)
                    .setDestinationUri(Uri.fromFile(destination))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(false)
            if (!authToken.isNullOrBlank()) request.addRequestHeader("Authorization", "Bearer ${authToken.trim()}")
            val id = dm.enqueue(request)
            Log.i(TAG, "download:: enqueued id=$id url=$url")
            emit(State.Pending)

            try {
                // DownloadManager has no push API for progress, so we poll its content cursor until the request reaches a terminal state.
                // A null snapshot means the row is already gone (e.g. the user cleared notifications mid-flight), which we surface as a generic failure.
                while (true) {
                    val snapshot = query(id)
                    if (snapshot == null) {
                        emit(State.Failed(DownloadManager.ERROR_UNKNOWN))
                        return@flow
                    }
                    emit(snapshot)
                    if (snapshot is State.Complete || snapshot is State.Failed) return@flow
                    delay(POLL_INTERVAL_MS)
                }
            } finally {
                // Leave the file in place on success; DownloadManager auto-cleans temp files on failure.
                // If the consumer cancels mid-flight (coroutine cancellation triggers this finally) the request is still active in DownloadManager.
                // Removing it cancels the underlying download and dismisses the system notification so the user doesn't see a stuck progress bar.
                val latest = query(id)
                if (latest !is State.Complete && latest !is State.Failed) dm.remove(id)
            }
        }

    /**
     * Remove every `.gguf` model file from disk.
     *
     * @return true if at least one file was deleted.
     */
    fun delete(): Boolean {
        val files = baseDir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) } ?: return false
        var any = false
        for (f in files) if (f.delete()) any = true
        return any
    }

    /**
     * Remove a specific model file from disk.
     *
     * @param filename Bare filename inside [baseDir].
     * @return true if the file existed and was deleted.
     */
    fun deleteByName(filename: String): Boolean {
        val f = fileFor(filename)
        return f.isFile && f.delete()
    }

    /**
     * Report the on-disk size of the preferred active model file.
     *
     * @param preferredFilename Optional user-selected filename to size; falls back to the most recent download.
     * @return Current size in bytes of the resolved model file, or 0 if none is present.
     */
    fun size(preferredFilename: String? = null): Long = currentModelFile(preferredFilename)?.length() ?: 0

    /**
     * Snapshot the current status of [DownloadManager] request [id] and translate it into a [State].
     *
     * @param id Request id returned from [DownloadManager.enqueue].
     * @return The current [State], or null if no row exists for [id] (e.g. it was already removed).
     */
    private fun query(id: Long): State? {
        val q = DownloadManager.Query().setFilterById(id)
        // `.use` closes the Cursor on every path - leaking it would tie up a SQLite connection per poll.
        dm.query(q).use { cursor: Cursor ->
            // No row means DownloadManager has dropped this id (e.g. user cleared the notification). Treat
            // that as a missing snapshot so the caller can surface a failure rather than spin forever.
            if (!cursor.moveToFirst()) return null

            val statusIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val soFarIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reasonIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val status = cursor.getInt(statusIdx)
            val soFar = cursor.getLong(soFarIdx)
            val total = cursor.getLong(totalIdx)

            // Translate DownloadManager's int status into our typed [State] hierarchy. Any unknown status
            // collapses to [Failed] with ERROR_UNKNOWN so we never emit an unhandled value to the bridge.
            return when (status) {
                DownloadManager.STATUS_PENDING -> State.Pending
                DownloadManager.STATUS_RUNNING -> State.Running(soFar, total)
                DownloadManager.STATUS_PAUSED -> State.Paused(soFar, total)
                DownloadManager.STATUS_SUCCESSFUL -> State.Complete
                DownloadManager.STATUS_FAILED -> State.Failed(cursor.getInt(reasonIdx))
                else -> State.Failed(DownloadManager.ERROR_UNKNOWN)
            }
        }
    }
}
