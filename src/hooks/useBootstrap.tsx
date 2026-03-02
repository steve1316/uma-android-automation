import { useContext, useEffect, useState, useRef } from "react"
import { DeviceEventEmitter, AppState } from "react-native"
import { BotStateContext, BotStateProviderProps } from "../context/BotStateContext"
import { MessageLogContext, MessageLogProviderProps } from "../context/MessageLogContext"
import { useSettings } from "../context/SettingsContext"
import { logWithTimestamp, logErrorWithTimestamp } from "../lib/logger"
import { databaseManager, DatabaseRace, DatabaseSkill } from "../lib/database"
import racesData from "../data/races.json"
import skillsData from "../data/skills.json"
import charactersData from "../data/characters.json"
import supportsData from "../data/supports.json"

/**
 * Manages app initialization, settings persistence, and message handling.
 * Coordinates startup sequence and maintains app state synchronization.
 * @returns The bootstrap state with the `isReady` flag and initialization functions.
 */
export const useBootstrap = () => {
    const [isReady, setIsReady] = useState<boolean>(false)
    const isSavingRef = useRef<boolean>(false)

    const bsc = useContext(BotStateContext) as BotStateProviderProps
    const mlc = useContext(MessageLogContext) as MessageLogProviderProps

    // Hook for managing settings persistence.
    const { saveSettingsImmediate, loadSettings } = useSettings()

    useEffect(() => {
        // Listen for messages from the Android automation service.
        const messageLogSubscription = DeviceEventEmitter.addListener("MessageLog", (data: any) => {
            mlc.addMessageToLog(data.id, data.message)
        })

        return () => messageLogSubscription.remove()
    }, [])

    // Initialize database and populate races data on mount.
    useEffect(() => {
        const initializeApp = async () => {
            try {
                logWithTimestamp("[Bootstrap] Initializing database and populating table data...")
                await databaseManager.initialize()
                await populateRacesData()
                await populateSkillsData()
                await populateEventData()

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

        initializeApp()
    }, [])

    /**
     * Populate race event data from racing.json into SQLite.
     * @returns A promise that resolves when the races data has been populated.
     */
    const populateRacesData = async (): Promise<void> => {
        try {
            logWithTimestamp("[Bootstrap] Starting races data population...")

            // Convert races.json data to database format.
            const races: Array<Omit<DatabaseRace, "id">> = Object.entries(racesData).map(([key, race]) => ({
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

            // Convert skills.json data to database format.
            const skills: Array<Omit<DatabaseSkill, "id">> = Object.entries(skillsData).map(([key, skill]) => ({
                key,
                skill_id: skill.id,
                gene_id: skill.gene_id,
                name_en: skill.name_en,
                desc_en: skill.desc_en,
                icon_id: skill.icon_id,
                cost: skill.cost,
                eval_pt: skill.eval_pt,
                pt_ratio: skill.pt_ratio,
                rarity: skill.rarity,
                condition: skill.condition,
                precondition: skill.precondition,
                inherited: skill.inherited,
                community_tier: skill.community_tier,
                versions: skill.versions,
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
     * Populate character and support event data from JSON files into SQLite.
     * @returns A promise that resolves when the event data has been populated.
     */
    const populateEventData = async (): Promise<void> => {
        try {
            logWithTimestamp("[Bootstrap] Starting event data population...")

            // Save character event data to SQLite.
            await databaseManager.saveSetting("trainingEvent", "characterEventData", charactersData, true)
            logWithTimestamp(`[Bootstrap] Successfully saved character event data (${Object.keys(charactersData).length} characters) to SQLite`)

            // Save support event data to SQLite.
            await databaseManager.saveSetting("trainingEvent", "supportEventData", supportsData, true)
            logWithTimestamp(`[Bootstrap] Successfully saved support event data (${Object.keys(supportsData).length} supports) to SQLite`)

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
            const scenario = bsc.settings.general.scenario
            bsc.setReadyStatus(scenario !== "")
        }
    }, [isReady, bsc.settings.general.scenario])
}
