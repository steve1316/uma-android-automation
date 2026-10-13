import { dailiesChips } from "./heroDailiesData"

const ALL_ON = {
    enableTeamTrials: true,
    teamTrialsUseParfait: false,
    enableDailyRaces: true,
    dailyRaceType: "Jupiter Cup",
    enableLegendRace: true,
    legendRaceCharacter: "El Condor Pasa",
    legendRaceUseParfait: false,
    clubRequestEnabled: true,
    clubRequestItem: "Mile Racing Shoes",
    clubDonateEnabled: true,
    enableMissions: true,
    enablePresents: true,
}

describe("dailiesChips", () => {
    it("lists every enabled part in run order, each linked to its setting", () => {
        expect(dailiesChips(ALL_ON)).toEqual([
            { label: "Team Trials", targetId: "dailies-team-trials" },
            { label: "Jupiter Cup", targetId: "dailies-daily-race-type" },
            { label: "Legend: El Condor Pasa", targetId: "dailies-legend-character" },
            { label: "Club: Mile Racing Shoes", targetId: "dailies-club-item" },
            { label: "Missions", targetId: "dailies-missions" },
            { label: "Presents", targetId: "dailies-presents" },
        ])
    })

    it("leaves out parts that are off", () => {
        const chips = dailiesChips({ ...ALL_ON, enableTeamTrials: false, enableLegendRace: false, enablePresents: false })
        expect(chips.map((c) => c.label)).toEqual(["Jupiter Cup", "Club: Mile Racing Shoes", "Missions"])
    })

    it("shows the Club as donate-only when no item is requested", () => {
        const chips = dailiesChips({ ...ALL_ON, clubRequestEnabled: false })
        expect(chips).toContainEqual({ label: "Club: Donate", targetId: "dailies-club-donate" })
    })

    it("drops the Club chip when both Club switches are off", () => {
        const chips = dailiesChips({ ...ALL_ON, clubRequestEnabled: false, clubDonateEnabled: false })
        expect(chips.some((c) => c.label.startsWith("Club"))).toBe(false)
    })
})
