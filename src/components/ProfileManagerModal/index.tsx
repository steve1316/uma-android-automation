import React, { useMemo, useState, useEffect, useCallback, useRef } from "react"
import { View, Text, StyleSheet, ScrollView, TouchableOpacity, Modal as RNModal } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import CustomButton from "../CustomButton"
import { Input } from "../ui/input"
import { useProfileManager } from "../../hooks/useProfileManager"
import { Settings } from "../../context/BotStateContext"
import { X, Edit2, Trash2, Save, Check } from "lucide-react-native"
import ProfileComparison from "../ProfileComparison"

interface ProfileManagerModalProps {
    /** Whether the modal is currently visible. */
    visible: boolean
    /** Callback to close the modal. */
    onClose: () => void
    /** The current training settings used for comparison when overwriting. */
    currentTrainingSettings: Settings["training"]
    /** The current training stat target settings used for comparison when overwriting. */
    currentTrainingStatTargetSettings: Settings["trainingStatTarget"]
    /** Optional callback to apply a profile's settings to the current configuration. */
    onOverwriteSettings?: (settings: Partial<Settings>) => Promise<void>
    /** Optional callback fired after a profile is deleted. */
    onProfileDeleted?: (deletedProfileName: string) => void
    /** Optional callback fired after a profile is renamed or updated. */
    onProfileUpdated?: (oldName?: string, newName?: string) => void
    /** Optional callback fired when no differences are detected between current and profile settings. */
    onNoChangesDetected?: (profileName: string) => void
    /** Optional callback fired when an error occurs. */
    onError?: (message: string) => void
}

/**
 * A modal dialog for managing user profiles, supporting viewing, renaming, deleting,
 * and overwriting profiles with the current settings.
 * Shows a ProfileComparison preview before overwriting to highlight differences.
 * Implements manual touch-to-scroll for reliable scrolling within the modal.
 * @param visible Whether the modal is visible.
 * @param onClose Callback to close the modal.
 * @param currentTrainingSettings Current training settings for comparison.
 * @param currentTrainingStatTargetSettings Current training stat target settings for comparison.
 * @param onOverwriteSettings Callback to apply profile settings.
 * @param onProfileDeleted Optional callback fired after a profile is deleted.
 * @param onProfileUpdated Optional callback fired after a profile is renamed or updated.
 * @param onNoChangesDetected Optional callback fired when no differences are detected between current and profile settings.
 * @param onError Optional callback for error handling.
 */
