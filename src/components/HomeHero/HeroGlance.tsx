import React, { useMemo } from "react"
import { Pressable, StyleSheet, Text, View } from "react-native"
import { ChevronRight } from "lucide-react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import ValuePill from "../ui/value-pill"

/** A settings screen the hero chips and glance rows can deep-link into. `debug` targets the Debug Mode toggle, `debugTest` the armed test's row. */
export type HeroGlanceTarget = "debug" | "debugTest" | "srs" | "skills" | "training" | "racing"

/** Layout for the glance zone. */
const glanceStyles = StyleSheet.create({ container: { gap: SPACING.xs } })

/** Props for `GlanceRow`. */
export interface GlanceRowProps {
    /** Uppercase label in the left column. */
    label: string
    /** The row's values, laid out in a wrapping line. */
    children: React.ReactNode
    /** Opens the row's settings screen. Without it the row is not tappable and shows no chevron. */
    onPress?: () => void
}

/**
 * One labelled drawer row: a fixed-width label column and a wrapping value area, shared by the Plans, Priority, Profile and Dailies rows so they line up.
 * @param label Uppercase label.
 * @param children The row's values.
 * @param onPress Opens the row's settings screen, or undefined for a plain row.
 * @returns The row.
 */
export const GlanceRow = ({ label, children, onPress }: GlanceRowProps) => {
    const { colors } = useTheme()
    const styles = useMemo(
        () =>
            StyleSheet.create({
                row: { flexDirection: "row", alignItems: "center", gap: SPACING.md, marginHorizontal: -SPACING.xs, paddingHorizontal: SPACING.xs, paddingVertical: 2, borderRadius: RADII.sm },
                rowLabel: { ...TYPE.monoLabel, color: colors.textMuted, width: 60 },
                rowVal: { flex: 1, flexDirection: "row", flexWrap: "wrap", gap: 6, alignItems: "center" },
            }),
        [colors]
    )
    return (
        <Pressable onPress={onPress} disabled={!onPress} android_ripple={onPress ? { color: colors.ripple } : undefined} style={styles.row}>
            <Text style={styles.rowLabel}>{label}</Text>
            <View style={styles.rowVal}>{children}</View>
            {onPress ? <ChevronRight size={16} color={colors.textSubtle} /> : null}
        </Pressable>
    )
}

/** Props for `HeroGlance`. */
export interface HeroGlanceProps {
    /** Enabled skill-plan titles for the Plans row. */
    planNames: string[]
    /** Skill-point threshold for the Plans row, or null to omit the threshold pill. */
    spThreshold: number | null
    /** Ordered stat priority abbreviations for the Priority row. */
    priority: string[]
    /** Navigate to a settings screen when a row is tapped. */
    onNavigate: (target: HeroGlanceTarget) => void
}

/**
 * The "at a glance" rows in the hero drawer: an enabled skill-plans row and the stat priority row. Each renders only when it has something to show, and both are
 * tappable shortcuts that deep-link into their settings screens via `onNavigate`. The status chips (SRS / Debug / Test / Style) live on the header status line, not here.
 * @param planNames Enabled skill-plan titles.
 * @param spThreshold Skill-point threshold, or null to omit the pill.
 * @param priority Ordered stat priority abbreviations.
 * @param onNavigate Navigation callback invoked with the tapped target.
 * @returns The glance zone with any active Plans and Priority rows.
 */
const HeroGlanceImpl = ({ planNames, spThreshold, priority, onNavigate }: HeroGlanceProps) => {
    const renderRow = (label: string, value: React.ReactNode, target: HeroGlanceTarget) => (
        <GlanceRow label={label} onPress={() => onNavigate(target)}>
            {value}
        </GlanceRow>
    )

    return (
        <View style={glanceStyles.container}>
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
