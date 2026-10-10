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

describe("buildSettingsBanner", () => {
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
