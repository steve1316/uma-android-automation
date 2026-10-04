import React, { useCallback, useEffect, useRef, useState } from "react"
import { DeviceEventEmitter } from "react-native"
import * as Application from "expo-application"
import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle,
} from "../ui/alert-dialog"
import { Text } from "../ui/text"
import { DataVersion, getActiveDataVersion } from "../../data/live/readDownloaded"
import { applyDataUpdate, checkForDataUpdate, DATA_UPDATE_CHECK_EVENT } from "../../lib/dataUpdate"
import { logErrorWithTimestamp, logWithTimestamp } from "../../lib/logger"

/** What the dialog is currently showing. */
type Phase =
    | { kind: "hidden" }
    | { kind: "checking" }
    | { kind: "available"; remote: DataVersion }
    | { kind: "downloading"; remote: DataVersion }
    | { kind: "done"; remote: DataVersion }
    | { kind: "message"; title: string; body: string }

/**
 * Checks GitHub for newer game data on launch and when the drawer button asks, and offers to download it.
 * @returns The dialog, or nothing while hidden.
 */
export default function DataUpdateDialog() {
    const [phase, setPhase] = useState<Phase>({ kind: "hidden" })
    // Set while a download runs, since the apply step shares one staging folder.
    const busy = useRef<boolean>(false)

    /**
     * Runs one update check and shows the result. Ignored while a download is running.
     * @param manual True when the user asked for the check, so non-update results are shown too.
     */
    const runCheck = useCallback(async (manual: boolean) => {
        if (busy.current) return
        if (manual) setPhase({ kind: "checking" })
        const result = await checkForDataUpdate(Application.nativeApplicationVersion || "0.0.0")
        if (busy.current) return
        if (result.status === "available") {
            logWithTimestamp(`[DataUpdate] Newer game data available: ${result.remote.label}`)
            setPhase({ kind: "available", remote: result.remote })
        } else if (!manual) {
            // The launch check stays silent unless there is something to download.
        } else if (result.status === "upToDate") {
            setPhase({ kind: "message", title: "Game Data Up to Date", body: `You have the latest game data (up to ${getActiveDataVersion().label}).` })
        } else if (result.status === "appTooOld") {
            setPhase({ kind: "message", title: "App Update Needed", body: `The newest game data needs app version ${result.minAppVersion} or newer.` })
        } else {
            setPhase({ kind: "message", title: "Could Not Check for Data Updates", body: result.message })
        }
    }, [])

    useEffect(() => {
        runCheck(false)
        const subscription = DeviceEventEmitter.addListener(DATA_UPDATE_CHECK_EVENT, () => runCheck(true))
        return () => subscription.remove()
    }, [runCheck])

    /**
     * Downloads and applies the given game data version.
     * @param remote The version to download.
     */
    const startUpdate = async (remote: DataVersion) => {
        busy.current = true
        setPhase({ kind: "downloading", remote })
        try {
            await applyDataUpdate(remote)
            logWithTimestamp(`[DataUpdate] Downloaded game data up to ${remote.label}`)
            setPhase({ kind: "done", remote })
        } catch (error) {
            logErrorWithTimestamp("[DataUpdate] Game data update failed:", error)
            setPhase({ kind: "message", title: "Data Update Failed", body: error instanceof Error ? error.message : String(error) })
        } finally {
            busy.current = false
        }
    }

    const close = () => setPhase({ kind: "hidden" })
    if (phase.kind === "hidden") return null
    // Checking and downloading finish on their own, so the dialog cannot be dismissed or answered meanwhile.
    const working = phase.kind === "checking" || phase.kind === "downloading"

    return (
        <AlertDialog open onOpenChange={(open) => !open && !working && close()}>
            <AlertDialogContent onDismiss={working ? undefined : close}>
                <AlertDialogHeader>
                    <AlertDialogTitle>
                        {phase.kind === "message"
                            ? phase.title
                            : phase.kind === "checking"
                              ? "Checking for Game Data Updates"
                              : phase.kind === "done"
                                ? "Game Data Downloaded"
                                : "Game Data Update"}
                    </AlertDialogTitle>
                    <AlertDialogDescription>
                        {phase.kind === "checking" && "Asking GitHub for newer game data..."}
                        {phase.kind === "available" && `New game data is available, up to ${phase.remote.label}. Download it now?`}
                        {phase.kind === "downloading" && `Downloading game data up to ${phase.remote.label}...`}
                        {phase.kind === "done" && `Game data up to ${phase.remote.label} is ready. Close and reopen the app to start using it.`}
                        {phase.kind === "message" && phase.body}
                    </AlertDialogDescription>
                </AlertDialogHeader>
                {!working && (
                    <AlertDialogFooter>
                        {phase.kind === "available" ? (
                            <>
                                <AlertDialogCancel onPress={close}>
                                    <Text>Cancel</Text>
                                </AlertDialogCancel>
                                <AlertDialogAction onPress={() => startUpdate(phase.remote)}>
                                    <Text>Update</Text>
                                </AlertDialogAction>
                            </>
                        ) : (
                            <AlertDialogAction onPress={close}>
                                <Text>OK</Text>
                            </AlertDialogAction>
                        )}
                    </AlertDialogFooter>
                )}
            </AlertDialogContent>
        </AlertDialog>
    )
}
