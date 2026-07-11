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
 * The "at a glance" zone below the hero header: an enabled skill-plans row and the stat priority row. Each renders only when it has something to show, and both are
 * tappable shortcuts that deep-link into their settings screens via `onNavigate`. The status chips (SRS / Debug / Test / Style) live on the header status line, not here.
 * @param planNames Enabled skill-plan titles.
 * @param spThreshold Skill-point threshold, or null to omit the pill.
 * @param priority Ordered stat priority abbreviations.
 * @param onNavigate Navigation callback invoked with the tapped target.
 * @returns The glance zone with any active Plans and Priority rows.
 */
const HeroGlanceImpl = ({ planNames, spThreshold, priority, onNavigate }: HeroGlanceProps) => {
    const { colors } = useTheme()
    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: { padding: SPACING.md, gap: SPACING.sm },
                row: { flexDirection: "row", alignItems: "center", gap: SPACING.md, marginHorizontal: -SPACING.xs, paddingHorizontal: SPACING.xs, paddingVertical: 2, borderRadius: RADII.sm },
                rowLabel: { ...TYPE.monoLabel, color: colors.textMuted, width: 68 },
                rowVal: { flex: 1, flexDirection: "row", flexWrap: "wrap", gap: 6, alignItems: "center" },
            }),
        [colors]
    )

    const renderRow = (label: string, value: React.ReactNode, target: HeroGlanceTarget) => (
        <Pressable onPress={() => onNavigate(target)} android_ripple={{ color: colors.ripple }} style={styles.row}>
            <Text style={styles.rowLabel}>{label}</Text>
            <View style={styles.rowVal}>{value}</View>
            <ChevronRight size={16} color={colors.textSubtle} />
        </Pressable>
    )

    return (
        <View style={styles.container}>
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
