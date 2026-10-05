import { NativeModules } from "react-native"
import { checkForAppUpdate } from "./appUpdate"

jest.mock("react-native", () => ({ NativeModules: { StartModule: { checkForAppUpdate: jest.fn() } } }))

const nativeCheck = NativeModules.StartModule.checkForAppUpdate as jest.Mock

test("reports an update when the native check showed its dialog", async () => {
    nativeCheck.mockResolvedValueOnce({ status: "available", version: "5.8.9" })
    expect(await checkForAppUpdate()).toEqual({ status: "available" })
})

test("reports up to date when the latest release is not newer", async () => {
    nativeCheck.mockResolvedValueOnce({ status: "upToDate", version: "5.8.8" })
    expect(await checkForAppUpdate()).toEqual({ status: "upToDate" })
})

test("reports failed with the reason when GitHub cannot be reached", async () => {
    nativeCheck.mockRejectedValueOnce(new Error("GitHub returned HTTP 403."))
    expect(await checkForAppUpdate()).toEqual({ status: "failed", message: "GitHub returned HTTP 403." })
})

test("reports held with the version when the bot is running", async () => {
    nativeCheck.mockResolvedValueOnce({ status: "held", version: "5.8.9" })
    expect(await checkForAppUpdate()).toEqual({ status: "held", version: "5.8.9" })
})
