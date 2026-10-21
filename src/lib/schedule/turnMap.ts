/**
 * Parse a persisted `turn -> string` JSON map, such as the SRS manual locks or the hand-picked races, tolerating malformed values.
 * @param json The raw setting string.
 * @returns The parsed map, or an empty map when the string is empty, malformed, or not a JSON object.
 */
export function parseTurnMap(json: string | undefined): Record<string, string> {
    try {
        const parsed = JSON.parse(json || "{}")
        return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as Record<string, string>) : {}
    } catch {
        return {}
    }
}
