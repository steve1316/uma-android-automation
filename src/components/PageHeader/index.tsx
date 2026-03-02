import React, { useMemo, useState, useRef, useEffect } from "react"
import { View, Text, StyleSheet, TouchableOpacity, ViewStyle, TextInput, Animated, Keyboard, ScrollView } from "react-native"
import { useNavigation, DrawerActions } from "@react-navigation/native"
import { Ionicons } from "@expo/vector-icons"
import { useTheme } from "../../context/ThemeContext"
import { useSearchRegistry } from "../../context/SearchRegistryContext"
import { Portal } from "@rn-primitives/portal"

interface PageHeaderProps {
    /** The title to display in the header. */
    title: string
    /** Whether to show the Home button (default: true). */
    showHomeButton?: boolean
    /** Optional React node to display on the left side of the header, next to the search icon. This is used if a standard string title is not sufficient. */
    titleComponent?: React.ReactNode
    /** Optional React node to display in the center of the header. */
    centerComponent?: React.ReactNode
    /** Optional right-side component to render (e.g., `ThemeToggle`). */
    rightComponent?: React.ReactNode
    /** Optional additional styles for the header container. */
    style?: ViewStyle
}

// This is a mapping of page names to their display names. This is used to display the page name in the header.
const pageNameMapping: Record<string, string> = {
    SettingsMain: "General Settings",
    TrainingSettings: "Training",
    TrainingEventSettings: "Training Events",
    OCRSettings: "OCR",
    RacingSettings: "Racing",
    RacingPlanSettings: "Racing Plan",
    SkillSettings: "Skills",
    DebugSettings: "Debug",
    EventLogVisualizer: "Event Log Visualizer",
    ImportSettingsPreview: "Import Settings Preview",
    SkillPlanSettingsSkillPointCheck: "Skill Plan: Skill Point Check",
    SkillPlanSettingsPreFinals: "Skill Plan: Pre-Finals",
    SkillPlanSettingsCareerComplete: "Skill Plan: Career Complete",
}

/**
 * A component that highlights a specific query within a text.
 * @param text The text to highlight.
 * @param query The query to highlight.
 * @param style The style to apply to the highlighted text.
 * @param highlightColor The color to use for highlighting.
 */
const HighlightedText = ({ text, query, style, highlightColor }: { text: string; query: string; style?: any; highlightColor: string }) => {
    if (!query) return <Text style={style}>{text}</Text>

    // Split text on the query while preserving the matched original parts.
    const parts = text.split(new RegExp(`(${query})`, "gi"))

    return (
        <Text style={style}>
            {parts.map((part, idx) =>
                part.toLowerCase() === query.toLowerCase() ? (
                    <Text key={idx} style={[{ color: highlightColor, fontWeight: "bold" }]}>
                        {part}
                    </Text>
                ) : (
                    <Text key={idx}>{part}</Text>
                )
            )}
        </Text>
    )
}

/**
 * A reusable header component for pages that includes:
 * - A hamburger menu button to open the drawer.
 * - A Home button for quick navigation to the Home page.
 * - A page title.
 * - An optional right-side component.
 * - A search bar.
 *
 * @param title The title text for the header.
 * @param showHomeButton Whether to show the Home button.
 * @param titleComponent Optional React node to display in the center of the header.
 * @param centerComponent Optional React node to display in the center of the header.
 * @param rightComponent Optional React node to display in the right side of the header.
 * @param style Optional custom style for the header container.
 */
