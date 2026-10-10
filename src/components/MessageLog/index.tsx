import { useContext, useState, useMemo, useCallback, memo, useEffect, useRef } from "react"
import { MessageLogDataContext } from "../../context/MessageLogContext"
import { BotMetaContext, useSettingsSnapshot } from "../../context/BotStateContext"
import { useSettings } from "../../context/SettingsContext"
import { databaseManager } from "../../lib/database"
import { buildSettingsBanner } from "../../lib/messageLog/buildSettingsBanner"
import { StyleSheet, Text, View, TextInput, Pressable, Animated } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import type { ThemeColors } from "../../lib/theme"
import * as Clipboard from "expo-clipboard"
import { Copy, Plus, Minus, Type, X, ArrowUp, ArrowDown, ArrowUpAZ, ArrowDownZA } from "lucide-react-native"
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover"
import { AlertDialog, AlertDialogAction, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "../ui/alert-dialog"
import { CustomScrollView } from "../CustomScrollView"
import { GlassFab } from "../ui/glass-fab"

const createStyles = (colors: ThemeColors) =>
    StyleSheet.create({
        logInnerContainer: {
            flex: 1,
            width: "100%",
            backgroundColor: colors.surface,
            borderStyle: "solid",
            borderRadius: 25,
            marginBottom: 10,
            elevation: 10,
            position: "relative",
        },
        searchContainer: {
            flexDirection: "row",
            alignItems: "center",
            paddingHorizontal: 15,
            paddingVertical: 10,
            backgroundColor: colors.surfaceRaised,
            borderTopLeftRadius: 25,
            borderTopRightRadius: 25,
        },
        searchInput: {
            flex: 1,
            backgroundColor: "transparent",
            color: colors.text,
            paddingHorizontal: 12,
            paddingVertical: 8,
            fontSize: 12,
        },
        searchInputContainer: {
            flex: 1,
            flexDirection: "row",
            alignItems: "center",
            backgroundColor: colors.bg,
            borderRadius: 8,
            marginRight: 8,
        },
        clearButton: {
            padding: 4,
            marginRight: 8,
        },
        actionButton: {
            padding: 8,
            borderRadius: 6,
            backgroundColor: colors.surfaceRaised,
            marginLeft: 4,
        },
        logContainer: {
            flex: 1,
            paddingHorizontal: 15,
            paddingBottom: 10,
            marginTop: 10,
        },
        logText: {
            color: colors.text,
            fontFamily: "monospace",
        },
        logTextWarning: {
            color: colors.warning,
            fontFamily: "monospace",
        },
        logTextError: {
            color: colors.error,
            fontFamily: "monospace",
        },
        logItem: {
            paddingVertical: 1,
            paddingHorizontal: 2,
        },
        popoverContentContainer: {
            flexDirection: "row",
            alignItems: "center",
            gap: 12,
        },
        popoverButtonContainer: {
            flexDirection: "row",
            gap: 8,
        },
        popoverButton: {
            alignItems: "center",
            justifyContent: "center",
            paddingVertical: 6,
            paddingHorizontal: 8,
            borderRadius: 4,
            backgroundColor: colors.surfaceRaised,
            width: 28,
            height: 28,
        },
        fontSizeDisplay: {
            color: colors.text,
            fontSize: 12,
            fontWeight: "600",
        },
        floatingButtonContainer: {
            position: "absolute",
            bottom: 15,
            right: 15,
            flexDirection: "column",
            gap: 6,
            zIndex: 1000,
        },
    })

interface LogMessage {
    /** Unique identifier for the log message. */
    id: string
    /** The text content of the log message. */
    text: string
    /** The message type used for color-coding (normal, warning, error). */
    type: "normal" | "warning" | "error"
    /** Optional sequential message ID from the bot service. */
    messageId?: number
}

/**
 * Memoized individual log entry component for virtualized list rendering.
 * Supports color-coded text, long-press copy, and optional message ID display.
 * @param item The log message to display.
 * @param fontSize The font size to use for the log message.
 * @param onLongPress The function to call when the log message is long-pressed.
 * @param enableMessageIdDisplay Whether to display the message ID.
 */
const LogItem = memo(({ item, fontSize, onLongPress, enableMessageIdDisplay }: { item: LogMessage; fontSize: number; onLongPress: (message: string) => void; enableMessageIdDisplay: boolean }) => {
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors), [colors])
    /**
     * Returns the style for the log message based on its type.
     * @returns The style for the log message.
     */
    const getTextStyle = useCallback(() => {
        const baseStyle = {
            fontSize: fontSize,
            lineHeight: fontSize * 1.5,
        }

        switch (item.type) {
            case "warning":
                return { ...styles.logTextWarning, ...baseStyle }
            case "error":
                return { ...styles.logTextError, ...baseStyle }
            default:
                return { ...styles.logText, ...baseStyle }
        }
    }, [item.type, fontSize, styles])

    /**
     * Trim leading newlines when message ID is present to maintain alignment.
     * @returns The display text for the log message.
     */
    const displayText = useMemo(() => {
        if (enableMessageIdDisplay && item.messageId !== undefined) {
            // Remove leading newlines and whitespace to keep alignment with message ID.
            return item.text.replace(/^[\n\r\s]+/, "")
        }
        return item.text
    }, [item.text, item.messageId, enableMessageIdDisplay])

    return (
        <Pressable style={styles.logItem} onLongPress={() => onLongPress(item.text)} delayLongPress={500} android_ripple={{ color: colors.ripple, foreground: true }}>
            <View style={{ flexDirection: "row", alignItems: "flex-start" }}>
                {enableMessageIdDisplay && item.messageId !== undefined && <Text style={[getTextStyle(), { color: colors.textSubtle, minWidth: 40 }]}>[{item.messageId}]</Text>}
                <Text style={[getTextStyle(), { flex: 1, flexShrink: 1 }]}>{displayText}</Text>
            </View>
        </Pressable>
    )
})

