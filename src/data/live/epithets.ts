import bundled from "../epithets.json"
import { readDownloaded } from "./readDownloaded"

/** Epithets. The downloaded copy when it is newer than the APK's, else the bundled one. */
const epithetsData = (readDownloaded("epithets.json") ?? bundled) as typeof bundled

export default epithetsData
