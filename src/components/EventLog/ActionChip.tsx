import React, { useMemo } from "react"
import { StyleSheet, Text, View } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { ACTION_VISUALS, type ActionKey } from "./actionVisuals"

/** Props for `ActionChip`. */
interface ActionChipProps {
    /** Which action this chip represents. */
    action: ActionKey
    /** Optional granular detail appended after the label (e.g. race grade "G3" or training type "Wit"). */
    sublabel?: string
}

/**
 * A small pill showing one action that happened on a day: a tinted Lucide icon plus an uppercase mono tag.
 * The tint comes from the shared `ACTION_VISUALS` mapping so it matches the Year Summary stat bars.
 * @param action The action this chip represents.
 * @param sublabel Optional granular detail appended after the label.
 * @returns A tinted icon-and-label pill.
 */
const ActionChipImpl = ({ action, sublabel }: ActionChipProps) => {
    const { colors } = useTheme()
    const visual = ACTION_VISUALS[action]
    const tint = colors[visual.colorKey]

    const styles = useMemo(
        () =>
            StyleSheet.create({
                chip: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: SPACING.xs,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: 3,
                    borderRadius: RADII.pill,
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                },
                label: { ...TYPE.monoLabel, color: tint },
            }),
        [colors, tint]
    )

    const Icon = visual.icon
    const text = sublabel ? `${visual.label} ${sublabel}` : visual.label

    return (
        <View style={styles.chip}>
            <Icon size={12} color={tint} />
            <Text style={styles.label}>{text}</Text>
        </View>
    )
}

export const ActionChip = React.memo(ActionChipImpl)
export default ActionChip
