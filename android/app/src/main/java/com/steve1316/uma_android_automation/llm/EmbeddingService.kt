package com.steve1316.uma_android_automation.llm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Thrown when callers attempt to use the embedder before its ONNX file has been downloaded. The bridge translates
 * this into a typed JS error so the Ask the Docs UI can surface a "download the engine" CTA instead of a generic
 * "search failed" message.
 */
class EmbedderNotInstalledException : RuntimeException("Embedder ONNX is not installed; download it from LLM Settings first.")

/**
 * Produces fixed-size sentence embeddings from text using MiniLM-L6-v2 (int8 quantized) via ONNX Runtime.
 *
 * Embeddings are 384-dimensional, mean-pooled over non-PAD token positions and L2-normalized - matching the reference
 * output of the `sentence-transformers/all-MiniLM-L6-v2` Python model so vectors produced here are directly comparable
 * via cosine similarity with vectors produced by the build-time indexer.
 *
 * Reuses the existing [ai.onnxruntime] dependency already bundled for YOLOv8 detection.
 *
 * @property context The application context for loading the bundled vocab from assets.
 * @property modelFile External-storage path to the downloaded MiniLM ONNX. Constructed by the orchestrator from
 *   `ModelDownloader.embedderFile()` so the location stays in lockstep with the downloader. The constructor
 *   throws [EmbedderNotInstalledException] when the file is missing - callers must download via
 *   `LLMChatModule.downloadEmbedder` before instantiating this class.
 */
class EmbeddingService(private val context: Context, private val modelFile: File) {
    /** Process-wide ONNX Runtime environment; cheap singleton handle reused across sessions. */
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** Loaded MiniLM ONNX session; null until [load] succeeds and after [close]. */
    private var session: OrtSession? = null

    /** WordPiece tokenizer paired with the MiniLM vocab; initialized inside [load]. */
    private lateinit var tokenizer: WordPieceTokenizer

    companion object {
        /** Logger tag for this class. */
        private const val TAG = "${SharedData.loggerTag}EmbeddingService"

        /** Asset path of the BERT-style vocab.txt; still bundled because it is small and tied to the embedder version. */
        private const val VOCAB_PATH = "llm/minilm-l6-v2-vocab.txt"

        /** Output embedding dimensionality - fixed by the MiniLM-L6-v2 architecture. */
        const val EMBEDDING_DIM = 384
    }

    init {
        load()
    }

    /**
     * Embed [text] into a length-[EMBEDDING_DIM] unit vector.
     *
     * @param text The query or document chunk to embed.
     * @return A normalized float array of length [EMBEDDING_DIM], or null if the model is not loaded.
     */
    fun embed(text: String): FloatArray? {
        val s =
            session ?: run {
                Log.w(TAG, "embed:: session not initialized; returning null")
                return null
            }
        val encoded = tokenizer.encode(text)
        // Shape `[batch=1, seq_len]` matches the BERT-family ONNX input contract for all three input tensors.
        val shape = longArrayOf(1, encoded.seqLen.toLong())

        // Each OnnxTensor wraps off-heap native memory; nesting `.use { }` guarantees every tensor is closed
        // even if the model run throws, so we don't leak native buffers across embed() calls.
        // The inner `result.use { }` does the same for the output tensors returned by ORT.
        OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(encoded.inputIds), shape).use { ids ->
            OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(encoded.attentionMask), shape).use { mask ->
                OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(encoded.tokenTypeIds), shape).use { types ->
                    val inputs =
                        mapOf(
                            "input_ids" to ids,
                            "attention_mask" to mask,
                            "token_type_ids" to types,
                        )
                    s.run(inputs).use { result ->
                        // ORT returns the first output as `[batch][seq_len][embedding_dim]`. Batch is always 1
                        // here, so [hidden[0]] is the per-token hidden-state matrix that pooling consumes.
                        @Suppress("UNCHECKED_CAST")
                        val hidden = result[0].value as Array<Array<FloatArray>>
                        return meanPoolAndNormalize(hidden[0], encoded.attentionMask)
                    }
                }
            }
        }
    }

    /**
     * Mean-pool the token embeddings over positions where attention_mask == 1, then L2-normalize.
     *
     * @param hidden `[seq_len][embedding_dim]` matrix of per-token hidden states.
     * @param mask Attention mask aligned to [hidden]'s first dimension.
     * @return Pooled and L2-normalized embedding of length [EMBEDDING_DIM].
     */
    private fun meanPoolAndNormalize(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val pooled = FloatArray(EMBEDDING_DIM)
        var count = 0

        // Sum hidden states only at positions where attention_mask == 1. PAD positions carry no meaningful signal and including them would skew the average toward zero.
        for (t in hidden.indices) {
            if (mask[t] == 0L) continue
            val row = hidden[t]
            for (d in 0 until EMBEDDING_DIM) pooled[d] += row[d]
            count += 1
        }

        // Divide by the actual number of attended tokens (not seq_len) to get the true mean. Guarded against the all-PAD edge case so we never divide by zero.
        if (count > 0) for (d in 0 until EMBEDDING_DIM) pooled[d] /= count.toFloat()

        // L2-normalize so cosine similarity reduces to a plain dot product downstream in DocIndex.search().
        // Matches the reference sentence-transformers post-processing exactly.
        var norm = 0f
        for (d in 0 until EMBEDDING_DIM) norm += pooled[d] * pooled[d]
        norm = sqrt(norm)
        if (norm > 0f) for (d in 0 until EMBEDDING_DIM) pooled[d] /= norm
        return pooled
    }

    /**
     * Load the ONNX model bytes from external storage and the BERT vocab from assets, then create the [session]
     * and [tokenizer]. Throws [EmbedderNotInstalledException] when [modelFile] is missing so callers can route the
     * user to the LLM Settings download CTA; other errors are logged so [embed] can short-circuit gracefully
     * rather than crashing the host process.
     */
    private fun load() {
        if (!modelFile.isFile || modelFile.length() == 0L) throw EmbedderNotInstalledException()
        try {
            val modelBytes = modelFile.readBytes()
            session = ortEnv.createSession(modelBytes)
            tokenizer = context.assets.open(VOCAB_PATH).use { WordPieceTokenizer.fromVocabStream(it) }
            Log.i(TAG, "load:: MiniLM model and vocab loaded (${modelBytes.size / 1024} KB model from ${modelFile.absolutePath})")
        } catch (e: EmbedderNotInstalledException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "load:: failed to initialize: ${e.message}", e)
        }
    }

    /** Release the ONNX session. Call from the owning component's teardown. */
    fun close() {
        session?.close()
        session = null
    }
}
