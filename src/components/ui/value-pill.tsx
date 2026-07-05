import React from "react"
import { Text } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** Props for `ValuePill`. */
export interface ValuePillProps {
    /** The current value to display inside the pill. */
    label: string
    /** Color treatment: "brand" (cyan, default) for picker values, "neutral" (muted, hairline-bordered) for read-only tags. */
    tone?: "brand" | "neutral"
}

/**
 * A small pill showing a picker's current value or a read-only tag. Used in the `right` slot of a `Row` (or a `Pressable` cell) that opens a selector modal.
 * @param label The value text rendered inside the pill.
 * @param tone Color treatment: "brand" (default) or "neutral".
 * @returns A styled `Text` node sized to fit the value.
 */
const ValuePillImpl = ({ label, tone = "brand" }: ValuePillProps) => {
    const { colors } = useTheme()
    const neutral = tone === "neutral"
    return (
        <Text
            style={{
                ...TYPE.monoLabel,
                color: neutral ? colors.textMuted : colors.brand,
                paddingHorizontal: SPACING.sm,
                paddingVertical: 2,
                backgroundColor: neutral ? colors.surfaceRaised : colors.brandSubtle,
                borderRadius: RADII.pill,
                borderWidth: neutral ? 1 : 0,
                borderColor: colors.borderHair,
                overflow: "hidden",
            }}
        >
            {label}
        </Text>
    )
}

export const ValuePill = React.memo(ValuePillImpl)
export default ValuePill
