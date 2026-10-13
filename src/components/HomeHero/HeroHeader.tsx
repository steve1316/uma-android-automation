import React, { useMemo } from "react"
import { Pressable, StyleSheet, Text, View } from "react-native"
import { ChevronDown, ChevronUp, X } from "lucide-react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { HeroTone, HeroView } from "./heroView"
import type { ThemeColors } from "../../lib/theme"
import type { RunMode } from "../../context/BotStateContext"
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
        spacer: { flex: 1 },
        switch: { flexDirection: "row", backgroundColor: colors.surfaceRaised, borderRadius: RADII.sm, padding: 2 },
        switchLocked: { opacity: 0.5 },
        option: { paddingHorizontal: SPACING.sm + 2, paddingVertical: 4, borderRadius: RADII.sm - 2 },
        optionOn: { backgroundColor: colors.borderStrong },
        optionLabel: { ...TYPE.caption, fontWeight: "600", color: colors.textMuted },
        optionLabelOn: { color: colors.text },
        dismiss: { padding: 2 },
    })
}

/** Run modes the header switch offers, in display order. */
const RUN_MODES: { value: RunMode; label: string }[] = [
    { value: "career", label: "Career" },
    { value: "dailies", label: "Dailies" },
]

/** Props for `HeroHeader`. */
interface HeroHeaderProps {
    /** What the hero shows for the current state. */
    view: HeroView
    /** Whether the next run is a career or the dailies. */
    runMode: RunMode
    /** Switches the run mode. */
    onRunModeChange: (mode: RunMode) => void
    /** Whether the drawer is open, for the chevron direction. */
    open: boolean
    /** Opens or closes the drawer. */
    onToggle: () => void
    /** Dismisses the run result. */
    onDismiss: () => void
}

/**
 * Row 1 of the hero: the status or result pill, the Career / Dailies switch (dimmed and locked while a run is in progress), the drawer chevron, and the dismiss button after
 * a run. The whole row toggles the drawer. The switch is locked while the overlay is on.
 * @param view What the hero shows for the current state.
 * @param runMode Whether the next run is a career or the dailies.
 * @param onRunModeChange Switches the run mode.
 * @param open Whether the drawer is open.
 * @param onToggle Opens or closes the drawer.
 * @param onDismiss Dismisses the run result.
 * @returns The header row.
 */
const HeroHeader = ({ view, runMode, onRunModeChange, open, onToggle, onDismiss }: HeroHeaderProps) => {
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors, view.pillTone), [colors, view.pillTone])
    const Chevron = open ? ChevronUp : ChevronDown
    return (
        <Pressable style={styles.row} onPress={onToggle} accessibilityRole="button" accessibilityLabel={open ? "Hide details" : "Show details"}>
            <Text style={styles.pill}>{view.pillLabel}</Text>
            <View style={[styles.switch, view.switchLocked && styles.switchLocked]} accessibilityRole="radiogroup">
                {RUN_MODES.map(({ value, label }) => {
                    const on = value === runMode
                    return (
                        <Pressable
                            key={value}
                            style={[styles.option, on && styles.optionOn]}
                            onPress={() => onRunModeChange(value)}
                            disabled={view.switchLocked}
                            hitSlop={6}
                            accessibilityRole="radio"
                            accessibilityState={{ checked: on }}
                            accessibilityLabel={`${label} run`}
                        >
                            <Text style={[styles.optionLabel, on && styles.optionLabelOn]}>{label}</Text>
                        </Pressable>
                    )
                })}
            </View>
            <View style={styles.spacer} />
            <Chevron size={16} color={colors.textMuted} />
            {view.hasResult ? (
                <Pressable style={styles.dismiss} onPress={onDismiss} hitSlop={10} accessibilityRole="button" accessibilityLabel="Dismiss run result">
                    <X size={16} color={colors.textMuted} />
                </Pressable>
            ) : null}
        </Pressable>
    )
}

export default React.memo(HeroHeader)
