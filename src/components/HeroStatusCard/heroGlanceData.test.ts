import { findActiveDebugTest, activeSkillPlans, abbreviateStatPriority, heroGlanceHasContent } from "./heroGlanceData"

describe("findActiveDebugTest", () => {
    it("returns null when no test flag is armed", () => {
        expect(findActiveDebugTest({ enableDebugMode: true, debugMode_startRainbowDetectionTest: false })).toBeNull()
    })

    it("derives a readable name from the armed test key", () => {
        expect(findActiveDebugTest({ debugMode_startRainbowDetectionTest: true })).toBe("Rainbow Detection")
        expect(findActiveDebugTest({ debugMode_startTrackblazerRaceSelectionTest: true })).toBe("Trackblazer Race Selection")
        expect(findActiveDebugTest({ debugMode_startSingleTrainingOCRTest: true })).toBe("Single Training OCR")
        expect(findActiveDebugTest({ debugMode_startScrollBarDetectionTest: true })).toBe("Scroll Bar Detection")
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

describe("heroGlanceHasContent", () => {
    const empty = { debugMode: false, activeTest: null, srs: false, planNames: [] as string[], priority: [] as string[] }

    it("is false when nothing is active", () => {
        expect(heroGlanceHasContent(empty)).toBe(false)
    })

    it("is true when any single piece is present", () => {
        expect(heroGlanceHasContent({ ...empty, debugMode: true })).toBe(true)
        expect(heroGlanceHasContent({ ...empty, activeTest: "Rainbow Detection" })).toBe(true)
        expect(heroGlanceHasContent({ ...empty, srs: true })).toBe(true)
        expect(heroGlanceHasContent({ ...empty, planNames: ["Pre-Finals"] })).toBe(true)
        expect(heroGlanceHasContent({ ...empty, priority: ["SPD"] })).toBe(true)
    })
})
