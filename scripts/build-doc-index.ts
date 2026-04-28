/**
 * Build-time documentation indexer for the on-device chatbot.
 *
 * Reads README.md, HOW_IT_WORKS.md, and src/data/searchConfig.ts; chunks them into ~200-token pieces with heading
 * context preserved; embeds each chunk with the same MiniLM-L6-v2 int8 ONNX model shipped in the APK via
 * onnxruntime-node; writes a packed binary index to android/app/src/main/assets/llm/doc_index.bin in the format
 * consumed by DocIndex.kt.
 *
 * Run with: `yarn tsx scripts/build-doc-index.ts`
 */
import * as crypto from "node:crypto"
import * as fs from "node:fs"
import * as path from "node:path"
import * as ort from "onnxruntime-node"
import { chunkKotlinFile, findKotlinFiles } from "./lib/kotlinChunker"

const REPO_ROOT = path.resolve(__dirname, "..")
const ASSET_DIR = path.join(REPO_ROOT, "android/app/src/main/assets/llm")
const CACHE_DIR = path.join(REPO_ROOT, ".cache/embedder")
const KOTLIN_SRC_DIR = path.join(REPO_ROOT, "android/app/src/main/java/com/steve1316/uma_android_automation")
const VOCAB_PATH = path.join(ASSET_DIR, "minilm-l6-v2-vocab.txt")
const OUTPUT_PATH = path.join(ASSET_DIR, "doc_index.bin")
const HASH_PATH = path.join(ASSET_DIR, "doc_index.sources.sha256")

/**
 * Source-of-truth pair for the MiniLM-L6-v2 int8 ONNX. Mirrors `EMBEDDER_URL` / `EMBEDDER_SHA256` in
 * `src/lib/chat/embedder.ts`; both ends MUST stay in lockstep so the runtime ONNX users download is
 * byte-for-byte identical to the one used here when generating `doc_index.bin`.
 */
const EMBEDDER_URL = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx"
const EMBEDDER_SHA256 = "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1"
const MODEL_PATH = path.join(CACHE_DIR, "minilm-l6-v2-int8.onnx")

const MAGIC = "UMADOCIX"
const VERSION = 2
const KIND_DOC = 0x01
const KIND_CODE = 0x02
const EMBEDDING_DIM = 384
const MAX_SEQ_LEN = 128
const TARGET_CHUNK_TOKENS = 200
const CHUNK_OVERLAP_TOKENS = 40

interface Chunk {
    id: string
    source: string
    heading: string
    text: string
    kind: "doc" | "code"
}

// ----------------------------------------------------------------------------
// WordPiece tokenizer (mirrors WordPieceTokenizer.kt). Kept in lockstep so JS-side
// tokens match Kotlin's exactly; any divergence produces vectors the device cannot
// cosine-compare against.
// ----------------------------------------------------------------------------

class WordPieceTokenizer {
    static readonly CLS_ID = 101
    static readonly SEP_ID = 102
    static readonly PAD_ID = 0
    static readonly UNK_ID = 100
    static readonly MAX_INPUT_CHARS_PER_WORD = 100

    constructor(private readonly vocab: Map<string, number>) {}

    /**
     * Build a tokenizer from a BERT-style `vocab.txt` (one token per line, line number = token id).
     *
     * @param filePath Absolute or relative path to the vocab file.
     * @returns A [WordPieceTokenizer] populated with every token from the file.
     */
    static fromVocabFile(filePath: string): WordPieceTokenizer {
        const lines = fs.readFileSync(filePath, "utf8").split(/\r?\n/)
        const vocab = new Map<string, number>()
        for (let i = 0; i < lines.length; i++) {
            const token = lines[i].trim()
            if (token.length > 0 || i === 0) vocab.set(token, i)
        }
        return new WordPieceTokenizer(vocab)
    }

