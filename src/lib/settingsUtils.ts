import { logWithTimestamp } from "./logger"

/**
 * Deep merges two objects, preserving nested structure.
 * @param target - The target object to merge into.
 * @param source - The source object to merge from.
 * @returns A new object with merged values from both target and source.
 */
export const deepMerge = <T extends Record<string, any>>(target: T, source: Partial<T>): T => {
    const output = { ...target }
    for (const key in source) {
        if (source[key] && typeof source[key] === "object" && !Array.isArray(source[key]) && source[key] !== null) {
            output[key] = deepMerge((target[key] || {}) as Record<string, any>, source[key] as any) as T[Extract<keyof T, string>]
        } else if (source[key] !== undefined) {
            output[key] = source[key] as T[Extract<keyof T, string>]
        }
    }
    return output
}

/**
 * Persisted settings whose canonical owner is *not* the React-state `Settings` object. They live
 * in SQLite and are read directly by Kotlin (or by a dedicated JS writer) — round-tripping them
 * through React state inflates re-renders and the auto-save batch by hundreds of KB on a populated
 * profile.
 *
 * Each entry is filtered out on **both** directions:
 * - Save path (`convertSettingsToBatch`): never re-write rows React shouldn't own.
 * - Load path (`stripDbOwnedKeys` invoked by `loadSettings`): never absorb them into React state.
 *
 * Categories of entries:
 * - `misc.formattedSettingsString` — built and persisted directly by `MessageLog`'s debounced effect.
 * - `racing.{racesData,epithetsData,characterPresetsData}` — bundled JSON assets for the Smart Race
 *   Solver, written once at bootstrap by `populateSolverData`; only consumed by Kotlin via
 *   `SettingsHelper.getStringSetting`.
 * - `racing.racingPlanData` — bundled racing plan blob written by the racing-plan generator;
 *   only consumed by Kotlin via `SettingsHelper.getStringSetting`.
 * - `trainingEvent.{characterEventData,supportEventData,scenarioEventData}` — bundled JSON event data
 *   written once at bootstrap by `populateEventData`; only consumed by Kotlin.
 */
export const DB_OWNED_KEYS: ReadonlyArray<readonly [string, string]> = [
    ["misc", "formattedSettingsString"],
    ["racing", "racesData"],
    ["racing", "epithetsData"],
    ["racing", "characterPresetsData"],
    ["racing", "characterObjectivesData"],
    ["racing", "racingPlanData"],
    ["trainingEvent", "characterEventData"],
    ["trainingEvent", "supportEventData"],
    ["trainingEvent", "scenarioEventData"],
]

const isDbOwned = (category: string, key: string): boolean => DB_OWNED_KEYS.some(([c, k]) => c === category && k === key)

/**
 * Returns a per-category shallow copy of `settings` with all `DB_OWNED_KEYS` removed. Used on the
 * load path to keep large Kotlin-owned blobs out of React state.
 *
 * @param settings - The nested `category -> key -> value` map returned by `loadAllSettings`.
 * @returns A new map with the same structure, minus DB-owned keys.
 */
export const stripDbOwnedKeys = (settings: Record<string, any>): Record<string, any> => {
    const out: Record<string, any> = {}
    for (const [category, categorySettings] of Object.entries(settings)) {
        if (!categorySettings || typeof categorySettings !== "object") {
            out[category] = categorySettings
            continue
        }
        const filtered: Record<string, any> = {}
        for (const [key, value] of Object.entries(categorySettings as Record<string, any>)) {
            if (isDbOwned(category, key)) continue
            filtered[key] = value
        }
        out[category] = filtered
    }
    return out
}

/**
 * Converts `Settings` object to database batch format.
 *
 * @param settings - The `Settings` object to convert.
 * @returns An array of objects in the format `{ category: string; key: string; value: any }`.
 */
export const convertSettingsToBatch = (settings: Record<string, any>) => {
    const batch: Array<{ category: string; key: string; value: any }> = []

    Object.entries(settings).forEach(([category, categorySettings]) => {
        Object.entries(categorySettings).forEach(([key, value]) => {
            if (isDbOwned(category, key)) return
            batch.push({ category, key, value })
        })
    })

    return batch
}

/**
 * Applies all registered migrations to the Settings object.
 * @param settings - The Settings object to apply migrations to (already merged with defaults).
 * @param rawSettings - Optional raw settings (pre-merge) used to detect fields that were absent in the persisted store.
 *   Required by migrations that need to distinguish "user never set this" from "user set this to the default value".
 * @returns An object containing the migrated Settings object and a boolean indicating whether any migrations were applied.
 */
