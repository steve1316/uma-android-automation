// Node-project stand-in for expo-file-system, whose ESM build Jest cannot parse. Nothing is ever downloaded, so the bundled data is used.
class Directory {
    constructor(...parts) {
        this.uri = parts.map((p) => (typeof p === "string" ? p : p.uri)).join("/")
    }
}

class File {
    constructor(...parts) {
        this.uri = parts.map((p) => (typeof p === "string" ? p : p.uri)).join("/")
        this.exists = false
    }

    textSync() {
        return ""
    }
}

module.exports = { Directory, File, Paths: { document: { uri: "document" } } }
