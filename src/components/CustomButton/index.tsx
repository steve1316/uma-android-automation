import React from "react"
import { PressableProps, ViewStyle, ActivityIndicator, View } from "react-native"
import { Button } from "../ui/button"
import { Text } from "../ui/text"
import { useTheme } from "../../context/ThemeContext"

interface CustomButtonProps extends PressableProps {
    /** The visual style variant of the button. */
    variant?: "default" | "destructive" | "outline" | "secondary" | "ghost" | "link"
    /** The size preset for the button. */
    size?: "default" | "sm" | "lg" | "icon"
    /** Optional custom style for the button. */
    style?: ViewStyle
    /** Optional NativeWind class name. */
    className?: string
    /** Whether the button is disabled. */
    disabled?: boolean
    /** Whether to show a loading spinner. */
    isLoading?: boolean
    /** Optional custom font size for the button text. */
    fontSize?: number
    /** Optional icon element to render alongside the button text. */
    icon?: React.ReactElement
    /** Whether the icon appears to the left or right of the text. */
    iconPosition?: "left" | "right"
    /** The button label content. */
    children: React.ReactNode
}

/**
 * A themed, configurable button component with support for multiple variants, icons, and loading state.
 * Automatically applies theme-aware colors based on the selected variant and dark/light mode.
 * @param variant The visual style variant of the button.
 * @param size The size preset for the button.
 * @param style Optional custom style.
 * @param disabled Whether the button is disabled.
 * @param isLoading Whether to show a loading spinner.
 * @param icon Optional icon element.
 * @param iconPosition Whether the icon appears left or right.
 * @param children The button label content.
 */
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

    /**
     * Determine the background color based on variant and theme.
     * @returns The background color for the button.
     */
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

    /**
     * Determine the text color based on variant and theme.
     * @returns The text color for the button.
     */
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
