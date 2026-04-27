import { NativeModules } from "react-native"
import { databaseManager } from "../database"

/**
 * SQLite location of the user's selected active model filename (set from LLM Settings or the Ask the Docs page).
 *
 * Stored under the `chat` category so it lives outside `BotStateContext` and is therefore not included in
 * settings exports. Pair the same constant on both the read and write side so the two pages can never disagree
 * on where the value lives.
 */
export const ACTIVE_MODEL_SETTING = { category: "chat", key: "activeModelFilename" } as const

/**
 * Bridge-shape of one entry returned by `LLMChatModule.listModels()`.
 *
 * Mirrors the Kotlin side exactly - any field rename here must also land in `LLMChatModule.listModels`.
 */
export interface DownloadedModel {
    /** Bare filename of the GGUF on disk (e.g. `qwen2.5-1.5b-instruct-q4_k_m.gguf`). */
    filename: string
    /** Absolute path inside app-private storage. Pass to `llama.rn` directly. */
    path: string
    /** File size in bytes; used by the LLM Settings UI to show "~NN MB" for each model. */
    sizeBytes: number
    /** Last-modified epoch millis; the fallback ordering in `resolveActiveModel` uses the bridge order, not this. */
    lastModifiedMillis: number
}

/**
 * Minimal projection of `DownloadedModel` used by callers that only need to load and identify the model.
 *
 * Returned by `resolveActiveModel` so call sites don't accidentally branch on metadata they shouldn't trust
 * (e.g. `sizeBytes` is not refreshed once a model is loaded).
 */
export interface ResolvedModel {
    /** Bare filename of the chosen model. */
    filename: string
    /** Absolute path on disk to feed to `llama.rn`. */
    path: string
}

/**
 * Resolve which downloaded GGUF model the chat pipeline should use.
 *
 * Resolution order:
 *  1. Honor the user's explicit selection from `ACTIVE_MODEL_SETTING` when that filename is still present on
 *     disk.
 *  2. Fall back to the first entry returned by `LLMChatModule.listModels()` so a freshly-downloaded model
 *     becomes usable without a settings round-trip.
 *
 * @returns The resolved model, or `null` when no models are present or the bridge call fails. Callers treat
 *   `null` as a signal to drop into retrieve-only mode rather than crash.
 */
export async function resolveActiveModel(): Promise<ResolvedModel | null> {
    try {
        const models: DownloadedModel[] = await NativeModules.LLMChatModule.listModels()
        if (!models || models.length === 0) return null
        const active = await databaseManager.loadSetting(ACTIVE_MODEL_SETTING.category, ACTIVE_MODEL_SETTING.key)
        if (typeof active === "string" && active.length > 0) {
            const matched = models.find((m) => m.filename === active)
            if (matched) return { filename: matched.filename, path: matched.path }
        }
        const fallback = models[0]
        return { filename: fallback.filename, path: fallback.path }
    } catch {
        return null
    }
}
