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
}

/**
 * A small cyan pill showing a picker's current value. Used in the `right` slot of a `Row` (or a `Pressable` cell) that opens a selector modal.
 * @param label The value text rendered inside the pill.
 * @returns A styled `Text` node sized to fit the value.
 */
const ValuePillImpl = ({ label }: ValuePillProps) => {
    const { colors } = useTheme()
    return (
        <Text style={{ ...TYPE.monoLabel, color: colors.brand, paddingHorizontal: SPACING.sm, paddingVertical: 2, backgroundColor: colors.brandSubtle, borderRadius: RADII.pill, overflow: "hidden" }}>
            {label}
        </Text>
    )
}

export const ValuePill = React.memo(ValuePillImpl)
export default ValuePill
