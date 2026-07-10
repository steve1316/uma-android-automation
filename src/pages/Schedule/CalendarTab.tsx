import { memo, useMemo } from "react"
import { View, Text, StyleSheet, Pressable } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { SectionLabel } from "../../components/ui/section-label"
import SeasonCalendar, { isBlockedTurn, useSeasonCalendarStyles } from "../../components/SeasonCalendar"
import { shortenRaceName, turnDateLabel } from "../../lib/solver/constants"
import type { ScheduleEvent } from "../../lib/schedule/types"
import type { ScheduleModel } from "../../lib/schedule/registry"
import { SPACING } from "../../lib/spacing"
import { TYPE } from "../../lib/type"

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Legend

/** Whether each source is enabled, gating legend chips so no unreachable glyph shows. */
interface LegendFlags {
    /** Dating Schedule (recreation + pure passion) enabled. */
    dating: boolean
    /** Stop-at-Date enabled. */
    stop: boolean
    /** Smart Race Solver enabled. */
    srs: boolean
}

/** Legend entries; each chip renders only when its gating source is enabled (mandatory always shows). */
const LEGEND: Array<{ glyph: string; label: string; show: (flags: LegendFlags) => boolean }> = [
    { glyph: "📌", label: "Mandatory", show: () => true },
    { glyph: "🔒", label: "SRS race lock", show: (flags) => flags.srs },
    { glyph: "📅", label: "Recreation", show: (flags) => flags.dating },
    { glyph: "✨", label: "Pure Passion", show: (flags) => flags.dating },
    { glyph: "🛑", label: "Stop here", show: (flags) => flags.stop },
    { glyph: "⚡", label: "SRS wants this turn", show: (flags) => flags.srs },
]

/**
 * Cell-glyph prefix for the owner marker shown on the date label under a cell.
 * @param owner The turn's action owner, or undefined when the turn is unowned.
 * @returns The marker followed by a space, or an empty string when there is no owner.
 */
function ownerPrefix(owner: ScheduleEvent | undefined): string {
    return owner?.marker ? `${owner.marker} ` : ""
}

/** Props for `CalendarTab`. */
interface CalendarTabProps {
    /** Merged schedule model keyed by turn. */
    model: ScheduleModel
    /** Which sources are enabled, for legend gating. */
    legendFlags: LegendFlags
    /** Called when a calendar cell is tapped. */
    onSelectTurn: (turn: number) => void
    /** Whether Summer turns are tappable. */
    allowSummer: boolean
}

/**
 * Calendar tab: the unified overlay of every source on one 72-turn grid, plus a gated legend and an overflow list for events on blocked turns.
 * @param model Merged schedule model.
 * @param legendFlags Enabled-source flags for legend gating.
 * @param onSelectTurn Cell tap handler.
 * @param allowSummer Whether Summer turns are tappable.
 * @returns The calendar tab body.
 */
