import type { ThemeColors } from "../../lib/theme"
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
 * Resolves a hero tone to theme colors.
 * @param colors The theme colors.
 * @param tone The tone to resolve.
 * @returns The pill and card colors for that tone.
 */
export function toneColors(colors: ThemeColors, tone: HeroTone): ToneColors {
    switch (tone) {
        case "success":
            return { accent: colors.success, accentBg: colors.successSubtle, cardBg: colors.successSubtle, border: colors.successSoftBorder }
        case "error":
            return { accent: colors.error, accentBg: colors.errorSubtle, cardBg: colors.errorSubtle, border: colors.errorSoftBorder }
        case "warning":
            return { accent: colors.warning, accentBg: colors.warningSubtle, cardBg: colors.warningSubtle, border: colors.warningSoftBorder }
        case "neutral":
            return { accent: colors.textMuted, accentBg: colors.surfaceRaised, cardBg: colors.brandSubtle, border: colors.brandBorder }
        default:
            return { accent: colors.brand, accentBg: colors.brandSubtle, cardBg: colors.brandSubtle, border: colors.brandBorder }
    }
}
