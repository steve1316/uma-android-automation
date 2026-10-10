import React, { useMemo } from "react"
import { Pressable, StyleSheet, Text } from "react-native"
import { ChevronDown, ChevronUp, X } from "lucide-react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { HeroTone, HeroView } from "./heroView"
import type { ThemeColors } from "../../lib/theme"
import { toneColors } from "./heroTone"

/**
 * Builds the header styles.
 * @param colors The theme colors.
 * @param pillTone The pill's color family.
 * @returns The style sheet.
 */
const createStyles = (colors: ThemeColors, pillTone: HeroTone) => {
    const tone = toneColors(colors, pillTone)
    return StyleSheet.create({
        row: { flexDirection: "row", alignItems: "center", gap: 6, minHeight: 24 },
        pill: { ...TYPE.monoLabel, color: tone.accent, backgroundColor: tone.accentBg, paddingHorizontal: SPACING.sm, paddingVertical: 2, borderRadius: RADII.pill },
        facts: { ...TYPE.monoLabel, textTransform: "none", color: colors.text, flex: 1 },
        profile: { ...TYPE.h2, fontSize: 14, color: colors.text, flex: 1 },
        dismiss: { padding: 2 },
    })
}

/** Props for `HeroHeader`. */
interface HeroHeaderProps {
    /** What the hero shows for the current state. */
    view: HeroView
    /** Active profile name, shown when there are no result facts. */
    profile: string
    /** Whether the drawer is open, for the chevron direction. */
    open: boolean
    /** Whether there is a drawer to toggle. */
    hasDrawer: boolean
    /** Opens or closes the drawer. */
    onToggle: () => void
    /** Dismisses the run result. */
    onDismiss: () => void
}

/**
 * Row 1 of the hero: the status or result pill, the profile name or the run's turn and runtime, the drawer chevron, and the dismiss button after a run.
 * The whole row toggles the drawer.
 * @param view What the hero shows for the current state.
 * @param profile Active profile name.
 * @param open Whether the drawer is open.
 * @param hasDrawer Whether there is a drawer to toggle.
 * @param onToggle Opens or closes the drawer.
 * @param onDismiss Dismisses the run result.
 * @returns The header row.
 */
const HeroHeader = ({ view, profile, open, hasDrawer, onToggle, onDismiss }: HeroHeaderProps) => {
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors, view.pillTone), [colors, view.pillTone])
    const Chevron = open ? ChevronUp : ChevronDown
    return (
        <Pressable style={styles.row} onPress={onToggle} disabled={!hasDrawer} accessibilityRole="button" accessibilityLabel={open ? "Hide details" : "Show details"}>
            <Text style={styles.pill}>{view.pillLabel}</Text>
            {view.facts ? (
                <Text style={styles.facts} numberOfLines={1}>
                    {view.facts}
                </Text>
            ) : (
                <Text style={styles.profile} numberOfLines={1}>
                    {profile}
                </Text>
            )}
            {hasDrawer ? <Chevron size={16} color={colors.textMuted} /> : null}
            {view.hasResult ? (
                <Pressable style={styles.dismiss} onPress={onDismiss} hitSlop={10} accessibilityRole="button" accessibilityLabel="Dismiss run result">
                    <X size={16} color={colors.textMuted} />
                </Pressable>
            ) : null}
        </Pressable>
    )
}

export default React.memo(HeroHeader)
