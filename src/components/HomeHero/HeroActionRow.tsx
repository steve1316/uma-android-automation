import React, { useMemo } from "react"
import { ScrollView, StyleSheet, Text, View } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { HeroView } from "./heroView"
import type { ThemeColors } from "../../lib/theme"

/**
 * Builds the action row styles.
 * @param colors The theme colors.
 * @returns The style sheet.
 */
const createStyles = (colors: ThemeColors) =>
    StyleSheet.create({
        row: { flexDirection: "row", alignItems: "center", gap: SPACING.sm, marginTop: SPACING.sm },
        left: { flex: 1, minWidth: 0 },
        chips: { gap: 6, alignItems: "center" },
        title: { ...TYPE.h2, fontSize: 14, color: colors.text },
        sub: { ...TYPE.caption, color: colors.textMuted },
    })

/** Props for `HeroActionRow`. */
interface HeroActionRowProps {
    /** What the hero shows for the current state. */
    view: HeroView
    /** Nav chips, shown on one sideways-scrolling line when there is no result. */
    chips: React.ReactNode
    /** The main action button. */
    button: React.ReactNode
}

/**
 * Row 2 of the hero: the nav chips, or the run's title and one line after a run, beside the main action button.
 * @param view What the hero shows for the current state.
 * @param chips Nav chips for the scrolling line.
 * @param button The main action button.
 * @returns The action row.
 */
const HeroActionRow = ({ view, chips, button }: HeroActionRowProps) => {
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors), [colors])
    return (
        <View style={styles.row}>
            <View style={styles.left}>
                {!view.hasResult ? (
                    <ScrollView horizontal showsHorizontalScrollIndicator={false} fadingEdgeLength={24} contentContainerStyle={styles.chips}>
                        {chips}
                    </ScrollView>
                ) : (
                    <>
                        <Text style={styles.title} numberOfLines={1}>
                            {view.title}
                        </Text>
                        {view.sub ? (
                            <Text style={styles.sub} numberOfLines={1}>
                                {view.sub}
                            </Text>
                        ) : null}
                    </>
                )}
            </View>
            {button}
        </View>
    )
}

export default React.memo(HeroActionRow)
