import { useContext, useEffect, useState, useRef } from "react"
import { DeviceEventEmitter, AppState, InteractionManager } from "react-native"
import { BotMetaContext, GeneralMiscContext } from "../context/BotStateContext"
import { MessageLogDispatchContext, MessageLogDispatchProps } from "../context/MessageLogContext"
import { useSettings } from "../context/SettingsContext"
import { logWithTimestamp, logErrorWithTimestamp } from "../lib/logger"
import { databaseManager, DatabaseRace, DatabaseSkill } from "../lib/database"
import { startJsThreadBlockDetector } from "../lib/performanceLogger"

/**
 * Manages app initialization, settings persistence, and message handling.
 * Coordinates startup sequence and maintains app state synchronization.
 * @returns The bootstrap state with the `isReady` flag and initialization functions.
 */
export const useBootstrap = () => {
    const [isReady, setIsReady] = useState<boolean>(false)
    const isSavingRef = useRef<boolean>(false)

    const { setReadyStatus } = useContext(BotMetaContext)
    const { general } = useContext(GeneralMiscContext)
    const mlc = useContext(MessageLogDispatchContext) as MessageLogDispatchProps

    // Hook for managing settings persistence.
    const { saveSettingsImmediate, loadSettings } = useSettings()

    useEffect(() => {
        // Listen for messages from the Android automation service.
        const messageLogSubscription = DeviceEventEmitter.addListener("MessageLog", (data: any) => {
            mlc.addMessageToLog(data.id, data.message)
        })

        // Start the JS-thread block detector so any sustained > 100 ms blocks surface in logcat
        // as `[BLOCK]` warnings. Cheap when idle; gated by `PerformanceLogger.ENABLED`.
        const stopBlockDetector = startJsThreadBlockDetector(100)

        return () => {
            messageLogSubscription.remove()
            stopBlockDetector()
        }
    }, [])

    // Initialize database and populate data after the first paint so the splash can render.
    useEffect(() => {
        const initializeApp = async () => {
            try {
                logWithTimestamp("[Bootstrap] Initializing database and populating table data...")
                await databaseManager.initialize()
                await populateRacesData()
                await populateSkillsData()
                await populateEventData()
                await populateSolverData()

                // Load settings after database initialization but before marking app as ready.
                // Skip the initialization check since we know the database is ready.
                logWithTimestamp("[Bootstrap] Loading settings from database...")
                await loadSettings(true)

                logWithTimestamp("[Bootstrap] Settings loaded.")
                setIsReady(true)
                logWithTimestamp("[Bootstrap] App initialization complete")
            } catch (error) {
                logErrorWithTimestamp("[Bootstrap] Failed to initialize app:", error)
                setIsReady(true)
            }
        }

        // Defer the heavy JSON parses + DB writes until after the first frame paints.
        const handle = InteractionManager.runAfterInteractions(() => {
            initializeApp()
        })
        return () => handle.cancel()
    }, [])

    /**
     * Yield to the UI thread so any pending frame can paint before the next heavy step.
     * @returns A promise that resolves once interactions are idle.
     */
    const yieldToFrame = (): Promise<void> =>
        new Promise((resolve) => {
            InteractionManager.runAfterInteractions(() => resolve())
        })

    /**
     * Populate race event data from racing.json into SQLite.
     * @returns A promise that resolves when the races data has been populated.
     */
    const populateRacesData = async (): Promise<void> => {
        try {
            logWithTimestamp("[Bootstrap] Starting races data population...")

            const racesData = require("../data/races.json")
            await yieldToFrame()

            // Convert races.json data to database format.
            const races: Array<Omit<DatabaseRace, "id">> = Object.entries(racesData).map(([key, race]: [string, any]) => ({
                key,
                name: race.name,
                date: race.date,
                raceTrack: race.raceTrack,
                course: race.course,
                direction: race.direction,
                grade: race.grade,
                terrain: race.terrain,
                distanceType: race.distanceType,
                distanceMeters: race.distanceMeters,
                fans: race.fans,
                turnNumber: race.turnNumber,
                nameFormatted: race.nameFormatted,
            }))

            logWithTimestamp(`[Bootstrap] Converted ${races.length} races from JSON to database format`)

            // Clear existing races and populate with new data.
            await databaseManager.clearRaces()
            await databaseManager.saveRacesBatch(races)

            logWithTimestamp(`[Bootstrap] Successfully populated ${races.length} races into database`)
        } catch (error) {
            logErrorWithTimestamp("[Bootstrap] Error populating races data:", error)
            throw error
        }
    }

    /**
     * Populate skills data from skills.json into SQLite.
     * @returns A promise that resolves when the skills data has been populated.
     */
    const populateSkillsData = async (): Promise<void> => {
        try {
            logWithTimestamp("[Bootstrap] Starting skills data population...")

            const skillsData = require("../data/live/skills").default
            await yieldToFrame()

            // Convert skills.json data to database format.
            const skills: Array<Omit<DatabaseSkill, "id">> = Object.entries(skillsData).map(([key, skill]: [string, any]) => ({
                key,
                skill_id: skill.id,
                name_en: skill.name_en,
                desc_en: skill.desc_en,
                icon_id: skill.icon_id,
                cost: skill.cost,
                eval_pt: skill.eval_pt,
                condition: skill.condition,
                precondition: skill.precondition,
                inherited: skill.inherited,
                community_tier: skill.community_tier,
                upgrade: skill.upgrade,
                downgrade: skill.downgrade,
            }))

            logWithTimestamp(`[Bootstrap] Converted ${skills.length} skills from JSON to database format`)

            // Clear existing skills and populate with new data.
            await databaseManager.clearSkills()
            await databaseManager.saveSkillsBatch(skills)

            logWithTimestamp(`[Bootstrap] Successfully populated ${skills.length} skills into database`)
        } catch (error) {
            logErrorWithTimestamp("[Bootstrap] Error populating skills data:", error)
            throw error
        }
    }

    /**
     * Populate the Smart Race Solver's bundled JSON datasets into SQLite under the `racing` namespace.
     * Kotlin's `SmartRaceSolverIntegration.loadEpithets` / `loadAllRaces` / `loadCharacterPresets` read
     * these rows directly. Re-running on every app start keeps the persisted blob in sync with the
     * bundled assets so a re-scrape (or a schema change like the `displayLabel` fields) propagates to
     * the bot side without requiring a settings reset.
     *
     * @returns A promise that resolves when all three rows have been written.
     */
    const populateSolverData = async (): Promise<void> => {
        try {
            logWithTimestamp("[Bootstrap] Starting solver data population...")

            const epithetsData = require("../data/live/epithets").default
            await yieldToFrame()
            await databaseManager.saveSetting("racing", "epithetsData", epithetsData, true)
            logWithTimestamp(`[Bootstrap] Successfully saved epithets data (${Object.keys(epithetsData).length} epithets) to SQLite`)
            await yieldToFrame()

            const racesData = require("../data/races.json")
            await yieldToFrame()
            await databaseManager.saveSetting("racing", "racesData", racesData, true)
            logWithTimestamp(`[Bootstrap] Successfully saved races data (${Object.keys(racesData).length} races) to SQLite`)
            await yieldToFrame()

            const characterPresetsData = require("../data/live/characterPresets").default
            await yieldToFrame()
            await databaseManager.saveSetting("racing", "characterPresetsData", characterPresetsData, true)
            logWithTimestamp(`[Bootstrap] Successfully saved character presets data (${Object.keys(characterPresetsData).length} presets) to SQLite`)
            await yieldToFrame()

            const characterObjectivesData = require("../data/live/characterObjectives").default
            await yieldToFrame()
            await databaseManager.saveSetting("racing", "characterObjectivesData", characterObjectivesData, true)
            logWithTimestamp(`[Bootstrap] Successfully saved character objectives data (${Object.keys(characterObjectivesData).length} characters) to SQLite`)

            logWithTimestamp("[Bootstrap] Solver data population complete")
        } catch (error) {
            logErrorWithTimestamp("[Bootstrap] Error populating solver data:", error)
            throw error
        }
    }

    /**
     * Populate character and support event data from JSON files into SQLite.
     * @returns A promise that resolves when the event data has been populated.
     */
    const populateEventData = async (): Promise<void> => {
        try {
            logWithTimestamp("[Bootstrap] Starting event data population...")

            // Lazy-require each large bundled JSON between yields so each parse and write
            // happens on its own frame instead of all in one main-thread block.
            const charactersData = require("../data/live/characters").default
            await yieldToFrame()
            await databaseManager.saveSetting("trainingEvent", "characterEventData", charactersData, true)
            logWithTimestamp(`[Bootstrap] Successfully saved character event data (${Object.keys(charactersData).length} characters) to SQLite`)
            await yieldToFrame()

            const supportsData = require("../data/live/supports").default
            await yieldToFrame()
            await databaseManager.saveSetting("trainingEvent", "supportEventData", supportsData, true)
            logWithTimestamp(`[Bootstrap] Successfully saved support event data (${Object.keys(supportsData).length} supports) to SQLite`)
            await yieldToFrame()

            const scenariosData = require("../data/scenarios.json")
            await yieldToFrame()
            await databaseManager.saveSetting("trainingEvent", "scenarioEventData", scenariosData, true)
            logWithTimestamp(`[Bootstrap] Successfully saved scenario-specific event data (${Object.keys(scenariosData).length} scenarios) to SQLite`)

            logWithTimestamp("[Bootstrap] Event data population complete")
        } catch (error) {
            logErrorWithTimestamp("[Bootstrap] Error populating event data:", error)
            throw error
        }
    }

    // Save settings when app goes to background or is about to close.
    useEffect(() => {
        const handleAppStateChange = (nextAppState: string) => {
            if (nextAppState === "background" || nextAppState === "inactive") {
                logWithTimestamp(`[Bootstrap] App state changed to ${nextAppState}, saving settings...`)
                if (!isSavingRef.current) {
                    isSavingRef.current = true
                    // Do an immediate save to bypass debouncing.
                    saveSettingsImmediate().finally(() => {
                        isSavingRef.current = false
                    })
                }
            }
        }

        const subscription = AppState.addEventListener("change", handleAppStateChange)
        return () => subscription?.remove()
    }, [saveSettingsImmediate])

    // Update ready status whenever settings change or app becomes ready.
    useEffect(() => {
        if (isReady) {
            const scenario = general.scenario
            setReadyStatus(scenario !== "")
        }
    }, [isReady, general.scenario])
}
