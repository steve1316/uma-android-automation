import React, { useMemo } from "react"
import { StyleSheet, Text, View } from "react-native"
import type { DayRecord } from "../../lib/eventLogParser"
import { useTheme } from "../../context/ThemeContext"

type Props = {
    /** The parsed day record data including actions, triggers, and summary. */
    record: DayRecord
    /** Whether to show detailed trigger information for each action type. */
    showTriggers?: boolean
}

/**
 * Displays a single day's event log data as a styled card.
 * Shows a day number, date, summary text, and action flags (energy, mood, injury, training, race).
 * Optionally displays detailed trigger information for each action type.
 * @param record The parsed day record data.
 * @param showTriggers Whether to show detailed trigger information.
 */
const DayRow: React.FC<Props> = ({ record, showTriggers }) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: {
                    paddingVertical: 12,
                    paddingHorizontal: 12,
                    borderRadius: 8,
                    borderWidth: 1,
                    marginBottom: 10,
                    backgroundColor: colors.card,
                    borderColor: colors.border,
                },
                headerRow: {
                    flexDirection: "row",
                    alignItems: "center",
                    justifyContent: "space-between",
                    marginBottom: 4,
                },
                title: {
                    fontSize: 16,
                    fontWeight: "600",
                    color: colors.foreground,
                },
                date: {
                    fontSize: 12,
                    color: colors.lightlyMuted,
                },
                summary: {
                    fontSize: 14,
                    marginBottom: 8,
                    color: colors.foreground,
                },
                flagsRow: {
                    flexDirection: "row",
                    flexWrap: "wrap",
                    gap: 10,
                },
                flag: {
                    flexDirection: "row",
                    alignItems: "center",
                    marginRight: 8,
                    marginBottom: 6,
                },
                flagDot: {
                    marginRight: 6,
                },
                flagLabel: {
                    fontSize: 12,
                    color: colors.lightlyMuted,
                },
                triggersContainer: {
                    marginTop: 8,
                },
                triggerSection: {
                    marginBottom: 6,
                },
                triggerTitle: {
                    color: colors.lightlyMuted,
                    fontWeight: "600",
                    marginBottom: 2,
                },
                triggerLine: {
                    color: colors.lightlyMuted,
                    fontSize: 12,
                },
            }),
        [colors]
    )

    /**
     * Renders the trigger information for a specific action type.
     * @param title The title of the trigger section (e.g., `Training`).
     * @param lines An array of trigger description strings.
     * @returns The rendered trigger section, or null if no triggers are present.
     */
    const renderTriggers = (title: string, lines: string[]) => {
        if (!lines || lines.length === 0) return null
        return (
            <View style={styles.triggerSection}>
                <Text style={styles.triggerTitle}>{title} Triggers:</Text>
                {lines.map((l, idx) => (
                    <Text key={idx} style={styles.triggerLine}>
                        • {l}
                    </Text>
                ))}
            </View>
        )
    }

    return (
        <View style={styles.container}>
            <View style={styles.headerRow}>
                <Text style={styles.title}>Day {record.dayNumber}</Text>
                {!!record.dateText && <Text style={styles.date}>{record.dateText}</Text>}
            </View>
            <Text style={styles.summary}>{record.summary}</Text>
            <View style={styles.flagsRow}>
                {[
                    { label: "Recover Energy", active: record.actions.energy },
                    { label: "Recover Mood", active: record.actions.mood },
                    { label: "Recover Injury", active: record.actions.injury },
                    { label: "Training", active: record.actions.training },
                    { label: "Race", active: record.actions.race },
                ].map(({ label, active }) => {
                    const flagColor = active ? colors.activeFlag : colors.lightlyMuted
                    return (
                        <View key={label} style={[styles.flag, { opacity: active ? 1 : 0.35 }]}>
                            <Text style={[styles.flagDot, { color: flagColor }]}>{active ? "●" : "○"}</Text>
                            <Text style={styles.flagLabel}>{label}</Text>
                        </View>
                    )
                })}
            </View>

            {showTriggers && record.triggers && (
                <View style={styles.triggersContainer}>
                    {renderTriggers("Training", record.triggers.training)}
                    {renderTriggers("Race", record.triggers.race)}
                    {renderTriggers("Recover Energy", record.triggers.energy)}
                    {renderTriggers("Recover Mood", record.triggers.mood)}
                    {renderTriggers("Recover Injury", record.triggers.injury)}
                </View>
            )}
        </View>
    )
}

export default DayRow
