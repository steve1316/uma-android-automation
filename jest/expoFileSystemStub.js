// In-memory stand-in for expo-file-system, whose ESM build Jest cannot parse. It starts empty, so the bundled data is used unless a test seeds
// `mockFileSystem`. The store lives on globalThis so module reloads in a test share it.
const mockFileSystem = (globalThis.__expoFileSystemMock ??= { files: {}, remote: {}, md5: {} })

/**
 * Joins path parts the way the real module builds uris.
 * @param {Array<string | { uri: string }>} parts Strings, files or directories.
 * @returns {string} The joined uri.
 */
const join = (parts) => parts.map((p) => (typeof p === "string" ? p : p.uri)).join("/")

/**
 * Returns the last path segment of a uri.
 * @param {string} uri The uri.
 * @returns {string} The file name.
 */
const baseName = (uri) => uri.split("/").pop()

class Directory {
    constructor(...parts) {
        this.uri = join(parts)
    }

    get exists() {
        return Object.keys(mockFileSystem.files).some((k) => k === this.uri + "/" || k.startsWith(this.uri + "/"))
    }

    create() {
        mockFileSystem.files[this.uri + "/"] = ""
    }

    delete() {
        for (const k of Object.keys(mockFileSystem.files)) if (k === this.uri + "/" || k.startsWith(this.uri + "/")) delete mockFileSystem.files[k]
    }
}

class File {
    constructor(...parts) {
        this.uri = join(parts)
    }

    get exists() {
        return this.uri in mockFileSystem.files
    }

    get md5() {
        return this.exists ? (mockFileSystem.md5[baseName(this.uri)] ?? null) : null
    }

    textSync() {
        return mockFileSystem.files[this.uri]
    }

    create() {
        mockFileSystem.files[this.uri] = ""
    }

    write(content) {
        mockFileSystem.files[this.uri] = content
    }

    delete() {
        delete mockFileSystem.files[this.uri]
    }

    async move(dest) {
        mockFileSystem.files[dest.uri] = mockFileSystem.files[this.uri]
        delete mockFileSystem.files[this.uri]
        this.uri = dest.uri
    }

    static async downloadFileAsync(url, dest) {
        const name = baseName(url.split("?")[0])
        if (!(name in mockFileSystem.remote)) throw new Error(`404 ${name}`)
        mockFileSystem.files[dest.uri] = mockFileSystem.remote[name]
        return dest
    }
}

/** Empties every store between tests. */
const resetMockFileSystem = () => {
    for (const store of Object.values(mockFileSystem)) for (const k of Object.keys(store)) delete store[k]
}

module.exports = { Directory, File, Paths: { document: { uri: "doc" } }, mockFileSystem, resetMockFileSystem }
