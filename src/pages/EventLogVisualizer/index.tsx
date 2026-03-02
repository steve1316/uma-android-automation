import React, { useMemo, useState, useCallback } from "react"
import { StyleSheet, Text, View, TouchableOpacity } from "react-native"
import { FlashList } from "@shopify/flash-list"
import * as DocumentPicker from "expo-document-picker"
import * as FileSystem from "expo-file-system"
import DayRow from "../../components/EventLog/DayRow"
import GapsNotice from "../../components/EventLog/GapsNotice"
import FileDivider from "../../components/EventLog/FileDivider"
import YearSummaryCard from "../../components/EventLog/YearSummaryCard"
import { parseLogs, type LogFileInput, type DayRecord, type GapRecord, type FileDividerRecord, aggregateYearSummaries } from "../../lib/eventLogParser"
import CustomButton from "../../components/CustomButton"
import { useTheme } from "../../context/ThemeContext"
import { Snackbar } from "react-native-paper"
import { useSettings } from "../../context/SettingsContext"
import { Tooltip, TooltipContent, TooltipTrigger } from "../../components/ui/tooltip"
import { Info } from "lucide-react-native"
import CustomCheckbox from "../../components/CustomCheckbox"
import PageHeader from "../../components/PageHeader"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import WarningContainer from "../../components/WarningContainer"

type MixedRecord = DayRecord | GapRecord | FileDividerRecord

/**
 * The Event Log Visualizer page.
 * Allows users to import bot log files and view a day-by-day timeline of actions, gap notices for missing days, file dividers,
 * and aggregated year summaries with action counts, stat gains, and elapsed time.
 */
