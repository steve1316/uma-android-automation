import {
    APT_ORDER,
    AptitudeMap,
    BASE_SP_BY_GRADE,
    BASE_STAT_BY_GRADE,
    COUNTRY_NAMES,
    EPITHETS_BY_NAME,
    EpithetEntry,
    MatcherProgress,
    normalizeGrade,
    OP_GRADES,
    PreviewStats,
    RaceEntry,
    WeightsMap,
} from "./constants"
import { SchedulePreview } from "./preview"

/** Matches gametora's "<X> scenario only" bullet. Group 1 captures the scenario name.
 *  Mirror of `EpithetFilters.SCENARIO_RESTRICTION_REGEX` in the Kotlin solver. */
const SCENARIO_RESTRICTION_REGEX = /([A-Za-z][A-Za-z0-9 \-]*?) scenario only/i

/** Matches gametora's character-restriction bullet, e.g. "Yaeno Muteki only".
 *  The whole bullet must be `<character name> only` - bullets containing extra words don't qualify.
 *  Mirror of `EpithetFilters.CHARACTER_RESTRICTION_REGEX`. */
const CHARACTER_RESTRICTION_REGEX = /^(.+?)\s+only$/

/** Matches gametora's stat-reward bullet. Current form: "2 random stats +10".
 *  The legacy "+10 to 2 random stats" wording is also recognised so a re-scrape isn't required to keep older JSON snapshots working.
 *  Groups 1+2 capture the current form (count, per-stat). Groups 3+4 capture the legacy form (per-stat, count).
 *  Mirror of `EpithetFilters.STAT_REWARD_REGEX`. */
const STAT_REWARD_REGEX = /(?:(\d+)\s+random\s+stats?\s*\+(\d+))|(?:\+(\d+)\s+to\s+(\d+)\s+random\s+stats?)/i

/** Matches gametora's hint-reward bullet, e.g. "Reward: Top Pick hint +1" or "Homestretch Haste hint +1". Group 1 = level. */
const HINT_REWARD_REGEX = /hint\s*\+(\d+)/i

/**
 * Extracts scenario restrictions from an epithet's bullet list.
 * By convention the restriction is the first bullet, but the scan covers every bullet so ordering drift won't silently break the gate.
 * An empty return means the epithet is universally obtainable. Mirrors `EpithetFilters.scenariosFromBullets` in Kotlin.
 *
 * @param e Epithet entry to inspect.
 * @returns Scenario names referenced by any "<X> scenario only" bullet.
 */
export const scenariosForEpithet = (e: EpithetEntry): string[] => {
    if (e.scenarios && e.scenarios.length > 0) return e.scenarios
    const bullets = e.bullet_points ?? []
    const out: string[] = []
    for (const b of bullets) {
        const m = SCENARIO_RESTRICTION_REGEX.exec(b)
        if (m) out.push(m[1].trim())
    }
    return out
}

/**
 * Extracts character restrictions from an epithet's bullet list. Gametora prints these as a standalone bullet like "Yaeno Muteki only".
 * Scenario-restriction bullets ("X scenario only") are excluded so they don't collide with the character regex.
 * An empty return means the epithet has no character gate. Mirrors `EpithetFilters.charactersFromBullets` in Kotlin.
 *
 * @param e Epithet entry to inspect.
 * @returns Character names referenced by any standalone "<name> only" bullet.
 */
export const charactersForEpithet = (e: EpithetEntry): string[] => {
    if (e.characters && e.characters.length > 0) return e.characters
    const bullets = e.bullet_points ?? []
    const out: string[] = []
    for (const b of bullets) {
        const trimmed = b.trim().replace(/\.$/, "")
        if (/scenario only/i.test(trimmed)) continue
        const m = CHARACTER_RESTRICTION_REGEX.exec(trimmed)
        if (m) out.push(m[1].trim())
    }
    return out
}

/**
 * Parses an epithet's reward bullet (last by convention) into kind + total magnitude.
 * Falls back to scanning every bullet so a row whose reward isn't last still works.
 * Mirrors `EpithetFilters.rewardFromBullets` in Kotlin.
 *
 * @param e Epithet entry to inspect.
 * @returns `{ kind: "stat" | "hint" | "unknown"; amount }` where `amount` is `per_stat * stat_count` for stat rewards,
 *   the level for hint rewards, and 0 otherwise.
 */
