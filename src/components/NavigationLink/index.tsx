import React from "react"
import { View, Text, TouchableOpacity, ViewStyle } from "react-native"
import { useThemeClasses } from "../../hooks/useThemeClasses"

interface NavigationLinkProps {
    /** The title text for the navigation link. */
    title: string
    /** The description text displayed below the title. */
    description: string
    /** Callback fired when the link is pressed. */
    onPress: () => void
    /** Whether the link is disabled. */
    disabled?: boolean
    /** Optional warning text shown when the link is disabled. */
    disabledDescription?: string
    /** Optional NativeWind class name. */
    className?: string
    /** Optional custom style for the container. */
    style?: ViewStyle
}

/**
 * A themed card-style navigation link with title, description, and disabled state support.
 * Used on settings pages to navigate to sub-pages.
 * @param title The title text for the navigation link.
 * @param description The description text displayed below the title.
 * @param onPress Callback fired when the link is pressed.
 * @param disabled Whether the link is disabled.
 * @param disabledDescription Optional warning text shown when the link is disabled.
 * @param className Optional NativeWind class name.
 * @param style Optional custom style for the container.
 */
const NavigationLink: React.FC<NavigationLinkProps> = ({ title, description, onPress, disabled = false, disabledDescription, className = "", style }) => {
    const themeClasses = useThemeClasses()

    return (
        <View className={`mt-5 p-4 rounded-lg border ${themeClasses.bgCard} ${themeClasses.border} ${disabled ? "opacity-50" : ""} ${className}`} style={style}>
            <TouchableOpacity onPress={disabled ? undefined : onPress} disabled={disabled}>
                <Text className={`text-lg font-semibold ${disabled ? themeClasses.textSecondary : themeClasses.text}`}>{title}</Text>
                <Text className={`mt-2 ${themeClasses.textSecondary}`}>{description}</Text>
                {disabled && disabledDescription && <Text className={`mt-2 text-sm text-orange-500`}>⚠️ {disabledDescription}</Text>}
            </TouchableOpacity>
        </View>
    )
}

export default React.memo(NavigationLink)
