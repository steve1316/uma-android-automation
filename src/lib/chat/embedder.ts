import { NativeModules } from "react-native"

/**
 * Public HuggingFace mirror of the int8-quantized MiniLM-L6-v2 ONNX used by the on-device docs chatbot.
 *
 * The runtime ONNX must be byte-for-byte identical to the one [scripts/build-doc-index.ts] uses, otherwise the
 * cosine similarities computed at query time stop matching the vectors written into `doc_index.bin`. Both ends
 * pin this same URL + [EMBEDDER_SHA256] so a future re-index can't drift.
 */
export const EMBEDDER_URL = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx"

/**
 * Expected lowercase hex SHA-256 of the file at [EMBEDDER_URL]. Verified by `LLMChatModule.downloadEmbedder`
 * after the download completes; on mismatch the partial file is deleted and a `failed` event surfaces in the UI.
 */
export const EMBEDDER_SHA256 = "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1"

/** Approximate byte size of the embedder ONNX, used by the LLM Settings UI to advertise the download cost. */
export const EMBEDDER_SIZE_BYTES = 22_972_370

/**
 * Promise-resolves to `true` when the embedder ONNX is present on-disk and ready to load, otherwise `false`.
 *
 * Backed by `LLMChatModule.isEmbedderReady`; safe to call frequently because the bridge does only a stat-style
 * existence check (no file read).
 */
export async function isEmbedderReady(): Promise<boolean> {
    try {
        return await NativeModules.LLMChatModule.isEmbedderReady()
    } catch {
        return false
    }
}
