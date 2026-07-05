import React, { useMemo } from "react"
import { Pressable, StyleSheet, Text, View } from "react-native"
import type { LucideIcon } from "lucide-react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** Props for `TintedChip`. */
export interface TintedChipProps {
    /** Lucide icon rendered at the start of the chip. */
    icon: LucideIcon
    /** Uppercase mono label shown after the icon. */
    label: string
    /** Resolved tint color for the icon and label. */
    tint: string
    /** Optional press handler; when set the chip becomes a tappable shortcut with ripple feedback. */
    onPress?: () => void
    /** Icon size in px. Defaults to 12. */
    iconSize?: number
}

/**
 * A small pill with a tinted Lucide icon and an uppercase mono label on a neutral raised surface. Shared by the Event Log action chips and the Home hero
 * status chips so both read the same. Renders a `Pressable` with ripple when `onPress` is given, otherwise a static `View`.
 * @param icon The Lucide icon to render.
 * @param label The uppercase mono label.
 * @param tint The resolved tint color for the icon and label.
 * @param onPress Optional press handler that turns the chip into a shortcut.
 * @param iconSize Icon size in px (default 12).
 * @returns A tinted icon-and-label pill.
 */
const TintedChipImpl = ({ icon: Icon, label, tint, onPress, iconSize = 12 }: TintedChipProps) => {
    const { colors } = useTheme()
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
                    overflow: "hidden",
                },
                label: { ...TYPE.monoLabel, color: tint },
            }),
        [colors, tint]
    )

    const content = (
        <>
            <Icon size={iconSize} color={tint} />
            <Text style={styles.label}>{label}</Text>
        </>
    )

    if (onPress) {
        return (
            <Pressable onPress={onPress} android_ripple={{ color: colors.ripple }} style={styles.chip}>
                {content}
            </Pressable>
        )
    }
    return <View style={styles.chip}>{content}</View>
}

export const TintedChip = React.memo(TintedChipImpl)
export default TintedChip
