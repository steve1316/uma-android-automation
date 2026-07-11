import React, { useMemo } from "react"
import { Pressable, StyleSheet, Text } from "react-native"
import { Bot, Bug, ChevronRight, FlaskConical } from "lucide-react-native"
import Ionicons from "@react-native-vector-icons/ionicons"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import type { HeroGlanceTarget } from "./HeroGlance"

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

/** Props for `HeroChips`. */
export interface HeroChipsProps {
    /** Whether Debug Mode is on (renders the Debug chip). */
    debugMode: boolean
    /** The armed debug test's display name, or null when none is armed (renders the Test chip). */
    activeTest: string | null
    /** Whether the Smart Race Solver is on (renders the SRS chip). */
    srs: boolean
    /** Race strategy label for the always-present Style chip (e.g. "Auto", "Late", "Per-distance"). */
    raceStyle: string
    /** Navigate to a settings screen when a chip is tapped. */
    onNavigate: (target: HeroGlanceTarget) => void
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
 * The hero status-line chip cluster: the active flag chips (SRS / Debug / Test) plus the always-present Style chip. Returned as a fragment (not a wrapping view)
 * so the chips are direct siblings of the status pill and wrap alongside it. SRS tints brand, Debug warning, Test info; Style is neutral-bright with a muted walk icon.
 * @param debugMode Whether Debug Mode is on.
 * @param activeTest The armed debug test's display name, or null.
 * @param srs Whether the Smart Race Solver is on.
 * @param raceStyle Race strategy label for the Style chip.
 * @param onNavigate Navigation callback invoked with the tapped target.
 * @returns A fragment of nav-chips for the hero status line.
 */
const HeroChipsImpl = ({ debugMode, activeTest, srs, raceStyle, onNavigate }: HeroChipsProps) => {
    const { colors } = useTheme()
    return (
        <>
            {srs ? <NavChip icon={<Bot size={12} color={colors.brand} />} label="SRS" tint={colors.brand} onPress={() => onNavigate("srs")} /> : null}
            {debugMode ? <NavChip icon={<Bug size={12} color={colors.warning} />} label="Debug" tint={colors.warning} onPress={() => onNavigate("debug")} /> : null}
            {activeTest ? <NavChip icon={<FlaskConical size={12} color={colors.info} />} label={activeTest} tint={colors.info} onPress={() => onNavigate("debugTest")} /> : null}
            <NavChip icon={<Ionicons name="walk" size={14} color={colors.textMuted} />} label={raceStyle} tint={colors.text} onPress={() => onNavigate("racing")} />
        </>
    )
}

export const HeroChips = React.memo(HeroChipsImpl)
export default HeroChips