    /**
     * Encode [text] into the fixed-length input tensors expected by MiniLM-L6-v2: `[CLS]` + WordPiece ids +
     * `[SEP]`, padded with `[PAD]` to [maxLen]. Tokens past [maxLen] - 2 are truncated.
     *
     * @param text Input string to tokenize and encode.
     * @param maxLen Target sequence length (default `MAX_SEQ_LEN`); output arrays are exactly this long.
     * @returns Object with three `BigInt64Array(maxLen)` tensors: `inputIds`, `attentionMask`, `tokenTypeIds`.
     */
    encode(text: string, maxLen: number = MAX_SEQ_LEN): { inputIds: BigInt64Array; attentionMask: BigInt64Array; tokenTypeIds: BigInt64Array } {
        const ids: number[] = [WordPieceTokenizer.CLS_ID]
        const pieces = this.tokenize(text)
        for (const piece of pieces) {
            if (ids.length >= maxLen - 1) break
            ids.push(this.vocab.get(piece) ?? WordPieceTokenizer.UNK_ID)
        }
        ids.push(WordPieceTokenizer.SEP_ID)
        const inputIds = new BigInt64Array(maxLen)
        const attentionMask = new BigInt64Array(maxLen)
        const tokenTypeIds = new BigInt64Array(maxLen)
        for (let i = 0; i < maxLen; i++) {
            if (i < ids.length) {
                inputIds[i] = BigInt(ids[i])
                attentionMask[i] = 1n
            } else {
                inputIds[i] = BigInt(WordPieceTokenizer.PAD_ID)
                attentionMask[i] = 0n
            }
            tokenTypeIds[i] = 0n
        }
        return { inputIds, attentionMask, tokenTypeIds }
    }

    /**
     * Run the full BERT tokenization pipeline: lowercase + strip accents + split on whitespace/punctuation,
     * then greedy WordPiece subword segmentation against the loaded vocab.
     *
     * @param text Raw input string.
     * @returns Ordered list of WordPiece tokens; unknown words collapse to `[UNK]`.
     */
    tokenize(text: string): string[] {
        const basic = this.basicTokenize(text)
        const out: string[] = []
        for (const w of basic) out.push(...this.wordPieceTokenize(w))
        return out
    }

    /**
     * Lowercase, strip accents, then split [text] on whitespace and punctuation. Punctuation characters
     * become standalone tokens so WordPiece can map them independently.
     *
     * @param text Raw input string.
     * @returns Whitespace/punctuation-separated tokens, all lowercase and accent-free.
     */
    private basicTokenize(text: string): string[] {
        const normalized = this.stripAccents(text.toLowerCase())
        const tokens: string[] = []
        let current = ""
        for (const ch of normalized) {
            if (/\s/.test(ch)) {
                if (current) {
                    tokens.push(current)
                    current = ""
                }
            } else if (this.isPunctuation(ch)) {
                if (current) {
                    tokens.push(current)
                    current = ""
                }
                tokens.push(ch)
            } else {
                current += ch
            }
        }
        if (current) tokens.push(current)
        return tokens
    }

    /**
     * Greedy longest-match subword segmentation of [word] against the loaded vocab. Continuation pieces are
     * prefixed with `##` per the BERT convention.
     *
     * @param word Single basic-tokenized word (no whitespace, may include punctuation).
     * @returns Ordered subword pieces, or `["[UNK]"]` when the word can't be segmented or is too long.
     */
    private wordPieceTokenize(word: string): string[] {
        if (word.length > WordPieceTokenizer.MAX_INPUT_CHARS_PER_WORD) return ["[UNK]"]
        const pieces: string[] = []
        let start = 0
        while (start < word.length) {
            let end = word.length
            let matched: string | null = null
            while (start < end) {
                const sub = (start > 0 ? "##" : "") + word.slice(start, end)
                if (this.vocab.has(sub)) {
                    matched = sub
                    break
                }
                end -= 1
            }
            if (matched === null) return ["[UNK]"]
            pieces.push(matched)
            start = end
        }
        return pieces
    }

    /**
     * Remove combining diacritical marks via NFD normalization so accented characters match their base form
     * in the vocab.
     *
     * @param text Input string (typically already lowercased).
     * @returns [text] with all Unicode mark characters stripped.
     */
    private stripAccents(text: string): string {
        return text.normalize("NFD").replace(/\p{M}/gu, "")
    }

