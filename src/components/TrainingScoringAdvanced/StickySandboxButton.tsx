// src/components/TrainingScoringAdvanced/StickySandboxButton.tsx
import React from "react"
import { FlaskConical } from "lucide-react-native"
import { GlassFab } from "../ui/glass-fab"
import { useTheme } from "../../context/ThemeContext"
import { SPACING } from "../../lib/spacing"

/** Props for `StickySandboxButton`. */
export interface StickySandboxButtonProps {
    /** Press handler, typically opens the scoring sandbox modal. */
    onPress: () => void
}

/**
 * Floating circular FAB pinned to the bottom-right of the viewport. Opens the scoring sandbox modal.
 *
 * @param props See `StickySandboxButtonProps`.
 * @returns A 40x40 `GlassFab` with a flask icon, absolutely positioned.
 */
export function StickySandboxButton({ onPress }: StickySandboxButtonProps): React.ReactElement {
    const { colors } = useTheme()
    return (
        <GlassFab
            onPress={onPress}
            accessibilityLabel="Open scoring sandbox"
            icon={<FlaskConical size={20} color={colors.brand} />}
            style={{ position: "absolute", right: SPACING.lg, bottom: SPACING.lg }}
        />
    )
}
