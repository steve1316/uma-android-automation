import React, { useState, useEffect, useMemo, useCallback } from "react"
import { View, StyleSheet, TouchableOpacity, Text } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import CustomSelect from "../CustomSelect"
import { useProfileManager } from "../../hooks/useProfileManager"
import { DEFAULT_PROFILE_NAME } from "../../context/ProfileContext"
import ProfileManagerModal from "../ProfileManagerModal"
import ProfileCreationModal from "../ProfileCreationModal"
import { Settings } from "../../context/BotStateContext"
import { databaseManager } from "../../lib/database"
import { Plus, Settings as SettingsIcon } from "lucide-react-native"

interface ProfileSelectorProps {
    /** The current training settings, passed to profile creation and comparison. */
    currentTrainingSettings: Settings["training"]
    /** The current training stat target settings, passed to profile creation and comparison. */
    currentTrainingStatTargetSettings: Settings["trainingStatTarget"]
    /** Optional callback to apply a profile's settings to the current configuration. */
    onOverwriteSettings?: (settings: Partial<Settings>) => Promise<void>
    /** Optional callback fired after a profile is deleted. */
    onProfileDeleted?: (deletedProfileName: string) => void
    /** Optional callback fired when no differences are detected between current and profile settings. */
    onNoChangesDetected?: (profileName: string) => void
    /** Optional callback fired when an error occurs. */
    onError?: (message: string) => void
}

/**
 * Get the profile name to select based on available profiles.
 * @param profiles The list of available profiles.
 * @returns The default profile name to select.
 */
const getDefaultSelectedProfile = (profiles: Array<{ name: string }>): string => {
    return profiles.length > 0 ? profiles[0].name : DEFAULT_PROFILE_NAME
}

/**
 * A profile selector component with a dropdown, create button, and manage button.
 * Handles profile switching (loading settings from the selected profile),
 * profile creation, and profile management via modals.
 * @param currentTrainingSettings Current training settings passed to modals.
 * @param currentTrainingStatTargetSettings Current stat target settings passed to modals.
 * @param onOverwriteSettings Callback to apply profile settings.
 * @param onProfileDeleted Callback fired after profile deletion.
 * @param onNoChangesDetected Callback fired when no changes are detected between current and profile settings.
 * @param onError Optional callback for error handling.
 */
