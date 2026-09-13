import { ALL_STAT_NAMES, BarFillResult, DEFAULT_TRAINING_SCORING_CONSTANTS, StatName, TrainingConfig, TrainingOption, TrainingScoringConstants } from "../../lib/training/scoring"
import { SandboxScenario } from "./scenarioState"

const TIER_FILL_PERCENT: Record<"blue" | "green" | "orange", number> = { blue: 0, green: 50, orange: 100 }

const STAT_NAME_BY_LABEL: Record<string, StatName> = {
    speed: StatName.SPEED,
    stamina: StatName.STAMINA,
    power: StatName.POWER,
    guts: StatName.GUTS,
    wit: StatName.WIT,
}

/**
 * Map a settings-shaped stat label list (e.g. `["Speed", "Power"]`) to the matching `StatName` enum values, dropping anything that doesn't parse. The match is case-insensitive
 * so settings using "Speed" or "SPEED" both resolve correctly.
 *
 * @param labels Stat names as stored in the settings slice.
 * @returns Equivalent `StatName[]`.
 */
function statNamesFromLabels(labels: readonly string[]): StatName[] {
    const out: StatName[] = []
    for (const label of labels) {
        const mapped = STAT_NAME_BY_LABEL[label.toLowerCase()]
        if (mapped !== undefined) out.push(mapped)
    }
    return out
}

function buildBars(friendBars: SandboxScenario["trainings"][StatName]["friendBars"]): BarFillResult[] {
    const out: BarFillResult[] = []
    for (const tier of ["blue", "green", "orange"] as const) {
        for (let i = 0; i < friendBars[tier]; i += 1) {
            out.push({ dominantColor: tier, fillPercent: TIER_FILL_PERCENT[tier], isTrainerSupport: false })
        }
    }
    return out
}

/** Subset of saved settings the sandbox consumes when hydrating a scenario into a `TrainingConfig`. */
export interface SandboxSettingsInputs {
    /** Stat priority list as stored in settings (e.g. `["Speed", "Stamina", ...]`). */
    statPrioritization: readonly string[]
    /** Summer priority list as stored in settings. Activated when `scenario.summer` is true. */
    summerTrainingStatPriority: readonly string[]
    /** Training blacklist as stored in settings. Stats listed here are filtered out by the scoring pipeline. */
    trainingBlacklist: readonly string[]
    /** Currently-selected scenario name (e.g. `"URA"`, `"Unity Cup"`, `"Trackblazer"`). */
    scenario: string
    /** Rainbow bonus toggle. */
    enableRainbowTrainingBonus: boolean
    /** Skip trainings whose primary stat is already maxed past the buffer. */
    disableTrainingOnMaxedStat: boolean
    /** Boost skill-hint-bearing trainings. */
    enablePrioritizeSkillHints: boolean
    /** Apply training-level weighting to rank-1/rank-2 priority stats. */
    enableTrainingLevelWeighting: boolean
    /** Skip the ratio-bucket logic entirely (treat every stat as below target). */
    disableStatTargets: boolean
    /** Add the anticipatory bonus for near-full non-rainbow bars. */
    enablePrioritizeNearMaxFriendship: boolean
}

const DEFAULT_PRIORITY: StatName[] = [StatName.SPEED, StatName.STAMINA, StatName.POWER, StatName.GUTS, StatName.WIT]
const DEFAULT_SUMMER_PRIORITY: StatName[] = [StatName.WIT, StatName.SPEED, StatName.STAMINA, StatName.POWER, StatName.GUTS]

/** Hydrated scoring inputs for one scenario. */
export interface ScenarioScoringInputs {
    /** The shared `TrainingConfig` consumed by every per-training score call. */
    config: TrainingConfig
    /** The 5 trainings, ready to be passed into `calculateRawTrainingScore`. */
    trainings: TrainingOption[]
}

/**
 * Hydrate a `SandboxScenario` into a scoring `TrainingConfig` plus 5 `TrainingOption`s ready to feed into the scoring functions exported from `src/lib/training/scoring`.
 * When `settings` is supplied, the user's saved priority lists, blacklist, scenario name, and feature toggles are threaded through. Per-distance stat targets are NOT
 * sourced from settings - the sandbox always uses a flat 1200 target so the ratio buckets are predictable.
 *
 * @param scenario The user's sandbox scenario.
 * @param constants Scoring constants to embed in the returned `config.scoring`. Defaults to `DEFAULT_TRAINING_SCORING_CONSTANTS` so existing callers work unchanged.
 * @param settings Optional saved-settings inputs. Omitted in unit tests; provided by the sandbox modal.
 * @returns Scoring inputs for the 5 trainings.
 */
export function scenarioToScoring(scenario: SandboxScenario, constants: TrainingScoringConstants = DEFAULT_TRAINING_SCORING_CONSTANTS, settings?: SandboxSettingsInputs): ScenarioScoringInputs {
    const statPrioritization = settings ? statNamesFromLabels(settings.statPrioritization) : DEFAULT_PRIORITY
    const summerTrainingStatPriority = settings ? statNamesFromLabels(settings.summerTrainingStatPriority) : DEFAULT_SUMMER_PRIORITY
    const blacklist = settings ? statNamesFromLabels(settings.trainingBlacklist) : []
    const config: TrainingConfig = {
        currentStats: { ...scenario.traineeTotals },
        statPrioritization: statPrioritization.length > 0 ? statPrioritization : DEFAULT_PRIORITY,
        summerTrainingStatPriority: summerTrainingStatPriority.length > 0 ? summerTrainingStatPriority : DEFAULT_SUMMER_PRIORITY,
        statTargets: { [StatName.SPEED]: 1200, [StatName.STAMINA]: 1200, [StatName.POWER]: 1200, [StatName.GUTS]: 1200, [StatName.WIT]: 1200 },
        currentDate: { year: scenario.year, day: 1, bIsPreDebut: false, isSummer: scenario.summer },
        scenario: settings?.scenario ?? "URA",
        enableRainbowTrainingBonus: settings?.enableRainbowTrainingBonus ?? true,
        blacklist,
        disableTrainingOnMaxedStat: settings?.disableTrainingOnMaxedStat ?? false,
        trainingOptions: [],
        skillHintsPerLocation: {},
        enablePrioritizeSkillHints: settings?.enablePrioritizeSkillHints ?? false,
        enableTrainingLevelWeighting: settings?.enableTrainingLevelWeighting ?? true,
        disableStatTargets: settings?.disableStatTargets ?? false,
        enablePrioritizeNearMaxFriendship: settings?.enablePrioritizeNearMaxFriendship ?? true,
        statsTrainedOverBuffer: new Set(),
        scoring: constants,
    }

    const trainings: TrainingOption[] = ALL_STAT_NAMES.map((name) => {
        const t = scenario.trainings[name]
        return {
            name,
            statGains: { ...t.statGains },
            failureChance: 0,
            relationshipBars: buildBars(t.friendBars),
            numRainbow: t.rainbow ? 1 : 0,
            numSkillHints: 0,
            trainingLevel: t.trainingLevel,
        }
    })

    return { config, trainings }
}
