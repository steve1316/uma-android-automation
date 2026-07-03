import { createContext, useState, useMemo, useCallback, useContext } from "react"
import { startTiming } from "../lib/performanceLogger"
import { DATING_SCHEDULE_PRESETS } from "../lib/datingSchedule"
import { skillPlanSettingsPages } from "../pages/SkillPlanSettings/config"

/**
 * Configuration for an individual skill plan (e.g. preFinals, careerComplete).
 */
interface SkillPlanSettingsConfig {
    /** Whether this skill plan is enabled. */
    enabled: boolean
    /** The spending strategy for this plan. */
    strategy: string
    /** Whether to buy negative skills. */
    enableBuyNegativeSkills: boolean
    /** The serialized skill plan data (comma-separated skill IDs). */
    plan: string
    /** Comma-separated skill IDs that should never be purchased by this plan, even when ranked highly by a strategy. */
    blacklist: string
    /** When true, all green skills are excluded from this plan's purchases. */
    excludeGreenSkills: boolean
    /** When true, all red skills (debuffs like Intimidate, Speed Eater) are excluded from this plan's purchases. */
    excludeRedSkills: boolean
    /** When true, all inherited unique (legacy) skills are excluded from this plan's purchases, even if listed in the plan. */
    excludeUniqueSkills: boolean
    /** When true, double-circle (double-O) skills are skipped by the auto-strategy. Planned ones are still bought. */
    excludeDoubleCircleSkills: boolean
}

/**
 * The complete application settings interface.
 * Organized into category-specific sub-objects for general, racing, skills,
 * training events, misc, training, stat targets, OCR, and debug settings.
 */
export interface Settings {
    // General settings
    general: {
        scenario: string
        enableClawMachineAttempt: boolean
        enableSwipeBasedScrolling: boolean
        enableStopBeforeFinals: boolean
        enableStopAtDate: boolean
        stopAtDates: string[]
        enableDatingSchedule: boolean
        enableRecreationCatchUp: boolean
        datingSchedulePreset: string
        recreationTurns: number[]
        purePassionTurn: number
        recreationTotalOutings: number
        waitDelay: number
        dialogWaitDelay: number
    }

    // Racing settings
    racing: {
        enableFarmingFans: boolean
        ignoreConsecutiveRaceWarning: boolean
        ignoreLowEnergyRacingBlock: boolean
        daysToRunExtraRaces: number
        disableRaceRetries: boolean
        enableFreeRaceRetry: boolean
        enableCompleteCareerOnFailure: boolean
        enableStopOnMandatoryRaces: boolean
        enableForceRacing: boolean
        enableUserInGameRaceAgenda: boolean
        limitRacesToInGameAgenda: boolean
        skipSummerTrainingForAgenda: boolean
        selectedUserAgenda: string
        customAgendaTitle: string
        juniorYearRaceStrategy: string
        originalRaceStrategy: string
        enablePerDistanceStrategy: boolean
        juniorYearPerDistanceStrategies: Record<string, string>
        originalPerDistanceStrategies: Record<string, string>
        // Smart Race Solver — beam-search-based race scheduler driven by epithet completions.
        // The static bundled assets (`racesData`, `epithetsData`, `characterPresetsData`) are
        // intentionally NOT in this interface: they're written once at bootstrap by
        // `populateSolverData` and read directly from SQLite by Kotlin. Round-tripping them
        // through React state inflated re-renders by ~160 KB and made every toggle re-write the
        // blobs to SQLite via the auto-save effect.
        enableSmartRaceSolver: boolean
        disableScheduleReplanOnRaceLoss: boolean
        smartRaceSolverCharacterPreset: string
        smartRaceSolverAptitudes: string
        smartRaceSolverTargetEpithets: string
        smartRaceSolverForcedEpithets: string
        smartRaceSolverManualLocks: string
        smartRaceSolverMaxRaces: number
        smartRaceSolverMaxConsecutiveRaces: number
        smartRaceSolverWeights: string
    }

    // Skill Settings
    skills: {
        enableSkillPointCheck: boolean
        skillPointCheck: number
        preferredRunningStyle: string
        preferredTrackDistance: string
        preferredTrackSurface: string
        plans: Record<string, SkillPlanSettingsConfig>
    }

