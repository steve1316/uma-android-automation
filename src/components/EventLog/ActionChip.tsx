import React from "react"
import { useTheme } from "../../context/ThemeContext"
import TintedChip from "../ui/tinted-chip"
import { ACTION_VISUALS, type ActionKey } from "./actionVisuals"

/** Props for `ActionChip`. */
interface ActionChipProps {
    /** Which action this chip represents. */
    action: ActionKey
    /** Optional granular detail appended after the label (e.g. race grade "G3" or training type "Wit"). */
    sublabel?: string
}

/**
 * A small pill showing one action that happened on a day: a tinted Lucide icon plus an uppercase mono tag.
 * The tint comes from the shared `ACTION_VISUALS` mapping so it matches the Year Summary stat bars.
 * @param action The action this chip represents.
 * @param sublabel Optional granular detail appended after the label.
 * @returns A tinted icon-and-label pill.
 */
const ActionChipImpl = ({ action, sublabel }: ActionChipProps) => {
    const { colors } = useTheme()
    const visual = ACTION_VISUALS[action]
    const text = sublabel ? `${visual.label} ${sublabel}` : visual.label
    return <TintedChip icon={visual.icon} label={text} tint={colors[visual.colorKey]} />
}

export const ActionChip = React.memo(ActionChipImpl)
export default ActionChip
