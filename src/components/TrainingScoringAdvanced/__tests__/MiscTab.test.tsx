import { SCORING_CONSTANTS_CATALOG, ScoringConstantEntry } from "../../../lib/training/scoringConstantsCatalog"
import { groupBySubgroup } from "../MiscTab"

describe("MiscTab.groupBySubgroup", () => {
    test("returns the five sub-groups in fixed order", () => {
        const all = SCORING_CONSTANTS_CATALOG.filter((e) => e.group === "misc")
        const groups = groupBySubgroup(all)
        expect(groups.map((g) => g.subgroup)).toEqual(["rel", "misc", "rainbow", "anticipatory", "unityCup"])
    })

    test("REL sub-group contains all six relationship bar entries", () => {
        const all = SCORING_CONSTANTS_CATALOG.filter((e) => e.group === "misc")
        const rel = groupBySubgroup(all).find((g) => g.subgroup === "rel")!
        const keys = rel.entries.map((e) => e.key).sort()
        expect(keys).toEqual([
            "relationshipBlueValue",
            "relationshipDiminishingFactor",
            "relationshipEarlyGameBonus",
            "relationshipGreenValue",
            "relationshipOrangeValue",
            "relationshipTrainerSupportBonus",
        ])
    })

    test("Unity Cup sub-group contains the scenario-specific entries", () => {
        const all = SCORING_CONSTANTS_CATALOG.filter((e) => e.group === "misc")
        const unity = groupBySubgroup(all).find((g) => g.subgroup === "unityCup")!
        const keys = unity.entries.map((e) => e.key).sort()
        expect(keys).toEqual(["juniorEarlyGameFlatBonus", "rainbowPerInstanceBase", "rainbowPerInstanceDecay", "relationshipScale", "unityFillBaseBonus", "unityFillPerGaugeBonus"])
    })

    test("entries without a subgroup are dropped", () => {
        const stub: ScoringConstantEntry = {
            key: "test",
            label: "test",
            description: "test",
            group: "misc",
            defaultValue: 0,
            min: 0,
            max: 1,
            step: 0.1,
        }
        const groups = groupBySubgroup([stub])
        const total = groups.reduce((acc, g) => acc + g.entries.length, 0)
        expect(total).toBe(0)
    })

    test("preserves catalog source order within a sub-group", () => {
        const all = SCORING_CONSTANTS_CATALOG.filter((e) => e.group === "misc")
        const grouped = groupBySubgroup(all)
        for (const { entries } of grouped) {
            const indices = entries.map((e) => SCORING_CONSTANTS_CATALOG.indexOf(e))
            const sorted = [...indices].sort((a, b) => a - b)
            expect(indices).toEqual(sorted)
        }
    })
})