    // Training Event settings
    trainingEvent: {
        enablePrioritizeEnergyOptions: boolean
        enableAutomaticOCRRetry: boolean
        ocrConfidence: number
        enableHideOCRComparisonResults: boolean
        specialEventOverrides: Record<string, { selectedOption: string; requiresConfirmation: boolean; enableEnergyBasedSelection?: boolean }>
        characterEventOverrides: Record<string, number>
        supportEventOverrides: Record<string, number>
        scenarioEventOverrides: Record<string, number>
    }

    // Misc settings
    misc: {
        enableSettingsDisplay: boolean
        formattedSettingsString: string
        currentProfileName: string
        messageLogFontSize: number
    }

    // Training settings
    training: {
        trainingBlacklist: string[]
        statPrioritization: string[]
        eventChoiceStatPriority: string[]
        summerTrainingStatPriority: string[]
        maximumFailureChance: number
        disableTrainingOnMaxedStat: boolean
        enableRainbowTrainingBonus: boolean
        enablePrioritizeNearMaxFriendship: boolean
        preferredDistanceOverride: string
        mustRestBeforeSummer: boolean
        enableRiskyTraining: boolean
        riskyTrainingMinStatGain: number
        riskyTrainingMaxFailureChance: number
        trainWitDuringFinale: boolean
        enablePrioritizeSkillHints: boolean
        enableTrainingLevelWeighting: boolean
        disableStatTargets: boolean
        enableTrainingAnalysisValidation: boolean
        enableYoloStatDetection: boolean
        classicMilestonePercent: number
        seniorMilestonePercent: number
    }

    // Training Stat Target settings
    trainingStatTarget: {
        // Sprint
        trainingSprintStatTarget_speedStatTarget: number
        trainingSprintStatTarget_staminaStatTarget: number
        trainingSprintStatTarget_powerStatTarget: number
        trainingSprintStatTarget_gutsStatTarget: number
        trainingSprintStatTarget_witStatTarget: number

        // Mile
        trainingMileStatTarget_speedStatTarget: number
        trainingMileStatTarget_staminaStatTarget: number
        trainingMileStatTarget_powerStatTarget: number
        trainingMileStatTarget_gutsStatTarget: number
        trainingMileStatTarget_witStatTarget: number

        // Medium
        trainingMediumStatTarget_speedStatTarget: number
        trainingMediumStatTarget_staminaStatTarget: number
        trainingMediumStatTarget_powerStatTarget: number
        trainingMediumStatTarget_gutsStatTarget: number
        trainingMediumStatTarget_witStatTarget: number

        // Long
        trainingLongStatTarget_speedStatTarget: number
        trainingLongStatTarget_staminaStatTarget: number
        trainingLongStatTarget_powerStatTarget: number
        trainingLongStatTarget_gutsStatTarget: number
        trainingLongStatTarget_witStatTarget: number
    }

    // Debug settings
    debug: {
        enableDebugMode: boolean
        ocrThreshold: number
        templateMatchConfidence: number
        templateMatchCustomScale: number
        debugMode_startTemplateMatchingTest: boolean
        debugMode_startSingleTrainingOCRTest: boolean
        debugMode_startComprehensiveTrainingOCRTest: boolean
        debugMode_startRaceListDetectionTest: boolean
        debugMode_startMainScreenUpdateTest: boolean
        debugMode_startSkillListBuyTest: boolean
        debugMode_startScrollBarDetectionTest: boolean
        debugMode_startTrackblazerRaceSelectionTest: boolean
        debugMode_startTrackblazerInventorySyncTest: boolean
        debugMode_startTrackblazerBuyItemsTest: boolean
        enableScreenRecording: boolean
        recordingBitRate: number
        recordingFrameRate: number
        recordingResolutionScale: number
        enableRemoteLogViewer: boolean
        remoteLogViewerPort: number
        enableMessageIdDisplay: boolean
        overlayButtonSizeDP: number
    }

    // Discord settings
    discord: {
        enableDiscordNotifications: boolean
        discordToken: string
        discordUserID: string
    }

