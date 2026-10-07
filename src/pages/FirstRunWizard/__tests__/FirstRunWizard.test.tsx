import React from "react"
import { act, fireEvent, render, waitFor } from "@testing-library/react-native"
import { BackHandler } from "react-native"

jest.mock("../../../lib/storageBridge", () => ({
    INTERNAL_STORAGE_FOLDER: { uri: "", name: "App default (internal storage)" },
    storageBridge: {
        getCurrentFolder: jest.fn(),
        pickFolder: jest.fn(),
        clearFolder: jest.fn(),
        validateAccess: jest.fn(),
        migrateLegacyFiles: jest.fn(),
    },
}))

jest.mock("../../../hooks/useLegacyFileScan", () => ({
    useLegacyFileScan: jest.fn(() => ({ scanning: false, counts: null, hasLegacyFiles: false })),
}))

let mockPermissionsChange: ((r: { accessibility: boolean; overlay: boolean; battery: boolean }) => void) | null = null
jest.mock("../../../components/SystemChecksWizard", () => ({
    __esModule: true,
    default: ({ onPermissionsChange }: { onPermissionsChange?: (r: { accessibility: boolean; overlay: boolean; battery: boolean }) => void }) => {
        mockPermissionsChange = onPermissionsChange ?? null
        const { View } = require("react-native")
        return <View testID="system-checks-mock" />
    },
}))

jest.mock("@react-native-vector-icons/ionicons", () => "Ionicons")

jest.mock("../../../context/ThemeContext", () => ({
    useTheme: () => ({
        colors: {
            background: "#0e1116",
            text: "#e8eaed",
            textMuted: "#a8aebb",
            surface: "#161a20",
            borderHair: "#232831",
            brand: "#5b9dff",
            primary: "#5b9dff",
            ripple: "#2a2f38",
            success: "#7bd590",
            successSubtle: "rgba(123, 213, 144, 0.15)",
            error: "#e07b7b",
            warning: "#e0a067",
            warningSubtle: "rgba(224, 160, 103, 0.1)",
            warningText: "#e0a067",
        },
    }),
}))

jest.mock("../../../components/CustomButton", () => {
    const { Pressable, Text } = require("react-native")
    return {
        __esModule: true,
        default: ({ onPress, children, disabled }: { onPress?: () => void; children: React.ReactNode; disabled?: boolean }) => (
            <Pressable onPress={disabled ? undefined : onPress} disabled={disabled}>
                <Text>{children}</Text>
            </Pressable>
        ),
    }
})

import FirstRunWizard from "../index"
import { storageBridge } from "../../../lib/storageBridge"
import { useLegacyFileScan } from "../../../hooks/useLegacyFileScan"

const mockStorageBridge = storageBridge as jest.Mocked<typeof storageBridge>
const mockUseLegacyFileScan = useLegacyFileScan as jest.MockedFunction<typeof useLegacyFileScan>

const grantAll = () => {
    act(() => {
        mockPermissionsChange?.({ accessibility: true, overlay: true, battery: true })
    })
}

