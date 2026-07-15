import { splitEpithetBullets, isRewardBullet } from "./scoring"
import { EPITHETS_BY_NAME } from "./constants"

describe("isRewardBullet", () => {
    it("recognizes stat, hint, and explicitly labelled rewards", () => {
        expect(isRewardBullet("2 random stats +10")).toBe(true)
        expect(isRewardBullet("Top Pick hint +1")).toBe(true)
        expect(isRewardBullet("Reward: something")).toBe(true)
    })

    it("does not mistake a win condition for a reward", () => {
        expect(isRewardBullet("Win the Tenno Sho (Autumn)")).toBe(false)
        expect(isRewardBullet("Win 3 dirt G1 races")).toBe(false)
        expect(isRewardBullet("Trackblazer scenario only")).toBe(false)
    })
})

describe("splitEpithetBullets", () => {
    it("reports no reward when the epithet is a pure win-condition list", () => {
        // Regression: the last bullet used to be assumed to be the reward, so this read "Reward: Win the Tenno Sho (Autumn)".
        const tennoSweep = EPITHETS_BY_NAME["Tenno Sweep"]
        expect(tennoSweep).toBeDefined()
        const { reward, conditions } = splitEpithetBullets(tennoSweep.bullet_points)
        expect(reward).toBeNull()
        expect(conditions).toEqual(["Win the Tenno Sho (Spring)", "Win the Tenno Sho (Autumn)"])
    })

    it("pulls out the reward and keeps every other bullet as a condition", () => {
        const { reward, conditions } = splitEpithetBullets(["Trackblazer scenario only", "Win 5 dirt races", "2 random stats +5"])
        expect(reward).toBe("2 random stats +5")
        expect(conditions).toEqual(["Trackblazer scenario only", "Win 5 dirt races"])
    })

    it("finds the reward even when it is not the last bullet", () => {
        const { reward, conditions } = splitEpithetBullets(["Reward: Top Pick hint +1", "Win 9 dirt G1 races"])
        expect(reward).toBe("Reward: Top Pick hint +1")
        expect(conditions).toEqual(["Win 9 dirt G1 races"])
    })

    it("never labels a 'Win the ...' bullet as a reward across the whole catalog", () => {
        for (const [name, epithet] of Object.entries(EPITHETS_BY_NAME)) {
            const { reward } = splitEpithetBullets(epithet.bullet_points ?? [])
            if (reward !== null) expect(`${name}: ${reward}`).not.toMatch(/^Win /)
        }
    })

    it("handles an epithet with no bullets", () => {
        expect(splitEpithetBullets([])).toEqual({ reward: null, conditions: [] })
    })
})
