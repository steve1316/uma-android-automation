import { useState, useContext } from "react"
import * as DocumentPicker from "expo-document-picker"
import * as Sharing from "expo-sharing"
import * as FileSystem from "expo-file-system"
import { useNavigation } from "@react-navigation/native"
import { useSettings } from "../context/SettingsContext"
import { BotStateContext, Settings, defaultSettings } from "../context/BotStateContext"
import { logErrorWithTimestamp } from "../lib/logger"

/**
 * Format a value for display in the preview dialog.
 * Converts various data types into human-readable strings for the settings import preview.
 * @param value - The value to format (can be any type).
 * @returns A formatted string representation of the value.
 */
const formatValue = (value: any): string => {
    if (value == null) return "null"
    if (typeof value === "boolean") return value ? "Enabled" : "Disabled"
    if (Array.isArray(value)) return value.length === 0 ? "[]" : value.join(", ")
    if (typeof value === "object") return JSON.stringify(value)
    return String(value)
}

/**
 * Deep comparison of two values to determine if they are equal.
 * Recursively compares nested objects and arrays, handling all primitive types.
 * Used to detect actual changes in settings values, not just reference equality.
 * @param a - First value to compare.
 * @param b - Second value to compare.
 * @returns true if values are deeply equal, false otherwise.
 */
const deepEqual = (a: any, b: any): boolean => {
    if (a === b) return true
    if (a == null || b == null || typeof a !== typeof b) return false

    if (Array.isArray(a) && Array.isArray(b)) {
        return a.length === b.length && a.every((item, i) => deepEqual(item, b[i]))
    }

    if (typeof a === "object" && typeof b === "object") {
        // Sort the keys of the objects to ensure consistent order.
        const keysA = Object.keys(a).sort()
        const keysB = Object.keys(b).sort()
        return keysA.length === keysB.length && keysA.every((key) => keysB.includes(key) && deepEqual(a[key], b[key]))
    }

    return false
}

/**
 * Compare two `Settings` objects and return a list of changes.
 * Iterates through all categories and keys in the imported settings,
 * comparing each value with the current settings using deep equality.
 * Only returns settings that would actually change if imported.
 * @param current - The current settings object from the app state.
 * @param imported - The settings object loaded from the JSON file.
 * @returns Array of change objects containing category, key, old value, and new value.
 */
const compareSettings = (current: Settings, imported: Settings) => {
    const changes: Array<{ category: string; key: string; oldValue: any; newValue: any }> = []

    for (const category of Object.keys(imported) as Array<keyof Settings>) {
        const currentCategory = current[category]
        const importedCategory = imported[category]

        if (!currentCategory || !importedCategory) continue

        for (const key of Object.keys(importedCategory)) {
            // Skip large settings fields that shouldn't be shown in preview.
            if ((category === "racing" && key === "racingPlanData") || (category === "misc" && key === "formattedSettingsString")) {
                continue
            }

            const currentValue = (currentCategory as any)[key]
            const importedValue = (importedCategory as any)[key]

            // Compare the current value with the imported value using deep equality.
            if (!deepEqual(currentValue, importedValue)) {
                changes.push({ category, key, oldValue: currentValue, newValue: importedValue })
            }
        }
    }

    return changes
}

/**
 * Deep merges two objects, preserving nested structure.
 * Recursively merges source object into target, ensuring all nested properties
 * are properly merged rather than replaced. Used to merge imported settings
 * with default settings to ensure all required fields exist.
 * @param target - The target object to merge into (typically default settings).
 * @param source - The source object to merge from (typically imported settings).
 * @returns A new object with merged values from both target and source.
 */
const deepMerge = <T extends Record<string, any>>(target: T, source: Partial<T>): T => {
    const output = { ...target }
    for (const key in source) {
        if (source[key] && typeof source[key] === "object" && !Array.isArray(source[key]) && source[key] !== null) {
            // Recursively merge nested objects.
            output[key] = deepMerge((target[key] || {}) as Record<string, any>, source[key] as any) as T[Extract<keyof T, string>]
        } else if (source[key] !== undefined) {
            output[key] = source[key] as T[Extract<keyof T, string>]
        }
    }
    return output
}

/**
 * Load and fix settings from a JSON file without importing.
 * Reads a JSON file from the filesystem, parses it as a `Settings` object, and merges it
 * with default settings to ensure all required fields are present. This allows
 * previewing changes before actually applying them to the app state.
 * @param fileUri - The URI/path to the JSON settings file.
 * @returns A `Settings` object with all fields populated (merged with defaults).
 * @throws Error if file cannot be read or parsed.
 */
const loadFromJSONFile = async (fileUri: string): Promise<Settings> => {
    try {
        const data = await FileSystem.readAsStringAsync(fileUri)
        const parsed = JSON.parse(data) as Settings
        // Merge the parsed settings with the default settings.
        return deepMerge(defaultSettings, parsed as Partial<Settings>)
    } catch (error) {
        logErrorWithTimestamp(`Error reading settings from JSON file: ${error}`)
        throw error
    }
}

