import { Directory, File, Paths } from "expo-file-system"
import { DataVersion, getGameDataDir, getInstalledDataVersion, VERSION_FILE_NAME } from "../data/live/readDownloaded"

/** Event the drawer button emits to run a manual data update check. */
export const DATA_UPDATE_CHECK_EVENT = "checkDataUpdate"

/** The data files an update replaces. races.json and scenarios.json only change with an APK. */
export const DATA_FILES = ["characters.json", "supports.json", "skills.json", "epithets.json", "characterPresets.json", "character_objectives.json"] as const

/** Where master's game data is served from. */
const DEFAULT_DATA_URL_BASE = "https://raw.githubusercontent.com/steve1316/uma-android-automation/refs/heads/master/src/data"

/** Folder that downloads land in before they are verified and moved into place. */
const STAGING_DIR_NAME = "game-data-staging"

/** How long the update check waits for GitHub before giving up, since a dropped connection would otherwise hang for minutes. */
export const CHECK_TIMEOUT_MS = 10_000

/** Result of asking GitHub whether newer game data exists. */
export type DataUpdateCheck =
    | { status: "available"; remote: DataVersion }
    | { status: "upToDate" }
    | { status: "appTooOld"; minAppVersion: string }
    | { status: "failed"; message: string }

/**
 * Returns the base URL the data files are downloaded from.
 * @returns `EXPO_PUBLIC_DATA_URL_BASE` when set at build time (for testing), else master on GitHub.
 */
export function getDataUrlBase(): string {
    return process.env.EXPO_PUBLIC_DATA_URL_BASE || DEFAULT_DATA_URL_BASE
}

/**
 * Compares two dotted app versions numerically.
 * @param a The first version, e.g. "5.8.10".
 * @param b The second version.
 * @returns 1 when `a` is newer, -1 when older, 0 when equal.
 */
export function compareAppVersions(a: string, b: string): number {
    const pa = a.split(".").map((p) => parseInt(p, 10) || 0)
    const pb = b.split(".").map((p) => parseInt(p, 10) || 0)
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
        const diff = (pa[i] ?? 0) - (pb[i] ?? 0)
        if (diff !== 0) return Math.sign(diff)
    }
    return 0
}

/**
 * Asks GitHub whether newer game data than what is on the device exists.
 * @param appVersion The installed app version, e.g. from `Application.nativeApplicationVersion`.
 * @returns What the check found. Never throws.
 */
export async function checkForDataUpdate(appVersion: string): Promise<DataUpdateCheck> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), CHECK_TIMEOUT_MS)
    try {
        const response = await fetch(`${getDataUrlBase()}/${VERSION_FILE_NAME}?t=${Date.now()}`, { signal: controller.signal })
        if (!response.ok) return { status: "failed", message: `GitHub returned HTTP ${response.status}.` }
        const remote = (await response.json()) as DataVersion
        if (remote.version <= getInstalledDataVersion().version) return { status: "upToDate" }
        if (compareAppVersions(appVersion, remote.minAppVersion) < 0) return { status: "appTooOld", minAppVersion: remote.minAppVersion }
        return { status: "available", remote }
    } catch (error) {
        if (controller.signal.aborted) return { status: "failed", message: "GitHub did not respond in time. Check your connection and try again." }
        return { status: "failed", message: error instanceof Error ? error.message : String(error) }
    } finally {
        clearTimeout(timer)
    }
}

/**
 * Downloads and verifies every data file, then swaps them in with the version stamp written last.
 * A failure at any point leaves the previous data in use.
 * @param remote The stamp returned by `checkForDataUpdate`.
 */
export async function applyDataUpdate(remote: DataVersion): Promise<void> {
    const staging = new Directory(Paths.document, STAGING_DIR_NAME)
    if (staging.exists) staging.delete()
    staging.create()
    try {
        for (const name of DATA_FILES) {
            const url = `${getDataUrlBase()}/${name}?v=${encodeURIComponent(remote.version)}`
            const file = await File.downloadFileAsync(url, new File(staging, name))
            if (!remote.files[name] || file.md5 !== remote.files[name]) {
                throw new Error(`${name} did not match its checksum. GitHub may still be serving an older copy, so try again in a few minutes.`)
            }
            JSON.parse(file.textSync())
        }
        const target = getGameDataDir()
        if (!target.exists) target.create()
        // Remove the stamp first so a crash mid-swap falls back to the bundled data instead of mixing versions.
        const stamp = new File(target, VERSION_FILE_NAME)
        if (stamp.exists) stamp.delete()
        for (const name of DATA_FILES) {
            const dest = new File(target, name)
            if (dest.exists) dest.delete()
            await new File(staging, name).move(dest)
        }
        stamp.create()
        stamp.write(JSON.stringify(remote))
    } finally {
        if (staging.exists) staging.delete()
    }
}