    // On-device docs chatbot settings
    chat: {
        enableAskTheDocs: boolean
    }

    // Scenario specific overrides
    scenarioOverrides: {
        trackblazerConsecutiveRacesLimit: number
        trackblazerEnergyThreshold: number
        trackblazerForceTrainEnergyFloor: number
        trackblazerShopCheckGrades: string[]
        trackblazerSkipRiskyCharmTrainingBelowGain: number
        trackblazerSkipBadMoodItemsBelowGain: number
        trackblazerSkipEmpoweringMegaphoneBelowGain: number
        trackblazerSkipMotivatingMegaphoneBelowGain: number
        trackblazerSkipCoachingMegaphoneBelowGain: number
        trackblazerMaxRetriesPerRace: number
        trackblazerWhistleForcesTraining: boolean
        trackblazerRetryRacesBeforeFinalGrades: string[]
        trackblazerEnableIrregularTraining: boolean
        trackblazerIrregularTrainingMinStatGain: number
        trackblazerExcludedItems: string[]
        trackblazerShopCheckFrequency: number
        trackblazerPreferredDistances: string[]
        trackblazerPreferredSurfaces: string[]
        trackblazerEnergyItemReserve: number
        trackblazerCupcakeReserve: number
        trackblazerMasterHammerFinaleReserve: number
        trackblazerArtisanHammerMinStockForG3: number
        trackblazerArtisanHammerMinStockForG2: number
        trackblazerGlowStickFinalReserve: number
        trackblazerGlowStickMinFans: number
    }
}

