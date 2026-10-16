import { useCallback, useState } from "react"
import { useFocusEffect } from "@react-navigation/native"
import { INTERNAL_STORAGE_FOLDER, PickedFolder, storageBridge } from "../lib/storageBridge"

/** Whether the folder that logs and zips are written to is one the user chose and the app can still write to. */
export type StorageFolderStatus = "loading" | "ok" | "default" | "inaccessible"

/** Help text for the Storage Folder setting, shown in the row and in settings search. */
export const STORAGE_FOLDER_HELP = "Where logs and debug zips are saved. Tap to pick a different folder."

/** Warning shown while the storage folder is not usable, keyed by status. */
export const STORAGE_FOLDER_WARNING: Record<Exclude<StorageFolderStatus, "ok">, string> = {
    loading: STORAGE_FOLDER_HELP,
    default: "No storage folder is chosen, so logs and zips go to a hidden app folder. Tap to pick one.",
    inaccessible: "The chosen storage folder is no longer writable, so logs and zips go to a hidden app folder. Tap to pick it again.",
}

/** Shape returned by `useStorageFolder`. */
interface StorageFolder {
    /** The saved folder, or the internal-storage sentinel when none is saved. Null until the first read finishes. */
    folder: PickedFolder | null
    /** Health of the saved folder. `default` means none is chosen and `inaccessible` means the saved one can no longer be written. */
    status: StorageFolderStatus
    /** Opens the system folder picker and refreshes on success. Resolves true when a folder was picked. */
    pick: () => Promise<boolean>
}

/**
 * Reads the saved storage folder each time the screen gains focus, so a change made elsewhere shows up without a restart.
 * @returns The saved folder, its status and a picker.
 */
export const useStorageFolder = (): StorageFolder => {
    const [folder, setFolder] = useState<PickedFolder | null>(null)
    const [status, setStatus] = useState<StorageFolderStatus>("loading")

    const refresh = useCallback(async () => {
        try {
            const current = await storageBridge.getCurrentFolder()
            if (!current || current.uri === "") {
                setFolder(INTERNAL_STORAGE_FOLDER)
                setStatus("default")
                return
            }
            setFolder(current)
            setStatus((await storageBridge.validateAccess()) ? "ok" : "inaccessible")
        } catch (e) {
            console.warn("[useStorageFolder] Could not read the storage folder", e)
        }
    }, [])

    useFocusEffect(
        useCallback(() => {
            refresh()
        }, [refresh])
    )

    const pick = useCallback(async () => {
        try {
            if ((await storageBridge.pickFolder()) == null) return false
            await refresh()
            return true
        } catch (e) {
            console.warn("[useStorageFolder] Folder picker failed", e)
            return false
        }
    }, [refresh])

    return { folder, status, pick }
}