const epithetReward = (e: EpithetEntry): { kind: "stat" | "hint" | "unknown"; amount: number } => {
    const bullets = e.bullet_points ?? []
    if (bullets.length === 0) return { kind: "unknown", amount: 0 }
    const ordered = [bullets[bullets.length - 1], ...bullets.slice(0, -1)]
    for (const b of ordered) {
        const stat = STAT_REWARD_REGEX.exec(b)
        if (stat) {
            // Groups 1+2 cover the current "<count> random stats +<perStat>" form; groups
            // 3+4 cover the legacy "+<perStat> to <count> random stats" form.
            const count = parseInt(stat[1] ?? stat[4] ?? "0", 10)
            const perStat = parseInt(stat[2] ?? stat[3] ?? "0", 10)
            return { kind: "stat", amount: count * perStat }
        }
        const hint = HINT_REWARD_REGEX.exec(b)
        if (hint) return { kind: "hint", amount: parseInt(hint[1], 10) }
    }
    return { kind: "unknown", amount: 0 }
}

/**
 * True iff the race's name contains one of the `COUNTRY_NAMES` tokens (Globe-Trotter filter).
 *
 * @param name The race name to test.
 * @returns True when the name contains any of the country tokens.
 */
export const nameContainsCountry = (name: string): boolean => COUNTRY_NAMES.some((c) => name.includes(c))

/**
 * True for graded races (G1, G2, G3).
 *
 * @param grade The race grade string.
 * @returns True iff `grade` is "G1", "G2", or "G3".
 */
const isGradedRace = (grade: string): boolean => grade === "G1" || grade === "G2" || grade === "G3"

/**
 * True for OP-tier races and above (graded, OP, FINALE, EX).
 *
 * @param grade The race grade string.
 * @returns True iff `grade` is graded or one of "OP", "FINALE", "EX".
 */
const isOpenOrAboveRace = (grade: string): boolean => isGradedRace(grade) || grade === "OP" || grade === "FINALE" || grade === "EX"

/**
 * TS mirror of `ScoringFunctions.isEligible`. A race is eligible iff its distance and surface
 * aptitudes both meet `weights.aptitudeThreshold`, and OP/Pre-OP races are only allowed when
 * `weights.includeOpAndPreOp` is true.
 *
 * @param race The race to test.
 * @param aptitudes Trainee's distance + surface aptitudes.
 * @param weights Solver weights (only `aptitudeThreshold` and `includeOpAndPreOp` are read).
 * @returns True when the race passes the eligibility filter.
 */
export const isRaceEligible = (race: RaceEntry, aptitudes: AptitudeMap, weights: Pick<WeightsMap, "aptitudeThreshold" | "includeOpAndPreOp">): boolean => {
    if (OP_GRADES.has(race.grade) && !weights.includeOpAndPreOp) return false
    const threshold = APT_ORDER[weights.aptitudeThreshold] ?? 4
    const distKey = race.distanceType === "Sprint" ? "Sprint" : race.distanceType === "Mile" ? "Mile" : race.distanceType === "Medium" ? "Medium" : race.distanceType === "Long" ? "Long" : null
    const surfKey = race.terrain === "Turf" ? "Turf" : race.terrain === "Dirt" ? "Dirt" : null
    if (!distKey || !surfKey) return false
    const distApt = APT_ORDER[(aptitudes as any)[distKey]] ?? 0
    const surfApt = APT_ORDER[(aptitudes as any)[surfKey]] ?? 0
    return distApt >= threshold && surfApt >= threshold
}

/**
 * Predicate evaluating whether `matcher` (a raw record from `epithets.json`) is satisfied by `race`.
 * Mirrors the matcher branches in `Epithet.kt`: `winRace`, `winRaceTimes`, `winAnyOf`, `winAtLeast`, `winCount`.
 * `epithetAll` / `epithetAnyOf` are dependency matchers and return false here.
 *
 * @param matcher Raw matcher record from `epithets.json`.
 * @param race The race to test.
 * @returns True when this matcher fires for this race.
 */
