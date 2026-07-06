import React, { useMemo, useState, useEffect, useContext, useRef, useCallback } from "react"
import { View, Text, StyleSheet, Pressable, Linking, NativeModules } from "react-native"
import { DrawerContentScrollView, DrawerContentComponentProps, useDrawerStatus } from "@react-navigation/drawer"
import { CommonActions } from "@react-navigation/native"
import Ionicons from "@react-native-vector-icons/ionicons"
import { Avatar, AvatarImage } from "../ui/avatar"
import { SectionLabel } from "../ui/section-label"
import { markNavigationStart, markNavigationPhase } from "../../lib/performanceLogger"
import { useTheme } from "../../context/ThemeContext"
import { BotMetaContext } from "../../context/BotStateContext"
import { circularPress } from "../../lib/pressSurface"
import { SPACING } from "../../lib/spacing"
import { TYPE } from "../../lib/type"
import { databaseManager } from "../../lib/database"

/** A single drawer row entry. May expand to reveal `children` rows. */
interface DrawerItem {
    /** Display label rendered in the row. */
    label: string
    /** Ionicons name. Outline variant is preferred so the active variant can be derived. */
    icon: string
    /** Stack/Drawer route name the row navigates to. */
    route: string
    /** Optional nested rows revealed when the row is expanded. */
    children?: DrawerItem[]
}

/** A labelled group of `DrawerItem` rows rendered under a `SectionLabel`. */
interface DrawerSection {
    /** Section heading rendered via `SectionLabel`. */
    label: string
    /** Rows that belong to this section. */
    items: DrawerItem[]
}

/** Repository GitHub URL used by the footer GitHub icon. */
const GITHUB_URL = "https://github.com/steve1316/uma-android-automation"
/** SQLite category for misc drawer state. */
const MISC_CATEGORY = "misc"
/** SQLite key holding the JSON-serialised recent-page route list. */
const RECENT_PAGES_KEY = "drawerRecentPages"
/** Max entries stored on disk. */
const RECENT_PAGES_STORE_CAP = 5
/** Max entries actually rendered as chips. */
const RECENT_PAGES_RENDER_CAP = 3

/** Sections rendered in the drawer. Order is significant. */
const SECTIONS: DrawerSection[] = [
    {
        label: "Overview",
        items: [
            { label: "Home", icon: "home-outline", route: "Home" },
            { label: "Settings", icon: "settings-outline", route: "SettingsMain" },
            { label: "Ask the Docs", icon: "chatbubble-outline", route: "Chat" },
        ],
    },
    {
        label: "Gameplay",
        items: [
            { label: "Training", icon: "barbell-outline", route: "TrainingSettings" },
            { label: "Training Events", icon: "calendar-outline", route: "TrainingEventSettings" },
            { label: "Racing", icon: "flag-outline", route: "RacingSettings" },
            { label: "Schedule", icon: "calendar-number-outline", route: "ScheduleScreen" },
            { label: "Skills", icon: "american-football-outline", route: "Skills" },
        ],
    },
    {
        label: "Scenarios",
        items: [{ label: "Scenario Overrides", icon: "options-outline", route: "ScenarioOverridesSettings" }],
    },
    {
        label: "Integrations",
        items: [
            { label: "Discord", icon: "chatbubble-ellipses-outline", route: "DiscordSettings" },
            { label: "LLM", icon: "sparkles-outline", route: "LLMSettings" },
        ],
    },
    {
        label: "Tools",
        items: [
            { label: "Event Log", icon: "eye-outline", route: "EventLogVisualizer" },
            { label: "Debug", icon: "bug-outline", route: "DebugSettings" },
        ],
    },
]

/** Lookup table from route name to drawer label. Built from `SECTIONS` so chips stay in sync. */
const ROUTE_LABELS: Record<string, string> = (() => {
    const out: Record<string, string> = {}
    for (const section of SECTIONS) {
        for (const item of section.items) {
            out[item.route] = item.label
            if (item.children) {
                for (const child of item.children) {
                    out[child.route] = child.label
                }
            }
        }
    }
    return out
})()

/** Routes that live under the Settings stack navigator. Used to dispatch nested navigation. */
const SETTINGS_STACK_ROUTES = new Set<string>([
    "SettingsMain",
    "TrainingSettings",
    "TrainingEventSettings",
    "RacingSettings",
    "ScheduleScreen",
    "Skills",
    "EventLogVisualizer",
    "ImportSettingsPreview",
    "ScenarioOverridesSettings",
    "DebugSettings",
    "DiscordSettings",
    "LLMSettings",
])