// Set the default settings.
export const defaultSettings: Settings = {
    general: {
        scenario: "",
        enableClawMachineAttempt: false,
        enableSwipeBasedScrolling: false,
        enableStopBeforeFinals: false,
        enableStopAtDate: false,
        stopAtDates: ["Senior January Early"],
        enableDatingSchedule: false,
        enableRecreationCatchUp: true,
        datingSchedulePreset: "siriusSenior",
        recreationTurns: [...DATING_SCHEDULE_PRESETS.siriusSenior.recreationTurns],
        purePassionTurn: DATING_SCHEDULE_PRESETS.siriusSenior.purePassionTurn,
        recreationTotalOutings: DATING_SCHEDULE_PRESETS.siriusSenior.totalOutings,
        waitDelay: 0.5,
        dialogWaitDelay: 0.5,
    },
    racing: {
        enableFarmingFans: false,
        ignoreConsecutiveRaceWarning: false,
        ignoreLowEnergyRacingBlock: false,
        daysToRunExtraRaces: 5,
        disableRaceRetries: false,
        enableFreeRaceRetry: false,
        enableCompleteCareerOnFailure: false,
        enableStopOnMandatoryRaces: false,
        enableForceRacing: false,
        enableUserInGameRaceAgenda: false,
        limitRacesToInGameAgenda: true,
        skipSummerTrainingForAgenda: false,
        selectedUserAgenda: "Agenda 1",
        customAgendaTitle: "",
        juniorYearRaceStrategy: "Default",
        originalRaceStrategy: "Default",
        enablePerDistanceStrategy: false,
        juniorYearPerDistanceStrategies: { Short: "Default", Mile: "Default", Medium: "Default", Long: "Default" },
        originalPerDistanceStrategies: { Short: "Default", Mile: "Default", Medium: "Default", Long: "Default" },
        enableSmartRaceSolver: false,
        disableScheduleReplanOnRaceLoss: false,
        smartRaceSolverCharacterPreset: "Special Week",
        smartRaceSolverAptitudes: JSON.stringify({
            Sprint: "F",
            Mile: "C",
            Medium: "A",
            Long: "A",
            Turf: "A",
            Dirt: "G",
        }),
        smartRaceSolverTargetEpithets: "[]",
        smartRaceSolverForcedEpithets: "[]",
        smartRaceSolverManualLocks: "{}",
        smartRaceSolverMaxRaces: 0,
        smartRaceSolverMaxConsecutiveRaces: 3,
        smartRaceSolverWeights: JSON.stringify({
            raceValue: 1.0,
            epithetValue: 1.0,
            statWeight: 1.0,
            spWeight: 1.0,
            hintWeight: 8.0,
            consecutiveRacePenalty: 3.0,
            summerPenalty: 5.0,
            raceBonusPct: 50.0,
            raceCostPct: 100.0,
            fanWeight: 0.0,
            aptitudeThreshold: "C",
            includeOpAndPreOp: false,
            allowSummerRacing: false,
        }),
    },
    skills: {
        enableSkillPointCheck: false,
        skillPointCheck: 750,
        preferredRunningStyle: "inherit",
        preferredTrackDistance: "inherit",
        preferredTrackSurface: "no_preference",
        plans: Object.keys(skillPlanSettingsPages).reduce(
            (acc, curr) => {
                acc[curr] = {
                    enabled: false,
                    strategy: "default",
                    enableBuyNegativeSkills: false,
                    plan: "",
                    blacklist: "",
                    excludeGreenSkills: false,
                    excludeRedSkills: false,
                    excludeUniqueSkills: false,
                    excludeDoubleCircleSkills: false,
                }
                return acc
            },
            {} as Record<string, SkillPlanSettingsConfig>
        ),
    },
    trainingEvent: {
        enablePrioritizeEnergyOptions: false,
        enableAutomaticOCRRetry: true,
        ocrConfidence: 90,
        enableHideOCRComparisonResults: true,
        specialEventOverrides: {
            "New Year's Resolutions": {
                selectedOption: "Option 2: Energy +20",
                requiresConfirmation: false,
            },
            "New Year's Shrine Visit": {
                selectedOption: "Option 1: Energy +30",
                requiresConfirmation: false,
            },
            "Victory!": {
                selectedOption: "Option 2: Energy -5/-20 and random stat gain",
                requiresConfirmation: false,
                enableEnergyBasedSelection: false,
            },
            "Solid Showing": {
                selectedOption: "Option 2: Energy -5/-20 and random stat gain",
                requiresConfirmation: false,
                enableEnergyBasedSelection: false,
            },
            Defeat: {
                selectedOption: "Option 1: Energy -25 and random stat gain",
                requiresConfirmation: false,
                enableEnergyBasedSelection: false,
            },
            "Get Well Soon!": {
                selectedOption: "Option 2: (Random) Mood -1 / Stat decrease / Get Practice Poor negative status",
                requiresConfirmation: false,
            },
            "Don't Overdo It!": {
                selectedOption: "Option 2: (Random) Mood -3 / Stat decrease / Get Practice Poor negative status",
                requiresConfirmation: false,
            },
            "Extra Training": {
                selectedOption: "Option 2: Energy +5",
                requiresConfirmation: false,
            },
            "Acupuncture (Just an Acupuncturist, No Worries! ☆)": {
                selectedOption: "Option 5: Energy +10",
                requiresConfirmation: true,
            },
            "Etsuko's Exhaustive Coverage": {
                selectedOption: "Option 2: Energy Down / Gain skill points",
                requiresConfirmation: false,
            },
            "A Team at Last": {
                selectedOption: "Default",
                requiresConfirmation: false,
            },
        },
        characterEventOverrides: {},
        supportEventOverrides: {},
        scenarioEventOverrides: {},
    },
    misc: {
        enableSettingsDisplay: false,
        formattedSettingsString: "",
        currentProfileName: "",
        messageLogFontSize: 8,
    },
    training: {
        trainingBlacklist: [],
        statPrioritization: ["Speed", "Stamina", "Power", "Wit", "Guts"],
        eventChoiceStatPriority: ["Speed", "Stamina", "Power", "Wit", "Guts"],
        summerTrainingStatPriority: ["Speed", "Stamina", "Power", "Wit", "Guts"],
        maximumFailureChance: 20,
        disableTrainingOnMaxedStat: true,
        enableRainbowTrainingBonus: false,
        enablePrioritizeNearMaxFriendship: true,
        preferredDistanceOverride: "Auto",
        mustRestBeforeSummer: false,
        enableRiskyTraining: false,
        riskyTrainingMinStatGain: 20,
        riskyTrainingMaxFailureChance: 30,
        trainWitDuringFinale: false,
        enablePrioritizeSkillHints: false,
        enableTrainingLevelWeighting: true,
        disableStatTargets: false,
        enableTrainingAnalysisValidation: false,
        enableYoloStatDetection: false,
        classicMilestonePercent: 33,
        seniorMilestonePercent: 66,
    },
    trainingStatTarget: {
        trainingSprintStatTarget_speedStatTarget: 1200,
        trainingSprintStatTarget_staminaStatTarget: 450,
        trainingSprintStatTarget_powerStatTarget: 900,
        trainingSprintStatTarget_gutsStatTarget: 500,
        trainingSprintStatTarget_witStatTarget: 1200,
        trainingMileStatTarget_speedStatTarget: 1200,
        trainingMileStatTarget_staminaStatTarget: 650,
        trainingMileStatTarget_powerStatTarget: 1000,
        trainingMileStatTarget_gutsStatTarget: 400,
        trainingMileStatTarget_witStatTarget: 800,
        trainingMediumStatTarget_speedStatTarget: 1200,
        trainingMediumStatTarget_staminaStatTarget: 800,
        trainingMediumStatTarget_powerStatTarget: 900,
        trainingMediumStatTarget_gutsStatTarget: 400,
        trainingMediumStatTarget_witStatTarget: 600,
        trainingLongStatTarget_speedStatTarget: 1200,
        trainingLongStatTarget_staminaStatTarget: 1100,
        trainingLongStatTarget_powerStatTarget: 1000,
        trainingLongStatTarget_gutsStatTarget: 500,
        trainingLongStatTarget_witStatTarget: 600,
    },
    debug: {
        enableDebugMode: false,
        ocrThreshold: 230,
        templateMatchConfidence: 0.8,
        templateMatchCustomScale: 1.0,
        debugMode_startTemplateMatchingTest: false,
        debugMode_startSingleTrainingOCRTest: false,
        debugMode_startComprehensiveTrainingOCRTest: false,
        debugMode_startRaceListDetectionTest: false,
        debugMode_startMainScreenUpdateTest: false,
        debugMode_startSkillListBuyTest: false,
        debugMode_startScrollBarDetectionTest: false,
        debugMode_startTrackblazerRaceSelectionTest: false,
        debugMode_startTrackblazerInventorySyncTest: false,
        debugMode_startTrackblazerBuyItemsTest: false,
        enableScreenRecording: false,
        recordingBitRate: 6,
        recordingFrameRate: 30,
        recordingResolutionScale: 1.0,
        enableRemoteLogViewer: false,
        remoteLogViewerPort: 9000,
        enableMessageIdDisplay: false,
        overlayButtonSizeDP: 40,
    },
    discord: {
        enableDiscordNotifications: false,
        discordToken: "",
        discordUserID: "",
    },
    chat: {
        enableAskTheDocs: false,
    },
    scenarioOverrides: {
        trackblazerConsecutiveRacesLimit: 5,
        trackblazerEnergyThreshold: 40,
        trackblazerForceTrainEnergyFloor: 20,
        trackblazerShopCheckGrades: ["G1", "G2", "G3"],
        trackblazerSkipRiskyCharmTrainingBelowGain: 30,
        trackblazerSkipBadMoodItemsBelowGain: 15,
        trackblazerSkipEmpoweringMegaphoneBelowGain: 0,
        trackblazerSkipMotivatingMegaphoneBelowGain: 0,
        trackblazerSkipCoachingMegaphoneBelowGain: 0,
        trackblazerMaxRetriesPerRace: 1,
        trackblazerWhistleForcesTraining: true,
        trackblazerRetryRacesBeforeFinalGrades: ["G1", "G2", "G3"],
        trackblazerEnableIrregularTraining: false,
        trackblazerIrregularTrainingMinStatGain: 30,
        trackblazerExcludedItems: [],
        trackblazerShopCheckFrequency: 3,
        trackblazerPreferredDistances: [] as string[],
        trackblazerPreferredSurfaces: [] as string[],
        trackblazerEnergyItemReserve: 1,
        trackblazerCupcakeReserve: 1,
        trackblazerMasterHammerFinaleReserve: 2,
        trackblazerArtisanHammerMinStockForG3: 3,
        trackblazerArtisanHammerMinStockForG2: 2,
        trackblazerGlowStickFinalReserve: 1,
        trackblazerGlowStickMinFans: 20000,
    },
}