export const applyMigrations = (settings: any, rawSettings?: any): { settings: any; anyMigrated: boolean } => {
    let anyMigrated = false
    let migratedSettings = settings

    /**
     * Marks that at least one migration ran and logs it under the shared SettingsManager prefix.
     *
     * @param message Human-readable description of the migration that was applied.
     */
    const markMigrated = (message: string): void => {
        anyMigrated = true
        logWithTimestamp(`[SettingsManager] ${message}`)
    }

    // Migration: Move Training Event specific OCR settings to trainingEvent category.
    const ocr = (migratedSettings as any).ocr
    const debug = (migratedSettings as any).debug

    if (ocr?.ocrConfidence !== undefined) {
        migratedSettings.trainingEvent.ocrConfidence = ocr.ocrConfidence
        delete ocr.ocrConfidence
        markMigrated("Migrated ocrConfidence to trainingEvent category.")
    }

    if (ocr?.enableAutomaticOCRRetry !== undefined) {
        migratedSettings.trainingEvent.enableAutomaticOCRRetry = ocr.enableAutomaticOCRRetry
        delete ocr.enableAutomaticOCRRetry
        markMigrated("Migrated enableAutomaticOCRRetry to trainingEvent category.")
    }

    if (debug?.enableHideOCRComparisonResults !== undefined) {
        migratedSettings.trainingEvent.enableHideOCRComparisonResults = debug.enableHideOCRComparisonResults
        delete debug.enableHideOCRComparisonResults
        markMigrated("Migrated enableHideOCRComparisonResults to trainingEvent category.")
    }

    if (ocr?.ocrThreshold !== undefined) {
        migratedSettings.debug.ocrThreshold = ocr.ocrThreshold
        delete ocr.ocrThreshold
        markMigrated("Migrated ocrThreshold to debug category.")
    }

    // Migration: Move enableMessageIdDisplay and overlayButtonSizeDP from misc to debug category.
    const misc = (migratedSettings as any).misc
    if (misc?.enableMessageIdDisplay !== undefined) {
        migratedSettings.debug.enableMessageIdDisplay = misc.enableMessageIdDisplay
        delete misc.enableMessageIdDisplay
        markMigrated("Migrated enableMessageIdDisplay to debug category.")
    }
    if (misc?.overlayButtonSizeDP !== undefined) {
        migratedSettings.debug.overlayButtonSizeDP = misc.overlayButtonSizeDP
        delete misc.overlayButtonSizeDP
        markMigrated("Migrated overlayButtonSizeDP to debug category.")
    }

    // After moving all OCR settings, delete the empty ocr object.
    if (migratedSettings && (migratedSettings as any).ocr && Object.keys((migratedSettings as any).ocr).length === 0) {
        delete (migratedSettings as any).ocr
    }

    // Migration: Mirror statPrioritization into eventChoiceStatPriority and summerTrainingStatPriority for users
    // upgrading from a version that only had a single stat priority list. The new keys are absent in the persisted
    // settings, so deepMerge fills them with the canonical default — but we want them to match the user's main list.
    const rawTraining = rawSettings?.training as any
    const training = migratedSettings.training as any
    if (training && rawTraining) {
        if (rawTraining.eventChoiceStatPriority === undefined && Array.isArray(training.statPrioritization)) {
            training.eventChoiceStatPriority = [...training.statPrioritization]
            markMigrated("Mirrored statPrioritization into eventChoiceStatPriority for upgrade.")
        }
        if (rawTraining.summerTrainingStatPriority === undefined && Array.isArray(training.statPrioritization)) {
            training.summerTrainingStatPriority = [...training.statPrioritization]
            markMigrated("Mirrored statPrioritization into summerTrainingStatPriority for upgrade.")
        }
    }

    // Migration: Convert single stopAtDate string to stopAtDates array.
    const general = migratedSettings.general as any
    if (general?.stopAtDate !== undefined && typeof general.stopAtDate === "string") {
        migratedSettings.general.stopAtDates = [general.stopAtDate]
        delete general.stopAtDate
        markMigrated("Migrated stopAtDate to stopAtDates array.")
    }

    // Migration: Drop the removed enablePopupCheck setting.
    if (general?.enablePopupCheck !== undefined) {
        delete general.enablePopupCheck
        markMigrated("Dropped removed setting enablePopupCheck.")
    }

    // Migration: Drop the removed fan-farming cadence settings. The Smart Race Solver is now the only source of discretionary extra races.
    const racing = migratedSettings.racing as any
    if (racing?.enableFarmingFans !== undefined) {
        delete racing.enableFarmingFans
        markMigrated("Dropped removed setting enableFarmingFans.")
    }
    if (racing?.daysToRunExtraRaces !== undefined) {
        delete racing.daysToRunExtraRaces
        markMigrated("Dropped removed setting daysToRunExtraRaces.")
    }

    // Migration: Rename the Crane Game setting to Claw Machine.
    if (general?.enableCraneGameAttempt !== undefined) {
        migratedSettings.general.enableClawMachineAttempt = general.enableCraneGameAttempt
        delete general.enableCraneGameAttempt
        markMigrated("Migrated enableCraneGameAttempt to enableClawMachineAttempt.")
    }

    // Migration: Rename Trackblazer Charm and Item Floor settings to behavior-first names.
    // The old keys (trackblazerMinStatGainForCharm, trackblazerLowMainStatGainItemFloor) are replaced by
    // trackblazerSkipRiskyCharmTrainingBelowGain and trackblazerSkipBadMoodItemsBelowGain respectively.
    const scenarioOverrides = migratedSettings.scenarioOverrides as any
    const rawScenarioOverrides = rawSettings?.scenarioOverrides as any
    if (scenarioOverrides && rawScenarioOverrides) {
        if (rawScenarioOverrides.trackblazerMinStatGainForCharm !== undefined) {
            scenarioOverrides.trackblazerSkipRiskyCharmTrainingBelowGain = rawScenarioOverrides.trackblazerMinStatGainForCharm
            delete scenarioOverrides.trackblazerMinStatGainForCharm
            markMigrated("Migrated trackblazerMinStatGainForCharm to trackblazerSkipRiskyCharmTrainingBelowGain.")
        }
        if (rawScenarioOverrides.trackblazerLowMainStatGainItemFloor !== undefined) {
            scenarioOverrides.trackblazerSkipBadMoodItemsBelowGain = rawScenarioOverrides.trackblazerLowMainStatGainItemFloor
            delete scenarioOverrides.trackblazerLowMainStatGainItemFloor
            markMigrated("Migrated trackblazerLowMainStatGainItemFloor to trackblazerSkipBadMoodItemsBelowGain.")
        }
    }

    return { settings: migratedSettings, anyMigrated }
}
