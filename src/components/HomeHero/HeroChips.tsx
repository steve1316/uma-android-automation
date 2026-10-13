import React, { useMemo } from "react"
import { Pressable, StyleSheet, Text } from "react-native"
import { Bot, Bug, CalendarCheck, ChevronRight, FlaskConical, ListChecks } from "lucide-react-native"
import Ionicons from "@react-native-vector-icons/ionicons"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import type { HeroGlanceTarget } from "./HeroGlance"
import type { DailiesChip } from "./heroDailiesData"
import { GlanceRow } from "./HeroGlance"
import ValuePill from "../ui/value-pill"

/** Props for `NavChip`. */
interface NavChipProps {
    /** Rendered leading glyph (a Lucide or Ionicons icon element), colored by the caller. */
    icon: React.ReactNode
    /** Uppercase mono label - the chip's current value. */
    label: string
    /** Label color. */
    tint: string
    /** Tap handler; deep-links the chip to its settings screen. */
    onPress: () => void
}

/** Props for `HeroDebugChips`. */
export interface HeroDebugChipsProps {
    /** Whether Debug Mode is on (renders the Debug chip). */
    debugMode: boolean
    /** The armed debug test's display name, or null when none is armed (renders the Test chip). */
    activeTest: string | null
    /** The run mode the armed test needs when it differs from the current one, such as "Dailies", or null when it matches. */
    testNeedsMode: string | null
    /** Navigate to Debug Settings when a chip is tapped. */
    onNavigate: (target: HeroGlanceTarget) => void
}

/** Props for `HeroChips`. */
export interface HeroChipsProps extends HeroDebugChipsProps {
    /** Whether the Smart Race Solver is on (renders the SRS chip). */
    srs: boolean
    /** Race strategy label for the always-present Style chip (e.g. "Auto", "Late", "Per-distance"). */
    raceStyle: string
    /** Display titles of the enabled skill plans, one chip each. */
    planNames: string[]
}

/**
 * One hero status-line chip: a leading icon, an uppercase value, and a trailing chevron marking it as a tappable deep-link.
 * The icon is caller-rendered so a chip can use either a Lucide or an Ionicons glyph and tint the icon independently of the label.
 * @param icon The rendered leading glyph.
 * @param label The uppercase value label.
 * @param tint The label color.
 * @param onPress Tap handler.
 * @returns A tappable pill chip with a trailing chevron.
 */
const NavChip = ({ icon, label, tint, onPress }: NavChipProps) => {
    const { colors } = useTheme()
    const styles = useMemo(
        () =>
            StyleSheet.create({
                chip: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: SPACING.xs,
                    paddingLeft: SPACING.sm,
                    paddingRight: 6,
                    paddingVertical: 3,
                    borderRadius: RADII.pill,
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    overflow: "hidden",
                },
                label: { ...TYPE.monoLabel, color: tint },
            }),
        [colors, tint]
    )
    return (
        <Pressable onPress={onPress} android_ripple={{ color: colors.ripple }} style={styles.chip} accessibilityRole="button" accessibilityLabel={label}>
            {icon}
            <Text style={styles.label}>{label}</Text>
            <ChevronRight size={13} color={colors.textSubtle} />
        </Pressable>
    )
}

/**
 * The Debug and armed-test chips, shown first on the chip line in both run modes so an armed test is never missed. Debug tints warning, Test info. A test
 * armed for the other run mode tints warning and says which mode it needs, since starting now would stop without running it.
 * @param debugMode Whether Debug Mode is on.
 * @param activeTest The armed debug test's display name, or null.
 * @param testNeedsMode The run mode the armed test needs when it differs from the current one, or null.
 * @param onNavigate Navigation callback invoked with the tapped target.
 * @returns A fragment with the active chips, or nothing.
 */
const HeroDebugChipsImpl = ({ debugMode, activeTest, testNeedsMode, onNavigate }: HeroDebugChipsProps) => {
    const { colors } = useTheme()
    const testTint = testNeedsMode ? colors.warning : colors.info
    return (
        <>
            {debugMode ? <NavChip icon={<Bug size={12} color={colors.warning} />} label="Debug" tint={colors.warning} onPress={() => onNavigate("debug")} /> : null}
            {activeTest ? (
                <NavChip
                    icon={<FlaskConical size={12} color={testTint} />}
                    label={testNeedsMode ? `${activeTest} (${testNeedsMode} only)` : activeTest}
                    tint={testTint}
                    onPress={() => onNavigate("debugTest")}
                />
            ) : null}
        </>
    )
}