const ProfileManagerModal: React.FC<ProfileManagerModalProps> = ({
    visible,
    onClose,
    currentTrainingSettings,
    currentTrainingStatTargetSettings,
    onOverwriteSettings,
    onProfileDeleted,
    onProfileUpdated,
    onNoChangesDetected,
    onError,
}) => {
    const { colors } = useTheme()
    const { profiles, updateProfile, deleteProfile, loadProfiles, compareWithProfile } = useProfileManager(onError)
    const [profileName, setProfileName] = useState("")
    const [editingProfileId, setEditingProfileId] = useState<number | null>(null)
    const [deleteProfileId, setDeleteProfileId] = useState<number | null>(null)
    const [showDeleteDialog, setShowDeleteDialog] = useState(false)
    const [showComparison, setShowComparison] = useState(false)
    const [overwriteProfileId, setOverwriteProfileId] = useState<number | null>(null)
    const [comparisonData, setComparisonData] = useState<Record<string, { current: any; profile: any }> | null>(null)

    /**
     * These refs and handlers implement a manual touch-to-scroll mechanism (replicating
     * the proven pattern in MultiSelector.tsx).
     */
    const scrollViewRef = useRef<ScrollView | null>(null)
    const lastTouchY = useRef(0)
    const currentScrollY = useRef(0)
    const isScrolling = useRef(false)

    /**
     * Handles the start of a touch event to initialize scrolling.
     * @param event The native touch event.
     */
    const handleTouchStart = (event: any) => {
        const touch = event.nativeEvent.touches[0]
        lastTouchY.current = touch.pageY
        isScrolling.current = false
    }

    /**
     * Handles the movement of a touch event to manually scroll the ScrollView.
     * @param event The native touch event.
     */
    const handleTouchMove = (event: any) => {
        if (!scrollViewRef.current) return

        const touch = event.nativeEvent.touches[0]
        const currentY = touch.pageY
        const deltaY = lastTouchY.current - currentY

        // Only scroll if there is significant movement.
        if (Math.abs(deltaY) > 1) {
            isScrolling.current = true
            // Use a balanced scroll factor for smooth but responsive movement.
            const scrollFactor = 2.0
            const newScrollY = Math.max(0, currentScrollY.current + deltaY * scrollFactor)
            currentScrollY.current = newScrollY

            scrollViewRef.current.scrollTo({
                y: newScrollY,
                animated: false,
            })
            lastTouchY.current = currentY
        }
    }

    /**
     * Handles the end of a touch event.
     */
    const handleTouchEnd = () => {
        isScrolling.current = false
    }

    const styles = useMemo(
        () =>
            StyleSheet.create({
                modal: {
                    flex: 1,
                    justifyContent: "center",
                    alignItems: "center",
                    backgroundColor: "rgba(70, 70, 70, 0.5)",
                },
                /**
                 * The main content area of the modal.
                 * Using maxHeight: "80%" ensures it stays on screen on all devices.
                 * flexShrink: 1 allows it to grow with content but stay within screen limits.
                 */
                modalContent: {
                    backgroundColor: colors.background,
                    borderRadius: 12,
                    padding: 20,
                    width: "90%",
                    maxHeight: "80%",
                    overflow: "hidden",
                    flexShrink: 1,
                },
                header: {
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "center",
                    marginBottom: 20,
                },
                title: {
                    fontSize: 20,
                    fontWeight: "bold",
                    color: colors.foreground,
                },
                closeButton: {
                    padding: 4,
                },
                profileList: {
                    marginTop: 0,
                },
                /**
                 * The main ScrollView for the modal content.
                 * Using flexShrink: 1 allows it to occupy the remaining space
                 * and trigger scrolling when content exceeds modalContent maxHeight.
                 *
                 * NOTE: Manual touch handlers are implemented in the component logic to ensure scrolling reliability.
                 */
                mainScroll: {
                    flexShrink: 1,
                },
                mainScrollContent: {
                    flexGrow: 1,
                    paddingBottom: 20, // Extra padding to ensure bottom content is reachable.
                },
                profileItem: {
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "center",
                    padding: 12,
                    marginBottom: 8,
                    backgroundColor: colors.secondary,
                    borderRadius: 8,
                },
                profileName: {
                    fontSize: 16,
                    color: colors.foreground,
                    flex: 1,
                },
                profileNameInput: {
                    flex: 1,
                    marginRight: 8,
                },
                profileActions: {
                    flexDirection: "row",
                    gap: 8,
                },
                actionButton: {
                    padding: 8,
                },
                buttonRow: {
                    flexDirection: "row",
                    justifyContent: "space-between",
                    gap: 8,
                    marginTop: 16,
                },
                emptyState: {
                    padding: 20,
                    alignItems: "center",
                },
                emptyText: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.6,
                },
            }),
        [colors]
    )

    useEffect(() => {
        if (visible) {
            loadProfiles()
            setProfileName("")
            setEditingProfileId(null)
            setShowComparison(false)
            setOverwriteProfileId(null)
            setComparisonData(null)
        }
    }, [visible, loadProfiles])

    /**
     * Handles the editing of a profile.
     * @param profileId The ID of the profile to edit.
     */
    const handleEditProfile = useCallback(
        (profileId: number) => {
            const profile = profiles.find((p) => p.id === profileId)
            if (profile) {
                setProfileName(profile.name)
                setEditingProfileId(profileId)
            }
        },
        [profiles]
    )

    /**
     * Handles the updating of a profile.
     * @param profileId The ID of the profile to update.
     */
    const handleUpdateProfile = useCallback(async () => {
        if (!profileName.trim() || !editingProfileId) {
            return
        }

        try {
            const newName = profileName.trim()
            await updateProfile(editingProfileId, { name: newName })
            setProfileName("")
            setEditingProfileId(null)
            // Notify parent that a profile was updated.
            onProfileUpdated?.()
        } catch (error) {
            const errorMessage = `Failed to update profile: ${error instanceof Error ? error.message : String(error)}`
            onError?.(errorMessage)
        }
    }, [profileName, editingProfileId, profiles, updateProfile, onProfileUpdated, onError])

    /**
     * Handles the deletion of a profile.
     * @param profileId The ID of the profile to delete.
     */
    const handleDeleteClick = useCallback((profileId: number) => {
        setDeleteProfileId(profileId)
        setShowDeleteDialog(true)
    }, [])

    /**
     * Handles the confirmation of a profile deletion.
     * @param profileId The ID of the profile to delete.
     */
    const handleDeleteConfirm = useCallback(async () => {
        if (!deleteProfileId) {
            return
        }

        try {
            // Get the name of the profile to delete.
            const profileToDelete = profiles.find((p) => p.id === deleteProfileId)
            const deletedProfileName = profileToDelete?.name || ""
            await deleteProfile(deleteProfileId)
            // Reload profiles to ensure the modal shows the updated list immediately.
            await loadProfiles()
            setShowDeleteDialog(false)
            setDeleteProfileId(null)
            // Notify parent that a profile was deleted.
            if (deletedProfileName) {
                onProfileDeleted?.(deletedProfileName)
            }
        } catch (error) {
            setShowDeleteDialog(false)
            setDeleteProfileId(null)
            const errorMessage = `Failed to delete profile: ${error instanceof Error ? error.message : String(error)}`
            onError?.(errorMessage)
        }
    }, [deleteProfileId, profiles, deleteProfile, loadProfiles, onProfileDeleted, onError])

    /**
     * Handles the cancellation of a profile deletion.
     */
    const handleDeleteCancel = useCallback(() => {
        setShowDeleteDialog(false)
        setDeleteProfileId(null)
    }, [])

    /**
     * Handles the cancellation of a profile edit.
     */
    const handleCancelEdit = useCallback(() => {
        setProfileName("")
        setEditingProfileId(null)
    }, [])

    /**
     * Handles the saving of a profile.
     * @param profileId The ID of the profile to save.
     */
    const handleSaveClick = useCallback(
        (profileId: number) => {
            // Get the profile to save.
            const profile = profiles.find((p) => p.id === profileId)
            if (!profile || !onOverwriteSettings) {
                return
            }

            // Compare current settings with the profile's settings.
            const currentSettings: Partial<Settings> = {
                training: currentTrainingSettings,
                trainingStatTarget: currentTrainingStatTargetSettings,
            }
            const comparison = compareWithProfile(profile, currentSettings, ["training", "trainingStatTarget"])

            if (Object.keys(comparison).length > 0) {
                // Show comparison preview before overwriting.
                setOverwriteProfileId(profileId)
                setComparisonData(comparison)
                setShowComparison(true)
            } else {
                // There were no differences so we notify the parent to show a snackbar.
                onNoChangesDetected?.(profile.name)
            }
        },
        [profiles, onOverwriteSettings, compareWithProfile, currentTrainingSettings, currentTrainingStatTargetSettings, onNoChangesDetected]
    )

    /**
     * Handles the confirmation of a profile overwrite.
     * @param profileId The ID of the profile to overwrite.
     */
    const handleConfirmOverwrite = useCallback(
        async (profileId: number) => {
            try {
                // Update the profile with the current settings (overwrite the profile).
                const currentSettings: Partial<Settings> = {
                    training: currentTrainingSettings,
                    trainingStatTarget: currentTrainingStatTargetSettings,
                }
                await updateProfile(profileId, { settings: currentSettings })
                setOverwriteProfileId(null)
                setComparisonData(null)
                setShowComparison(false)
                onProfileUpdated?.()
                onClose()
            } catch (error) {
                const errorMessage = `Failed to overwrite settings: ${error instanceof Error ? error.message : String(error)}`
                onError?.(errorMessage)
            }
        },
        [currentTrainingSettings, currentTrainingStatTargetSettings, updateProfile, onProfileUpdated, onClose, onError]
    )

    /**
     * Handles the cancellation of a profile overwrite.
     */
    const handleCancelOverwrite = useCallback(() => {
        setShowComparison(false)
        setOverwriteProfileId(null)
        setComparisonData(null)
    }, [])

    return (
        <>
            <RNModal visible={visible && !showDeleteDialog} transparent={true} animationType="fade" onRequestClose={onClose}>
                <TouchableOpacity style={styles.modal} activeOpacity={1} onPress={onClose}>
                    <TouchableOpacity style={styles.modalContent} activeOpacity={1} onPress={(e) => e.stopPropagation()}>
                        <View style={styles.header}>
                            <Text style={styles.title}>Manage Profiles</Text>
                            <TouchableOpacity style={styles.closeButton} onPress={onClose}>
                                <X size={24} color={colors.foreground} />
                            </TouchableOpacity>
                        </View>

                        {/*
                         * We use manual touch handlers to manage scrolling because the standard
                         * native ScrollView events are swallowed by the RNModal or
                         * its containing overlays in this project's environment.
                         */}
                        <ScrollView
                            style={styles.mainScroll}
                            contentContainerStyle={styles.mainScrollContent}
                            showsVerticalScrollIndicator={true}
                            nestedScrollEnabled={true}
                            onTouchStart={handleTouchStart}
                            onTouchMove={handleTouchMove}
                            onTouchEnd={handleTouchEnd}
                            ref={scrollViewRef}
                            onScroll={(event) => {
                                const offsetY = event.nativeEvent.contentOffset.y
                                currentScrollY.current = offsetY
                            }}
                        >
                            <View style={styles.profileList}>
                                {profiles.length === 0 ? (
                                    <View style={styles.emptyState}>
                                        <Text style={styles.emptyText}>No profiles yet.</Text>
                                    </View>
                                ) : (
                                    <>
                                        {/* Map through the profiles and display them. */}
                                        {profiles.map((profile) => {
                                            const isEditing = editingProfileId === profile.id
                                            return (
                                                <View key={profile.id} style={styles.profileItem}>
                                                    {isEditing ? (
                                                        <Input
                                                            placeholder="Profile name"
                                                            value={profileName}
                                                            onChangeText={setProfileName}
                                                            onSubmitEditing={handleUpdateProfile}
                                                            style={[styles.profileNameInput, { color: colors.foreground, backgroundColor: colors.background || "#ffffff" }]}
                                                            autoFocus
                                                        />
                                                    ) : (
                                                        <Text style={styles.profileName}>{profile.name}</Text>
                                                    )}
                                                    <View style={styles.profileActions}>
                                                        {isEditing ? (
                                                            <>
                                                                {/* Save button */}
                                                                <TouchableOpacity style={styles.actionButton} onPress={handleUpdateProfile}>
                                                                    <Check size={18} color={colors.primary} />
                                                                </TouchableOpacity>
                                                                {/* Cancel button */}
                                                                <TouchableOpacity style={styles.actionButton} onPress={handleCancelEdit}>
                                                                    <X size={18} color={colors.foreground} />
                                                                </TouchableOpacity>
                                                            </>
                                                        ) : (
                                                            <>
                                                                {/* Edit button */}
                                                                <TouchableOpacity style={styles.actionButton} onPress={() => handleEditProfile(profile.id)}>
                                                                    <Edit2 size={18} color={colors.primary} />
                                                                </TouchableOpacity>
                                                                {/* Delete button */}
                                                                <TouchableOpacity style={styles.actionButton} onPress={() => handleDeleteClick(profile.id)}>
                                                                    <Trash2 size={18} color={colors.destructive} />
                                                                </TouchableOpacity>
                                                            </>
                                                        )}
                                                        {/* Save button */}
                                                        {!isEditing && onOverwriteSettings && (
                                                            <TouchableOpacity style={styles.actionButton} onPress={() => handleSaveClick(profile.id)}>
                                                                <Save size={18} color={colors.primary} />
                                                            </TouchableOpacity>
                                                        )}
                                                    </View>
                                                </View>
                                            )
                                        })}
                                    </>
                                )}
                            </View>

                            {/* Comparison modal */}
                            {showComparison && comparisonData && overwriteProfileId && (
                                <ProfileComparison
                                    comparison={comparisonData}
                                    onConfirm={() => handleConfirmOverwrite(overwriteProfileId)}
                                    onCancel={handleCancelOverwrite}
                                    actionType="overwrite"
                                    category="training"
                                />
                            )}
                        </ScrollView>
                    </TouchableOpacity>
                </TouchableOpacity>
            </RNModal>

            {/* Delete dialog */}
            <RNModal visible={showDeleteDialog} transparent={true} animationType="fade" onRequestClose={handleDeleteCancel} statusBarTranslucent={true}>
                <View style={[styles.modal, { zIndex: 10000 }]}>
                    <View style={[styles.modalContent, { maxWidth: "85%" }]}>
                        <View style={styles.header}>
                            <Text style={styles.title}>Delete Profile</Text>
                            {/* Close button */}
                            <TouchableOpacity style={styles.closeButton} onPress={handleDeleteCancel}>
                                <X size={24} color={colors.foreground} />
                            </TouchableOpacity>
                        </View>
                        <Text style={{ color: colors.foreground, marginBottom: 20 }}>Are you sure you want to delete this profile? This action cannot be undone.</Text>
                        <View style={styles.buttonRow}>
                            <CustomButton onPress={handleDeleteCancel} variant="outline">
                                Cancel
                            </CustomButton>
                            <CustomButton onPress={handleDeleteConfirm} variant="destructive">
                                Delete
                            </CustomButton>
                        </View>
                    </View>
                </View>
            </RNModal>
        </>
    )
}

export default ProfileManagerModal
