import React, { useMemo } from "react"
import { StyleSheet, Text, View } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** Props for `StatBar`. */
interface StatBarProps {
    /** Short label shown at the left of the bar. */
    label: string
    /** Optional leading icon node (e.g. a tinted Lucide icon). */
    iconLeft?: React.ReactNode
    /** The value this bar represents, also shown right-aligned. */
    value: number
    /** The maximum value across sibling bars, used to scale the fill width. */
    max: number
    /** Fill color (an already-resolved theme color string). */
    color: string
    /** Optional caption appended after the value (e.g. "x3" training count). */
    caption?: string
}

/**
 * A labeled horizontal bar: a fixed-width label on the left, a proportional fill in the middle, and the value on the right.
 * The fill width is `value / max`; a zero value renders an empty track dimmed so it still reads as "none".
 * @param label Short label shown at the left of the bar.
 * @param iconLeft Optional leading icon node.
 * @param value The value this bar represents.
 * @param max The maximum value across sibling bars, used to scale the fill width.
 * @param color Fill color (an already-resolved theme color string).
 * @param caption Optional caption appended after the value.
 * @returns A single labeled proportional bar row.
 */
const StatBarImpl = ({ label, iconLeft, value, max, color, caption }: StatBarProps) => {
    const { colors } = useTheme()
    const pct = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0
    const isZero = value === 0

    const styles = useMemo(
        () =>
            StyleSheet.create({
                row: { flexDirection: "row", alignItems: "center", gap: SPACING.sm, marginBottom: SPACING.xs, opacity: isZero ? 0.45 : 1 },
                labelWrap: { flexDirection: "row", alignItems: "center", gap: SPACING.xs, width: 82 },
                label: { ...TYPE.monoLabel, color: colors.textMuted },
                track: { flex: 1, height: 6, borderRadius: RADII.pill, backgroundColor: colors.surfaceRaised, overflow: "hidden" },
                fill: { height: "100%", borderRadius: RADII.pill, backgroundColor: color },
                value: { ...TYPE.monoValue, color: colors.text, minWidth: 28, textAlign: "right" },
                caption: { ...TYPE.caption, color: colors.textMuted, minWidth: 26 },
            }),
        [colors, color, isZero]
    )

    return (
        <View style={styles.row}>
            <View style={styles.labelWrap}>
                {iconLeft}
                <Text style={styles.label}>{label}</Text>
            </View>
            <View style={styles.track}>
                <View style={[styles.fill, { width: `${pct * 100}%` }]} />
            </View>
            <Text style={styles.value}>{value}</Text>
            {caption ? <Text style={styles.caption}>{caption}</Text> : null}
        </View>
    )
}

export const StatBar = React.memo(StatBarImpl)
export default StatBar
