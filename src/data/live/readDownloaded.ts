import { Directory, File, Paths } from "expo-file-system"
import bundledVersionJson from "../data_version.json"

/** Version stamp the refresh workflow writes next to the game data (`src/data/data_version.json`). */
export interface DataVersion {
    /** UTC timestamp of the scrape. Later data has a later stamp, so plain string comparison orders versions. */
    version: string
    /** Human-readable label such as "Tamamo Cross (Christmas) 2026-12-25". */
    label: string
    /** Lowest app version that can read this data. */
    minAppVersion: string
    /** md5 of each updatable data file with line endings normalized to LF. */
    files: Record<string, string>
}

/** Folder under the app's document directory that holds downloaded game data. */
export const GAME_DATA_DIR_NAME = "game-data"

/** File name of the version stamp, both bundled and downloaded. */
export const VERSION_FILE_NAME = "data_version.json"

/** Version of the game data bundled into this APK. */
const BUNDLED_VERSION = bundledVersionJson as DataVersion

/** The version picked at first use, fixed for the process so all data comes from the same copy until restart. */
let activeVersion: DataVersion | null = null

/**
 * Returns the folder that holds downloaded game data.
 * @returns The `game-data` directory under the app's document directory.
 */
export function getGameDataDir(): Directory {
    return new Directory(Paths.document, GAME_DATA_DIR_NAME)
}

/**
 * Reads and parses one JSON file from the downloaded game data folder.
 * @param fileName The file name, e.g. "supports.json".
 * @returns The parsed file, or null when it is missing or cannot be parsed.
 */
function readGameDataJson(fileName: string): unknown | null {
    try {
        const file = new File(getGameDataDir(), fileName)
        return file.exists ? JSON.parse(file.textSync()) : null
    } catch {
        return null
    }
}

/**
 * Reads the version stamp of the downloaded game data.
 * @returns The stamp, or null when nothing complete was downloaded or it cannot be parsed.
 */
export function readDownloadedVersion(): DataVersion | null {
    return readGameDataJson(VERSION_FILE_NAME) as DataVersion | null
}

/**
 * Returns the newest game data on the device right now, including a download made this session.
 * @returns The downloaded stamp when it is newer than the bundled one, else the bundled stamp.
 */
export function getInstalledDataVersion(): DataVersion {
    const downloaded = readDownloadedVersion()
    return downloaded !== null && downloaded.version > BUNDLED_VERSION.version ? downloaded : BUNDLED_VERSION
}

/**
 * Returns the game data version the app is using in this process.
 * @returns The stamp chosen at first use. A download made later only takes effect after a restart.
 */
export function getActiveDataVersion(): DataVersion {
    if (activeVersion === null) activeVersion = getInstalledDataVersion()
    return activeVersion
}

/**
 * Reads one downloaded data file when the downloaded data is the active copy.
 * @param fileName The data file name, e.g. "supports.json".
 * @returns The parsed file, or null to fall back to the bundled copy.
 */
export function readDownloaded(fileName: string): unknown | null {
    return getActiveDataVersion() === BUNDLED_VERSION ? null : readGameDataJson(fileName)
}
