import React, { useState, useMemo, useCallback } from "react"
import { View, Text, StyleSheet, Pressable, TextInput } from "react-native"
import Ionicons from "@react-native-vector-icons/ionicons"
import { SheetModal } from "../ui/sheet-modal"
import { useModalShellStyles } from "../ui/modal-shell-styles"
import { useTheme } from "../../context/ThemeContext"
import { useProfileManager } from "../../hooks/useProfileManager"
import { Settings } from "../../context/BotStateContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** Per-distance labels shown in the snapshot stat table. */
const DISTANCE_TYPES = ["Sprint", "Mile", "Med", "Long"] as const

/** Props for `ProfileCreationModal`. */
interface ProfileCreationModalProps {
    /** Whether the modal is currently visible. */
    visible: boolean
    /** Callback to close the modal. */
    onClose: () => void
    /** The current training settings to be saved in the new profile. */
    currentTrainingSettings: Settings["training"]
    /** The current training stat target settings to be saved in the new profile. */
    currentTrainingStatTargetSettings: Settings["trainingStatTarget"]
    /** Optional callback fired after a profile is successfully created. */
    onProfileCreated?: (profileName: string) => void
    /** Optional callback fired when an error occurs. */
    onError?: (message: string) => void
}

/**
 * A modal dialog for creating new training profiles. Renders a name input above a flat snapshot of every training setting, plus a stat targets
 * grid organized by distance. Uses the shared `SheetModal` shell so the body scrolls reliably and the Cancel/Create footer stays locked.
 * @param visible Whether the modal is visible.
 * @param onClose Callback to close the modal.
 * @param currentTrainingSettings The current training settings to save.
 * @param currentTrainingStatTargetSettings The current stat target settings to save.
 * @param onProfileCreated Optional callback fired after successful creation.
 * @param onError Optional callback for error handling.
 * @returns A `SheetModal` containing a profile name input, settings snapshot, and Cancel/Create actions.
 */
