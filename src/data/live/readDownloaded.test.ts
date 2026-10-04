// In-memory stand-in for expo-file-system, keyed by uri.
const mockFiles: Record<string, string> = {}

jest.mock("expo-file-system", () => {
    const join = (parts: any[]) => parts.map((p) => (typeof p === "string" ? p : p.uri)).join("/")
    class Directory {
        uri: string
        constructor(...parts: any[]) {
            this.uri = join(parts)
        }
    }
    class File {
        uri: string
        constructor(...parts: any[]) {
            this.uri = join(parts)
        }
        get exists() {
            return this.uri in mockFiles
        }
        textSync() {
            return mockFiles[this.uri]
        }
    }
    return { Directory, File, Paths: { document: { uri: "doc" } } }
})

/**
 * Loads a fresh copy of the module so its memoized active version is reset.
 * @returns The module under test.
 */
const load = () => {
    let mod: typeof import("./readDownloaded") | undefined
    jest.isolateModules(() => {
        mod = require("./readDownloaded")
    })
    return mod!
}

const bundled = require("../data_version.json")

beforeEach(() => {
    for (const key of Object.keys(mockFiles)) delete mockFiles[key]
})

test("uses the bundled data when nothing was downloaded", () => {
    const mod = load()
    expect(mod.readDownloaded("supports.json")).toBeNull()
    expect(mod.getActiveDataVersion().version).toBe(bundled.version)
})

test("uses the downloaded data when its version is newer", () => {
    mockFiles["doc/game-data/data_version.json"] = JSON.stringify({ ...bundled, version: "2099-01-01T00:00:00Z", label: "Future 2099-01-01" })
    mockFiles["doc/game-data/supports.json"] = JSON.stringify({ "New Girl": { E: ["Speed +10"] } })
    const mod = load()
    expect(mod.readDownloaded("supports.json")).toEqual({ "New Girl": { E: ["Speed +10"] } })
    expect(mod.getActiveDataVersion().label).toBe("Future 2099-01-01")
})

test("ignores downloaded data older than the bundled copy", () => {
    mockFiles["doc/game-data/data_version.json"] = JSON.stringify({ ...bundled, version: "2000-01-01T00:00:00Z" })
    mockFiles["doc/game-data/supports.json"] = "{}"
    expect(load().readDownloaded("supports.json")).toBeNull()
})

test("ignores a half-written update that has no version file", () => {
    mockFiles["doc/game-data/supports.json"] = "{}"
    expect(load().readDownloaded("supports.json")).toBeNull()
})

test("falls back when a downloaded file is corrupt", () => {
    mockFiles["doc/game-data/data_version.json"] = JSON.stringify({ ...bundled, version: "2099-01-01T00:00:00Z" })
    mockFiles["doc/game-data/supports.json"] = "{not json"
    expect(load().readDownloaded("supports.json")).toBeNull()
})

test("the active version stays fixed after a later download in the same session", () => {
    const mod = load()
    expect(mod.getActiveDataVersion().version).toBe(bundled.version)
    mockFiles["doc/game-data/data_version.json"] = JSON.stringify({ ...bundled, version: "2099-01-01T00:00:00Z" })
    expect(mod.getActiveDataVersion().version).toBe(bundled.version)
    expect(mod.getInstalledDataVersion().version).toBe("2099-01-01T00:00:00Z")
})
