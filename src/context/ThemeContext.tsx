import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react"
import { useColorScheme } from "react-native"
import { THEME } from "../lib/theme"

type Theme = "light" | "dark"

/**
 * Context value interface for the Theme provider.
 * Exposes the current theme, toggle, and pre-resolved color tokens.
 */
interface ThemeContextType {
    /** The current active theme ("light" or "dark"). */
    theme: Theme
    /** Setter for the theme value. */
    setTheme: (theme: Theme) => void
    /** Toggles between light and dark themes. */
    toggleTheme: () => void
    /** Whether the current theme is dark mode. */
    isDark: boolean
    /** The resolved color tokens for the current theme. */
    colors: typeof THEME.light
}

const ThemeContext = createContext<ThemeContextType | undefined>(undefined)

/**
 * Hook to access the Theme context. Must be used within a ThemeProvider.
 * @returns The theme context with the current theme, toggle function, and color tokens.
 */
export const useTheme = () => {
    const context = useContext(ThemeContext)
    if (!context) {
        throw new Error("useTheme must be used within a ThemeProvider")
    }
    return context
}

interface ThemeProviderProps {
    /** The children of the provider. */
    children: React.ReactNode
}

/**
 * Provider component for the Theme context.
 * Initializes the theme based on the device's system color scheme
 * and provides theme state and toggle functionality to child components.
 * @param children The children of the provider.
 * @returns The theme provider.
 */
export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
    const systemColorScheme = useColorScheme()
    const [theme, setTheme] = useState<Theme>("light")

    // Initialize theme based on system preference.
    useEffect(() => {
        if (systemColorScheme) {
            setTheme(systemColorScheme)
        }
    }, [systemColorScheme])

    /**
     * Toggles between light and dark themes.
     */
    const toggleTheme = useCallback(() => {
        setTheme((prev) => (prev === "light" ? "dark" : "light"))
    }, [])

    // Memoize the provider value to prevent cascading re-renders.
    const value = useMemo<ThemeContextType>(() => {
        const isDark = theme === "dark"
        const colors = THEME[theme]
        return {
            theme,
            setTheme,
            toggleTheme,
            isDark,
            colors,
        }
    }, [theme, toggleTheme])

    return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}