const matcherMatchesRace = (matcher: Record<string, unknown>, race: RaceEntry): boolean => {
    const type = matcher["type"] as string
    const name = matcher["name"] as string | undefined
    const names = (matcher["names"] as string[] | undefined) ?? []
    switch (type) {
        case "winRace":
        case "winRaceTimes":
            return name != null && name === race.name
        case "winAnyOf":
        case "winAtLeast":
            return names.includes(race.name)
        case "winCount": {
            const f = (matcher["filter"] as Record<string, unknown> | undefined) ?? {}
            if (f["terrain"] && f["terrain"] !== race.terrain) return false
            if (f["grade"] && f["grade"] !== race.grade) return false
            if (f["gradedOnly"] && !isGradedRace(race.grade)) return false
            if (f["gradeAtLeastOpen"] && !isOpenOrAboveRace(race.grade)) return false
            const dts = f["distanceTypes"] as string[] | undefined
            if (dts && dts.length > 0 && !dts.includes(race.distanceType)) return false
            const tracks = f["raceTracks"] as string[] | undefined
            if (tracks && tracks.length > 0 && !tracks.includes(race.raceTrack)) return false
            const nameContains = f["nameContains"] as string | undefined
            if (nameContains && !race.name.toLowerCase().includes(nameContains.toLowerCase())) return false
            if (f["nameContainsCountry"] && !nameContainsCountry(race.name)) return false
            return true
        }
        default:
            return false
    }
}

/**
 * Returns every epithet whose matcher list references the given race.
 * Mirrors the matcher branches in `Epithet.kt`: `winRace`, `winRaceTimes`, `winAnyOf`, `winAtLeast`, `winCount`.
 * `epithetAll` / `epithetAnyOf` are dependency matchers and are intentionally skipped here.
 *
 * @param race The race to test against every epithet's matcher list.
 * @returns Epithet entries whose matchers reference `race`.
 */
export const epithetsForRace = (race: RaceEntry): EpithetEntry[] => {
    const all = EPITHETS_BY_NAME
    const out: EpithetEntry[] = []
    for (const ep of Object.values(all)) {
        const matchers = ep.matchers ?? []
        if (matchers.some((m) => matcherMatchesRace(m, race))) out.push(ep)
    }
    return out
}

/**
 * Returns the indices of matchers in `ep.matchers` that fire for `race`.
 * Useful when callers need to know not just whether an epithet matched but which matcher(s) drove the match.
 *
 * @param race The race being scheduled.
 * @param ep Epithet whose matcher list should be evaluated.
 * @returns Indices into `ep.matchers` for matchers satisfied by `race`. Empty when none fire.
 */
const matchingMatcherIndicesForRace = (race: RaceEntry, ep: EpithetEntry): number[] => {
    const matchers = ep.matchers ?? []
    const out: number[] = []
    for (let i = 0; i < matchers.length; i++) {
        if (matcherMatchesRace(matchers[i], race)) out.push(i)
    }
    return out
}

/**
 * Picks the best display string for a single fired matcher.
 * Prefers a verbatim bullet from `bullets` so the label matches gametora's authored phrasing in the rest of the UI.
 * Falls back to the pre-computed `displayLabel` / `displayLabelTemplate` carried on the matcher
 * (populated by `scripts/precompute-epithet-labels.ts`), so the React popover, the Race History tooltip,
 * and the Kotlin win log all render identical text for the same race + matcher.
 *
 * @param matcher The matcher that fired.
 * @param race The race that triggered it.
 * @param bullets The same epithet's `bullet_points` array.
 * @returns Display label, or null when `matcher` is a prerequisite type with no race-condition meaning.
 */
