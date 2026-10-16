import bundled from "../traineeCards.json"
import { readDownloaded } from "./readDownloaded"

/** Trainee outfits keyed by card id. The downloaded copy when it is newer than the APK's, else the bundled one. */
const traineeCardsData = (readDownloaded("traineeCards.json") ?? bundled) as typeof bundled

export default traineeCardsData
