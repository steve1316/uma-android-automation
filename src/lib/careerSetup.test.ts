import { smartSparks, solverPresetFor } from "./careerSetup"

/**
 * Builds aptitudes from the game's ten-grade string.
 * @param grades Turf, Dirt, Sprint, Mile, Medium, Long, Front, Pace, Late, End as one letter each.
 * @returns The aptitudes keyed like the catalog.
 */
const aptitudes = (grades: string): Record<string, string> => Object.fromEntries(["turf", "dirt", "sprint", "mile", "medium", "long", "front", "pace", "late", "end"].map((k, i) => [k, grades[i]]))

/**
 * Builds growth bonuses.
 * @param values Speed, Stamina, Power, Guts and Wit bonuses in percent.
 * @returns The growth keyed like the catalog.
 */
const growth = (...values: number[]): Record<string, number> => Object.fromEntries(["speed", "stamina", "power", "guts", "wit"].map((k, i) => [k, values[i]]))

// These mirror the Smart sparks tests in SparksTest.kt so the settings preview always shows what the bot will use.
test("Inari One [Edomurasaki] wants Mile, then Speed, Stamina and Power", () => {
    expect(smartSparks(aptitudes("AAFBAAGBBA"), growth(0, 10, 20, 0, 0))).toEqual(["MILE", "SPD", "STA", "PWR"])
})

test("a trainee with a Long goal race wants Stamina ahead of Speed", () => {
    expect(smartSparks(aptitudes("AAFBAAGBBA"), growth(0, 10, 20, 0, 0), true)).toEqual(["MILE", "STA", "SPD", "PWR"])
})

test("a Long goal does not move Stamina first for a trainee too weak at Long to win it", () => {
    expect(smartSparks(aptitudes("GAABGGGGAB"), growth(0, 0, 10, 20, 0), true)).toEqual(["MILE", "SPD", "GUTS", "PWR"])
})

test("a dirt sprinter skips Stamina and follows its growth bonuses", () => {
    expect(smartSparks(aptitudes("GAABGGGGAB"), growth(0, 0, 10, 20, 0))).toEqual(["MILE", "SPD", "GUTS", "PWR"])
})

test("a stayer whose best style is below A also wants that style", () => {
    expect(smartSparks(aptitudes("AGGCABCBGG"), growth(0, 20, 0, 0, 10))).toEqual(["LONG", "PACE", "SPD", "STA", "WIT", "PWR"])
})

test("a surface graded B is worth raising, one graded C or lower is not", () => {
    expect(smartSparks(aptitudes("ABGAGGAGGG"), growth(0, 0, 0, 0, 0))).toEqual(["DIRT", "SPD", "PWR"])
    expect(smartSparks(aptitudes("ACGAGGAGGG"), growth(0, 0, 0, 0, 0))).toEqual(["SPD", "PWR"])
})

test("An outfit sets the solver's character and its own distance and surface grades", () => {
    const outfit = {
        name: "Inari One",
        aptitudes: { turf: "A", dirt: "A", sprint: "F", mile: "B", medium: "A", long: "A", front: "G", pace: "B", late: "B", end: "A" },
    }
    const preset = solverPresetFor(outfit)
    expect(preset.smartRaceSolverCharacterPreset).toBe("Inari One")
    expect(JSON.parse(preset.smartRaceSolverAptitudes)).toEqual({ Sprint: "F", Mile: "B", Medium: "A", Long: "A", Turf: "A", Dirt: "A" })
})
