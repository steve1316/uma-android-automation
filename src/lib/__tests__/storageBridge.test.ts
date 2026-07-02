import { NativeModules } from "react-native"
import { storageBridge, folderDocumentUriFromTreeUri } from "../storageBridge"

jest.mock("react-native", () => ({
    NativeModules: {
        StorageBridgeModule: {
            pickFolder: jest.fn(),
            getCurrentFolder: jest.fn(),
            clearFolder: jest.fn(),
            validateAccess: jest.fn(),
            scanLegacyFiles: jest.fn(),
            migrateLegacyFiles: jest.fn(),
        },
    },
}))

describe("storageBridge", () => {
    it("forwards pickFolder to the native module", async () => {
        ;(NativeModules.StorageBridgeModule.pickFolder as jest.Mock).mockResolvedValue("content://uri")
        const result = await storageBridge.pickFolder()
        expect(result).toBe("content://uri")
    })

    it("forwards migrateLegacyFiles with the mode argument", async () => {
        ;(NativeModules.StorageBridgeModule.migrateLegacyFiles as jest.Mock).mockResolvedValue({ movedLogs: 5, movedRecordings: 2 })
        const result = await storageBridge.migrateLegacyFiles("move")
        expect(NativeModules.StorageBridgeModule.migrateLegacyFiles).toHaveBeenCalledWith("move")
        expect(result).toEqual({ movedLogs: 5, movedRecordings: 2 })
    })
})

describe("folderDocumentUriFromTreeUri", () => {
    it("appends the encoded tree document id as the document segment", () => {
        expect(folderDocumentUriFromTreeUri("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FUmaLogs")).toBe(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FUmaLogs/document/primary%3ADownload%2FUmaLogs"
        )
    })

    it("does not double-encode an already-encoded document id", () => {
        const result = folderDocumentUriFromTreeUri("content://x/tree/primary%3ADownload")
        expect(result).toContain("/document/primary%3ADownload")
        expect(result).not.toContain("%253A")
    })

    it("returns the input unchanged when it is not a tree uri", () => {
        expect(folderDocumentUriFromTreeUri("content://x/document/abc")).toBe("content://x/document/abc")
    })
})
