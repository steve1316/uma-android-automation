import bundled from "../characters.json"
import { readDownloaded } from "./readDownloaded"

/** Character training events. The downloaded copy when it is newer than the APK's, else the bundled one. */
const charactersData = (readDownloaded("characters.json") ?? bundled) as typeof bundled

export default charactersData
