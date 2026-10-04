// In-memory stand-in for expo-file-system. Downloads copy from mockRemote. md5 comes from mockMd5 keyed by file name.
const mockFiles: Record<string, string> = {}
const mockRemote: Record<string, string> = {}
const mockMd5: Record<string, string> = {}

jest.mock("expo-file-system", () => {
    const join = (parts: any[]) => parts.map((p) => (typeof p === "string" ? p : p.uri)).join("/")
    const name = (uri: string) => uri.split("/").pop()!
    class Directory {
        uri: string
        constructor(...parts: any[]) {
            this.uri = join(parts)
        }
        get exists() {
            return Object.keys(mockFiles).some((k) => k.startsWith(this.uri + "/")) || (this.uri + "/") in mockFiles
        }
        create() {
            mockFiles[this.uri + "/"] = ""
        }
        delete() {
            for (const k of Object.keys(mockFiles)) if (k === this.uri + "/" || k.startsWith(this.uri + "/")) delete mockFiles[k]
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
        get md5() {
            return this.exists ? (mockMd5[name(this.uri)] ?? null) : null
        }
        textSync() {
            return mockFiles[this.uri]
        }
        create() {
            mockFiles[this.uri] = ""
        }
        write(content: string) {
            mockFiles[this.uri] = content
        }
        delete() {
            delete mockFiles[this.uri]
        }
        async move(dest: File) {
            mockFiles[dest.uri] = mockFiles[this.uri]
            delete mockFiles[this.uri]
            this.uri = dest.uri
        }
        static async downloadFileAsync(url: string, dest: File) {
            const file = name(url.split("?")[0])
            if (!(file in mockRemote)) throw new Error(`404 ${file}`)
            mockFiles[dest.uri] = mockRemote[file]
            return dest
        }
    }
    return { Directory, File, Paths: { document: { uri: "doc" } } }
})

import { applyDataUpdate, CHECK_TIMEOUT_MS, checkForDataUpdate, compareAppVersions, DATA_FILES } from "./dataUpdate"

const bundled = require("../data/data_version.json")
// Own `files` object so the tests never mutate the cached bundled JSON that readDownloaded also imports.
const remote = { ...bundled, files: {} as Record<string, string>, version: "2099-01-01T00:00:00Z", label: "Future 2099-01-01", minAppVersion: "5.8.8" }

beforeEach(() => {
    for (const store of [mockFiles, mockRemote, mockMd5]) for (const k of Object.keys(store)) delete store[k]
    for (const f of DATA_FILES) {
        mockRemote[f] = JSON.stringify({ file: f })
        mockMd5[f] = `md5-${f}`
        remote.files[f] = `md5-${f}`
    }
    ;(global as any).fetch = jest.fn(async () => ({ ok: true, status: 200, json: async () => remote }))
})

test("compareAppVersions orders dotted versions numerically", () => {
    expect(compareAppVersions("5.8.10", "5.8.9")).toBe(1)
    expect(compareAppVersions("5.8.8", "5.8.8")).toBe(0)
    expect(compareAppVersions("5.8", "5.8.1")).toBe(-1)
})

test("reports an update when the remote stamp is newer", async () => {
    expect(await checkForDataUpdate("5.8.8")).toEqual({ status: "available", remote })
})

test("reports up to date when the remote stamp is not newer", async () => {
    ;(global as any).fetch = jest.fn(async () => ({ ok: true, status: 200, json: async () => bundled }))
    expect(await checkForDataUpdate("5.8.8")).toEqual({ status: "upToDate" })
})

test("reports appTooOld when the data needs a newer app", async () => {
    expect(await checkForDataUpdate("5.8.7")).toEqual({ status: "appTooOld", minAppVersion: "5.8.8" })
})

test("reports failed when offline or GitHub errors", async () => {
    ;(global as any).fetch = jest.fn(async () => {
        throw new Error("Network request failed")
    })
    expect((await checkForDataUpdate("5.8.8")).status).toBe("failed")
    ;(global as any).fetch = jest.fn(async () => ({ ok: false, status: 404, json: async () => ({}) }))
    expect(await checkForDataUpdate("5.8.8")).toEqual({ status: "failed", message: "GitHub returned HTTP 404." })
})

test("reports failed when the check hangs past the timeout", async () => {
    jest.useFakeTimers()
    try {
        // A dropped connection never answers, so the request only ends when it is aborted.
        ;(global as any).fetch = jest.fn(
            (_url: string, init: RequestInit) => new Promise((_resolve, reject) => init.signal!.addEventListener("abort", () => reject(new Error("Aborted")))),
        )
        const pending = checkForDataUpdate("5.8.8")
        jest.advanceTimersByTime(CHECK_TIMEOUT_MS)
        expect(await pending).toEqual({ status: "failed", message: "GitHub did not respond in time. Check your connection and try again." })
    } finally {
        jest.useRealTimers()
    }
})

test("applies every file and writes the version stamp last", async () => {
    await applyDataUpdate(remote)
    for (const f of DATA_FILES) expect(mockFiles[`doc/game-data/${f}`]).toBe(JSON.stringify({ file: f }))
    expect(JSON.parse(mockFiles["doc/game-data/data_version.json"])).toEqual(remote)
    expect(Object.keys(mockFiles).some((k) => k.startsWith("doc/game-data-staging"))).toBe(false)
})

test("a checksum mismatch aborts and leaves the old data in place", async () => {
    mockFiles["doc/game-data/data_version.json"] = "OLD"
    mockFiles["doc/game-data/supports.json"] = "OLD"
    mockMd5["supports.json"] = "stale-cdn-copy"
    await expect(applyDataUpdate(remote)).rejects.toThrow(/supports\.json/)
    expect(mockFiles["doc/game-data/data_version.json"]).toBe("OLD")
    expect(mockFiles["doc/game-data/supports.json"]).toBe("OLD")
    expect(Object.keys(mockFiles).some((k) => k.startsWith("doc/game-data-staging"))).toBe(false)
})