const ProfileCreationModal: React.FC<ProfileCreationModalProps> = ({ visible, onClose, currentTrainingSettings, currentTrainingStatTargetSettings, onProfileCreated, onError }) => {
    const { colors } = useTheme()
    const shell = useModalShellStyles()
    const { createProfile } = useProfileManager(onError)
    const [profileName, setProfileName] = useState("")
    const [isCreating, setIsCreating] = useState(false)

    const styles = useMemo(
        () =>
            StyleSheet.create({
                label: { ...TYPE.monoLabel, color: colors.textMuted, marginBottom: SPACING.xs },
                input: {
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.md,
                    paddingHorizontal: SPACING.md,
                    color: colors.text,
                    fontSize: 14,
                    marginBottom: SPACING.md,
                },
                rule: { height: 1, backgroundColor: colors.borderHair, marginVertical: SPACING.md },
                kvRow: { flexDirection: "row", alignItems: "flex-start", justifyContent: "space-between", paddingVertical: 4 },
                kvKey: { ...TYPE.monoLabel, color: colors.textMuted },
                kvVal: { ...TYPE.body, color: colors.text, textAlign: "right" as const, flexShrink: 1, marginLeft: SPACING.md },
                kvValMono: { ...TYPE.monoValue, color: colors.brand, textAlign: "right" as const, marginLeft: SPACING.md },
                statHeader: { ...TYPE.monoLabel, color: colors.textMuted, marginBottom: SPACING.xs },
                statGrid: { gap: 4 },
                statHeaderRow: { flexDirection: "row", paddingBottom: 4, borderBottomWidth: 1, borderBottomColor: colors.borderHair },
                statHeaderCell: { flex: 1, ...TYPE.monoLabel, color: colors.textMuted, textAlign: "right" as const },
                statHeaderCellLeft: { flex: 1.4, ...TYPE.monoLabel, color: colors.textMuted, textAlign: "left" as const },
                statRow: { flexDirection: "row", paddingVertical: 3 },
                statDistance: { flex: 1.4, ...TYPE.body, color: colors.text },
                statCell: { flex: 1, ...TYPE.monoValue, color: colors.brand, textAlign: "right" as const },
                footerRow: { flexDirection: "row", gap: SPACING.sm },
                footerBtn: {
                    flex: 1,
                    paddingVertical: SPACING.sm,
                    borderRadius: RADII.md,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    alignItems: "center",
                    overflow: "hidden",
                },
                footerBtnPrimary: { borderColor: colors.brand, backgroundColor: colors.brand },
                footerBtnDisabled: { opacity: 0.5 },
                footerBtnText: { ...TYPE.body, color: colors.text, fontWeight: "600" as const },
                footerBtnTextPrimary: { color: colors.onBrand },
            }),
        [colors]
    )

    const snapshotRows = useMemo<Array<{ key: string; value: string; mono?: boolean }>>(() => {
        const yesNo = (b: boolean) => (b ? "On" : "Off")
        const joinOrNone = (arr: string[]) => (arr.length > 0 ? arr.join(", ") : "None")
        const rows: Array<{ key: string; value: string; mono?: boolean }> = [
            { key: "BLACKLIST", value: joinOrNone(currentTrainingSettings.trainingBlacklist) },
            { key: "PRIORITY", value: joinOrNone(currentTrainingSettings.statPrioritization) },
            { key: "EVENT PRIORITY", value: joinOrNone(currentTrainingSettings.eventChoiceStatPriority) },
            { key: "SUMMER PRIORITY", value: joinOrNone(currentTrainingSettings.summerTrainingStatPriority) },
            { key: "MAX FAILURE", value: `${currentTrainingSettings.maximumFailureChance}%`, mono: true },
            { key: "DISABLE ON MAXED", value: yesNo(currentTrainingSettings.disableTrainingOnMaxedStat) },
            { key: "RAINBOW BONUS", value: yesNo(currentTrainingSettings.enableRainbowTrainingBonus) },
            { key: "NEAR-MAX FRIEND", value: yesNo(currentTrainingSettings.enablePrioritizeNearMaxFriendship) },
            { key: "PREFERRED DIST", value: currentTrainingSettings.preferredDistanceOverride || "Auto" },
            { key: "MUST REST", value: yesNo(currentTrainingSettings.mustRestBeforeSummer) },
            { key: "TRAIN WIT FINALE", value: yesNo(currentTrainingSettings.trainWitDuringFinale) },
            { key: "PRIORITIZE SKILL", value: yesNo(currentTrainingSettings.enablePrioritizeSkillHints) },
            { key: "WEIGHT BY LEVEL", value: yesNo(currentTrainingSettings.enableTrainingLevelWeighting) },
            { key: "DISABLE TARGETS", value: yesNo(currentTrainingSettings.disableStatTargets) },
            { key: "READ STAT CAPS", value: yesNo(currentTrainingSettings.useDynamicStatCaps) },
            { key: "ANALYSIS CHECK", value: yesNo(currentTrainingSettings.enableTrainingAnalysisValidation) },
            { key: "YOLO DETECTION", value: yesNo(currentTrainingSettings.enableYoloStatDetection) },
            { key: "CLASSIC MILESTONE", value: `${currentTrainingSettings.classicMilestonePercent}%`, mono: true },
            { key: "SENIOR MILESTONE", value: `${currentTrainingSettings.seniorMilestonePercent}%`, mono: true },
            { key: "RISKY TRAINING", value: yesNo(currentTrainingSettings.enableRiskyTraining) },
        ]
        if (currentTrainingSettings.enableRiskyTraining) {
            rows.push({ key: "RISKY MIN GAIN", value: String(currentTrainingSettings.riskyTrainingMinStatGain), mono: true })
            rows.push({ key: "RISKY MAX FAIL", value: `${currentTrainingSettings.riskyTrainingMaxFailureChance}%`, mono: true })
        }
        return rows
    }, [currentTrainingSettings])

    const getStatTargets = useCallback(
        (distance: (typeof DISTANCE_TYPES)[number]) => {
            const distanceMap: Record<(typeof DISTANCE_TYPES)[number], string> = {
                Sprint: "Sprint",
                Mile: "Mile",
                Med: "Medium",
                Long: "Long",
            }
            const prefix = `training${distanceMap[distance]}StatTarget`
            const settings = currentTrainingStatTargetSettings
            return {
                speed: settings[`${prefix}_speedStatTarget` as keyof typeof settings] as number,
                stamina: settings[`${prefix}_staminaStatTarget` as keyof typeof settings] as number,
                power: settings[`${prefix}_powerStatTarget` as keyof typeof settings] as number,
                guts: settings[`${prefix}_gutsStatTarget` as keyof typeof settings] as number,
                wit: settings[`${prefix}_witStatTarget` as keyof typeof settings] as number,
            }
        },
        [currentTrainingStatTargetSettings]
    )

    const tableData = useMemo(() => {
        return DISTANCE_TYPES.map((distance) => ({
            distance,
            ...getStatTargets(distance),
        }))
    }, [getStatTargets])

    /**
     * Handles the creation of a new profile by calling the profile manager hook, then resetting state and closing on success.
     */
    const handleCreate = useCallback(async () => {
        if (!profileName.trim()) {
            return
        }
        try {
            setIsCreating(true)
            const createdProfileName = profileName.trim()
            await createProfile(createdProfileName, {
                training: currentTrainingSettings,
                trainingStatTarget: currentTrainingStatTargetSettings,
            })
            setProfileName("")
            onProfileCreated?.(createdProfileName)
            onClose()
        } catch (error) {
            const errorMessage = `Failed to create profile: ${error instanceof Error ? error.message : String(error)}`
            onError?.(errorMessage)
        } finally {
            setIsCreating(false)
        }
    }, [profileName, createProfile, currentTrainingSettings, currentTrainingStatTargetSettings, onProfileCreated, onClose, onError])

    /** Reset the profile name and close the modal. */
    const handleClose = useCallback(() => {
        setProfileName("")
        onClose()
    }, [onClose])

    const header = (
        <View style={shell.modalHeaderRow}>
            <Text style={shell.modalTitleMono}>CREATE PROFILE</Text>
            <Pressable style={shell.modalCloseChip} onPress={handleClose} android_ripple={{ color: colors.ripple, foreground: true }} accessibilityLabel="Close">
                <Ionicons name="close" size={18} color={colors.text} />
            </Pressable>
        </View>
    )

    const canCreate = !isCreating && !!profileName.trim()
    const footer = (
        <View style={styles.footerRow}>
            <Pressable
                onPress={handleClose}
                disabled={isCreating}
                style={[styles.footerBtn, isCreating && styles.footerBtnDisabled]}
                android_ripple={{ color: colors.ripple, foreground: true }}
                accessibilityRole="button"
            >
                <Text style={styles.footerBtnText}>Cancel</Text>
            </Pressable>
            <Pressable
                onPress={handleCreate}
                disabled={!canCreate}
                style={[styles.footerBtn, styles.footerBtnPrimary, !canCreate && styles.footerBtnDisabled]}
                android_ripple={{ color: colors.ripple, foreground: true }}
                accessibilityRole="button"
            >
                <Text style={[styles.footerBtnText, styles.footerBtnTextPrimary]}>Create</Text>
            </Pressable>
        </View>
    )

    return (
        <SheetModal
            visible={visible}
            onRequestClose={handleClose}
            header={header}
            footer={footer}
            subHeader={
                <>
                    <Text style={styles.label}>NAME</Text>
                    <TextInput
                        style={styles.input}
                        placeholder="Profile name"
                        placeholderTextColor={colors.textMuted}
                        value={profileName}
                        onChangeText={setProfileName}
                        editable={!isCreating}
                        autoCapitalize="words"
                        autoCorrect={false}
                    />
                </>
            }
        >
            <Text style={styles.label}>SNAPSHOT - ALL SETTINGS</Text>
            {snapshotRows.map((row, idx) => (
                <View key={`${row.key}-${idx}`} style={styles.kvRow}>
                    <Text style={styles.kvKey}>{row.key}</Text>
                    <Text style={row.mono ? styles.kvValMono : styles.kvVal}>{row.value}</Text>
                </View>
            ))}
            <View style={styles.rule} />
            <Text style={styles.statHeader}>STAT TARGETS</Text>
            <View style={styles.statGrid}>
                <View style={styles.statHeaderRow}>
                    <View style={styles.statHeaderCellLeft} />
                    <Text style={styles.statHeaderCell}>SPD</Text>
                    <Text style={styles.statHeaderCell}>STA</Text>
                    <Text style={styles.statHeaderCell}>PWR</Text>
                    <Text style={styles.statHeaderCell}>GUT</Text>
                    <Text style={styles.statHeaderCell}>WIT</Text>
                </View>
                {tableData.map((row, idx) => (
                    <View key={`${row.distance}-${idx}`} style={styles.statRow}>
                        <Text style={styles.statDistance}>{row.distance}</Text>
                        <Text style={styles.statCell}>{row.speed}</Text>
                        <Text style={styles.statCell}>{row.stamina}</Text>
                        <Text style={styles.statCell}>{row.power}</Text>
                        <Text style={styles.statCell}>{row.guts}</Text>
                        <Text style={styles.statCell}>{row.wit}</Text>
                    </View>
                ))}
            </View>
        </SheetModal>
    )
}

export default ProfileCreationModal
