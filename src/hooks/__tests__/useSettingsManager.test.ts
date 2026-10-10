import { deepMerge, convertSettingsToBatch, applyMigrations } from "../../lib/settingsUtils"

// ===========================================================================
// deepMerge
// ===========================================================================

describe("deepMerge", () => {
    it("shallow merge: source overrides target", () => {
        const target = { a: 1, b: 2 }
        const source = { b: 3 }
        expect(deepMerge(target, source)).toEqual({ a: 1, b: 3 })
    })

    it("nested merge: preserves nested target keys not in source", () => {
        const target = { nested: { a: 1, b: 2 } }
        const source = { nested: { a: 10 } }
        expect(deepMerge(target, source as any)).toEqual({ nested: { a: 10, b: 2 } })
    })

    it("arrays are replaced entirely, not merged", () => {
        const target = { arr: [1, 2, 3] }
        const source = { arr: [4, 5] }
        expect(deepMerge(target, source)).toEqual({ arr: [4, 5] })
    })

    it("null in source overrides target", () => {
        const target = { a: { b: 1 } }
        const source = { a: null }
        // null is not an object, so it should override
        expect(deepMerge(target, source as any)).toEqual({ a: null })
    })

    it("undefined in source is skipped", () => {
        const target = { a: 1, b: 2 }
        const source = { a: undefined, b: 3 }
        expect(deepMerge(target, source)).toEqual({ a: 1, b: 3 })
    })

    it("empty source returns copy of target", () => {
        const target = { a: 1, b: { c: 2 } }
        const result = deepMerge(target, {})
        expect(result).toEqual({ a: 1, b: { c: 2 } })
        // Should be a new object (not same reference)
        expect(result).not.toBe(target)
    })

    it("merges 3+ levels deep", () => {
        const target = { l1: { l2: { l3: { a: 1, b: 2 } } } }
        const source = { l1: { l2: { l3: { a: 10 } } } }
        expect(deepMerge(target, source as any)).toEqual({ l1: { l2: { l3: { a: 10, b: 2 } } } })
    })

    it("adds new keys from source", () => {
        const target = { a: 1 }
        const source = { b: 2 }
        expect(deepMerge(target, source as any)).toEqual({ a: 1, b: 2 })
    })

    it("creates nested structure when target lacks the key", () => {
        const target = {} as any
        const source = { nested: { a: 1, b: 2 } }
        expect(deepMerge(target, source)).toEqual({ nested: { a: 1, b: 2 } })
    })
})

// ===========================================================================
// convertSettingsToBatch
// ===========================================================================

describe("convertSettingsToBatch", () => {
    it("converts single category with two keys to batch entries", () => {
        const settings = { general: { scenario: "URA", enableClawMachineAttempt: true } } as any
        const batch = convertSettingsToBatch(settings)
        expect(batch).toHaveLength(2)
        expect(batch).toContainEqual({ category: "general", key: "scenario", value: "URA" })
        expect(batch).toContainEqual({ category: "general", key: "enableClawMachineAttempt", value: true })
    })

    it("converts multiple categories", () => {
        const settings = {
            general: { scenario: "URA" },
            training: { maximumFailureChance: 30 },
        } as any
        const batch = convertSettingsToBatch(settings)
        expect(batch).toHaveLength(2)
        expect(batch).toContainEqual({ category: "general", key: "scenario", value: "URA" })
        expect(batch).toContainEqual({ category: "training", key: "maximumFailureChance", value: 30 })
    })

    it("handles values of different types", () => {
        const settings = {
            test: {
                str: "hello",
                num: 42,
                bool: false,
                arr: [1, 2],
                obj: { nested: true },
            },
        } as any
        const batch = convertSettingsToBatch(settings)
        expect(batch).toHaveLength(5)
    })

    it("skips misc.formattedSettingsString so MessageLog's direct DB write isn't clobbered", () => {
        const settings = {
            misc: { formattedSettingsString: "stale react-state value", currentProfileName: "p1" },
            general: { scenario: "URA" },
        } as any
        const batch = convertSettingsToBatch(settings)
        expect(batch).toContainEqual({ category: "misc", key: "currentProfileName", value: "p1" })
        expect(batch).toContainEqual({ category: "general", key: "scenario", value: "URA" })
        expect(batch.find((row) => row.category === "misc" && row.key === "formattedSettingsString")).toBeUndefined()
    })
})

// ===========================================================================
// applyMigrations
// ===========================================================================

