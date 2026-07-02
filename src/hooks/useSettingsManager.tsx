import { useState, useEffect, useContext, useMemo, useRef, useCallback } from "react"
import { File, Paths } from "expo-file-system"
import * as Sharing from "expo-sharing"
import { startActivityAsync } from "expo-intent-launcher"
import { defaultSettings, Settings, BotMetaContext, useSettingsSnapshot } from "../context/BotStateContext"
import { databaseManager } from "../lib/database"
import { startTiming } from "../lib/performanceLogger"
import { logWithTimestamp, logErrorWithTimestamp } from "../lib/logger"
import { deepMerge, convertSettingsToBatch, applyMigrations, stripDbOwnedKeys } from "../lib/settingsUtils"
import { storageBridge, folderDocumentUriFromTreeUri } from "../lib/storageBridge"

export { deepMerge, convertSettingsToBatch, applyMigrations }

/**
 * Manages settings persistence using `SQLite` database.
 * @returns An object containing the state and functions for managing settings persistence.
 */
export const useSettingsManager = () => {
    // Track whether settings are currently being saved.
    const [, setIsSaving] = useState(false)
    const [migrationCompleted, setMigrationCompleted] = useState(false)

    const { setSettings, setReadyStatus } = useContext(BotMetaContext)
    const settings = useSettingsSnapshot()

    // Ref to always track the latest settings, avoiding stale closure issues.
    const settingsRef = useRef<Settings>(settings)

    // Track whether the initial load from database has completed.
    const hasLoadedRef = useRef(false)

    // Debounce timer for auto-saving settings.
    const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

    // Snapshot of the settings as last persisted to SQLite. Used to compute a per-slice diff so
    // each toggle only writes the slice it touched (~10 rows) instead of the full 175-row batch.
    // Slice-level identity is reliable because slice context updates always replace the slice
    // object (e.g. `updateMisc({ ...misc, foo: bar })`).
    const lastSavedSettingsRef = useRef<Settings | null>(null)

    // Keep the ref in sync with the latest settings.
    useEffect(() => {
        settingsRef.current = settings
    }, [settings])

    // Direct database operations.
    const isSQLiteInitialized = databaseManager.isInitialized()

    // Auto-load settings when SQLite is initialized.
    useEffect(() => {
        if (isSQLiteInitialized && !migrationCompleted) {
            logWithTimestamp("[SettingsManager] SQLite initialized and loading settings will be handled by bootstrap.")
            setMigrationCompleted(true)
        }
    }, [isSQLiteInitialized, migrationCompleted])

    // Auto-save settings to SQLite whenever they change, with debouncing.
    // Skips saving during initial load to avoid re-writing defaults back on startup.
    useEffect(() => {
        if (!hasLoadedRef.current || !isSQLiteInitialized) {
            return
        }

        // Clear any pending debounce timer.
        if (autoSaveTimerRef.current) {
            clearTimeout(autoSaveTimerRef.current)
        }

        // Debounce the save to batch rapid changes.
        autoSaveTimerRef.current = setTimeout(async () => {
            try {
                const current = settingsRef.current
                const last = lastSavedSettingsRef.current
                // First save after load: persist everything. Subsequent saves: only the slices
                // whose top-level reference changed since the last persisted snapshot.
                let toPersist: Record<string, any>
                if (last == null) {
                    toPersist = current
                } else {
                    toPersist = {}
                    for (const key of Object.keys(current) as Array<keyof Settings>) {
                        if (current[key] !== last[key]) {
                            toPersist[key as string] = current[key]
                        }
                    }
                }
                const batch = convertSettingsToBatch(toPersist)
                if (batch.length === 0) {
                    return
                }
                logWithTimestamp(`[SettingsManager] Auto-saving ${batch.length} settings (slices: ${Object.keys(toPersist).join(", ")}).`)
                await databaseManager.saveSettingsBatch(batch)
                lastSavedSettingsRef.current = current
                logWithTimestamp("[SettingsManager] Auto-save completed.")
            } catch (error) {
                logErrorWithTimestamp(`[SettingsManager] Auto-save failed: ${error}`)
            }
        }, 500)

        return () => {
            if (autoSaveTimerRef.current) {
                clearTimeout(autoSaveTimerRef.current)
            }
        }
    }, [settings, isSQLiteInitialized])

    /**
     * Save settings to `SQLite` database.
     * @param newSettings - The `Settings` object to save. If not provided, the current settings from the `BotStateContext` will be used.
     * @returns A promise that resolves when the settings are saved.
     */
    const saveSettings = useCallback(async (newSettings?: Settings) => {
        const endTiming = startTiming("settings_manager_save_settings", "settings")

        setIsSaving(true)

        try {
            // Read from the ref to always get the latest settings.
            const localSettings: Settings = newSettings ? newSettings : settingsRef.current
            await databaseManager.saveSettingsBatch(convertSettingsToBatch(localSettings))
            lastSavedSettingsRef.current = localSettings
            endTiming({ status: "success", hasNewSettings: !!newSettings })
        } catch (error) {
            logErrorWithTimestamp(`Error saving settings: ${error}`)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
        } finally {
            setIsSaving(false)
        }
    }, [])

    /**
     * Save settings immediately without debouncing (for background/exit saves).
     * @param newSettings - The `Settings` object to save. If not provided, the current settings from the `BotStateContext` will be used.
     * @returns A promise that resolves when the settings are saved.
     */
    const saveSettingsImmediate = useCallback(async (newSettings?: Settings) => {
        // Skip auto/background saves until the initial load completes.
        if (!hasLoadedRef.current && !newSettings) {
            return
        }

        const endTiming = startTiming("settings_manager_save_settings_immediate", "settings")

        setIsSaving(true)

        try {
            // Read from the ref to always get the latest settings.
            const localSettings: Settings = newSettings ? newSettings : settingsRef.current
            // Ensure the DB is open before writing. initialize() is idempotent and awaits any in-flight init.
            await databaseManager.initialize()
            await databaseManager.saveSettingsBatch(convertSettingsToBatch(localSettings))
            lastSavedSettingsRef.current = localSettings
            endTiming({ status: "success", hasNewSettings: !!newSettings, immediate: true })
        } catch (error) {
            logErrorWithTimestamp(`Error saving settings immediately: ${error}`)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
        } finally {
            setIsSaving(false)
        }
    }, [])

    /**
     * Load settings from `SQLite` database.
     * @param skipInitializationCheck - Whether to skip the SQLite initialization check.
     * @returns A promise that resolves when the settings are loaded.
     */
    const loadSettings = useCallback(
        async (skipInitializationCheck: boolean = false) => {
            const timingName = skipInitializationCheck ? "settings_manager_load_settings_bootstrap" : "settings_manager_load_settings"
            const endTiming = startTiming(timingName, "settings")
            const context = skipInitializationCheck ? "during bootstrap" : ""

            try {
                // Wait for SQLite to be initialized (unless explicitly skipped).
                if (!skipInitializationCheck && !databaseManager.isInitialized()) {
                    logWithTimestamp("[SettingsManager] Waiting for SQLite initialization...")
                    endTiming({ status: "skipped", reason: "sqlite_not_initialized" })
                    return
                }

                // Load from SQLite database.
                let newSettings: Settings = JSON.parse(JSON.stringify(defaultSettings))
                let rawDbSettings: any = undefined
                try {
                    const dbSettings = await databaseManager.loadAllSettings()
                    rawDbSettings = dbSettings
                    // Drop Kotlin-owned blobs (racing plan, character/support event data) before they
                    // reach React state. Loading them inflates the settings tree and forces the
                    // auto-save effect to re-write all of it on every toggle — see `DB_OWNED_KEYS`
                    // in `settingsUtils.ts`.
                    const reactOwnedDbSettings = stripDbOwnedKeys(dbSettings)
                    // Use deep merge to preserve nested default values.
                    newSettings = deepMerge(defaultSettings, reactOwnedDbSettings as Partial<Settings>)
                    logWithTimestamp(`[SettingsManager] Settings loaded from SQLite database ${context}.`)
                } catch (sqliteError) {
                    logWithTimestamp(`[SettingsManager] Failed to load from SQLite ${context}, using defaults:`)
                    console.warn(sqliteError)
                }

                // Apply all migrations to the settings.
                const { settings: migratedSettings, anyMigrated } = applyMigrations(newSettings, rawDbSettings)
                newSettings = migratedSettings

                // If any migration occurred, save the migrated settings back to the database.
                if (anyMigrated) {
                    try {
                        await databaseManager.saveSettingsBatch(convertSettingsToBatch(newSettings))
                        lastSavedSettingsRef.current = newSettings
                        logWithTimestamp("[SettingsManager] Saved migrated settings to database.")
                    } catch (migrationSaveError) {
                        logErrorWithTimestamp("[SettingsManager] Error saving migrated settings:", migrationSaveError)
                    }
                }

                setSettings(newSettings)
                // The DB now matches React state, so the next auto-save can diff against it
                // and skip writing slices that haven't changed.
                lastSavedSettingsRef.current = newSettings
                // Mark that the initial load has completed so auto-save can begin.
                hasLoadedRef.current = true
                logWithTimestamp(`[SettingsManager] Settings loaded and applied to context ${context}.`)
                logWithTimestamp(`[SettingsManager] Scenario value after load: "${newSettings.general.scenario}"`)
                endTiming({ status: "success", usedDefaults: newSettings === defaultSettings })
            } catch (error) {
                logErrorWithTimestamp(`[SettingsManager] Error loading settings${context}:`, error)
                setSettings(JSON.parse(JSON.stringify(defaultSettings)))
                setReadyStatus(false)
                endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            }
        },
        [setSettings, setReadyStatus]
    )

    /**
     * Import settings from a JSON file.
     * @param fileUri - The URI/path to the JSON settings file.
     * @returns A promise that resolves with the imported settings and profiles.
     */
    const loadFromJSONFile = async (fileUri: string): Promise<{ settings: Settings; profiles?: Array<{ id: number; name: string; settings: any; created_at: string; updated_at: string }> }> => {
        try {
            const data = await new File(fileUri).text()
            const parsed: any = JSON.parse(data)

            // Extract profiles if they exist.
            const profiles = parsed.profiles
            delete parsed.profiles

            // Parse as Settings and fix missing fields.
            const fixedSettings: Settings = fixSettings(parsed as Settings)

            logWithTimestamp("Settings imported from JSON file successfully.")
            return { settings: fixedSettings, profiles }
        } catch (error: any) {
            logErrorWithTimestamp(`Error reading settings from JSON file: ${error}`)
            throw error
        }
    }

    /**
     * Ensure all required `Settings` fields exist by filling missing ones with defaults.
     * @param decoded - The `Settings` object to fix.
     * @returns A `Settings` object with all required fields populated.
     */
    const fixSettings = (decoded: Settings): Settings => {
        const merged = deepMerge(defaultSettings, decoded as Partial<Settings>)
        // Apply all migrations to the settings.
        const { settings } = applyMigrations(merged, decoded)
        return settings
    }

    /**
     * Import settings from a JSON file and save to `SQLite`.
     * @param fileUri - The URI/path to the JSON settings file.
     * @returns A promise that resolves with a boolean indicating whether the import was successful.
     */
    const importSettings = useCallback(
        async (fileUri: string): Promise<boolean> => {
            const endTiming = startTiming("settings_manager_import_settings", "settings")

            try {
                setIsSaving(true)

                // Ensure database is initialized before saving. Read live so the closure stays stable.
                logWithTimestamp("Ensuring database is initialized before saving...")
                if (!databaseManager.isInitialized()) {
                    logWithTimestamp("Database not initialized, triggering initialization...")
                    await databaseManager.initialize()
                }

                // Check for current active profile name before importing profiles.
                let previousActiveProfileName: string | null = null
                try {
                    previousActiveProfileName = await databaseManager.getCurrentProfileName()
                } catch (error) {
                    logErrorWithTimestamp("[SettingsManager] Error getting current profile name (continuing with import):", error)
                }

                // Load settings and profiles from JSON file.
                const { settings: importedSettings, profiles } = await loadFromJSONFile(fileUri)

                // Preserve the current Discord token. Export already strips it for privacy, and re-importing should never
                // overwrite the user's locally-stored token with a default empty string or a stale value from another device.
                if (importedSettings.discord) {
                    importedSettings.discord.discordToken = settingsRef.current.discord?.discordToken ?? ""
                }

                // Save settings to SQLite database.
                await databaseManager.saveSettingsBatch(convertSettingsToBatch(importedSettings))
                lastSavedSettingsRef.current = importedSettings
                setSettings(importedSettings)

                // Import profiles if they exist.
                if (profiles && Array.isArray(profiles) && profiles.length > 0) {
                    try {
                        // Delete all existing profiles.
                        const existingProfiles = await databaseManager.getAllProfiles()
                        for (const profile of existingProfiles) {
                            await databaseManager.deleteProfile(profile.id)
                        }
                        logWithTimestamp(`[SettingsManager] Deleted ${existingProfiles.length} existing profiles.`)

                        // Import all profiles from the JSON file.
                        for (const profile of profiles) {
                            await databaseManager.saveProfile({
                                name: profile.name,
                                settings: profile.settings,
                            })
                        }
                        logWithTimestamp(`[SettingsManager] Imported ${profiles.length} profiles.`)

                        // If there was a previously active profile and at least one profile was imported, set active profile to the first imported profile.
                        if (previousActiveProfileName && profiles.length > 0) {
                            await databaseManager.setCurrentProfileName(profiles[0].name)
                            logWithTimestamp(`[SettingsManager] Set active profile to first imported profile: ${profiles[0].name}`)
                        }
                    } catch (profileError) {
                        logErrorWithTimestamp("[SettingsManager] Error importing profiles (settings import succeeded):", profileError)
                    }
                }

                logWithTimestamp("Settings imported successfully.")

                endTiming({ status: "success", fileUri, profilesImported: profiles?.length || 0 })
                return true
            } catch (error) {
                logErrorWithTimestamp("Error importing settings:", error)
                endTiming({ status: "error", fileUri, error: error instanceof Error ? error.message : String(error) })
                return false
            } finally {
                setIsSaving(false)
            }
        },
        [setSettings]
    )

    /**
     * Export current settings to a JSON file which includes stripping large fields.
     * @returns A promise that resolves with the file URI of the exported settings, or null if the export failed.
     */
    const exportSettings = useCallback(async (): Promise<string | null> => {
        const endTiming = startTiming("settings_manager_export_settings", "settings")

        try {
            // Fetch all profiles from database.
            let profiles: Array<{ id: number; name: string; settings: any; created_at: string; updated_at: string }> = []
            try {
                if (databaseManager.isInitialized()) {
                    const dbProfiles = await databaseManager.getAllProfiles()
                    profiles = dbProfiles.map((p) => ({
                        id: p.id,
                        name: p.name,
                        settings: JSON.parse(p.settings),
                        created_at: p.created_at,
                        updated_at: p.updated_at,
                    }))
                    logWithTimestamp(`[SettingsManager] Exported ${profiles.length} profiles.`)
                }
            } catch (profileError) {
                logErrorWithTimestamp("[SettingsManager] Error exporting profiles (continuing with settings export):", profileError)
            }

            // Create export object with settings and profiles. The large Kotlin-owned blobs
            // (`racing.racingPlanData`, `trainingEvent.{characterEventData,supportEventData}`,
            // `misc.formattedSettingsString`) are now filtered out at the load boundary
            // (`stripDbOwnedKeys`) so they never reach React state to begin with — only the
            // user-owned fields remain to be deep-cloned here.
            const settingsForExport = JSON.parse(JSON.stringify(settingsRef.current))

            // Drop fields that are export-irrelevant but still live in React state.
            delete settingsForExport.misc.currentProfileName

            // Remove sensitive Discord credentials from export.
            if (settingsForExport.discord) {
                delete settingsForExport.discord.discordToken
            }

            const exportData = {
                ...settingsForExport,
                profiles: profiles.length > 0 ? profiles : undefined,
            }

            const jsonString = JSON.stringify(exportData, null, 4)

            // Create a temporary file name with timestamp.
            const timestamp = new Date().toISOString().replace(/[:.]/g, "-")
            const fileName = `UAA-settings-${timestamp}.json`
            const file = new File(Paths.document, fileName)

            // Write the settings to file. In SDK 56's expo-file-system, `write` is synchronous.
            file.write(jsonString)

            logWithTimestamp(`Settings exported successfully to: ${file.uri}`)

            endTiming({ status: "success", fileName, fileSize: jsonString.length, profilesCount: profiles.length })
            return file.uri
        } catch (error) {
            logErrorWithTimestamp("Error exporting settings:", error)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            return null
        }
    }, [])

    /**
     * Open the app's data directory using Storage Access Framework or fallback to file explorer.
     * @returns A promise that resolves when the data directory is opened.
     */
    const openDataDirectory = useCallback(async () => {
        const endTiming = startTiming("settings_manager_open_data_dir", "settings")
        // Get the app's package name from the document directory path.
        const packageName = "com.steve1316.uma_android_automation"

        try {
            // Prefer the folder the user chose for the bot's logs/recordings so the button lands on their actual data
            // location, not the app's internal storage. Only fall back to internal when no folder has been configured.
            let chosenUri: string | null = null
            try {
                chosenUri = (await storageBridge.getCurrentFolder())?.uri || null
            } catch (folderReadError) {
                console.warn("Could not read the chosen storage folder, falling back to internal:", folderReadError)
            }

            // Ordered open attempts; the first that launches wins. When the user picked a folder we view it directly in
            // the system Files app; otherwise we keep the legacy internal-storage behavior (SAF picker, then a path view).
            const attempts: { action: string; params: Parameters<typeof startActivityAsync>[1]; method: string }[] = chosenUri
                ? [
                      { action: "android.intent.action.VIEW", params: { data: folderDocumentUriFromTreeUri(chosenUri), type: "vnd.android.document/directory", flags: 1 }, method: "view-chosen" },
                      { action: "android.intent.action.OPEN_DOCUMENT_TREE", params: { flags: 1 }, method: "saf-chosen" },
                  ]
                : [
                      {
                          action: "android.intent.action.OPEN_DOCUMENT_TREE",
                          params: { data: `content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2F${packageName}/files`, flags: 1 },
                          method: "saf",
                      },
                      { action: "android.intent.action.VIEW", params: { data: `/storage/emulated/0/Android/data/${packageName}/files`, type: "resource/folder" }, method: "intent" },
                  ]

            for (const { action, params, method } of attempts) {
                try {
                    await startActivityAsync(action, params)
                    endTiming({ status: "success", method })
                    return
                } catch (attemptError) {
                    console.warn(`Open data directory attempt "${method}" failed, trying next:`, attemptError)
                }
            }

            // Final fallback: Share the settings file directly.
            const settingsFile = new File(Paths.document, "settings.json")

            if (settingsFile.exists) {
                const isAvailable = await Sharing.isAvailableAsync()
                if (isAvailable) {
                    await Sharing.shareAsync(settingsFile.uri, {
                        mimeType: "application/json",
                        dialogTitle: "Share Settings File",
                    })
                    endTiming({ status: "success", method: "share" })
                } else {
                    throw new Error("Sharing not available")
                }
            } else {
                throw new Error("Settings file not found")
            }
        } catch (error) {
            logErrorWithTimestamp(`Error opening app data directory: ${error}`)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
        }
    }, [])

    /**
     * Reset settings to default values.
     * @returns A promise that resolves with a boolean indicating whether the reset was successful.
     */
    const resetSettings = useCallback(async (): Promise<boolean> => {
        const endTiming = startTiming("settings_manager_reset_settings", "settings")

        try {
            setIsSaving(true)

            // Ensure database is initialized before saving. Read live so the closure stays stable.
            logWithTimestamp("Ensuring database is initialized before resetting...")
            if (!databaseManager.isInitialized()) {
                logWithTimestamp("Database not initialized, triggering initialization...")
                await databaseManager.initialize()
            }

            // Create a deep copy of default settings to avoid reference issues.
            const defaultSettingsCopy = JSON.parse(JSON.stringify(defaultSettings))

            // Save default settings to SQLite database.
            await databaseManager.saveSettingsBatch(convertSettingsToBatch(defaultSettingsCopy))
            lastSavedSettingsRef.current = defaultSettingsCopy

            // Update the current settings in context.
            setSettings(defaultSettingsCopy)
            setReadyStatus(false)

            logWithTimestamp("Settings reset to defaults successfully.")

            endTiming({ status: "success" })
            return true
        } catch (error) {
            logErrorWithTimestamp("Error resetting settings:", error)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
            return false
        } finally {
            setIsSaving(false)
        }
    }, [setSettings, setReadyStatus])

    return useMemo(
        () => ({
            saveSettings,
            saveSettingsImmediate,
            loadSettings,
            importSettings,
            exportSettings,
            resetSettings,
            openDataDirectory,
        }),
        [saveSettings, saveSettingsImmediate, loadSettings, importSettings, exportSettings, resetSettings, openDataDirectory]
    )
}
