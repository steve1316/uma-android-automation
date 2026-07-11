import { findActiveDebugTest, activeSkillPlans, abbreviateStatPriority, raceStrategyLabel, raceStrategyTargetId } from "./heroGlanceData"

describe("findActiveDebugTest", () => {
    it("returns null when no test flag is armed", () => {
        expect(findActiveDebugTest({ enableDebugMode: true, debugMode_startRainbowDetectionTest: false })).toBeNull()
    })

    it("returns the readable name and search id of the armed test", () => {
        expect(findActiveDebugTest({ debugMode_startRainbowDetectionTest: true })).toEqual({ name: "Rainbow Detection", searchId: "debug-rainbow-detection-test" })
        expect(findActiveDebugTest({ debugMode_startTrackblazerRaceSelectionTest: true })).toEqual({ name: "Trackblazer Race Selection", searchId: "debug-trackblazer-race-selection-test" })
        expect(findActiveDebugTest({ debugMode_startSingleTrainingOCRTest: true })).toEqual({ name: "Single Training OCR", searchId: "debug-single-training-ocr-test" })
        expect(findActiveDebugTest({ debugMode_startScrollBarDetectionTest: true })).toEqual({ name: "Scroll Bar Detection", searchId: "debug-scrollbar-detection-test" })
    })

    it("ignores non-test keys and non-true values", () => {
        expect(findActiveDebugTest({ enableDebugMode: true, someOtherSetting: 5 })).toBeNull()
    })
})

describe("activeSkillPlans", () => {
    it("lists enabled plans in registry order with the threshold", () => {
        const result = activeSkillPlans({ enableSkillPointCheck: true, skillPointCheck: 750, plans: { preFinals: { enabled: true }, careerComplete: { enabled: false } } })
        expect(result.names).toEqual(["Skill Point Check", "Pre-Finals"])
        expect(result.spThreshold).toBe(750)
    })

    it("omits the threshold and Skill Point Check title when it is off", () => {
        const result = activeSkillPlans({ enableSkillPointCheck: false, skillPointCheck: 750, plans: { careerComplete: { enabled: true } } })
        expect(result.names).toEqual(["Career Complete"])
        expect(result.spThreshold).toBeNull()
    })

    it("treats a missing plan as disabled", () => {
        const result = activeSkillPlans({ enableSkillPointCheck: false, plans: {} })
        expect(result.names).toEqual([])
        expect(result.spThreshold).toBeNull()
    })
})

describe("abbreviateStatPriority", () => {
    it("maps known stats in order and drops unknowns", () => {
        expect(abbreviateStatPriority(["Speed", "Stamina", "Power", "Wit", "Guts"])).toEqual(["SPD", "STA", "POW", "WIT", "GUT"])
        expect(abbreviateStatPriority([])).toEqual([])
        expect(abbreviateStatPriority(["Nonsense"])).toEqual([])
    })
})

describe("raceStrategyLabel", () => {
    it("returns the Original strategy in single-strategy mode", () => {
        expect(raceStrategyLabel({ enablePerDistanceStrategy: false, originalRaceStrategy: "Late" })).toBe("Late")
        expect(raceStrategyLabel({ enablePerDistanceStrategy: false, originalRaceStrategy: "Auto" })).toBe("Auto")
    })

    it("returns 'Per-distance' when per-distance mode is on, regardless of the stored strategy", () => {
        expect(raceStrategyLabel({ enablePerDistanceStrategy: true, originalRaceStrategy: "Front" })).toBe("Per-distance")
    })

    it("falls back to 'Default' when the Original strategy is unset", () => {
        expect(raceStrategyLabel({})).toBe("Default")
    })
})

describe("raceStrategyTargetId", () => {
    it("targets the per-distance toggle when per-distance mode is on", () => {
        expect(raceStrategyTargetId({ enablePerDistanceStrategy: true, originalRaceStrategy: "Front" })).toBe("enable-per-distance-strategy")
    })

    it("targets the original-strategy row in single-strategy mode", () => {
        expect(raceStrategyTargetId({ enablePerDistanceStrategy: false, originalRaceStrategy: "Late" })).toBe("original-race-strategy")
        expect(raceStrategyTargetId({})).toBe("original-race-strategy")
    })
})