describe("applyMigrations", () => {
    it("migrates ocrConfidence from ocr to trainingEvent", () => {
        const settings = {
            ocr: { ocrConfidence: 85 },
            trainingEvent: { ocrConfidence: 90 },
        } as any

        const { settings: migrated, anyMigrated } = applyMigrations(settings)
        expect(anyMigrated).toBe(true)
        expect(migrated.trainingEvent.ocrConfidence).toBe(85)
        expect((migrated as any).ocr?.ocrConfidence).toBeUndefined()
    })

    it("migrates enableAutomaticOCRRetry from ocr to trainingEvent", () => {
        const settings = {
            ocr: { enableAutomaticOCRRetry: false },
            trainingEvent: { enableAutomaticOCRRetry: true },
        } as any

        const { settings: migrated } = applyMigrations(settings)
        expect(migrated.trainingEvent.enableAutomaticOCRRetry).toBe(false)
    })

    it("migrates enableHideOCRComparisonResults from debug to trainingEvent", () => {
        const settings = {
            debug: { enableHideOCRComparisonResults: false },
            trainingEvent: { enableHideOCRComparisonResults: true },
        } as any

        const { settings: migrated } = applyMigrations(settings)
        expect(migrated.trainingEvent.enableHideOCRComparisonResults).toBe(false)
    })

    it("migrates ocrThreshold from ocr to debug", () => {
        const settings = {
            ocr: { ocrThreshold: 0.8 },
            debug: { ocrThreshold: 0.7 },
        } as any

        const { settings: migrated } = applyMigrations(settings)
        expect(migrated.debug.ocrThreshold).toBe(0.8)
    })

    it("deletes empty ocr object after all fields migrated", () => {
        const settings = {
            ocr: { ocrConfidence: 85 },
            trainingEvent: { ocrConfidence: 90 },
            debug: {},
        } as any

        const { settings: migrated } = applyMigrations(settings)
        expect((migrated as any).ocr).toBeUndefined()
    })

    it("migrates enableCraneGameAttempt to enableClawMachineAttempt", () => {
        const settings = {
            general: { enableCraneGameAttempt: true, enableClawMachineAttempt: false },
        } as any

        const { settings: migrated, anyMigrated } = applyMigrations(settings)
        expect(anyMigrated).toBe(true)
        expect(migrated.general.enableClawMachineAttempt).toBe(true)
        expect((migrated.general as any).enableCraneGameAttempt).toBeUndefined()
    })

    it("moves overlayButtonSizeDP from debug back to misc, where the automation library reads it", () => {
        const settings = {
            debug: { overlayButtonSizeDP: 55 },
            misc: { overlayButtonSizeDP: 40 },
        } as any

        const { settings: migrated, anyMigrated } = applyMigrations(settings)
        expect(anyMigrated).toBe(true)
        expect(migrated.misc.overlayButtonSizeDP).toBe(55)
        expect(migrated.debug.overlayButtonSizeDP).toBeUndefined()
    })

    it("leaves misc.overlayButtonSizeDP alone when debug has none", () => {
        const settings = {
            debug: {},
            misc: { overlayButtonSizeDP: 50 },
        } as any

        const { settings: migrated } = applyMigrations(settings)
        expect(migrated.misc.overlayButtonSizeDP).toBe(50)
        expect(migrated.debug.overlayButtonSizeDP).toBeUndefined()
    })

    it("keeps the user's misc overlayButtonSizeDP when a stale debug value is still stored", () => {
        const raw = { debug: { overlayButtonSizeDP: 55 }, misc: { overlayButtonSizeDP: 30 } }
        const settings = structuredClone(raw) as any

        const { settings: migrated } = applyMigrations(settings, raw)
        expect(migrated.misc.overlayButtonSizeDP).toBe(30)
        expect(migrated.debug.overlayButtonSizeDP).toBeUndefined()
    })

    it("migrates stopAtDate string to stopAtDates array", () => {
        const settings = {
            general: { stopAtDate: "Senior January Early", stopAtDates: [] },
        } as any

        const { settings: migrated, anyMigrated } = applyMigrations(settings)
        expect(anyMigrated).toBe(true)
        expect(migrated.general.stopAtDates).toEqual(["Senior January Early"])
        expect((migrated.general as any).stopAtDate).toBeUndefined()
    })

    it("returns anyMigrated=false when no migration needed", () => {
        const settings = {
            general: { stopAtDates: ["Senior January Early"] },
            trainingEvent: { ocrConfidence: 90 },
            debug: { ocrThreshold: 0.7 },
        } as any

        const { anyMigrated } = applyMigrations(settings)
        expect(anyMigrated).toBe(false)
    })

    it("is idempotent: running twice produces same result", () => {
        const settings = {
            ocr: { ocrConfidence: 85 },
            trainingEvent: { ocrConfidence: 90 },
            debug: {},
            general: { stopAtDate: "Senior January Early", stopAtDates: [] },
        } as any

        const { settings: first } = applyMigrations(settings)
        const { settings: second, anyMigrated } = applyMigrations(first)
        expect(anyMigrated).toBe(false)
        expect(second).toEqual(first)
    })
})
