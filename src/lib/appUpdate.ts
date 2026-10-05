import { NativeModules } from "react-native"

/** Result of asking GitHub whether a newer app release exists. */
export type AppUpdateCheck = { status: "available" } | { status: "held"; version: string } | { status: "upToDate" } | { status: "failed"; message: string }

/**
 * Checks GitHub for a newer app release. When one exists, the native side shows its own update dialog, unless the bot is running, in which
 * case the update is held back because installing it ends the app.
 * @returns What the check found. Never throws.
 */
export async function checkForAppUpdate(): Promise<AppUpdateCheck> {
    try {
        const result: { status: string; version: string } = await NativeModules.StartModule.checkForAppUpdate()
        if (result.status === "available") return { status: "available" }
        if (result.status === "held") return { status: "held", version: result.version }
        return { status: "upToDate" }
    } catch (error) {
        return { status: "failed", message: error instanceof Error ? error.message : String(error) }
    }
}
