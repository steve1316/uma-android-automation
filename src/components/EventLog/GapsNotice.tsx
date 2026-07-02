import React, { useMemo } from "react"
import { StyleSheet, Text, View } from "react-native"
import { type GapRecord, formatGapText } from "../../lib/eventLogParser"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { RAIL_WIDTH, NODE_SIZE } from "./constants"

type Props = {
    /** The gap record indicating missing day(s) in the event log. */
    gap: GapRecord
}

/**
 * Marks missing day(s) as a greyed-out node on the timeline rail that mirrors a real day row: a dashed node plus a muted
 * "Day N missing" line, so a gap reads as an absent day in the sequence rather than a warning banner.
 * @param gap The gap record indicating the missing day range.
 */
const GapsNotice: React.FC<Props> = ({ gap }) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: { flexDirection: "row", paddingRight: SPACING.sm, opacity: 0.6 },
                rail: { width: RAIL_WIDTH, alignItems: "center" },
                railLine: { position: "absolute", top: 0, bottom: 0, left: RAIL_WIDTH / 2 - 0.5, width: 1, backgroundColor: colors.borderHair },
                node: {
                    width: NODE_SIZE,
                    height: NODE_SIZE,
                    borderRadius: RADII.pill,
                    marginTop: SPACING.sm,
                    alignItems: "center",
                    justifyContent: "center",
                    backgroundColor: colors.surface,
                    borderWidth: 1,
                    borderStyle: "dashed",
                    borderColor: colors.borderStrong,
                },
                nodeText: { ...TYPE.monoLabel, fontSize: 10, letterSpacing: 0, color: colors.textMuted },
                content: { flex: 1, justifyContent: "center", paddingTop: SPACING.sm, paddingBottom: SPACING.md, paddingLeft: SPACING.xs },
                text: { ...TYPE.body, color: colors.textMuted, fontStyle: "italic" },
            }),
        [colors]
    )

    // Single missing day shows its number. A range shows an ellipsis since the label already spells out the span.
    const nodeLabel = gap.from === gap.to ? String(gap.from) : "…"

    return (
        <View style={styles.container}>
            <View style={styles.rail}>
                <View style={styles.railLine} />
                <View style={styles.node}>
                    <Text style={styles.nodeText}>{nodeLabel}</Text>
                </View>
            </View>
            <View style={styles.content}>
                <Text style={styles.text}>{formatGapText(gap)}</Text>
            </View>
        </View>
    )
}

export default React.memo(GapsNotice)
