import bundled from "../character_objectives.json"
import { readDownloaded } from "./readDownloaded"

/** Character mandatory objective races. The downloaded copy when it is newer than the APK's, else the bundled one. */
const characterObjectivesData = (readDownloaded("character_objectives.json") ?? bundled) as typeof bundled

export default characterObjectivesData
