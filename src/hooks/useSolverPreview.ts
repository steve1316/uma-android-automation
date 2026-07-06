import { useEffect, useMemo, useState } from "react"
import racesData from "../data/races.json"
import epithetsData from "../data/epithets.json"
import characterObjectivesData from "../data/character_objectives.json"
import { previewSchedule, SchedulePreview, SolverConfigSnapshot } from "../lib/solver/preview"

// Stringify the bundled JSON once at module load so we don't pay the serialisation cost on every preview call.
const RACES_DATA_JSON = JSON.stringify(racesData)
const EPITHETS_DATA_JSON = JSON.stringify(epithetsData)
const OBJECTIVES_DATA_JSON = JSON.stringify(characterObjectivesData)

// Remembers the last preview so re-opening a consumer shows the previous calendar instantly instead of a blank screen while the solver re-runs.
// Keyed on a JSON snapshot of the solver-relevant settings. Module-level so every consumer (SRS page + Schedule screen) shares one cache and can't diverge. Cleared only on app reload.
let lastPreviewCache: { key: string; preview: SchedulePreview } | null = null

// Tracks whether the bundled races/epithets JSON has been shipped to Kotlin. After the first bridge call Kotlin caches its own copy, so subsequent calls omit the payload (~150KB saved).
let bridgeDataPrimed = false

/** The solver-relevant settings a consumer feeds the preview. Two consumers passing identical inputs share the cache and the `dirty` computation. */
export interface SolverPreviewInputs {
    /** Whether the Smart Race Solver is enabled; when false the preview clears and no solve runs. */
    enableSmartRaceSolver: boolean
    /** Active scenario (empty falls back to "Trackblazer", matching the solver default). */
    scenario: string
    /** Selected character preset name. */
    characterPreset: string
    /** Distance/surface aptitude grades. */
    aptitudes: SolverConfigSnapshot["aptitudes"]
    /** Epithet names to prioritise. */
    targetEpithets: string[]
    /** Epithet names the solver must complete. */
    forcedEpithets: string[]
    /** Per-turn manual locks (turn string -> race name or train sentinel). */
    manualLocks: Record<string, string>
    /** Solver weights bundle. */
    weights: SolverConfigSnapshot["weights"]
    /** Optional-race cap (0 = no limit). */
    maxRaces: number
    /** Back-to-back race cap (0 = no limit). */
    maxConsecutiveRaces: number
}

/** The preview state a consumer renders. */
export interface SolverPreviewState {
    /** The latest schedule preview, or null when SRS is off / errored / still loading the first solve. */
    preview: SchedulePreview | null
    /** Whether a solve is in flight. */
    previewLoading: boolean
    /** Soft-failure message from the solver, or null. */
    previewError: string | null
    /** True when the live settings no longer match the ones that produced `preview` (drives the Apply Changes affordance). */
    dirty: boolean
    /** Force a fresh solve (cache hit is instant). */
    runPreview: () => Promise<void>
}

/**
 * Shared Smart Race Solver preview hook. Owns the module-level preview cache + bridge-primed flag so the SRS settings page and the unified Schedule screen share one
 * cache and one `dirty` computation (identical inputs -> identical snapshot key -> no divergence). Auto-runs on mount / enable and clears when SRS is toggled off.
 * @param inputs The solver-relevant settings to evaluate.
 * @returns The preview state plus a `runPreview` refresher.
 */
export function useSolverPreview(inputs: SolverPreviewInputs): SolverPreviewState {
    const { enableSmartRaceSolver, scenario, characterPreset, aptitudes, targetEpithets, forcedEpithets, manualLocks, weights, maxRaces, maxConsecutiveRaces } = inputs

    const [preview, setPreview] = useState<SchedulePreview | null>(lastPreviewCache?.preview ?? null)
    const [previewLoading, setPreviewLoading] = useState(false)
    const [previewError, setPreviewError] = useState<string | null>(null)
    const [previewSnapshotKey, setPreviewSnapshotKey] = useState<string | null>(lastPreviewCache?.key ?? null)

    const currentSnapshotKey = useMemo(
        () => JSON.stringify({ scenario: scenario || "Trackblazer", characterPreset, aptitudes, targetEpithets, forcedEpithets, manualLocks, weights, maxRaces, maxConsecutiveRaces }),
        [scenario, characterPreset, aptitudes, targetEpithets, forcedEpithets, manualLocks, weights, maxRaces, maxConsecutiveRaces]
    )

    const dirty = previewSnapshotKey != null && currentSnapshotKey !== previewSnapshotKey

    const buildSnapshot = (): SolverConfigSnapshot => ({
        scenario: scenario || "Trackblazer",
        characterPreset,
        aptitudes,
        targetEpithets,
        forcedEpithets,
        manualLocks,
        weights,
        maxRaces,
        maxConsecutiveRaces,
        // Only ship the bundled JSON the first time; Kotlin caches it after that.
        racesDataJson: bridgeDataPrimed ? undefined : RACES_DATA_JSON,
        epithetsDataJson: bridgeDataPrimed ? undefined : EPITHETS_DATA_JSON,
        objectivesDataJson: bridgeDataPrimed ? undefined : OBJECTIVES_DATA_JSON,
    })

    const runPreview = async () => {
        if (!enableSmartRaceSolver) return
        const snapshot = buildSnapshot()
        const key = currentSnapshotKey
        // Cache hit - instant, no bridge call.
        if (lastPreviewCache && lastPreviewCache.key === key) {
            setPreview(lastPreviewCache.preview)
            setPreviewError(lastPreviewCache.preview.error ?? null)
            setPreviewSnapshotKey(key)
            return
        }
        setPreviewLoading(true)
        try {
            const result = await previewSchedule(snapshot)
            bridgeDataPrimed = !result.error
            setPreview(result)
            setPreviewError(result.error ?? null)
            setPreviewSnapshotKey(key)
            lastPreviewCache = { key, preview: result }
        } catch (e: unknown) {
            setPreview(null)
            setPreviewError(e instanceof Error ? e.message : String(e))
        } finally {
            setPreviewLoading(false)
        }
    }

    // Auto-run on first mount or when the feature is toggled on; clear state when toggled off.
    useEffect(() => {
        if (!enableSmartRaceSolver) {
            setPreview(null)
            setPreviewError(null)
            // Reset the snapshot key so `dirty` doesn't stay stuck true on re-enable.
            setPreviewSnapshotKey(null)
            return
        }
        if (preview == null) runPreview()
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [enableSmartRaceSolver])

    return { preview, previewLoading, previewError, dirty, runPreview }
}
