import { memo, useMemo } from "react"
import { View, Text, StyleSheet, Pressable } from "react-native"
import { Divider } from "react-native-paper"
import { useTheme } from "../../../context/ThemeContext"
import { SectionLabel } from "../../../components/ui/section-label"
import { EPITHETS_BY_NAME } from "../../../lib/solver/constants"
import { splitEpithetBullets } from "../../../lib/solver/scoring"
import type { SchedulePreview } from "../../../lib/solver/preview"
import { SPACING } from "../../../lib/spacing"
import { TYPE } from "../../../lib/type"

/**
 * Looks up an epithet's reward and conditions for display. A reward bullet may carry a redundant "Reward:" label, which the card renders itself, so it is stripped here.
 * @param name The epithet name to look up.
 * @returns The reward text (null when the epithet lists no reward) and its condition lines.
 */
function epithetCopy(name: string): { reward: string | null; conditions: string[] } {
    const { reward, conditions } = splitEpithetBullets(EPITHETS_BY_NAME[name]?.bullet_points ?? [])
    return { reward: reward ? reward.replace(/^\s*reward\s*:\s*/i, "") : null, conditions }
}

/**
 * Builds the themed style sheet for this panel.
 * @param colors The active theme colors.
 * @returns The style sheet.
 */
function buildStyles(colors: ReturnType<typeof useTheme>["colors"]) {
    return StyleSheet.create({
        container: { marginTop: SPACING.md, marginBottom: SPACING.lg },
        hint: { ...TYPE.caption, color: colors.textMuted, fontStyle: "italic", marginBottom: 6 },
        listTitle: { ...TYPE.h2, color: colors.text, marginBottom: 4 },
        empty: { ...TYPE.caption, color: colors.textMuted, paddingVertical: 4 },
        epithetCard: { paddingVertical: 6, paddingHorizontal: 8, marginVertical: 3, borderRadius: 6, borderWidth: 1, borderColor: colors.borderHair, backgroundColor: colors.surface },
        epithetCardHighlighted: { borderColor: colors.brand, borderWidth: 2, backgroundColor: colors.surfaceRaised },
        epithetCardName: { fontSize: 13, fontWeight: "700", color: colors.text, marginBottom: 2 },
        epithetCardReward: { fontSize: 11, color: colors.text, marginBottom: 1 },
        epithetCardCondition: { fontSize: 11, color: colors.textMuted, fontStyle: "italic" },
        epithetCardConditionItem: { fontSize: 11, color: colors.textMuted, fontStyle: "italic", marginLeft: 8 },
    })
}

/** Props for `EpithetCard`. */
interface EpithetCardProps {
    /** The epithet name shown as the card title. */
    name: string
    /** Suffix appended to the name, e.g. "  ★" for a forced epithet or "  ✓" for a projected one that is also selected. */
    suffix?: string
    /** Overrides the name color, used to tint a projected epithet that is also a selected target. */
    nameColor?: string
    /** Whether this card is the one currently highlighting its contributing races on the calendar. */
    highlighted: boolean
    /** Toggles the highlight for this epithet. */
    onPress: () => void
    /** Style sheet from the parent. */
    styles: ReturnType<typeof buildStyles>
}

/**
 * One tappable epithet card showing its reward and conditions. Tapping it highlights the races that complete it on the calendar above.
 * @param props The `EpithetCardProps` for this card.
 * @returns The card.
 */
function EpithetCard({ name, suffix, nameColor, highlighted, onPress, styles }: EpithetCardProps) {
    const { colors } = useTheme()
    const { reward, conditions } = epithetCopy(name)
    return (
        <Pressable style={[styles.epithetCard, highlighted && styles.epithetCardHighlighted]} onPress={onPress} android_ripple={{ color: colors.ripple, foreground: true }}>
            <Text style={[styles.epithetCardName, nameColor ? { color: nameColor } : null]}>
                {name}
                {suffix ?? ""}
            </Text>
            {reward ? <Text style={styles.epithetCardReward}>Reward: {reward}</Text> : null}
            {conditions.length > 0 ? (
                <>
                    <Text style={styles.epithetCardCondition}>Condition:</Text>
                    {conditions.map((line, idx) => (
                        <Text key={`${name}-cond-${idx}`} style={styles.epithetCardConditionItem}>
                            • {line}
                        </Text>
                    ))}
                </>
            ) : (
                <Text style={styles.epithetCardCondition}>Condition: (condition unknown)</Text>
            )}
        </Pressable>
    )
}

