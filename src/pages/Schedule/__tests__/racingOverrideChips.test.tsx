import { RACING_OVERRIDES } from "../components/RacingOverrideChips"
import searchConfig from "../../../context/searchConfig"
import { defaultSettings } from "../../../context/BotStateContext"

describe("RACING_OVERRIDES", () => {
    it("every targetId points at a real Racing Settings search entry", () => {
        const racingIds = new Set(searchConfig.filter((item) => item.page === "RacingSettings").map((item) => item.id))
        const dangling = RACING_OVERRIDES.filter((override) => !racingIds.has(override.targetId)).map((override) => `${override.label} -> "${override.targetId}"`)
        expect(dangling).toEqual([])
    })

    it("every key is a real racing setting that defaults to off", () => {
        for (const override of RACING_OVERRIDES) {
            expect(defaultSettings.racing).toHaveProperty(override.key)
            expect(defaultSettings.racing[override.key]).toBe(false)
        }
    })

    it("has no duplicate keys or labels", () => {
        expect(new Set(RACING_OVERRIDES.map((override) => override.key)).size).toBe(RACING_OVERRIDES.length)
        expect(new Set(RACING_OVERRIDES.map((override) => override.label)).size).toBe(RACING_OVERRIDES.length)
    })
})