const matcherConditionLabel = (matcher: Record<string, unknown>, race: RaceEntry, bullets: string[]): string | null => {
    const type = matcher["type"] as string
    const name = matcher["name"] as string | undefined
    const findBulletContaining = (needle: string): string | null => {
        if (!needle) return null
        const lower = needle.toLowerCase()
        return (
            bullets.find((b) => {
                const l = b.toLowerCase()
                // Inheritance-prereq bullets often contain the matcher's race name (e.g. "Inherit memories from a parent that won the Arima Kinen") but
                // describe an unverifiable parent condition, not the matcher's actual race. Skip them so the matcher's own displayLabel wins.
                if (l.startsWith("inherit memories") || l.startsWith("inherit the memories")) return false
                return l.includes(lower)
            }) ?? null
        )
    }
    const keywords: string[] = []
    switch (type) {
        case "winRace":
        case "winRaceTimes":
            if (name) keywords.push(name)
            break
        case "winAnyOf":
        case "winAtLeast":
            keywords.push(race.name)
            break
        case "winCount": {
            const f = (matcher["filter"] as Record<string, unknown> | undefined) ?? {}
            const terrain = f["terrain"] as string | undefined
            const grade = f["grade"] as string | undefined
            const dts = (f["distanceTypes"] as string[] | undefined) ?? []
            if (terrain) keywords.push(terrain.toLowerCase())
            if (grade) keywords.push(grade)
            for (const dt of dts) keywords.push(dt.toLowerCase())
            break
        }
        case "epithetAnyOf":
        case "epithetAll":
            return null
    }
    for (const k of keywords) {
        const hit = findBulletContaining(k)
        if (hit) return hit
    }
    const template = matcher["displayLabelTemplate"] as string | undefined
    if (template) return template.replace("{race}", race.name)
    const label = matcher["displayLabel"] as string | undefined
    return label ?? null
}

/**
 * Builds short labels describing which condition(s) of `ep` this race progresses.
 * For each matcher that fires for `race`, the helper prefers a verbatim bullet from `ep.bullet_points` so the label matches gametora's wording.
 *
 * @param race The race contributing progress.
 * @param ep Epithet whose condition the race advances.
 * @returns Deduped condition labels in matcher-list order. Empty when no matcher in `ep` fires for `race`.
 */
export const conditionLabelsForRaceAndEpithet = (race: RaceEntry, ep: EpithetEntry): string[] => {
    const matchers = ep.matchers ?? []
    const bullets = ep.bullet_points ?? []
    const seen = new Set<string>()
    const out: string[] = []
    for (const idx of matchingMatcherIndicesForRace(race, ep)) {
        const label = matcherConditionLabel(matchers[idx], race, bullets)
        if (label && !seen.has(label)) {
            seen.add(label)
            out.push(label)
        }
    }
    return out
}

/**
 * Computes how much progress a single matcher has accumulated up to and including `upToTurn`, based on the preview's race decisions.
 * Returns null when the matcher type isn't progress-trackable (e.g. `epithetAll` / `epithetAnyOf`). `current` is capped at `required`.
 *
 * @param upToTurn Inclusive upper bound on which turns count toward progress.
 * @param matcher Raw matcher record from `epithets.json`.
 * @param preview Schedule preview that supplies the win history.
 * @param racesByKey Lookup table from race key to race entry.
 * @returns Progress for the matcher, or null when the matcher type isn't progress-trackable.
 */
