import { createContext, useState, useMemo, useCallback } from "react"
import { startTiming } from "../lib/performanceLogger"
import racesData from "../data/races.json"
import { skillPlanSettingsPages } from "../pages/SkillPlanSettings/config"

/**
 * Configuration for an individual skill plan (e.g. preFinals, careerComplete).
 */
interface SkillPlanSettingsConfig {
    /** Whether this skill plan is enabled. */
    enabled: boolean
    /** The spending strategy for this plan. */
    strategy: string
    /** Whether to buy inherited unique skills. */
    enableBuyInheritedUniqueSkills: boolean
    /** Whether to buy negative skills. */
    enableBuyNegativeSkills: boolean
    /** The serialized skill plan data. */
    plan: string
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
        enablePopupCheck: boolean
        enableCraneGameAttempt: boolean
        enableStopBeforeFinals: boolean
        enableStopAtDate: boolean
        stopAtDates: string[]
        waitDelay: number
        dialogWaitDelay: number
    }

    // Racing settings
    racing: {
        enableFarmingFans: boolean
        ignoreConsecutiveRaceWarning: boolean
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
        enableRacingPlan: boolean
        enableMandatoryRacingPlan: boolean
        racingPlan: string
        racingPlanData: string
        minFansThreshold: number
        preferredTerrain: string
        preferredGrades: string[]
        preferredDistances: string[]
        lookAheadDays: number
        smartRacingCheckInterval: number
        juniorYearRaceStrategy: string
        originalRaceStrategy: string
        minimumQualityThreshold: number
        timeDecayFactor: number
        improvementThreshold: number
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
        specialEventOverrides: Record<string, { selectedOption: string; requiresConfirmation: boolean }>
        characterEventOverrides: Record<string, number>
        supportEventOverrides: Record<string, number>
        scenarioEventOverrides: Record<string, number>
    }

    // Misc settings
    misc: {
        enableSettingsDisplay: boolean
        formattedSettingsString: string
        enableMessageIdDisplay: boolean
        currentProfileName: string
        messageLogFontSize: number
        overlayButtonSizeDP: number
    }

    // Training settings
    training: {
        trainingBlacklist: string[]
        statPrioritization: string[]
        maximumFailureChance: number
        disableTrainingOnMaxedStat: boolean
        focusOnSparkStatTarget: string[]
        enableRainbowTrainingBonus: boolean
        preferredDistanceOverride: string
        mustRestBeforeSummer: boolean
        enableRiskyTraining: boolean
        riskyTrainingMinStatGain: number
        riskyTrainingMaxFailureChance: number
        trainWitDuringFinale: boolean
        enablePrioritizeSkillHints: boolean
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
    }

    // Discord settings
    discord: {
        enableDiscordNotifications: boolean
        discordToken: string
        discordUserID: string
    }

    // Scenario specific overrides
    scenarioOverrides: {
        trackblazerConsecutiveRacesLimit: number
        trackblazerEnergyThreshold: number
        trackblazerShopCheckGrades: string[]
        trackblazerMinStatGainForCharm: number
        trackblazerMaxRetriesPerRace: number
        trackblazerWhistleForcesTraining: boolean
        trackblazerRetryRacesBeforeFinalGrades: string[]
        trackblazerEnableIrregularTraining: boolean
        trackblazerIrregularTrainingMinStatGain: number
        trackblazerExcludedItems: string[]
        trackblazerShopCheckFrequency: number
    }
}

