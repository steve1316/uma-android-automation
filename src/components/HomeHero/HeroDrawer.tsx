import React, { useEffect, useMemo, useRef, useState } from "react"
import { StyleSheet, Text, View } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import CustomButton from "../CustomButton"
import { formatRuntime, LiveRun, RunReport, RunStats, RunSummary, statFill } from "../../lib/runReport"
import { HeroView } from "./heroView"
import type { ThemeColors } from "../../lib/theme"

/** How long the Copy error button says "Copied". */
const COPIED_MS = 2000

/** Longest error message shown in the drawer. Copy error keeps the full text. */
const MESSAGE_LIMIT = 300

/** Stack frames shown in the drawer. Copy error keeps them all. */
const TOP_FRAMES = 2

/** Stat tiles in display order. */
const STAT_TILES: { key: keyof RunStats; label: string }[] = [
    { key: "speed", label: "Spd" },
    { key: "stamina", label: "Sta" },
    { key: "power", label: "Pow" },
    { key: "guts", label: "Gut" },
    { key: "wit", label: "Wit" },
]

/**
 * Builds the drawer styles.
 * @param colors The theme colors.
 * @returns The style sheet.
 */
const createStyles = (colors: ThemeColors) =>
    StyleSheet.create({
        hair: { height: 1, backgroundColor: colors.borderHair, marginHorizontal: -SPACING.md, marginVertical: SPACING.sm },
        stats: { flexDirection: "row", gap: 5 },
        tile: { flex: 1, backgroundColor: colors.surfaceRaised, borderRadius: 9, paddingVertical: 6, paddingHorizontal: 5, alignItems: "center" },
        tileValue: { ...TYPE.monoValue, color: colors.text },
        tileLabel: { ...TYPE.monoLabel, fontSize: 9, color: colors.textMuted },
        bar: { alignSelf: "stretch", height: 3, borderRadius: 2, backgroundColor: colors.borderStrong, marginTop: 5, overflow: "hidden" },
        barFill: { height: "100%", backgroundColor: colors.brand },
        line: { ...TYPE.caption, color: colors.textMuted, marginTop: 6 },
        error: { backgroundColor: colors.errorSubtle, borderWidth: 1, borderColor: colors.errorSoftBorder, borderRadius: 10, padding: 9 },
        errorClass: { ...TYPE.body, color: colors.error },
        errorText: { ...TYPE.caption, color: colors.text, marginTop: 2 },
        frames: { ...TYPE.caption, fontFamily: TYPE.monoValue.fontFamily, color: colors.textMuted, marginTop: 4 },
        reason: { ...TYPE.caption, color: colors.text },
        liveHead: { ...TYPE.monoLabel, color: colors.textMuted, marginBottom: 6 },
        actions: { flexDirection: "row", alignItems: "center", gap: 8, marginTop: SPACING.sm },
    })

/** Props for `HeroDrawer`. */
interface HeroDrawerProps {
    /** What the hero shows for the current state, for which run details to show. */
    view: HeroView
    /** The last run's report, or null when there is no result. */
    report: RunReport | null
    /** The current run's stats so far, shown while the overlay is on, or null. */
    live: LiveRun | null
    /** Whether View log is offered, only when the run's log is still in memory. */
    canViewLog: boolean
    /** Plans and priority chips, or null when there are none. */
    glance: React.ReactNode | null
    /** Scrolls the Home log to the error or the end. */
    onViewLog: () => void
    /** Copies the error block to the clipboard. */
    onCopyError: () => Promise<void>
}

/**
 * The hero drawer: the last run's details (the error, the final stats, or the stop reason), or the current run's stats, above the plans and priority chips.
 * @param view What the hero shows for the current state.
 * @param report The last run's report, or null.
 * @param live The current run's stats so far, or null.
 * @param canViewLog Whether View log is offered.
 * @param glance Plans and priority chips, or null.
 * @param onViewLog Scrolls the Home log.
 * @param onCopyError Copies the error text.
 * @returns The drawer content.
 */
