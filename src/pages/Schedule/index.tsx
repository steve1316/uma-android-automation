import { useContext, useMemo, useState, useCallback, useRef, useEffect } from "react"
import { View, Text, ScrollView, Pressable, StyleSheet } from "react-native"
import { CalendarDays, RefreshCw, RotateCcw } from "lucide-react-native"
import racesData from "../../data/races.json"
import characterObjectivesData from "../../data/character_objectives.json"
import { RacingContext, GeneralMiscContext, defaultSettings } from "../../context/BotStateContext"
import { useTheme } from "../../context/ThemeContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import PageHeader from "../../components/PageHeader"
import SearchableItem from "../../components/SearchableItem"
import TabStrip, { TabStripItem } from "../../components/ui/tab-strip"
import { GlassFab } from "../../components/ui/glass-fab"
import { useTranslation } from "../../lib/translations"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalHeader } from "../../components/ui/modal-header"
import { Row } from "../../components/ui/row"
import { ValuePill } from "../../components/ui/value-pill"
import { RADII } from "../../lib/radii"
import { useSolverPreview } from "../../hooks/useSolverPreview"
import { useSolverInputs } from "../../hooks/useSolverInputs"
import { buildScheduleModel, SCHEDULE_SOURCES, type ScheduleModel } from "../../lib/schedule/registry"
import { DATING_SCHEDULE_CUSTOM } from "../../lib/datingSchedule"
import { EPITHETS_BY_NAME, type RaceEntry } from "../../lib/solver/constants"
import { turnsContributingToEpithet, computePreviewStats } from "../../lib/solver/scoring"
import type { CharacterObjectives, ScheduleMutators, ScheduleSourceContext } from "../../lib/schedule/types"
import { SPACING } from "../../lib/spacing"
import { TYPE } from "../../lib/type"
import CalendarTab from "./CalendarTab"
import CharacterPresetSelector from "./components/CharacterPresetSelector"
import EpithetRewards from "./components/EpithetRewards"
import ScheduleStats from "./components/ScheduleStats"
import RaceSolverTab from "./RaceSolverTab"
import RecreationTab from "./RecreationTab"
import TurnDetailSheet from "./TurnDetailSheet"
import { buildEligibleRacesByTurn, buildAllowedEpithetNames } from "./raceEditing"

/** Ordered Schedule tabs. */
const TAB_ITEMS: TabStripItem[] = [
    { key: "raceSolver", label: "Race Solver" },
    { key: "recreation", label: "Recreation" },
]

/** Empty eligible-races-by-turn map, returned while neither the calendar nor the turn-detail sheet is open so the catalog scan is skipped. */
const EMPTY_ELIGIBLE: Map<number, RaceEntry[]> = new Map()

/** Empty allowed-epithet-names set, returned while neither the calendar nor the turn-detail sheet is open so the catalog scan is skipped. */
const EMPTY_ALLOWED: Set<string> = new Set()

/** Empty merged model, returned while neither the calendar nor the turn-detail sheet is open so the source merge (and its race-catalog scans) is skipped. */
const EMPTY_MODEL: ScheduleModel = { byTurn: new Map() }

/** Empty contributing-turns set, returned while no epithet is highlighted so the common case allocates nothing. */
const EMPTY_CONTRIBUTING: Set<number> = new Set()

/** The bundled race catalog, keyed by race name. */
const RACES = racesData as unknown as Record<string, RaceEntry>

/** Optional route params for deep-linking to a tab (drawer + in-app search). */
interface ScheduleRouteParams {
    /** Initial tab key; falls back to "raceSolver". */
    tab?: string
    /** Search result ID to scroll to and highlight, set by in-app search navigation. */
    targetId?: string
}

/**
 * Unified Schedule page shell. Owns the shared solver preview + merged model, switches between the Race Solver / Recreation config tabs shown full-height,
 * and opens the unified calendar in a modal via a floating Calendar FAB.
 * @param route Optional navigation route carrying an initial tab.
 * @returns The tabbed Schedule page.
 */
