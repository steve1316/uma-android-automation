import { createContext, useState, useMemo, useCallback } from "react"
import { startTiming } from "../lib/performanceLogger"
import racesData from "../data/races.json"
import { skillPlanSettingsPages } from "../pages/SkillPlanSettings"

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
        waitDelay: number
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
        selectedUserAgenda: string
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
        specialEventOverrides: Record<string, { selectedOption: string; requiresConfirmation: boolean }>
        characterEventOverrides: Record<string, number>
        supportEventOverrides: Record<string, number>
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
        manualStatCap: number
        focusOnSparkStatTarget: string[]
        enableRainbowTrainingBonus: boolean
        preferredDistanceOverride: string
        mustRestBeforeSummer: boolean
        enableRiskyTraining: boolean
        riskyTrainingMinStatGain: number
        riskyTrainingMaxFailureChance: number
        trainWitDuringFinale: boolean
        enablePrioritizeSkillHints: boolean
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

    // OCR settings
    ocr: {
        ocrThreshold: number
        enableAutomaticOCRRetry: boolean
        ocrConfidence: number
    }

    // Debug settings
    debug: {
        enableDebugMode: boolean
        templateMatchConfidence: number
        templateMatchCustomScale: number
        debugMode_startTemplateMatchingTest: boolean
        debugMode_startSingleTrainingOCRTest: boolean
        debugMode_startComprehensiveTrainingOCRTest: boolean
        debugMode_startDateOCRTest: boolean
        debugMode_startRaceListDetectionTest: boolean
        debugMode_startAptitudesDetectionTest: boolean
        debugMode_startMainScreenOCRTest: boolean
        debugMode_startTrainingScreenOCRTest: boolean
        enableHideOCRComparisonResults: boolean
        enableScreenRecording: boolean
        recordingBitRate: number
        recordingFrameRate: number
        recordingResolutionScale: number
    }

    // Discord settings
    discord: {
        enableDiscordNotifications: boolean
        discordToken: string
        discordUserID: string
    }
}

// Set the default settings.
export const defaultSettings: Settings = {
    general: {
        scenario: "",
        enablePopupCheck: false,
        enableCraneGameAttempt: false,
        enableStopBeforeFinals: false,
        waitDelay: 0.5,
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
        selectedUserAgenda: "Agenda 1",
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
        minimumQualityThreshold: 70.0,
        timeDecayFactor: 0.8,
        improvementThreshold: 25.0,
    },
    skills: {
        enableSkillPointCheck: false,
        skillPointCheck: 750,
        preferredRunningStyle: "inherit",
        preferredTrackDistance: "inherit",
        preferredTrackSurface: "no_preference",
        plans: Object.keys(skillPlanSettingsPages).reduce((acc, curr) => {
            acc[curr] = {
                enabled: false,
                strategy: "default",
                enableBuyInheritedUniqueSkills: false,
                enableBuyNegativeSkills: false,
                plan: "",
            }
            return acc
        }, {} as Record<string, SkillPlanSettingsConfig>),
    },
    trainingEvent: {
        enablePrioritizeEnergyOptions: false,
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
        manualStatCap: 1200,
        focusOnSparkStatTarget: ["Speed", "Stamina", "Power"],
        enableRainbowTrainingBonus: false,
        preferredDistanceOverride: "Auto",
        mustRestBeforeSummer: false,
        enableRiskyTraining: false,
        riskyTrainingMinStatGain: 20,
        riskyTrainingMaxFailureChance: 30,
        trainWitDuringFinale: false,
        enablePrioritizeSkillHints: false,
    },
    trainingStatTarget: {
        trainingSprintStatTarget_speedStatTarget: 900,
        trainingSprintStatTarget_staminaStatTarget: 300,
        trainingSprintStatTarget_powerStatTarget: 600,
        trainingSprintStatTarget_gutsStatTarget: 300,
        trainingSprintStatTarget_witStatTarget: 300,
        trainingMileStatTarget_speedStatTarget: 900,
        trainingMileStatTarget_staminaStatTarget: 300,
        trainingMileStatTarget_powerStatTarget: 600,
        trainingMileStatTarget_gutsStatTarget: 300,
        trainingMileStatTarget_witStatTarget: 300,
        trainingMediumStatTarget_speedStatTarget: 800,
        trainingMediumStatTarget_staminaStatTarget: 450,
        trainingMediumStatTarget_powerStatTarget: 550,
        trainingMediumStatTarget_gutsStatTarget: 300,
        trainingMediumStatTarget_witStatTarget: 300,
        trainingLongStatTarget_speedStatTarget: 700,
        trainingLongStatTarget_staminaStatTarget: 600,
        trainingLongStatTarget_powerStatTarget: 450,
        trainingLongStatTarget_gutsStatTarget: 300,
        trainingLongStatTarget_witStatTarget: 300,
    },
    ocr: {
        ocrThreshold: 230,
        enableAutomaticOCRRetry: true,
        ocrConfidence: 90,
    },
    debug: {
        enableDebugMode: false,
        templateMatchConfidence: 0.8,
        templateMatchCustomScale: 1.0,
        debugMode_startTemplateMatchingTest: false,
        debugMode_startSingleTrainingOCRTest: false,
        debugMode_startComprehensiveTrainingOCRTest: false,
        debugMode_startDateOCRTest: false,
        debugMode_startRaceListDetectionTest: false,
        debugMode_startAptitudesDetectionTest: false,
        debugMode_startMainScreenOCRTest: false,
        debugMode_startTrainingScreenOCRTest: false,
        enableHideOCRComparisonResults: true,
        enableScreenRecording: false,
        recordingBitRate: 6,
        recordingFrameRate: 30,
        recordingResolutionScale: 1.0,
    },
    discord: {
        enableDiscordNotifications: false,
        discordToken: "",
        discordUserID: "",
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