const PageHeader = ({ title, showHomeButton = true, titleComponent, centerComponent, rightComponent, style }: PageHeaderProps) => {
    const { colors } = useTheme()
    const navigation = useNavigation()

    const [isSearching, setIsSearching] = useState(false)
    const [searchQuery, setSearchQuery] = useState("")
    const searchInputRef = useRef<TextInput>(null)
    const fadeAnim = useRef(new Animated.Value(0)).current

    const { searchIndex } = useSearchRegistry()

    useEffect(() => {
        // Fade in or out for the search results based on the query length.
        if (searchQuery.length > 0) {
            Animated.timing(fadeAnim, {
                toValue: 1,
                duration: 200,
                useNativeDriver: true,
            }).start()
        } else {
            Animated.timing(fadeAnim, {
                toValue: 0,
                duration: 200,
                useNativeDriver: true,
            }).start()
        }
    }, [searchQuery, fadeAnim])

    /**
     * Opens the drawer navigation.
     */
    const openDrawer = () => {
        navigation.dispatch(DrawerActions.openDrawer())
    }

    /**
     * Navigates to the Home page.
     */
    const goHome = () => {
        navigation.navigate("Home" as never)
    }

    /**
     * Toggles the search bar.
     */
    const handleSearchToggle = () => {
        if (isSearching) {
            // Reset search state and dismiss keyboard.
            setIsSearching(false)
            setSearchQuery("")
            Keyboard.dismiss()
        } else {
            // Open search bar and focus on the search input.
            setIsSearching(true)
            setTimeout(() => searchInputRef.current?.focus(), 100)
        }
    }

    /**
     * Filters the search results based on the search query.
     * @returns An array of grouped search results.
     */
    const filteredResults = useMemo(() => {
        if (!searchQuery) return []
        const lowerQuery = searchQuery.toLowerCase()
        // Flatten the search index and filter by the search query.
        const flatResults = Object.values(searchIndex).filter((item) => item.title.toLowerCase().includes(lowerQuery) || item.description.toLowerCase().includes(lowerQuery))

        // Group results by their human-readable page name.
        const grouped: Record<string, any[]> = {}
        flatResults.forEach((item) => {
            const pageName = pageNameMapping[item.page] || item.page
            if (!grouped[pageName]) {
                grouped[pageName] = []
            }
            grouped[pageName].push(item)
        })

        return Object.entries(grouped).map(([title, data]) => ({
            title,
            data,
        }))
    }, [searchQuery, searchIndex])

    /**
     * Handles the press event for a search result.
     * @param item The search result item.
     */
    const handleResultPress = (item: any) => {
        // Reset search state and dismiss keyboard.
        setIsSearching(false)
        setSearchQuery("")
        Keyboard.dismiss()

        // Set navigation parameters with the target ID set to the item's ID and the fallback target ID set to the item's parent ID.
        const navParams = {
            targetId: item.id,
            fallbackTargetId: item.parentId || undefined,
        }

        // List of pages that are nested inside the "Settings" stack.
        const settingsPages = [
            "SettingsMain",
            "TrainingSettings",
            "TrainingEventSettings",
            "OCRSettings",
            "RacingSettings",
            "RacingPlanSettings",
            "SkillSettings",
            "EventLogVisualizer",
            "ImportSettingsPreview",
            "DebugSettings",
        ]

        // Check if the target page is a regular Settings page or a dynamic Skill Plan Settings page.
        const isSettingsPage = settingsPages.includes(item.page) || item.page.startsWith("SkillPlanSettings")

        if (isSettingsPage) {
            // Use nested navigation to reach settings from outside the stack (e.g., from Home).
            ;(navigation.navigate as any)("Settings", {
                screen: item.page,
                params: navParams,
            })
        } else {
            // Fallback for top-level pages.
            ;(navigation.navigate as any)(item.page, navParams)
        }
    }

    const styles = useMemo(
        () =>
            StyleSheet.create({
                header: {
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "center",
                    paddingTop: 8,
                    paddingBottom: 12,
                },
                headerLeft: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: 8,
                },
                headerCenter: {
                    position: "absolute",
                    left: 0,
                    right: 0,
                    alignItems: "center",
                    justifyContent: "center",
                    pointerEvents: "box-none",
                },
                menuButton: {
                    padding: 8,
                    borderRadius: 8,
                },
                homeButton: {
                    padding: 8,
                    borderRadius: 8,
                },
                title: {
                    fontSize: 24,
                    fontWeight: "bold",
                    color: colors.foreground,
                },
                searchContainer: {
                    flex: 1,
                    flexDirection: "row",
                    alignItems: "center",
                    backgroundColor: colors.card,
                    borderRadius: 8,
                    paddingHorizontal: 10,
                    marginLeft: 10,
                    height: 40,
                },
                searchInput: {
                    flex: 1,
                    color: colors.foreground,
                    marginLeft: 8,
                    fontSize: 16,
                },
                overlay: {
                    position: "absolute",
                    top: 55,
                    left: 0,
                    right: 0,
                    bottom: 0,
                    backgroundColor: colors.background,
                    zIndex: 100,
                    padding: 10,
                },
                resultList: {
                    flex: 1,
                },
                resultItem: {
                    padding: 16,
                    borderBottomWidth: 1,
                    borderBottomColor: colors.border,
                },
                resultTitle: {
                    fontSize: 16,
                    fontWeight: "bold",
                    color: colors.foreground,
                },
                resultDescription: {
                    fontSize: 14,
                    color: colors.mutedForeground,
                    marginTop: 4,
                },
                resultHeader: {
                    backgroundColor: colors.muted,
                    paddingHorizontal: 16,
                    paddingVertical: 8,
                    marginTop: 8,
                    borderRadius: 4,
                },
                resultHeaderText: {
                    fontSize: 12,
                    fontWeight: "bold",
                    color: colors.mutedForeground,
                    textTransform: "uppercase",
                    letterSpacing: 1,
                },
            }),
        [colors]
    )

    return (
        <View style={[{ zIndex: isSearching ? 100 : 1 }, style]}>
            <View style={styles.header}>
                {!isSearching && centerComponent && <View style={styles.headerCenter}>{centerComponent}</View>}
                <View style={[styles.headerLeft, { flex: isSearching ? 1 : undefined }]}>
                    {/* Hamburger menu button */}
                    <TouchableOpacity onPress={openDrawer} style={styles.menuButton} activeOpacity={0.7}>
                        <Ionicons name="menu" size={28} color={colors.foreground} />
                    </TouchableOpacity>

                    {/* Home button */}
                    {!isSearching && showHomeButton && (
                        <TouchableOpacity onPress={goHome} style={styles.homeButton} activeOpacity={0.7}>
                            <Ionicons name="home" size={24} color={colors.foreground} />
                        </TouchableOpacity>
                    )}

                    {/* Search button */}
                    {!isSearching && (
                        <TouchableOpacity onPress={handleSearchToggle} style={styles.homeButton} activeOpacity={0.7}>
                            <Ionicons name="search" size={24} color={colors.foreground} />
                        </TouchableOpacity>
                    )}

                    {/* Page title */}
                    {!isSearching && !!title && <Text style={styles.title}>{title}</Text>}
                    {!isSearching && titleComponent}

                    {/* Search bar */}
                    {isSearching && (
                        <View style={styles.searchContainer}>
                            <Ionicons name="search" size={20} color={colors.mutedForeground} />
                            <TextInput
                                ref={searchInputRef}
                                style={styles.searchInput}
                                placeholder="Search settings..."
                                placeholderTextColor={colors.mutedForeground}
                                value={searchQuery}
                                onChangeText={setSearchQuery}
                            />
                            <TouchableOpacity onPress={handleSearchToggle} activeOpacity={0.7} style={{ padding: 4 }}>
                                <Ionicons name="close" size={20} color={colors.foreground} />
                            </TouchableOpacity>
                        </View>
                    )}
                </View>
                {!isSearching && rightComponent && <View>{rightComponent}</View>}
            </View>

            {/* Fading Overlay for Search Results */}
            {isSearching && searchQuery.length > 0 && (
                <Portal name="search-results">
                    <View style={{ position: "absolute", top: 0, left: 0, right: 0, bottom: 0, zIndex: 1000 }} pointerEvents="box-none">
                        <Animated.View style={[styles.overlay, { opacity: fadeAnim, top: 80 }]}>
                            {/* Search results list */}
                            <ScrollView keyboardShouldPersistTaps="handled" style={styles.resultList} contentContainerStyle={{ paddingBottom: 100 }}>
                                {filteredResults.length > 0 ? (
                                    filteredResults.map((section) => (
                                        <View key={section.title}>
                                            {/* Search results header */}
                                            <View style={styles.resultHeader}>
                                                <Text style={styles.resultHeaderText}>{section.title}</Text>
                                            </View>
                                            {/* Search results items */}
                                            {section.data.map((item) => (
                                                <TouchableOpacity key={item.id} style={styles.resultItem} onPress={() => handleResultPress(item)}>
                                                    <HighlightedText text={item.title} query={searchQuery} style={styles.resultTitle} highlightColor={colors.primary} />
                                                    <HighlightedText text={item.description} query={searchQuery} style={styles.resultDescription} highlightColor={colors.primary} />
                                                </TouchableOpacity>
                                            ))}
                                        </View>
                                    ))
                                ) : (
                                    <Text style={[styles.resultTitle, { textAlign: "center", marginTop: 20 }]}>No results found.</Text>
                                )}
                            </ScrollView>
                        </Animated.View>
                    </View>
                </Portal>
            )}
        </View>
    )
}

export default React.memo(PageHeader)
