import React from "react"
import { View, Text, TouchableOpacity, ViewStyle } from "react-native"
import { useThemeClasses } from "../../hooks/useThemeClasses"

interface NavigationLinkProps {
    title: string
    description: string
    onPress: () => void
    disabled?: boolean
    disabledDescription?: string
    className?: string
    style?: ViewStyle
}

const NavigationLink: React.FC<NavigationLinkProps> = ({ title, description, onPress, disabled = false, disabledDescription, className = "", style }) => {
    const themeClasses = useThemeClasses()

    return (
        <View className={`mt-5 p-4 rounded-lg border ${themeClasses.bgCard} ${themeClasses.border} ${disabled ? "opacity-50" : ""} ${className}`} style={style}>
            <TouchableOpacity onPress={disabled ? undefined : onPress} disabled={disabled}>
                <Text className={`text-lg font-semibold ${disabled ? themeClasses.textSecondary : themeClasses.text}`}>{title}</Text>
                <Text className={`mt-2 ${themeClasses.textSecondary}`}>{description}</Text>
                {disabled && disabledDescription && (
                    <Text className={`mt-2 text-sm text-orange-500`}>⚠️ {disabledDescription}</Text>
                )}
            </TouchableOpacity>
        </View>
    )
}

export default React.memo(NavigationLink)
