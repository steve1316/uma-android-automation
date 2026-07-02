import React, { useContext, useMemo, useState } from "react"
import { View, Text, StyleSheet, Pressable } from "react-native"
import Ionicons from "@react-native-vector-icons/ionicons"
import { Section } from "../../components/ui/section"
import { Row } from "../../components/ui/row"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import InfoCallout from "../../components/ui/info-callout"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import { SkillsContext, defaultSettings } from "../../context/BotStateContext"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** Picker option entry. `chipLabel` is the short text rendered in the row's right-side pill. */
interface StyleOption {
    /** Stored value. */
    value: string
    /** Long label shown inside the SheetModal option list. */
    label: string
    /** Short label rendered in the row's right-side chip. */
    chipLabel: string
}

/** Options for the Running Style picker. */
const RUNNING_STYLE_OPTIONS: StyleOption[] = [
    { value: "inherit", label: "Use [Racing Settings] -> [Original Race Strategy]", chipLabel: "From Racing" },
    { value: "no_preference", label: "Any", chipLabel: "Any" },
    { value: "front_runner", label: "Front Runner", chipLabel: "Front Runner" },
    { value: "pace_chaser", label: "Pace Chaser", chipLabel: "Pace Chaser" },
    { value: "late_surger", label: "Late Surger", chipLabel: "Late Surger" },
    { value: "end_closer", label: "End Closer", chipLabel: "End Closer" },
]

/** Options for the Track Distance picker. */
const TRACK_DISTANCE_OPTIONS: StyleOption[] = [
    { value: "inherit", label: "Use [Training Settings] -> [Preferred Distance Override]", chipLabel: "From Training" },
    { value: "no_preference", label: "Any", chipLabel: "Any" },
    { value: "sprint", label: "Sprint", chipLabel: "Sprint" },
    { value: "mile", label: "Mile", chipLabel: "Mile" },
    { value: "medium", label: "Medium", chipLabel: "Medium" },
    { value: "long", label: "Long", chipLabel: "Long" },
]

/** Options for the Track Surface picker. */
const TRACK_SURFACE_OPTIONS: StyleOption[] = [
    { value: "no_preference", label: "Any", chipLabel: "Any" },
    { value: "turf", label: "Turf", chipLabel: "Turf" },
    { value: "dirt", label: "Dirt", chipLabel: "Dirt" },
]

/** Discriminator for which of the three pickers is currently open. */
type OpenPicker = null | "running" | "distance" | "surface"

/**
 * Global Style settings. Three compact Row+chip selectors for running style, track distance, and track surface. Each chip
 * opens a `SheetModal` with the full option list. A collapsible explainer callout sits below the section.
 * @returns A `Section` containing the three style selectors plus the explainer callout.
 */
