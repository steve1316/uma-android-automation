import React, { createContext, useContext, useState, useEffect, useCallback, useMemo, ReactNode } from "react"
import { databaseManager } from "../lib/database"
import { startTiming } from "../lib/performanceLogger"
import { logWithTimestamp, logErrorWithTimestamp } from "../lib/logger"
import { Settings } from "./BotStateContext"

export interface Profile {
    /** The unique identifier for the profile. */
    id: number
    /** The name of the profile. */
    name: string
    /** The settings for the profile. */
    settings: Partial<Settings>
    /** The creation timestamp of the profile. */
    created_at: string
    /** The last updated timestamp of the profile. */
    updated_at: string
}

export const DEFAULT_PROFILE_NAME = "Default Profile"

interface ProfileContextType {
    /** The array of all profiles. */
    profiles: Profile[]
    /** The name of the currently selected profile. */
    currentProfileName: string | null
    /** Whether the profiles are currently being loaded. */
    isLoading: boolean
    /** Loads all profiles from the database. */
    loadProfiles: () => Promise<void>
    /** Loads the name of the currently selected profile. */
    loadCurrentProfileName: () => Promise<void>
    /** Sets the array of all profiles. */
    setProfiles: (profiles: Profile[]) => void
    /** Sets the name of the currently selected profile. */
    setCurrentProfileName: (name: string | null) => void
    /** Sets whether the profiles are currently being loaded. */
    setIsLoading: (loading: boolean) => void
}

const ProfileContext = createContext<ProfileContextType | undefined>(undefined)

/**
 * Provider for profiles to ensure they are loaded only once and shared across components.
 * @param children The child components to render within the provider.
 * @returns The profile context provider.
 */
export const ProfileProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
    const [profiles, setProfiles] = useState<Profile[]>([])
    const [currentProfileName, setCurrentProfileName] = useState<string | null>(null)
    const [isLoading, setIsLoading] = useState(true)

    /**
     * Load all profiles from the database.
     */
    const loadProfiles = useCallback(async () => {
        const endTiming = startTiming("profile_load_all", "state")
        try {
            setIsLoading(true)

            // Wait for database to be initialized before loading profiles.
            await databaseManager.initialize()

            const dbProfiles = await databaseManager.getAllProfiles()

            const parsedProfiles: Profile[] = dbProfiles.map((p) => ({
                id: p.id,
                name: p.name,
                settings: JSON.parse(p.settings),
                created_at: p.created_at,
                updated_at: p.updated_at,
            }))

            // Sort profiles alphabetically.
            parsedProfiles.sort((a, b) => a.name.localeCompare(b.name))

            setProfiles(parsedProfiles)
            logWithTimestamp(`[ProfileManager] Loaded ${parsedProfiles.length} profiles.`)
            endTiming({ count: parsedProfiles.length })
        } catch (error) {
            logErrorWithTimestamp("[ProfileManager] Failed to load profiles:", error)
            setProfiles([])
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
        } finally {
            setIsLoading(false)
        }
    }, [])

    /**
     * Load the current active profile name.
     */
    const loadCurrentProfileName = useCallback(async () => {
        const endTiming = startTiming("profile_load_current_name", "state")
        try {
            // Wait for database to be initialized before loading current profile name.
            await databaseManager.initialize()

            const profileName = await databaseManager.getCurrentProfileName()
            setCurrentProfileName(profileName)
            endTiming({ name: profileName })
        } catch (error) {
            logErrorWithTimestamp("[ProfileManager] Failed to load current profile name:", error)
            setCurrentProfileName(null)
            endTiming({ status: "error", error: error instanceof Error ? error.message : String(error) })
        }
    }, [])

    // Initial load of profiles and current profile name.
    useEffect(() => {
        loadProfiles()
        loadCurrentProfileName()
    }, [loadProfiles, loadCurrentProfileName])

    // Memoize the provider value to prevent cascading re-renders.
    const value = useMemo(
        () => ({
            profiles,
            currentProfileName,
            isLoading,
            loadProfiles,
            loadCurrentProfileName,
            setProfiles,
            setCurrentProfileName,
            setIsLoading,
        }),
        [profiles, currentProfileName, isLoading, loadProfiles, loadCurrentProfileName]
    )

    return <ProfileContext.Provider value={value}>{children}</ProfileContext.Provider>
}

/**
 * Hook to use the ProfileContext. It is required that it is used within a ProfileProvider.
 * @returns The profile context with the profiles, current profile name, loading state, and profile management functions.
 */
export const useProfileContext = () => {
    const context = useContext(ProfileContext)
    if (context === undefined) {
        throw new Error("useProfileContext must be used within a ProfileProvider")
    }
    return context
}
