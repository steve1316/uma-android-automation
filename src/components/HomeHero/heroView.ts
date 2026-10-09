import { formatRunReport, LiveRun, RunReport, RunTone } from "../../lib/runReport"

/** Whether the bot can start: ready, missing a scenario or device info, or on an unsupported device. */
export type HeroHealth = "ready" | "notReady" | "error"

/** Color family for the hero card and its pill. `brand` is the normal hero tint. */
export type HeroTone = RunTone | "brand"

/** Which run details the drawer shows above plans and priority. `live` is the current run's stats while the overlay is on. */
export type HeroDetails = "error" | "stats" | "reason" | "live" | "none"

/** Everything the hero needs to render one state. */
export interface HeroView {
    /** Card background and border family. */
    cardTone: HeroTone
    /** Status or result pill text. */
    pillLabel: string
    /** Status or result pill color family. */
    pillTone: HeroTone
    /** Header facts such as "Turn 41/72 · 0:52:07", or empty. */
    facts: string
    /** Row 2 title after a run, or empty when the chips show. */
    title: string
    /** Row 2 line under the title, or empty. */
    sub: string
    /** Whether a run result is showing. It replaces the nav chips with the title and adds the dismiss button. */
    hasResult: boolean
    /** Scenario Start button, or Open game plus the overlay stop. */
    buttonMode: "start" | "openGame"
    /** Whether the drawer opens when this result arrives. */
    drawerDefaultOpen: boolean
    /** Run details the drawer shows. */
    details: HeroDetails
}

/** Input to `heroView`. */
export interface HeroViewInput {
    /** Whether the overlay service is on. */
    overlayOn: boolean
    /** Whether the bot can start. */
    health: HeroHealth
    /** The last run's report, or null when there is no result to show. */
    report: RunReport | null
    /** The current run's stats so far, or null when no run has read a turn. */
    live?: LiveRun | null
}

/** Status pills shown when there is no result. */
const STATUS_PILL: Record<"overlayOn" | HeroHealth, { label: string; tone: HeroTone }> = {
    overlayOn: { label: "● OVERLAY ON", tone: "brand" },
    ready: { label: "● READY", tone: "success" },
    notReady: { label: "● NOT READY", tone: "warning" },
    error: { label: "● ERROR", tone: "warning" },
}

/**
 * Decides what the Home hero shows for the current overlay state and last run.
 * @param input The overlay state, start readiness, and last run.
 * @returns The labels, tones, and modes for each part of the hero.
 */
export function heroView({ overlayOn, health, report, live = null }: HeroViewInput): HeroView {
    const buttonMode = overlayOn ? "openGame" : "start"
    if (!report) {
        const pill = STATUS_PILL[overlayOn ? "overlayOn" : health]
        const details: HeroDetails = overlayOn && live ? "live" : "none"
        return { cardTone: "brand", pillLabel: pill.label, pillTone: pill.tone, facts: "", title: "", sub: "", hasResult: false, buttonMode, drawerDefaultOpen: false, details }
    }
    const v = formatRunReport(report)
    let details: HeroDetails = "none"
    if (report.outcome === "CRASHED") details = report.error ? "error" : "reason"
    else if (report.outcome === "STOPPED_BY_BOT") details = "reason"
    else if (report.summary) details = "stats"
    return {
        cardTone: v.tone,
        pillLabel: v.pill,
        pillTone: v.tone,
        facts: v.facts.join(" · "),
        title: v.title,
        sub: v.sub,
        hasResult: true,
        buttonMode,
        drawerDefaultOpen: report.outcome === "CRASHED",
        details,
    }
}