/** Props for `EpithetRewards`. */
interface EpithetRewardsProps {
    /** Epithets picked as solver targets. */
    targetEpithets: string[]
    /** Epithets the solver is forced to complete, rendered with a star. */
    forcedEpithets: string[]
    /** Solver preview supplying the projected completions, or null before the first solve. */
    preview: SchedulePreview | null
    /** Whether a solve is in flight. */
    previewLoading: boolean
    /** The epithet currently highlighting its contributing races, or null when none is. */
    highlightedEpithet: string | null
    /** Toggles the highlight for an epithet. */
    onToggleEpithet: (name: string) => void
}

/**
 * Epithet rewards panel shown at the bottom of the calendar modal: the selected epithets and the ones the preview projects completing.
 * Tapping a card highlights the calendar cells of the races that contribute to it.
 * @param targetEpithets Epithets picked as solver targets.
 * @param forcedEpithets Epithets the solver must complete.
 * @param preview Solver preview supplying the projected completions.
 * @param previewLoading Whether a solve is in flight.
 * @param highlightedEpithet The epithet currently highlighting its races.
 * @param onToggleEpithet Toggles the highlight for an epithet.
 * @returns The epithet rewards panel.
 */
function EpithetRewards({ targetEpithets, forcedEpithets, preview, previewLoading, highlightedEpithet, onToggleEpithet }: EpithetRewardsProps) {
    const { colors } = useTheme()
    const styles = useMemo(() => buildStyles(colors), [colors])

    const selectedNames = useMemo(() => Array.from(new Set([...targetEpithets, ...forcedEpithets])), [targetEpithets, forcedEpithets])
    const projectedNames = preview?.projectedEpithets ?? []

    return (
        <View style={styles.container}>
            <SectionLabel label="Epithet Rewards" style={{ marginBottom: 4 }} />
            <Text style={styles.hint}>Tap an epithet to highlight the races that complete it on the calendar above.</Text>

            <Text style={styles.listTitle}>Selected Epithets</Text>
            {selectedNames.length === 0 ? (
                <Text style={styles.empty}>No epithets selected — pick targets on the Race Solver tab to see their rewards here.</Text>
            ) : (
                selectedNames.map((name) => (
                    <EpithetCard
                        key={`sel-${name}`}
                        name={name}
                        suffix={forcedEpithets.includes(name) ? "  ★" : undefined}
                        highlighted={highlightedEpithet === name}
                        onPress={() => onToggleEpithet(name)}
                        styles={styles}
                    />
                ))
            )}

            <Divider style={{ marginVertical: 8 }} />

            <Text style={styles.listTitle}>Projected Completions</Text>
            {previewLoading && <Text style={styles.empty}>Computing preview…</Text>}
            {!previewLoading && projectedNames.length === 0 && <Text style={styles.empty}>The preview schedule does not project completing any epithets with the current configuration.</Text>}
            {projectedNames.map((name) => {
                const isSelected = targetEpithets.includes(name) || forcedEpithets.includes(name)
                return (
                    <EpithetCard
                        key={`proj-${name}`}
                        name={name}
                        suffix={isSelected ? "  ✓" : undefined}
                        nameColor={isSelected ? colors.brand : undefined}
                        highlighted={highlightedEpithet === name}
                        onPress={() => onToggleEpithet(name)}
                        styles={styles}
                    />
                )
            })}
        </View>
    )
}

export default memo(EpithetRewards)
