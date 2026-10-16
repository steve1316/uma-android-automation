import { APTITUDE_RANKS } from "./solver/constants"

/** Spark labels for each aptitude key in the trainee catalog. */
const APTITUDE_LABELS: Record<string, string> = {
    turf: "TURF",
    dirt: "DIRT",
    sprint: "SPRNT",
    mile: "MILE",
    medium: "MED",
    long: "LONG",
    front: "FRONT",
    pace: "PACE",
    late: "LATE",
    end: "END",
}

/** Spark labels for each stat key in the trainee catalog. */
const STAT_LABELS: Record<string, string> = { speed: "SPD", stamina: "STA", power: "PWR", guts: "GUTS", wit: "WIT" }

const SURFACES = ["turf", "dirt"]
const DISTANCES = ["sprint", "mile", "medium", "long"]
const STYLES = ["front", "pace", "late", "end"]

/** The parts of a trainee outfit the solver preset is built from. */
interface SolverOutfit {
    /** Character name, which is also the solver's preset name. */
    name: string
    /** The outfit's grades keyed like the trainee catalog, such as `mile` or `turf`. */
    aptitudes: Record<string, string>
}

/** The racing settings that tell the Smart Race Solver which trainee it plans for. */
interface SolverPreset {
    /** The solver's character preset name. */
    smartRaceSolverCharacterPreset: string
    /** The solver's distance and surface grades as a JSON string. */
    smartRaceSolverAptitudes: string
}

/**
 * Ranks a grade so a lower number is better.
 * @param grade The grade letter.
 * @returns The grade's position, with an unknown grade ranked worst.
 */
const rank = (grade: string | undefined): number => {
    const i = grade ? APTITUDE_RANKS.indexOf(grade) : -1
    return i >= 0 ? i : APTITUDE_RANKS.length
}

/**
 * Derives the Smart Auto-Select spark wish list for an outfit. Mirrors `SmartSparks` in `Sparks.kt`, which the bot uses, so the settings page previews the same list.
 * @param aptitudes The outfit's grades keyed by "turf", "dirt", "sprint", "mile", "medium", "long", "front", "pace", "late" and "end".
 * @param growth The outfit's growth bonuses in percent keyed by "speed", "stamina", "power", "guts" and "wit".
 * @param hasLongGoal Whether one of the trainee's goal races is always a Long race. Ranks Stamina first when the outfit's Long grade is C or better.
 * @returns Spark labels, most wanted first.
 */
export const smartSparks = (aptitudes: Record<string, string>, growth: Record<string, number>, hasLongGoal = false): string[] => {
    const wanted: string[] = []

    // A distance or surface graded B can be used, so sparks that lift it to A pay off. C or lower is out of reach.
    for (const key of [...DISTANCES, ...SURFACES]) if (aptitudes[key] === "B") wanted.push(APTITUDE_LABELS[key])
    const bestStyle = STYLES.reduce((best, key) => (rank(aptitudes[key]) < rank(aptitudes[best]) ? key : best), STYLES[0])
    if (rank(aptitudes[bestStyle]) > rank("A")) wanted.push(APTITUDE_LABELS[bestStyle])

    // A Long goal race needs far more Stamina than a Speed-focused build trains, and failing it ends the career. A Long grade of D or worse cannot win it anyway.
    if (hasLongGoal && rank(aptitudes.long) <= rank("C")) wanted.push("STA")
    wanted.push("SPD")
    const bestDistanceRank = Math.min(...DISTANCES.map((key) => rank(aptitudes[key])))
    if (DISTANCES.filter((key) => rank(aptitudes[key]) === bestDistanceRank).some((key) => key === "medium" || key === "long")) wanted.push("STA")
    Object.entries(growth)
        .filter(([, value]) => value > 0)
        .sort((a, b) => b[1] - a[1])
        .forEach(([key]) => wanted.push(STAT_LABELS[key]))
    wanted.push("PWR")
    return [...new Set(wanted)]
}

/**
 * Builds the solver settings for an outfit, so the solver plans races for the trainee Career Setup picks. The outfit's own grades are used since a few
 * outfits differ from their character's base grades.
 * @param outfit The picked outfit.
 * @returns The racing settings to save.
 */
export const solverPresetFor = (outfit: SolverOutfit): SolverPreset => ({
    smartRaceSolverCharacterPreset: outfit.name,
    smartRaceSolverAptitudes: JSON.stringify({
        Sprint: outfit.aptitudes.sprint,
        Mile: outfit.aptitudes.mile,
        Medium: outfit.aptitudes.medium,
        Long: outfit.aptitudes.long,
        Turf: outfit.aptitudes.turf,
        Dirt: outfit.aptitudes.dirt,
    }),
})
