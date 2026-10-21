import { buildRaceOptionsByTurn, buildAllowedEpithetNames, EPITHETS_BY_NAME } from "../raceEditing"
import { DEFAULT_APTITUDES, DEFAULT_WEIGHTS } from "../../../lib/solver/constants"

test("buildRaceOptionsByTurn buckets races by turn with suited races first", () => {
    const byTurn = buildRaceOptionsByTurn(DEFAULT_APTITUDES, DEFAULT_WEIGHTS)
    expect(byTurn.size).toBeGreaterThan(0)
    for (const list of byTurn.values()) {
        expect(list.length).toBeGreaterThan(0)
        // every race in a bucket shares that turn
        const t = list[0].race.turnNumber
        expect(list.every((o) => o.race.turnNumber === t)).toBe(true)
        // no suited race sits below a poor-aptitude one
        const firstPoor = list.findIndex((o) => o.poorAptitude)
        if (firstPoor >= 0) expect(list.slice(firstPoor).every((o) => o.poorAptitude)).toBe(true)
    }
})

test("buildRaceOptionsByTurn keeps every race, tagging the ones below the aptitude threshold", () => {
    const allA = buildRaceOptionsByTurn(DEFAULT_APTITUDES, DEFAULT_WEIGHTS)
    const noDirt = buildRaceOptionsByTurn({ ...DEFAULT_APTITUDES, Dirt: "G" }, DEFAULT_WEIGHTS)
    const count = (m: typeof allA) => [...m.values()].reduce((n, list) => n + list.length, 0)
    expect(count(noDirt)).toBe(count(allA))
    const dirtRaces = [...noDirt.values()].flat().filter((o) => o.race.terrain === "Dirt")
    expect(dirtRaces.length).toBeGreaterThan(0)
    expect(dirtRaces.every((o) => o.poorAptitude)).toBe(true)
})

test("buildRaceOptionsByTurn does not tag OP races as poor aptitude when OP is excluded from the solver", () => {
    const byTurn = buildRaceOptionsByTurn(DEFAULT_APTITUDES, { ...DEFAULT_WEIGHTS, includeOpAndPreOp: false })
    const op = [...byTurn.values()].flat().filter((o) => o.race.grade === "OP" && o.race.terrain === "Turf")
    expect(op.length).toBeGreaterThan(0)
    expect(op.every((o) => !o.poorAptitude)).toBe(true)
})

test("buildAllowedEpithetNames returns a non-empty subset of all epithets", () => {
    const names = buildAllowedEpithetNames("Trackblazer", "")
    expect(names.size).toBeGreaterThan(0)
    expect(names.size).toBeLessThanOrEqual(EPITHETS_BY_NAME.size)
})
