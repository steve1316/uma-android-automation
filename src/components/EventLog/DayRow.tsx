import React, { useMemo } from "react"
import { StyleSheet, Text, View } from "react-native"
import type { DayRecord } from "../../lib/eventLogParser"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { ValuePill } from "../ui/value-pill"
import { ActionChip } from "./ActionChip"
import { ACTION_ORDER, type ActionKey } from "./actionVisuals"
import { RAIL_WIDTH, NODE_SIZE, STATS } from "./constants"

type Props = {
    /** The parsed day record data including actions, triggers, and summary. */
    record: DayRecord
    /** Whether to show detailed trigger information for each action type. */
    showTriggers?: boolean
}

/** Titles rendered above each expanded trigger group, in action order. */
const TRIGGER_GROUPS: { key: ActionKey; title: string }[] = [
    { key: "training", title: "Training" },
    { key: "race", title: "Race" },
    { key: "energy", title: "Recover Energy" },
    { key: "mood", title: "Recover Mood" },
    { key: "injury", title: "Recover Injury" },
]

/**
 * Title-cases a freeform date string for display so uppercase log dates read cleanly (e.g. "JUNIOR YEAR LATE AUGUST" -> "Junior Year Late August").
 * @param text The raw date text.
 * @returns The date text with each word title-cased.
 */
function toDisplayDate(text: string): string {
    return text.replace(/\S+/g, (w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase())
}

/**
 * Builds the short granular detail shown on an action chip (e.g. the race grade or a normalized energy type).
 * @param key The action the chip represents.
 * @param record The day record supplying the granular fields.
 * @returns The sublabel string, or undefined when there is no useful detail.
 */
function chipSublabel(key: ActionKey, record: DayRecord): string | undefined {
    if (key === "training") return record.trainingType ? toDisplayDate(record.trainingType) : undefined
    if (key === "race") return record.raceGrade || undefined
    if (key === "energy") {
        const t = record.energyType?.toLowerCase() ?? ""
        if (t.includes("summer")) return "Summer"
        if (t.includes("date")) return "Date"
        if (t.includes("rest")) return "Rest"
        return record.energyType || undefined
    }
    if (key === "mood") {
        const t = record.moodType?.toLowerCase() ?? ""
        if (t.includes("date")) return "Date"
        return record.moodType || undefined
    }
    return undefined
}

/**
 * Displays a single day as a node on the timeline rail: a day-number badge on a connecting vertical line, the date, and a
 * row of tinted action chips for whatever happened that turn. Optionally expands the raw trigger log lines below.
 * @param record The parsed day record data.
 * @param showTriggers Whether to show detailed trigger information.
 */
const DayRow: React.FC<Props> = ({ record, showTriggers }) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: { flexDirection: "row", paddingRight: SPACING.sm },
                rail: { width: RAIL_WIDTH, alignItems: "center" },
                railLine: { position: "absolute", top: 0, bottom: 0, left: RAIL_WIDTH / 2 - 0.5, width: 1, backgroundColor: colors.borderHair },
                node: {
                    width: NODE_SIZE,
                    height: NODE_SIZE,
                    borderRadius: RADII.pill,
                    marginTop: SPACING.sm,
                    alignItems: "center",
                    justifyContent: "center",
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.brandBorder,
                },
                nodeText: { ...TYPE.monoLabel, fontSize: 10, letterSpacing: 0, color: colors.brand },
                content: { flex: 1, paddingTop: SPACING.sm, paddingBottom: SPACING.md, paddingLeft: SPACING.xs },
                headerRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: SPACING.sm },
                date: { ...TYPE.body, color: colors.text, flex: 1 },
                chipsRow: { flexDirection: "row", flexWrap: "wrap", gap: SPACING.xs, marginTop: SPACING.sm },
                detail: { ...TYPE.monoValue, color: colors.textMuted, marginTop: SPACING.sm },
                triggers: { marginTop: SPACING.sm, gap: SPACING.xs },
                triggerTitle: { ...TYPE.monoLabel, color: colors.textMuted },
                triggerLine: { ...TYPE.caption, color: colors.textMuted },
            }),
        [colors]
    )

    const activeActions = ACTION_ORDER.filter((key) => record.actions[key])

    // Compose the non-zero training stat gains into a compact line, e.g. "SPD +5   WIT +8".
    const detailText = useMemo(() => {
        if (!record.actions.training || !record.trainingStatGains) return ""
        return record.trainingStatGains
            .map((gain, i) => (gain !== 0 ? `${STATS[i].abbr} ${gain > 0 ? "+" : ""}${gain}` : null))
            .filter(Boolean)
            .join("   ")
    }, [record.actions.training, record.trainingStatGains])

    return (
        <View style={styles.container} accessibilityLabel={`Day ${record.dayNumber}: ${record.summary}`}>
            <View style={styles.rail}>
                <View style={styles.railLine} />
                <View style={styles.node}>
                    <Text style={styles.nodeText}>{record.dayNumber}</Text>
                </View>
            </View>

            <View style={styles.content}>
                <View style={styles.headerRow}>
                    <Text style={styles.date}>{record.dateText ? toDisplayDate(record.dateText) : `Day ${record.dayNumber}`}</Text>
                    {!!record.year && <ValuePill label={record.year} />}
                </View>

                {activeActions.length > 0 && (
                    <View style={styles.chipsRow}>
                        {activeActions.map((key) => (
                            <ActionChip key={key} action={key} sublabel={chipSublabel(key, record)} />
                        ))}
                    </View>
                )}

                {!!detailText && <Text style={styles.detail}>{detailText}</Text>}

                {record.actions.race && !!record.raceName && (
                    <Text style={styles.detail}>
                        {record.raceName}
                        {record.racePlace ? `  ·  ${record.racePlace}` : ""}
                        {record.raceWon !== undefined && <Text style={{ color: record.raceWon ? colors.activeFlag : colors.warning }}>{`  ·  ${record.raceWon ? "Won" : "Lost"}`}</Text>}
                    </Text>
                )}

                {showTriggers && record.triggers && (
                    <View style={styles.triggers}>
                        {TRIGGER_GROUPS.map(({ key, title }) => {
                            const lines = record.triggers![key]
                            if (!lines || lines.length === 0) return null
                            return (
                                <View key={key}>
                                    <Text style={styles.triggerTitle}>{title}</Text>
                                    {lines.map((l, idx) => (
                                        <Text key={idx} style={styles.triggerLine}>
                                            {l}
                                        </Text>
                                    ))}
                                </View>
                            )
                        })}
                    </View>
                )}
            </View>
        </View>
    )
}

export default React.memo(DayRow)
