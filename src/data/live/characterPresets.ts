import bundled from "../characterPresets.json"
import { readDownloaded } from "./readDownloaded"

/** Character aptitude presets. The downloaded copy when it is newer than the APK's, else the bundled one. */
const characterPresetsData = (readDownloaded("characterPresets.json") ?? bundled) as typeof bundled

export default characterPresetsData