/**
 * Slice updater accepts either a partial slice (merged shallowly) or a functional updater
 * that receives the previous slice and returns the next. Functional callers always see the
 * latest slice value, eliminating stale-closure races on rapid taps.
 */
type SliceUpdater<T> = (update: Partial<T> | ((prev: T) => T)) => void

/** App metadata + readyStatus + immutable defaultSettings. Updates rarely. */
export interface BotMetaContextValue {
    readyStatus: boolean
    setReadyStatus: (readyStatus: boolean) => void
    defaultSettings: Settings
    appName: string
    setAppName: (appName: string) => void
    appVersion: string
    setAppVersion: (appVersion: string) => void
    /**
     * Bulk settings setter. Exposed here (rather than only via the legacy `BotStateContext`)
     * so cross-slice writers (e.g., profile overwrite) can mutate without subscribing to the
     * full settings object, since `setSettings` is a stable callback identity.
     */
    setSettings: (settings: Settings | ((prev: Settings) => Settings)) => void
}

export interface RacingContextValue {
    racing: Settings["racing"]
    updateRacing: SliceUpdater<Settings["racing"]>
}

export interface SkillsContextValue {
    skills: Settings["skills"]
    updateSkills: SliceUpdater<Settings["skills"]>
}

export interface TrainingContextValue {
    training: Settings["training"]
    trainingStatTarget: Settings["trainingStatTarget"]
    updateTraining: SliceUpdater<Settings["training"]>
    updateTrainingStatTarget: SliceUpdater<Settings["trainingStatTarget"]>
}

