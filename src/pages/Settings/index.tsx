import { useMemo, useContext, useEffect, useState, useRef, useCallback } from "react"
import { SearchPageProvider } from "../../context/SearchPageContext"
import { BotMetaContext, GeneralMiscContext } from "../../context/BotStateContext"
import { Dimensions, InteractionManager, Pressable, ScrollView, StyleSheet, Text, View } from "react-native"
import { Snackbar } from "react-native-paper"
import { useNavigation } from "@react-navigation/native"
import { Ionicons } from "@react-native-vector-icons/ionicons"
import ThemeToggle from "../../components/ThemeToggle"
import { useTheme } from "../../context/ThemeContext"
import CustomSelect from "../../components/CustomSelect"
import CustomSlider from "../../components/CustomSlider"
import CustomButton from "../../components/CustomButton"
import PageHeader from "../../components/PageHeader"
import { Row } from "../../components/ui/row"
import { Switch } from "../../components/ui/switch"
import { Section } from "../../components/ui/section"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import { ValuePill } from "../../components/ui/value-pill"
import { ModalHeader } from "../../components/ui/modal-header"
import WarningContainer from "../../components/WarningContainer"
import InfoContainer from "../../components/InfoContainer"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "../../components/ui/alert-dialog"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import SeasonCalendar, { useSeasonCalendarStyles } from "../../components/SeasonCalendar"
import { Popover, PopoverContent, PopoverTrigger, usePopoverRootContext } from "../../components/ui/popover"
import { formatCareerTurn, turnDateLabel } from "../../lib/solver/constants"
import { DATING_SCHEDULE_CUSTOM, DATING_SCHEDULE_PRESETS } from "../../lib/datingSchedule"
import { useSettings } from "../../context/SettingsContext"
import { useSettingsFileManager } from "../../hooks/useSettingsFileManager"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** Preset options for the recreation dating-schedule selector, plus a Custom entry for hand-editing the calendar. */
const datingPresetOptions = [...Object.entries(DATING_SCHEDULE_PRESETS).map(([value, preset]) => ({ label: preset.label, value })), { label: "Custom", value: DATING_SCHEDULE_CUSTOM }]

/** Props for RecreationDateActions. */
interface RecreationDateActionsProps {
    /** The career turn (1-72) this popover is acting on. */
    turn: number
    /** Whether this turn is currently pinned as a regular recreation date. */
    isRecreation: boolean
    /** Whether this turn is currently the single Pure Passion final date. */
    isPurePassion: boolean
    /** Marks the turn as a regular recreation date, or clears it when toggled off. */
    onMark: (turn: number) => void
    /** Sets the turn as the single Pure Passion final date. */
    onSetPurePassion: (turn: number) => void
    /** Clears the turn from whichever role it currently holds. */
    onClear: (turn: number) => void
}

/**
 * The recreation-cell popover body: one switch pins the turn as a regular Recreation date and one marks it as the single Pure Passion final date.
 * Only one turn can be the Pure Passion date, so toggling it on moves it here off any other turn. Reads the popover root context so each toggle also dismisses the popover.
 * @param turn The career turn this popover is acting on.
 * @param isRecreation Whether this turn is currently pinned as a regular recreation date.
 * @param isPurePassion Whether this turn is currently the single Pure Passion final date.
 * @param onMark Marks the turn as a regular recreation date.
 * @param onSetPurePassion Sets the turn as the single Pure Passion final date.
 * @param onClear Clears the turn from whichever role it currently holds.
 * @returns The rendered switch rows.
 */
function RecreationDateActions({ turn, isRecreation, isPurePassion, onMark, onSetPurePassion, onClear }: RecreationDateActionsProps) {
    const { onOpenChange } = usePopoverRootContext()
    // Toggling a role on applies it; toggling off clears the turn. Either way the popover dismisses.
    const toggleRole = (value: boolean, apply: (turn: number) => void) => {
        if (value) apply(turn)
        else onClear(turn)
        onOpenChange(false)
    }
    return (
        <>
            <Row title="Recreation date" right={<Switch checked={isRecreation} onCheckedChange={(value) => toggleRole(value, onMark)} />} />
            <Row
                title="Pure Passion final date"
                description="Only one date can trigger Pure Passion."
                right={<Switch checked={isPurePassion} onCheckedChange={(value) => toggleRole(value, onSetPurePassion)} />}
            />
        </>
    )
}

