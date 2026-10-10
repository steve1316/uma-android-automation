import React, { useMemo } from "react"
import { Pressable, StyleSheet, View } from "react-native"
import { Square } from "lucide-react-native"
import { useTheme } from "../../context/ThemeContext"
import { RADII } from "../../lib/radii"
import CustomButton from "../CustomButton"
import type { ThemeColors } from "../../lib/theme"

/**
 * Builds the button styles.
 * @param colors The theme colors.
 * @returns The style sheet.
 */
const createStyles = (colors: ThemeColors) =>
    StyleSheet.create({
        row: { flexDirection: "row", alignItems: "center", gap: 6 },
        stop: { width: 32, height: 32, borderRadius: RADII.md, backgroundColor: colors.errorSubtle, alignItems: "center", justifyContent: "center" },
    })

/** Props for `HeroButton`. */
interface HeroButtonProps {
    /** Scenario Start button, or Open game plus the overlay stop. */
    mode: "start" | "openGame"
    /** Home's scenario `SelectButton`, shown while the overlay is off. */
    startButton: React.ReactNode
    /** Brings the game to the front. */
    onOpenGame: () => void
    /** Turns the overlay off. */
    onStopOverlay: () => void
}

/**
 * The hero's main action. With the overlay off it is Home's scenario Start button. With the overlay on it opens the game, next to a small button that turns the overlay off.
 * @param mode Which button set to show.
 * @param startButton Home's scenario `SelectButton`.
 * @param onOpenGame Brings the game to the front.
 * @param onStopOverlay Turns the overlay off.
 * @returns The action button or buttons.
 */
const HeroButton = ({ mode, startButton, onOpenGame, onStopOverlay }: HeroButtonProps) => {
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors), [colors])
    if (mode === "start") return <>{startButton}</>
    return (
        <View style={styles.row}>
            <CustomButton variant="info" size="sm" onPress={onOpenGame}>
                ↗ Open game
            </CustomButton>
            <Pressable style={styles.stop} onPress={onStopOverlay} hitSlop={6} accessibilityRole="button" accessibilityLabel="Turn the overlay off">
                <Square size={12} color={colors.error} fill={colors.error} />
            </Pressable>
        </View>
    )
}

export default React.memo(HeroButton)