const StyleSection: React.FC = () => {
    const { colors } = useTheme()
    const { skills, updateSkills } = useContext(SkillsContext)
    const merged = { ...defaultSettings.skills, ...skills }
    const { preferredRunningStyle, preferredTrackDistance, preferredTrackSurface, prioritizeRecoveryForStamina } = merged
    const modalShellStyles = useModalShellStyles()
    const [openPicker, setOpenPicker] = useState<OpenPicker>(null)

    const styles = useMemo(
        () =>
            StyleSheet.create({
                chip: {
                    ...TYPE.monoLabel,
                    color: colors.brand,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: 2,
                    backgroundColor: colors.brandSubtle,
                    borderRadius: RADII.pill,
                    overflow: "hidden",
                    maxWidth: 140,
                },
                infoBlock: { marginTop: SPACING.sm },
                infoLabel: { ...TYPE.body, color: colors.text, fontWeight: "600" },
                infoDescription: { ...TYPE.body, color: colors.text, opacity: 0.8 },
            }),
        [colors]
    )

    const chipFor = (label: string) => (
        <Text style={styles.chip} numberOfLines={1} ellipsizeMode="tail">
            {label}
        </Text>
    )

    const runningChip = RUNNING_STYLE_OPTIONS.find((o) => o.value === preferredRunningStyle)?.chipLabel ?? "Any"
    const distanceChip = TRACK_DISTANCE_OPTIONS.find((o) => o.value === preferredTrackDistance)?.chipLabel ?? "Any"
    const surfaceChip = TRACK_SURFACE_OPTIONS.find((o) => o.value === preferredTrackSurface)?.chipLabel ?? "Any"

    const renderModal = (kind: OpenPicker, titleMono: string, options: StyleOption[], current: string, onSelect: (value: string) => void) => (
        <SheetModal
            visible={openPicker === kind}
            onRequestClose={() => setOpenPicker(null)}
            header={
                <View style={modalShellStyles.modalHeaderRow}>
                    <Text style={modalShellStyles.modalTitleMono}>{titleMono}</Text>
                    <Pressable style={modalShellStyles.modalCloseChip} onPress={() => setOpenPicker(null)} android_ripple={{ color: colors.ripple, foreground: true }} accessibilityLabel="Close">
                        <Ionicons name="close" size={18} color={colors.text} />
                    </Pressable>
                </View>
            }
            footer={null}
        >
            <View style={modalShellStyles.modalBodyList}>
                {options.map((o) => (
                    <ModalRadioRow
                        key={o.value}
                        label={o.label}
                        selected={o.value === current}
                        onPress={() => {
                            onSelect(o.value)
                            setOpenPicker(null)
                        }}
                    />
                ))}
            </View>
        </SheetModal>
    )

    return (
        <>
            <Section label="Style">
                <SearchableItem
                    id="skill-plan-running-style"
                    title="Running Style for Skills"
                    description="Restricts auto-purchased skills to the preferred running style across all spending strategies."
                >
                    <Row
                        title="Running Style"
                        description="Restricts auto-purchased skills to the preferred running style across all spending strategies."
                        onPress={() => setOpenPicker("running")}
                        right={chipFor(runningChip)}
                    />
                </SearchableItem>
                <SearchableItem
                    id="preferred-distance-override"
                    title="Track Distance for Skills"
                    description="Restricts auto-purchased skills to the preferred track distance across all spending strategies."
                >
                    <Row
                        title="Track Distance"
                        description="Restricts auto-purchased skills to the preferred track distance across all spending strategies."
                        onPress={() => setOpenPicker("distance")}
                        right={chipFor(distanceChip)}
                    />
                </SearchableItem>
                <SearchableItem
                    id="preferred-track-surface"
                    title="Track Surface for Skills"
                    description="Restricts auto-purchased skills to the preferred track surface across all spending strategies."
                >
                    <Row
                        title="Track Surface"
                        description="Restricts auto-purchased skills to the preferred track surface across all spending strategies."
                        onPress={() => setOpenPicker("surface")}
                        right={chipFor(surfaceChip)}
                    />
                </SearchableItem>
                <ToggleSetting
                    id="prioritize-recovery-for-stamina"
                    title="Prioritize Recovery Skills for Stamina"
                    description="On Medium/Long builds, nudge recovery skills up the auto-purchase ranking so the trainee holds pace longer. Has no effect on Sprint/Mile builds."
                    checked={prioritizeRecoveryForStamina}
                    onCheckedChange={(checked) => updateSkills({ prioritizeRecoveryForStamina: checked })}
                />
            </Section>

            <InfoCallout title="How Running Style affects skill picks">
                <Text style={styles.infoLabel}>There are two different groups of Running Style skills.</Text>
                <View style={styles.infoBlock}>
                    <Text style={styles.infoDescription}>
                        The first are skills that specifically say in their description that they are for a specific running style. These cannot be activated unless the trainee is using that running
                        style.
                    </Text>
                </View>
                <View style={styles.infoBlock}>
                    <Text style={styles.infoDescription}>
                        The second are skills that do not say they are for a running style, but have activation conditions which limit which styles would actually be able to activate them (ignoring
                        rare cases).
                    </Text>
                </View>
                <View style={styles.infoBlock}>
                    <Text style={styles.infoDescription}>
                        This setting will filter skills based on both of these conditions. This helps us avoid having situations like an End Closer purchasing a skill like "Keeping the Lead". This
                        skill doesn't require using the Front Runner style to activate, but it does require the runner to be in the lead mid-race which is very unlikely for an End Closer.
                    </Text>
                </View>
                <Text style={[styles.infoLabel, { marginTop: SPACING.md }]}>Detailed breakdown of examples:</Text>
                <View style={styles.infoBlock}>
                    <Text style={styles.infoLabel}>Use [Racing Settings] {"->"} [Original Race Strategy]</Text>
                    <Text style={styles.infoDescription}>
                        - Inherits the running style from your Racing Settings. For example, if you set the Strategy to "Late Surger" in Racing Settings, only Late Surger skills will be considered.
                    </Text>
                </View>
                <View style={styles.infoBlock}>
                    <Text style={styles.infoLabel}>Any</Text>
                    <Text style={styles.infoDescription}>
                        - Does not filter any skills based on running style. For example, even if your trainee is an "End Closer", the bot may still purchase "Pace Chaser Corners O" (a Pace Chaser
                        skill) if it's available.
                    </Text>
                </View>
                <View style={styles.infoBlock}>
                    <Text style={styles.infoLabel}>Front Runner</Text>
                    <Text style={styles.infoDescription}>
                        - Only considers skills that are compatible with the Front Runner style. For example, skills like "Escape Artist" will be included, while "Outer Swell" (Late Surger) will be
                        ignored.
                    </Text>
                </View>
            </InfoCallout>

            {renderModal("running", "RUNNING STYLE", RUNNING_STYLE_OPTIONS, preferredRunningStyle, (v) => updateSkills({ preferredRunningStyle: v } as any))}
            {renderModal("distance", "TRACK DISTANCE", TRACK_DISTANCE_OPTIONS, preferredTrackDistance, (v) => updateSkills({ preferredTrackDistance: v } as any))}
            {renderModal("surface", "TRACK SURFACE", TRACK_SURFACE_OPTIONS, preferredTrackSurface, (v) => updateSkills({ preferredTrackSurface: v } as any))}
        </>
    )
}

export default React.memo(StyleSection)