describe("FirstRunWizard", () => {
    beforeEach(() => {
        mockStorageBridge.getCurrentFolder.mockReset()
        mockStorageBridge.pickFolder.mockReset()
        mockStorageBridge.clearFolder.mockReset()
        mockStorageBridge.validateAccess.mockReset()
        mockStorageBridge.migrateLegacyFiles.mockReset()
        mockUseLegacyFileScan.mockReset()
        mockUseLegacyFileScan.mockReturnValue({ scanning: false, counts: null, hasLegacyFiles: false })
        mockPermissionsChange = null
    })

    it("mounts all three cards when legacy files are present", async () => {
        mockUseLegacyFileScan.mockReturnValue({ scanning: false, counts: { logs: 5, recordings: 2 }, hasLegacyFiles: true })
        mockStorageBridge.getCurrentFolder.mockResolvedValue(null)
        const { findByText } = render(<FirstRunWizard onComplete={jest.fn()} />)
        expect(await findByText("STORAGE FOLDER")).toBeTruthy()
        expect(await findByText("MOVE YOUR EXISTING FILES?")).toBeTruthy()
        expect(await findByText("SYSTEM PERMISSIONS")).toBeTruthy()
    })

    it("hides the migration card when no legacy files", async () => {
        mockStorageBridge.getCurrentFolder.mockResolvedValue(null)
        const { findByText, queryByText } = render(<FirstRunWizard onComplete={jest.fn()} />)
        expect(await findByText("STORAGE FOLDER")).toBeTruthy()
        expect(queryByText("MOVE YOUR EXISTING FILES?")).toBeNull()
        expect(await findByText("SYSTEM PERMISSIONS")).toBeTruthy()
    })

    it("Finish does nothing until folder picked and permissions granted (no legacy files)", async () => {
        mockStorageBridge.getCurrentFolder.mockResolvedValueOnce(null).mockResolvedValueOnce({ uri: "content://test", name: "Test" })
        mockStorageBridge.pickFolder.mockResolvedValue("content://test")
        mockStorageBridge.validateAccess.mockResolvedValue(true)
        const onComplete = jest.fn().mockResolvedValue(undefined)
        const { findByText } = render(<FirstRunWizard onComplete={onComplete} />)

        const finish = await findByText("Finish")
        fireEvent.press(finish)
        await Promise.resolve()
        expect(mockStorageBridge.validateAccess).not.toHaveBeenCalled()

        fireEvent.press(await findByText("Pick a folder"))
        await waitFor(() => expect(mockStorageBridge.pickFolder).toHaveBeenCalled())
        grantAll()

        await waitFor(() => {
            fireEvent.press(finish)
            expect(mockStorageBridge.validateAccess).toHaveBeenCalled()
        })
    })

    it("Finish does nothing when legacy files exist and no migration choice made", async () => {
        mockUseLegacyFileScan.mockReturnValue({ scanning: false, counts: { logs: 5, recordings: 2 }, hasLegacyFiles: true })
        mockStorageBridge.getCurrentFolder.mockResolvedValue({ uri: "content://test", name: "Test" })
        mockStorageBridge.validateAccess.mockResolvedValue(true)
        const onComplete = jest.fn().mockResolvedValue(undefined)
        const { findByText } = render(<FirstRunWizard onComplete={onComplete} />)

        await waitFor(() => expect(mockStorageBridge.getCurrentFolder).toHaveBeenCalled())
        grantAll()

        const finish = await findByText("Finish")
        fireEvent.press(finish)
        await Promise.resolve()
        expect(mockStorageBridge.validateAccess).not.toHaveBeenCalled()

        fireEvent.press(await findByText("Leave them"))
        await waitFor(() => expect(findByText("Left at old location")).resolves.toBeTruthy())

        await waitFor(() => {
            fireEvent.press(finish)
            expect(mockStorageBridge.validateAccess).toHaveBeenCalled()
        })
    })

    it("Cancel exits the app", async () => {
        mockStorageBridge.getCurrentFolder.mockResolvedValue(null)
        const exitSpy = jest.spyOn(BackHandler, "exitApp").mockImplementation(() => true)
        const { findByText } = render(<FirstRunWizard onComplete={jest.fn()} />)
        fireEvent.press(await findByText("Cancel"))
        expect(exitSpy).toHaveBeenCalled()
        exitSpy.mockRestore()
    })

    it("Hardware Back also invokes Cancel", async () => {
        mockStorageBridge.getCurrentFolder.mockResolvedValue(null)
        const exitSpy = jest.spyOn(BackHandler, "exitApp").mockImplementation(() => true)
        let backHandler: () => boolean = () => false
        const addEventListenerSpy = jest.spyOn(BackHandler, "addEventListener").mockImplementation(((event: string, handler: () => boolean) => {
            if (event === "hardwareBackPress") backHandler = handler
            return { remove: jest.fn() }
        }) as never)
        render(<FirstRunWizard onComplete={jest.fn()} />)
        expect(backHandler()).toBe(true)
        expect(exitSpy).toHaveBeenCalled()
        exitSpy.mockRestore()
        addEventListenerSpy.mockRestore()
    })

    it("Finish re-validates folder access and rolls back on failure", async () => {
        mockStorageBridge.getCurrentFolder.mockResolvedValue({ uri: "content://test", name: "Test" })
        mockStorageBridge.validateAccess.mockResolvedValue(false)
        const onComplete = jest.fn()
        const { findByText, queryByText } = render(<FirstRunWizard onComplete={onComplete} />)

        await waitFor(() => expect(queryByText("Pick a folder")).toBeNull())
        grantAll()

        const finish = await findByText("Finish")
        await waitFor(() => {
            fireEvent.press(finish)
            expect(mockStorageBridge.validateAccess).toHaveBeenCalled()
        })

        expect(onComplete).not.toHaveBeenCalled()
        expect(await findByText(/no longer accessible/i)).toBeTruthy()
        expect(await findByText("Pick a folder")).toBeTruthy()
    })

    it("Migration choice collapses the card to a confirmation row", async () => {
        mockUseLegacyFileScan.mockReturnValue({ scanning: false, counts: { logs: 5, recordings: 2 }, hasLegacyFiles: true })
        mockStorageBridge.getCurrentFolder.mockResolvedValue({ uri: "content://test", name: "Test" })
        const { findByText, queryByText } = render(<FirstRunWizard onComplete={jest.fn()} />)
        fireEvent.press(await findByText("Leave them"))
        await waitFor(() => expect(queryByText("Move them")).toBeNull())
        expect(queryByText("Delete them")).toBeNull()
        expect(await findByText("Left at old location")).toBeTruthy()
    })

    it("onComplete is awaited when Finish succeeds", async () => {
        mockStorageBridge.getCurrentFolder.mockResolvedValue({ uri: "content://test", name: "Test" })
        mockStorageBridge.validateAccess.mockResolvedValue(true)
        const onComplete = jest.fn().mockResolvedValue(undefined)
        const { findByText } = render(<FirstRunWizard onComplete={onComplete} />)
        await waitFor(() => expect(mockStorageBridge.getCurrentFolder).toHaveBeenCalled())
        grantAll()
        const finish = await findByText("Finish")
        await waitFor(() => {
            fireEvent.press(finish)
            expect(onComplete).toHaveBeenCalled()
        })
    })

    it("onComplete failure surfaces saveError and Finish remains tappable", async () => {
        mockStorageBridge.getCurrentFolder.mockResolvedValue({ uri: "content://test", name: "Test" })
        mockStorageBridge.validateAccess.mockResolvedValue(true)
        const onComplete = jest.fn().mockRejectedValue(new Error("save failed"))
        const { findByText } = render(<FirstRunWizard onComplete={onComplete} />)
        await waitFor(() => expect(mockStorageBridge.getCurrentFolder).toHaveBeenCalled())
        grantAll()
        const finish = await findByText("Finish")
        await waitFor(() => {
            fireEvent.press(finish)
            expect(onComplete).toHaveBeenCalled()
        })
        expect(await findByText(/Couldn't save your setup/i)).toBeTruthy()
        const callsBefore = onComplete.mock.calls.length
        fireEvent.press(finish)
        await waitFor(() => expect(onComplete.mock.calls.length).toBeGreaterThan(callsBefore))
    })

    it("surfaces a partial-move banner when migrate fails and lets the user continue", async () => {
        mockUseLegacyFileScan.mockReturnValue({ scanning: false, counts: { logs: 5, recordings: 2 }, hasLegacyFiles: true })
        mockStorageBridge.getCurrentFolder.mockResolvedValue({ uri: "content://test", name: "Test" })
        mockStorageBridge.migrateLegacyFiles.mockResolvedValue({ movedLogs: 1, movedRecordings: 0, error: "OUT_OF_SPACE", remaining: 6 })
        const { findByText } = render(<FirstRunWizard onComplete={jest.fn()} />)

        // Moving needs the picked folder, so wait for it to load first.
        expect(await findByText("Test")).toBeTruthy()
        fireEvent.press(await findByText("Move them"))
        await waitFor(() => expect(mockStorageBridge.migrateLegacyFiles).toHaveBeenCalledWith("move"))
        expect(await findByText(/Moved 1 of 7 files\. Out of space on your new folder\./i)).toBeTruthy()

        fireEvent.press(await findByText("Continue with partial move"))
        expect(await findByText("Left at old location")).toBeTruthy()
    })

    it("does not move files before a folder is picked", async () => {
        mockUseLegacyFileScan.mockReturnValue({ scanning: false, counts: { logs: 5, recordings: 2 }, hasLegacyFiles: true })
        mockStorageBridge.getCurrentFolder.mockResolvedValue(null)
        const { findByText, queryByText } = render(<FirstRunWizard onComplete={jest.fn()} />)

        expect(await findByText("Pick a folder above to move them")).toBeTruthy()
        fireEvent.press(await findByText("Move them"))
        await Promise.resolve()

        expect(mockStorageBridge.migrateLegacyFiles).not.toHaveBeenCalled()
        expect(queryByText("Moved to new folder")).toBeNull()
    })

    it("does not move files into app default storage", async () => {
        mockUseLegacyFileScan.mockReturnValue({ scanning: false, counts: { logs: 5, recordings: 2 }, hasLegacyFiles: true })
        mockStorageBridge.getCurrentFolder.mockResolvedValue(null)
        mockStorageBridge.clearFolder.mockResolvedValue(true)
        const { findByText, queryByText } = render(<FirstRunWizard onComplete={jest.fn()} />)

        fireEvent.press(await findByText("Use app default storage instead"))
        expect(await findByText("App default (internal storage)")).toBeTruthy()
        fireEvent.press(await findByText("Move them"))
        await Promise.resolve()

        expect(mockStorageBridge.migrateLegacyFiles).not.toHaveBeenCalled()
        expect(queryByText("Moved to new folder")).toBeNull()
    })

    it("explains a refused move when the library reports no destination", async () => {
        mockUseLegacyFileScan.mockReturnValue({ scanning: false, counts: { logs: 5, recordings: 2 }, hasLegacyFiles: true })
        mockStorageBridge.getCurrentFolder.mockResolvedValue({ uri: "content://test", name: "Test" })
        mockStorageBridge.migrateLegacyFiles.mockResolvedValue({ movedLogs: 0, movedRecordings: 0, error: "NO_DESTINATION", remaining: 7 })
        const { findByText, queryByText } = render(<FirstRunWizard onComplete={jest.fn()} />)

        expect(await findByText("Test")).toBeTruthy()
        fireEvent.press(await findByText("Move them"))
        await waitFor(() => expect(mockStorageBridge.migrateLegacyFiles).toHaveBeenCalledWith("move"))

        expect(await findByText(/Nothing was moved\. The picked folder is no longer accessible\./i)).toBeTruthy()
        expect(queryByText("Moved to new folder")).toBeNull()
    })

    it("shows 0 OF 2 on initial mount when no legacy files", async () => {
        mockStorageBridge.getCurrentFolder.mockResolvedValue(null)
        const { findByText } = render(<FirstRunWizard onComplete={jest.fn()} />)
        expect(await findByText("0 OF 2")).toBeTruthy()
    })

    it("shows 2 OF 2 once folder picked and all permissions granted (no legacy files)", async () => {
        mockStorageBridge.getCurrentFolder.mockResolvedValue({ uri: "content://test", name: "Test" })
        const { findByText } = render(<FirstRunWizard onComplete={jest.fn()} />)
        await waitFor(() => expect(mockStorageBridge.getCurrentFolder).toHaveBeenCalled())
        grantAll()
        expect(await findByText("2 OF 2")).toBeTruthy()
    })

    it("total switches to 3 OF 3 when legacy files are present and all three are complete", async () => {
        mockUseLegacyFileScan.mockReturnValue({ scanning: false, counts: { logs: 5, recordings: 2 }, hasLegacyFiles: true })
        mockStorageBridge.getCurrentFolder.mockResolvedValue({ uri: "content://test", name: "Test" })
        const { findByText } = render(<FirstRunWizard onComplete={jest.fn()} />)
        await waitFor(() => expect(mockStorageBridge.getCurrentFolder).toHaveBeenCalled())
        fireEvent.press(await findByText("Leave them"))
        await waitFor(() => expect(findByText("Left at old location")).resolves.toBeTruthy())
        grantAll()
        expect(await findByText("3 OF 3")).toBeTruthy()
    })
})