const matcherProgress = (upToTurn: number, matcher: Record<string, unknown>, preview: SchedulePreview, racesByKey: Record<string, RaceEntry>): MatcherProgress | null => {
    const winsUpTo: Array<{ turn: number; race: RaceEntry }> = []
    for (const [turnStr, dec] of Object.entries(preview.decisions)) {
        const t = parseInt(turnStr, 10)
        if (Number.isNaN(t) || t > upToTurn) continue
        if (dec.type !== "Race") continue
        const r = dec.raceKey ? racesByKey[dec.raceKey] : undefined
        if (!r) continue
        winsUpTo.push({ turn: t, race: r })
    }

    const type = matcher["type"] as string
    switch (type) {
        case "winRace": {
            const name = matcher["name"] as string
            const hit = winsUpTo.some((w) => w.race.name === name)
            return { current: hit ? 1 : 0, required: 1 }
        }
        case "winRaceTimes": {
            const name = matcher["name"] as string
            const required = (matcher["times"] as number) ?? 1
            const current = winsUpTo.filter((w) => w.race.name === name).length
            return { current: Math.min(current, required), required }
        }
        case "winAnyOf": {
            const names = (matcher["names"] as string[]) ?? []
            const required = (matcher["count"] as number) ?? names.length
            const current = winsUpTo.filter((w) => names.includes(w.race.name)).length
            return { current: Math.min(current, required), required }
        }
        case "winAtLeast": {
            const names = (matcher["names"] as string[]) ?? []
            const required = (matcher["count"] as number) ?? names.length
            const distinct = new Set(winsUpTo.filter((w) => names.includes(w.race.name)).map((w) => w.race.name))
            return { current: Math.min(distinct.size, required), required }
        }
        case "winCount": {
            const f = (matcher["filter"] as Record<string, unknown>) ?? {}
            const required = (matcher["count"] as number) ?? 1
            let current = 0
            for (const w of winsUpTo) {
                if (f["terrain"] && f["terrain"] !== w.race.terrain) continue
                if (f["grade"] && f["grade"] !== w.race.grade) continue
                if (f["gradedOnly"] && !isGradedRace(w.race.grade)) continue
                if (f["gradeAtLeastOpen"] && !isOpenOrAboveRace(w.race.grade)) continue
                const dts = f["distanceTypes"] as string[] | undefined
                if (dts && dts.length > 0 && !dts.includes(w.race.distanceType)) continue
                const tracks = f["raceTracks"] as string[] | undefined
                if (tracks && tracks.length > 0 && !tracks.includes(w.race.raceTrack)) continue
                const nameContains = f["nameContains"] as string | undefined
                if (nameContains && !w.race.name.toLowerCase().includes(nameContains.toLowerCase())) continue
                if (f["nameContainsCountry"] && !nameContainsCountry(w.race.name)) continue
                current++
            }
            return { current: Math.min(current, required), required }
        }
        default:
            return null
    }
}

/**
 * Aggregate progress across ALL of an epithet's matchers as of `upToTurn`.
 * Sums each matcher's `(current, required)` so multi-condition epithets like Turf Tussler render as "(1/4) -> (4/4)"
 * instead of "(1/1)" after just one matcher fires.
 * Returns null when no matchers progress.
 *
 * @param upToTurn Inclusive upper bound on which turns count toward progress.
 * @param ep Epithet entry to evaluate.
 * @param preview Schedule preview that supplies the win history.
 * @param racesByKey Lookup table from race key to race entry.
 * @returns Aggregate `(current, required)` across the epithet's matchers, or null when no matchers progress.
 */
export const epithetProgress = (upToTurn: number, ep: EpithetEntry, preview: SchedulePreview, racesByKey: Record<string, RaceEntry>): MatcherProgress | null => {
    let totalCurrent = 0
    let totalRequired = 0
    for (const m of ep.matchers ?? []) {
        const p = matcherProgress(upToTurn, m, preview, racesByKey)
        if (!p) continue
        totalCurrent += p.current
        totalRequired += p.required
    }
    if (totalRequired === 0) return null
    return { current: totalCurrent, required: totalRequired }
}

/**
 * Reports whether `epName` is fully satisfied by the schedule preview as of `upToTurn`. Mirrors `EpithetTracker.classify`'s
 * COMPLETED branch in Kotlin: every win* matcher must be fully met, and every dependency matcher (`epithetAnyOf`, `epithetAll`)
 * must hold against other epithets' completion at the same turn. The visited set guards against pathological dependency cycles.
 *
 * @param epName Name of the epithet to evaluate.
 * @param upToTurn Inclusive upper bound on the turn timeline used for matcher progress.
 * @param epithetsByName Lookup table built once by the caller from the global epithet list.
 * @param preview Schedule preview that supplies the win history.
 * @param racesByKey Lookup table from race key to race entry.
 * @param visited Names already on the recursion stack; callers usually pass the default empty set.
 * @returns True when every matcher on the epithet is satisfied at `upToTurn`.
 */
const isEpithetCompletedAtTurn = (
    epName: string,
    upToTurn: number,
    epithetsByName: Map<string, EpithetEntry>,
    preview: SchedulePreview,
    racesByKey: Record<string, RaceEntry>,
    visited: Set<string> = new Set(),
): boolean => {
    if (visited.has(epName)) return false
    const ep = epithetsByName.get(epName)
    if (!ep) return false
    const nextVisited = new Set(visited).add(epName)
    for (const m of ep.matchers ?? []) {
        const type = m["type"] as string
        if (type === "epithetAnyOf") {
            const names = (m["names"] as string[] | undefined) ?? []
            if (!names.some((n) => isEpithetCompletedAtTurn(n, upToTurn, epithetsByName, preview, racesByKey, nextVisited))) return false
        } else if (type === "epithetAll") {
            const names = (m["names"] as string[] | undefined) ?? []
            if (!names.every((n) => isEpithetCompletedAtTurn(n, upToTurn, epithetsByName, preview, racesByKey, nextVisited))) return false
        } else {
            const p = matcherProgress(upToTurn, m, preview, racesByKey)
            if (!p) continue
            if (p.current < p.required) return false
        }
    }
    return true
}