export interface TrainingEventContextValue {
    trainingEvent: Settings["trainingEvent"]
    updateTrainingEvent: SliceUpdater<Settings["trainingEvent"]>
}

export interface GeneralMiscContextValue {
    general: Settings["general"]
    misc: Settings["misc"]
    updateGeneral: SliceUpdater<Settings["general"]>
    updateMisc: SliceUpdater<Settings["misc"]>
}

export interface DebugContextValue {
    debug: Settings["debug"]
    updateDebug: SliceUpdater<Settings["debug"]>
}

export interface DiscordContextValue {
    discord: Settings["discord"]
    updateDiscord: SliceUpdater<Settings["discord"]>
}

export interface ChatContextValue {
    chat: Settings["chat"]
    updateChat: SliceUpdater<Settings["chat"]>
}

export interface ScenarioOverridesContextValue {
    scenarioOverrides: Settings["scenarioOverrides"]
    updateScenarioOverrides: SliceUpdater<Settings["scenarioOverrides"]>
}

export const BotMetaContext = createContext<BotMetaContextValue>({} as BotMetaContextValue)
export const RacingContext = createContext<RacingContextValue>({} as RacingContextValue)
export const SkillsContext = createContext<SkillsContextValue>({} as SkillsContextValue)
export const TrainingContext = createContext<TrainingContextValue>({} as TrainingContextValue)
export const TrainingEventContext = createContext<TrainingEventContextValue>({} as TrainingEventContextValue)
export const GeneralMiscContext = createContext<GeneralMiscContextValue>({} as GeneralMiscContextValue)
export const DebugContext = createContext<DebugContextValue>({} as DebugContextValue)
export const DiscordContext = createContext<DiscordContextValue>({} as DiscordContextValue)
export const ChatContext = createContext<ChatContextValue>({} as ChatContextValue)
export const ScenarioOverridesContext = createContext<ScenarioOverridesContextValue>({} as ScenarioOverridesContextValue)

/**
 * Provider component for the BotState context.
 * Manages application-wide state including readiness, settings, and metadata.
 * Settings updates are wrapped with performance timing.
 * @param children The child components to render within the provider.
 * @returns The bot state context provider.
 */
