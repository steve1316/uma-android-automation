import type { Settings } from "../../../context/BotStateContext"
import { buildSettingsBanner } from "../buildSettingsBanner"

/**
 * Recursive proxy that returns sensible defaults for any property access. Any property reads as `0` when
 * coerced to a number, `""` when coerced to a string, `false` when coerced to boolean, and `[]` / `{}` for
 * the array/object methods this banner builder calls (`length`, `join`, `map`, `Object.keys`). Avoids hand-rolling
 * the full `Settings` shape just for a banner regression check.
 *
 * @returns A proxy object usable as a `Settings`-shaped stub.
 */
const makeStubSettings = (): Settings => {
    const handler: ProxyHandler<object> = {
        get(_target, prop) {
            if (prop === "length") return 0
            if (prop === "join") return () => ""
            if (prop === "map") return () => []
            if (prop === "split") return () => []
            if (prop === "trim") return () => ""
            if (prop === Symbol.toPrimitive) return () => 0
            if (prop === "toString") return () => "0"
            return new Proxy({}, handler)
        },
    }
    return new Proxy({}, handler) as unknown as Settings
}

/** Dailies values used by the Dailies-mode banner test. */
const DAILIES: Settings["dailies"] = {
    enableTeamTrials: true,
    teamTrialsUseParfait: false,
    enableDailyRaces: true,
    dailyRaceType: "Jupiter Cup",
    enableLegendRace: true,
    legendRaceCharacter: "El Condor Pasa",
    legendRaceUseParfait: false,
    clubRequestEnabled: true,
    clubRequestItem: "Dirt Racing Shoes",
    clubDonateEnabled: true,
    enableMissions: true,
    enablePresents: true,
}

/**
 * Wraps the stub so the given top-level slices return real values.
 * @param overrides Slices to return as given.
 * @returns A `Settings`-shaped stub with those slices replaced.
 */
const withSlices = (overrides: Partial<Record<keyof Settings, unknown>>): Settings => {
    const stub = makeStubSettings()
    return new Proxy(stub as object, { get: (target, prop) => (prop in overrides ? overrides[prop as keyof Settings] : (target as any)[prop]) }) as Settings
}

describe("buildSettingsBanner", () => {
    it("shows the Dailies options instead of the career options in Dailies mode", () => {
        const banner = buildSettingsBanner(withSlices({ general: { runMode: "dailies", scenario: "" }, dailies: DAILIES }))
        expect(banner).toContain("🗓️ Run Mode: Dailies")
        expect(banner).toContain("---------- Dailies Options ----------")
        expect(banner).toContain("🏆 Legend to Race: El Condor Pasa")
        expect(banner).toContain("🎁 Club Item to Request: Dirt Racing Shoes")
        expect(banner).not.toContain("---------- Training Options ----------")
    })

    it("lists only the Dailies debug tests in Dailies mode", () => {
        const debug = { enableDebugMode: false, debugMode_startDailiesPresentsTest: true }
        const banner = buildSettingsBanner(withSlices({ general: { runMode: "dailies", scenario: "" }, dailies: DAILIES, debug }))
        expect(banner).toContain("🔍 Start Dailies Presents Test: ✅")
        expect(banner).toContain("🔍 Start Dailies Team Trials Test: ❌")
        expect(banner).not.toContain("Start Template Matching Test")
    })

    it("leaves the Dailies debug tests out of the career banner", () => {
        const banner = buildSettingsBanner(makeStubSettings())
        expect(banner).toContain("Start Template Matching Test")
        expect(banner).not.toContain("Start Dailies")
    })

    it("names the career run mode", () => {
        const banner = buildSettingsBanner(withSlices({ dailies: DAILIES }))
        expect(banner).toContain("🗓️ Run Mode: Career")
        expect(banner).not.toContain("---------- Dailies Options ----------")
    })

    it("includes the three keys added during the audit", () => {
        const banner = buildSettingsBanner(makeStubSettings())
        expect(banner).toMatch(/Classic Year Milestone: .+%/)
        expect(banner).toMatch(/Senior Year Milestone: .+%/)
        expect(banner).toMatch(/Complete Career on Failure:/)
    })

    it("does not include the legacy asterisk divider line", () => {
        expect(buildSettingsBanner(makeStubSettings())).not.toMatch(/\*{10,}/)
    })

    it("includes the major section headers", () => {
        const banner = buildSettingsBanner(makeStubSettings())
        expect(banner).toContain("---------- Training Event Options ----------")
        expect(banner).toContain("---------- Training Options ----------")
        expect(banner).toContain("---------- Racing Options ----------")
        expect(banner).toContain("---------- Smart Race Solver Options ----------")
        expect(banner).toContain("---------- Skill Options ----------")
        expect(banner).toContain("---------- Scenario Overrides ----------")
        expect(banner).toContain("---------- Misc Options ----------")
        expect(banner).toContain("---------- Debug Options ----------")
        expect(banner).toContain("---------- Discord Options ----------")
    })

    it("includes the overlay style and size", () => {
        const banner = buildSettingsBanner(makeStubSettings())
        expect(banner).toContain("🕹️ Overlay: Tray (")
    })

    it("includes the disable schedule re-plan on race loss line", () => {
        const banner = buildSettingsBanner(makeStubSettings())
        expect(banner).toContain("Disable Schedule Re-Plan Upon Race Loss")
    })
})