export interface SettingsChange {
    /** The category of the setting that changed. */
    category: string
    /** The key of the setting that changed. */
    key: string
    /** The old value of the setting that changed. */
    oldValue: any
    /** The new value of the setting that changed. */
    newValue: any
    /** The formatted old value of the setting that changed. */
    formattedOldValue: string
    /** The formatted new value of the setting that changed. */
    formattedNewValue: string
}

/**
 * Hook for managing settings file operations (import/export) with file picker and restart prompts.
 * @returns An object containing the state and functions for managing settings file operations.
 */
export const useSettingsFileManager = () => {
    const [showImportDialog, setShowImportDialog] = useState(false)
    const [showResetDialog, setShowResetDialog] = useState(false)
    const [importPreviewChanges, setImportPreviewChanges] = useState<SettingsChange[]>([])
    const [pendingImportUri, setPendingImportUri] = useState<string | null>(null)

    const { importSettings, exportSettings } = useSettings()
    const bsc = useContext(BotStateContext)
    const navigation = useNavigation()

    /**
     * Clears all state related to the import preview, including
     * the pending file URI and list of changes.
     */
    const clearPreviewState = () => {
        setPendingImportUri(null)
        setImportPreviewChanges([])
    }

    /**
     * Confirms and performs the actual import. Imports the settings from the provided file URI, shows a success dialog
     * if successful, and clears the preview state.
     * @param fileUri - The URI/path to the JSON settings file to import
     */
    const confirmImportSettings = async (fileUri: string) => {
        if (!fileUri) return

        try {
            const success = await importSettings(fileUri)
            if (success) {
                setShowImportDialog(true)
            }
            clearPreviewState()
        } catch (error) {
            logErrorWithTimestamp("Error importing settings:", error)
        }
    }

    /**
     * Compare imported settings with current settings and navigate to preview screen.
     * Loads the settings file, compares it with current settings, formats
     * the changes for display, and navigates to the preview screen. This is called
     * when the user selects a file to import, before actually applying changes.
     * @param fileUri - The URI/path to the JSON settings file to compare
     */
    const compareAndPreviewSettings = async (fileUri: string) => {
        try {
            const importedSettings = await loadFromJSONFile(fileUri)
            const changes = compareSettings(bsc.settings, importedSettings)

            const formattedChanges = changes.map((change) => ({
                ...change,
                formattedOldValue: formatValue(change.oldValue),
                formattedNewValue: formatValue(change.newValue),
            }))

            setPendingImportUri(fileUri)
            setImportPreviewChanges(formattedChanges)

            // Navigate to the preview screen with data passed as params.
            ;(navigation as any).navigate("ImportSettingsPreview", {
                changes: formattedChanges,
                fileUri: fileUri,
            })
        } catch (error) {
            logErrorWithTimestamp("Error comparing settings:", error)
        }
    }

    /**
     * Cancels the import preview. Called when the user cancels the import preview.
     */
    const cancelImportPreview = clearPreviewState

    /**
     * Opens the system document picker to allow the user to select a JSON settings file.
     * After file selection, compares the imported settings with current settings
     * and shows a preview dialog instead of importing immediately.
     * @returns A promise that resolves when the settings file is imported.
     */
    const handleImportSettings = async () => {
        try {
            // Open document picker for JSON files.
            const result = await DocumentPicker.getDocumentAsync({
                type: "application/json",
                copyToCacheDirectory: true,
            })

            if (result.canceled || !result.assets?.[0]) return

            await compareAndPreviewSettings(result.assets[0].uri)
        } catch (error) {
            logErrorWithTimestamp("Error importing settings:", error)
        }
    }

    /**
     * Exports the current app settings to a JSON file and shares it using
     * the system share dialog, allowing the user to save it to their preferred location.
     * @returns A promise that resolves when the settings file is exported.
     */
    const handleExportSettings = async () => {
        try {
            const fileUri = await exportSettings()
            if (fileUri && (await Sharing.isAvailableAsync())) {
                await Sharing.shareAsync(fileUri, {
                    mimeType: "application/json",
                    dialogTitle: "Export Settings",
                })
            }
        } catch (error) {
            logErrorWithTimestamp("Error exporting settings:", error)
        }
    }

    /**
     * Confirms import using the pending import URI from state.
     * This is a wrapper that uses the pendingImportUri state.
     * @returns A promise that resolves when the settings file is imported.
     */
    const confirmPendingImport = async () => {
        if (pendingImportUri) {
            await confirmImportSettings(pendingImportUri)
        }
    }

    return {
        handleImportSettings,
        handleExportSettings,
        showImportDialog,
        setShowImportDialog,
        showResetDialog,
        setShowResetDialog,
        confirmImportSettings,
        confirmPendingImport,
        cancelImportPreview,
        importPreviewChanges,
        pendingImportUri,
        clearPreviewState,
    }
}
