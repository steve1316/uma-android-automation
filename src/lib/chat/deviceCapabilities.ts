import { NativeModules } from "react-native"

/**
 * Device-capability snapshot returned by `LLMChatModule.getDeviceCapabilities`.
 *
 * Drives the LLM Settings diagnostic row (acceleration tier, RAM, recommended preset) and the pre-download
 * fit warning. Refreshed on focus, not in a hot path.
 */
export interface DeviceCapabilities {
    /** Total physical RAM reported by `ActivityManager.MemoryInfo.totalMem`. */
    totalRamBytes: number
    /** Currently-available RAM (`MemoryInfo.availMem`). Loose upper bound on what a chat model can claim. */
    availRamBytes: number
    /** Tokens from `/proc/cpuinfo`'s `Features:` line (e.g. `["fp", "asimd", "asimddp", "i8mm", ...]`). */
    cpuFeatures: string[]
    /** Primary supported ABI (`Build.SUPPORTED_ABIS[0]`); should always be `arm64-v8a` after our split. */
    abi: string
}

/**
 * Acceleration tier the device qualifies for. Matches the highest llama.rn `.so` variant we ship that the
 * device's CPU features satisfy. `unknown` is a defensive fallback for parse failures - treated as the slow
 * baseline by the UI.
 */
export type AccelerationTier = "v8.2-dotprod" | "v8-baseline" | "unknown"

/** Approximate free-RAM requirement for each chat-model preset, keyed by a substring of the preset URL. */
export const PRESET_RAM_REQUIREMENTS_BYTES: Array<{ urlSubstring: string; requiredAvailRamBytes: number; label: string }> = [
    { urlSubstring: "Qwen2.5-0.5B-Instruct", requiredAvailRamBytes: 1 * 1024 * 1024 * 1024, label: "Qwen 2.5 0.5B" },
    { urlSubstring: "Qwen2.5-1.5B-Instruct", requiredAvailRamBytes: 2 * 1024 * 1024 * 1024, label: "Qwen 2.5 1.5B" },
    { urlSubstring: "Qwen2.5-3B-Instruct", requiredAvailRamBytes: 4 * 1024 * 1024 * 1024, label: "Qwen 2.5 3B" },
]

/**
 * Fetch a fresh capability snapshot from the bridge.
 *
 * @returns The snapshot, or `null` when the bridge call fails so callers can fall through to "device info
 *   unavailable" without a try/catch.
 */
export async function loadDeviceCapabilities(): Promise<DeviceCapabilities | null> {
    try {
        const raw = await NativeModules.LLMChatModule.getDeviceCapabilities()
        return {
            totalRamBytes: Number(raw?.totalRamBytes ?? 0),
            availRamBytes: Number(raw?.availRamBytes ?? 0),
            cpuFeatures: Array.isArray(raw?.cpuFeatures) ? raw.cpuFeatures : [],
            abi: typeof raw?.abi === "string" ? raw.abi : "unknown",
        }
    } catch {
        return null
    }
}

/**
 * Map [features] to the highest llama.rn variant we actually ship that the device qualifies for.
 *
 * `asimddp` (dotprod) is what differentiates the fast `v8_2_dotprod` variant from the baseline `v8` /
 * `v8_2` variants. The `i8mm` and `hexagon_opencl` variants are not in the APK (trimmed for size), so the
 * presence of `i8mm` is informational only and doesn't change the tier.
 */
export function accelerationTier(features: string[]): AccelerationTier {
    if (!features || features.length === 0) return "unknown"
    if (features.includes("asimddp")) return "v8.2-dotprod"
    return "v8-baseline"
}

/**
 * Human-readable label for [tier] suitable for the diagnostic row.
 */
export function accelerationTierLabel(tier: AccelerationTier): string {
    switch (tier) {
        case "v8.2-dotprod":
            return "v8.2 + dotprod (fast)"
        case "v8-baseline":
            return "v8 baseline (slow)"
        case "unknown":
            return "unknown"
    }
}

/**
 * Pick the largest preset that fits in [caps.availRamBytes]. Returns `null` when no preset fits or [caps] is
 * `null`, which the UI surfaces as "your device may not have enough RAM for any preset".
 */
export function recommendedPreset(caps: DeviceCapabilities | null): { urlSubstring: string; label: string } | null {
    if (!caps) return null
    const fits = PRESET_RAM_REQUIREMENTS_BYTES.filter((p) => caps.availRamBytes >= p.requiredAvailRamBytes)
    if (fits.length === 0) return null
    return fits[fits.length - 1]
}

/**
 * Whether the preset whose URL contains [urlSubstring] fits in the device's free RAM.
 *
 * Returns `true` when [caps] is `null` so a missing capability snapshot doesn't block downloads (the bridge
 * would have already surfaced the failure elsewhere).
 */
export function presetFitsRam(caps: DeviceCapabilities | null, presetUrl: string): boolean {
    if (!caps) return true
    const match = PRESET_RAM_REQUIREMENTS_BYTES.find((p) => presetUrl.includes(p.urlSubstring))
    if (!match) return true
    return caps.availRamBytes >= match.requiredAvailRamBytes
}

/** Format [bytes] as a short string like `5.2 GB` or `812 MB`. */
export function formatBytes(bytes: number): string {
    const gb = bytes / 1024 / 1024 / 1024
    if (gb >= 1) return `${gb.toFixed(1)} GB`
    const mb = bytes / 1024 / 1024
    return `${Math.round(mb)} MB`
}
