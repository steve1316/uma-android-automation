package com.steve1316.uma_android_automation.llm

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.steve1316.automation_library.data.SharedData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * React Native bridge for the on-device documentation chatbot's retrieval + model-file-management surface.
 *
 * After migrating the LLM to llama.rn (JS-side), this module's responsibility narrows to:
 * - Retrieval: [searchDocs] runs MiniLM embedding + cosine search over the bundled index and returns the top-k
 *   chunks with their full enclosing section text expanded.
 * - Model file management: download/cancel/delete/list/select GGUF model files. The actual inference happens
 *   JS-side in `src/lib/chat/llamaRunner.ts`.
 *
 * @property reactContext Injected by React Native's module loader.
 */
class LLMChatModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    /** Owned retrieval orchestrator; same lifetime as this module. */
    private val orchestrator = ChatOrchestrator(reactContext.applicationContext)

    /** Background scope for bridge methods that fan out into coroutines (search, downloads). */
    private val scope = CoroutineScope(Dispatchers.Default)

    /** In-flight download coroutine, if any. Volatile so [cancelDownload] sees the latest value cross-thread. */
    @Volatile private var downloadJob: Job? = null

    /** Optional Bearer token applied to the next [downloadModel] call. Volatile so updates are visible immediately. */
    @Volatile private var authToken: String? = null

    companion object {
        /** Logger tag for this class. */
        private const val TAG = "${SharedData.loggerTag}LLMChatModule"

        /** Name JS imports this module under via `NativeModules.LLMChatModule`. */
        private const val MODULE_NAME = "LLMChatModule"

        /** Event emitted per DownloadManager progress snapshot. Payload: `{ status, bytesDownloaded, bytesTotal }`. */
        const val EVENT_DOWNLOAD_STATE = "LLMChatModule.DownloadState"
    }

    /** @return The name JS uses to look up this module via `NativeModules.LLMChatModule`. */
    override fun getName(): String = MODULE_NAME

    /**
     * Required no-op for `NativeEventEmitter`; events are dispatched via `RCTDeviceEventEmitter`.
     *
     * @param eventName Ignored; React Native's emitter contract requires this signature.
     */
    @ReactMethod
    fun addListener(eventName: String) {}

    /**
     * Required no-op for `NativeEventEmitter`; events are dispatched via `RCTDeviceEventEmitter`.
     *
     * @param count Ignored; React Native's emitter contract requires this signature.
     */
    @ReactMethod
    fun removeListeners(count: Int) {}

    /**
     * Retrieve top-[k] doc chunks with their full enclosing section text expanded.
     *
     * @param query User-typed natural-language question.
     * @param k Maximum chunks to return.
     * @param promise Resolves with `[{ id, source, heading, text, score, expandedText }]`.
     */
    @ReactMethod
    fun searchDocs(query: String, k: Int, promise: Promise) {
        scope.launch {
            try {
                val results = orchestrator.searchDocs(query, k)
                val array = Arguments.createArray()
                for (r in results) {
                    val map = Arguments.createMap()
                    map.putString("id", r.chunk.id)
                    map.putString("source", r.chunk.source)
                    map.putString("heading", r.chunk.heading)
                    map.putString("text", r.chunk.text)
                    map.putDouble("score", r.score.toDouble())
                    map.putString("expandedText", r.expandedText)
                    map.putString("kind", if (r.chunk.kind == DocIndex.Kind.CODE) "code" else "doc")
                    array.pushMap(map)
                }
                promise.resolve(array)
            } catch (e: EmbedderNotInstalledException) {
                Log.w(TAG, "searchDocs:: embedder not installed")
                promise.reject("E_EMBEDDER_NOT_INSTALLED", e.message, e)
            } catch (e: Exception) {
                Log.e(TAG, "searchDocs:: failed: ${e.message}", e)
                promise.reject("E_SEARCH_FAILED", e.message, e)
            }
        }
    }

    /**
     * Start downloading the model. Progress emitted via [EVENT_DOWNLOAD_STATE].
     *
     * @param url HTTPS URL of the model file to download.
     * @param promise Resolves with null once the download has been enqueued, or rejects when one is already running.
     */
    @ReactMethod
    fun downloadModel(url: String, promise: Promise) {
        // Reject overlapping downloads up front - DownloadManager would happily run two but the JS UI assumes
        // a single in-flight job, and we'd risk losing the [downloadJob] reference for the older one.
        val existing = downloadJob
        if (existing != null && existing.isActive) {
            promise.reject("E_ALREADY_DOWNLOADING", "A model download is already in progress.")
            return
        }

        // Snapshot the auth token at launch time so a later [setAuthToken] call doesn't reach into this
        // already-running coroutine and change the auth header mid-download.
        val token = authToken
        val filename = filenameFromUrl(url)
        downloadJob =
            scope.launch {
                try {
                    // Forward each State emission to JS. Errors thrown out of the Flow (cancellation aside)
                    // are surfaced as a synthetic "error" event so the UI can recover instead of hanging.
                    orchestrator.modelDownloader().download(url, filename, token).collect { state ->
                        emitDownloadState(state)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "downloadModel:: failed: ${e.message}", e)
                    emitDownloadStateRaw("error", 0, 0, e.message)
                }
            }
        promise.resolve(null)
    }

    /**
     * Store an optional Bearer token applied to the next [downloadModel] call. Pass empty to clear.
     *
     * @param token Bearer token to apply, or an empty/blank string to clear any previously stored token.
     */
    @ReactMethod
    fun setAuthToken(token: String) {
        authToken = token.trim().ifEmpty { null }
    }

    /**
     * Cancel the in-progress download, if any.
     *
     * @param promise Resolves with null once cancellation has been requested.
     */
    @ReactMethod
    fun cancelDownload(promise: Promise) {
        downloadJob?.cancel()
        downloadJob = null
        promise.resolve(null)
    }

    /**
     * Delete every downloaded model from disk.
     *
     * @param promise Resolves with `true` if any file was deleted, `false` otherwise.
     */
    @ReactMethod
    fun deleteModel(promise: Promise) {
        val deleted = orchestrator.modelDownloader().delete()
        promise.resolve(deleted)
    }

    /**
     * Delete a specific downloaded model by filename.
     *
     * @param filename Bare filename of the model to delete.
     * @param promise Resolves with `true` if the file existed and was deleted, `false` otherwise.
     */
    @ReactMethod
    fun deleteModelFile(filename: String, promise: Promise) {
        val deleted = orchestrator.modelDownloader().deleteByName(filename)
        promise.resolve(deleted)
    }

    /**
     * List every model downloaded on-device.
     *
     * @param promise Resolves with `[{ filename, path, sizeBytes, lastModifiedMillis }]` for every downloaded model.
     */
    @ReactMethod
    fun listModels(promise: Promise) {
        try {
            val models = orchestrator.modelDownloader().listModels()
            val array = Arguments.createArray()
            for (f in models) {
                val map = Arguments.createMap()
                map.putString("filename", f.name)
                map.putString("path", f.absolutePath)
                map.putDouble("sizeBytes", f.length().toDouble())
                map.putDouble("lastModifiedMillis", f.lastModified().toDouble())
                array.pushMap(map)
            }
            promise.resolve(array)
        } catch (e: Exception) {
            Log.e(TAG, "listModels:: failed: ${e.message}", e)
            promise.reject("E_LIST_FAILED", e.message, e)
        }
    }

    /**
     * Select which downloaded model the bridge surfaces as "active". Empty string clears the selection.
     *
     * @param filename Bare filename of the desired active model, or an empty/blank string to clear the selection.
     */
    @ReactMethod
    fun setActiveModel(filename: String) {
        orchestrator.activeModelFilename = filename.trim().ifEmpty { null }
    }

    /**
     * Snapshot the device's runtime capabilities relevant to picking and running a chat model.
     *
     * Drives the diagnostic + preset-recommendation row on the LLM Settings page; called once on focus, not in
     * a hot path, so the `/proc/cpuinfo` parse is acceptable.
     *
     * @param promise Resolves with `{ totalRamBytes, availRamBytes, cpuFeatures: string[], abi: string }`.
     */
    @ReactMethod
    fun getDeviceCapabilities(promise: Promise) {
        try {
            val activityManager = reactContext.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(mem)
            val features = parseCpuFeatures()
            val map: WritableMap = Arguments.createMap()
            map.putDouble("totalRamBytes", mem.totalMem.toDouble())
            map.putDouble("availRamBytes", mem.availMem.toDouble())
            val featureArr = Arguments.createArray()
            for (f in features) featureArr.pushString(f)
            map.putArray("cpuFeatures", featureArr)
            map.putString("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            promise.resolve(map)
        } catch (e: Exception) {
            Log.e(TAG, "getDeviceCapabilities:: failed: ${e.message}", e)
            promise.reject("E_DEVICE_CAPS", e.message, e)
        }
    }

    /**
     * Resolve with `true` when the embedder ONNX is downloaded and ready for use, otherwise `false`. Synchronous
     * existence + size check; the JS side calls this on Chat/LLM Settings page focus to decide which UI to render.
     *
     * @param promise Resolves with a [Boolean] indicating whether the embedder ONNX is on-disk and non-empty.
     */
    @ReactMethod
    fun isEmbedderReady(promise: Promise) {
        promise.resolve(orchestrator.modelDownloader().isEmbedderDownloaded())
    }

    /**
     * Start downloading the MiniLM embedder ONNX from [url] and verify the resulting bytes match [expectedSha256].
     * Progress is streamed through the same [EVENT_DOWNLOAD_STATE] channel as [downloadModel], discriminated by
     * the `kind: "embedder"` field on each event payload. On hash mismatch the partial file is deleted and a
     * `failed` event with `error="sha256-mismatch"` is emitted so the UI can prompt the user to retry.
     *
     * @param url HTTPS URL of the ONNX model file (HuggingFace mirror).
     * @param expectedSha256 Lowercase hex SHA-256 the downloaded bytes must match.
     * @param promise Resolves once the download is enqueued, rejects when one is already running.
     */
    @ReactMethod
    fun downloadEmbedder(url: String, expectedSha256: String, promise: Promise) {
        val existing = downloadJob
        if (existing != null && existing.isActive) {
            promise.reject("E_ALREADY_DOWNLOADING", "A download is already in progress.")
            return
        }
        downloadJob =
            scope.launch {
                try {
                    orchestrator.modelDownloader().downloadEmbedder(url).collect { state ->
                        if (state is ModelDownloader.State.Complete) {
                            val file = orchestrator.modelDownloader().embedderFile()
                            val actual = sha256Hex(file)
                            if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                                Log.e(TAG, "downloadEmbedder:: sha mismatch expected=$expectedSha256 actual=$actual")
                                file.delete()
                                emitDownloadStateRaw("failed", 0, 0, "sha256-mismatch", "embedder")
                                return@collect
                            }
                            // Drop any cached embedder so the next chat call reloads against the newly downloaded ONNX.
                            orchestrator.resetEmbedder()
                            emitDownloadState(state, "embedder")
                        } else {
                            emitDownloadState(state, "embedder")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "downloadEmbedder:: failed: ${e.message}", e)
                    emitDownloadStateRaw("error", 0, 0, e.message, "embedder")
                }
            }
        promise.resolve(null)
    }

    /**
     * Delete the on-disk embedder ONNX and drop the cached [EmbeddingService] so the next chat call cleanly
     * surfaces "not installed" again.
     *
     * @param promise Resolves with `true` when a file was removed, `false` otherwise.
     */
    @ReactMethod
    fun deleteEmbedder(promise: Promise) {
        val deleted = orchestrator.modelDownloader().deleteEmbedder()
        orchestrator.resetEmbedder()
        promise.resolve(deleted)
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Hex-encoded SHA-256 of [file]. Streams through a 64 KB buffer so a 22 MB ONNX doesn't materialize twice in
     * the heap during verification.
     *
     * @param file File whose contents are hashed.
     * @return Lowercase hex SHA-256 digest of [file]'s bytes.
     */
    private fun sha256Hex(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        val sb = StringBuilder(md.digestLength * 2)
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * Derive a local filename from the model URL's last path segment, falling back to a generic name when the URL
     * is unparseable or doesn't end in a recognized model extension.
     *
     * @param url Source URL of the model download.
     * @return The chosen local filename ending in `.gguf` or `.task`.
     */
    private fun filenameFromUrl(url: String): String {
        // Strip query string and fragment first so something like `model.gguf?token=...` still parses to `model.gguf` rather than the raw URL tail.
        val noQuery = url.substringBefore('?').substringBefore('#')
        val last = noQuery.substringAfterLast('/', missingDelimiterValue = "").trim()

        // Only accept the URL's basename when it actually looks like a model file. This guards against odd
        // redirect tails that would otherwise persist as garbage filenames on disk.
        val isModel = last.endsWith(".gguf", ignoreCase = true) || last.endsWith(".task", ignoreCase = true)

        return if (last.isNotEmpty() && isModel) last else "chat-model.gguf"
    }

    /**
     * Parse `/proc/cpuinfo` for the ARM `Features:` line and return its tokens. Used by the LLM Settings page
     * to decide which CPU-feature variant of llama.rn would have loaded (and to show the user their tier).
     *
     * Returns an empty list on any parse failure so the UI can fall back to a generic "v8 baseline" label
     * rather than misreport features.
     */
    private fun parseCpuFeatures(): List<String> {
        return try {
            val cpuinfo = java.io.File("/proc/cpuinfo")
            if (!cpuinfo.canRead()) return emptyList()
            cpuinfo.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("Features")) {
                        return@useLines line.substringAfter(':').trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    }
                }
                emptyList<String>()
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseCpuFeatures:: ${e.message}")
            emptyList()
        }
    }

    /**
     * Map a [ModelDownloader.State] into an [EVENT_DOWNLOAD_STATE] payload and forward it to JS.
     *
     * @param state The latest snapshot from the download flow.
     * @param kind Discriminator routed to the JS listener so chat-model and embedder downloads can share the
     *   same event channel without colliding; defaults to `chat` for backward compatibility.
     */
    private fun emitDownloadState(state: ModelDownloader.State, kind: String = "chat") {
        when (state) {
            is ModelDownloader.State.Pending -> emitDownloadStateRaw("pending", 0, 0, null, kind)
            is ModelDownloader.State.Running -> emitDownloadStateRaw("running", state.bytesDownloaded, state.bytesTotal, null, kind)
            is ModelDownloader.State.Paused -> emitDownloadStateRaw("paused", state.bytesDownloaded, state.bytesTotal, null, kind)
            is ModelDownloader.State.Complete -> emitDownloadStateRaw("complete", 0, 0, null, kind)
            is ModelDownloader.State.Failed -> emitDownloadStateRaw("failed", 0, 0, "reason=${state.failureReason}", kind)
        }
    }

    /**
     * Emit a single [EVENT_DOWNLOAD_STATE] event with the supplied fields.
     *
     * @param status One of `pending`, `running`, `paused`, `complete`, `failed`, `error`.
     * @param soFar Bytes downloaded so far.
     * @param total Total expected bytes, or 0 when unknown.
     * @param error Optional error description; included in the payload when non-null.
     * @param kind Discriminator written into the payload so JS listeners can route chat-model vs. embedder
     *   download events; defaults to `chat`.
     */
    private fun emitDownloadStateRaw(status: String, soFar: Long, total: Long, error: String?, kind: String = "chat") {
        val map: WritableMap = Arguments.createMap()
        map.putString("kind", kind)
        map.putString("status", status)
        map.putDouble("bytesDownloaded", soFar.toDouble())
        map.putDouble("bytesTotal", total.toDouble())
        if (error != null) map.putString("error", error)
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(EVENT_DOWNLOAD_STATE, map)
    }
}
