import React from "react"
import { PressableProps, ViewStyle, ActivityIndicator, View } from "react-native"
import { Button } from "../ui/button"
import { Text } from "../ui/text"
import { useTheme } from "../../context/ThemeContext"

interface CustomButtonProps extends PressableProps {
    variant?: "default" | "destructive" | "outline" | "secondary" | "ghost" | "link"
    size?: "default" | "sm" | "lg" | "icon"
    style?: ViewStyle
    className?: string
    disabled?: boolean
    isLoading?: boolean
    fontSize?: number
    icon?: React.ReactElement
    iconPosition?: "left" | "right"
    children: React.ReactNode
}

const CustomButton: React.FC<CustomButtonProps> = ({
    variant = "default",
    size = "default",
    style,
    className = "",
    disabled = false,
    isLoading = false,
    fontSize,
    icon,
    iconPosition = "left",
    children,
    ...props
}) => {
    const { colors, isDark } = useTheme()

    // Determine the background color based on variant and theme.
    const getBackgroundColor = () => {
        if (disabled) return { opacity: 0.5 }

        switch (variant) {
            case "destructive":
                return { backgroundColor: colors.destructive }
            case "outline":
                return { backgroundColor: isDark ? "black" : "white" }
            case "secondary":
                return { backgroundColor: colors.secondary }
            case "ghost":
                return { backgroundColor: "transparent" }
            case "link":
                return { backgroundColor: "transparent" }
            default:
                return {}
        }
    }

    // Determine the text color based on variant and theme.
    const getTextColor = () => {
        if (disabled) return { opacity: 0.5 }

        switch (variant) {
            case "destructive":
                return { color: "white" }
            case "outline":
                return { color: isDark ? "white" : "black" }
            case "secondary":
                return { color: isDark ? "black" : "white" }
            default:
                return { color: "black" }
        }
    }

    // Apply custom styling for specific variants that need theme-aware colors.
    const getCustomStyle = () => {
        if (disabled) return {}

        switch (variant) {
            case "destructive":
                return { backgroundColor: colors.destructive }
            case "outline":
                return { borderColor: isDark ? "white" : "black" }
            case "secondary":
                return { backgroundColor: isDark ? colors.secondary : colors.primary }
            case "default":
                return { backgroundColor: isDark ? colors.primary : colors.secondary }
            default:
                return {}
        }
    }

    return (
        <Button variant={variant} size={size} style={[getBackgroundColor(), getCustomStyle(), style]} disabled={disabled} {...props}>
            {isLoading && <ActivityIndicator size="small" color="#ffffff" />}
            <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
                {icon && iconPosition === "left" && icon}
                <Text style={[getTextColor(), fontSize ? { fontSize: fontSize } : undefined]}>{children}</Text>
                {icon && iconPosition === "right" && icon}
            </View>
        </Button>
    )
}

export default React.memo(CustomButton)