export const BotStateProvider = ({ children }: any): React.ReactElement => {
    const [readyStatus, setReadyStatus] = useState<boolean>(false)
    const [appName, setAppName] = useState<string>("")
    const [appVersion, setAppVersion] = useState<string>("")

    // Create a deep copy of default settings to avoid reference issues.
    const [settings, setSettings] = useState<Settings>(() => JSON.parse(JSON.stringify(defaultSettings)))

    /**
     * Wrapped setSettings with performance logging.
     * @param update The update to apply to the settings.
     */
    const setSettingsWithLogging = useCallback((update: Settings | ((prev: Settings) => Settings)) => {
        const endTiming = startTiming("bot_state_set_settings", "state")

        try {
            if (typeof update === "function") {
                setSettings((prev) => {
                    const newSettings = update(prev)
                    endTiming({ status: "success" })
                    return newSettings
                })
            } else {
                setSettings(update)
                endTiming({ status: "success" })
            }
        } catch (error) {
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            throw error
        }
    }, [])

    // Build a slice-aware updater. Accepts either `Partial<Slice>` (shallow-merged) or
    // `(prev) => next`. Functional updaters always read the freshest slice, avoiding
    // stale-closure races when multiple toggles fire in the same React batch.
    const makeSliceUpdater = useCallback(
        <K extends keyof Settings>(key: K): SliceUpdater<Settings[K]> =>
            (update) => {
                setSettingsWithLogging((prev) => {
                    const prevSlice = prev[key]
                    const nextSlice = typeof update === "function" ? (update as (p: Settings[K]) => Settings[K])(prevSlice) : ({ ...prevSlice, ...update } as Settings[K])
                    if (nextSlice === prevSlice) return prev
                    return { ...prev, [key]: nextSlice }
                })
            },
        [setSettingsWithLogging]
    )

    const updateRacing = useMemo(() => makeSliceUpdater("racing"), [makeSliceUpdater])
    const updateSkills = useMemo(() => makeSliceUpdater("skills"), [makeSliceUpdater])
    const updateTraining = useMemo(() => makeSliceUpdater("training"), [makeSliceUpdater])
    const updateTrainingStatTarget = useMemo(() => makeSliceUpdater("trainingStatTarget"), [makeSliceUpdater])
    const updateTrainingEvent = useMemo(() => makeSliceUpdater("trainingEvent"), [makeSliceUpdater])
    const updateGeneral = useMemo(() => makeSliceUpdater("general"), [makeSliceUpdater])
    const updateMisc = useMemo(() => makeSliceUpdater("misc"), [makeSliceUpdater])
    const updateDebug = useMemo(() => makeSliceUpdater("debug"), [makeSliceUpdater])
    const updateDiscord = useMemo(() => makeSliceUpdater("discord"), [makeSliceUpdater])
    const updateChat = useMemo(() => makeSliceUpdater("chat"), [makeSliceUpdater])
    const updateScenarioOverrides = useMemo(() => makeSliceUpdater("scenarioOverrides"), [makeSliceUpdater])

    // Per-slice values memoized on their own slice reference. An untouched slice keeps a
    // stable identity across renders, so consumers of that slice's context skip re-rendering
    // when an unrelated domain mutates.
    const metaValue = useMemo<BotMetaContextValue>(
        () => ({ readyStatus, setReadyStatus, defaultSettings, appName, setAppName, appVersion, setAppVersion, setSettings: setSettingsWithLogging }),
        [readyStatus, appName, appVersion, setSettingsWithLogging]
    )
    const racingValue = useMemo<RacingContextValue>(() => ({ racing: settings.racing, updateRacing }), [settings.racing, updateRacing])
    const skillsValue = useMemo<SkillsContextValue>(() => ({ skills: settings.skills, updateSkills }), [settings.skills, updateSkills])
    const trainingValue = useMemo<TrainingContextValue>(
        () => ({ training: settings.training, trainingStatTarget: settings.trainingStatTarget, updateTraining, updateTrainingStatTarget }),
        [settings.training, settings.trainingStatTarget, updateTraining, updateTrainingStatTarget]
    )
    const trainingEventValue = useMemo<TrainingEventContextValue>(() => ({ trainingEvent: settings.trainingEvent, updateTrainingEvent }), [settings.trainingEvent, updateTrainingEvent])
    const generalMiscValue = useMemo<GeneralMiscContextValue>(
        () => ({ general: settings.general, misc: settings.misc, updateGeneral, updateMisc }),
        [settings.general, settings.misc, updateGeneral, updateMisc]
    )
    const debugValue = useMemo<DebugContextValue>(() => ({ debug: settings.debug, updateDebug }), [settings.debug, updateDebug])
    const discordValue = useMemo<DiscordContextValue>(() => ({ discord: settings.discord, updateDiscord }), [settings.discord, updateDiscord])
    const chatValue = useMemo<ChatContextValue>(() => ({ chat: settings.chat, updateChat }), [settings.chat, updateChat])
    const scenarioOverridesValue = useMemo<ScenarioOverridesContextValue>(
        () => ({ scenarioOverrides: settings.scenarioOverrides, updateScenarioOverrides }),
        [settings.scenarioOverrides, updateScenarioOverrides]
    )

    return (
        <BotMetaContext.Provider value={metaValue}>
            <GeneralMiscContext.Provider value={generalMiscValue}>
                <RacingContext.Provider value={racingValue}>
                    <SkillsContext.Provider value={skillsValue}>
                        <TrainingContext.Provider value={trainingValue}>
                            <TrainingEventContext.Provider value={trainingEventValue}>
                                <DebugContext.Provider value={debugValue}>
                                    <DiscordContext.Provider value={discordValue}>
                                        <ChatContext.Provider value={chatValue}>
                                            <ScenarioOverridesContext.Provider value={scenarioOverridesValue}>
                                                <SettingsSnapshotPublisher />
                                                {children}
                                            </ScenarioOverridesContext.Provider>
                                        </ChatContext.Provider>
                                    </DiscordContext.Provider>
                                </DebugContext.Provider>
                            </TrainingEventContext.Provider>
                        </TrainingContext.Provider>
                    </SkillsContext.Provider>
                </RacingContext.Provider>
            </GeneralMiscContext.Provider>
        </BotMetaContext.Provider>
    )
}