const HeroDrawer = ({ view, report, live, canViewLog, glance, onViewLog, onCopyError }: HeroDrawerProps) => {
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors), [colors])
    const [copied, setCopied] = useState(false)
    const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

    useEffect(
        () => () => {
            if (copiedTimer.current) clearTimeout(copiedTimer.current)
        },
        []
    )

    /** Copies the error and briefly confirms it on the button. */
    const handleCopy = async () => {
        await onCopyError()
        setCopied(true)
        if (copiedTimer.current) clearTimeout(copiedTimer.current)
        copiedTimer.current = setTimeout(() => setCopied(false), COPIED_MS)
    }

    /**
     * Renders the five stat tiles with cap bars and the fans and skill points line.
     * @param run The trainee summary to show.
     * @returns The stats block.
     */
    const renderStats = (run: RunSummary) => (
        <>
            <View style={styles.stats}>
                {STAT_TILES.map(({ key, label }) => (
                    <View key={key} style={styles.tile}>
                        <Text style={styles.tileValue}>{run.stats[key]}</Text>
                        <Text style={styles.tileLabel}>{label}</Text>
                        <View style={styles.bar}>
                            <View style={[styles.barFill, { width: `${Math.round(statFill(run.stats[key], run.statCaps[key]) * 100)}%` }]} />
                        </View>
                    </View>
                ))}
            </View>
            <Text style={styles.line}>{`${run.fans.toLocaleString()} fans · ${run.skillPoints} skill pts`}</Text>
        </>
    )

    const viewLog = canViewLog ? (
        <CustomButton variant="outline" size="sm" onPress={onViewLog}>
            View log
        </CustomButton>
    ) : null
    const summary = report?.summary
    const error = report?.error

    let details: React.ReactNode = null
    if (view.details === "error" && error) {
        const message = error.message.length > MESSAGE_LIMIT ? `${error.message.slice(0, MESSAGE_LIMIT)}...` : error.message
        details = (
            <>
                <View style={styles.error}>
                    <Text style={styles.errorClass}>{error.className}</Text>
                    {message ? <Text style={styles.errorText}>{message}</Text> : null}
                    {error.frames.length > 0 ? (
                        <Text style={styles.frames}>
                            {error.frames
                                .slice(0, TOP_FRAMES)
                                .map((f) => `at ${f}`)
                                .join("\n")}
                        </Text>
                    ) : null}
                </View>
                {report?.logFile ? <Text style={styles.line}>{report.logFile}</Text> : null}
                <View style={styles.actions}>
                    {viewLog}
                    <CustomButton variant="outline" size="sm" onPress={handleCopy}>
                        {copied ? "Copied" : "Copy error"}
                    </CustomButton>
                </View>
            </>
        )
    } else if (view.details === "stats" && summary) {
        details = (
            <>
                {renderStats(summary)}
                {viewLog ? <View style={styles.actions}>{viewLog}</View> : null}
            </>
        )
    } else if (view.details === "live" && live) {
        const turn = live.totalTurns > 0 ? `TURN ${live.turn}/${live.totalTurns}` : `TURN ${live.turn}`
        details = (
            <>
                <Text style={styles.liveHead}>{`THIS RUN · ${turn} · ${formatRuntime(live.runtimeMs)}`}</Text>
                {renderStats(live.summary)}
            </>
        )
    } else if (view.details === "reason" && report) {
        details = (
            <>
                <Text style={styles.reason}>{report.reason}</Text>
                {viewLog ? <View style={styles.actions}>{viewLog}</View> : null}
            </>
        )
    }

    return (
        <View>
            <View style={styles.hair} />
            {details}
            {details && glance ? <View style={styles.hair} /> : null}
            {glance}
        </View>
    )
}

export default React.memo(HeroDrawer)