function CalendarTab({ model, legendFlags, onSelectTurn, allowSummer }: CalendarTabProps) {
    const { colors } = useTheme()
    const calStyles = useSeasonCalendarStyles()

    const overflowEvents = useMemo(() => {
        const rows: Array<{ turn: number; event: ScheduleEvent }> = []
        model.byTurn.forEach((merged, turn) => {
            if (!isBlockedTurn(turn, allowSummer)) return
            const shown = merged.owner ?? merged.annotations[0] ?? merged.reservations[0]
            if (shown) rows.push({ turn, event: shown })
        })
        return rows.sort((a, b) => a.turn - b.turn)
    }, [model, allowSummer])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                legend: { flexDirection: "row", flexWrap: "wrap", gap: 6, marginBottom: SPACING.sm },
                legendChip: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: 5,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: 3,
                    borderRadius: 999,
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                },
                legendText: { fontSize: 10.5, color: colors.textMuted },
                cellContent: { alignItems: "center", justifyContent: "center" },
                cellGlyph: { fontSize: 15, lineHeight: 18 },
                raceName: { fontSize: 9, color: colors.text, fontWeight: "600", textAlign: "center", lineHeight: 11 },
                corner: { position: "absolute", top: 1, right: 2, fontSize: 14 },
                cornerLeft: { position: "absolute", top: 2, left: 3, fontSize: 9 },
                overflow: { marginTop: SPACING.xs, marginBottom: SPACING.lg, padding: SPACING.md, borderRadius: 8, borderWidth: 1, borderStyle: "dashed", borderColor: colors.borderStrong },
                overflowHead: { ...TYPE.monoLabel, color: colors.textMuted, marginBottom: 6 },
                overflowItem: { fontSize: 12, color: colors.text, paddingVertical: 3 },
            }),
        [colors]
    )

    /**
     * Render one interactive calendar cell for a turn from its merged model.
     * @param turn The 1-indexed career turn.
     * @param turnInYear The 0-23 offset within the year card.
     * @returns The cell element wrapped in `calendarCellWrapper` with a date label below.
     */
    const renderCell = (turn: number, turnInYear: number) => {
        const merged = model.byTurn.get(turn)
        const owner = merged?.owner
        const autoRace = merged?.autoRace
        const reservation = merged?.reservations[0]
        const stopEvent = merged?.annotations.find((event) => event.sourceId === "stopAtDate")
        const isMandatoryCell = owner?.ownership === "mandatory"
        // A stop halts the turn, so it owns the cell like a recreation date does - except on a mandatory turn, where the career race stays and the stop moves to the corner.
        const stopIsMainGlyph = !!stopEvent && !isMandatoryCell

        const cellStyle = [
            calStyles.calendarCell,
            isMandatoryCell && calStyles.calendarCellMandatory,
            stopIsMainGlyph && calStyles.calendarCellStop,
            owner?.ownership === "explicit" && calStyles.calendarCellLocked,
        ]

        // Shared render for a race owner (a mandatory career race or an SRS lock): grade badge + shortened name.
        const raceOwnerContent = owner ? (
            <View style={styles.cellContent}>
                {owner.badge ? (
                    <View style={[calStyles.calendarBadge, { backgroundColor: owner.color ?? colors.brand }]}>
                        <Text style={calStyles.calendarBadgeText}>{owner.badge}</Text>
                    </View>
                ) : null}
                <Text style={styles.raceName} numberOfLines={2}>
                    {shortenRaceName(owner.label)}
                </Text>
            </View>
        ) : null

        let content
        if (isMandatoryCell) {
            content = raceOwnerContent
        } else if (stopEvent) {
            content = <Text style={styles.cellGlyph}>{stopEvent.marker}</Text>
        } else if (owner?.sourceId === "srs") {
            content = raceOwnerContent
        } else if (owner) {
            content = <Text style={styles.cellGlyph}>{owner.marker}</Text>
        } else if (autoRace) {
            content = (
                <View style={styles.cellContent}>
                    <View style={[calStyles.calendarBadge, { backgroundColor: autoRace.color ?? colors.brand }]}>
                        <Text style={calStyles.calendarBadgeText}>{autoRace.badge}</Text>
                    </View>
                    <Text style={styles.raceName} numberOfLines={2}>
                        {shortenRaceName(autoRace.label)}
                    </Text>
                </View>
            )
        } else if (reservation) {
            content = <Text style={styles.cellGlyph}>{reservation.marker}</Text>
        } else {
            content = <Text style={calStyles.calendarCellEmpty}>—</Text>
        }

        const datePrefix = stopIsMainGlyph && stopEvent ? `${stopEvent.marker} ` : ownerPrefix(owner)

        return (
            <View key={turn} style={calStyles.calendarCellWrapper}>
                <Pressable style={cellStyle} android_ripple={{ color: colors.ripple, foreground: true }} onPress={() => onSelectTurn(turn)}>
                    {merged?.conflict ? <Text style={styles.cornerLeft}>⚠️</Text> : null}
                    {stopEvent && isMandatoryCell ? <Text style={styles.corner}>{stopEvent.marker}</Text> : null}
                    {merged?.hasReservableAutoRace ? <Text style={styles.corner}>⚡</Text> : null}
                    {content}
                </Pressable>
                <Text style={calStyles.calendarDateLabel}>
                    {datePrefix}
                    {turnDateLabel(turnInYear)}
                </Text>
            </View>
        )
    }

    return (
        <View>
            <SectionLabel label="Legend" style={{ marginTop: SPACING.sm, marginBottom: 6 }} />
            <View style={styles.legend}>
                {LEGEND.filter((item) => item.show(legendFlags)).map((item) => (
                    <View key={item.label} style={styles.legendChip}>
                        <Text style={{ fontSize: 12 }}>{item.glyph}</Text>
                        <Text style={styles.legendText}>{item.label}</Text>
                    </View>
                ))}
            </View>

            <SeasonCalendar allowSummer={allowSummer} renderCell={renderCell} deps={[model, colors]} />

            {overflowEvents.length > 0 && (
                <View style={styles.overflow}>
                    <Text style={styles.overflowHead}>Events on unavailable turns (Pre-Debut / Summer)</Text>
                    {overflowEvents.map(({ turn, event }) => (
                        <Text key={`${turn}-${event.sourceId}`} style={styles.overflowItem}>
                            {event.marker || "•"} T{turn} · {event.label}
                        </Text>
                    ))}
                </View>
            )}
        </View>
    )
}

export default memo(CalendarTab)
