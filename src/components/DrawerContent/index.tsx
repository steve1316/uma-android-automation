import React, { useMemo, useState, useEffect, useContext, useRef } from "react"
import { View, Text, StyleSheet, TouchableOpacity } from "react-native"
import { DrawerContentScrollView, DrawerContentComponentProps, useDrawerStatus } from "@react-navigation/drawer"
import { CommonActions } from "@react-navigation/native"
import { Ionicons } from "@expo/vector-icons"
import { markNavigationStart } from "../../lib/performanceLogger"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext } from "../../context/BotStateContext"
import { skillPlanSettingsPages } from "../../pages/SkillPlanSettings"

interface MenuItem {
    /** The route name used for navigation. */
    name: string
    /** The display label shown in the drawer. */
    label: string
    /** Function returning the Ionicons icon name based on focused state. */
    icon: (focused: boolean) => string
    /** Optional nested menu items for expandable sections. */
    nested?: MenuItem[]
}

/**
 * Custom drawer content component that renders a styled navigation sidebar.
 * Supports multi-level nested menu items with expand/collapse functionality,
 * active route highlighting, and deferred navigation for smooth drawer animations.
 * @param props The drawer content component props from React Navigation.
 */
const DrawerContent: React.FC<DrawerContentComponentProps> = (props) => {
    const { colors } = useTheme()
    const { state, navigation } = props
    const bsc = useContext(BotStateContext)
    const drawerStatus = useDrawerStatus()
    // Initialize with Settings expanded by default.
    const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set(["Settings"]))
    const previousDrawerStatus = useRef<string | undefined>(undefined)

    // List of nested routes under Settings.
    const settingsNestedRoutes = [
        "TrainingSettings",
        "TrainingEventSettings",
        "OCRSettings",
        "RacingSettings",
        "RacingPlanSettings",
        "SkillSettings",
        ...Object.values(skillPlanSettingsPages).flatMap((item) => item.name),
        "DiscordSettings",
        "DebugSettings",
    ]

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: {
                    flex: 1,
                    backgroundColor: colors.card,
                },
                header: {
                    paddingTop: 40,
                    paddingBottom: 24,
                    paddingHorizontal: 20,
                    borderBottomWidth: 1,
                    borderBottomColor: colors.border,
                },
                headerTitle: {
                    fontSize: 24,
                    fontWeight: "bold",
                    color: colors.foreground,
                    marginBottom: 4,
                },
                headerSubtitle: {
                    fontSize: 14,
                    color: colors.mutedForeground,
                },
                menuContainer: {
                    paddingTop: 8,
                },
                menuItem: {
                    flexDirection: "row",
                    alignItems: "center",
                    paddingVertical: 16,
                    paddingHorizontal: 20,
                    marginHorizontal: 4,
                    marginVertical: 2,
                    borderRadius: 0,
                },
                menuItemActive: {
                    backgroundColor: colors.muted,
                },
                menuItemIcon: {
                    marginRight: 16,
                    width: 24,
                    alignItems: "center",
                },
                menuItemText: {
                    fontSize: 16,
                    fontWeight: "500",
                    color: colors.foreground,
                    flex: 1,
                },
                menuItemTextActive: {
                    color: colors.primary,
                    fontWeight: "600",
                },
                chevronButton: {
                    padding: 4,
                    marginLeft: 8,
                    borderRadius: 4,
                },
                nestedContainer: {
                    overflow: "hidden",
                },
                nestedItem: {
                    flexDirection: "row",
                    alignItems: "center",
                    paddingVertical: 12,
                    paddingHorizontal: 20,
                    paddingLeft: 40,
                    marginHorizontal: 4,
                    marginVertical: 2,
                    borderRadius: 0,
                },
                nestedItemActive: {
                    backgroundColor: colors.muted,
                },
                nestedItemIcon: {
                    marginRight: 16,
                    width: 24,
                    alignItems: "center",
                },
                nestedItemText: {
                    fontSize: 15,
                    fontWeight: "400",
                    color: colors.foreground,
                    flex: 1,
                },
                nestedItemTextActive: {
                    color: colors.primary,
                    fontWeight: "500",
                },
                doubleNestedItem: {
                    flexDirection: "row",
                    alignItems: "center",
                    paddingVertical: 12,
                    paddingHorizontal: 20,
                    paddingLeft: 64,
                    marginHorizontal: 4,
                    marginVertical: 2,
                    borderRadius: 0,
                },
                doubleNestedItemActive: {
                    backgroundColor: colors.muted,
                },
                doubleNestedItemIcon: {
                    marginRight: 16,
                    width: 24,
                    alignItems: "center",
                },
                doubleNestedItemText: {
                    fontSize: 14,
                    fontWeight: "400",
                    color: colors.foreground,
                    flex: 1,
                },
                doubleNestedItemTextActive: {
                    color: colors.primary,
                    fontWeight: "500",
                },
            }),
        [colors]
    )

    // Define the menu item configurations for the drawer.
    const menuItems: MenuItem[] = [
        {
            name: "Home",
            label: "Home",
            icon: (focused: boolean) => (focused ? "home" : "home-outline"),
        },
        {
            name: "Settings",
            label: "Settings",
            icon: (focused: boolean) => (focused ? "settings" : "settings-outline"),
            nested: [
                {
                    name: "TrainingSettings",
                    label: "Training Settings",
                    icon: () => "barbell-outline",
                },
                {
                    name: "TrainingEventSettings",
                    label: "Training Event Settings",
                    icon: () => "calendar-outline",
                },
                {
                    name: "OCRSettings",
                    label: "OCR Settings",
                    icon: () => "eye-outline",
                },
                {
                    name: "RacingSettings",
                    label: "Racing Settings",
                    icon: () => "flag-outline",
                    nested: [
                        {
                            name: "RacingPlanSettings",
                            label: "Racing Plan Settings",
                            icon: () => "map-outline",
                        },
                    ],
                },
                {
                    name: "SkillSettings",
                    label: "Skill Settings",
                    icon: () => "american-football-outline",
                    nested: Object.values(skillPlanSettingsPages).map((item) => ({
                        name: item.name,
                        label: `${item.title} Plan Settings`,
                        icon: () => "cube-outline",
                    })),
                },
                {
                    name: "EventLogVisualizer",
                    label: "Event Log Visualizer",
                    icon: () => "eye-outline",
                },
                {
                    name: "DiscordSettings",
                    label: "Discord Settings",
                    icon: () => "logo-discord",
                },
                {
                    name: "DebugSettings",
                    label: "Debug Settings",
                    icon: () => "bug-outline",
                },
            ],
        },
    ]

    /**
     * Gets the current active screen name, handling nested navigators.
     * If on Settings stack, returns the nested screen name (e.g., `TrainingSettings`).
     * Otherwise returns the drawer route name (e.g., `Home`).
     * @returns The current active screen name.
     */
    const getCurrentActiveScreen = (): string => {
        const drawerRoute = state.routes[state.index]
        if (drawerRoute?.name === "Settings") {
            // Check if there's nested state from the stack navigator.
            const nestedState = drawerRoute.state
            if (nestedState?.routes && nestedState.index !== undefined) {
                return nestedState.routes[nestedState.index]?.name || "SettingsMain"
            }
            return "SettingsMain"
        }
        return drawerRoute?.name || "Home"
    }

    // Ensure Settings is expanded when drawer opens, and auto-expand sections if nested routes are active.
    useEffect(() => {
        // Check if drawer just opened (transitioned from closed to open).
        const drawerJustOpened = previousDrawerStatus.current !== "open" && drawerStatus === "open"

        if (drawerJustOpened) {
            // Reset Settings to expanded when drawer opens.
            setExpandedSections((prev) => {
                const newSet = new Set(prev)
                newSet.add("Settings")
                return newSet
            })
        }

        previousDrawerStatus.current = drawerStatus

        const currentScreen = getCurrentActiveScreen()
        const newExpanded = new Set<string>()

        // Auto-expand Settings if any nested route is active.
        if (settingsNestedRoutes.includes(currentScreen) || currentScreen === "SettingsMain") {
            newExpanded.add("Settings")
        }

        // Auto-expand Racing Settings if Racing Plan Settings is active.
        if (currentScreen === "RacingPlanSettings") {
            newExpanded.add("RacingSettings")
        }

        // Auto-expand Skill Settings if Skill Plan Settings is active.
        if (
            Object.values(skillPlanSettingsPages)
                .map((item) => item.name)
                .includes(currentScreen)
        ) {
            newExpanded.add("SkillSettings")
        }

        // Merge with existing expanded sections to preserve user's manual expansions.
        if (newExpanded.size > 0) {
            setExpandedSections((prev) => {
                const merged = new Set(prev)
                newExpanded.forEach((section) => merged.add(section))
                return merged
            })
        }
    }, [state.index, state.routes, drawerStatus])

    /**
     * Toggles the expanded state of a section in the drawer.
     * @param sectionName The name of the section to toggle.
     */
    const toggleSection = (sectionName: string) => {
        setExpandedSections((prev) => {
            const newSet = new Set(prev)
            if (newSet.has(sectionName)) {
                newSet.delete(sectionName)
            } else {
                newSet.add(sectionName)
            }
            return newSet
        })
    }

    /**
     * Navigates to a route and closes the drawer.
     * For nested routes, we navigate to the Settings drawer and then the specific screen.
     * @param routeName The name of the route to navigate to.
     */
    const handleNavigation = (routeName: string) => {
        // Mark the start of the navigation for performance tracking.
        markNavigationStart(routeName)

        // Close the drawer immediately to start the transition.
        // This achieves the effect of hiding the initial lag of mounting and rendering the target page while we are transitioning to it.
        navigation.closeDrawer()

        // Defer the heavy navigation until the drawer closing animation has been scheduled.
        // This prevents the target page's heavy mount/render from stuttering the drawer animation.
        setTimeout(() => {
            if (routeName === "Home") {
                // Navigate to Home drawer screen.
                navigation.dispatch(CommonActions.navigate({ name: "Home" }))
            } else if (routeName === "Settings") {
                // Navigate to Settings main page.
                navigation.dispatch(
                    CommonActions.navigate({
                        name: "Settings",
                        params: { screen: "SettingsMain", initial: false },
                    })
                )
            } else {
                // Settings sub-pages: navigate to Settings drawer, then to the specific screen.
                navigation.dispatch(
                    CommonActions.navigate({
                        name: "Settings",
                        params: { screen: routeName, initial: false },
                    })
                )
            }
        }, 0)
    }

    /**
     * Navigates to a parent route and closes the drawer.
     * @param item The menu item to navigate to.
     */
    const handleParentNavigation = (item: MenuItem) => {
        handleNavigation(item.name)
    }

    /**
     * Stops event propagation to prevent the navigation from happening when the chevron is pressed.
     * @param e The event object.
     * @param item The menu item.
     */
    const handleChevronPress = (e: any, item: MenuItem) => {
        e.stopPropagation()
        toggleSection(item.name)
    }

    /**
     * Checks if a section is expanded.
     * @param sectionName The name of the section to check.
     * @returns True if the section is expanded, false otherwise.
     */
    const isSectionExpanded = (sectionName: string) => {
        return expandedSections.has(sectionName)
    }

    /**
     * Checks if a route is active.
     * @param routeName The name of the route to check.
     * @returns True if the route is active, false otherwise.
     */
    const isRouteActive = (routeName: string) => {
        const currentScreen = getCurrentActiveScreen()
        // Settings menu item is active when on SettingsMain.
        if (routeName === "Settings") {
            return currentScreen === "SettingsMain"
        }
        return currentScreen === routeName
    }

    /**
     * Recursively renders menu items at any nesting level.
     * @param item The menu item to render.
     * @param level The nesting level.
     * @returns The rendered menu item.
     */
    const renderMenuItem = (item: MenuItem, level: number = 0) => {
        const isActive = isRouteActive(item.name)
        const isExpanded = item.nested ? isSectionExpanded(item.name) : false

        const stylesByLevel = {
            0: {
                item: styles.menuItem,
                active: styles.menuItemActive,
                icon: styles.menuItemIcon,
                text: styles.menuItemText,
                textActive: styles.menuItemTextActive,
                iconSize: 24,
                chevronSize: 20,
            },
            1: {
                item: styles.nestedItem,
                active: styles.nestedItemActive,
                icon: styles.nestedItemIcon,
                text: styles.nestedItemText,
                textActive: styles.nestedItemTextActive,
                iconSize: 20,
                chevronSize: 18,
            },
            2: {
                item: styles.doubleNestedItem,
                active: styles.doubleNestedItemActive,
                icon: styles.doubleNestedItemIcon,
                text: styles.doubleNestedItemText,
                textActive: styles.doubleNestedItemTextActive,
                iconSize: 18,
                chevronSize: 16,
            },
        }

        // Determine styles based on nesting level.
        const itemStyle = stylesByLevel[level as keyof typeof stylesByLevel].item
        const activeStyle = stylesByLevel[level as keyof typeof stylesByLevel].active
        const iconStyle = stylesByLevel[level as keyof typeof stylesByLevel].icon
        const textStyle = stylesByLevel[level as keyof typeof stylesByLevel].text
        const textActiveStyle = stylesByLevel[level as keyof typeof stylesByLevel].textActive
        const iconSize = stylesByLevel[level as keyof typeof stylesByLevel].iconSize
        const chevronSize = stylesByLevel[level as keyof typeof stylesByLevel].chevronSize

        return (
            <View key={item.name}>
                <View style={[itemStyle, isActive && activeStyle]}>
                    <TouchableOpacity
                        style={{ flex: 1, flexDirection: "row", alignItems: "center" }}
                        onPress={() => (level === 0 ? handleParentNavigation(item) : handleNavigation(item.name))}
                        activeOpacity={0.7}
                    >
                        <View style={iconStyle}>
                            <Ionicons name={item.icon(isActive) as any} size={iconSize} color={isActive ? colors.primary : colors.foreground} />
                        </View>
                        <Text style={[textStyle, isActive && textActiveStyle]}>{item.label}</Text>
                    </TouchableOpacity>
                    {item.nested && (
                        <TouchableOpacity onPress={(e) => handleChevronPress(e, item)} style={styles.chevronButton} activeOpacity={0.7}>
                            <Ionicons name={isExpanded ? "chevron-up" : "chevron-down"} size={chevronSize} color={colors.mutedForeground} />
                        </TouchableOpacity>
                    )}
                </View>
                {item.nested && isExpanded && <View style={styles.nestedContainer}>{item.nested.map((nestedItem) => renderMenuItem(nestedItem, level + 1))}</View>}
            </View>
        )
    }

    return (
        <DrawerContentScrollView {...props} style={styles.container} contentContainerStyle={{ flexGrow: 1 }}>
            <View style={styles.header}>
                <Text style={styles.headerTitle}>Uma Android Automation</Text>
                <Text style={styles.headerSubtitle}>{bsc.appVersion}</Text>
            </View>
            <View style={styles.menuContainer}>{menuItems.map((item) => renderMenuItem(item, 0))}</View>
        </DrawerContentScrollView>
    )
}

export default React.memo(DrawerContent)
