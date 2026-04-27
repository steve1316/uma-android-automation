import { initLlama, LlamaContext } from "llama.rn"

/**
 * Singleton wrapper around llama.rn's `initLlama` + `context.completion` for the on-device chatbot.
 *
 * Holds one `LlamaContext` across queries - reloading the GGUF + warming up the KV cache costs seconds, so the
 * second prompt onward should be much faster than the first.
 *
 * Usage:
 * ```
 * await llamaRunner.ensureContext("/path/to/model.gguf", { nCtx: 4096 })
 * const text = await llamaRunner.chat({
 *     messages: [{ role: "system", content: "..." }, { role: "user", content: "..." }],
 *     maxTokens: 768, temperature: 0.35,
 * }, (token) => setPartialAnswer(prev => prev + token))
 * ```
 */

export interface LoadOptions {
    /** KV-cache size (input + output tokens combined). Default 4096. */
    nCtx?: number
    /** GPU layers to offload. Default 0 (CPU-only) for max device compatibility. */
    nGpuLayers?: number
    /** Pin context in RAM. Default true on Android to avoid OOM swap during long generations. */
    useMlock?: boolean
}

/**
 * One turn of the conversation passed to the model. Mirrors the OpenAI chat-completion shape that `llama.rn`
 * accepts directly, so callers can build the array without an additional conversion step.
 */
export interface ChatMessage {
    /** Author of the message; `system` is the grounding scaffold, `user` is the question, `assistant` is prior answers. */
    role: "system" | "user" | "assistant"
    /** Raw message text. The Chat page packs the system prompt plus retrieved excerpts into a single `system` content string. */
    content: string
}

/** Per-call generation parameters forwarded to `llama.rn`'s `context.completion`. */
export interface ChatOptions {
    /** Ordered conversation turns; the last entry is the question being answered. */
    messages: ChatMessage[]
    /** Hard cap on tokens generated for this answer (`n_predict`). Defaults to 768. */
    maxTokens?: number
    /** Sampling temperature; lower values produce more deterministic paraphrases. Default 0.35. */
    temperature?: number
    /** Top-K sampling cutoff. Default 40. */
    topK?: number
    /** Nucleus (top-P) sampling cutoff. Default 0.95. */
    topP?: number
    /** Strings that, when emitted, halt generation. Defaults cover Gemma + Qwen + Llama EOS markers. */
    stop?: string[]
}

/** Generation timing + token counters reported by llama.rn alongside the final text. */
export interface ChatStats {
    /** Tokens produced during the generation phase. */
    tokensPredicted: number
    /** Tokens consumed from the prompt during prefill. */
    tokensEvaluated: number
    /** Generation throughput in tokens per second (the "tok/s" figure users care about). */
    predictedPerSecond: number
    /** Prefill throughput in tokens per second (typically much higher than `predictedPerSecond`). */
    promptPerSecond: number
    /** Wall-clock generation time in milliseconds. */
    predictedMs: number
    /** Wall-clock prefill time in milliseconds. */
    promptMs: number
}

/** Final shape returned by `chat`: the assembled answer plus optional engine timing stats. */
export interface ChatResult {
    /** Full concatenated answer text after all tokens have been streamed. Empty string when llama.rn returned a non-string `text`. */
    text: string
    /** Generation timing block from llama.rn's `timings`, or `null` when the engine didn't report one. */
    stats: ChatStats | null
}

/** Lazily-initialized llama.rn context. Held module-scope so a single GGUF stays loaded across queries. */
let currentContext: LlamaContext | null = null
/** Path of the GGUF backing `currentContext`; used by `ensureContext` to detect model swaps. */
let currentModelPath: string | null = null
/** Load options `currentContext` was initialized with; used by `optsEqual` to detect when a reload is needed. */
let currentLoadOpts: LoadOptions = {}
/** Coalesces concurrent `ensureContext` calls so a second caller during load reuses the in-flight promise instead of triggering a second `initLlama`. */
let loadInFlight: Promise<LlamaContext | null> | null = null

/**
 * Default end-of-turn markers passed as `stop` to `llama.rn` when the caller doesn't override it.
 *
 * Covers the EOS markers used by the supported preset families - Gemma (`<end_of_turn>`), Qwen
 * (`<|im_end|>`/`<|end|>`), and Llama (`<|eot_id|>`/`</s>`) - so the model halts cleanly regardless of which
 * GGUF the user has selected.
 */
const DEFAULT_STOP = ["</s>", "<|im_end|>", "<|end|>", "<end_of_turn>", "<|eot_id|>"]

/**
 * Load (or reuse) a llama.rn context for `modelPath`. If a context for a different path is already loaded,
 * release it first. If `opts` differ from the cached load (notably `nCtx`), reload as well.
 *
 * @param modelPath Absolute path on disk to the GGUF file to load.
 * @param opts Load-time options; missing fields fall back to the defaults documented on `LoadOptions`.
 * @returns The loaded context, or `null` if the load failed (an error is also re-thrown to the caller via the
 *   underlying llama.rn promise rejection).
 */
