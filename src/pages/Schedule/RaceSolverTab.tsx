import { memo, useMemo, useContext, useState, useEffect, useCallback, ReactNode } from "react"
import { View, Text, StyleSheet, Pressable, InteractionManager } from "react-native"
import { FlashList } from "@shopify/flash-list"
import { Divider } from "react-native-paper"
import { useSolverInputs } from "../../hooks/useSolverInputs"
import { APTITUDE_RANKS, AptitudeMap, EpithetEntry, OPTIMIZE_MODE_LABELS, OPTIMIZE_MODE_PRESETS, OptimizeModeKey, WeightsMap } from "../../lib/solver/constants"
import { useTheme } from "../../context/ThemeContext"
import { RacingContext, GeneralMiscContext, defaultSettings } from "../../context/BotStateContext"
import CustomButton from "../../components/CustomButton"
import { Input } from "../../components/ui/input"
import epithetsData from "../../data/epithets.json"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import CustomSlider from "../../components/CustomSlider"
import { AptitudeRow, EpithetChip } from "./components/Helpers"
import { isEpithetAllowed } from "./raceEditing"
import { Trash2 } from "lucide-react-native"
import { Section } from "../../components/ui/section"
import { Row } from "../../components/ui/row"
import { Switch } from "../../components/ui/switch"
import { CountBadge } from "../../components/ui/count-badge"
import InfoCallout from "../../components/ui/info-callout"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** Number of epithet chips per grid row (chips are 31.5% wide, so three fit across). */
const EPITHET_COLUMNS = 3

/**
 * Split a flat list into fixed-size rows so a linear virtualized list (FlashList) can render a multi-column grid without a wrapping flex layout.
 * @param items The flat list to chunk.
 * @param perRow How many items go in each row.
 * @returns An array of rows, each an array of up to `perRow` items.
 */
function chunkRows<T>(items: T[], perRow: number): T[][] {
    const rows: T[][] = []
    for (let i = 0; i < items.length; i += perRow) rows.push(items.slice(i, i + perRow))
    return rows
}

/** Props for SubTopic. */
interface SubTopicProps {
    /** Section heading shown in `TYPE.h2`. */
    title: string
    /** Body content shown in `TYPE.body` with `textMuted` color. */
    children: ReactNode
}

/**
 * A titled paragraph used inside an InfoCallout body.
 * @param title Section heading shown in `TYPE.h2`.
 * @param children Body content shown in `TYPE.body` with `textMuted` color.
 * @returns A View with a heading and body text.
 */
const SubTopic = ({ title, children }: SubTopicProps) => {
    const { colors } = useTheme()
    return (
        <View style={{ marginBottom: SPACING.sm }}>
            <Text style={[TYPE.h2, { color: colors.text, marginBottom: SPACING.xs }]}>{title}</Text>
            <Text style={[TYPE.body, { color: colors.textMuted }]}>{children}</Text>
        </View>
    )
}

/**
 * Race Solver tab. Lets the user configure aptitudes, target/forced epithets, and scoring weights for the beam-search race scheduler.
 * @returns The rendered Race Solver tab content.
 */