/**
 * The career chip cluster: Debug / Test / SRS when active, the always-present Style chip, then one chip per enabled skill plan. Returned as a fragment so
 * the caller lays the chips out, which in the hero is a single sideways-scrolling row. SRS tints brand. Style is neutral-bright with a muted walk icon.
 * @param debugMode Whether Debug Mode is on.
 * @param activeTest The armed debug test's display name, or null.
 * @param testNeedsMode The run mode the armed test needs when it differs from the current one, or null.
 * @param srs Whether the Smart Race Solver is on.
 * @param raceStyle Race strategy label for the Style chip.
 * @param planNames Display titles of the enabled skill plans.
 * @param onNavigate Navigation callback invoked with the tapped target.
 * @returns A fragment of nav-chips for the hero status line.
 */
const HeroChipsImpl = ({ debugMode, activeTest, testNeedsMode, srs, raceStyle, planNames, onNavigate }: HeroChipsProps) => {
    const { colors } = useTheme()
    return (
        <>
            <HeroDebugChipsImpl debugMode={debugMode} activeTest={activeTest} testNeedsMode={testNeedsMode} onNavigate={onNavigate} />
            {srs ? <NavChip icon={<Bot size={12} color={colors.brand} />} label="SRS" tint={colors.brand} onPress={() => onNavigate("srs")} /> : null}
            <NavChip icon={<Ionicons name="walk" size={14} color={colors.textMuted} />} label={raceStyle} tint={colors.text} onPress={() => onNavigate("racing")} />
            {planNames.map((name) => (
                <NavChip key={name} icon={<ListChecks size={12} color={colors.textMuted} />} label={name} tint={colors.text} onPress={() => onNavigate("skills")} />
            ))}
        </>
    )
}

/** Props for `HeroDailiesChips`. */
export interface HeroDailiesChipsProps {
    /** The enabled Dailies parts, from `dailiesChips()`. */
    chips: DailiesChip[]
    /** Opens Dailies Settings at the given row. */
    onNavigate: (targetId: string) => void
}

/**
 * The Dailies chip cluster shown in place of the career chips while the run mode is Dailies. One neutral chip per enabled part, matching the career Style and plan chips.
 * @param chips The enabled parts.
 * @param onNavigate Opens Dailies Settings at the tapped part's row.
 * @returns A fragment of nav-chips.
 */
const HeroDailiesChipsImpl = ({ chips, onNavigate }: HeroDailiesChipsProps) => {
    const { colors } = useTheme()
    return (
        <>
            {chips.map((chip) => (
                <NavChip key={chip.targetId} icon={<CalendarCheck size={12} color={colors.textMuted} />} label={chip.label} tint={colors.text} onPress={() => onNavigate(chip.targetId)} />
            ))}
        </>
    )
}

/**
 * The drawer's DAILIES row, styled like the career Plans row: every enabled part as a wrapping list of value pills, and the whole row opens Dailies Settings.
 * @param chips The enabled parts.
 * @param onNavigate Opens Dailies Settings at the first part's row.
 * @returns The labelled row, or null when no part is enabled.
 */
const HeroDailiesGlanceImpl = ({ chips, onNavigate }: HeroDailiesChipsProps) =>
    chips.length === 0 ? null : (
        <GlanceRow label="Dailies" onPress={() => onNavigate(chips[0].targetId)}>
            {chips.map((chip) => (
                <ValuePill key={chip.targetId} label={chip.label} />
            ))}
        </GlanceRow>
    )

export const HeroChips = React.memo(HeroChipsImpl)
export const HeroDebugChips = React.memo(HeroDebugChipsImpl)
export const HeroDailiesChips = React.memo(HeroDailiesChipsImpl)
export const HeroDailiesGlance = React.memo(HeroDailiesGlanceImpl)
export default HeroChips