/**
 * Lists the unmet dependency-prerequisite phrases for `ep` evaluated against the schedule projection at `upToTurn`.
 * Mirrors Kotlin's `pendingPrerequisitesFor` exactly: scan the matcher list for `epithetAnyOf` / `epithetAll`, and for each
 * unmet name emit the verbatim bullet that references it (case-insensitive substring match) or fall back to `"Get the <name> epithet"`.
 *
 * @param ep Epithet whose dependency matchers to inspect.
 * @param upToTurn Inclusive upper bound on the turn timeline used for completion lookups.
 * @param epithetsByName Lookup table built once by the caller from the global epithet list.
 * @param preview Schedule preview that supplies the win history.
 * @param racesByKey Lookup table from race key to race entry.
 * @returns Pending-prerequisite phrases in matcher order, deduplicated by referenced name. Empty when no prerequisites are unmet.
 */
export const pendingPrerequisitesForEpithet = (
    ep: EpithetEntry,
    upToTurn: number,
    epithetsByName: Map<string, EpithetEntry>,
    preview: SchedulePreview,
    racesByKey: Record<string, RaceEntry>,
): string[] => {
    const out: string[] = []
    const seen = new Set<string>()
    const bullets = ep.bullet_points ?? []
    for (const m of ep.matchers ?? []) {
        const type = m["type"] as string
        const names = (m["names"] as string[] | undefined) ?? []
        let pending: string[] = []
        if (type === "epithetAnyOf") {
            pending = names.some((n) => isEpithetCompletedAtTurn(n, upToTurn, epithetsByName, preview, racesByKey)) ? [] : names
        } else if (type === "epithetAll") {
            pending = names.filter((n) => !isEpithetCompletedAtTurn(n, upToTurn, epithetsByName, preview, racesByKey))
        }
        for (const name of pending) {
            if (seen.has(name)) continue
            seen.add(name)
            const lower = name.toLowerCase()
            const bullet = bullets.find((b) => b.toLowerCase().includes(lower))
            out.push(bullet ?? `Get the ${name} epithet`)
        }
    }
    return out
}

/**
 * Set of turn numbers whose race actually counts toward completing `ep`, walked chronologically and capped at each matcher's required count.
 * Used by the calendar-cell highlight: a race that *could* satisfy a matcher's filter but exceeds the required count is NOT in the set.
 *
 * @param ep Epithet entry to evaluate.
 * @param preview Schedule preview that supplies the win history.
 * @param racesByKey Lookup table from race key to race entry.
 * @returns Turns whose scheduled race contributes to completing the epithet.
 */