function Schedule({ route }: { route?: { params?: ScheduleRouteParams } }) {
    const { colors } = useTheme()
    const t = useTranslation()
    const { racing, updateRacing } = useContext(RacingContext)
    const { general, updateGeneral } = useContext(GeneralMiscContext)

    const initialTab = route?.params?.tab && TAB_ITEMS.some((t) => t.key === route.params!.tab) ? route.params!.tab! : "raceSolver"
    const [activeKey, setActiveKey] = useState<string>(initialTab)
    const onChangeTab = useCallback((key: string) => setActiveKey(key), [])
    const scrollViewRef = useRef<ScrollView>(null)

    // A search/drawer nav to a specific tab should switch even when the screen is already mounted.
    useEffect(() => {
        const t = route?.params?.tab
        if (t && TAB_ITEMS.some((i) => i.key === t)) setActiveKey(t)
    }, [route?.params?.tab])

    // Both tabs share one ScrollView now (they stay mounted), so reset scroll to the top when the active tab changes.
    useEffect(() => {
        scrollViewRef.current?.scrollTo({ y: 0, animated: false })
    }, [activeKey])

    const racingSettings = useMemo(() => ({ ...defaultSettings.racing, ...racing }), [racing])
    const generalSettings = useMemo(() => ({ ...defaultSettings.general, ...general }), [general])

    const { aptitudes, targetEpithets, forcedEpithets, manualLocks, weights } = useSolverInputs(racingSettings)

    const { preview, previewLoading, dirty, runPreview } = useSolverPreview({
        enableSmartRaceSolver: racingSettings.enableSmartRaceSolver,
        scenario: general?.scenario ?? "",
        characterPreset: racingSettings.smartRaceSolverCharacterPreset,
        aptitudes,
        targetEpithets,
        forcedEpithets,
        manualLocks,
        weights,
        maxRaces: racingSettings.smartRaceSolverMaxRaces,
        maxConsecutiveRaces: racingSettings.smartRaceSolverMaxConsecutiveRaces,
    })

    const ctx: ScheduleSourceContext = useMemo(
        () => ({
            general: generalSettings,
            racing: racingSettings,
            preview,
            racesByKey: RACES,
            objectives: characterObjectivesData as unknown as Record<string, CharacterObjectives>,
            character: racingSettings.smartRaceSolverCharacterPreset,
            scenario: general?.scenario ?? "",
        }),
        [generalSettings, racingSettings, preview]
    )

    const mutators: ScheduleMutators = useMemo(() => ({ updateGeneral, updateRacing }), [updateGeneral, updateRacing])
    const allowSummer = weights.allowSummerRacing
    const legendFlags = useMemo(
        () => ({ dating: generalSettings.enableDatingSchedule, stop: generalSettings.enableStopAtDate, srs: racingSettings.enableSmartRaceSolver }),
        [generalSettings.enableDatingSchedule, generalSettings.enableStopAtDate, racingSettings.enableSmartRaceSolver]
    )
    const [selectedTurn, setSelectedTurn] = useState<number | null>(null)
    const [calendarOpen, setCalendarOpen] = useState(false)
    const [traineePickerOpen, setTraineePickerOpen] = useState(false)

    /** Name of the epithet whose contributing races are highlighted on the calendar, or null when none is. */
    const [highlightedEpithet, setHighlightedEpithet] = useState<string | null>(null)

    /** Toggle the highlight for an epithet - tapping the highlighted one again clears it. */
    const toggleEpithet = useCallback((name: string) => setHighlightedEpithet((prev) => (prev === name ? null : name)), [])

    /** Close the calendar and drop the highlight, so reopening it starts clean rather than mid-highlight. */
    const closeCalendar = useCallback(() => {
        setCalendarOpen(false)
        setHighlightedEpithet(null)
    }, [])

    // Turns whose scheduled race actually counts toward completing the highlighted epithet, capped at each matcher's required count.
    const contributingTurns = useMemo(() => {
        const ep = highlightedEpithet ? EPITHETS_BY_NAME[highlightedEpithet] : undefined
        if (!ep || !preview) return EMPTY_CONTRIBUTING
        return turnsContributingToEpithet(ep, preview, RACES)
    }, [highlightedEpithet, preview])

    // Aggregate stats for the previewed schedule. Gated on the calendar being open like the scans below - the panel only mounts in that modal, so
    // computing it while the Race Solver tab is in front would re-walk all 72 decisions on every weight edit for a panel nobody can see.
    const previewStats = useMemo(() => (calendarOpen && preview ? computePreviewStats(preview, weights, RACES) : null), [calendarOpen, preview, weights])

    // Skip the eligible-race and allowed-epithet catalog scans while neither the calendar nor the turn-detail sheet is open - they're
    // only consumed there, and the full-catalog scan is otherwise wasted work on every aptitude/weight/scenario change.
    const calendarOrSheetOpen = calendarOpen || selectedTurn != null
    const eligibleRacesByTurn = useMemo(() => (calendarOrSheetOpen ? buildEligibleRacesByTurn(aptitudes, weights) : EMPTY_ELIGIBLE), [aptitudes, weights, calendarOrSheetOpen])
    const allowedEpithetNames = useMemo(
        () => (calendarOrSheetOpen ? buildAllowedEpithetNames(general?.scenario ?? "", racingSettings.smartRaceSolverCharacterPreset) : EMPTY_ALLOWED),
        [general?.scenario, racingSettings.smartRaceSolverCharacterPreset, calendarOrSheetOpen]
    )

    // The merged model is consumed only by the calendar and the turn-detail sheet, so skip the source merge (and its race-catalog scans) while both are closed, like the two gated maps above.
    const model = useMemo(() => (calendarOrSheetOpen ? buildScheduleModel(SCHEDULE_SOURCES, ctx) : EMPTY_MODEL), [ctx, calendarOrSheetOpen])

    /** Select a turn for the detail sheet. The calendar modal stays open behind it so its scroll position is preserved when the sheet closes. */
    const openTurn = useCallback((t: number) => setSelectedTurn(t), [])

    /** Close the turn-detail sheet. Stable identity so it doesn't defeat `memo(TurnDetailSheet)` on unrelated shell re-renders. */
    const closeTurn = useCallback(() => setSelectedTurn(null), [])

    // Whether any manual scheduling overlay exists, so the Reset button can disable itself on an already-clean schedule.
    const hasOverrides = Object.keys(manualLocks).length > 0 || generalSettings.recreationTurns.length > 0 || generalSettings.purePassionTurn > 0 || generalSettings.stopAtDates.length > 0

    /**
     * Clear every manual scheduling overlay back to a clean default: SRS manual locks to the pure solver plan, recreation and Pure Passion pins emptied, and all stop-at-date
     * markers removed. Leaves the feature toggles alone. Clearing the SRS locks flips the schedule dirty, so the existing Apply bar surfaces for a re-solve without the locks.
     */
    const handleReset = useCallback(() => {
        updateRacing({ smartRaceSolverManualLocks: "{}" })
        updateGeneral({ recreationTurns: [], purePassionTurn: -1, datingSchedulePreset: DATING_SCHEDULE_CUSTOM, stopAtDates: [], enableStopAtDate: false })
    }, [updateRacing, updateGeneral])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                applyBar: {
                    flexDirection: "row",
                    alignItems: "center",
                    justifyContent: "space-between",
                    gap: 10,
                    marginHorizontal: SPACING.md,
                    marginTop: SPACING.sm,
                    padding: 10,
                    borderRadius: 8,
                    backgroundColor: colors.warningSubtle ?? colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.warning,
                },
                applyBarText: { ...TYPE.caption, color: colors.warning, flex: 1 },
                applyButton: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: 6,
                    backgroundColor: colors.warning,
                    paddingHorizontal: 12,
                    paddingVertical: 7,
                    borderRadius: 999,
                },
                applyButtonText: { ...TYPE.caption, color: colors.warningContent, fontWeight: "700" },
                resetButton: {
                    flexDirection: "row",
                    alignItems: "center",
                    justifyContent: "center",
                    gap: 8,
                    paddingVertical: 10,
                    borderRadius: 999,
                    borderWidth: 1,
                    borderColor: colors.borderStrong,
                    backgroundColor: colors.surfaceRaised,
                },
                resetButtonText: { ...TYPE.body, color: colors.text, fontWeight: "600" },
            }),
        [colors]
    )

    const translatedTabItems = useMemo(() => TAB_ITEMS.map((item) => ({ ...item, label: t(item.label) })), [t])

    return (
        <SearchPageProvider page="ScheduleScreen" scrollViewRef={scrollViewRef}>
            <View style={{ flex: 1, backgroundColor: colors.bg }}>
                <PageHeader title={t("Schedule")} />
                <SearchableItem
                    id="smart-solver-character-preset"
                    title={t("Character Preset")}
                    description={t("Pick the trainee. Sets the calendar's mandatory races and seeds the Race Solver aptitudes.")}
                >
                    <View
                        style={{
                            marginHorizontal: SPACING.md,
                            marginTop: SPACING.sm,
                            backgroundColor: colors.surface,
                            borderRadius: RADII.lg,
                            borderWidth: 1,
                            borderColor: colors.borderHair,
                            overflow: "hidden",
                        }}
                    >
                        <Row
                            title={t("Trainee")}
                            description={t("Sets the calendar's mandatory races")}
                            onPress={() => setTraineePickerOpen(true)}
                            right={<ValuePill label={racingSettings.smartRaceSolverCharacterPreset || "(none)"} />}
                        />
                    </View>
                </SearchableItem>
                <View style={{ paddingHorizontal: SPACING.md, paddingTop: SPACING.sm }}>
                    <TabStrip items={translatedTabItems} activeKey={activeKey} onChange={onChangeTab} style={{ marginBottom: SPACING.sm }} />
                </View>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled contentContainerStyle={{ paddingHorizontal: SPACING.md, paddingBottom: 96 }} showsVerticalScrollIndicator={false}>
                    {/* Both tabs stay mounted - toggling display avoids re-mounting the heavy Race Solver tab (its 700ms+ section build) on every switch. */}
                    <View style={{ display: activeKey === "raceSolver" ? "flex" : "none" }}>
                        <RaceSolverTab />
                    </View>
                    <View style={{ display: activeKey === "recreation" ? "flex" : "none" }}>
                        <RecreationTab />
                    </View>
                </ScrollView>

                <View style={{ position: "absolute", right: SPACING.lg, bottom: SPACING.lg }}>
                    <GlassFab onPress={() => setCalendarOpen(true)} accessibilityLabel="Open schedule calendar" icon={<CalendarDays size={22} color={colors.brand} />} />
                    {dirty && (
                        <View
                            pointerEvents="none"
                            style={{ position: "absolute", top: -2, right: -2, width: 14, height: 14, borderRadius: 7, backgroundColor: colors.warning, borderWidth: 2, borderColor: colors.bg }}
                        />
                    )}
                </View>

                <SheetModal
                    visible={calendarOpen}
                    onRequestClose={closeCalendar}
                    heightFraction={0.9}
                    widthFraction={0.85}
                    header={<ModalHeader title={t("CALENDAR")} onClose={closeCalendar} />}
                    subHeader={
                        dirty ? (
                            <View style={styles.applyBar}>
                                <Text style={styles.applyBarText}>{t("Settings changed - recompute the schedule.")}</Text>
                                <Pressable onPress={() => runPreview()} disabled={previewLoading} style={[styles.applyButton, { opacity: previewLoading ? 0.6 : 1 }]}>
                                    <RefreshCw size={14} color={colors.warningContent} />
                                    <Text style={styles.applyButtonText}>{t("Apply")}</Text>
                                </Pressable>
                            </View>
                        ) : null
                    }
                    footer={
                        <Pressable
                            onPress={handleReset}
                            disabled={!hasOverrides}
                            style={[styles.resetButton, { opacity: hasOverrides ? 1 : 0.4 }]}
                            android_ripple={hasOverrides ? { color: colors.ripple } : undefined}
                            accessibilityLabel="Reset schedule to default"
                        >
                            <RotateCcw size={16} color={colors.text} />
                            <Text style={styles.resetButtonText}>{t("Reset to default")}</Text>
                        </Pressable>
                    }
                >
                    <CalendarTab
                        model={model}
                        legendFlags={legendFlags}
                        onSelectTurn={openTurn}
                        allowSummer={allowSummer}
                        contributingTurns={contributingTurns}
                        statsSlot={legendFlags.srs && previewStats ? <ScheduleStats stats={previewStats} totalScore={preview?.totalScore ?? 0} /> : null}
                    />
                    {legendFlags.srs && (
                        <EpithetRewards
                            targetEpithets={targetEpithets}
                            forcedEpithets={forcedEpithets}
                            preview={preview}
                            previewLoading={previewLoading}
                            highlightedEpithet={highlightedEpithet}
                            onToggleEpithet={toggleEpithet}
                        />
                    )}
                </SheetModal>

                <TurnDetailSheet
                    turn={selectedTurn}
                    model={model}
                    ctx={ctx}
                    mutators={mutators}
                    eligibleRacesByTurn={eligibleRacesByTurn}
                    allowedEpithetNames={allowedEpithetNames}
                    manualLocks={manualLocks}
                    onClose={closeTurn}
                />

                <SheetModal
                    visible={traineePickerOpen}
                    onRequestClose={() => setTraineePickerOpen(false)}
                    header={<ModalHeader title={t("TRAINEE")} onClose={() => setTraineePickerOpen(false)} />}
                    footer={null}
                    scrollableBody={false}
                >
                    <CharacterPresetSelector onSelect={() => setTraineePickerOpen(false)} />
                </SheetModal>
            </View>
        </SearchPageProvider>
    )
}

export default Schedule
