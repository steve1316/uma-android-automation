import React, { useCallback, useEffect, useMemo, useState } from "react"
import { StyleSheet, View } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { LiveRun, RunReport } from "../../lib/runReport"
import type { ThemeColors } from "../../lib/theme"
import { HeroTone, HeroView } from "./heroView"
import { toneColors } from "./heroTone"
import HeroHeader from "./HeroHeader"
import HeroActionRow from "./HeroActionRow"
import HeroButton from "./HeroButton"
import HeroDrawer from "./HeroDrawer"

/**
 * Builds the card styles for the hero's tone.
 * @param colors The theme colors.
 * @param cardTone The card's color family.
 * @returns The style sheet.
 */
const createStyles = (colors: ThemeColors, cardTone: HeroTone) => {
    const tone = toneColors(colors, cardTone)
    return StyleSheet.create({
        card: { backgroundColor: tone.cardBg, borderWidth: 1, borderColor: tone.border, borderRadius: RADII.xl, paddingHorizontal: SPACING.md, paddingVertical: SPACING.sm + 2 },
    })
}

/** Props for `HomeHero`. */
interface HomeHeroProps {
    /** What the hero shows for the current state, from `heroView()`. */
    view: HeroView
    /** Active profile name. */
    profile: string
    /** The last run's report, or null when there is no result. */
    report: RunReport | null
    /** The current run's stats so far, shown in the drawer while the overlay is on, or null. */
    live: LiveRun | null
    /** Whether the result arrived live this session. Its log is still in memory for View log, and the drawer opens to the result's default. */
    arrivedLive: boolean
    /** Nav chips (SRS, debug, race style) for row 2. */
    chips: React.ReactNode
    /** Plans and priority chips for the drawer, or null when there are none. */
    glance: React.ReactNode | null
    /** Home's scenario `SelectButton`, shown while the overlay is off. */
    startButton: React.ReactNode
    /** Brings the game to the front. */
    onOpenGame: () => void
    /** Turns the overlay off. */
    onStopOverlay: () => void
    /** Scrolls the Home log to the error or the end. */
    onViewLog: () => void
    /** Copies the error block to the clipboard. */
    onCopyError: () => Promise<void>
    /** Dismisses the run result. */
    onDismiss: () => void
    /** Called when the drawer opens, so the live stats can refresh. */
    onDrawerOpen: () => void
}

/**
 * Compact two-row Home hero. Row 1 is the status or result pill with the profile or the run's turn and runtime. Row 2 is the nav chips or the run's title,
 * beside the main action. A drawer below holds the run details and the plans and priority chips.
 * @param view What the hero shows for the current state.
 * @param profile Active profile name.
 * @param report The last run's report, or null.
 * @param live The current run's stats so far, or null.
 * @param arrivedLive Whether the result arrived live this session.
 * @param chips Nav chips for row 2.
 * @param glance Plans and priority chips, or null.
 * @param startButton Home's scenario `SelectButton`.
 * @param onOpenGame Brings the game to the front.
 * @param onStopOverlay Turns the overlay off.
 * @param onViewLog Scrolls the Home log.
 * @param onCopyError Copies the error text.
 * @param onDismiss Dismisses the run result.
 * @param onDrawerOpen Called when the drawer opens.
 * @returns The hero card.
 */
const HomeHero = ({ view, profile, report, live, arrivedLive, chips, glance, startButton, onOpenGame, onStopOverlay, onViewLog, onCopyError, onDismiss, onDrawerOpen }: HomeHeroProps) => {
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors, view.cardTone), [colors, view.cardTone])
    const [open, setOpen] = useState(false)
    const hasDrawer = view.details !== "none" || glance != null

    /** Opens or closes the drawer, refreshing the live stats when it opens. */
    const toggle = useCallback(() => {
        if (!open) onDrawerOpen()
        setOpen((v) => !v)
    }, [open, onDrawerOpen])

    // A result that just arrived resets the drawer to that result's default. One loaded when the app opens leaves it closed.
    useEffect(() => {
        if (report && arrivedLive) setOpen(view.drawerDefaultOpen)
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [report?.endedAt])

    return (
        <View style={styles.card}>
            <HeroHeader view={view} profile={profile} open={open} hasDrawer={hasDrawer} onToggle={toggle} onDismiss={onDismiss} />
            <HeroActionRow view={view} chips={chips} button={<HeroButton mode={view.buttonMode} startButton={startButton} onOpenGame={onOpenGame} onStopOverlay={onStopOverlay} />} />
            {open && hasDrawer ? <HeroDrawer view={view} report={report} live={live} canViewLog={arrivedLive} glance={glance} onViewLog={onViewLog} onCopyError={onCopyError} /> : null}
        </View>
    )
}

export default React.memo(HomeHero)
