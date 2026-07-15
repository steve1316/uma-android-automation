import { normalizeGrade, formatGradeLabel, gradeColor, GRADE_COLORS } from "./constants"

describe("normalizeGrade", () => {
    it("folds every Pre-OP spelling onto the canonical enum key", () => {
        // races.json stores "Pre-OP" while the Kotlin solver serialises the enum name "PRE_OP". Both have to land on the same key.
        expect(normalizeGrade("Pre-OP")).toBe("PRE_OP")
        expect(normalizeGrade("PRE_OP")).toBe("PRE_OP")
        expect(normalizeGrade("PRE-OP")).toBe("PRE_OP")
        expect(normalizeGrade("PreOP")).toBe("PRE_OP")
    })

    it("leaves the plain grades untouched", () => {
        expect(normalizeGrade("G1")).toBe("G1")
        expect(normalizeGrade("OP")).toBe("OP")
    })
})

describe("formatGradeLabel", () => {
    it("renders Pre-OP readably from either spelling", () => {
        expect(formatGradeLabel("PRE_OP")).toBe("Pre-OP")
        expect(formatGradeLabel("Pre-OP")).toBe("Pre-OP")
    })

    it("covers the rest of the RaceGrade enum", () => {
        expect(formatGradeLabel("G1")).toBe("G1")
        expect(formatGradeLabel("OP")).toBe("OP")
        expect(formatGradeLabel("MAIDEN")).toBe("Maiden")
        expect(formatGradeLabel("DEBUT")).toBe("Debut")
        expect(formatGradeLabel("FINALE")).toBe("Finale")
        expect(formatGradeLabel("EX")).toBe("EX")
    })

    it("falls back to the raw grade when it is unrecognized", () => {
        expect(formatGradeLabel("WAT")).toBe("WAT")
    })
})

describe("gradeColor", () => {
    it("resolves the same color from either Pre-OP spelling", () => {
        expect(gradeColor("Pre-OP")).toBe(GRADE_COLORS.PRE_OP)
        expect(gradeColor("PRE_OP")).toBe(GRADE_COLORS.PRE_OP)
    })

    it("returns undefined for an unrecognized grade so callers can fall back", () => {
        expect(gradeColor("WAT")).toBeUndefined()
    })
})
