import { databaseManager } from "../database"

/**
 * Persistence layer for the user-tunable chat parameters.
 *
 * Lives outside `BotStateContext`, under category `"chat"`, so values are NOT included in settings exports.
 * Each tuning value is read directly from SQLite at chat-call time (see `loadChatTuning`) rather than mirrored
 * into the React context, which keeps the LLM Settings sliders authoritative without a round-trip through
 * `bsc.setSettings`.
 */

/** SQLite category used by all chat-related settings (model URL, HF token, tuning, active model filename). */
export const CHAT_CATEGORY = "chat"

/**
 * Stable key strings for the three generation-tuning sliders shown on the LLM Settings page.
 *
 * Centralized so reads (`loadChatTuning`) and writes (`saveTuning`) cannot drift out of sync, and so a future
 * rename only has to touch this one map.
 */
export const SETTING_KEYS = {
    maxOutputTokens: "maxOutputTokens",
    llmCitationCharCap: "llmCitationCharCap",
    modelContextWindow: "modelContextWindow",
} as const

/**
 * Defaults applied when a tuning value isn't yet persisted. Hand-picked to fit the on-device profile:
 * 768 tokens of output is enough for 4-10 sentence answers; 2200 chars per citation lets four expanded
 * sections fit alongside the system prompt within a 4096-token KV cache.
 */
export const DEFAULTS = {
    maxOutputTokens: 768,
    llmCitationCharCap: 2200,
    modelContextWindow: 4096,
} as const

/**
 * Snapshot of all three tuning values, returned by `loadChatTuning`.
 *
 * The Chat page reads this once per query and forwards each value to the corresponding stage of the pipeline:
 * `llmCitationCharCap` to `trimToCap`, `modelContextWindow` to `llamaRunner.ensureContext`, and
 * `maxOutputTokens` to `llamaRunner.chat`.
 */
export interface ChatTuning {
    /** Hard cap on tokens generated per answer (passed as `n_predict` to llama.rn). */
    maxOutputTokens: number
    /** Per-citation character cap applied to expanded section text before it enters the system prompt. */
    llmCitationCharCap: number
    /** Engine KV-cache size (`n_ctx`); changing this triggers a model reload on the next chat call. */
    modelContextWindow: number
}

/**
 * Load all three tuning values from SQLite, falling back to `DEFAULTS` for any that aren't set yet.
 *
 * @returns A complete `ChatTuning` snapshot - any value not yet persisted is filled from `DEFAULTS`, and a
 *   thrown DB error returns a fresh defaults clone so the caller never has to handle null.
 */
export async function loadChatTuning(): Promise<ChatTuning> {
    try {
        const [maxOut, capRaw, ctx] = await Promise.all([
            databaseManager.loadSetting(CHAT_CATEGORY, SETTING_KEYS.maxOutputTokens),
            databaseManager.loadSetting(CHAT_CATEGORY, SETTING_KEYS.llmCitationCharCap),
            databaseManager.loadSetting(CHAT_CATEGORY, SETTING_KEYS.modelContextWindow),
        ])
        return {
            maxOutputTokens: typeof maxOut === "number" ? maxOut : DEFAULTS.maxOutputTokens,
            llmCitationCharCap: typeof capRaw === "number" ? capRaw : DEFAULTS.llmCitationCharCap,
            modelContextWindow: typeof ctx === "number" ? ctx : DEFAULTS.modelContextWindow,
        }
    } catch {
        return { ...DEFAULTS }
    }
}

/**
 * Persist a single tuning value to SQLite. Fire-and-forget - failures are swallowed (DB layer logs them).
 *
 * @param key Logical name of the tuning value, constrained to a key of `SETTING_KEYS` so renames stay typesafe.
 * @param value New value to store. The slider widgets emit pre-clamped numbers; no extra validation is done here.
 */
export function saveTuning<K extends keyof typeof SETTING_KEYS>(key: K, value: number): void {
    databaseManager.saveSetting(CHAT_CATEGORY, SETTING_KEYS[key], value, true).catch(() => undefined)
}

/**
 * Cap a per-citation expanded text snippet to `maxChars`, breaking on a word boundary and adding an ellipsis.
 *
 * @param text Raw expanded section text to trim.
 * @param maxChars Inclusive character cap; values at or below this length are returned unchanged.
 * @returns Either `text` verbatim or a trimmed prefix ending at the last space inside the cap, with an ellipsis
 *   appended. Falls back to a hard cut when no space appears within the cap.
 */
export function trimToCap(text: string, maxChars: number): string {
    if (text.length <= maxChars) return text
    const slice = text.slice(0, maxChars)
    const lastSpace = slice.lastIndexOf(" ")
    return (lastSpace > 0 ? slice.slice(0, lastSpace) : slice) + "…"
}
