import React, { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Animated, StyleSheet } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { LiveRun, RunReport } from "../../lib/runReport"
import type { ThemeColors } from "../../lib/theme"
import type { RunMode } from "../../context/BotStateContext"
import { HeroTone, HeroView } from "./heroView"
import { modeAccent, toneColors } from "./heroTone"
import HeroHeader from "./HeroHeader"
import HeroActionRow from "./HeroActionRow"
import HeroButton from "./HeroButton"
import HeroDrawer from "./HeroDrawer"

/** How long the card takes to fade between the Career and Dailies colors, in milliseconds. */
const MODE_FADE_MS = 250

/** How long the outline pulse lasts after switching the run mode, in milliseconds. */
const MODE_PULSE_MS = 450

/** Card styles. The card's colors are animated per run mode, so they are applied in the component. */
const styles = StyleSheet.create({
    card: { borderWidth: 1, borderRadius: RADII.xl, paddingHorizontal: SPACING.md, paddingVertical: SPACING.sm + 2 },
    pulse: { position: "absolute", top: -1, left: -1, right: -1, bottom: -1, borderWidth: 2, borderRadius: RADII.xl },
})

/**
 * Fades the card between its Career and Dailies colors when the run mode changes, and pulses its outline once so the switch is noticed.
 * @param colors The theme colors.
 * @param cardTone The card's color family.
 * @param runMode Whether the next run is a career or the dailies.
 * @returns The animated card colors and the pulse outline style.
 */
const useModeColors = (colors: ThemeColors, cardTone: HeroTone, runMode: RunMode) => {
    const progress = useRef(new Animated.Value(runMode === "dailies" ? 1 : 0)).current
    const pulse = useRef(new Animated.Value(0)).current
    const firstRender = useRef(true)

    useEffect(() => {
        Animated.timing(progress, { toValue: runMode === "dailies" ? 1 : 0, duration: MODE_FADE_MS, useNativeDriver: false }).start()
        // The pulse marks a switch, so it does not play when the hero first appears.
        if (firstRender.current) {
            firstRender.current = false
            return
        }
        pulse.setValue(1)
        Animated.timing(pulse, { toValue: 0, duration: MODE_PULSE_MS, useNativeDriver: true }).start()
    }, [runMode, progress, pulse])

    return useMemo(() => {
        const career = toneColors(colors, cardTone, "career")
        const dailies = toneColors(colors, cardTone, "dailies")
        const between = (from: string, to: string) => progress.interpolate({ inputRange: [0, 1], outputRange: [from, to] })
        return {
            card: { backgroundColor: between(career.cardBg, dailies.cardBg), borderColor: between(career.border, dailies.border) },
            pulse: { borderColor: modeAccent(colors, runMode).accent, opacity: pulse },
        }
    }, [colors, cardTone, runMode, progress, pulse])
}

/** Props for `HomeHero`. */
interface HomeHeroProps {
    /** What the hero shows for the current state, from `heroView()`. */
    view: HeroView
    /** Active profile name, shown at the top of the drawer. */
    profile: string
    /** Whether the next run is a career or the dailies. */
    runMode: RunMode
    /** Switches the run mode. */
    onRunModeChange: (mode: RunMode) => void
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
 * Compact two-row Home hero. Row 1 is the status or result pill with the Career / Dailies switch. Row 2 is the nav chips or
 * the run's title, beside the main action. A drawer below holds the profile, the run details, and the plans and priority chips.
 * @param view What the hero shows for the current state.
 * @param profile Active profile name.
 * @param runMode Whether the next run is a career or the dailies.
 * @param onRunModeChange Switches the run mode.
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
const HomeHero = ({
    view,
    profile,
    runMode,
    onRunModeChange,
    report,
    live,
    arrivedLive,
    chips,
    glance,
    startButton,
    onOpenGame,
    onStopOverlay,
    onViewLog,
    onCopyError,
    onDismiss,
    onDrawerOpen,
}: HomeHeroProps) => {
    const { colors } = useTheme()
    const modeColors = useModeColors(colors, view.cardTone, runMode)
    const [open, setOpen] = useState(false)

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
        <Animated.View style={[styles.card, modeColors.card]}>
            <Animated.View style={[styles.pulse, modeColors.pulse]} pointerEvents="none" />
            <HeroHeader view={view} runMode={runMode} onRunModeChange={onRunModeChange} open={open} onToggle={toggle} onDismiss={onDismiss} />
            <HeroActionRow view={view} chips={chips} button={<HeroButton mode={view.buttonMode} startButton={startButton} onOpenGame={onOpenGame} onStopOverlay={onStopOverlay} />} />
            {open ? <HeroDrawer view={view} report={report} live={live} canViewLog={arrivedLive} glance={glance} profile={profile} onViewLog={onViewLog} onCopyError={onCopyError} /> : null}
        </Animated.View>
    )
}

export default React.memo(HomeHero)
