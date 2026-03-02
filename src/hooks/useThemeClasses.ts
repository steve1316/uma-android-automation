import { useMemo } from "react"
import { useTheme } from "../context/ThemeContext"

/**
 * Hook to get theme-based Tailwind CSS classes.
 * @returns An object containing theme-based Tailwind CSS classes.
 */
export const useThemeClasses = () => {
    const { isDark } = useTheme()

    // Memoize the return value to prevent new object references on every render.
    return useMemo(
        () => ({
            // Background classes
            bg: isDark ? "bg-gray-900" : "bg-white",
            bgSecondary: isDark ? "bg-gray-800" : "bg-gray-50",
            bgCard: isDark ? "bg-gray-800" : "bg-white",

            // Text classes
            text: isDark ? "text-white" : "text-gray-900",
            textSecondary: isDark ? "text-gray-300" : "text-gray-600",
            textMuted: isDark ? "text-gray-400" : "text-gray-500",

            // Border classes
            border: isDark ? "border-gray-700" : "border-gray-200",
            borderInput: isDark ? "border-gray-600" : "border-gray-300",

            // Interactive classes
            hover: isDark ? "hover:bg-gray-700" : "hover:bg-gray-100",
            active: isDark ? "active:bg-gray-600" : "active:bg-gray-200",

            // Status classes
            success: "text-green-600",
            warning: "text-yellow-600",
            error: "text-red-600",
            info: "text-blue-600",
        }),
        [isDark]
    )
}
