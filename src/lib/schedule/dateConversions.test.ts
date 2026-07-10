import { turnToStopDate, stopDateToTurn } from "./dateConversions"

describe("turnToStopDate / stopDateToTurn", () => {
    it("maps the known anchor turns", () => {
        expect(turnToStopDate(1)).toBe("Junior January Early")
        expect(turnToStopDate(25)).toBe("Classic January Early")
        expect(turnToStopDate(49)).toBe("Senior January Early")
        expect(turnToStopDate(72)).toBe("Senior December Late")
    })

    it("round-trips every turn 1-72", () => {
        for (let turn = 1; turn <= 72; turn++) {
            const str = turnToStopDate(turn)
            expect(str).not.toBeNull()
            expect(stopDateToTurn(str as string)).toBe(turn)
        }
    })

    it("returns null for out-of-range turns", () => {
        expect(turnToStopDate(0)).toBeNull()
        expect(turnToStopDate(73)).toBeNull()
    })

    it("returns null for malformed strings", () => {
        expect(stopDateToTurn("Senior January")).toBeNull()
        expect(stopDateToTurn("Senior Januarie Early")).toBeNull()
        expect(stopDateToTurn("Rookie January Early")).toBeNull()
        expect(stopDateToTurn("")).toBeNull()
    })

    it("tolerates extra whitespace", () => {
        expect(stopDateToTurn("  Senior   January   Early ")).toBe(49)
    })
})