/** Props for `MessageLog`. */
interface MessageLogProps {
    /** Scroll request from the run result card. Each new `nonce` scrolls to the first line containing `term`, or to the bottom when it is empty or not found. */
    jumpRequest?: { term: string; nonce: number } | null
}

/**
 * A full-featured message log display component with search, sort, copy, and font size controls.
 * Uses virtualized rendering via `FlashList` for performant display of large log volumes.
 * Supports color-coded messages (normal, warning, error), floating scroll buttons,
 * and a formatted settings summary as the intro message.
 * @param jumpRequest Scroll request from the run result card.
 * @returns The log view.
 */
const MessageLog = ({ jumpRequest }: MessageLogProps) => {
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors), [colors])
    const mlc = useContext(MessageLogDataContext)
    const { appName, appVersion, setSettings } = useContext(BotMetaContext)
    const settings = useSettingsSnapshot()
    const { saveSettingsImmediate } = useSettings()
    const [searchQuery, setSearchQuery] = useState("")
    const [showErrorDialog, setShowErrorDialog] = useState(false)
    const [errorMessage, setErrorMessage] = useState("")
    const [sortOrder, setSortOrder] = useState<"asc" | "desc">("asc")
    const scrollViewRef = useRef<any>(null)
    const [scrollOffset, setScrollOffset] = useState(0)
    const [contentHeight, setContentHeight] = useState(0)
    const [viewportHeight, setViewportHeight] = useState(0)

    const fontSize = settings.misc.messageLogFontSize
    const maxFontSize = 24
    const minFontSize = 8

    // Animated values for smooth scroll button transitions.
    const topButtonOpacity = useRef(new Animated.Value(0)).current
    const bottomButtonOpacity = useRef(new Animated.Value(0)).current
    const topHideTimeoutRef = useRef<NodeJS.Timeout | null>(null)
    const bottomHideTimeoutRef = useRef<NodeJS.Timeout | null>(null)

    // Determine if scrolling is needed and scroll buttons visibility.
    const needsScrolling = contentHeight > viewportHeight + 10 // Add buffer to account for rounding.
    const scrollThreshold = 50 // Increased threshold for more reliable detection.
    const maxScrollOffset = Math.max(0, contentHeight - viewportHeight)

    // Check if at top or bottom of log.
    const isAtTop = scrollOffset <= scrollThreshold
    const isAtBottom = needsScrolling && maxScrollOffset > 0 && scrollOffset >= Math.max(0, maxScrollOffset - scrollThreshold)

    const showScrollButtons = needsScrolling && contentHeight > 0 && viewportHeight > 0
    const showScrollToTop = showScrollButtons && !isAtTop
    const showScrollToBottom = showScrollButtons && !isAtBottom

    /**
     * Show error dialog.
     * @param message Error message to display.
     */
    const showError = useCallback((message: string) => {
        setErrorMessage(message)
        setShowErrorDialog(true)
    }, [])

    // Debounced state for the formatted settings banner. Recomputing the ~30-line template
    // literal (including `JSON.parse` calls on the smart-race-solver fields and `Object.keys(...)`
    // over each override map) synchronously on every settings change was making toggles feel
    // sluggish once the user imported a populated settings file. We now compute it 250ms after the last settings
    // change, off the toggle's render commit. The intro/log path keeps using the previous
    // value until the new one lands; downstream memos bail out via `Object.is`.
    const [formattedSettingsString, setFormattedSettingsString] = useState<string>(() => buildSettingsBanner(settings))
    const formattedStringTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
    useEffect(() => {
        if (formattedStringTimerRef.current) clearTimeout(formattedStringTimerRef.current)
        formattedStringTimerRef.current = setTimeout(() => {
            const next = buildSettingsBanner(settings)
            setFormattedSettingsString((prev) => (prev === next ? prev : next))
        }, 250)
        return () => {
            if (formattedStringTimerRef.current) clearTimeout(formattedStringTimerRef.current)
        }
    }, [settings])

    // Persist the formatted string directly to SQLite. The Kotlin runtime is the only consumer
    // (via SettingsHelper.getStringSetting), so writing through `setSettings` would just trigger
    // an extra full re-render of every BotStateContext consumer for each user toggle.
    const formattedStringWriteTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
    useEffect(() => {
        if (formattedStringWriteTimer.current) clearTimeout(formattedStringWriteTimer.current)
        formattedStringWriteTimer.current = setTimeout(() => {
            databaseManager.saveSetting("misc", "formattedSettingsString", formattedSettingsString, true).catch(() => {})
        }, 250)
        return () => {
            if (formattedStringWriteTimer.current) clearTimeout(formattedStringWriteTimer.current)
        }
    }, [formattedSettingsString])

    /**
     * Create the intro message for the log.
     * @returns The intro message.
     */
    const introMessage = useMemo(() => {
        const hasLogs = mlc.messageLog.length > 0
        const baseMessage = `****************************************\nWelcome to ${appName} v${appVersion}\n****************************************`

        // Don't add formattedSettingsString if logs are already present (Android already copied it).
        if (hasLogs) {
            // If logs exist, Android already copied the settings string, so don't include it.
            return baseMessage
        }

        // Only include settings string if enabled and no logs exist yet.
        return settings.misc.enableSettingsDisplay ? `${baseMessage}\n\n${formattedSettingsString}` : baseMessage
    }, [appName, appVersion, settings.misc.enableSettingsDisplay, formattedSettingsString, mlc.messageLog.length])

    /**
     * Process log messages with color coding and virtualization while sorting them by timestamp.
     * @returns Processed log messages.
     */
    const processedMessages = useMemo((): LogMessage[] => {
        // Add intro message as the first item.
        const introLines = introMessage.split("\n")
        const introMessages = introLines.map((line, index) => ({
            id: `intro-${index}`,
            text: line,
            type: "normal" as const,
        }))

        // Process actual log messages and set the type based on the message content.
        const logMessages = mlc.messageLog.map((entry, index) => {
            let type: "normal" | "warning" | "error" = "normal"

            if (entry.message.includes("[ERROR]")) {
                type = "error"
            } else if (entry.message.includes("[WARNING]") || entry.message.includes("[WARN]")) {
                type = "warning"
            }

            return {
                id: `log-${index}-${entry.message.substring(0, 20)}`,
                text: entry.message,
                type,
                messageId: entry.id,
            }
        })

        /**
         * Parse timestamp from message text (format: HH:MM:SS.mmm).
         * @param text The message text to parse.
         * @returns The timestamp in milliseconds.
         */
        const parseTimestamp = (text: string): number => {
            // Match timestamps like "00:00:00.462", allowing optional leading whitespace/newlines.
            const match = text.match(/^\s*(\d{2}):(\d{2}):(\d{2})\.(\d{3})/)
            if (match) {
                const [, hours, minutes, seconds, milliseconds] = match
                return parseInt(hours) * 3600000 + parseInt(minutes) * 60000 + parseInt(seconds) * 1000 + parseInt(milliseconds)
            }
            // Return -1 for messages without valid timestamps (e.g., "--:--:--.---").
            return -1
        }

        // Sort log messages by timestamp (primary) and messageId (secondary/tiebreaker).
        const sortedLogMessages = [...logMessages].sort((a, b) => {
            const timestampA = parseTimestamp(a.text)
            const timestampB = parseTimestamp(b.text)

            // Primary sort by timestamp for chronological order.
            if (timestampA !== timestampB) {
                return sortOrder === "desc" ? timestampB - timestampA : timestampA - timestampB
            }

            // Secondary sort by messageId when timestamps are equal.
            const idA = a.messageId ?? 0
            const idB = b.messageId ?? 0
            return sortOrder === "desc" ? idB - idA : idA - idB
        })

        // Always keep intro message at the top, regardless of sort order.
        return [...introMessages, ...sortedLogMessages]
    }, [mlc.messageLog, introMessage, sortOrder])

    /**
     * Filter messages based on search query (excluding intro messages).
     * @returns Filtered log messages.
     */
    const filteredMessages = useMemo(() => {
        if (!searchQuery.trim()) {
            // Always return a new array reference to ensure FlashList detects the change.
            return [...processedMessages]
        }

        const query = searchQuery.toLowerCase()
        return processedMessages.filter((message) => {
            // Only search log messages, not intro messages.
            if (message.id.startsWith("intro-")) {
                return false
            }
            return message.text.toLowerCase().includes(query)
        })
    }, [processedMessages, searchQuery])

    // Force the CustomScrollView to refresh the FlashList when search is cleared by using a key that changes.
    // This ensures a complete remount when transitioning from searching to having no search query.
    const listKey = useMemo(() => (searchQuery.trim().length === 0 ? "all-messages" : `search-${searchQuery}`), [searchQuery])

    // Scroll to top when data changes (search or sort).
    useEffect(() => {
        // Use setTimeout to ensure the scroll happens after the list has updated.
        const timeoutId = setTimeout(() => {
            scrollViewRef.current?.scrollToOffset({
                offset: 0,
                animated: false,
            })
        }, 0)
        return () => clearTimeout(timeoutId)
    }, [listKey, sortOrder])

    /**
     * Toggle sort order between ascending and descending.
     */
    const toggleSortOrder = useCallback(() => {
        setSortOrder((prev) => (prev === "asc" ? "desc" : "asc"))
    }, [])

    /**
     * Scroll to top of the list.
     */
    const scrollToTop = useCallback(() => {
        scrollViewRef.current?.scrollToOffset({
            offset: 0,
            animated: true,
        })
    }, [])

    /**
     * Scroll to bottom of the list.
     */
    const scrollToBottom = useCallback(() => {
        if (filteredMessages.length > 0) {
            try {
                scrollViewRef.current?.scrollToIndex({
                    index: filteredMessages.length - 1,
                    animated: true,
                })
            } catch (error) {
                // Fallback to scrolling to a large offset if scrollToIndex fails.
                scrollViewRef.current?.scrollToOffset({
                    offset: 999999,
                    animated: true,
                })
            }
        }
    }, [filteredMessages.length])

    // Jump to the requested line when the run result card asks for it.
    useEffect(() => {
        if (!jumpRequest) return
        const index = jumpRequest.term ? filteredMessages.findIndex((m) => m.text.includes(jumpRequest.term)) : -1
        if (index < 0) {
            scrollToBottom()
            return
        }
        try {
            scrollViewRef.current?.scrollToIndex({ index, animated: true, viewPosition: 0 })
        } catch {
            scrollToBottom()
        }
        // Only a new request should scroll, not every new log line.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [jumpRequest?.nonce])

    /**
     * Handle scroll events to track position.
     * @param event The scroll event.
     */
    const handleScroll = useCallback((event: any) => {
        const nativeEvent = event.nativeEvent
        const offset = nativeEvent?.contentOffset?.y ?? 0
        const contentHeight = nativeEvent?.contentSize?.height ?? 0
        const layoutHeight = nativeEvent?.layoutMeasurement?.height ?? 0

        setScrollOffset(Math.max(0, offset))

        // Update content and viewport height from scroll event if available.
        if (contentHeight > 0) {
            setContentHeight(contentHeight)
        }
        if (layoutHeight > 0) {
            setViewportHeight(layoutHeight)
        }
    }, [])

    /**
     * Handle scroll end to get final position.
     * @param event The scroll event.
     */
    const handleScrollEnd = useCallback((event: any) => {
        const nativeEvent = event.nativeEvent
        const offset = nativeEvent?.contentOffset?.y ?? 0
        setScrollOffset(Math.max(0, offset))
    }, [])

    /**
     * Handle content size changes to update content height.
     * @param _width The width of the content (unused).
     * @param height The height of the content.
     */
    const handleContentSizeChange = useCallback((_width: number, height: number) => {
        if (height > 0) {
            setContentHeight(height)
        }
    }, [])

    /**
     * Handle layout changes to update viewport height.
     * @param event The layout event.
     */
    const handleLayout = useCallback((event: any) => {
        const { height } = event.nativeEvent.layout
        if (height > 0) {
            setViewportHeight(height)
        }
    }, [])

    // Animate scroll button visibility with smooth transitions.
    useEffect(() => {
        // Clear any pending timeouts.
        if (topHideTimeoutRef.current) {
            clearTimeout(topHideTimeoutRef.current)
            topHideTimeoutRef.current = null
        }
        if (bottomHideTimeoutRef.current) {
            clearTimeout(bottomHideTimeoutRef.current)
            bottomHideTimeoutRef.current = null
        }

        // Animate top scroll button.
        if (showScrollToTop) {
            Animated.timing(topButtonOpacity, {
                toValue: 1,
                duration: 100,
                useNativeDriver: true,
            }).start()
        } else {
            topHideTimeoutRef.current = setTimeout(() => {
                Animated.timing(topButtonOpacity, {
                    toValue: 0,
                    duration: 100,
                    useNativeDriver: true,
                }).start()
            })
        }

        // Animate bottom scroll button.
        if (showScrollToBottom) {
            Animated.timing(bottomButtonOpacity, {
                toValue: 1,
                duration: 100,
                useNativeDriver: true,
            }).start()
        } else {
            bottomHideTimeoutRef.current = setTimeout(() => {
                Animated.timing(bottomButtonOpacity, {
                    toValue: 0,
                    duration: 100,
                    useNativeDriver: true,
                }).start()
            })
        }

        return () => {
            if (topHideTimeoutRef.current) {
                clearTimeout(topHideTimeoutRef.current)
            }
            if (bottomHideTimeoutRef.current) {
                clearTimeout(bottomHideTimeoutRef.current)
            }
        }
    }, [showScrollToTop, showScrollToBottom, topButtonOpacity, bottomButtonOpacity])

    /**
     * Increase font size and then save it to the settings.
     */
    const increaseFontSize = useCallback(async () => {
        const newFontSize = Math.min(fontSize + 1, maxFontSize)
        const updatedSettings = {
            ...settings,
            misc: { ...settings.misc, messageLogFontSize: newFontSize },
        }
        setSettings(updatedSettings)
        await saveSettingsImmediate(updatedSettings)
    }, [fontSize, settings, setSettings, saveSettingsImmediate])

    /**
     * Decrease font size and then save it to the settings.
     */
    const decreaseFontSize = useCallback(async () => {
        const newFontSize = Math.max(fontSize - 1, minFontSize)
        const updatedSettings = {
            ...settings,
            misc: { ...settings.misc, messageLogFontSize: newFontSize },
        }
        setSettings(updatedSettings)
        await saveSettingsImmediate(updatedSettings)
    }, [fontSize, settings, setSettings, saveSettingsImmediate])

    /**
     * Clear search query.
     */
    const clearSearch = useCallback(() => {
        setSearchQuery("")
    }, [])

    /**
     * Copy all messages to clipboard.
     */
    const copyToClipboard = useCallback(async () => {
        try {
            const allText = introMessage + "\n" + mlc.messageLog.map((entry) => entry.message).join("\n")
            await Clipboard.setStringAsync(allText)
        } catch (error) {
            showError("Failed to copy to clipboard")
        }
    }, [mlc.messageLog, introMessage, showError])

    /**
     * Copy individual message on long press.
     * @param message The message to copy.
     */
    const handleLongPress = useCallback(
        async (message: string) => {
            try {
                await Clipboard.setStringAsync(message)
            } catch (error) {
                showError("Failed to copy message")
            }
        },
        [showError]
    )

    /**
     * Render individual log item.
     * @param item The log item to render.
     * @returns The rendered log item.
     */
    const renderLogItem = useCallback(
        ({ item }: { item: LogMessage }) => <LogItem item={item} fontSize={fontSize} onLongPress={handleLongPress} enableMessageIdDisplay={settings.debug.enableMessageIdDisplay} />,
        [fontSize, handleLongPress, settings.debug.enableMessageIdDisplay]
    )

    /**
     * Key extractor for `FlashList`.
     * @param item The log item to extract the key from.
     * @returns The key for the log item.
     */
    const keyExtractor = useCallback((item: LogMessage) => item.id, [])

    return (
        <View style={styles.logInnerContainer}>
            {/* Search Bar */}
            <View style={styles.searchContainer}>
                <View style={styles.searchInputContainer}>
                    <TextInput
                        style={styles.searchInput}
                        placeholder="Search messages..."
                        placeholderTextColor={colors.textMuted}
                        value={searchQuery}
                        onChangeText={setSearchQuery}
                        autoCorrect={false}
                        autoCapitalize="none"
                    />
                    {searchQuery.length > 0 && (
                        <Pressable style={styles.clearButton} onPress={clearSearch} android_ripple={{ color: colors.ripple, foreground: true }}>
                            <X size={16} color={colors.textMuted} />
                        </Pressable>
                    )}
                </View>
                <Pressable style={styles.actionButton} onPress={copyToClipboard} android_ripple={{ color: colors.ripple, foreground: true }}>
                    <Copy size={16} color={colors.text} />
                </Pressable>
                <Pressable style={styles.actionButton} onPress={toggleSortOrder} android_ripple={{ color: colors.ripple, foreground: true }}>
                    {sortOrder === "asc" ? <ArrowUpAZ size={16} color={colors.text} /> : <ArrowDownZA size={16} color={colors.text} />}
                </Pressable>
                <Popover>
                    <PopoverTrigger asChild>
                        <Pressable style={styles.actionButton} android_ripple={{ color: colors.ripple, foreground: true }}>
                            <Type size={16} color={colors.text} />
                        </Pressable>
                    </PopoverTrigger>
                    <PopoverContent className="bg-black w-auto p-2" align="end" side="bottom">
                        <View style={styles.popoverContentContainer}>
                            <Text style={styles.fontSizeDisplay}>Font Size: {fontSize}pt</Text>
                            <View style={styles.popoverButtonContainer}>
                                <Pressable style={styles.popoverButton} onPress={decreaseFontSize} android_ripple={{ color: colors.ripple, foreground: true }}>
                                    <Minus size={16} color={colors.text} />
                                </Pressable>
                                <Pressable style={styles.popoverButton} onPress={increaseFontSize} android_ripple={{ color: colors.ripple, foreground: true }}>
                                    <Plus size={16} color={colors.text} />
                                </Pressable>
                            </View>
                        </View>
                    </PopoverContent>
                </Popover>
            </View>

            {/* Log Messages */}
            <View style={styles.logContainer}>
                <CustomScrollView
                    ref={scrollViewRef}
                    key={listKey}
                    targetProps={{
                        data: filteredMessages,
                        renderItem: renderLogItem,
                        keyExtractor: keyExtractor,
                        removeClippedSubviews: true,
                        onScroll: handleScroll,
                        onMomentumScrollEnd: handleScrollEnd,
                        onScrollEndDrag: handleScrollEnd,
                        scrollEventThrottle: 16,
                        onContentSizeChange: handleContentSizeChange,
                        onLayout: handleLayout,
                    }}
                    hideScrollbar={true}
                />
            </View>

            {/* Floating Scroll Buttons */}
            {showScrollButtons && (
                <View style={styles.floatingButtonContainer}>
                    <Animated.View
                        style={{
                            opacity: topButtonOpacity,
                            pointerEvents: showScrollToTop ? "auto" : "none",
                        }}
                    >
                        <GlassFab onPress={scrollToTop} accessibilityLabel="Scroll to top" icon={<ArrowUp size={20} color={colors.brand} />} />
                    </Animated.View>
                    <Animated.View
                        style={{
                            opacity: bottomButtonOpacity,
                            pointerEvents: showScrollToBottom ? "auto" : "none",
                        }}
                    >
                        <GlassFab onPress={scrollToBottom} accessibilityLabel="Scroll to bottom" icon={<ArrowDown size={20} color={colors.brand} />} />
                    </Animated.View>
                </View>
            )}

            {/* Error Dialog */}
            <AlertDialog open={showErrorDialog} onOpenChange={setShowErrorDialog}>
                <AlertDialogContent onDismiss={() => setShowErrorDialog(false)}>
                    <AlertDialogHeader>
                        <AlertDialogTitle>Error</AlertDialogTitle>
                        <AlertDialogDescription>{errorMessage}</AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                        <AlertDialogAction onPress={() => setShowErrorDialog(false)}>
                            <Text>OK</Text>
                        </AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>
        </View>
    )
}

export default memo(MessageLog)