export const turnsContributingToEpithet = (ep: EpithetEntry, preview: SchedulePreview, racesByKey: Record<string, RaceEntry>): Set<number> => {
    const contributing = new Set<number>()
    const winsOrdered: Array<{ turn: number; race: RaceEntry }> = []
    for (const [turnStr, dec] of Object.entries(preview.decisions)) {
        const t = parseInt(turnStr, 10)
        if (Number.isNaN(t)) continue
        if (dec.type !== "Race") continue
        const r = dec.raceKey ? racesByKey[dec.raceKey] : undefined
        if (!r) continue
        winsOrdered.push({ turn: t, race: r })
    }
    winsOrdered.sort((a, b) => a.turn - b.turn)

    for (const matcher of ep.matchers ?? []) {
        const type = matcher["type"] as string
        switch (type) {
            case "winRace": {
                const name = matcher["name"] as string
                for (const w of winsOrdered) {
                    if (w.race.name === name) {
                        contributing.add(w.turn)
                        break
                    }
                }
                break
            }
            case "winRaceTimes": {
                const name = matcher["name"] as string
                const required = (matcher["times"] as number) ?? 1
                let counted = 0
                for (const w of winsOrdered) {
                    if (counted >= required) break
                    if (w.race.name === name) {
                        contributing.add(w.turn)
                        counted++
                    }
                }
                break
            }
            case "winAnyOf": {
                const names = (matcher["names"] as string[]) ?? []
                const required = (matcher["count"] as number) ?? names.length
                let counted = 0
                for (const w of winsOrdered) {
                    if (counted >= required) break
                    if (names.includes(w.race.name)) {
                        contributing.add(w.turn)
                        counted++
                    }
                }
                break
            }
            case "winAtLeast": {
                const names = (matcher["names"] as string[]) ?? []
                const required = (matcher["count"] as number) ?? names.length
                const distinctSeen = new Set<string>()
                for (const w of winsOrdered) {
                    if (distinctSeen.size >= required) break
                    if (names.includes(w.race.name) && !distinctSeen.has(w.race.name)) {
                        contributing.add(w.turn)
                        distinctSeen.add(w.race.name)
                    }
                }
                break
            }
            case "winCount": {
                const f = (matcher["filter"] as Record<string, unknown>) ?? {}
                const required = (matcher["count"] as number) ?? 1
                let counted = 0
                for (const w of winsOrdered) {
                    if (counted >= required) break
                    if (f["terrain"] && f["terrain"] !== w.race.terrain) continue
                    if (f["grade"] && f["grade"] !== w.race.grade) continue
                    if (f["gradedOnly"] && !isGradedRace(w.race.grade)) continue
                    if (f["gradeAtLeastOpen"] && !isOpenOrAboveRace(w.race.grade)) continue
                    const dts = f["distanceTypes"] as string[] | undefined
                    if (dts && dts.length > 0 && !dts.includes(w.race.distanceType)) continue
                    const tracks = f["raceTracks"] as string[] | undefined
                    if (tracks && tracks.length > 0 && !tracks.includes(w.race.raceTrack)) continue
                    const nameContains = f["nameContains"] as string | undefined
                    if (nameContains && !w.race.name.toLowerCase().includes(nameContains.toLowerCase())) continue
                    if (f["nameContainsCountry"] && !nameContainsCountry(w.race.name)) continue
                    contributing.add(w.turn)
                    counted++
                }
                break
            }
        }
    }

    return contributing
}

/**
 * Aggregate stats for the reference Trackblazer-style summary panel: race count, epithet count,
 * total race stats (BASE_STAT * (1 + raceBonusPct/100)), race SP, epithet stats, and hint count.
 *
 * @param preview Schedule preview to summarise.
 * @param weights Solver weights (only `raceBonusPct` is read).
 * @param racesByKey Lookup table from race key to race entry.
 * @returns The aggregate {@link PreviewStats}.
 */
export const computePreviewStats = (preview: SchedulePreview, weights: Pick<WeightsMap, "raceBonusPct">, racesByKey: Record<string, RaceEntry>): PreviewStats => {
    const epithetsAll = EPITHETS_BY_NAME
    const rb = Math.max(0, weights.raceBonusPct) / 100
    let races = 0
    let raceStats = 0
    let raceSp = 0
    let fans = 0
    for (const [, entry] of Object.entries(preview.decisions)) {
        if (entry.type !== "Race") continue
        races += 1
        const race = entry.raceKey ? racesByKey[entry.raceKey] : undefined
        const grade = normalizeGrade(race?.grade ?? entry.grade ?? "")
        raceStats += Math.floor((BASE_STAT_BY_GRADE[grade] ?? 0) * (1 + rb))
        raceSp += Math.floor((BASE_SP_BY_GRADE[grade] ?? 0) * (1 + rb))
        fans += race?.fans ?? 0
    }
    let epithetStats = 0
    let hints = 0
    for (const name of preview.projectedEpithets) {
        const ep = epithetsAll[name]
        if (!ep) continue
        const { kind, amount } = epithetReward(ep)
        if (kind === "stat") epithetStats += amount
        else if (kind === "hint") hints += 1
    }
    return { races, epithets: preview.projectedEpithets.length, raceStats, raceSp, epithetStats, hints, fans }
}
