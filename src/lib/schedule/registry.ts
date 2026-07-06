import { mandatorySource } from "./sources/mandatorySource"
import { srsSource } from "./sources/srsSource"
import { recreationSource } from "./sources/recreationSource"
import { purePassionSource } from "./sources/purePassionSource"
import { stopAtDateSource } from "./sources/stopAtDateSource"
import type { ScheduleEvent, ScheduleSource, ScheduleSourceContext, ScheduleSourceId } from "./types"

/** Every schedule source, in render/precedence-collection order. New date-based features append here. */
export const SCHEDULE_SOURCES: ScheduleSource[] = [mandatorySource, srsSource, recreationSource, purePassionSource, stopAtDateSource]

/** The merged view of one turn across all sources. */
export interface MergedTurn {
    /** The 1-indexed career turn. */
    turn: number
    /** The single action owner (mandatory outranks a lone explicit), or undefined when no source owns the turn's action. */
    owner?: ScheduleEvent
    /** SRS "__TRAIN__" keep-clear locks on this turn (coexist with the owner). */
    reservations: ScheduleEvent[]
    /** Non-action markers on this turn (stop-at-date), which coexist with the owner. */
    annotations: ScheduleEvent[]
    /** The SRS solver-suggested race on this turn, if any. */
    autoRace?: ScheduleEvent
    /** Every event on this turn, for the detail sheet. */
    events: ScheduleEvent[]
    /** True when a movable non-SRS explicit owner sits on a turn SRS auto-races and no SRS lock/reservation exists yet - the "reserve this turn" affordance. */
    hasReservableAutoRace: boolean
    /** True when more than one explicit action owner lands on the turn (only reachable via preset apply or imported settings). */
    conflict: boolean
}

/** The whole calendar merged across sources, keyed by turn. */
export interface ScheduleModel {
    /** Merged turns keyed by 1-indexed turn number (only turns with at least one event are present). */
    byTurn: Map<number, MergedTurn>
}

/**
 * Merge every enabled source's events into a per-turn model, choosing the action owner by precedence (mandatory > a single explicit) and flagging conflicts + reservable auto-races.
 * @param sources The schedule sources to aggregate.
 * @param ctx The read-only source context.
 * @returns The merged schedule model keyed by turn.
 */
export function buildScheduleModel(sources: ScheduleSource[], ctx: ScheduleSourceContext): ScheduleModel {
    const byTurn = new Map<number, MergedTurn>()
    const events = sources.filter((source) => source.isEnabled(ctx)).flatMap((source) => source.getEvents(ctx))

    const groups = new Map<number, ScheduleEvent[]>()
    events.forEach((event) => {
        const list = groups.get(event.turn) ?? []
        list.push(event)
        groups.set(event.turn, list)
    })

    groups.forEach((turnEvents, turn) => {
        const mandatory = turnEvents.find((event) => event.ownership === "mandatory")
        const explicits = turnEvents.filter((event) => event.ownership === "explicit")
        const reservations = turnEvents.filter((event) => event.ownership === "reservation")
        const annotations = turnEvents.filter((event) => event.ownership === "annotation")
        const autoRace = turnEvents.find((event) => event.ownership === "auto")
        const owner = mandatory ?? explicits[0]
        // A movable explicit owner is one whose action differs from the source that produced the auto-race (SRS) - i.e. it can be reserved to let the solver reschedule.
        const ownerIsMovableExplicit = !!owner && owner.ownership === "explicit" && owner.sourceId !== autoRace?.sourceId
        byTurn.set(turn, {
            turn,
            owner,
            reservations,
            annotations,
            autoRace,
            events: turnEvents,
            hasReservableAutoRace: ownerIsMovableExplicit && !!autoRace && reservations.length === 0,
            conflict: explicits.length > 1,
        })
    })

    return { byTurn }
}

/** The enforcement decision for a claim attempt: proceed, block on a mandatory race, or require an override that clears the current owner. */
export type TurnClaimPlan = { kind: "free" } | { kind: "blocked-mandatory"; owner: ScheduleEvent } | { kind: "conflict"; owner: ScheduleEvent }

/** Recreation and Pure Passion both take the turn's action as an explicit date, and are mutually exclusive with a stop (you cannot halt and recreate on the same turn). */
function isDateSource(id: ScheduleSourceId): boolean {
    return id === "recreation" || id === "purePassion"
}

/**
 * Decide what happens when `requester` tries to claim `turn`. Stop-at-Date coexists with a race owner but conflicts with a recreation/Pure-Passion date (and vice versa).
 * A mandatory owner blocks the claim. Any other existing owner requires an explicit override that clears it first. An unowned turn (or one already owned by the same source) is free.
 * @param turn The target turn.
 * @param requester The source attempting the claim.
 * @param model The current merged schedule model.
 * @returns The claim plan the UI acts on.
 */
export function planClaim(turn: number, requester: ScheduleSourceId, model: ScheduleModel): TurnClaimPlan {
    const merged = model.byTurn.get(turn)
    const owner = merged?.owner
    if (SCHEDULE_SOURCES.find((source) => source.id === requester)?.coexists) {
        // A coexisting marker (stop-at-date) rides alongside a race, but a stop and a recreation/Pure-Passion date cannot share a turn - you cannot halt and recreate at once.
        if (owner && isDateSource(owner.sourceId)) return { kind: "conflict", owner }
        return { kind: "free" }
    }
    if (!owner) {
        // A date claim collides with an existing stop annotation on the same (otherwise unowned) turn.
        const stop = isDateSource(requester) ? merged?.annotations.find((event) => event.sourceId === "stopAtDate") : undefined
        return stop ? { kind: "conflict", owner: stop } : { kind: "free" }
    }
    if (owner.ownership === "mandatory") return { kind: "blocked-mandatory", owner }
    if (owner.sourceId === requester) return { kind: "free" }
    return { kind: "conflict", owner }
}
