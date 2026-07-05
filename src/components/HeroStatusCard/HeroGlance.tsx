import React, { useMemo } from "react"
import { Pressable, StyleSheet, Text, View } from "react-native"
import { Bug, FlaskConical, Bot, ChevronRight, type LucideIcon } from "lucide-react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import ValuePill from "../ui/value-pill"
import TintedChip from "../ui/tinted-chip"

/** A settings screen the hero glance can deep-link into. */
export type HeroGlanceTarget = "debug" | "srs" | "skills" | "training"

/** One active status flag rendered as a tappable chip. */
interface FlagChip {
    /** Stable key for the list. */
    key: string
    /** Lucide icon for the flag. */
    icon: LucideIcon
    /** Chip label (the test name for the Debug Test chip). */
    label: string
    /** Resolved tint color. */
    tint: string
    /** Where tapping the chip navigates. */
    target: HeroGlanceTarget
}

/** Props for `HeroGlance`. */
export interface HeroGlanceProps {
    /** Whether Debug Mode is on (renders the Debug chip). */
    debugMode: boolean
    /** The armed debug test's display name, or null when none is armed (renders the Test chip). */
    activeTest: string | null
    /** Whether the Smart Race Solver is on (renders the SRS chip). */
    srs: boolean
    /** Enabled skill-plan titles for the Plans row. */
    planNames: string[]
    /** Skill-point threshold for the Plans row, or null to omit the threshold pill. */
    spThreshold: number | null
    /** Ordered stat priority abbreviations for the Priority row. */
    priority: string[]
    /** Navigate to a settings screen when a chip or row is tapped. */
    onNavigate: (target: HeroGlanceTarget) => void
}

/**
 * The "at a glance" zone below the hero header: active status chips (Debug / Debug Test / SRS), an enabled skill-plans row, and the stat priority row.
 * Each piece renders only when it has something to show, so an all-off config collapses the zone to nothing. Chips and rows are tappable shortcuts that
 * deep-link into their settings screens via `onNavigate`.
 * @param debugMode Whether Debug Mode is on.
 * @param activeTest The armed debug test's display name, or null.
 * @param srs Whether the Smart Race Solver is on.
 * @param planNames Enabled skill-plan titles.
 * @param spThreshold Skill-point threshold, or null to omit the pill.
 * @param priority Ordered stat priority abbreviations.
 * @param onNavigate Navigation callback invoked with the tapped target.
 * @returns The glance zone, or a compact subset when only some data is present.
 */
const HeroGlanceImpl = ({ debugMode, activeTest, srs, planNames, spThreshold, priority, onNavigate }: HeroGlanceProps) => {
    const { colors } = useTheme()
    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: { padding: SPACING.md, gap: SPACING.sm },
                chipRow: { flexDirection: "row", flexWrap: "wrap", gap: 6, alignItems: "center" },
                row: { flexDirection: "row", alignItems: "center", gap: SPACING.md, marginHorizontal: -SPACING.xs, paddingHorizontal: SPACING.xs, paddingVertical: 2, borderRadius: RADII.sm },
                rowLabel: { ...TYPE.monoLabel, color: colors.textMuted, width: 68 },
                rowVal: { flex: 1, flexDirection: "row", flexWrap: "wrap", gap: 6, alignItems: "center" },
            }),
        [colors]
    )

    const flagChips: FlagChip[] = []
    if (debugMode) flagChips.push({ key: "debug", icon: Bug, label: "Debug", tint: colors.warning, target: "debug" })
    if (activeTest) flagChips.push({ key: "test", icon: FlaskConical, label: activeTest, tint: colors.info, target: "debug" })
    if (srs) flagChips.push({ key: "srs", icon: Bot, label: "SRS", tint: colors.brand, target: "srs" })

    const renderRow = (label: string, value: React.ReactNode, target: HeroGlanceTarget) => (
        <Pressable onPress={() => onNavigate(target)} android_ripple={{ color: colors.ripple }} style={styles.row}>
            <Text style={styles.rowLabel}>{label}</Text>
            <View style={styles.rowVal}>{value}</View>
            <ChevronRight size={16} color={colors.textSubtle} />
        </Pressable>
    )

    return (
        <View style={styles.container}>
            {flagChips.length > 0 ? (
                <View style={styles.chipRow}>
                    {flagChips.map((chip) => (
                        <TintedChip key={chip.key} icon={chip.icon} label={chip.label} tint={chip.tint} onPress={() => onNavigate(chip.target)} />
                    ))}
                </View>
            ) : null}

            {planNames.length > 0
                ? renderRow(
                      "Plans",
                      <>
                          {planNames.map((name) => (
                              <ValuePill key={name} label={name} />
                          ))}
                          {spThreshold !== null ? <ValuePill label={`SP ≥ ${spThreshold}`} /> : null}
                      </>,
                      "skills"
                  )
                : null}

            {priority.length > 0
                ? renderRow(
                      "Priority",
                      priority.map((abbr) => <ValuePill key={abbr} tone="neutral" label={abbr} />),
                      "training"
                  )
                : null}
        </View>
    )
}

export const HeroGlance = React.memo(HeroGlanceImpl)
export default HeroGlance
