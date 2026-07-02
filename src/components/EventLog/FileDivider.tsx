import React, { useMemo } from "react"
import { StyleSheet, Text, View } from "react-native"
import type { FileDividerRecord } from "../../lib/eventLogParser"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { ValuePill } from "../ui/value-pill"

type Props = {
    /** The file divider record containing the filename to display. */
    divider: FileDividerRecord
}

/**
 * Renders a labeled break in the timeline where the source log file changes: a hairline on each side with the filename and,
 * when detected, the trainee name and scenario as pills.
 * @param divider The file divider record containing the filename and optional trainee name and scenario.
 */
const FileDivider: React.FC<Props> = ({ divider }) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: { flexDirection: "row", alignItems: "center", gap: SPACING.md, marginVertical: SPACING.md, marginHorizontal: SPACING.md },
                line: { flex: 1, height: 1, backgroundColor: colors.borderHair },
                center: { alignItems: "center", gap: SPACING.xs },
                pillRow: { flexDirection: "row", flexWrap: "wrap", justifyContent: "center", gap: SPACING.xs },
                fileName: { ...TYPE.monoValue, color: colors.textMuted },
            }),
        [colors]
    )

    return (
        <View style={styles.container}>
            <View style={styles.line} />
            <View style={styles.center}>
                <Text style={styles.fileName}>{divider.fileName}</Text>
                {(divider.traineeName || divider.scenario) && (
                    <View style={styles.pillRow}>
                        {divider.traineeName && <ValuePill label={divider.traineeName} />}
                        {divider.scenario && <ValuePill label={divider.scenario} />}
                    </View>
                )}
            </View>
            <View style={styles.line} />
        </View>
    )
}

export default React.memo(FileDivider)
