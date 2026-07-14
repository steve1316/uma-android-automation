import React from "react"
import { View, Text } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { RADII } from "../../lib/radii"

/** Minimum diameter of the badge, so a single digit renders as a circle rather than a padding-sized sliver. Wider counts grow it into a pill. */
const MIN_SIZE = 18

/** Props for `CountBadge`. */
export interface CountBadgeProps {
    /** The number to display inside the badge, e.g. the number of selected targets. */
    count: number
}

/**
 * A small filled circle showing a count, used as the leading item of a chip list in the Configuration Summary blocks.
 * The digit is centered on both axes: `TYPE.monoLabel`'s letter spacing is zeroed out (it otherwise adds trailing space after the last glyph
 * and shoves a single digit left of center) and `includeFontPadding` is off so the glyph box matches the circle.
 * @param count The number rendered inside the badge.
 * @returns The badge circle with its centered count.
 */
const CountBadgeImpl = ({ count }: CountBadgeProps) => {
    const { colors } = useTheme()
    return (
        <View
            style={{
                minWidth: MIN_SIZE,
                height: MIN_SIZE,
                paddingHorizontal: 4,
                alignItems: "center",
                justifyContent: "center",
                backgroundColor: colors.brand,
                borderRadius: RADII.pill,
            }}
        >
            <Text style={{ ...TYPE.monoLabel, color: colors.onBrand, fontSize: 9, letterSpacing: 0, textAlign: "center", includeFontPadding: false }}>{count}</Text>
        </View>
    )
}

export const CountBadge = React.memo(CountBadgeImpl)
export default CountBadge