// Set the default settings.
export const defaultSettings: Settings = {
    general: {
        scenario: "",
        enablePopupCheck: false,
        enableCraneGameAttempt: false,
        enableStopBeforeFinals: false,
        enableStopAtDate: false,
        stopAtDates: ["Senior January Early"],
        waitDelay: 0.5,
        dialogWaitDelay: 0.5,
    },
    racing: {
        enableFarmingFans: false,
        ignoreConsecutiveRaceWarning: false,
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
        enableRacingPlan: false,
        enableMandatoryRacingPlan: false,
        racingPlan: JSON.stringify(
            Object.values(racesData).map((race, index) => ({
                raceName: race.name,
                date: race.date,
                priority: index,
            }))
        ),
        racingPlanData: JSON.stringify(racesData),
        minFansThreshold: 0,
        preferredTerrain: "Any",
        preferredGrades: ["G1", "G2", "G3"],
        preferredDistances: ["Short", "Mile", "Medium", "Long"],
        lookAheadDays: 10,
        smartRacingCheckInterval: 2,
        juniorYearRaceStrategy: "Default",
        originalRaceStrategy: "Default",
        minimumQualityThreshold: 50.0,
        timeDecayFactor: 0.7,
        improvementThreshold: 50.0,
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
                    enableBuyInheritedUniqueSkills: false,
                    enableBuyNegativeSkills: false,
                    plan: "",
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
                selectedOption: "Option 2: Energy -5 and random stat gain",
                requiresConfirmation: false,
            },
            "Solid Showing": {
                selectedOption: "Option 2: Energy -5/-20 and random stat gain",
                requiresConfirmation: false,
            },
            Defeat: {
                selectedOption: "Option 1: Energy -25 and random stat gain",
                requiresConfirmation: false,
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
        enableMessageIdDisplay: false,
        currentProfileName: "",
        messageLogFontSize: 8,
        overlayButtonSizeDP: 40,
    },
    training: {
        trainingBlacklist: [],
        statPrioritization: ["Speed", "Stamina", "Power", "Wit", "Guts"],
        maximumFailureChance: 20,
        disableTrainingOnMaxedStat: true,
        focusOnSparkStatTarget: ["Speed", "Stamina", "Power"],
        enableRainbowTrainingBonus: false,
        preferredDistanceOverride: "Auto",
        mustRestBeforeSummer: false,
        enableRiskyTraining: false,
        riskyTrainingMinStatGain: 20,
        riskyTrainingMaxFailureChance: 30,
        trainWitDuringFinale: false,
        enablePrioritizeSkillHints: false,
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
    },
    discord: {
        enableDiscordNotifications: false,
        discordToken: "",
        discordUserID: "",
    },
    scenarioOverrides: {
        trackblazerConsecutiveRacesLimit: 5,
        trackblazerEnergyThreshold: 40,
        trackblazerShopCheckGrades: ["G1", "G2", "G3"],
        trackblazerMinStatGainForCharm: 30,
        trackblazerMaxRetriesPerRace: 1,
        trackblazerWhistleForcesTraining: true,
        trackblazerRetryRacesBeforeFinalGrades: ["G1", "G2", "G3"],
        trackblazerEnableIrregularTraining: false,
        trackblazerIrregularTrainingMinStatGain: 30,
        trackblazerExcludedItems: [],
        trackblazerShopCheckFrequency: 3,
    },
}

/**
 * Context value interface for the BotState provider.
 * Exposes application-wide state including readiness, settings, and app metadata.
 */
export interface BotStateProviderProps {
    /** Whether the bot/app is ready (initialized and settings loaded). */
    readyStatus: boolean
    /** Setter for the ready status. */
    setReadyStatus: (readyStatus: boolean) => void
    /** The default settings used for reset and comparison. */
    defaultSettings: Settings
    /** The current application settings. */
    settings: Settings
    /** Setter for the application settings. */
    setSettings: (settings: Settings | ((prev: Settings) => Settings)) => void
    /** The application name. */
    appName: string
    /** Setter for the application name. */
    setAppName: (appName: string) => void
    /** The application version string. */
    appVersion: string
    /** Setter for the application version. */
    setAppVersion: (appVersion: string) => void
}

export const BotStateContext = createContext<BotStateProviderProps>({} as BotStateProviderProps)

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

    // Memoize the provider value to prevent cascading re-renders.
    const providerValues = useMemo<BotStateProviderProps>(
        () => ({
            readyStatus,
            setReadyStatus,
            defaultSettings,
            settings,
            setSettings: setSettingsWithLogging,
            appName,
            setAppName,
            appVersion,
            setAppVersion,
        }),
        [readyStatus, settings, appName, appVersion, setSettingsWithLogging]
    )

    return <BotStateContext.Provider value={providerValues}>{children}</BotStateContext.Provider>
}