    /**
     * Check whether [ch] is a BERT-style punctuation character (ASCII punctuation ranges plus any Unicode
     * `\p{P}` codepoint).
     *
     * @param ch A single character (codepoint).
     * @returns `true` when [ch] should be treated as punctuation by [basicTokenize].
     */
    private isPunctuation(ch: string): boolean {
        const cp = ch.codePointAt(0)!
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) return true
        return /\p{P}/u.test(ch)
    }
}

// ----------------------------------------------------------------------------
// Chunkers
// ----------------------------------------------------------------------------

/**
 * Heading-aware markdown chunker. Preserves the nearest heading with each chunk and targets ~200 "tokens"
 * (approximated as whitespace-separated words, which tracks WordPiece ids closely enough for chunk sizing).
 *
 * @param markdown Raw markdown source to chunk.
 * @param source Logical source name written into each chunk's `source` field and used as the id prefix.
 * @returns One [Chunk] per heading-bounded body, sliced into ~200-word windows with `CHUNK_OVERLAP_TOKENS`
 *   carry-over so neighboring chunks share enough context for retrieval recall.
 */
function chunkMarkdown(markdown: string, source: string): Chunk[] {
    const chunks: Chunk[] = []
    const lines = markdown.split(/\r?\n/)
    let headingStack: string[] = []
    let bodyLines: string[] = []
    let flushIdx = 0

    const flush = () => {
        if (bodyLines.length === 0) return
        const text = bodyLines.join("\n").trim()
        if (text.length === 0) {
            bodyLines = []
            return
        }
        // Word-offset chunking that preserves the original whitespace (newlines, blank lines, code fence
        // indentation) inside each chunk. Splitting on /\s+/ and rejoining with " " would destroy markdown
        // structure - tables, bullet lists, and fenced code would render as one line of noise in the UI.
        const wordMatches = [...text.matchAll(/\S+/g)]
        const heading = headingStack.filter(Boolean).join(" › ") || source
        const step = TARGET_CHUNK_TOKENS - CHUNK_OVERLAP_TOKENS
        for (let i = 0; i < wordMatches.length; i += step) {
            const startIdx = wordMatches[i].index!
            const lastWordAbs = Math.min(i + TARGET_CHUNK_TOKENS - 1, wordMatches.length - 1)
            const lastWord = wordMatches[lastWordAbs]
            const endIdx = lastWord.index! + lastWord[0].length
            const chunkText = text.slice(startIdx, endIdx).trim()
            if (chunkText.length === 0) break
            chunks.push({
                id: `${source}#${flushIdx}-${i}`,
                source,
                heading,
                text: chunkText,
                kind: "doc",
            })
            if (i + TARGET_CHUNK_TOKENS >= wordMatches.length) break
        }
        flushIdx += 1
        bodyLines = []
    }

    for (const line of lines) {
        const match = line.match(/^(#{1,6})\s+(.*)$/)
        if (match) {
            flush()
            const level = match[1].length
            const title = match[2].trim()
            headingStack = headingStack.slice(0, level - 1)
            while (headingStack.length < level - 1) headingStack.push("")
            headingStack[level - 1] = title
        } else {
            bodyLines.push(line)
        }
    }
    flush()
    return chunks
}

/**
 * Extract title/description pairs from searchConfig.ts as individual chunks. Uses regex on the raw source to
 * avoid importing React Native dependencies into a Node build script.
 *
 * @param tsSource Raw text of `searchConfig.ts`.
 * @returns One [Chunk] per `title` / `description` pair found in the source, in declaration order.
 */
function chunkSearchConfig(tsSource: string): Chunk[] {
    const chunks: Chunk[] = []
    // Match `title: "..."` and the nearest following `description: "..."` (same object literal).
    const re = /title:\s*"((?:[^"\\]|\\.)*)",\s*description:\s*"((?:[^"\\]|\\.)*)"/g
    let m: RegExpExecArray | null
    let idx = 0
    while ((m = re.exec(tsSource)) !== null) {
        const title = unescapeTsString(m[1])
        const description = unescapeTsString(m[2])
        chunks.push({
            id: `searchConfig.ts#${idx}`,
            source: "searchConfig.ts",
            heading: title,
            text: `${title}: ${description}`,
            kind: "doc",
        })
        idx += 1
    }
    return chunks
}

/**
 * Reverse the most common TypeScript string-literal escapes so chunk text matches what a reader sees in the
 * rendered UI rather than the on-disk source.
 *
 * @param s Escaped string literal contents (without the surrounding quotes).
 * @returns The same string with `\"`, `\\`, and `\n` escape sequences decoded.
 */
function unescapeTsString(s: string): string {
    return s.replace(/\\"/g, '"').replace(/\\\\/g, "\\").replace(/\\n/g, "\n")
}

// ----------------------------------------------------------------------------
// Embedder
// ----------------------------------------------------------------------------

/**
 * Ensure the MiniLM ONNX is present on-disk at `MODEL_PATH` and matches `EMBEDDER_SHA256`. Cached under .cache/
 * so subsequent indexer runs don't re-download. On hash mismatch the file is removed and a fresh download is
 * attempted exactly once.
 *
 * @returns Promise that resolves once a verified embedder ONNX is on-disk at `MODEL_PATH`.
 * @throws Error when the HTTP fetch fails or the post-download SHA-256 does not match `EMBEDDER_SHA256`.
 */
async function ensureEmbedderCached(): Promise<void> {
    fs.mkdirSync(CACHE_DIR, { recursive: true })
    if (fs.existsSync(MODEL_PATH)) {
        const actual = sha256File(MODEL_PATH)
        if (actual === EMBEDDER_SHA256) return
        console.warn(`Cached embedder hash mismatch (got ${actual}); re-downloading.`)
        fs.rmSync(MODEL_PATH)
    }
    console.log(`Downloading embedder from ${EMBEDDER_URL}...`)
    const res = await fetch(EMBEDDER_URL)
    if (!res.ok) throw new Error(`Embedder download failed: ${res.status} ${res.statusText}`)
    const buf = Buffer.from(await res.arrayBuffer())
    fs.writeFileSync(MODEL_PATH, buf)
    const actual = sha256File(MODEL_PATH)
    if (actual !== EMBEDDER_SHA256) {
        fs.rmSync(MODEL_PATH)
        throw new Error(`Embedder SHA-256 mismatch: expected ${EMBEDDER_SHA256}, got ${actual}`)
    }
    console.log(`  cached at ${MODEL_PATH} (${(buf.length / 1024 / 1024).toFixed(1)} MB)`)
}

/**
 * Hex-encoded SHA-256 of the file at [filePath]. Reads the entire file into memory at once; only used for the
 * 22 MB embedder ONNX so the simpler API is fine.
 *
 * @param filePath Absolute or relative path to the file to hash.
 * @returns Lowercase hex SHA-256 digest of the file's bytes.
 */
function sha256File(filePath: string): string {
    const hash = crypto.createHash("sha256")
    hash.update(fs.readFileSync(filePath))
    return hash.digest("hex")
}

/**
 * Run the MiniLM-L6-v2 ONNX over every chunk and return their pooled, normalized sentence embeddings.
 * Ensures the embedder is cached before the first call so the indexer can be invoked on a clean checkout.
 *
 * @param chunks Source chunks to embed; ordering is preserved in the returned array.
 * @returns Promise resolving to one `Float32Array(EMBEDDING_DIM)` per input chunk, mean-pooled and L2-normalized.
 */
async function embedAll(chunks: Chunk[]): Promise<Float32Array[]> {
    await ensureEmbedderCached()
    const tokenizer = WordPieceTokenizer.fromVocabFile(VOCAB_PATH)
    const session = await ort.InferenceSession.create(MODEL_PATH)
    const embeddings: Float32Array[] = []

    for (let i = 0; i < chunks.length; i++) {
        const { inputIds, attentionMask, tokenTypeIds } = tokenizer.encode(chunks[i].text)
        const feeds: Record<string, ort.Tensor> = {
            input_ids: new ort.Tensor("int64", inputIds, [1, MAX_SEQ_LEN]),
            attention_mask: new ort.Tensor("int64", attentionMask, [1, MAX_SEQ_LEN]),
            token_type_ids: new ort.Tensor("int64", tokenTypeIds, [1, MAX_SEQ_LEN]),
        }
        const out = await session.run(feeds)
        const hidden = out[Object.keys(out)[0]].data as Float32Array
        embeddings.push(meanPoolAndNormalize(hidden, attentionMask))
        if ((i + 1) % 50 === 0) console.log(`  embedded ${i + 1}/${chunks.length}`)
    }
    return embeddings
}

/**
 * Mean-pool the per-token hidden states masked by [mask], then L2-normalize the result so cosine similarity
 * collapses to a dot product at query time. Mirrors `EmbeddingService.meanPoolAndNormalize` on the Kotlin side.
 *
 * @param hidden Flat `seq_len * EMBEDDING_DIM` row-major hidden states emitted by the ONNX session.
 * @param mask Attention mask aligned with [hidden]; tokens with mask=0 are excluded from the average.
 * @returns A unit-length `Float32Array(EMBEDDING_DIM)` sentence embedding.
 */
function meanPoolAndNormalize(hidden: Float32Array, mask: BigInt64Array): Float32Array {
    const pooled = new Float32Array(EMBEDDING_DIM)
    let count = 0
    for (let t = 0; t < mask.length; t++) {
        if (mask[t] === 0n) continue
        const base = t * EMBEDDING_DIM
        for (let d = 0; d < EMBEDDING_DIM; d++) pooled[d] += hidden[base + d]
        count += 1
    }
    if (count > 0) for (let d = 0; d < EMBEDDING_DIM; d++) pooled[d] /= count
    let norm = 0
    for (let d = 0; d < EMBEDDING_DIM; d++) norm += pooled[d] * pooled[d]
    norm = Math.sqrt(norm)
    if (norm > 0) for (let d = 0; d < EMBEDDING_DIM; d++) pooled[d] /= norm
    return pooled
}

// ----------------------------------------------------------------------------
// Binary writer (format matches DocIndex.kt's reader)
// ----------------------------------------------------------------------------

/**
 * Serialize [chunks] and their [embeddings] into the binary `doc_index.bin` format that `DocIndex.kt` reads at
 * runtime. Format is documented in `DocIndex.kt`; this writer must stay byte-compatible with that reader.
 *
 * @param chunks Chunks aligned 1-to-1 with [embeddings] by index.
 * @param embeddings Per-chunk embedding vectors of length `EMBEDDING_DIM`.
 * @returns Concatenated [Buffer] ready to be written to disk as `doc_index.bin`.
 */
function writeIndex(chunks: Chunk[], embeddings: Float32Array[]): Buffer {
    const parts: Buffer[] = []
    parts.push(Buffer.from(MAGIC, "utf8"))
    parts.push(u32LE(VERSION))
    parts.push(u32LE(chunks.length))
    parts.push(u32LE(EMBEDDING_DIM))
    for (let i = 0; i < chunks.length; i++) {
        const c = chunks[i]
        const id = Buffer.from(c.id, "utf8")
        const source = Buffer.from(c.source, "utf8")
        const heading = Buffer.from(c.heading, "utf8")
        const text = Buffer.from(c.text, "utf8")
        parts.push(u16LE(id.length), id)
        parts.push(u16LE(source.length), source)
        parts.push(u16LE(heading.length), heading)
        parts.push(u32LE(text.length), text)
        parts.push(Buffer.from([c.kind === "code" ? KIND_CODE : KIND_DOC]))
        const emb = Buffer.alloc(EMBEDDING_DIM * 4)
        for (let d = 0; d < EMBEDDING_DIM; d++) emb.writeFloatLE(embeddings[i][d], d * 4)
        parts.push(emb)
    }
    return Buffer.concat(parts)
}

/**
 * Encode [v] as a little-endian unsigned 16-bit integer.
 *
 * @param v Value in `[0, 65535]`.
 * @returns Two-byte little-endian [Buffer] representation of [v].
 */
function u16LE(v: number): Buffer {
    const b = Buffer.alloc(2)
    b.writeUInt16LE(v, 0)
    return b
}

/**
 * Encode [v] as a little-endian unsigned 32-bit integer.
 *
 * @param v Value in `[0, 4294967295]`.
 * @returns Four-byte little-endian [Buffer] representation of [v].
 */
function u32LE(v: number): Buffer {
    const b = Buffer.alloc(4)
    b.writeUInt32LE(v, 0)
    return b
}

// ----------------------------------------------------------------------------
// Incremental rebuild: skip if source hash unchanged
// ----------------------------------------------------------------------------

/**
 * Combined SHA-256 over the contents of every file in [sources], hashed in argument order. Used to short-circuit
 * an index rebuild when none of the source files have changed since the last run.
 *
 * @param sources Absolute paths to every source file that contributes to `doc_index.bin`.
 * @returns Promise resolving to the lowercase hex SHA-256 digest of the concatenated file contents.
 */
async function sourceHash(sources: string[]): Promise<string> {
    const crypto = await import("node:crypto")
    const hash = crypto.createHash("sha256")
    for (const s of sources) hash.update(fs.readFileSync(s))
    return hash.digest("hex")
}

// ----------------------------------------------------------------------------
// Main
// ----------------------------------------------------------------------------

/**
 * CLI entry point. Resolves every source file, short-circuits when the source hash matches the prior run,
 * otherwise re-chunks, re-embeds, and rewrites `doc_index.bin` and `doc_index.sources.sha256`.
 *
 * @returns Promise that resolves once the index files have been written (or the rebuild was skipped).
 */
async function main() {
    const readme = path.join(REPO_ROOT, "README.md")
    const howItWorks = path.join(REPO_ROOT, "HOW_IT_WORKS.md")
    const searchConfig = path.join(REPO_ROOT, "src/data/searchConfig.ts")
    const kotlinFiles = findKotlinFiles(KOTLIN_SRC_DIR)
    const sources = [readme, howItWorks, searchConfig, ...kotlinFiles]
    for (const s of sources) if (!fs.existsSync(s)) throw new Error(`missing source: ${s}`)

    const currentHash = await sourceHash(sources)
    if (fs.existsSync(HASH_PATH) && fs.existsSync(OUTPUT_PATH)) {
        const prev = fs.readFileSync(HASH_PATH, "utf8").trim()
        if (prev === currentHash) {
            console.log(`Doc index up to date (hash ${currentHash.slice(0, 12)}). Skipping rebuild.`)
            return
        }
    }

    console.log("Chunking sources...")
    const chunks: Chunk[] = []
    chunks.push(...chunkMarkdown(fs.readFileSync(readme, "utf8"), "README.md"))
    chunks.push(...chunkMarkdown(fs.readFileSync(howItWorks, "utf8"), "HOW_IT_WORKS.md"))
    chunks.push(...chunkSearchConfig(fs.readFileSync(searchConfig, "utf8")))
    const docCount = chunks.length
    for (const f of kotlinFiles) {
        for (const k of chunkKotlinFile(f)) {
            chunks.push({ id: k.id, source: k.source, heading: k.heading, text: k.text, kind: "code" })
        }
    }
    console.log(`  ${chunks.length} chunks (doc=${docCount}, code=${chunks.length - docCount}, ${kotlinFiles.length} .kt files)`)

    console.log("Embedding chunks...")
    const embeddings = await embedAll(chunks)

    console.log(`Writing ${OUTPUT_PATH}...`)
    fs.writeFileSync(OUTPUT_PATH, writeIndex(chunks, embeddings))
    fs.writeFileSync(HASH_PATH, currentHash)
    const sizeKB = Math.round(fs.statSync(OUTPUT_PATH).size / 1024)
    console.log(`Done. ${chunks.length} chunks, ${sizeKB} KB.`)
}

main().catch((err) => {
    console.error(err)
    process.exit(1)
})
