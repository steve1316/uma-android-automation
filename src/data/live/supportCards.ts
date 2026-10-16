import bundled from "../supportCards.json"
import { readDownloaded } from "./readDownloaded"

/** Support cards keyed by support id. The downloaded copy when it is newer than the APK's, else the bundled one. */
const supportCardsData = (readDownloaded("supportCards.json") ?? bundled) as typeof bundled

export default supportCardsData
