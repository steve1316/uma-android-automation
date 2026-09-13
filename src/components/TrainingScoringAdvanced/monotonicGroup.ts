// src/components/TrainingScoringAdvanced/monotonicGroup.ts
import { ScoringConstantEntry } from "../../lib/training/scoringConstantsCatalog"

/** A single propagated change as `[settingKey, newValue]`. */
export type MonotonicUpdate = [string, number]

/**
 * Compute the list of value updates needed to keep a monotonic group consistent after one entry changes.
 *
 * The `"ratio-breakpoints"` group is ascending: each entry must be >= every entry before it.
 * The `"ratio-values"` group is descending: each entry must be <= every entry before it.
 * When a change to one entry violates the invariant for a neighbor, that neighbor is pushed to the new value and propagation continues outward until the invariant holds.
 *
 * @param entries The full set of catalog entries for this tab (in catalog order).
 * @param key The settings key that was changed.
 * @param value The new value the user set.
 * @param currentValues Current values keyed by catalog key. Missing keys fall back to each entry's default.
 * @returns Ordered list of `[key, value]` updates to dispatch, always including the originating change first.
 */
export function propagateMonotonic(entries: readonly ScoringConstantEntry[], key: string, value: number, currentValues: Record<string, number>): MonotonicUpdate[] {
    const entry = entries.find((e) => e.key === key)
    if (!entry || !entry.monotonicGroup) return [[key, value]]
    const group = entries.filter((e) => e.monotonicGroup === entry.monotonicGroup)
    const idx = group.findIndex((e) => e.key === key)
    const ascending = entry.monotonicGroup === "ratio-breakpoints"
    const updates: MonotonicUpdate[] = [[key, value]]
    // Propagate forward (toward higher indices).
    for (let i = idx + 1; i < group.length; i += 1) {
        const cur = currentValues[group[i].key] ?? group[i].defaultValue
        if (ascending ? cur < value : cur > value) {
            updates.push([group[i].key, value])
        } else {
            break
        }
    }
    // Propagate backward (toward lower indices).
    for (let i = idx - 1; i >= 0; i -= 1) {
        const cur = currentValues[group[i].key] ?? group[i].defaultValue
        if (ascending ? cur > value : cur < value) {
            updates.push([group[i].key, value])
        } else {
            break
        }
    }
    return updates
}
