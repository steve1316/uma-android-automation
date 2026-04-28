package com.steve1316.uma_android_automation.llm

import android.content.Context
import android.util.Log
import com.steve1316.automation_library.data.SharedData

/**
 * Retrieval-only coordinator for the on-device documentation chatbot.
 *
 * After the migration from MediaPipe to llama.rn (which runs JS-side), the orchestrator's responsibility shrinks
 * to: load the embedder + bundled doc index, embed queries, return top-k chunks with their full enclosing section
 * text expanded. Generation, prompt building, sampling parameters, and grounding verification all live JS-side
 * now in `src/lib/chat/llamaRunner.ts` and `src/lib/chat/groundingVerifier.ts`.
 *
 * @property context Application context for asset access and DownloadManager.
 */
class ChatOrchestrator(private val context: Context) {
    /** Lazy-initialized MiniLM embedder. Volatile so the double-checked locking in [ensureEmbedder] is safe. */
    @Volatile private var embedder: EmbeddingService? = null

    /** Lazy-loaded bundled doc index. Volatile so the double-checked locking in [ensureIndex] is safe. */
    @Volatile private var index: DocIndex? = null

    /** Owned model-file downloader; same lifetime as this orchestrator and exposed via [modelDownloader]. */
    private val downloader = ModelDownloader(context)

    /** User-chosen active model filename. Persisted JS-side; pushed in via the bridge so future code that needs to
     *  surface "which file is active" (e.g. download UI) can ask the orchestrator. */
    @Volatile var activeModelFilename: String? = null

    companion object {
        /** Logger tag for this class. */
        private const val TAG = "${SharedData.loggerTag}ChatOrchestrator"

        /** Asset path of the build-time-generated doc index. */
        private const val INDEX_PATH = "llm/doc_index.bin"

        /** Default top-k passed to [DocIndex.search] when [searchDocs] is called without an explicit `k`. */
        private const val MAX_CONTEXT_CHUNKS = 4

        /** Hard cap on per-citation expanded text returned by [searchDocs]. JS-side trims further per the user's
         *  Generation Tuning slider; this number is just a safety upper bound. */
        const val EXPANSION_CHAR_CAP = 4000

        /** Must match CHUNK_OVERLAP_TOKENS in scripts/build-doc-index.ts. */
        private const val CHUNK_OVERLAP_WORDS = 40
    }

    /**
     * Single retrieval result fed to JS.
     *
     * @property chunk The matched doc/code chunk.
     * @property score Cosine similarity between [chunk]'s embedding and the query embedding.
     * @property expandedText Section text reassembled around [chunk] by [expandSection], capped at [EXPANSION_CHAR_CAP].
     */
    data class Result(val chunk: DocIndex.Chunk, val score: Float, val expandedText: String)

    /**
     * Embed [query], cosine-search the index, and return the top-[k] chunks with their full enclosing section text
     * expanded (capped at [EXPANSION_CHAR_CAP]).
     *
     * @param query Natural-language user question.
     * @param k Maximum number of results to return.
     * @return Top-k results sorted by descending score, or an empty list on init failure.
     */
    fun searchDocs(query: String, k: Int = MAX_CONTEXT_CHUNKS): List<Result> {
        val emb = ensureEmbedder() ?: return emptyList()
        val idx = ensureIndex() ?: return emptyList()
        val vector = emb.embed(query) ?: return emptyList()
        return idx.search(vector, k).map { Result(it.chunk, it.score, expandSection(it.chunk)) }
    }

    /**
     * Downloader instance exposed to the bridge for model file management.
     *
     * @return The owned [ModelDownloader] tied to this orchestrator's lifetime.
     */
    fun modelDownloader(): ModelDownloader = downloader

