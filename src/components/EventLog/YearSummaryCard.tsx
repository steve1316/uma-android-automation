import React, { useMemo } from "react"
import { StyleSheet, Text, View } from "react-native"
import type { YearSummary } from "../../lib/eventLogParser"
import { useTheme } from "../../context/ThemeContext"

type Props = {
    /** The year summary data including action counts, stat gains, and elapsed time. */
    summary: YearSummary
}

/**
 * Displays a summary card for a single year's event log data.
 * Shows total actions (energy, mood, injury, race, training), stat gains per training type,
 * and elapsed time. Appends "+ Finals" to the title for Senior Year if finals data is present.
 * @param summary The year summary data.
 */
const YearSummaryCard: React.FC<Props> = ({ summary }) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: {
                    paddingVertical: 16,
                    paddingHorizontal: 16,
                    borderRadius: 8,
                    borderWidth: 1,
                    marginBottom: 12,
                    backgroundColor: colors.card,
                    borderColor: colors.border,
                },
                headerRow: {
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "flex-start",
                    marginBottom: 12,
                },
                title: {
                    fontSize: 20,
                    fontWeight: "bold",
                    flex: 1,
                    color: colors.foreground,
                },
                timeContainer: {
                    alignItems: "flex-end",
                },
                timeFormatted: {
                    fontSize: 18,
                    fontWeight: "600",
                    marginBottom: 2,
                    color: colors.foreground,
                },
                timeHuman: {
                    fontSize: 12,
                    color: colors.lightlyMuted,
                },
                section: {
                    marginBottom: 12,
                },
                sectionTitle: {
                    fontSize: 16,
                    fontWeight: "600",
                    marginBottom: 8,
                    color: colors.foreground,
                },
                actionRow: {
                    gap: 8,
                },
                actionItem: {
                    flexDirection: "row",
                    alignItems: "center",
                    marginBottom: 4,
                },
                actionLabel: {
                    fontSize: 14,
                    marginRight: 8,
                    minWidth: 120,
                    color: colors.lightlyMuted,
                },
                actionValue: {
                    fontSize: 14,
                    fontWeight: "600",
                    color: colors.foreground,
                },
                statRow: {
                    gap: 8,
                },
                statItem: {
                    flexDirection: "row",
                    alignItems: "center",
                    marginBottom: 4,
                },
                statLabel: {
                    fontSize: 14,
                    marginRight: 8,
                    minWidth: 110,
                    color: colors.lightlyMuted,
                },
                statValue: {
                    fontSize: 14,
                    fontWeight: "600",
                    color: colors.foreground,
                },
            }),
        [colors]
    )

    // Build the title string, including "+ Finals" for Senior Year if the logs covered the Finals days (turns 73-75).
    const titleText = summary.hasFinals ? `${summary.year} Year + Finals` : `${summary.year} Year`

    return (
        <View style={styles.container}>
            <View style={styles.headerRow}>
                <Text style={styles.title}>{titleText}</Text>
                {summary.elapsedTimeFormatted && (
                    <View style={styles.timeContainer}>
                        <Text style={styles.timeFormatted}>{summary.elapsedTimeFormatted}</Text>
                        <Text style={styles.timeHuman}>{summary.elapsedTimeHuman}</Text>
                    </View>
                )}
            </View>

            <View style={styles.section}>
                <Text style={styles.sectionTitle}>Actions</Text>
                <View style={styles.actionRow}>
                    {[
                        { label: "Recover Energy", count: summary.energyCount },
                        { label: "Recover Mood", count: summary.moodCount },
                        { label: "Recover Injury", count: summary.injuryCount },
                        { label: "Race", count: summary.raceCount },
                        { label: "Training", count: summary.trainingCount },
                    ].map(({ label, count }) => (
                        <View key={label} style={styles.actionItem}>
                            <Text style={styles.actionLabel}>{label}:</Text>
                            <Text style={styles.actionValue}>{count}</Text>
                        </View>
                    ))}
                </View>
            </View>

            <View style={styles.section}>
                <Text style={styles.sectionTitle}>Total Stat Gains</Text>
                <View style={styles.statRow}>
                    {[
                        { label: "Speed", value: summary.totalStatGains.speed, count: summary.trainingCounts.speed },
                        { label: "Stamina", value: summary.totalStatGains.stamina, count: summary.trainingCounts.stamina },
                        { label: "Power", value: summary.totalStatGains.power, count: summary.trainingCounts.power },
                        { label: "Guts", value: summary.totalStatGains.guts, count: summary.trainingCounts.guts },
                        { label: "Wit", value: summary.totalStatGains.wit, count: summary.trainingCounts.wit },
                    ].map(({ label, value, count }) => (
                        <View key={label} style={styles.statItem}>
                            <Text style={styles.statLabel}>
                                {label} ({count}):
                            </Text>
                            <Text style={styles.statValue}>{value}</Text>
                        </View>
                    ))}
                </View>
            </View>
        </View>
    )
}

export default YearSummaryCard