/**
 * Subscribes to every slice context and returns a `Settings` snapshot. Used by the
 * three remaining full-settings consumers (`useSettingsManager`, `useSettingsFileManager`,
 * `MessageLog`'s formatted-string memo) that genuinely need cross-slice reads. The
 * returned object identity changes whenever any slice changes, mirroring the legacy
 * aggregate `BotStateContext.settings` it replaces.
 *
 * @returns A `Settings` object assembled from every slice context's current value.
 */
export const useSettingsSnapshot = (): Settings => {
    const { general, misc } = useContext(GeneralMiscContext)
    const { racing } = useContext(RacingContext)
    const { skills } = useContext(SkillsContext)
    const { training, trainingStatTarget } = useContext(TrainingContext)
    const { trainingEvent } = useContext(TrainingEventContext)
    const { debug } = useContext(DebugContext)
    const { discord } = useContext(DiscordContext)
    const { chat } = useContext(ChatContext)
    const { scenarioOverrides } = useContext(ScenarioOverridesContext)
    return useMemo(
        () => ({ general, racing, skills, trainingEvent, misc, training, trainingStatTarget, debug, discord, chat, scenarioOverrides }),
        [general, racing, skills, trainingEvent, misc, training, trainingStatTarget, debug, discord, chat, scenarioOverrides]
    )
}

/**
 * Module-level lazy getter for the latest aggregated `Settings` snapshot. Populated by the
 * mounted `BotStateProvider` (see `useSettingsSnapshotPublisher` below) and read by callers that
 * only need the value at user-action time (e.g. import / export handlers). Reading it does NOT
 * subscribe to any context, so call sites don't re-render when slices change.
 *
 * Falls back to `defaultSettings` if no provider is mounted (test environments).
 */
let _latestSettingsSnapshot: Settings = defaultSettings
export const getLatestSettingsSnapshot = (): Settings => _latestSettingsSnapshot

/**
 * Internal: publishes the live snapshot to `_latestSettingsSnapshot` so non-rendering callers
 * can read it via `getLatestSettingsSnapshot()`. Mounted once inside `BotStateProvider`.
 */
const SettingsSnapshotPublisher = (): null => {
    const snapshot = useSettingsSnapshot()
    _latestSettingsSnapshot = snapshot
    return null
}