function RaceSolverTab() {
    usePerformanceLogging("RaceSolverTab")
    const { colors } = useTheme()
    // Subscribe to context slices to avoid re-rendering on unrelated settings changes.
    const { racing, updateRacing } = useContext(RacingContext)
    const { general } = useContext(GeneralMiscContext)

    // Merge with defaults so partially-saved profiles keep working when fields are added.
    const racingSettings = useMemo(() => ({ ...defaultSettings.racing, ...racing }), [racing])
    const { enableSmartRaceSolver, disableScheduleReplanOnRaceLoss, smartRaceSolverCharacterPreset, smartRaceSolverMaxRaces, smartRaceSolverMaxConsecutiveRaces } = racingSettings

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Parsed state

    const { aptitudes, targetEpithets, forcedEpithets, manualLocks, weights } = useSolverInputs(racingSettings)

    const allEpithetsRaw = useMemo<EpithetEntry[]>(() => Object.values(epithetsData) as unknown as EpithetEntry[], [])

    /** Epithets visible in the target / forced pickers after applying the active scenario and character-preset gates. */
    const allEpithets = useMemo<EpithetEntry[]>(
        () => allEpithetsRaw.filter((e) => isEpithetAllowed(e, general?.scenario ?? "", smartRaceSolverCharacterPreset)),
        [allEpithetsRaw, general?.scenario, smartRaceSolverCharacterPreset]
    )

    /** User-facing notice describing which scenario / character filters are active, or null when none are. */
    const restrictionNotice = useMemo<string | null>(() => {
        const activeScenario = general?.scenario || "Trackblazer"
        const activePreset = smartRaceSolverCharacterPreset || ""
        const parts: string[] = [`${activeScenario}-scenario restriction in-effect`]
        if (activePreset) parts.push(`${activePreset} character restriction in-effect`)
        if (allEpithetsRaw.length === allEpithets.length) return null
        return `${parts.join(" + ")} — showing ${allEpithets.length} of ${allEpithetsRaw.length} epithets.`
    }, [allEpithets.length, allEpithetsRaw.length, general?.scenario, smartRaceSolverCharacterPreset])

    // Defer the heavy sections (preset list, epithet grids, rewards, summary) until after the first paint so the tab opens instantly.
    const [showHeavySections, setShowHeavySections] = useState(false)
    useEffect(() => {
        const handle = InteractionManager.runAfterInteractions(() => setShowHeavySections(true))
        return () => handle.cancel()
    }, [])

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Local input state for decimals

    const [raceValueInput, setRaceValueInput] = useState(weights.raceValue.toString())
    const [epithetValueInput, setEpithetValueInput] = useState(weights.epithetValue.toString())
    const [hintWeightInput, setHintWeightInput] = useState(weights.hintWeight.toString())
    const [targetBonusInput, setTargetBonusInput] = useState(weights.targetEpithetBonus.toString())
    const [consecPenaltyInput, setConsecPenaltyInput] = useState(weights.consecutiveRacePenalty.toString())
    const [summerPenaltyInput, setSummerPenaltyInput] = useState(weights.summerPenalty.toString())
    const [raceBonusPctInput, setRaceBonusPctInput] = useState(weights.raceBonusPct.toString())
    const [raceCostPctInput, setRaceCostPctInput] = useState(weights.raceCostPct.toString())
    const [fanWeightInput, setFanWeightInput] = useState(weights.fanWeight.toString())

    useEffect(() => setRaceValueInput(weights.raceValue.toString()), [weights.raceValue])
    useEffect(() => setEpithetValueInput(weights.epithetValue.toString()), [weights.epithetValue])
    useEffect(() => setHintWeightInput(weights.hintWeight.toString()), [weights.hintWeight])
    useEffect(() => setTargetBonusInput(weights.targetEpithetBonus.toString()), [weights.targetEpithetBonus])
    useEffect(() => setConsecPenaltyInput(weights.consecutiveRacePenalty.toString()), [weights.consecutiveRacePenalty])
    useEffect(() => setSummerPenaltyInput(weights.summerPenalty.toString()), [weights.summerPenalty])
    useEffect(() => setRaceBonusPctInput(weights.raceBonusPct.toString()), [weights.raceBonusPct])
    useEffect(() => setRaceCostPctInput(weights.raceCostPct.toString()), [weights.raceCostPct])
    useEffect(() => setFanWeightInput(weights.fanWeight.toString()), [weights.fanWeight])

    /** Derived optimization mode. Mode is not persisted - it falls out of the weights so the radio toggle and the slider can never disagree. */
    const currentOptimizeMode: OptimizeModeKey = weights.fanWeight > 0.0 ? "FANS_EPITAPH" : "STAT_EPITAPH"

    const [epithetSearch, setEpithetSearch] = useState("")
    const [forcedEpithetSearch, setForcedEpithetSearch] = useState("")

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Derived filters

    const filteredEpithets = useMemo(() => {
        if (!epithetSearch) return allEpithets
        const q = epithetSearch.toLowerCase()
        return allEpithets.filter((e) => e.name.toLowerCase().includes(q) || (e.bullet_points ?? []).join(" ").toLowerCase().includes(q))
    }, [allEpithets, epithetSearch])

    const filteredForcedEpithets = useMemo(() => {
        if (!forcedEpithetSearch) return allEpithets
        const q = forcedEpithetSearch.toLowerCase()
        return allEpithets.filter((e) => e.name.toLowerCase().includes(q) || (e.bullet_points ?? []).join(" ").toLowerCase().includes(q))
    }, [allEpithets, forcedEpithetSearch])

    // Grid rows for the virtualized epithet pickers - FlashList renders a row at a time, so only the visible ~2 rows of chips mount instead of all 119.
    const targetEpithetRows = useMemo(() => chunkRows(filteredEpithets, EPITHET_COLUMNS), [filteredEpithets])
    const forcedEpithetRows = useMemo(() => chunkRows(filteredForcedEpithets, EPITHET_COLUMNS), [filteredForcedEpithets])

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Setters

    /**
     * Update a single racing setting, preserving the rest of the racing block.
     *
     * @param key The settings.racing key to update.
     * @param value The new value.
     */
    const updateRacingSetting = (key: string, value: any) => {
        updateRacing({ [key]: value } as any)
    }

    /**
     * Set the rank for a single aptitude slot. Identity-stable so memoized children skip reconciliation on unrelated changes.
     *
     * @param slot The aptitude slot being changed.
     * @param rank The new rank (S..G).
     */
    const setAptitude = useCallback(
        (slot: keyof AptitudeMap, rank: string) => {
            updateRacing((prev) => {
                const prevAptitudes = JSON.parse(prev.smartRaceSolverAptitudes || "{}") as AptitudeMap
                return { ...prev, smartRaceSolverAptitudes: JSON.stringify({ ...prevAptitudes, [slot]: rank }) }
            })
        },
        [updateRacing]
    )

    /**
     * Toggle membership of `name` in the target epithets list. Identity-stable for memoized children.
     *
     * @param name The epithet name to toggle.
     */
    const toggleTargetEpithet = useCallback(
        (name: string) => {
            updateRacing((prev) => {
                const list = JSON.parse(prev.smartRaceSolverTargetEpithets || "[]") as string[]
                const next = list.includes(name) ? list.filter((n) => n !== name) : [...list, name]
                return { ...prev, smartRaceSolverTargetEpithets: JSON.stringify(next) }
            })
        },
        [updateRacing]
    )

    /**
     * Toggle membership of `name` in the forced epithets list. Identity-stable for memoized children.
     *
     * @param name The epithet name to toggle.
     */
    const toggleForcedEpithet = useCallback(
        (name: string) => {
            updateRacing((prev) => {
                const list = JSON.parse(prev.smartRaceSolverForcedEpithets || "[]") as string[]
                const next = list.includes(name) ? list.filter((n) => n !== name) : [...list, name]
                return { ...prev, smartRaceSolverForcedEpithets: JSON.stringify(next) }
            })
        },
        [updateRacing]
    )

    /**
     * Update a single scoring weight, preserving the rest.
     *
     * @param key The weight key to update.
     * @param value The new value.
     */
    const updateWeight = (key: keyof WeightsMap, value: number | string | boolean) => {
        updateRacingSetting("smartRaceSolverWeights", JSON.stringify({ ...weights, [key]: value }))
    }

    /**
     * Snap the editable weight sliders to the named optimization-mode preset. The user can still override individual sliders afterward
     * (the radio is derived from `weights.fanWeight > 0`, so manually tuning fanWeight back to 0 flips the radio without an extra click).
     *
     * @param mode Optimization mode key whose preset bundle should be applied.
     */
    const setOptimizeMode = (mode: OptimizeModeKey) => {
        const preset = OPTIMIZE_MODE_PRESETS[mode]
        updateRacingSetting("smartRaceSolverWeights", JSON.stringify({ ...weights, ...preset }))
    }

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Styles

    const styles = useMemo(
        () =>
            StyleSheet.create({
                description: { fontSize: 13, color: colors.textMuted, marginBottom: SPACING.md },
                inputLabel: { fontSize: 14, color: colors.text, marginBottom: 4, marginTop: 6 },
                input: { backgroundColor: colors.bg, color: colors.text, marginBottom: 4 },
                inputDescription: { fontSize: 12, color: colors.textMuted, marginBottom: 4 },
                row: { flexDirection: "row", flexWrap: "wrap", gap: 6, marginVertical: 4 },
                chip: {
                    width: "31.5%",
                    minHeight: 92,
                    paddingHorizontal: 8,
                    paddingVertical: 6,
                    borderRadius: 10,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    backgroundColor: colors.bg,
                    overflow: "hidden",
                },
                chipActive: { backgroundColor: colors.brand, borderColor: colors.brand },
                chipText: { color: colors.text, fontSize: 12, fontWeight: "600" },
                chipTextActive: { color: colors.onBrand, fontSize: 12, fontWeight: "700" },
                chipReward: { color: colors.textMuted, fontSize: 10, marginTop: 2 },
                chipRewardActive: { color: colors.onBrand, fontSize: 10, marginTop: 2, opacity: 0.9 },
                chipCondition: { color: colors.textMuted, fontSize: 10, fontStyle: "italic", marginTop: 2 },
                chipConditionActive: { color: colors.onBrand, fontSize: 10, fontStyle: "italic", marginTop: 2, opacity: 0.8 },
                chipNoMatcherDot: { position: "absolute", top: 8, right: 8, width: 8, height: 8, borderRadius: 4, backgroundColor: colors.destructive },
                aptRow: { flexDirection: "row", alignItems: "center", marginVertical: 4 },
                aptLabel: { width: 70, color: colors.text, fontSize: 13 },
                aptButtons: { flexDirection: "row", gap: 4, flex: 1 },
                aptBtn: {
                    flex: 1,
                    paddingVertical: 6,
                    borderRadius: 4,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    alignItems: "center",
                    backgroundColor: colors.bg,
                },
                aptBtnActive: { backgroundColor: colors.brand, borderColor: colors.brand },
                aptBtnText: { color: colors.text, fontSize: 12 },
                aptBtnTextActive: { color: colors.onBrand, fontSize: 12, fontWeight: "700" },
                epithetList: {
                    height: 600,
                    marginVertical: 4,
                    borderWidth: StyleSheet.hairlineWidth,
                    borderColor: colors.borderHair,
                    borderRadius: 6,
                },
                specCard: {
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.md,
                    overflow: "hidden",
                    marginTop: SPACING.sm,
                },
                specRow: { flexDirection: "row" as const, paddingHorizontal: SPACING.md, paddingVertical: SPACING.sm, gap: SPACING.md, alignItems: "flex-start" as const },
                specRowDivider: { borderTopWidth: 1, borderTopColor: colors.borderHair },
                specLabel: { ...TYPE.monoLabel, color: colors.textMuted, width: 84, paddingTop: 2 },
                specValue: { ...TYPE.monoValue, color: colors.text, flex: 1, flexWrap: "wrap" as const },
                specValueMuted: { ...TYPE.monoValue, color: colors.textMuted, flex: 1, fontStyle: "italic" as const },
                aptCellRow: { flexDirection: "row" as const, flex: 1, gap: 6, flexWrap: "wrap" as const },
                aptCell: {
                    minWidth: 44,
                    paddingHorizontal: 6,
                    paddingVertical: 4,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.sm,
                    backgroundColor: colors.surface,
                    alignItems: "center" as const,
                },
                aptCellLabel: { ...TYPE.monoLabel, color: colors.textMuted, fontSize: 9 },
                aptCellValue: { ...TYPE.monoValue, color: colors.text, fontSize: 14, marginTop: 1 },
                aptCellHighlighted: { borderColor: colors.brand, backgroundColor: colors.brandSubtle },
                aptCellHighlightedValue: { color: colors.brand },
                chipList: { flexDirection: "row" as const, flexWrap: "wrap" as const, gap: 4, flex: 1, alignItems: "center" as const },
                chipPill: {
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: 2,
                    backgroundColor: colors.surface,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.pill,
                },
                chipPillText: { ...TYPE.monoValue, color: colors.text, fontSize: 11 },
                weightGrid: { flex: 1, flexDirection: "row" as const, flexWrap: "wrap" as const, gap: 6 },
                weightCell: {
                    minWidth: "30%" as const,
                    flexGrow: 1,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: 4,
                    backgroundColor: colors.surface,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.sm,
                    flexDirection: "row" as const,
                    justifyContent: "space-between" as const,
                    alignItems: "center" as const,
                    gap: SPACING.xs,
                },
                weightKey: { ...TYPE.monoLabel, color: colors.textMuted, fontSize: 9 },
                weightVal: { ...TYPE.monoValue, color: colors.text, fontSize: 12 },
            }),
        [colors]
    )

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Helpers

    const renderAptitudeRow = (slot: keyof AptitudeMap, label: string) => <AptitudeRow key={slot} slot={slot} label={label} currentRank={aptitudes[slot]} onChange={setAptitude} styles={styles} />

    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // //////////////////////////////////////////////////////////////////////////////////////////////////
    // Render

    const sectionsDisabledStyle = enableSmartRaceSolver ? undefined : ({ opacity: 0.4 } as const)

    return (
        <View>
            {/* Master toggle */}
            <Section label="Smart Race Solver" firstDivider={false}>
                <SearchableItem
                    id="enable-smart-race-solver"
                    title="Enable Smart Race Solver"
                    description="Plans every turn of the career to maximize score by targeting epithet rewards. The bot only races when the solver picks a race; other turns become training or rest."
                >
                    <Row
                        title="Smart Race Solver"
                        description="Plans every turn of the career to maximize score by targeting epithet rewards. The bot only races when the solver picks a race; other turns become training or rest."
                        right={<Switch checked={enableSmartRaceSolver} onCheckedChange={(checked) => updateRacingSetting("enableSmartRaceSolver", checked)} />}
                    />
                </SearchableItem>

                <SearchableItem
                    id="smart-solver-how-it-works"
                    condition={enableSmartRaceSolver}
                    parentId="enable-smart-race-solver"
                    title="How it works"
                    description="Smart Race Solver overview, loss handling, race-history scrape, and notes on epithets without matchers."
                >
                    <View style={[sectionsDisabledStyle, { padding: SPACING.md }]}>
                        <InfoCallout title="How the solver works">
                            <SubTopic title="How it works">
                                The solver searches the entire 72-turn career and picks, for every turn, the best decision (Race / Train / Rest) that maximizes your projected score against the target
                                epithet rewards. The bot only races on the turns the solver has chosen in the calculated schedule - every other turn becomes training or rest, even when Farming Fans
                                would otherwise add an extra race. Hard goal requirements (fan / trophy / goal-points) and the Force Racing setting are the only things that can override the schedule.
                            </SubTopic>
                            <SubTopic title="What happens when you lose a race">
                                A loss is recorded against that turn and the solver immediately re-plans the remaining turns. Epithets that depended on the lost race may shift to alternative paths or
                                drop out entirely, so later races / trainings can change to keep the rest of the run on the highest-scoring track still available. Turn on "Disable Schedule Re-Plan
                                Upon Race Loss" to keep the original schedule after a loss instead of re-planning.
                            </SubTopic>
                            <SubTopic title="Race History scrape">
                                On bot start (and only when the career is past the pre-debut turns), the bot opens the in-game Career → Race History dialog and scrapes every past race entry. Each row
                                is matched to the race calendar so wins seed your epithet progress and losses are remembered when re-planning. This lets you stop and resume a career mid-run without
                                the solver forgetting what already happened.
                            </SubTopic>
                            <SubTopic title="Epithets without matchers">
                                Some epithets in the data set have no structured matchers in the code - usually because the in-game condition (like "Win your first G1 in Senior class") is difficult to
                                be modeled as a per-race rule. These are marked with a small red dot in the top-right corner of their chip. The solver treats them as untouched and never picks races to
                                advance them, so they won't be auto-completed. Adding one to Forced makes every candidate schedule infeasible since the condition can never be satisfied, so leave them
                                out of Forced even if you plan to earn them yourself in-game.
                            </SubTopic>
                        </InfoCallout>
                    </View>
                </SearchableItem>
            </Section>

            {enableSmartRaceSolver && (
                <Section label="General" collapsible defaultOpen={true}>
                    <ToggleSetting
                        id="disable-schedule-replan-on-race-loss"
                        title="Disable Schedule Re-Plan Upon Race Loss"
                        description="When a race is lost, keep the original schedule instead of re-planning the remaining turns. The loss is still recorded; epithets that depended on the lost race won't be re-routed."
                        condition={enableSmartRaceSolver}
                        parentId="enable-smart-race-solver"
                        checked={disableScheduleReplanOnRaceLoss}
                        onCheckedChange={(checked) => updateRacingSetting("disableScheduleReplanOnRaceLoss", checked)}
                    />

                    <View style={{ paddingHorizontal: SPACING.md, paddingVertical: SPACING.sm }}>
                        <CustomSlider
                            searchId="smart-solver-max-races"
                            searchCondition={enableSmartRaceSolver}
                            parentId="enable-smart-race-solver"
                            value={smartRaceSolverMaxRaces}
                            placeholder={defaultSettings.racing.smartRaceSolverMaxRaces}
                            onValueChange={(value) => updateRacingSetting("smartRaceSolverMaxRaces", value)}
                            onSlidingComplete={(value) => updateRacingSetting("smartRaceSolverMaxRaces", value)}
                            min={0}
                            max={40}
                            step={1}
                            label="Maximum Extra Races"
                            description="Caps how many optional races the solver schedules across the whole career. Mandatory career races always run and don't count toward this. 0 = no limit."
                            labelUnit=""
                            showValue={true}
                        />
                    </View>

                    <View style={{ paddingHorizontal: SPACING.md, paddingVertical: SPACING.sm }}>
                        <CustomSlider
                            searchId="smart-solver-max-consecutive-races"
                            searchCondition={enableSmartRaceSolver}
                            parentId="enable-smart-race-solver"
                            value={smartRaceSolverMaxConsecutiveRaces}
                            placeholder={defaultSettings.racing.smartRaceSolverMaxConsecutiveRaces}
                            onValueChange={(value) => updateRacingSetting("smartRaceSolverMaxConsecutiveRaces", value)}
                            onSlidingComplete={(value) => updateRacingSetting("smartRaceSolverMaxConsecutiveRaces", value)}
                            min={0}
                            max={10}
                            step={1}
                            label="Maximum Consecutive Races"
                            description="Caps how many races the solver schedules in back-to-back turns. Late-December turns are exempt so a chain may run into year-end. 0 = no limit."
                            labelUnit=""
                            showValue={true}
                        />
                    </View>

                    <SearchableItem
                        id="smart-solver-include-op"
                        condition={enableSmartRaceSolver}
                        parentId="enable-smart-race-solver"
                        title="Include OP / Pre-OP races"
                        description="By default the solver picks only G1/G2/G3 races. Enable this to also consider OP and Pre-OP races, useful for weaker characters whose only eligible races are OP/Pre-OP."
                    >
                        <Row
                            title="Include OP / Pre-OP races"
                            description="By default the solver picks only G1/G2/G3 races. Enable this to also consider OP and Pre-OP races. Useful for weaker characters (e.g. Haru Urara) who can't qualify for many graded races; OP races contribute much less to stats but at least give the solver something to schedule."
                            right={<Switch checked={weights.includeOpAndPreOp} onCheckedChange={(checked) => updateWeight("includeOpAndPreOp", checked)} />}
                        />
                    </SearchableItem>

                    <SearchableItem
                        id="smart-solver-allow-summer"
                        condition={enableSmartRaceSolver}
                        parentId="enable-smart-race-solver"
                        title="Allow racing during Summer (Classic / Senior)"
                        description="By default the Summer training camp turns (Early Jul to Late Aug) in Classic and Senior years are blocked from racing. Enable this to let the solver schedule races in those turns."
                    >
                        <Row
                            title="Allow racing during Summer (Classic / Senior)"
                            description="By default the Summer training camp turns (Early Jul → Late Aug) in Classic and Senior years are blocked from racing. Enable this to let the solver schedule races in those 4 turns each year - useful when a key epithet race lands in summer."
                            right={<Switch checked={weights.allowSummerRacing} onCheckedChange={(checked) => updateWeight("allowSummerRacing", checked)} />}
                        />
                    </SearchableItem>
                </Section>
            )}

            {enableSmartRaceSolver && showHeavySections && (
                <>
                    {/* Aptitudes */}
                    <Section label="Aptitudes">
                        <SearchableItem
                            id="smart-solver-aptitudes"
                            condition={enableSmartRaceSolver}
                            parentId="enable-smart-race-solver"
                            title="Aptitudes"
                            description="Distance and surface aptitude grades. Races below the threshold are skipped by the solver."
                        >
                            <View style={[sectionsDisabledStyle, { padding: SPACING.md }]}>
                                <Text style={styles.description}>Distance and surface aptitude grades. Races below the threshold are skipped by the solver.</Text>
                                {renderAptitudeRow("Sprint", "Sprint")}
                                {renderAptitudeRow("Mile", "Mile")}
                                {renderAptitudeRow("Medium", "Medium")}
                                {renderAptitudeRow("Long", "Long")}
                                <Divider style={{ marginVertical: 6 }} />
                                {renderAptitudeRow("Turf", "Turf")}
                                {renderAptitudeRow("Dirt", "Dirt")}
                            </View>
                        </SearchableItem>

                        <SearchableItem
                            id="smart-solver-aptitude-threshold"
                            condition={enableSmartRaceSolver}
                            parentId="enable-smart-race-solver"
                            title="Aptitude Threshold"
                            description="Minimum aptitude (distance AND surface) required for a race to be eligible."
                        >
                            <View style={[sectionsDisabledStyle, { padding: SPACING.md }]}>
                                <Text style={{ ...TYPE.body, color: colors.text, fontWeight: "600", marginBottom: SPACING.xs }}>Aptitude Threshold</Text>
                                <Text style={styles.description}>Minimum aptitude (distance AND surface) required for a race to be eligible.</Text>
                                <View style={styles.aptButtons}>
                                    {APTITUDE_RANKS.map((rank) => {
                                        const active = weights.aptitudeThreshold === rank
                                        return (
                                            <Pressable
                                                key={rank}
                                                style={[styles.aptBtn, active && styles.aptBtnActive]}
                                                onPress={() => updateWeight("aptitudeThreshold", rank)}
                                                android_ripple={{ color: active ? colors.rippleInverse : colors.ripple, foreground: true }}
                                            >
                                                <Text style={active ? styles.aptBtnTextActive : styles.aptBtnText}>{rank}</Text>
                                            </Pressable>
                                        )
                                    })}
                                </View>
                            </View>
                        </SearchableItem>
                    </Section>

                    {/* Epithets - shared informationals + Target / Forced sub-pickers */}
                    {enableSmartRaceSolver && (
                        <Section label="Epithets">
                            <View style={[sectionsDisabledStyle, { padding: SPACING.md, gap: SPACING.sm }]}>
                                {restrictionNotice && (
                                    <InfoCallout title={restrictionNotice} style={{ backgroundColor: colors.surfaceRaised }}>
                                        <Text style={[TYPE.body, { color: colors.text }]}>
                                            The epithet lists below are filtered to only those compatible with the current scenario and character preset. Change either to widen the lists.
                                        </Text>
                                    </InfoCallout>
                                )}
                                <InfoCallout
                                    title="Epithets with no structured matcher in the code"
                                    icon={
                                        <View style={{ width: 16, height: 16, alignItems: "center", justifyContent: "center" }}>
                                            <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: colors.destructive }} />
                                        </View>
                                    }
                                    style={{ backgroundColor: colors.surfaceRaised }}
                                >
                                    <Text style={[TYPE.body, { color: colors.text }]}>
                                        Their in-game conditions are too difficult or impossible to model as a per-race rule (like "Win your first G1 in Senior class"). The solver won't pick races to
                                        advance them. Adding one to Forced makes the schedule infeasible, so leave it out of Forced even if you plan to earn it manually in-game.
                                    </Text>
                                </InfoCallout>
                            </View>
                            <SearchableItem
                                id="smart-solver-target-epithets"
                                condition={enableSmartRaceSolver}
                                parentId="enable-smart-race-solver"
                                title="Target Epithets"
                                description="Epithets the solver actively pursues. Selecting one biases the schedule toward completing it."
                            >
                                <View style={[sectionsDisabledStyle, { padding: SPACING.md }]}>
                                    <View style={{ flexDirection: "row", alignItems: "center", marginBottom: SPACING.sm, gap: SPACING.sm }}>
                                        <View style={{ flex: 1 }}>
                                            <Text style={{ ...TYPE.body, color: colors.text, fontWeight: "600", marginBottom: SPACING.xs }}>Target Epithets</Text>
                                            <Text style={{ ...TYPE.caption, color: colors.textMuted, lineHeight: 18 }}>
                                                Selected <Text style={[TYPE.monoValue, { color: colors.text }]}>{targetEpithets.length}</Text> /{" "}
                                                <Text style={[TYPE.monoValue, { color: colors.text }]}>{filteredEpithets.length}</Text> epithets
                                            </Text>
                                        </View>
                                        <CustomButton icon={<Trash2 size={16} color={colors.text} />} onPress={() => updateRacingSetting("smartRaceSolverTargetEpithets", "[]")}>
                                            Clear
                                        </CustomButton>
                                    </View>
                                    <Text style={styles.description}>Epithets the solver actively pursues. Selecting one biases the schedule toward completing it.</Text>
                                    <Input style={styles.input} value={epithetSearch} onChangeText={setEpithetSearch} placeholder={`Search ${allEpithets.length} epithets…`} />
                                    <FlashList
                                        style={styles.epithetList}
                                        contentContainerStyle={{ padding: 6 }}
                                        data={targetEpithetRows}
                                        keyExtractor={(row) => row[0].name}
                                        renderItem={({ item }) => (
                                            <View style={styles.row}>
                                                {item.map((ep) => (
                                                    <EpithetChip key={ep.name} epithet={ep} selected={targetEpithets.includes(ep.name)} onToggle={toggleTargetEpithet} styles={styles} />
                                                ))}
                                            </View>
                                        )}
                                        keyboardShouldPersistTaps="handled"
                                        nestedScrollEnabled
                                    />
                                </View>
                            </SearchableItem>
                            <SearchableItem
                                id="smart-solver-forced-epithets"
                                condition={enableSmartRaceSolver}
                                parentId="enable-smart-race-solver"
                                title="Forced Epithets"
                                description="Epithets the solver MUST complete. If completion becomes impossible (for example a needed race was already lost), the solver stops planning. Use sparingly - each forced epithet narrows what the solver can pick."
                            >
                                <View style={[sectionsDisabledStyle, { padding: SPACING.md }]}>
                                    <View style={{ flexDirection: "row", alignItems: "center", marginBottom: SPACING.sm, gap: SPACING.sm }}>
                                        <View style={{ flex: 1 }}>
                                            <Text style={{ ...TYPE.body, color: colors.text, fontWeight: "600", marginBottom: SPACING.xs }}>Forced Epithets</Text>
                                            <Text style={{ ...TYPE.caption, color: colors.textMuted, lineHeight: 18 }}>
                                                Selected <Text style={[TYPE.monoValue, { color: colors.text }]}>{forcedEpithets.length}</Text> /{" "}
                                                <Text style={[TYPE.monoValue, { color: colors.text }]}>{filteredForcedEpithets.length}</Text> epithets
                                            </Text>
                                        </View>
                                        <CustomButton icon={<Trash2 size={16} color={colors.text} />} onPress={() => updateRacingSetting("smartRaceSolverForcedEpithets", "[]")}>
                                            Clear
                                        </CustomButton>
                                    </View>
                                    <Text style={styles.description}>
                                        Epithets the solver MUST complete. If completion becomes impossible (for example a needed race was already lost), the solver stops planning. Use sparingly -
                                        each forced epithet narrows what the solver can pick.
                                    </Text>
                                    <Input style={styles.input} value={forcedEpithetSearch} onChangeText={setForcedEpithetSearch} placeholder={`Search ${allEpithets.length} epithets…`} />
                                    <FlashList
                                        style={styles.epithetList}
                                        contentContainerStyle={{ padding: 6 }}
                                        data={forcedEpithetRows}
                                        keyExtractor={(row) => row[0].name}
                                        renderItem={({ item }) => (
                                            <View style={styles.row}>
                                                {item.map((ep) => (
                                                    <EpithetChip key={ep.name} epithet={ep} selected={forcedEpithets.includes(ep.name)} onToggle={toggleForcedEpithet} styles={styles} />
                                                ))}
                                            </View>
                                        )}
                                        keyboardShouldPersistTaps="handled"
                                        nestedScrollEnabled
                                    />
                                </View>
                            </SearchableItem>
                        </Section>
                    )}

                    {/* Optimization mode */}
                    <Section label="Optimization Mode">
                        <SearchableItem
                            id="smart-solver-optimize-mode"
                            condition={enableSmartRaceSolver}
                            parentId="enable-smart-race-solver"
                            title="Optimization Mode"
                            description="Pick whether the solver chases stat epitaphs or also emphasizes fan-heavy races."
                        >
                            <View style={[sectionsDisabledStyle, { padding: SPACING.md }]}>
                                <View style={styles.aptButtons}>
                                    {(Object.keys(OPTIMIZE_MODE_PRESETS) as OptimizeModeKey[]).map((mode) => {
                                        const active = currentOptimizeMode === mode
                                        return (
                                            <Pressable
                                                key={mode}
                                                style={[styles.aptBtn, active && styles.aptBtnActive]}
                                                onPress={() => setOptimizeMode(mode)}
                                                android_ripple={{ color: active ? colors.rippleInverse : colors.ripple, foreground: true }}
                                            >
                                                <Text style={active ? styles.aptBtnTextActive : styles.aptBtnText}>{OPTIMIZE_MODE_LABELS[mode]}</Text>
                                            </Pressable>
                                        )
                                    })}
                                </View>
                                <Text style={[styles.inputDescription, { marginBottom: 0, marginTop: 8 }]}>
                                    Stat Epitaphs (default) optimizes purely for stat-bearing epithets and ignores reward fans.{"\n\n"}Fans + Epitaphs adds a per-fan score so fan-rich races (G1s, big
                                    G3s) become more attractive alongside epithets.{"\n\n"}Switching modes snaps the editable Race Value, Epithet Value, and Fan Weight sliders to a fresh preset; you
                                    can still tune each slider afterward, and tapping the active mode again resets back to the preset.
                                </Text>
                            </View>
                        </SearchableItem>
                    </Section>

                    {/* Weights */}
                    <Section label="Scoring Weights">
                        <SearchableItem
                            id="smart-solver-weights"
                            condition={enableSmartRaceSolver}
                            parentId="enable-smart-race-solver"
                            title="Scoring Weights"
                            description="Tune how the solver balances race value, epithet completion, fan rewards, and penalties."
                        >
                            <View style={[sectionsDisabledStyle, { padding: SPACING.md }]}>
                                <Text style={styles.description}>Tune how the solver balances race value, epithet completion, fan rewards, and penalties.</Text>
                                <Section label="Show advanced weights" collapsible defaultOpen={false}>
                                    <View style={{ padding: SPACING.md }}>
                                        <Pressable android_ripple={{ color: colors.ripple, foreground: true }}>
                                            <Text style={styles.inputLabel}>Race Value Weight</Text>
                                            <Input
                                                style={styles.input}
                                                value={raceValueInput}
                                                onChangeText={(t) => /^-?\d*\.?\d*$/.test(t) && setRaceValueInput(t)}
                                                onBlur={() => updateWeight("raceValue", parseFloat(raceValueInput) || 0)}
                                                keyboardType="decimal-pad"
                                                placeholder="1.0"
                                            />
                                            <Text style={styles.inputDescription}>
                                                Multiplier on every race's stat + SP reward. Default 1.0. Raise to 2.0 to make the schedule more race-heavy; lower to 0.5 to favor training.
                                            </Text>
                                        </Pressable>

                                        <Pressable android_ripple={{ color: colors.ripple, foreground: true }}>
                                            <Text style={styles.inputLabel}>Epithet Value Weight</Text>
                                            <Input
                                                style={styles.input}
                                                value={epithetValueInput}
                                                onChangeText={(t) => /^-?\d*\.?\d*$/.test(t) && setEpithetValueInput(t)}
                                                onBlur={() => updateWeight("epithetValue", parseFloat(epithetValueInput) || 0)}
                                                keyboardType="decimal-pad"
                                                placeholder="1.0"
                                            />
                                            <Text style={styles.inputDescription}>
                                                Multiplier on epithet stat rewards. Default 1.0 weights an epithet's stats equally with race stats. Raise to 5.0 if you want the solver to chase
                                                epithets even at the cost of fewer total races.
                                            </Text>
                                        </Pressable>

                                        <Pressable android_ripple={{ color: colors.ripple, foreground: true }}>
                                            <Text style={styles.inputLabel}>Fan Weight</Text>
                                            <Input
                                                style={styles.input}
                                                value={fanWeightInput}
                                                onChangeText={(t) => /^-?\d*\.?\d*$/.test(t) && setFanWeightInput(t)}
                                                onBlur={() => updateWeight("fanWeight", parseFloat(fanWeightInput) || 0)}
                                                keyboardType="decimal-pad"
                                                placeholder="0.0"
                                            />
                                            <Text style={styles.inputDescription}>
                                                Score per fan earned from a race. Default 0.0 ignores fans entirely (Stat Epitaphs preset). 0.001 (Fans + Epitaphs preset) makes a 25k-fan G1 worth ~25
                                                score points - meaningful but not dominant. Above 0.005 the solver will race almost every eligible turn.
                                            </Text>
                                        </Pressable>

                                        <Pressable android_ripple={{ color: colors.ripple, foreground: true }}>
                                            <Text style={styles.inputLabel}>Hint Reward Weight</Text>
                                            <Input
                                                style={styles.input}
                                                value={hintWeightInput}
                                                onChangeText={(t) => /^-?\d*\.?\d*$/.test(t) && setHintWeightInput(t)}
                                                onBlur={() => updateWeight("hintWeight", parseFloat(hintWeightInput) || 0)}
                                                keyboardType="decimal-pad"
                                                placeholder="8.0"
                                            />
                                            <Text style={styles.inputDescription}>
                                                Score given for completing a skill-hint epithet (one that grants a skill instead of stats). Default 8.0 ≈ value of one G1 race. Drop to 0 to skip
                                                hint-only epithets entirely.
                                            </Text>
                                        </Pressable>

                                        <Pressable android_ripple={{ color: colors.ripple, foreground: true }}>
                                            <Text style={styles.inputLabel}>Target Epithet Bonus</Text>
                                            <Input
                                                style={styles.input}
                                                value={targetBonusInput}
                                                onChangeText={(t) => /^-?\d*\.?\d*$/.test(t) && setTargetBonusInput(t)}
                                                onBlur={() => updateWeight("targetEpithetBonus", parseFloat(targetBonusInput) || 0)}
                                                keyboardType="decimal-pad"
                                                placeholder="25.0"
                                            />
                                            <Text style={styles.inputDescription}>
                                                Score added to an epithet you picked as a Target, on top of whatever its reward is worth. Most epithets grant no listed reward at all, so without this a
                                                target scores 0 and the solver has no reason to chase it. Default 25.0 makes the solver pursue a target but still drop one that would wreck the rest of
                                                the schedule. Raise it toward a Forced epithet's behavior, or set 0 to make Target selection purely informational.
                                            </Text>
                                        </Pressable>

                                        <Pressable android_ripple={{ color: colors.ripple, foreground: true }}>
                                            <Text style={styles.inputLabel}>Consecutive Race Penalty</Text>
                                            <Input
                                                style={styles.input}
                                                value={consecPenaltyInput}
                                                onChangeText={(t) => /^-?\d*\.?\d*$/.test(t) && setConsecPenaltyInput(t)}
                                                onBlur={() => updateWeight("consecutiveRacePenalty", parseFloat(consecPenaltyInput) || 0)}
                                                keyboardType="decimal-pad"
                                                placeholder="3.0"
                                            />
                                            <Text style={styles.inputDescription}>
                                                Penalty per race when racing 3+ turns in a row. Models in-game motivation/condition loss. Late-Dec turns (23, 47, 71) are exempt because the year ends
                                                there. Set to 0 to disable.
                                            </Text>
                                        </Pressable>

                                        <Pressable android_ripple={{ color: colors.ripple, foreground: true }}>
                                            <Text style={styles.inputLabel}>Summer Block Penalty</Text>
                                            <Input
                                                style={styles.input}
                                                value={summerPenaltyInput}
                                                onChangeText={(t) => /^-?\d*\.?\d*$/.test(t) && setSummerPenaltyInput(t)}
                                                onBlur={() => updateWeight("summerPenalty", parseFloat(summerPenaltyInput) || 0)}
                                                keyboardType="decimal-pad"
                                                placeholder="5.0"
                                            />
                                            <Text style={styles.inputDescription}>
                                                Penalty for racing during summer training camps (turns 12-14, 36-39, 60-63). High enough to discourage racing through summer, low enough that an
                                                epithet-completing race can still be picked.
                                            </Text>
                                        </Pressable>

                                        <Pressable android_ripple={{ color: colors.ripple, foreground: true }}>
                                            <Text style={styles.inputLabel}>Race Bonus %</Text>
                                            <Input
                                                style={styles.input}
                                                value={raceBonusPctInput}
                                                onChangeText={(t) => /^-?\d*\.?\d*$/.test(t) && setRaceBonusPctInput(t)}
                                                onBlur={() => updateWeight("raceBonusPct", parseFloat(raceBonusPctInput) || 0)}
                                                keyboardType="decimal-pad"
                                                placeholder="50.0"
                                            />
                                            <Text style={styles.inputDescription}>
                                                Percentage uplift applied to base stat/SP reward of every race before scoring. Default 50%. Higher = the solver picks more races overall.
                                            </Text>
                                        </Pressable>

                                        <Pressable android_ripple={{ color: colors.ripple, foreground: true }}>
                                            <Text style={styles.inputLabel}>Race Cost %</Text>
                                            <Input
                                                style={styles.input}
                                                value={raceCostPctInput}
                                                onChangeText={(t) => /^-?\d*\.?\d*$/.test(t) && setRaceCostPctInput(t)}
                                                onBlur={() => updateWeight("raceCostPct", parseFloat(raceCostPctInput) || 0)}
                                                keyboardType="decimal-pad"
                                                placeholder="100.0"
                                            />
                                            <Text style={styles.inputDescription}>
                                                Cost subtracted from each race's reward, expressed as a percentage of a G2 race's baseline value. At 100 (default), G2 and G3 races score zero net and
                                                only get raced when they progress an epithet. Lower this to schedule more races.
                                            </Text>
                                        </Pressable>
                                    </View>
                                </Section>
                            </View>
                        </SearchableItem>
                    </Section>

                    {/* Diagnostic */}
                    <Section label="Configuration Summary">
                        <SearchableItem
                            id="smart-solver-diagnostic"
                            condition={enableSmartRaceSolver}
                            parentId="enable-smart-race-solver"
                            title="Configuration Summary"
                            description="Read-only summary of the current solver configuration."
                        >
                            <View style={[sectionsDisabledStyle, { padding: SPACING.md }]}>
                                <View style={styles.specCard}>
                                    <View style={styles.specRow}>
                                        <Text style={styles.specLabel}>Preset</Text>
                                        <Text style={smartRaceSolverCharacterPreset ? styles.specValue : styles.specValueMuted}>{smartRaceSolverCharacterPreset || "(none)"}</Text>
                                    </View>
                                    <View style={[styles.specRow, styles.specRowDivider]}>
                                        <Text style={styles.specLabel}>Mode</Text>
                                        <Text style={styles.specValue}>{OPTIMIZE_MODE_LABELS[currentOptimizeMode]}</Text>
                                    </View>
                                    <View style={[styles.specRow, styles.specRowDivider]}>
                                        <Text style={styles.specLabel}>Aptitudes</Text>
                                        <View style={styles.aptCellRow}>
                                            {(
                                                [
                                                    { key: "SPR", val: aptitudes.Sprint },
                                                    { key: "MIL", val: aptitudes.Mile },
                                                    { key: "MED", val: aptitudes.Medium },
                                                    { key: "LNG", val: aptitudes.Long },
                                                    { key: "TRF", val: aptitudes.Turf },
                                                    { key: "DRT", val: aptitudes.Dirt },
                                                ] as const
                                            ).map((a) => (
                                                <View key={a.key} style={styles.aptCell}>
                                                    <Text style={styles.aptCellLabel}>{a.key}</Text>
                                                    <Text style={styles.aptCellValue}>{a.val}</Text>
                                                </View>
                                            ))}
                                            <View style={[styles.aptCell, styles.aptCellHighlighted]}>
                                                <Text style={styles.aptCellLabel}>MIN</Text>
                                                <Text style={[styles.aptCellValue, styles.aptCellHighlightedValue]}>{weights.aptitudeThreshold}</Text>
                                            </View>
                                        </View>
                                    </View>
                                    <View style={[styles.specRow, styles.specRowDivider]}>
                                        <Text style={styles.specLabel}>Targets</Text>
                                        {targetEpithets.length === 0 ? (
                                            <Text style={styles.specValueMuted}>(none)</Text>
                                        ) : (
                                            <View style={styles.chipList}>
                                                <CountBadge count={targetEpithets.length} />
                                                {targetEpithets.map((e) => (
                                                    <View key={e} style={styles.chipPill}>
                                                        <Text style={styles.chipPillText}>{e}</Text>
                                                    </View>
                                                ))}
                                            </View>
                                        )}
                                    </View>
                                    <View style={[styles.specRow, styles.specRowDivider]}>
                                        <Text style={styles.specLabel}>Forced</Text>
                                        {forcedEpithets.length === 0 ? (
                                            <Text style={styles.specValueMuted}>(none)</Text>
                                        ) : (
                                            <View style={styles.chipList}>
                                                <CountBadge count={forcedEpithets.length} />
                                                {forcedEpithets.map((e) => (
                                                    <View key={e} style={styles.chipPill}>
                                                        <Text style={styles.chipPillText}>{e}</Text>
                                                    </View>
                                                ))}
                                            </View>
                                        )}
                                    </View>
                                    <View style={[styles.specRow, styles.specRowDivider]}>
                                        <Text style={styles.specLabel}>Locks</Text>
                                        {Object.keys(manualLocks).length === 0 ? (
                                            <Text style={styles.specValueMuted}>(none)</Text>
                                        ) : (
                                            <View style={styles.chipList}>
                                                <CountBadge count={Object.keys(manualLocks).length} />
                                                {Object.entries(manualLocks).map(([t, r]) => (
                                                    <View key={t} style={styles.chipPill}>
                                                        <Text style={styles.chipPillText}>{`T${t} → ${r}`}</Text>
                                                    </View>
                                                ))}
                                            </View>
                                        )}
                                    </View>
                                    <View style={[styles.specRow, styles.specRowDivider]}>
                                        <Text style={styles.specLabel}>Weights</Text>
                                        <View style={styles.weightGrid}>
                                            {(
                                                [
                                                    { key: "RACE", val: `${weights.raceValue}` },
                                                    { key: "EPITHET", val: `${weights.epithetValue}` },
                                                    { key: "FANS", val: `${weights.fanWeight}` },
                                                    { key: "HINT", val: `${weights.hintWeight}` },
                                                    { key: "TARGET", val: `${weights.targetEpithetBonus}` },
                                                    { key: "CONSEC", val: `-${weights.consecutiveRacePenalty}` },
                                                    { key: "SUMMER", val: `-${weights.summerPenalty}` },
                                                    { key: "RACE BONUS", val: `${weights.raceBonusPct}%` },
                                                    { key: "RACE COST", val: `${weights.raceCostPct}%` },
                                                ] as const
                                            ).map((w) => (
                                                <View key={w.key} style={styles.weightCell}>
                                                    <Text style={styles.weightKey}>{w.key}</Text>
                                                    <Text style={styles.weightVal}>{w.val}</Text>
                                                </View>
                                            ))}
                                        </View>
                                    </View>
                                </View>
                            </View>
                        </SearchableItem>
                    </Section>
                </>
            )}
        </View>
    )
}

export default memo(RaceSolverTab)
