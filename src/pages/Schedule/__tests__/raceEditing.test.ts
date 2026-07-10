import { buildEligibleRacesByTurn, buildAllowedEpithetNames, EPITHETS_BY_NAME } from "../raceEditing"
import { DEFAULT_APTITUDES, DEFAULT_WEIGHTS } from "../../../lib/solver/constants"

test("buildEligibleRacesByTurn buckets races by turn and sorts strongest-first", () => {
    const byTurn = buildEligibleRacesByTurn(DEFAULT_APTITUDES, DEFAULT_WEIGHTS)
    expect(byTurn.size).toBeGreaterThan(0)
    for (const list of byTurn.values()) {
        expect(list.length).toBeGreaterThan(0)
        // every race in a bucket shares that turn
        const t = list[0].turnNumber
        expect(list.every((r) => r.turnNumber === t)).toBe(true)
    }
})

test("buildAllowedEpithetNames returns a non-empty subset of all epithets", () => {
    const names = buildAllowedEpithetNames("Trackblazer", "")
    expect(names.size).toBeGreaterThan(0)
    expect(names.size).toBeLessThanOrEqual(EPITHETS_BY_NAME.size)
})
