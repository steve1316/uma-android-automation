import bundled from "../supports.json"
import { readDownloaded } from "./readDownloaded"

/** Support card training events. The downloaded copy when it is newer than the APK's, else the bundled one. */
const supportsData = (readDownloaded("supports.json") ?? bundled) as typeof bundled

export default supportsData
