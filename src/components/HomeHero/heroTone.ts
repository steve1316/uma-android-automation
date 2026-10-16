import type { ThemeColors } from "../../lib/theme"
import type { RunMode } from "../../context/BotStateContext"
import { HeroTone } from "./heroView"

/** Colors for one tone: pill text, pill background, card background, card border. */
export interface ToneColors {
    /** Pill text and accent color. */
    accent: string
    /** Pill background. */
    accentBg: string
    /** Card background. */
    cardBg: string
    /** Card border. */
    border: string
}

/**
 * The run mode's own color: brand teal for Career, violet for Dailies.
 * @param colors The theme colors.
 * @param runMode Whether the next run is a career or the dailies.
 * @returns The mode color and the text color that reads on it.
 */
export function modeAccent(colors: ThemeColors, runMode: RunMode): { accent: string; onAccent: string } {
    return runMode === "dailies" ? { accent: colors.dailies, onAccent: colors.onDailies } : { accent: colors.brand, onAccent: colors.onBrand }
}

/**
 * Resolves a hero tone to theme colors. In Dailies mode the brand tones use the Dailies color, while result tones keep their own.
 * @param colors The theme colors.
 * @param tone The tone to resolve.
 * @param runMode Whether the next run is a career or the dailies.
 * @returns The pill and card colors for that tone.
 */
export function toneColors(colors: ThemeColors, tone: HeroTone, runMode: RunMode = "career"): ToneColors {
    const dailies = runMode === "dailies"
    const brand = modeAccent(colors, runMode).accent
    const brandSubtle = dailies ? colors.dailiesSubtle : colors.brandSubtle
    const brandBorder = dailies ? colors.dailiesBorder : colors.brandBorder
    switch (tone) {
        case "success":
            return { accent: colors.success, accentBg: colors.successSubtle, cardBg: colors.successSubtle, border: colors.successSoftBorder }
        case "error":
            return { accent: colors.error, accentBg: colors.errorSubtle, cardBg: colors.errorSubtle, border: colors.errorSoftBorder }
        case "warning":
            return { accent: colors.warning, accentBg: colors.warningSubtle, cardBg: colors.warningSubtle, border: colors.warningSoftBorder }
        case "neutral":
            return { accent: colors.textMuted, accentBg: colors.surfaceRaised, cardBg: brandSubtle, border: brandBorder }
        default:
            return { accent: brand, accentBg: brandSubtle, cardBg: brandSubtle, border: brandBorder }
    }
}