    /** Release any held native resources. */
    fun close() {
        embedder?.close()
        embedder = null
        index = null
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Reassemble the full section text around [top]. See the unmigrated docstring for the algorithm; in short, this
     * gathers every chunk sharing [top]'s heading prefix, groups by the indexer's flush group, drops the 40-word
     * overlap from each non-first chunk, and joins. Returns [top]'s own text when no siblings exist.
     *
     * @param top The matched chunk whose enclosing section should be reconstructed.
     * @return The reassembled section text, capped at [EXPANSION_CHAR_CAP] characters.
     */
    private fun expandSection(top: DocIndex.Chunk): String {
        // Code chunks are already self-contained at function/class granularity - no surrounding-section
        // reassembly to do. Return the chunk text verbatim (it already includes the leading KDoc).
        if (top.kind == DocIndex.Kind.CODE) return top.text

        val idx = index ?: return top.text
        val prefix = top.heading

        // Pull every doc chunk under [top]'s heading scope: same heading, or a deeper subsection (heading prefix followed by the breadcrumb separator).
        // Subsections live under their parent so the expanded section captures the whole hierarchy the user is most likely asking about.
        val matches =
            idx.chunks.filter { c ->
                c.kind == DocIndex.Kind.DOC &&
                    c.source == top.source &&
                    (c.heading == prefix || c.heading.startsWith("$prefix › "))
            }
        if (matches.size <= 1) return top.text

        // Chunk ids look like `source.md#heading-{flush}-{seq}`. Group by the `flush` integer so chunks emitted
        // together by the indexer's flush boundary stay together (each flush group represents one continuous
        // run of prose that was sliced by the chunker). Sorting the groups by flush index puts them back in the
        // order they appeared in the source file.
        val byFlush =
            matches.groupBy { c ->
                c.id.substringAfterLast('#').substringBefore('-').toIntOrNull() ?: 0
            }.toSortedMap()

        // Within a flush group, sort by the trailing `seq` index so consecutive sliced chunks reassemble in
        // order. The first chunk of each group keeps its full text; every subsequent chunk drops the indexer's
        // CHUNK_OVERLAP_WORDS-word prefix so the overlap doesn't get duplicated in the output.
        val parts =
            byFlush.values.map { group ->
                val sorted =
                    group.sortedBy { c ->
                        c.id.substringAfterLast('#').substringAfter('-').toIntOrNull() ?: 0
                    }
                sorted.withIndex().joinToString("\n") { (i, c) ->
                    if (i == 0) c.text else dropLeadingWords(c.text, CHUNK_OVERLAP_WORDS)
                }.trim()
            }.filter { it.isNotEmpty() }

        // Hard char cap so a runaway heading scope doesn't blow up the JS-side prompt budget. Truncate at the
        // last whole word before the cap to avoid mid-word cuts that would confuse the LLM.
        val combined = parts.joinToString("\n\n")
        return if (combined.length <= EXPANSION_CHAR_CAP) {
            combined
        } else {
            combined.take(EXPANSION_CHAR_CAP).substringBeforeLast(' ') + "..."
        }
    }

    /**
     * Strip the first [n] whitespace-delimited words from [text]. Used by [expandSection] to remove the indexer's
     * fixed-size word overlap from each non-first chunk in a flush group.
     *
     * @param text Source text.
     * @param n Word count to drop from the front.
     * @return [text] with the leading [n] words and any trailing whitespace removed.
     */
    private fun dropLeadingWords(text: String, n: Int): String {
        var consumed = 0
        var i = 0
        val len = text.length

        // Skip any leading whitespace so the first character processed is part of a real word.
        while (i < len && text[i].isWhitespace()) i++

        // Walk one word at a time: advance past the word's non-whitespace run, then past the whitespace that follows it.
        // After [n] iterations [i] points at the first character of the (n+1)th word (or end of text).
        while (consumed < n && i < len) {
            while (i < len && !text[i].isWhitespace()) i++
            consumed += 1
            while (i < len && text[i].isWhitespace()) i++
        }

        return text.substring(i)
    }

    /**
     * Lazily create the [EmbeddingService] on first use, with double-checked locking so concurrent calls share one
     * instance. Subsequent calls return the cached service.
     *
     * @return The shared embedder, or null if construction failed.
     */
    private fun ensureEmbedder(): EmbeddingService? {
        // Fast path: already-initialized reads bypass the lock entirely. Safe because [embedder] is @Volatile.
        embedder?.let { return it }
        synchronized(this) {
            // Re-check inside the lock so a thread that lost the race doesn't create a second instance.
            embedder?.let { return it }
            // Construction throws [EmbedderNotInstalledException] when the user hasn't downloaded the ONNX yet;
            // let it propagate so the bridge can translate it into a typed JS rejection rather than the silent
            // null path used for generic failures.
            val created = EmbeddingService(context, downloader.embedderFile())
            embedder = created
            return created
        }
    }

    /**
     * Drop the cached embedder so the next call to [ensureEmbedder] reloads the ONNX from disk. Used by the
     * bridge after [ModelDownloader.downloadEmbedder] completes (or after [ModelDownloader.deleteEmbedder] runs)
     * so the in-memory state matches the on-disk state without requiring an app restart.
     */
    fun resetEmbedder() {
        synchronized(this) {
            embedder?.close()
            embedder = null
        }
    }

    /**
     * Lazily load the bundled doc index from assets on first use, with double-checked locking. Subsequent calls
     * return the cached index. Errors are logged and the call returns null so retrieval degrades gracefully.
     *
     * @return The shared in-memory index, or null if asset loading failed.
     */
    private fun ensureIndex(): DocIndex? {
        // Fast path: already-loaded reads bypass the lock entirely. Safe because [index] is @Volatile.
        index?.let { return it }
        synchronized(this) {
            // Re-check inside the lock so a thread that lost the race doesn't reparse the asset.
            index?.let { return it }
            try {
                context.assets.open(INDEX_PATH).use { stream ->
                    val loaded = DocIndex.load(stream)
                    Log.i(TAG, "ensureIndex:: loaded ${loaded.chunks.size} chunks, dim=${loaded.dim}")
                    index = loaded
                    return loaded
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureIndex:: failed to load $INDEX_PATH: ${e.message}", e)
                return null
            }
        }
    }
}
