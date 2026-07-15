import { memo, useMemo } from "react"
import { View, Text, StyleSheet } from "react-native"
import { useTheme } from "../../../context/ThemeContext"
import { SectionLabel } from "../../../components/ui/section-label"
import type { PreviewStats } from "../../../lib/solver/constants"
import { SPACING } from "../../../lib/spacing"
import { TYPE } from "../../../lib/type"

/** Props for `ScheduleStats`. */
interface ScheduleStatsProps {
    /** Aggregate stats for the previewed schedule, from `computePreviewStats`. */
    stats: PreviewStats
    /** The preview's total score, straight off `SchedulePreview.totalScore`. */
    totalScore: number
}

/**
 * Aggregate stats for the previewed race schedule, shown under the calendar legend: what the scheduled races and the epithets they
 * complete are projected to be worth over the career.
 * @param stats Aggregate stats for the previewed schedule.
 * @param totalScore The preview's total score.
 * @returns The stats panel.
 */
function ScheduleStats({ stats, totalScore }: ScheduleStatsProps) {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: { marginBottom: SPACING.sm },
                grid: {
                    flexDirection: "row",
                    flexWrap: "wrap",
                    padding: SPACING.sm,
                    borderRadius: 8,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    backgroundColor: colors.surface,
                },
                cell: { width: "25%", paddingVertical: 4, paddingHorizontal: 2 },
                label: { ...TYPE.monoLabel, color: colors.textMuted, fontSize: 9 },
                value: { ...TYPE.monoValue, color: colors.text, fontSize: 14, fontWeight: "700" },
                note: { ...TYPE.caption, color: colors.textMuted, fontStyle: "italic", marginTop: 4 },
            }),
        [colors]
    )

    // Ordered so the race-side numbers lead and the epithet-side numbers follow, with the headline fans/score last.
    const cells: Array<{ label: string; value: string }> = [
        { label: "Races", value: String(stats.races) },
        { label: "Epithets", value: String(stats.epithets) },
        { label: "Race Stats", value: String(stats.raceStats) },
        { label: "Race SP", value: String(stats.raceSp) },
        { label: "Epithet Stats", value: String(stats.epithetStats) },
        { label: "Hints", value: String(stats.hints) },
        { label: "Fan Gain", value: stats.fans.toLocaleString() },
        { label: "Score", value: String(Math.round(totalScore)) },
    ]

    return (
        <View style={styles.container}>
            <SectionLabel label="Schedule Stats" style={{ marginBottom: 6 }} />
            <View style={styles.grid}>
                {cells.map((cell) => (
                    <View key={cell.label} style={styles.cell}>
                        <Text style={styles.label}>{cell.label}</Text>
                        <Text style={styles.value}>{cell.value}</Text>
                    </View>
                ))}
            </View>
            <Text style={styles.note}>Fan Gain is the raw sum of each scheduled race's base fan reward. It does not model finishing position, fan bonuses, or the Trackblazer multiplier.</Text>
        </View>
    )
}

export default memo(ScheduleStats)