/**
 * The main Settings page of the application.
 * Provides scenario selection, navigation links to sub-settings pages,
 * misc bot configuration options, and settings management (import/export/reset).
 */
const Settings = () => {
    usePerformanceLogging("Settings")
    const scrollViewRef = useRef<ScrollView>(null)

    const { defaultSettings } = useContext(BotMetaContext)
    const { general, misc, updateGeneral, updateMisc } = useContext(GeneralMiscContext)
    const calStyles = useSeasonCalendarStyles()
    const { colors } = useTheme()
    const modalShellStyles = useModalShellStyles()
    const [datingPresetPickerOpen, setDatingPresetPickerOpen] = useState(false)
    // Width for the recreation-cell popovers, computed once instead of per calendar cell.
    const recreationPopoverStyle = useMemo(() => ({ width: Math.min(280, Dimensions.get("window").width - 24) }), [])
    const navigation = useNavigation()

    const { openDataDirectory, resetSettings } = useSettings()
    const { handleImportSettings, handleExportSettings, showImportDialog, setShowImportDialog, showResetDialog, setShowResetDialog } = useSettingsFileManager()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: {
                    flex: 1,
                    flexDirection: "column",
                    justifyContent: "center",
                    margin: 10,
                    backgroundColor: colors.bg,
                },
                managementGrid: {
                    flexDirection: "row",
                    gap: SPACING.sm,
                },
                managementTile: {
                    flex: 1,
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.lg,
                    paddingVertical: SPACING.md,
                    paddingHorizontal: SPACING.sm,
                    alignItems: "center",
                    justifyContent: "center",
                    gap: 6,
                    overflow: "hidden",
                },
                managementTileLabel: { ...TYPE.body, color: colors.text, fontWeight: "600" as const, textAlign: "center" as const },
                managementTileCaption: { ...TYPE.caption, color: colors.textMuted, fontSize: 10, textAlign: "center" as const },
                managementTileDanger: { borderColor: colors.destructive },
                dateEntry: {
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.md,
                    backgroundColor: colors.surfaceRaised,
                    padding: SPACING.md,
                    gap: SPACING.sm,
                },
                dateEntryHeader: { flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
                dateEntryTitleRow: { flexDirection: "row", alignItems: "center", gap: SPACING.sm, flex: 1 },
                dateBadge: { width: 24, height: 24, borderRadius: 12, backgroundColor: colors.brand, alignItems: "center" as const, justifyContent: "center" as const },
                dateBadgeText: { ...TYPE.monoLabel, color: colors.onBrand, fontSize: 11 },
                dateTitle: { ...TYPE.body, color: colors.text, fontWeight: "600" as const, flexShrink: 1 },
                dateRemoveButton: { padding: SPACING.xs, borderRadius: 999, overflow: "hidden" as const },
                dateSelectorRow: { flexDirection: "row" },
                dateSelectorCell: { flex: 1 },
                resetLink: { ...TYPE.caption, color: colors.brand, fontWeight: "600" as const },
            }),
        [colors]
    )

    //////////////////////////////////////////////////
    //////////////////////////////////////////////////
    // Callbacks

    // Two-phase mount. First paint renders the cheap navigation-link list (~40 ms baseline) so the
    // user sees the page immediately; the heavy Misc section (sliders, checkboxes, dialogs,
    // file-manager hook plumbing — ~1 s of additional work) commits one tick later, after the
    // navigator animation has painted. `runAfterInteractions` fires when the JS-side scheduler
    // considers itself idle, so we don't fight the navigation transition. Net: the page first
    // paint dropped 27 % (1065 → 782 ms) on a calibrated emulator harness.
    const [showHeavySections, setShowHeavySections] = useState(false)
    useEffect(() => {
        const handle = InteractionManager.runAfterInteractions(() => {
            setShowHeavySections(true)
        })
        return () => handle.cancel()
    }, [])

    const [snackbarMessage, setSnackbarMessage] = useState<string | null>(null)

    /**
     * Reset the settings to their default values.
     */
    const handleResetSettings = async () => {
        const success = await resetSettings()
        if (success) {
            setSnackbarMessage("Settings reset to defaults")
            setTimeout(() => setSnackbarMessage(null), 2500)
        }
    }

    //////////////////////////////////////////////////
    //////////////////////////////////////////////////
    // Rendering

    const years = [
        { label: "Junior", value: "Junior" },
        { label: "Classic", value: "Classic" },
        { label: "Senior", value: "Senior" },
    ]

    const months = [
        { label: "January", value: "January" },
        { label: "February", value: "February" },
        { label: "March", value: "March" },
        { label: "April", value: "April" },
        { label: "May", value: "May" },
        { label: "June", value: "June" },
        { label: "July", value: "July" },
        { label: "August", value: "August" },
        { label: "September", value: "September" },
        { label: "October", value: "October" },
        { label: "November", value: "November" },
        { label: "December", value: "December" },
    ]

    const phases = [
        { label: "Early", value: "Early" },
        { label: "Late", value: "Late" },
    ]

    const handleStopAtDateChange = useCallback(
        (index: number, part: "year" | "month" | "phase", value: string) => {
            const dates = [...general.stopAtDates]
            const currentParts = dates[index].split(" ")
            let newYear = currentParts[0] || "Senior"
            let newMonth = currentParts[1] || "January"
            let newPhase = currentParts[2] || "Early"

            if (part === "year") newYear = value
            if (part === "month") newMonth = value
            if (part === "phase") newPhase = value

            dates[index] = `${newYear} ${newMonth} ${newPhase}`
            updateGeneral({ stopAtDates: dates })
        },
        [general]
    )

    const handleAddStopAtDate = useCallback(() => {
        updateGeneral({ stopAtDates: [...general.stopAtDates, "Senior January Early"] })
    }, [general])

    const handleRemoveStopAtDate = useCallback(
        (index: number) => {
            const dates = general.stopAtDates.filter((_, i) => i !== index)
            updateGeneral({ stopAtDates: dates.length > 0 ? dates : ["Senior January Early"] })
        },
        [general]
    )

    // Shared chevron icon used as the right-aligned affordance on every navigation Row.
    const chevron = <Ionicons name="chevron-forward" size={20} color={colors.textMuted} />

    const renderNavigationSections = () => {
        return (
            <>
                <Section label="GAMEPLAY">
                    <Row title="Training" description="Stat priorities, training behavior, and customization." right={chevron} onPress={() => navigation.navigate("TrainingSettings" as never)} />
                    <Row title="Training Events" description="Training event preferences and event selection." right={chevron} onPress={() => navigation.navigate("TrainingEventSettings" as never)} />
                    <Row title="Racing" description="Racing behavior, retries, and mandatory race handling." right={chevron} onPress={() => navigation.navigate("RacingSettings" as never)} />
                    <Row title="Skills" description="Skill purchasing behavior." right={chevron} onPress={() => navigation.navigate("Skills" as never)} />
                </Section>

                <Section label="SCENARIO">
                    <Row
                        title="Scenario Overrides"
                        description="Behavior overrides specific to each scenario."
                        right={chevron}
                        onPress={() => navigation.navigate("ScenarioOverridesSettings" as never)}
                    />
                </Section>

                <Section label="INTEGRATIONS">
                    <Row title="Discord" description="Discord notifications when the bot stops." right={chevron} onPress={() => navigation.navigate("DiscordSettings" as never)} />
                    <Row title="LLM" description="On-device docs search and chat model downloads." right={chevron} onPress={() => navigation.navigate("LLMSettings" as never)} />
                </Section>

                <Section label="TOOLS">
                    <Row title="Ask the Docs" description="On-device docs chat powered by the LLM engine." right={chevron} onPress={() => navigation.navigate("Chat" as never)} />
                    <Row
                        title="Event Log Visualizer (Beta)"
                        description="Import logs and view a day-by-day timeline of actions."
                        right={chevron}
                        onPress={() => navigation.navigate("EventLogVisualizer" as never)}
                    />
                    <Row title="Debug" description="Debug mode, template matching, and diagnostic tests." right={chevron} onPress={() => navigation.navigate("DebugSettings" as never)} />
                </Section>
            </>
        )
    }

    const handleDatingPresetChange = useCallback(
        (preset: string) => {
            const selected = DATING_SCHEDULE_PRESETS[preset]
            if (selected) {
                updateGeneral({
                    datingSchedulePreset: preset,
                    recreationTurns: [...selected.recreationTurns],
                    purePassionTurn: selected.purePassionTurn,
                    recreationTotalOutings: selected.totalOutings,
                })
            } else {
                updateGeneral({ datingSchedulePreset: DATING_SCHEDULE_CUSTOM, recreationTurns: [], purePassionTurn: -1 })
            }
        },
        [updateGeneral]
    )

    const handleMarkRecreationTurn = useCallback(
        (turn: number) => {
            updateGeneral((prev) => ({
                ...prev,
                datingSchedulePreset: DATING_SCHEDULE_CUSTOM,
                recreationTurns: prev.recreationTurns.includes(turn) ? prev.recreationTurns : [...prev.recreationTurns, turn].sort((a, b) => a - b),
                purePassionTurn: prev.purePassionTurn === turn ? -1 : prev.purePassionTurn,
            }))
        },
        [updateGeneral]
    )

    const handleSetPurePassionTurn = useCallback(
        (turn: number) => {
            updateGeneral((prev) => ({ ...prev, datingSchedulePreset: DATING_SCHEDULE_CUSTOM, purePassionTurn: turn, recreationTurns: prev.recreationTurns.filter((t) => t !== turn) }))
        },
        [updateGeneral]
    )

    const handleClearRecreationTurn = useCallback(
        (turn: number) => {
            updateGeneral((prev) => ({
                ...prev,
                datingSchedulePreset: DATING_SCHEDULE_CUSTOM,
                recreationTurns: prev.recreationTurns.filter((t) => t !== turn),
                purePassionTurn: prev.purePassionTurn === turn ? -1 : prev.purePassionTurn,
            }))
        },
        [updateGeneral]
    )

    const resetDatingSchedule = useCallback(() => {
        updateGeneral({
            datingSchedulePreset: defaultSettings.general.datingSchedulePreset,
            recreationTurns: [...defaultSettings.general.recreationTurns],
            purePassionTurn: defaultSettings.general.purePassionTurn,
            recreationTotalOutings: defaultSettings.general.recreationTotalOutings,
        })
    }, [updateGeneral, defaultSettings])

    /** Shared "Reset" pressable used in a section label's right slot. */
    const makeResetLink = (onPress: () => void) => (
        <Pressable onPress={onPress} android_ripple={{ color: colors.ripple, foreground: true }} hitSlop={8}>
            <Text style={styles.resetLink}>Reset</Text>
        </Pressable>
    )

    const renderMiscSettings = () => {
        const renderRecreationPopover = (turn: number) => {
            const isRecreation = general.recreationTurns.includes(turn)
            const isPurePassion = general.purePassionTurn === turn
            return (
                <View style={{ gap: SPACING.sm }}>
                    <Text style={styles.dateTitle}>{formatCareerTurn(turn)}</Text>
                    <RecreationDateActions
                        turn={turn}
                        isRecreation={isRecreation}
                        isPurePassion={isPurePassion}
                        onMark={handleMarkRecreationTurn}
                        onSetPurePassion={handleSetPurePassionTurn}
                        onClear={handleClearRecreationTurn}
                    />
                </View>
            )
        }

        // A turn set as the Pure Passion final date shows the amber "mandatory" border and the Pure Passion marker. A plain recreation turn shows the brand border and the recreation marker.
        const renderRecreationCell = (turn: number, turnInYear: number) => {
            const isRecreation = general.recreationTurns.includes(turn)
            const isPurePassion = general.purePassionTurn === turn
            return (
                <View key={turn} style={calStyles.calendarCellWrapper}>
                    <Popover>
                        <PopoverTrigger asChild>
                            <Pressable
                                style={[calStyles.calendarCell, isRecreation && calStyles.calendarCellLocked, isPurePassion && calStyles.calendarCellMandatory]}
                                android_ripple={{ color: colors.ripple, foreground: true }}
                            >
                                <Text style={calStyles.calendarCellEmpty}>{isPurePassion ? "✨" : isRecreation ? "📅" : "—"}</Text>
                            </Pressable>
                        </PopoverTrigger>
                        <PopoverContent side="top" align="center" insets={{ top: 60, bottom: 60, left: 12, right: 12 }} className="p-3" style={recreationPopoverStyle}>
                            {renderRecreationPopover(turn)}
                        </PopoverContent>
                    </Popover>
                    <Text style={calStyles.calendarDateLabel}>
                        {isPurePassion ? "✨ " : isRecreation ? "📅 " : ""}
                        {turnDateLabel(turnInYear)}
                    </Text>
                </View>
            )
        }

        return (
            <View>
                <Section label="MISC">
                    <ToggleSetting
                        id="settings-stop-before-finals"
                        title="Stop before Finals"
                        description="Pause to buy skills before the final races"
                        checked={general.enableStopBeforeFinals}
                        onCheckedChange={(checked) => updateGeneral({ enableStopBeforeFinals: checked })}
                    />

                    <ToggleSetting
                        id="settings-stop-at-date"
                        title="Stop at Date"
                        description="Stop on one or more specified dates"
                        checked={general.enableStopAtDate}
                        onCheckedChange={(checked) => updateGeneral({ enableStopAtDate: checked })}
                    />

                    {general.enableStopAtDate && (
                        <SearchableItem id="settings-target-dates" title="Target Dates" description="Stops the bot on the specified dates." parentId="settings-stop-at-date">
                            <View style={{ padding: SPACING.md, gap: SPACING.sm }}>
                                {general.stopAtDates.map((dateStr, index) => {
                                    const parts = dateStr.split(" ")
                                    const year = parts[0] || "Senior"
                                    const month = parts[1] || "January"
                                    const phase = parts[2] || "Early"
                                    return (
                                        <View key={index} style={styles.dateEntry}>
                                            <View style={styles.dateEntryHeader}>
                                                <View style={styles.dateEntryTitleRow}>
                                                    <View style={styles.dateBadge}>
                                                        <Text style={styles.dateBadgeText}>{index + 1}</Text>
                                                    </View>
                                                    <Text style={styles.dateTitle} numberOfLines={1}>
                                                        {year} {month} {phase}
                                                    </Text>
                                                </View>
                                                {general.stopAtDates.length > 1 && (
                                                    <Pressable
                                                        onPress={() => handleRemoveStopAtDate(index)}
                                                        style={styles.dateRemoveButton}
                                                        hitSlop={8}
                                                        android_ripple={{ color: colors.ripple, foreground: true }}
                                                        accessibilityRole="button"
                                                        accessibilityLabel={`Remove Date ${index + 1}`}
                                                    >
                                                        <Ionicons name="trash-outline" size={18} color={colors.destructive} />
                                                    </Pressable>
                                                )}
                                            </View>
                                            <View style={styles.dateSelectorRow}>
                                                <View style={styles.dateSelectorCell}>
                                                    <CustomSelect
                                                        placeholder="Year"
                                                        width="100%"
                                                        options={years}
                                                        value={year}
                                                        onValueChange={(value) => handleStopAtDateChange(index, "year", value || "Senior")}
                                                    />
                                                </View>
                                                <View style={styles.dateSelectorCell}>
                                                    <CustomSelect
                                                        placeholder="Month"
                                                        width="100%"
                                                        options={months}
                                                        value={month}
                                                        onValueChange={(value) => handleStopAtDateChange(index, "month", value || "January")}
                                                    />
                                                </View>
                                                <View style={styles.dateSelectorCell}>
                                                    <CustomSelect
                                                        placeholder="Phase"
                                                        width="100%"
                                                        options={phases}
                                                        value={phase}
                                                        onValueChange={(value) => handleStopAtDateChange(index, "phase", value || "Early")}
                                                    />
                                                </View>
                                            </View>
                                        </View>
                                    )
                                })}
                                <CustomButton onPress={handleAddStopAtDate} variant="outline" icon={<Ionicons name="add" size={18} color={colors.text} />} style={{ marginVertical: SPACING.sm }}>
                                    Add Date
                                </CustomButton>
                            </View>
                        </SearchableItem>
                    )}

                    <ToggleSetting
                        id="settings-claw-machine-attempt"
                        title="Enable Claw Machine Attempt"
                        description="Attempt to complete the claw machine instead of stopping"
                        checked={general.enableClawMachineAttempt}
                        onCheckedChange={(checked) => updateGeneral({ enableClawMachineAttempt: checked })}
                    />

                    <ToggleSetting
                        id="settings-enable-swipe-based-scrolling"
                        title="Enable Swipe-Based Scrolling"
                        description="Scroll lists by swiping instead of detecting the in-game scrollbar. Enable this if the bot cannot scroll lists normally. This may or may not work depending on the device."
                        checked={general.enableSwipeBasedScrolling}
                        onCheckedChange={(checked) => updateGeneral({ enableSwipeBasedScrolling: checked })}
                    />

                    <ToggleSetting
                        id="settings-enable-settings-display"
                        title="Enable Settings Display in Message Log"
                        description="Show current bot configuration in the message log"
                        checked={misc.enableSettingsDisplay}
                        onCheckedChange={(checked) => updateMisc({ enableSettingsDisplay: checked })}
                    />
                </Section>

                <Section label="SUPPORT CARD DATING" labelRight={makeResetLink(resetDatingSchedule)}>
                    <ToggleSetting
                        id="settings-dating-schedule"
                        title="Support Card Dating Schedule"
                        description="On a pinned turn the bot does a support-card recreation outing over every other action, including scheduled races (your in-game racing agenda or the Smart Race Solver). Only mandatory career-goal races take priority."
                        checked={general.enableDatingSchedule}
                        onCheckedChange={(checked) => updateGeneral({ enableDatingSchedule: checked })}
                    />

                    {general.enableDatingSchedule && (
                        <>
                            <ToggleSetting
                                id="settings-recreation-catch-up"
                                title="Catch Up On Missed Dates"
                                description="If a scheduled outing gets skipped (e.g. a mandatory race lands on it), make it up on the next available turn instead of losing it."
                                checked={general.enableRecreationCatchUp}
                                onCheckedChange={(checked) => updateGeneral({ enableRecreationCatchUp: checked })}
                            />

                            <SearchableItem
                                id="settings-dating-preset"
                                title="Schedule Preset"
                                description="Pick an optimized preset (Pure Passion timed for a summer camp) or Custom to hand-pick turns on the calendar below."
                                parentId="settings-dating-schedule"
                            >
                                <Row
                                    title="Schedule Preset"
                                    description="Pick an optimized preset (Pure Passion timed for a summer camp) or Custom to hand-pick turns on the calendar below."
                                    onPress={() => setDatingPresetPickerOpen(true)}
                                    right={<ValuePill label={datingPresetOptions.find((o) => o.value === general.datingSchedulePreset)?.label ?? "Custom"} />}
                                />
                                {general.purePassionTurn > 0 && (
                                    <View style={{ paddingHorizontal: SPACING.md }}>
                                        <InfoContainer>
                                            Pure Passion activates when you complete the Heir to the Throne's final recreation date. For about 3 turns, Friendship Training occurs on a facility
                                            regardless of bond. This preset pins one date per outing and holds the final one for Senior June Late, so those turns land on Senior Summer Training where
                                            the gains matter most.
                                        </InfoContainer>
                                    </View>
                                )}
                            </SearchableItem>

                            <SearchableItem
                                id="settings-recreation-calendar"
                                title="Recreation Calendar"
                                description="Tap a turn to mark it as a Recreation date or the single Pure Passion date (editing switches the preset to Custom). Pre-Debut and Summer turns are unavailable."
                                parentId="settings-dating-schedule"
                            >
                                <View style={{ paddingHorizontal: SPACING.md }}>
                                    <SeasonCalendar renderCell={renderRecreationCell} deps={[general.recreationTurns, general.purePassionTurn]} />
                                </View>
                            </SearchableItem>

                            <SearchableItem
                                id="settings-recreation-total-outings"
                                title="Total Recreation Outings"
                                description="Number of outings in your support card's recreation chain. Team Sirius = 7, Heirs to the Throne = 4. Read from the game automatically when possible; this is the fallback. Used to hold the final outing for the Pure Passion turn."
                                parentId="settings-dating-schedule"
                            >
                                <View style={{ padding: SPACING.md }}>
                                    <CustomSlider
                                        searchId="settings-recreation-total-outings"
                                        value={general.recreationTotalOutings}
                                        placeholder={defaultSettings.general.recreationTotalOutings}
                                        onValueChange={(value) => updateGeneral({ recreationTotalOutings: value })}
                                        onSlidingComplete={(value) => updateGeneral({ recreationTotalOutings: value })}
                                        min={1}
                                        max={10}
                                        step={1}
                                        label="Total Recreation Outings"
                                        showValue={true}
                                        showLabels={true}
                                        description="Team Sirius = 7, Heirs to the Throne = 4. Pin enough Recreation dates before the Pure Passion date."
                                    />
                                </View>
                            </SearchableItem>
                        </>
                    )}
                </Section>

                <Section label="WAIT DELAY">
                    <View style={{ padding: SPACING.md }}>
                        <CustomSlider
                            searchId="settings-wait-delay"
                            value={general.waitDelay}
                            placeholder={defaultSettings.general.waitDelay}
                            onValueChange={(value) => {
                                updateGeneral({ waitDelay: value })
                            }}
                            onSlidingComplete={(value) => {
                                updateGeneral({ waitDelay: value })
                            }}
                            min={0.0}
                            max={1.0}
                            step={0.1}
                            label="Wait Delay"
                            labelUnit="s"
                            showValue={true}
                            showLabels={true}
                            description="Sets the delay between actions and imaging operations. Lowering this will make the bot run much faster at the risk of the bot losing track of its location after loading/connecting screens."
                        />
                    </View>
                    <View style={{ padding: SPACING.md }}>
                        <CustomSlider
                            searchId="settings-dialog-wait-delay"
                            value={general.dialogWaitDelay}
                            placeholder={defaultSettings.general.dialogWaitDelay}
                            onValueChange={(value) => {
                                updateGeneral({ dialogWaitDelay: value })
                            }}
                            onSlidingComplete={(value) => {
                                updateGeneral({ dialogWaitDelay: value })
                            }}
                            min={0.0}
                            max={1.0}
                            step={0.1}
                            label="Dialog Wait Delay"
                            labelUnit="s"
                            showValue={true}
                            showLabels={true}
                            description="Sets the delay between clicking a button that opens dialog and actually handling the dialog. Lowering this will make the bot run faster at an increased risk of the bot incorrectly handling dialogs that pop up."
                        />
                    </View>
                </Section>

                <Section label="DATA MANAGEMENT">
                    <SearchableItem id="settings-management-title" title="Settings Management" description="Import and export settings from JSON file or access the app's data directory.">
                        <View style={{ padding: SPACING.md }}>
                            <View style={styles.managementGrid}>
                                <Pressable style={styles.managementTile} android_ripple={{ color: colors.ripple, foreground: true }} onPress={handleImportSettings}>
                                    <Ionicons name="download-outline" size={24} color={colors.brand} />
                                    <Text style={styles.managementTileLabel}>Import</Text>
                                    <Text style={styles.managementTileCaption}>Load settings from JSON</Text>
                                </Pressable>
                                <Pressable style={styles.managementTile} android_ripple={{ color: colors.ripple, foreground: true }} onPress={handleExportSettings}>
                                    <Ionicons name="share-outline" size={24} color={colors.brand} />
                                    <Text style={styles.managementTileLabel}>Export</Text>
                                    <Text style={styles.managementTileCaption}>Save settings to JSON</Text>
                                </Pressable>
                                <Pressable style={styles.managementTile} android_ripple={{ color: colors.ripple, foreground: true }} onPress={openDataDirectory}>
                                    <Ionicons name="folder-outline" size={24} color={colors.brand} />
                                    <Text style={styles.managementTileLabel}>Data</Text>
                                    <Text style={styles.managementTileCaption}>Open folder</Text>
                                </Pressable>
                                <Pressable
                                    style={[styles.managementTile, styles.managementTileDanger]}
                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                    onPress={() => setShowResetDialog(true)}
                                >
                                    <Ionicons name="refresh-outline" size={24} color={colors.destructive} />
                                    <Text style={[styles.managementTileLabel, { color: colors.destructive }]}>Reset</Text>
                                    <Text style={styles.managementTileCaption}>Restore defaults</Text>
                                </Pressable>
                            </View>
                        </View>
                    </SearchableItem>
                </Section>

                <WarningContainer style={{ marginTop: 0, marginBottom: SPACING.md }}>
                    <View style={{ flexDirection: "row", flexWrap: "wrap" }}>
                        <Text style={{ fontWeight: "bold", color: colors.warningText }}>⚠️ File Explorer Note:</Text>
                        <Text style={{ fontSize: 14, color: colors.warningText, lineHeight: 20 }}>
                            To manually access files, you need a file explorer app that can access the /Android/data folder (like CX File Explorer). Standard file managers will not work.
                        </Text>
                    </View>
                </WarningContainer>
            </View>
        )
    }

    //////////////////////////////////////////////////
    //////////////////////////////////////////////////

    return (
        <View style={styles.root}>
            <SearchPageProvider page="SettingsMain" scrollViewRef={scrollViewRef}>
                <PageHeader title="Settings" searchOnRight rightComponent={<ThemeToggle />} />
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        {renderNavigationSections()}
                        {showHeavySections && renderMiscSettings()}
                    </View>
                </ScrollView>
            </SearchPageProvider>

            {/* Restart Dialog */}
            <AlertDialog open={showImportDialog} onOpenChange={setShowImportDialog}>
                <AlertDialogContent style={{ backgroundColor: "black" }}>
                    <AlertDialogHeader>
                        <AlertDialogTitle>
                            <Text style={{ color: "white" }}>Settings Imported</Text>
                        </AlertDialogTitle>
                        <AlertDialogDescription>
                            <Text style={{ color: "white" }}>Settings have been imported successfully.</Text>
                        </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                        <AlertDialogAction style={{ backgroundColor: "white" }}>
                            <Text style={{ color: "black" }}>OK</Text>
                        </AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>

            {/* Reset Settings Dialog */}
            <AlertDialog open={showResetDialog} onOpenChange={setShowResetDialog}>
                <AlertDialogContent style={{ backgroundColor: "black" }}>
                    <AlertDialogHeader>
                        <AlertDialogTitle>
                            <Text style={{ color: "white" }}>Reset Settings to Default</Text>
                        </AlertDialogTitle>
                        <AlertDialogDescription style={{ height: 50 }}>
                            <Text style={{ color: "white" }}>
                                Are you sure you want to reset all settings to their default values? This action cannot be undone and will overwrite your current configuration.
                            </Text>
                        </AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                        <AlertDialogCancel onPress={() => setShowResetDialog(false)} style={{ backgroundColor: "black" }}>
                            <Text style={{ color: "white" }}>Cancel</Text>
                        </AlertDialogCancel>
                        <AlertDialogAction onPress={handleResetSettings} style={{ backgroundColor: "white" }}>
                            <Text style={{ color: "black" }}>Reset</Text>
                        </AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>

            <SheetModal
                visible={datingPresetPickerOpen}
                onRequestClose={() => setDatingPresetPickerOpen(false)}
                header={<ModalHeader title="SCHEDULE PRESET" onClose={() => setDatingPresetPickerOpen(false)} />}
                footer={null}
            >
                <View style={modalShellStyles.modalBodyList}>
                    {datingPresetOptions.map((option) => (
                        <ModalRadioRow
                            key={option.value}
                            label={option.label}
                            selected={option.value === general.datingSchedulePreset}
                            onPress={() => {
                                handleDatingPresetChange(option.value)
                                setDatingPresetPickerOpen(false)
                            }}
                        />
                    ))}
                </View>
            </SheetModal>
            <Snackbar visible={snackbarMessage !== null} onDismiss={() => setSnackbarMessage(null)} style={{ backgroundColor: colors.surfaceRaised, borderRadius: 10 }}>
                {snackbarMessage ?? ""}
            </Snackbar>
        </View>
    )
}

export default Settings
