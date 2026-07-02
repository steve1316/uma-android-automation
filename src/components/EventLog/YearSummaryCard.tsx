import React, { useMemo } from "react"
import { StyleSheet, Text, View } from "react-native"
import type { YearSummary } from "../../lib/eventLogParser"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { ValuePill } from "../ui/value-pill"
import { StatBar } from "./StatBar"
import { ACTION_ORDER, ACTION_VISUALS, type ActionKey } from "./actionVisuals"
import { STATS } from "./constants"

type Props = {
    /** The year summary data including action counts, stat gains, and elapsed time. */
    summary: YearSummary
}

/**
 * Displays a summary card for a single year: action counts as tinted proportional bars and per-stat training gains as a
 * five-column strip, with the trainee names and elapsed time in the header. Shows a "Finals" pill for years covering turns 73-75.
 * @param summary The year summary data.
 */
const YearSummaryCard: React.FC<Props> = ({ summary }) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: { padding: SPACING.lg, borderRadius: RADII.lg, borderWidth: 1, marginBottom: SPACING.md, backgroundColor: colors.surface, borderColor: colors.borderHair },
                headerRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start", marginBottom: SPACING.md },
                titleRow: { flexDirection: "row", alignItems: "center", gap: SPACING.sm, flexWrap: "wrap", flex: 1 },
                title: { ...TYPE.h1, color: colors.text },
                timeContainer: { alignItems: "flex-end" },
                timeFormatted: { ...TYPE.monoValue, fontSize: 15, color: colors.text },
                timeHuman: { ...TYPE.caption, color: colors.textMuted, marginTop: 1 },
                sectionLabel: { ...TYPE.monoLabel, color: colors.textMuted, marginBottom: SPACING.sm },
                section: { marginTop: SPACING.md },
                statStrip: { flexDirection: "row", gap: SPACING.sm },
                statCol: { flex: 1, alignItems: "center", gap: 2 },
                statAbbr: { ...TYPE.monoLabel, color: colors.textMuted },
                statValue: { ...TYPE.monoValue, color: colors.text },
                statTrack: { width: "100%", height: 4, borderRadius: RADII.pill, backgroundColor: colors.surfaceRaised, overflow: "hidden" },
                statFill: { height: "100%", borderRadius: RADII.pill, backgroundColor: colors.brand },
                statCount: { ...TYPE.caption, color: colors.textMuted },
            }),
        [colors]
    )

    const countFor: Record<ActionKey, number> = {
        training: summary.trainingCount,
        race: summary.raceCount,
        energy: summary.energyCount,
        mood: summary.moodCount,
        injury: summary.injuryCount,
    }
    const maxCount = Math.max(1, ...ACTION_ORDER.map((k) => countFor[k]))
    const maxStat = Math.max(1, ...STATS.map((s) => summary.totalStatGains[s.key]))

    return (
        <View style={styles.container}>
            <View style={styles.headerRow}>
                <View style={{ flex: 1 }}>
                    <View style={styles.titleRow}>
                        <Text style={styles.title}>{summary.year} Year</Text>
                        {summary.hasFinals && <ValuePill label="Finals" />}
                    </View>
                </View>
                {summary.elapsedTimeFormatted && (
                    <View style={styles.timeContainer}>
                        <Text style={styles.timeFormatted}>{summary.elapsedTimeFormatted}</Text>
                        <Text style={styles.timeHuman}>{summary.elapsedTimeHuman}</Text>
                    </View>
                )}
            </View>

            <View style={styles.section}>
                <Text style={styles.sectionLabel}>Actions</Text>
                {ACTION_ORDER.map((key) => {
                    const visual = ACTION_VISUALS[key]
                    const Icon = visual.icon
                    const tint = colors[visual.colorKey]
                    return <StatBar key={key} label={visual.label} iconLeft={<Icon size={12} color={tint} />} value={countFor[key]} max={maxCount} color={tint} />
                })}
            </View>

            <View style={styles.section}>
                <Text style={styles.sectionLabel}>Stat Gains</Text>
                <View style={styles.statStrip}>
                    {STATS.map(({ abbr, key }) => {
                        const value = summary.totalStatGains[key]
                        const count = summary.trainingCounts[key]
                        return (
                            <View key={abbr} style={styles.statCol}>
                                <Text style={styles.statAbbr}>{abbr}</Text>
                                <Text style={styles.statValue}>{value}</Text>
                                <View style={styles.statTrack}>
                                    <View style={[styles.statFill, { width: `${(value / maxStat) * 100}%` }]} />
                                </View>
                                <Text style={styles.statCount}>x{count}</Text>
                            </View>
                        )
                    })}
                </View>
            </View>
        </View>
    )
}

export default React.memo(YearSummaryCard)