export async function ensureContext(modelPath: string, opts: LoadOptions = {}): Promise<LlamaContext | null> {
    const optsMatch = optsEqual(currentLoadOpts, opts)
    if (currentContext && currentModelPath === modelPath && optsMatch) return currentContext
    if (loadInFlight) return loadInFlight

    loadInFlight = (async () => {
        // Tear down any previous context before bringing up the new one - keeping two loaded would double RAM.
        if (currentContext) {
            try {
                await currentContext.release()
            } catch {
                // Best-effort release; old context may already be in a bad state.
            }
            currentContext = null
            currentModelPath = null
        }
        try {
            const ctx = await initLlama({
                model: modelPath,
                n_ctx: opts.nCtx ?? 4096,
                n_gpu_layers: opts.nGpuLayers ?? 0,
                use_mlock: opts.useMlock ?? true,
            })
            currentContext = ctx
            currentModelPath = modelPath
            currentLoadOpts = opts
            return ctx
        } catch (e) {
            currentContext = null
            currentModelPath = null
            throw e
        } finally {
            loadInFlight = null
        }
    })()
    return loadInFlight
}

/**
 * Run `opts.messages` through the loaded context and return the final answer text plus generation stats.
 *
 * Streams individual tokens to `onToken` as they arrive (so the UI can render a live partial answer); the resolved
 * promise carries the full concatenated text and llama.rn's `timings` block after generation finishes.
 *
 * @param opts Generation parameters; see `ChatOptions` for per-field defaults.
 * @param onToken Optional callback invoked once per generated token while streaming. Receives the bare token
 *   string. Pass to drive a live partial-answer view; omit when the caller only needs the final text.
 * @returns The full answer text and the engine's timing/token counters (or `null` stats when llama.rn omits
 *   the timings block).
 * @throws If `ensureContext` hasn't been called or the load failed.
 */
export async function chat(opts: ChatOptions, onToken?: (token: string) => void): Promise<ChatResult> {
    const ctx = currentContext
    if (!ctx) throw new Error("llamaRunner.chat called before ensureContext")
    const result: any = await ctx.completion(
        {
            messages: opts.messages,
            n_predict: opts.maxTokens ?? 768,
            temperature: opts.temperature ?? 0.35,
            top_k: opts.topK ?? 40,
            top_p: opts.topP ?? 0.95,
            stop: opts.stop ?? DEFAULT_STOP,
        },
        (data: { token: string }) => {
            if (onToken && data.token) onToken(data.token)
        }
    )
    return {
        text: typeof result?.text === "string" ? result.text : "",
        stats: extractStats(result),
    }
}

/**
 * Pull the timing block out of llama.rn's completion result.
 *
 * @param result Raw object returned by `LlamaContext.completion`. Shape is loosely typed (`any`) because
 *   llama.rn's TypeScript declarations don't include `timings` on every release.
 * @returns A populated `ChatStats` when `result.timings` is present, otherwise `null` so callers can render
 *   the answer card without a stats footer.
 */
function extractStats(result: any): ChatStats | null {
    const t = result?.timings
    if (!t) return null
    return {
        tokensPredicted: numberOr(result?.tokens_predicted, t.predicted_n ?? 0),
        tokensEvaluated: numberOr(result?.tokens_evaluated, t.prompt_n ?? 0),
        predictedPerSecond: numberOr(t.predicted_per_second, 0),
        promptPerSecond: numberOr(t.prompt_per_second, 0),
        predictedMs: numberOr(t.predicted_ms, 0),
        promptMs: numberOr(t.prompt_ms, 0),
    }
}

/**
 * Return `value` when it is a finite number, otherwise `fallback`. Defends `extractStats` against the
 * `NaN`/`undefined`/string entries that older llama.rn builds occasionally emit in the timings block.
 *
 * @param value Candidate value pulled from the timings block; may be any type.
 * @param fallback Replacement returned when `value` is not a finite number.
 * @returns `value` verbatim when it satisfies `Number.isFinite`, otherwise `fallback`.
 */
function numberOr(value: any, fallback: number): number {
    return typeof value === "number" && Number.isFinite(value) ? value : fallback
}

/**
 * Release the currently-loaded context, freeing RAM. Safe to call when nothing is loaded.
 *
 * @returns A promise that resolves once the underlying llama.rn release call has settled; failures during
 *   release are swallowed (the context may already be in a bad state) and do not propagate.
 */
export async function release(): Promise<void> {
    if (currentContext) {
        try {
            await currentContext.release()
        } catch {
            // Best-effort.
        }
    }
    currentContext = null
    currentModelPath = null
    currentLoadOpts = {}
}

/**
 * Reports whether a context is currently loaded. Useful for the UI to disable Chat input until a model is ready.
 *
 * @returns `true` when a llama.rn context is held in memory, `false` otherwise.
 */
export function isLoaded(): boolean {
    return currentContext !== null
}

/**
 * Path of the currently-loaded model, if any.
 *
 * @returns Absolute filesystem path passed to `ensureContext` for the live context, or `null` when no
 *   context is loaded.
 */
export function loadedModelPath(): string | null {
    return currentModelPath
}

/**
 * Structural equality for `LoadOptions`, with each field defaulted the same way `ensureContext` defaults it.
 *
 * Lets `ensureContext` decide whether the cached context can be reused or must be torn down and rebuilt - any
 * tunable that affects the engine state (notably `nCtx`) needs a reload to take effect.
 *
 * @param a One `LoadOptions` to compare; typically the cached `currentLoadOpts`.
 * @param b The other `LoadOptions`; typically the caller's incoming `opts`.
 * @returns `true` when both option sets resolve to the same effective values after defaulting.
 */
function optsEqual(a: LoadOptions, b: LoadOptions): boolean {
    return (a.nCtx ?? 4096) === (b.nCtx ?? 4096) && (a.nGpuLayers ?? 0) === (b.nGpuLayers ?? 0) && (a.useMlock ?? true) === (b.useMlock ?? true)
}