/**
 * Custom drawer content that renders a sectioned navigation sidebar. Includes a search-shortcut
 * row that dispatches to Home with an `openSearch` token, a recently-visited chips strip backed
 * by SQLite, and labelled sections with expandable parents that use distinct chevron hit targets.
 *
 * @param props The drawer content props from React Navigation.
 * @returns The rendered drawer sidebar.
 */
const DrawerContent: React.FC<DrawerContentComponentProps> = (props) => {
    const { colors } = useTheme()
    const { state, navigation } = props
    const { appVersion } = useContext(BotMetaContext)
    const drawerStatus = useDrawerStatus()
    const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set())
    const [recentRoutes, setRecentRoutes] = useState<string[]>([])
    const previousDrawerStatus = useRef<string | undefined>(undefined)
    const recentRoutesRef = useRef<string[]>([])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: {
                    flex: 1,
                    backgroundColor: colors.surface,
                },
                header: {
                    paddingBottom: 12,
                    paddingHorizontal: SPACING.lg,
                    borderBottomWidth: 1,
                    borderBottomColor: colors.borderHair,
                    flexDirection: "row",
                    alignItems: "center",
                    justifyContent: "space-between",
                },
                headerTextContainer: {
                    flex: 1,
                    justifyContent: "center",
                },
                headerTitle: {
                    fontSize: 24,
                    fontWeight: "bold",
                    color: colors.text,
                    marginBottom: 4,
                },
                headerSubtitle: {
                    fontSize: 14,
                    color: colors.textMuted,
                },
                recentStrip: {
                    paddingHorizontal: SPACING.md,
                    paddingVertical: SPACING.sm,
                    flexGrow: 0,
                    flexShrink: 0,
                },
                recentChip: {
                    backgroundColor: colors.surfaceRaised,
                    borderRadius: 999,
                    paddingHorizontal: SPACING.md,
                    paddingVertical: SPACING.xs + 2,
                    marginRight: SPACING.sm,
                    overflow: "hidden",
                    maxWidth: 90,
                    flexShrink: 0,
                },
                recentChipText: {
                    ...TYPE.caption,
                    color: colors.text,
                },
                section: {
                    paddingHorizontal: SPACING.md,
                    paddingTop: SPACING.md,
                },
                menuItem: {
                    flexDirection: "row",
                    alignItems: "center",
                    paddingVertical: 14,
                    paddingHorizontal: SPACING.md,
                    borderRadius: 10,
                    overflow: "hidden",
                },
                menuItemActive: {
                    backgroundColor: colors.surfaceRaised,
                },
                menuItemIcon: {
                    marginRight: SPACING.md,
                    width: 24,
                    alignItems: "center",
                },
                menuItemText: {
                    ...TYPE.body,
                    color: colors.text,
                    flex: 1,
                },
                menuItemTextActive: {
                    color: colors.brand,
                    fontWeight: "600",
                },
                chevronButton: { ...circularPress(44), marginLeft: SPACING.sm },
                childRow: {
                    flexDirection: "row",
                    alignItems: "center",
                    paddingVertical: 10,
                    paddingLeft: 44,
                    paddingRight: SPACING.md,
                    borderRadius: 10,
                    overflow: "hidden",
                },
                childRowActive: {
                    backgroundColor: colors.surfaceRaised,
                },
                childIcon: {
                    marginRight: SPACING.sm,
                    width: 20,
                    alignItems: "center",
                },
                childText: {
                    ...TYPE.body,
                    fontSize: 13,
                    color: colors.text,
                    flex: 1,
                },
                childTextActive: {
                    color: colors.brand,
                    fontWeight: "600",
                },
                footer: {
                    flexDirection: "row",
                    justifyContent: "center",
                    gap: SPACING.md,
                    padding: SPACING.lg,
                    borderTopWidth: 1,
                    borderTopColor: colors.borderHair,
                },
                footerIconButton: {
                    ...circularPress(48),
                    backgroundColor: colors.surfaceRaised,
                },
            }),
        [colors]
    )

    /**
     * Resolves the active screen, accounting for the nested Settings stack so chips/highlight track
     * the actual visible page.
     * @returns The current active screen name.
     */
    const getCurrentActiveScreen = useCallback((): string => {
        const drawerRoute = state.routes[state.index]
        if (drawerRoute?.name === "Settings") {
            const nestedState = drawerRoute.state
            if (nestedState?.routes && nestedState.index !== undefined) {
                return nestedState.routes[nestedState.index]?.name || "SettingsMain"
            }
            return "SettingsMain"
        }
        return drawerRoute?.name || "Home"
    }, [state.index, state.routes])

    // Hydrate recent routes once on mount from SQLite.
    useEffect(() => {
        let cancelled = false
        ;(async () => {
            try {
                const stored = await databaseManager.loadSetting(MISC_CATEGORY, RECENT_PAGES_KEY)
                if (cancelled) return
                if (Array.isArray(stored)) {
                    const sanitised = stored.filter((r): r is string => typeof r === "string" && r in ROUTE_LABELS).slice(0, RECENT_PAGES_STORE_CAP)
                    setRecentRoutes(sanitised)
                    recentRoutesRef.current = sanitised
                }
            } catch {
                // Best-effort hydrate. A missing row is normal on first launch.
            }
        })()
        return () => {
            cancelled = true
        }
    }, [])

    // Track the current page and update the recent-routes list whenever it changes.
    useEffect(() => {
        const currentScreen = getCurrentActiveScreen()
        if (!(currentScreen in ROUTE_LABELS)) return
        const prev = recentRoutesRef.current
        if (prev[0] === currentScreen) return
        const next = [currentScreen, ...prev.filter((r) => r !== currentScreen)].slice(0, RECENT_PAGES_STORE_CAP)
        recentRoutesRef.current = next
        setRecentRoutes(next)
        databaseManager.saveSetting(MISC_CATEGORY, RECENT_PAGES_KEY, next, true).catch(() => {
            // SQLite failures here are non-fatal. The list will simply not persist across launches.
        })
    }, [state.index, state.routes, getCurrentActiveScreen])

    // Auto-expand parents that contain the current screen, while preserving the user's manual expansions.
    useEffect(() => {
        const drawerJustOpened = previousDrawerStatus.current !== "open" && drawerStatus === "open"
        previousDrawerStatus.current = drawerStatus

        const currentScreen = getCurrentActiveScreen()
        const toAdd = new Set<string>()
        for (const section of SECTIONS) {
            for (const item of section.items) {
                if (item.children?.some((child) => child.route === currentScreen)) {
                    toAdd.add(item.route)
                }
            }
        }

        if (toAdd.size > 0 || drawerJustOpened) {
            setExpandedSections((prev) => {
                const merged = new Set(prev)
                toAdd.forEach((r) => merged.add(r))
                return merged
            })
        }
    }, [state.index, state.routes, drawerStatus, getCurrentActiveScreen])

    /**
     * Toggles the expanded state of a parent row.
     * @param routeName The route name of the parent whose children should expand or collapse.
     */
    const toggleSection = useCallback((routeName: string) => {
        setExpandedSections((prev) => {
            const newSet = new Set(prev)
            if (newSet.has(routeName)) {
                newSet.delete(routeName)
            } else {
                newSet.add(routeName)
            }
            return newSet
        })
    }, [])

    /**
     * Dispatches navigation to a route while closing the drawer. Settings-stack screens are wrapped
     * in a nested navigate so the Settings stack renders the right sub-page.
     * @param routeName The drawer or stack screen to navigate to.
     * @param params Optional params forwarded to the destination screen.
     */
    const handleNavigation = useCallback(
        (routeName: string, params?: Record<string, unknown>) => {
            markNavigationStart(routeName)
            navigation.closeDrawer()
            markNavigationPhase(routeName, "drawer_closed")

            setTimeout(() => {
                markNavigationPhase(routeName, "dispatch")
                if (routeName === "Home") {
                    navigation.dispatch(CommonActions.navigate({ name: "Home", params }))
                } else if (routeName === "Chat") {
                    navigation.dispatch(CommonActions.navigate({ name: "Chat", params }))
                } else if (SETTINGS_STACK_ROUTES.has(routeName)) {
                    navigation.dispatch(
                        CommonActions.navigate({
                            name: "Settings",
                            params: { screen: routeName, initial: false, params },
                        })
                    )
                } else {
                    navigation.dispatch(CommonActions.navigate({ name: routeName, params }))
                }
            }, 0)
        },
        [navigation]
    )

    /**
     * Checks whether a route is the currently visible screen.
     * @param routeName The route to check.
     * @returns True if the route matches the current visible screen.
     */
    const isRouteActive = useCallback(
        (routeName: string) => {
            return getCurrentActiveScreen() === routeName
        },
        [getCurrentActiveScreen]
    )

    /**
     * Renders a top-level drawer row. Expandable rows split label-press and chevron-press into
     * sibling Pressables so taps cannot conflict.
     * @param item The drawer item to render.
     * @returns The row plus any expanded children.
     */
    const renderItem = (item: DrawerItem) => {
        const isActive = isRouteActive(item.route)
        const isExpanded = expandedSections.has(item.route)
        const hasChildren = !!item.children && item.children.length > 0

        return (
            <View key={item.route}>
                <View style={[{ flexDirection: "row", alignItems: "center", borderRadius: 10, overflow: "hidden" }, isActive && styles.menuItemActive]}>
                    <Pressable
                        style={[styles.menuItem, { flex: 1, paddingRight: hasChildren ? 44 + SPACING.sm + SPACING.md : undefined }]}
                        android_ripple={{ color: colors.ripple, foreground: true }}
                        onPress={() => handleNavigation(item.route)}
                    >
                        <View style={styles.menuItemIcon}>
                            <Ionicons name={item.icon as any} size={22} color={isActive ? colors.brand : colors.text} />
                        </View>
                        <Text style={[styles.menuItemText, isActive && styles.menuItemTextActive]}>{item.label}</Text>
                    </Pressable>
                    {hasChildren && (
                        <Pressable
                            onPress={() => toggleSection(item.route)}
                            style={[styles.chevronButton, { position: "absolute", right: 0, top: "50%", marginTop: -22, marginLeft: 0 }]}
                            hitSlop={12}
                            android_ripple={{ color: colors.ripple, foreground: true }}
                        >
                            <Ionicons name={isExpanded ? "chevron-up" : "chevron-down"} size={20} color={colors.textMuted} />
                        </Pressable>
                    )}
                </View>
                {hasChildren && isExpanded && (
                    <View>
                        {item.children!.map((child) => {
                            const childActive = isRouteActive(child.route)
                            return (
                                <Pressable
                                    key={child.route}
                                    style={[styles.childRow, childActive && styles.childRowActive]}
                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                    onPress={() => handleNavigation(child.route)}
                                >
                                    <View style={styles.childIcon}>
                                        <Ionicons name={child.icon as any} size={16} color={childActive ? colors.brand : colors.textMuted} />
                                    </View>
                                    <Text style={[styles.childText, childActive && styles.childTextActive]}>{child.label}</Text>
                                </Pressable>
                            )
                        })}
                    </View>
                )}
            </View>
        )
    }

    const visibleRecent = recentRoutes.slice(0, RECENT_PAGES_RENDER_CAP)

    return (
        <>
            <DrawerContentScrollView {...props} style={styles.container} contentContainerStyle={{ flexGrow: 1, paddingTop: SPACING.md }}>
                <View style={styles.header}>
                    <View style={styles.headerTextContainer}>
                        <Text style={styles.headerTitle}>Uma Android Automation</Text>
                        <Text style={styles.headerSubtitle}>{appVersion}</Text>
                    </View>
                    <Avatar alt="UAA" style={{ width: 72, height: 72 }}>
                        <AvatarImage source={require("../../assets/app_icon.png")} />
                    </Avatar>
                </View>

                {visibleRecent.length > 0 && (
                    <View style={[styles.recentStrip, { flexDirection: "row", alignItems: "center" }]}>
                        {visibleRecent.map((route) => (
                            <Pressable key={route} style={styles.recentChip} android_ripple={{ color: colors.ripple, foreground: true }} onPress={() => handleNavigation(route)}>
                                <Text style={styles.recentChipText} numberOfLines={1} ellipsizeMode="tail">
                                    {ROUTE_LABELS[route]}
                                </Text>
                            </Pressable>
                        ))}
                    </View>
                )}

                {SECTIONS.map((section) => (
                    <View key={section.label} style={styles.section}>
                        <SectionLabel label={section.label} />
                        {section.items.map(renderItem)}
                    </View>
                ))}
            </DrawerContentScrollView>
            <View style={styles.footer}>
                <Pressable style={styles.footerIconButton} android_ripple={{ color: colors.ripple, foreground: true }} onPress={() => Linking.openURL(GITHUB_URL)}>
                    <Ionicons name="logo-github" size={24} color={colors.text} />
                </Pressable>
                <Pressable
                    style={styles.footerIconButton}
                    android_ripple={{ color: colors.ripple, foreground: true }}
                    accessibilityLabel="View current changelog"
                    onPress={() => {
                        NativeModules.StartModule.showChangelog().catch(() => {
                            // Swallow failures; the dialog is informational and not critical.
                        })
                    }}
                >
                    <Ionicons name="newspaper-outline" size={24} color={colors.text} />
                </Pressable>
            </View>
        </>
    )
}

export default React.memo(DrawerContent)
