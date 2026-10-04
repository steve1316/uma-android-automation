import bundled from "../skills.json"
import { readDownloaded } from "./readDownloaded"

/** Skill definitions. The downloaded copy when it is newer than the APK's, else the bundled one. */
const skillsData = (readDownloaded("skills.json") ?? bundled) as typeof bundled

export default skillsData
