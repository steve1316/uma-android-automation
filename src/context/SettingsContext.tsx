import React, { createContext, useContext, ReactNode } from "react"
import { useSettingsManager } from "../hooks/useSettingsManager"

/**
 * Context value interface for the Settings provider.
 * Exposes methods for persisting, importing, exporting, and resetting settings.
 */
interface SettingsContextType {
    /** Saves the current settings with optional debouncing. */
    saveSettings: (newSettings?: any) => Promise<void>
    /** Saves settings immediately without debouncing. */
    saveSettingsImmediate: (newSettings?: any) => Promise<void>
    /** Loads settings from persistent storage. */
    loadSettings: (skipInitializationCheck?: boolean) => Promise<void>
    /** Imports settings from a file at the given URI. Returns true on success. */
    importSettings: (fileUri: string) => Promise<boolean>
    /** Exports current settings to a file. Returns the file path or null on failure. */
    exportSettings: () => Promise<string | null>
    /** Resets all settings to defaults. Returns true on success. */
    resetSettings: () => Promise<boolean>
    /** Opens the app's data directory in the device's file manager. */
    openDataDirectory: () => Promise<void>
    /** Whether a save operation is currently in progress. */
    isSaving: boolean
}

const SettingsContext = createContext<SettingsContextType | undefined>(undefined)

interface SettingsProviderProps {
    /** The children of the provider. */
    children: ReactNode
}

/**
 * Provider component for the Settings context.
 * Wraps the useSettingsManager hook to provide settings persistence throughout the app.
 * @param children The children of the provider.
 * @returns The settings provider.
 */
export const SettingsProvider: React.FC<SettingsProviderProps> = ({ children }) => {
    const settingsManager = useSettingsManager()

    return <SettingsContext.Provider value={settingsManager}>{children}</SettingsContext.Provider>
}

/**
 * Hook to access the Settings context. Must be used within a SettingsProvider.
 * @returns The settings context value with save, load, import, export, and reset methods.
 */
export const useSettings = (): SettingsContextType => {
    const context = useContext(SettingsContext)
    if (context === undefined) {
        throw new Error("useSettings must be used within a SettingsProvider")
    }
    return context
}
