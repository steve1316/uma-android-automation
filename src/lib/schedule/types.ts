import type { Settings } from "../../context/BotStateContext"
import type { SchedulePreview } from "../solver/preview"
import type { RaceEntry } from "../solver/constants"

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Schedule event-source model
//
// A pluggable, React-free layer that aggregates every date-based scheduler (SRS races, recreation dates, pure passion, stop-at-date, future features)
// onto the shared 1-72 career-turn calendar. Each source reads the existing settings slices and writes back through the existing keys, so persisted
// shapes stay byte-compatible with what the Kotlin bot reads. All logic here is pure so future features drop in without touching the calendar UI.

/** Identifier for a schedule source. New date-based features append an id here; nothing else needs to know about them. */
export type ScheduleSourceId = "mandatory" | "srs" | "recreation" | "purePassion" | "stopAtDate"

/**
 * How an event on a turn participates in one-owner enforcement.
 * - `explicit`: a real user commitment that consumes the turn's action (recreation / pure passion / SRS race-lock). At most one per turn.
 * - `reservation`: an SRS "__TRAIN__" keep-clear lock. Coexists with an explicit owner and never conflicts.
 * - `auto`: an SRS-suggested race from the preview. Not owned, but "reservable".
 * - `mandatory`: an immovable career objective race. Read-only and outranks everything.
 * - `annotation`: a non-action marker that only decorates a turn (stop-at-date). Coexists with any owner and never conflicts.
 */
export type EventOwnership = "explicit" | "reservation" | "auto" | "mandatory" | "annotation"

/** One scheduled thing a source contributes to a turn. */
export interface ScheduleEvent {
    /** Which source produced this event. */
    sourceId: ScheduleSourceId
    /** The 1-indexed career turn (1-72). */
    turn: number
    /** How this event participates in one-owner enforcement. */
    ownership: EventOwnership
    /** Compact cell glyph (e.g. "📅", "🔒", "✨", "🛑", "📌"); empty when a race badge stands in for it. */
    marker: string
    /** Detail-sheet title, e.g. "Recreation date". */
    label: string
    /** Optional secondary text (race name, preset, stop string). */
    detail?: string
    /** Optional accent color (a theme token value or a grade color). */
    color?: string
    /** Grade text ("G1", "OP") when this event is a race. */
    badge?: string
    /** False only for mandatory races. */
    movable: boolean
    /** Opaque per-source payload handed back to `claimTurn` (e.g. a race key or the train sentinel). */
    variant?: string
}

/** A single character's mandatory career races, parsed from `character_objectives.json`. */
export interface CharacterObjectives {
    /** Character display name. */
    name: string
    /** Turn-pinned mandatory races (each may offer a choice of options). */
    mandatoryRaces: Array<{
        /** 1-indexed career turn the race is pinned to. */
        turn: number
        /** Whether the objective offers a choice between `options`. */
        isChoice: boolean
        /** The race option(s) for this turn. */
        options: Array<{ raceName: string; grade: string; surface: string; distanceType: string; fans: number }>
    }>
}

/** Read-only inputs every source derives its events from. */
export interface ScheduleSourceContext {
    /** The `general` settings slice (recreation, pure passion, stop-at-date). */
    general: Settings["general"]
    /** The `racing` settings slice (SRS manual locks + enable flag). */
    racing: Settings["racing"]
    /** The SRS solver preview, or null when SRS is off / still loading. */
    preview: SchedulePreview | null
    /** Race catalog keyed by "<name> (<date>)". */
    racesByKey: Record<string, RaceEntry>
    /** Parsed `character_objectives.json`, keyed by character name. */
    objectives: Record<string, CharacterObjectives>
    /** Active character preset (for the mandatory-race lookup). */
    character: string
    /** Active scenario (Trackblazer excludes objective-style mandatory races). */
    scenario: string
}

/** Slice updater mirroring `BotStateContext`'s internal `SliceUpdater` (accepts an object patch or a `(prev) => nextFullSlice` function). */
export type ScheduleSliceUpdater<T> = (update: Partial<T> | ((prev: T) => T)) => void

/** The settings writers a source uses to claim/release a turn. */
export interface ScheduleMutators {
    /** Writer for the `general` slice. */
    updateGeneral: ScheduleSliceUpdater<Settings["general"]>
    /** Writer for the `racing` slice. */
    updateRacing: ScheduleSliceUpdater<Settings["racing"]>
}

/** A pluggable schedule feature: stateless, derives events from context, and routes edits back through the existing settings keys. */
export interface ScheduleSource {
    /** Stable id. */
    id: ScheduleSourceId
    /** Human label for the legend and source toggle. */
    title: string
    /** When true, a claim by this source coexists with the turn's action owner and never triggers one-owner enforcement (e.g. stop-at-date halt markers, ownership `annotation`). */
    coexists?: boolean
    /** Whether this source is currently active (drives whether its events render). */
    isEnabled: (ctx: ScheduleSourceContext) => boolean
    /** All events this source contributes across the calendar. */
    getEvents: (ctx: ScheduleSourceContext) => ScheduleEvent[]
    /** Claim a turn for this source (writes to the existing settings key). Absent on read-only sources like mandatory. */
    claimTurn?: (turn: number, ctx: ScheduleSourceContext, mut: ScheduleMutators, variant?: string) => void
    /** Release this source's claim on a turn. Absent on read-only sources. */
    releaseTurn?: (turn: number, ctx: ScheduleSourceContext, mut: ScheduleMutators) => void
}
