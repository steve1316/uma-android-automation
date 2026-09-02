// src/lib/training/scoring/__tests__/parity.test.ts
import * as fs from "fs"
import * as path from "path"
import {
    calculateMiscScore,
    calculateRawTrainingScore,
    calculateRelationshipScore,
    calculateStatEfficiencyScore,
    DEFAULT_TRAINING_SCORING_CONSTANTS,
    StatName,
    TrainingConfig,
    TrainingOption,
} from ".."

const fixturesDir = path.join(__dirname, "..", "__fixtures__")
const inputs = JSON.parse(fs.readFileSync(path.join(fixturesDir, "parity-inputs.json"), "utf8")) as Array<{
    id: string
    config: any
    training: any
}>
const expected = JSON.parse(fs.readFileSync(path.join(fixturesDir, "parity-fixtures.json"), "utf8")) as Array<{
    id: string
    statEfficiency: number
    relationship: number
    misc: number
    raw: number
}>

function hydrateConfig(raw: any): TrainingConfig {
    return {
        currentStats: raw.currentStats ?? {},
        statPrioritization: raw.statPrioritization ?? [],
        summerTrainingStatPriority: raw.summerTrainingStatPriority ?? [],
        statTargets: raw.statTargets ?? {},
        currentDate: {
            year: raw.currentDate.year,
            day: raw.currentDate.day ?? 1,
            bIsPreDebut: raw.currentDate.bIsPreDebut ?? false,
            isSummer: raw.currentDate.isSummer ?? false,
        },
        scenario: raw.scenario ?? "URA",
        enableRainbowTrainingBonus: raw.enableRainbowTrainingBonus ?? true,
        blacklist: raw.blacklist ?? [],
        disableTrainingOnMaxedStat: raw.disableTrainingOnMaxedStat ?? false,
        trainingOptions: [],
        skillHintsPerLocation: raw.skillHintsPerLocation ?? {},
        enablePrioritizeSkillHints: raw.enablePrioritizeSkillHints ?? false,
        enableTrainingLevelWeighting: raw.enableTrainingLevelWeighting ?? false,
        disableStatTargets: raw.disableStatTargets ?? false,
        enablePrioritizeNearMaxFriendship: raw.enablePrioritizeNearMaxFriendship ?? true,
        statsTrainedOverBuffer: new Set((raw.statsTrainedOverBuffer ?? []) as StatName[]),
        scoring: raw.scoring ?? DEFAULT_TRAINING_SCORING_CONSTANTS,
    }
}

function hydrateTraining(raw: any): TrainingOption {
    return {
        name: raw.name,
        statGains: raw.statGains ?? {},
        failureChance: raw.failureChance ?? 0,
        relationshipBars: raw.relationshipBars ?? [],
        numRainbow: raw.numRainbow ?? 0,
        numSkillHints: raw.numSkillHints ?? 0,
        trainingLevel: raw.trainingLevel ?? null,
    }
}

describe("training scoring parity", () => {
    test("every input has a matching fixture", () => {
        expect(expected.length).toBe(inputs.length)
        const expectedIds = new Set(expected.map((e) => e.id))
        for (const i of inputs) expect(expectedIds.has(i.id)).toBe(true)
    })

    for (const input of inputs) {
        const fix = expected.find((e) => e.id === input.id)!
        test(input.id, () => {
            const cfg = hydrateConfig(input.config)
            const tr = hydrateTraining(input.training)
            expect(calculateStatEfficiencyScore(cfg, tr)).toBeCloseTo(fix.statEfficiency, 6)
            expect(calculateRelationshipScore(cfg, tr)).toBeCloseTo(fix.relationship, 6)
            expect(calculateMiscScore(cfg, tr)).toBeCloseTo(fix.misc, 6)
            expect(calculateRawTrainingScore(cfg, tr)).toBeCloseTo(fix.raw, 6)
        })
    }
})