const ProfileSelector: React.FC<ProfileSelectorProps> = ({ currentTrainingSettings, currentTrainingStatTargetSettings, onOverwriteSettings, onProfileDeleted, onNoChangesDetected, onError }) => {
    const { colors } = useTheme()
    const { profiles, loadProfiles, switchProfile, currentProfileName } = useProfileManager(onError)
    const [showManageModal, setShowManageModal] = useState(false)
    const [showCreateModal, setShowCreateModal] = useState(false)
    const [selectedProfileName, setSelectedProfileName] = useState<string>(DEFAULT_PROFILE_NAME)
    const [pendingProfileSwitch, setPendingProfileSwitch] = useState<string | null>(null)

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: {
                    padding: 16,
                    backgroundColor: colors.background,
                    borderRadius: 8,
                    borderWidth: 1,
                    borderColor: colors.border,
                },
                row: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: 12,
                },
                selectContainer: {
                    flex: 1,
                },
                iconButton: {
                    padding: 8,
                    borderRadius: 8,
                    backgroundColor: colors.secondary,
                    justifyContent: "center",
                    alignItems: "center",
                },
                description: {
                    fontSize: 12,
                    color: colors.foreground,
                    opacity: 0.7,
                },
            }),
        [colors]
    )

    // Initialize and sync selected profile with current active profile and available profiles.
    useEffect(() => {
        // If there's an active profile and it exists in the profiles list, select it.
        if (currentProfileName && profiles.some((p) => p.name === currentProfileName)) {
            if (selectedProfileName !== currentProfileName) {
                setSelectedProfileName(currentProfileName)
            }
            return
        }

        // If no active profile is set, check if current selection is valid.
        if (currentProfileName === null) {
            // If no profiles exist, use default.
            if (profiles.length === 0) {
                if (selectedProfileName !== DEFAULT_PROFILE_NAME) {
                    setSelectedProfileName(DEFAULT_PROFILE_NAME)
                }
                return
            }

            // If currently selected profile doesn't exist, or we're on default but profiles exist, switch to first profile.
            const currentProfileExists = profiles.some((p) => p.name === selectedProfileName)
            if (!currentProfileExists || selectedProfileName === DEFAULT_PROFILE_NAME) {
                setSelectedProfileName(getDefaultSelectedProfile(profiles))
            }
            return
        }

        // If active profile doesn't exist in profiles list, fall back to default logic.
        const currentProfileExists = profiles.some((p) => p.name === selectedProfileName)
        if (!currentProfileExists) {
            setSelectedProfileName(getDefaultSelectedProfile(profiles))
        }
    }, [profiles, currentProfileName, selectedProfileName])

    // Build the profile dropdown options: Display the default profile if no profiles exist, otherwise show the available profiles.
    const profileOptions = useMemo(() => {
        return profiles.length === 0 ? [{ value: DEFAULT_PROFILE_NAME, label: DEFAULT_PROFILE_NAME }] : profiles.map((p) => ({ value: p.name, label: p.name }))
    }, [profiles])

    /**
     * Handles the change of the selected profile.
     * @param value The new profile name to select.
     */
    const handleProfileChange = useCallback(
        async (value: string | undefined) => {
            if (!value) {
                return
            }

            // Update the selected profile name immediately for UI feedback.
            setSelectedProfileName(value)

            if (!onOverwriteSettings) {
                // If no overwrite callback, just update the display.
                return
            }

            try {
                if (value === DEFAULT_PROFILE_NAME) {
                    // Default Profile - keep current settings as-is and clear current profile name.
                    await switchProfile(null)
                    // Update settings context to reflect that currentProfileName is now null.
                    if (onOverwriteSettings) {
                        await onOverwriteSettings({})
                    }
                    return
                } else {
                    // Find the selected profile and apply its settings.
                    const selectedProfile = profiles.find((p) => p.name === value)
                    if (selectedProfile) {
                        // Switch to the profile (this updates currentProfileName in the database).
                        await switchProfile(value)
                        // Apply the profile's settings immediately when switching.
                        await onOverwriteSettings(selectedProfile.settings)
                    } else {
                        console.warn(`Profile "${value}" not found in profiles list.`)
                    }
                }
            } catch (error) {
                // If overwrite fails, revert to the previous selection or the first available profile.
                console.error("Failed to apply profile settings:", error)
                setSelectedProfileName(getDefaultSelectedProfile(profiles))
            }
        },
        [profiles, onOverwriteSettings]
    )

    // Handle pending profile switch after profiles have been reloaded.
    useEffect(() => {
        if (pendingProfileSwitch && profiles.some((p) => p.name === pendingProfileSwitch)) {
            handleProfileChange(pendingProfileSwitch)
            setPendingProfileSwitch(null)
        }
    }, [profiles, pendingProfileSwitch, handleProfileChange])

    return (
        <View style={styles.container}>
            <View style={styles.row}>
                <View style={styles.selectContainer}>
                    <CustomSelect placeholder="Select a profile" options={profileOptions} value={selectedProfileName} onValueChange={handleProfileChange} width="100%" />
                </View>
                {/* Create profile button */}
                <TouchableOpacity style={styles.iconButton} onPress={() => setShowCreateModal(true)}>
                    <Plus size={20} color={colors.foreground} />
                </TouchableOpacity>
                {/* Manage profiles button */}
                <TouchableOpacity style={styles.iconButton} onPress={() => setShowManageModal(true)}>
                    <SettingsIcon size={20} color={colors.foreground} />
                </TouchableOpacity>
            </View>

            <View style={[styles.row, { marginTop: 12 }]}>
                <Text style={styles.description}>Profiles constitute only the Training settings and stat targets. Other settings are not saved in profiles.</Text>
            </View>

            <ProfileCreationModal
                visible={showCreateModal}
                onClose={() => setShowCreateModal(false)}
                currentTrainingSettings={currentTrainingSettings}
                currentTrainingStatTargetSettings={currentTrainingStatTargetSettings}
                onProfileCreated={async (profileName) => {
                    setShowCreateModal(false)
                    // Set pending switch and reload profiles. The useEffect will handle switching once the profile gets added to the list.
                    setPendingProfileSwitch(profileName)
                    await loadProfiles()
                }}
                onError={onError}
            />

            <ProfileManagerModal
                visible={showManageModal}
                onClose={async () => {
                    setShowManageModal(false)
                    // Reload profiles to ensure we have the latest list after any deletions.
                    await loadProfiles()
                }}
                currentTrainingSettings={currentTrainingSettings}
                currentTrainingStatTargetSettings={currentTrainingStatTargetSettings}
                onOverwriteSettings={onOverwriteSettings}
                onProfileDeleted={async (deletedProfileName) => {
                    // Reload profiles first to get the latest state after deletion.
                    await loadProfiles()
                    // If the deleted profile was selected, reset appropriately.
                    if (selectedProfileName === deletedProfileName) {
                        // Check database directly to see if any profiles remain.
                        const updatedProfiles = await databaseManager.getAllProfiles()
                        if (updatedProfiles.length === 0) {
                            // All profiles deleted - switch to default and ensure currentProfileName is null.
                            await switchProfile(null)
                            setSelectedProfileName(DEFAULT_PROFILE_NAME)
                            // Update settings context to reflect that currentProfileName is now null.
                            if (onOverwriteSettings) {
                                await onOverwriteSettings({})
                            }
                        } else {
                            // Switch to the first remaining profile.
                            await handleProfileChange(updatedProfiles[0].name)
                        }
                    }
                    // The useEffect will also handle updating selectedProfileName when profiles state updates.
                    if (onProfileDeleted) {
                        onProfileDeleted(deletedProfileName)
                    }
                }}
                onProfileUpdated={async (oldName, newName) => {
                    await loadProfiles()
                    // If the renamed profile was the selected one, update to the new name.
                    if (oldName && newName && selectedProfileName === oldName) {
                        setSelectedProfileName(newName)
                    }
                }}
                onNoChangesDetected={onNoChangesDetected}
                onError={onError}
            />
        </View>
    )
}

export default React.memo(ProfileSelector)
