import { Dumbbell, Flag, Zap, Smile, Bandage, type LucideIcon } from "lucide-react-native"
import type { DayActions } from "../../lib/eventLogParser"

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Action visuals
//
// Single source of truth for how each of the five tracked actions is drawn. Both DayRow (Timeline chips) and
// YearSummaryCard (stat bars) read from here so their colors and icons never drift apart.

/** One of the five tracked per-day actions. */
export type ActionKey = keyof DayActions

/** The theme color token names used to tint actions (each exists on the palette returned by `useTheme`). */
export type ActionColorKey = "brand" | "info" | "warning" | "activeFlag" | "error"

/** Visual metadata for an action: its short label, Lucide icon, and the theme color token that tints it. */
export interface ActionVisual {
    /** Short human label shown on chips and stat bars. */
    label: string
    /** Lucide icon representing the action. */
    icon: LucideIcon
    /** Theme color token name resolved against `useTheme().colors`. */
    colorKey: ActionColorKey
}

/** Per-action visual mapping: Train=cyan, Race=blue, Energy=amber, Mood=green, Injury=red. */
export const ACTION_VISUALS: Record<ActionKey, ActionVisual> = {
    training: { label: "Train", icon: Dumbbell, colorKey: "brand" },
    race: { label: "Race", icon: Flag, colorKey: "info" },
    energy: { label: "Energy", icon: Zap, colorKey: "warning" },
    mood: { label: "Mood", icon: Smile, colorKey: "activeFlag" },
    injury: { label: "Injury", icon: Bandage, colorKey: "error" },
}

/** The order actions are listed everywhere (chips, stat bars). */
export const ACTION_ORDER: ActionKey[] = ["training", "race", "energy", "mood", "injury"]
