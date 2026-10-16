import React, { useEffect, useMemo, useRef, useState } from "react"
import { Animated, LayoutChangeEvent, Pressable, StyleSheet, Text, View } from "react-native"
import { ChevronDown, ChevronUp, X } from "lucide-react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { HeroTone, HeroView } from "./heroView"
import type { ThemeColors } from "../../lib/theme"
import type { RunMode } from "../../context/BotStateContext"
import { modeAccent, toneColors } from "./heroTone"

/**
 * Builds the header styles.
 * @param colors The theme colors.
 * @param pillTone The pill's color family.
 * @param runMode Whether the next run is a career or the dailies, which colors the switch.
 * @returns The style sheet.
 */
const createStyles = (colors: ThemeColors, pillTone: HeroTone, runMode: RunMode) => {
    const tone = toneColors(colors, pillTone, runMode)
    const mode = modeAccent(colors, runMode)
    return StyleSheet.create({
        row: { flexDirection: "row", alignItems: "center", gap: 6, minHeight: 24 },
        pill: { ...TYPE.monoLabel, color: tone.accent, backgroundColor: tone.accentBg, paddingHorizontal: SPACING.sm, paddingVertical: 2, borderRadius: RADII.pill },
        spacer: { flex: 1 },
        switch: { flexDirection: "row", backgroundColor: colors.surfaceRaised, borderRadius: RADII.sm, padding: 2 },
        switchLocked: { opacity: 0.5 },
        // The selected option's fill slides between the options in the mode's color.
        thumb: { position: "absolute", top: 2, bottom: 2, left: 0, borderRadius: RADII.sm - 2, backgroundColor: mode.accent },
        option: { paddingHorizontal: SPACING.sm + 2, paddingVertical: 4, borderRadius: RADII.sm - 2 },
        optionLabel: { ...TYPE.caption, fontWeight: "600", color: colors.textMuted },
        optionLabelOn: { color: mode.onAccent },
        dismiss: { padding: 2 },
    })
}

/** How long the switch's selected fill takes to slide to the other option, in milliseconds. */
const THUMB_SLIDE_MS = 220

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
    const styles = useMemo(() => createStyles(colors, view.pillTone, runMode), [colors, view.pillTone, runMode])
    const Chevron = open ? ChevronUp : ChevronDown
    // Each option's x and width, measured on layout, so the thumb can slide to the selected one.
    const [layouts, setLayouts] = useState<Partial<Record<RunMode, { x: number; width: number }>>>({})
    const thumbX = useRef(new Animated.Value(0)).current
    const thumbWidth = useRef(new Animated.Value(0)).current
    const selected = layouts[runMode]

    useEffect(() => {
        if (!selected) return
        Animated.parallel([
            Animated.timing(thumbX, { toValue: selected.x, duration: THUMB_SLIDE_MS, useNativeDriver: false }),
            Animated.timing(thumbWidth, { toValue: selected.width, duration: THUMB_SLIDE_MS, useNativeDriver: false }),
        ]).start()
    }, [selected?.x, selected?.width, thumbX, thumbWidth])

    /**
     * Records an option's position for the thumb.
     * @param mode The option's run mode.
     * @param event The option's layout event.
     */
    const onOptionLayout = (mode: RunMode, event: LayoutChangeEvent) => {
        const { x, width } = event.nativeEvent.layout
        setLayouts((prev) => (prev[mode]?.x === x && prev[mode]?.width === width ? prev : { ...prev, [mode]: { x, width } }))
    }
    return (
        <Pressable style={styles.row} onPress={onToggle} accessibilityRole="button" accessibilityLabel={open ? "Hide details" : "Show details"}>
            <Text style={styles.pill}>{view.pillLabel}</Text>
            <View style={[styles.switch, view.switchLocked && styles.switchLocked]} accessibilityRole="radiogroup">
                {selected ? <Animated.View style={[styles.thumb, { transform: [{ translateX: thumbX }], width: thumbWidth }]} /> : null}
                {RUN_MODES.map(({ value, label }) => {
                    const on = value === runMode
                    return (
                        <Pressable
                            key={value}
                            style={styles.option}
                            onLayout={(event) => onOptionLayout(value, event)}
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
