import { useMemo } from "react"
import { DEFAULT_APTITUDES, DEFAULT_WEIGHTS, type AptitudeMap, type WeightsMap } from "../lib/solver/constants"

/** The raw JSON-string settings fields `useSolverInputs` parses. Both the SRS page and the Schedule screen pass their merged racing slice, which satisfies this shape. */
export interface SolverInputSettings {
    /** JSON map of distance/surface aptitude grades. */
    smartRaceSolverAptitudes: string
    /** JSON array of epithet names to prioritise. */
    smartRaceSolverTargetEpithets: string
    /** JSON array of epithet names the solver must complete. */
    smartRaceSolverForcedEpithets: string
    /** JSON map of per-turn manual locks (turn string -> race name or train sentinel). */
    smartRaceSolverManualLocks: string
    /** JSON solver weights bundle. */
    smartRaceSolverWeights: string
}

/** The parsed, memoised solver inputs both consumers feed into `useSolverPreview`. */
export interface SolverInputs {
    /** Distance/surface aptitude grades, defaults filled in. */
    aptitudes: AptitudeMap
    /** Epithet names to prioritise. */
    targetEpithets: string[]
    /** Epithet names the solver must complete. */
    forcedEpithets: string[]
    /** Per-turn manual locks (turn string -> race name or train sentinel). */
    manualLocks: Record<string, string>
    /** Solver weights, defaults filled in. */
    weights: WeightsMap
}

/**
 * Parse the five JSON-string solver settings into memoised objects, filling defaults and swallowing malformed JSON. Shared by the SRS page and the Schedule screen so their
 * preview inputs can't drift. Each field is memoised on its own raw string so an unrelated settings change never re-parses the others.
 * @param racing The merged racing settings slice holding the raw JSON strings.
 * @returns The parsed solver inputs.
 */
export function useSolverInputs(racing: SolverInputSettings): SolverInputs {
    const aptitudes = useMemo<AptitudeMap>(() => {
        try {
            return { ...DEFAULT_APTITUDES, ...JSON.parse(racing.smartRaceSolverAptitudes || "{}") }
        } catch {
            return DEFAULT_APTITUDES
        }
    }, [racing.smartRaceSolverAptitudes])

    const targetEpithets = useMemo<string[]>(() => {
        try {
            return JSON.parse(racing.smartRaceSolverTargetEpithets || "[]")
        } catch {
            return []
        }
    }, [racing.smartRaceSolverTargetEpithets])

    const forcedEpithets = useMemo<string[]>(() => {
        try {
            return JSON.parse(racing.smartRaceSolverForcedEpithets || "[]")
        } catch {
            return []
        }
    }, [racing.smartRaceSolverForcedEpithets])

    const manualLocks = useMemo<Record<string, string>>(() => {
        try {
            return JSON.parse(racing.smartRaceSolverManualLocks || "{}")
        } catch {
            return {}
        }
    }, [racing.smartRaceSolverManualLocks])

    const weights = useMemo<WeightsMap>(() => {
        try {
            return { ...DEFAULT_WEIGHTS, ...JSON.parse(racing.smartRaceSolverWeights || "{}") }
        } catch {
            return DEFAULT_WEIGHTS
        }
    }, [racing.smartRaceSolverWeights])

    return { aptitudes, targetEpithets, forcedEpithets, manualLocks, weights }
}