const EventLogVisualizer: React.FC = () => {
    usePerformanceLogging("EventLogVisualizer")
    const { colors, isDark } = useTheme()
    const { openDataDirectory } = useSettings()

    const [records, setRecords] = useState<MixedRecord[]>([])
    const [errors, setErrors] = useState<string[]>([])
    const [snackbarOpen, setSnackbarOpen] = useState<boolean>(false)
    const [showTriggers, setShowTriggers] = useState<boolean>(false)
    const [viewMode, setViewMode] = useState<"timeline" | "years">("timeline")

    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: {
                    flex: 1,
                    backgroundColor: colors.background,
                },
                content: {
                    padding: 12,
                },
                empty: {
                    marginTop: 12,
                    marginBottom: 12,
                    color: "white",
                    opacity: 0.8,
                },
                totalTimeTitle: {
                    fontSize: 18,
                    fontWeight: "bold",
                },
                totalTimeValue: {
                    fontSize: 18,
                    fontWeight: "600",
                },
                totalTimeHuman: {
                    fontSize: 14,
                },
                toggleContainer: {
                    flexDirection: "row",
                    backgroundColor: colors.card,
                    borderRadius: 8,
                    padding: 4,
                    gap: 4,
                },
                toggleButton: {
                    flex: 1,
                    paddingVertical: 8,
                    paddingHorizontal: 12,
                    borderRadius: 6,
                    alignItems: "center",
                    justifyContent: "center",
                },
                toggleButtonActive: {
                    backgroundColor: colors.primary,
                },
                toggleButtonInactive: {
                    backgroundColor: "transparent",
                },
                toggleButtonText: {
                    fontSize: 14,
                    fontWeight: "600",
                },
                toggleButtonTextActive: {
                    color: colors.primaryForeground,
                },
                toggleButtonTextInactive: {
                    color: colors.foreground,
                },
            }),
        [colors]
    )

    /**
     * Handles file selection for log files by reading the selected files and parsing their contents.
     */
    async function onPickFiles() {
        try {
            const result = await DocumentPicker.getDocumentAsync({ multiple: true, type: "text/plain", copyToCacheDirectory: true })
            if (result.canceled) return

            const assets = result.assets || []
            const fileInputs: LogFileInput[] = []
            for (const a of assets) {
                const uri = a.uri
                const name = a.name || "log.txt"
                const content = await FileSystem.readAsStringAsync(uri, { encoding: FileSystem.EncodingType.UTF8 })
                fileInputs.push({ name, content })
            }

            const res = parseLogs(fileInputs)
            const errorMessages = res.errors.map((e) => e.message)

            // Defer state updates to prevent mounting conflicts when FlashList is updating.
            setTimeout(() => {
                setRecords(res.records)
                setErrors(errorMessages)
                setSnackbarOpen(errorMessages.length > 0)
            }, 0)
        } catch (e: any) {
            const errorMessage = String(e?.message || e)
            setTimeout(() => {
                setErrors([errorMessage])
                setSnackbarOpen(true)
            }, 0)
        }
    }

    /**
     * Aggregates year summaries from the records.
     * @returns An array of year summaries.
     */
    const yearSummariesResult = useMemo(() => {
        const dayRecords = records.filter((r) => r.kind === "day") as DayRecord[]
        return aggregateYearSummaries(dayRecords)
    }, [records])

    /**
     * Renders a list item based on the type of record.
     * @param item The record to render.
     * @returns A React component representing the list item.
     */
    const renderItem = useCallback(
        ({ item }: { item: MixedRecord }) => {
            if (item.kind === "gap") {
                return <GapsNotice gap={item} />
            }
            if (item.kind === "fileDivider") {
                return <FileDivider divider={item} />
            }
            return <DayRow record={item} showTriggers={showTriggers} />
        },
        [showTriggers]
    )

    /**
     * Extracts a unique key for each list item based on its type and content.
     * @param item The list item to extract a key from.
     * @param idx The index of the list item.
     * @returns A unique key for the list item.
     */
    const keyExtractor = useCallback((item: MixedRecord, idx: number) => {
        if (item.kind === "day") return `day-${item.dayNumber}`
        if (item.kind === "gap") return `gap-${item.from}-${item.to}-${idx}`
        return `file-divider-${item.fileName}-${idx}`
    }, [])

    return (
        <View style={styles.root}>
            <View style={styles.content}>
                <PageHeader title="Event Log Visualizer" style={{ marginBottom: 12 }} />

                <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between", marginBottom: 12, gap: 8 }}>
                    <CustomButton onPress={openDataDirectory} variant="default">
                        📁 Open Data Directory
                    </CustomButton>
                    <CustomButton onPress={onPickFiles} variant="default">
                        📂 Select Log Files
                    </CustomButton>
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <TouchableOpacity style={{ padding: 8 }}>
                                <Info size={20} color={colors.primary} />
                            </TouchableOpacity>
                        </TooltipTrigger>
                        <TooltipContent side="bottom" style={{ backgroundColor: isDark ? colors.muted : "black", maxWidth: 300 }}>
                            <WarningContainer>
                                <View style={{ flexDirection: "row", flexWrap: "wrap" }}>
                                    <Text style={{ fontWeight: "bold", color: colors.warningText }}>⚠️ File Explorer Note:</Text>
                                    <Text style={{ fontSize: 14, color: colors.warningText, lineHeight: 20 }}>
                                        To manually access files, you need a file explorer app that can access the /Android/data folder (like CX File Explorer). Standard file managers will not work.
                                    </Text>
                                </View>
                            </WarningContainer>
                            <Text style={styles.empty}>
                                Select one or more .txt logs named like "log @ yyyy-mm-dd HH_mm_ss.txt" to visualize per-day actions. Files are sorted by filename. Gaps between days are shown.{" "}
                                {"\n\n"}
                                Note: Recent Android versions heavily restrict access to the app data folder where logs are stored. Use the "Open Data Directory" button above to locate the logs, then
                                move the files you want to use out of /Android/data/ to a public folder like /Download/ before selecting them here.
                            </Text>
                        </TooltipContent>
                    </Tooltip>
                </View>

                <View style={{ marginTop: 8, marginBottom: 12 }}>
                    <CustomCheckbox
                        checked={showTriggers}
                        onCheckedChange={(checked) => setShowTriggers(!!checked)}
                        label="Show trigger lines"
                        description="Display the exact log lines that triggered each action per day."
                    />
                </View>

                <View style={{ flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 8 }}>
                    <View style={[styles.toggleContainer, { flex: 1 }]}>
                        <TouchableOpacity style={[styles.toggleButton, viewMode === "timeline" ? styles.toggleButtonActive : styles.toggleButtonInactive]} onPress={() => setViewMode("timeline")}>
                            <Text style={[styles.toggleButtonText, viewMode === "timeline" ? styles.toggleButtonTextActive : styles.toggleButtonTextInactive]}>Timeline</Text>
                        </TouchableOpacity>
                        <TouchableOpacity style={[styles.toggleButton, viewMode === "years" ? styles.toggleButtonActive : styles.toggleButtonInactive]} onPress={() => setViewMode("years")}>
                            <Text style={[styles.toggleButtonText, viewMode === "years" ? styles.toggleButtonTextActive : styles.toggleButtonTextInactive]}>Year Summaries</Text>
                        </TouchableOpacity>
                    </View>
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <TouchableOpacity style={{ padding: 8 }}>
                                <Info size={20} color={colors.primary} />
                            </TouchableOpacity>
                        </TooltipTrigger>
                        <TooltipContent side="bottom" style={{ backgroundColor: isDark ? colors.muted : "black", maxWidth: 300 }}>
                            <Text style={styles.empty}>
                                <Text style={{ fontWeight: "bold", color: isDark ? colors.foreground : colors.mutedForeground }}>Timeline View:</Text>
                                {"\n"}
                                Displays all days in chronological order with their actions (Recover Energy, Recover Mood, Recover Injury, Training, Race). Shows gaps for missing days and file
                                dividers when the source file changes.
                                {"\n\n"}
                                <Text style={{ fontWeight: "bold", color: isDark ? colors.foreground : colors.mutedForeground }}>Year Summaries View:</Text>
                                {"\n"}
                                Provides aggregated statistics for each year (Junior, Classic, Senior). Shows total action counts, stat gains from training (approximated), and elapsed time per year.
                            </Text>
                        </TooltipContent>
                    </Tooltip>
                </View>
            </View>

            <View style={{ flex: 1 }}>
                {viewMode === "timeline" ? (
                    <FlashList
                        data={records}
                        renderItem={renderItem}
                        keyExtractor={keyExtractor}
                        getItemType={(item) => (item.kind === "day" ? "day" : item.kind === "gap" ? "gap" : "fileDivider")}
                        ListEmptyComponent={<View />}
                    />
                ) : (
                    <>
                        {yearSummariesResult.totalElapsedTimeFormatted && (
                            <View style={[styles.content, { paddingBottom: 8, flexDirection: "row", alignItems: "center", gap: 8 }]}>
                                <Text style={[styles.totalTimeTitle, { color: colors.foreground }]}>Total Elapsed Time:</Text>
                                <Text style={[styles.totalTimeValue, { color: colors.foreground }]}>{yearSummariesResult.totalElapsedTimeFormatted}</Text>
                                <Text style={[styles.totalTimeHuman, { color: colors.mutedForeground }]}>({yearSummariesResult.totalElapsedTimeHuman})</Text>
                            </View>
                        )}
                        <FlashList
                            data={yearSummariesResult.summaries}
                            renderItem={({ item }) => <YearSummaryCard summary={item} />}
                            keyExtractor={(item) => item.year}
                            ListEmptyComponent={<View />}
                        />
                    </>
                )}
            </View>

            <Snackbar
                visible={snackbarOpen}
                onDismiss={() => setSnackbarOpen(false)}
                action={{ label: "Close", onPress: () => setSnackbarOpen(false) }}
                style={{ backgroundColor: errors.length ? colors.destructive : colors.card, borderRadius: 10 }}
            >
                {errors.join("\n")}
            </Snackbar>
        </View>
    )
}

export default EventLogVisualizer
