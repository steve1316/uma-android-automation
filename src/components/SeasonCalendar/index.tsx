import { ReactNode, useMemo } from "react"
import { View, Text, StyleSheet } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { YEAR_LABELS, turnDateLabel } from "../../lib/solver/constants"

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Styles

/**
 * Themed styles for the 72-turn season-calendar grid. Shared by every consumer (Smart Race Solver schedule preview and the recreation-date picker)
 * so the calendars look identical. Includes the grid scaffolding plus the race/lock/mandatory/highlight cell styles consumers compose onto their cells.
 * @returns The memoized StyleSheet for the calendar grid.
 */
export const useSeasonCalendarStyles = () => {
    const { colors } = useTheme()
    return useMemo(
        () =>
            StyleSheet.create({
                yearCard: {
                    marginVertical: 8,
                    padding: 12,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: 8,
                    backgroundColor: colors.bg,
                },
                yearCardTitle: { fontSize: 16, fontWeight: "700", color: colors.text, marginBottom: 6 },
                calendarRow: { flexDirection: "row", alignItems: "stretch", paddingVertical: 4 },
                calendarCellWrapper: { flex: 1, marginHorizontal: 3, alignItems: "stretch" },
                calendarCell: {
                    flexDirection: "column",
                    alignItems: "center",
                    justifyContent: "center",
                    paddingVertical: 6,
                    paddingHorizontal: 4,
                    borderRadius: 6,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    backgroundColor: colors.surface,
                    minHeight: 56,
                },
                calendarCellRace: {
                    backgroundColor: colors.surface,
                },
                calendarBadge: {
                    minWidth: 30,
                    height: 18,
                    borderRadius: 3,
                    paddingHorizontal: 4,
                    marginBottom: 4,
                    alignItems: "center",
                    justifyContent: "center",
                },
                calendarBadgeText: { color: "#fff", fontSize: 10, fontWeight: "700" },
                calendarRaceName: { fontSize: 10, color: colors.text, fontWeight: "600", textAlign: "center" },
                calendarCellEmpty: { fontSize: 11, color: colors.textMuted, textAlign: "center" },
                calendarCellPreDebut: {
                    backgroundColor: colors.surfaceRaised,
                    borderColor: colors.borderHair,
                    borderStyle: "dashed",
                    opacity: 0.6,
                },
                calendarCellPreDebutText: {
                    fontSize: 10,
                    color: colors.textMuted,
                    fontStyle: "italic",
                    fontWeight: "600",
                    textAlign: "center",
                },
                calendarDateLabel: { fontSize: 10, color: colors.textMuted, textAlign: "center", marginTop: 3 },
                calendarCellLocked: {
                    borderWidth: 2,
                    borderColor: colors.brand,
                },
                calendarCellMandatory: {
                    borderWidth: 2,
                    borderColor: "#f59e0b",
                    backgroundColor: "rgba(245, 158, 11, 0.12)",
                },
                calendarCellStop: {
                    borderWidth: 2,
                    borderColor: "#ef4444",
                    backgroundColor: "rgba(239, 68, 68, 0.12)",
                },
                // Violet deliberately: amber is the mandatory cell, brand/cyan is an SRS lock, and red is a stop, so the epithet highlight needs a hue none of them
                // already own. Only the border is set - a mandatory race that also contributes keeps its amber background tint and its pin marker underneath the ring.
                calendarCellHighlighted: {
                    borderColor: "#a855f7",
                    borderWidth: 3,
                    shadowColor: "#c084fc",
                    shadowOpacity: 0.9,
                    shadowRadius: 6,
                    shadowOffset: { width: 0, height: 0 },
                    elevation: 4,
                },
            }),
        [colors]
    )
}

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Component

/**
 * Whether a career turn is blocked - rendered as a non-tappable placeholder and never passed to `renderCell`. True for Pre-Debut turns (<= 13) and, unless `allowSummer`,
 * the Summer camp turns (37-40 / 61-64). Exported so consumers building overflow/aggregate views use the same ranges the grid renders and can't drift.
 * @param turn The 1-indexed career turn.
 * @param allowSummer Whether the Summer camp turns are tappable rather than blocked.
 * @returns True when the turn is not rendered as an interactive cell.
 */
export function isBlockedTurn(turn: number, allowSummer: boolean): boolean {
    return turn <= 13 || (!allowSummer && ((turn >= 37 && turn <= 40) || (turn >= 61 && turn <= 64)))
}

/** Props for SeasonCalendar. */
interface SeasonCalendarProps {
    /** When false (the default), the Summer training-camp turns (37-40 and 61-64) render as non-tappable "Summer" placeholders alongside the Pre-Debut turns. */
    allowSummer?: boolean
    /** Renders the full interactive cell for a tappable (non-blocked) turn. Must return an element keyed by `turn` (e.g. wrapped in `calendarCellWrapper`). */
    renderCell: (turn: number, turnInYear: number) => ReactNode
    /** Dependency list controlling when the memoized grid rebuilds. Pass the state that `renderCell` closes over so the calendar refreshes when it changes. */
    deps?: unknown[]
}

/**
 * Renders the 72-turn career as three year cards (Junior / Classic / Senior), each a 6x4 grid of turns. Pre-Debut turns (<= 13) and, unless `allowSummer`
 * is set, the Summer camp turns render as non-tappable placeholders. Every other turn is delegated to `renderCell` so each consumer supplies its own cell content.
 * @param allowSummer Whether the Summer camp turns are tappable rather than blocked placeholders.
 * @param renderCell Renders the interactive cell for a tappable turn.
 * @param deps Dependency list that triggers a grid rebuild when changed.
 * @returns The rendered three-year calendar grid.
 */
export default function SeasonCalendar({ allowSummer = false, renderCell, deps = [] }: SeasonCalendarProps) {
    const styles = useSeasonCalendarStyles()

    const renderBlockedCell = (turn: number, turnInYear: number, isPreDebut: boolean) => (
        <View key={turn} style={styles.calendarCellWrapper}>
            <View style={[styles.calendarCell, styles.calendarCellPreDebut]}>
                <Text style={styles.calendarCellPreDebutText}>{isPreDebut ? "Pre-Debut" : "Summer"}</Text>
            </View>
            <Text style={styles.calendarDateLabel}>{turnDateLabel(turnInYear)}</Text>
        </View>
    )

    const renderTurn = (turn: number, turnInYear: number) => {
        if (isBlockedTurn(turn, allowSummer)) return renderBlockedCell(turn, turnInYear, turn <= 13)
        return renderCell(turn, turnInYear)
    }

    const renderYearCard = (year: { name: string; startTurn: number }) => {
        const rows: number[][] = []
        for (let r = 0; r < 6; r++) rows.push([0, 1, 2, 3].map((c) => r * 4 + c))
        return (
            <View key={year.name} style={styles.yearCard}>
                <Text style={styles.yearCardTitle}>{year.name} Year</Text>
                {rows.map((row, ridx) => (
                    <View key={`row-${ridx}`} style={styles.calendarRow}>
                        {row.map((turnInYear) => renderTurn(year.startTurn + turnInYear, turnInYear))}
                    </View>
                ))}
            </View>
        )
    }

    // eslint-disable-next-line react-hooks/exhaustive-deps
    return useMemo(() => <>{YEAR_LABELS.map(renderYearCard)}</>, [styles, allowSummer, ...deps])
}
