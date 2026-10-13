import type { Settings } from "../../context/BotStateContext"

/** One Dailies chip on the hero: its label and the Dailies Settings row it opens. */
export interface DailiesChip {
    /** Chip text, such as "Legend: El Condor Pasa". */
    label: string
    /** Search id of the Dailies Settings row the chip deep-links to. */
    targetId: string
}

/**
 * Lists the enabled Dailies parts as hero chips, in run order.
 * @param dailies The Dailies settings.
 * @returns One chip per enabled part.
 */
export function dailiesChips(dailies: Settings["dailies"]): DailiesChip[] {
    const chips: DailiesChip[] = []
    if (dailies.enableTeamTrials) chips.push({ label: "Team Trials", targetId: "dailies-team-trials" })
    if (dailies.enableDailyRaces) chips.push({ label: dailies.dailyRaceType, targetId: "dailies-daily-race-type" })
    if (dailies.enableLegendRace) chips.push({ label: `Legend: ${dailies.legendRaceCharacter}`, targetId: "dailies-legend-character" })
    if (dailies.clubRequestEnabled) chips.push({ label: `Club: ${dailies.clubRequestItem}`, targetId: "dailies-club-item" })
    else if (dailies.clubDonateEnabled) chips.push({ label: "Club: Donate", targetId: "dailies-club-donate" })
    if (dailies.enableMissions) chips.push({ label: "Missions", targetId: "dailies-missions" })
    if (dailies.enablePresents) chips.push({ label: "Presents", targetId: "dailies-presents" })
    return chips
}
