import React from "react"
import { TouchableOpacity, TouchableOpacityProps } from "react-native"
import { Moon, Sun } from "lucide-react-native"
import { useTheme } from "../../context/ThemeContext"

interface ThemeToggleProps {
    /** Optional custom style for the toggle button. */
    style?: TouchableOpacityProps["style"]
}

/**
 * A toggle button that switches between light and dark themes.
 * Displays a moon icon in light mode and a sun icon in dark mode.
 * @param style Optional custom style for the toggle button.
 */
export const ThemeToggle: React.FC<ThemeToggleProps> = ({ style }) => {
    const { theme, toggleTheme, colors } = useTheme()

    return (
        <TouchableOpacity onPress={toggleTheme} style={style}>
            {theme === "light" ? <Moon size={24} color={colors.secondaryForeground} /> : <Sun size={24} color={colors.secondaryForeground} />}
        </TouchableOpacity>
    )
}

export default ThemeToggle
