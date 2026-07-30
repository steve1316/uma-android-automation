import { useMemo, useContext, useRef, useCallback, useState } from "react"
import { View, Text, TextInput, ScrollView, StyleSheet, Pressable } from "react-native"
import { useNavigation } from "@react-navigation/native"
import { useTheme } from "../../context/ThemeContext"
import { RacingContext, defaultSettings, Settings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import CustomSlider from "../../components/CustomSlider"
import PageHeader from "../../components/PageHeader"
import InfoContainer from "../../components/InfoContainer"
import WarningContainer from "../../components/WarningContainer"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import { Row } from "../../components/ui/row"
import { Section } from "../../components/ui/section"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { ValuePill } from "../../components/ui/value-pill"
import { ModalHeader } from "../../components/ui/modal-header"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"

/** Available race strategy values for both Junior Year and Original strategy pickers. */
const RACE_STRATEGY_OPTIONS = ["Default", "Auto", "Front", "Pace", "Late", "End"] as const

type RaceStrategy = (typeof RACE_STRATEGY_OPTIONS)[number]

/** Short explanations for the ambiguous strategy values. Front/Pace/Late/End are self-explanatory running styles and are intentionally left out. */
const STRATEGY_OPTION_DESCRIPTIONS: Partial<Record<RaceStrategy, string>> = {
    Default: "Keeps the running style already set in-game. The bot won't change it.",
    Auto: "Picks the running style your trainee has the best aptitude for.",
}

/** Intro line shown at the top of the single-strategy picker modals. */
const STRATEGY_PICKER_DESCRIPTION = "Pick the running style the bot uses for these races."

/** In-game race agenda slots the user can select from. */
const RACE_AGENDA_OPTIONS = ["Agenda 1", "Agenda 2", "Agenda 3", "Agenda 4", "Agenda 5", "Agenda 6", "Agenda 7", "Agenda 8"] as const

/** Track distance keys shared by the Junior and Classic/Senior per-distance strategy grids. */
type PerDistanceKey = "Short" | "Mile" | "Medium" | "Long"

/**
 * The Racing Settings page.
 * Provides configuration for race behavior, race strategies, force racing, and in-game race agenda.
 */
const RacingSettings = () => {
    usePerformanceLogging("RacingSettings")
    const { colors } = useTheme()
    const modalShellStyles = useModalShellStyles()
    const navigation = useNavigation()
    const { racing, updateRacing } = useContext(RacingContext)
    const scrollViewRef = useRef<ScrollView>(null)

    // Modal state for the Junior / Original strategy pickers (nav-row + chip pattern).
    const [juniorPickerOpen, setJuniorPickerOpen] = useState(false)
    const [originalPickerOpen, setOriginalPickerOpen] = useState(false)
    const [agendaPickerOpen, setAgendaPickerOpen] = useState(false)
    const [perDistancePicker, setPerDistancePicker] = useState<{ year: "junior" | "original"; distance: PerDistanceKey } | null>(null)

    // Merge current racing settings with defaults to handle missing properties.
    const racingSettings = { ...defaultSettings.racing, ...racing }
    const {
        ignoreConsecutiveRaceWarning,
        minEnergyForExtraRacing,
        disableRaceRetries,
        enableFreeRaceRetry,
        enableCompleteCareerOnFailure,
        enableStopOnMandatoryRaces,
        enableForceRacing,
        enableG1DayPreference,
        g1DayMinRainbowCount,
        juniorYearRaceStrategy,
        originalRaceStrategy,
        enablePerDistanceStrategy,
        juniorYearPerDistanceStrategies,
        originalPerDistanceStrategies,
        enableUserInGameRaceAgenda,
        limitRacesToInGameAgenda,
        skipSummerTrainingForAgenda,
        customAgendaTitle,
    } = racingSettings

    /**
     * Update a racing setting with special handling for the in-game race agenda.
     * When the in-game race agenda is enabled, it automatically disables the Smart Race Solver setting to prevent conflicts.
     * @param key The key of the setting to update.
     * @param value The value to set the setting to.
     */
    const updateRacingSetting = useCallback(
        (key: keyof Settings["racing"], value: any) => {
            if (key === "enableUserInGameRaceAgenda" && value) {
                updateRacing((prev) => ({
                    // Disable the Smart Race Solver when User In Game Race Agenda is enabled.
                    ...prev,
                    enableUserInGameRaceAgenda: true,
                    enableSmartRaceSolver: false,
                }))
            } else {
                updateRacing({ [key]: value } as Partial<Settings["racing"]>)
            }
        },
        [updateRacing]
    )

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
                section: {
                    marginBottom: 24,
                },
                inputContainer: {
                    marginBottom: 16,
                },
                inputLabel: {
                    fontSize: 16,
                    color: colors.text,
                    marginBottom: 8,
                },
                input: {
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: 8,
                    padding: 12,
                    fontSize: 16,
                    color: colors.text,
                    backgroundColor: colors.bg,
                },
                inputDescription: {
                    fontSize: 14,
                    color: colors.text,
                    opacity: 0.7,
                    marginTop: 4,
                },
                perDistanceGroupLabel: { ...TYPE.monoLabel, color: colors.textMuted, paddingHorizontal: SPACING.lg, paddingTop: SPACING.md, paddingBottom: SPACING.xs },
                perDistanceBody: { paddingHorizontal: SPACING.lg, paddingBottom: SPACING.md, gap: SPACING.sm },
                perDistanceItem: { flexDirection: "row" as const, alignItems: "center" as const, gap: SPACING.md },
            }),
        [colors]
    )

    /**
     * Render the modal contents for a strategy picker.
     * @param current The currently selected strategy.
     * @param onSelect Called when the user picks a new value (the modal close is handled by the caller).
     * @returns A list of pressable option rows.
     */
    // `current` is typed `string` to match the context shape; if the stored value is outside RACE_STRATEGY_OPTIONS, no row renders as selected.
    const renderStrategyOptions = (current: string, onSelect: (value: RaceStrategy) => void) => (
        <View style={modalShellStyles.modalBodyList}>
            {RACE_STRATEGY_OPTIONS.map((option) => (
                <ModalRadioRow key={option} label={option} description={STRATEGY_OPTION_DESCRIPTIONS[option]} selected={option === current} onPress={() => onSelect(option)} />
            ))}
        </View>
    )

    return (
        <View style={styles.root}>
            <SearchPageProvider page="RacingSettings" scrollViewRef={scrollViewRef}>
                <PageHeader title="Racing Settings" />
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            Race Behavior */}
                        <Section label="Race Behavior">
                            <ToggleSetting
                                id="ignore-consecutive-race-warning"
                                title="Ignore Consecutive Race Warning"
                                description="When enabled, the bot will ignore the warning popup about consecutive races and continue racing."
                                checked={ignoreConsecutiveRaceWarning}
                                onCheckedChange={(checked) => updateRacingSetting("ignoreConsecutiveRaceWarning", checked)}
                            />
                            <ToggleSetting
                                id="disable-race-retries"
                                title="Disable Race Retries"
                                description="When enabled, the bot will not retry mandatory races if they fail and will stop."
                                checked={disableRaceRetries}
                                onCheckedChange={(checked) => updateRacingSetting("disableRaceRetries", checked)}
                            />
                            <ToggleSetting
                                id="enable-free-race-retry"
                                title="Allow Daily Free Race Retry"
                                description="When enabled, the bot will attempt to retry a failed mandatory race only if the daily free race retry is available."
                                condition={disableRaceRetries}
                                parentId="disable-race-retries"
                                checked={enableFreeRaceRetry}
                                onCheckedChange={(checked) => updateRacingSetting("enableFreeRaceRetry", checked)}
                            />
                            <ToggleSetting
                                id="enable-complete-career-on-failure"
                                title="Complete Career on Failure"
                                description="When enabled, the bot will proceed to the career completion screen when a mandatory race fails and retries are exhausted."
                                checked={enableCompleteCareerOnFailure}
                                onCheckedChange={(checked) => updateRacingSetting("enableCompleteCareerOnFailure", checked)}
                            />
                            <ToggleSetting
                                id="enable-stop-on-mandatory-races"
                                title="Stop on Mandatory Races"
                                description="When enabled, the bot will automatically stop when it encounters a mandatory race, allowing you to manually handle them."
                                checked={enableStopOnMandatoryRaces}
                                onCheckedChange={(checked) => updateRacingSetting("enableStopOnMandatoryRaces", checked)}
                            />
                            <ToggleSetting
                                id="enable-force-racing"
                                title="Force Racing"
                                description="When enabled, the bot will skip all training, rest, and mood recovery activities and focus exclusively on racing every day."
                                checked={enableForceRacing}
                                onCheckedChange={(checked) => updateRacingSetting("enableForceRacing", checked)}
                            />
                            {enableForceRacing && <WarningContainer>Warning: Enabling this will override all other racing settings and they will be ignored.</WarningContainer>}
                            <ToggleSetting
                                id="enable-g1-day-preference"
                                title="Prefer Training on G1 Days"
                                description="On a G1 race day (Classic/Senior years), peek at the trainings first and stay to train when a strong rainbow training is available instead of taking the race."
                                checked={enableG1DayPreference}
                                onCheckedChange={(checked) => updateRacingSetting("enableG1DayPreference", checked)}
                            />
                            {enableG1DayPreference && (
                                <View style={{ padding: SPACING.md }}>
                                    <CustomSlider
                                        searchId="g1-day-min-rainbow-count"
                                        value={g1DayMinRainbowCount}
                                        placeholder={defaultSettings.racing.g1DayMinRainbowCount}
                                        onValueChange={(value) => updateRacingSetting("g1DayMinRainbowCount", value)}
                                        min={1}
                                        max={5}
                                        step={1}
                                        label="Minimum Rainbows to Train Over G1"
                                        showValue={true}
                                        showLabels={true}
                                        description="The best training must have at least this many rainbow supports to train instead of racing the G1."
                                    />
                                    <CustomSlider
                                        searchId="min-energy-for-g1-prescreen"
                                        value={minEnergyForExtraRacing}
                                        placeholder={defaultSettings.racing.minEnergyForExtraRacing}
                                        onValueChange={(value) => updateRacingSetting("minEnergyForExtraRacing", value)}
                                        min={0}
                                        max={100}
                                        step={5}
                                        label="Minimum Energy for G1 Pre-Screen"
                                        showValue={true}
                                        showLabels={true}
                                        description="Skip the training peek and take the G1 race when energy is below this percentage. 0 disables the floor so the peek always runs."
                                    />
                                </View>
                            )}
                        </Section>

                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            Strategy */}
                        <Section label="Strategy">
                            <ToggleSetting
                                id="enable-per-distance-strategy"
                                title="Per-Distance Strategy"
                                description="When enabled, allows setting different race strategies for each track distance."
                                checked={enablePerDistanceStrategy}
                                onCheckedChange={(checked) => updateRacingSetting("enablePerDistanceStrategy", checked)}
                            />

                            {!enablePerDistanceStrategy ? (
                                <>
                                    <SearchableItem id="junior-year-race-strategy" title="Junior Year Race Strategy" description="The race strategy to use for all races during Junior Year.">
                                        <Row
                                            title="Junior Year Strategy"
                                            description="The race strategy to use for all races during Junior Year."
                                            onPress={() => setJuniorPickerOpen(true)}
                                            right={<ValuePill label={juniorYearRaceStrategy} />}
                                        />
                                    </SearchableItem>
                                    <SearchableItem
                                        id="original-race-strategy"
                                        title="Original Race Strategy"
                                        description="The race strategy to reset to after Junior Year. The bot will use this strategy for races in Year 2 and beyond."
                                    >
                                        <Row
                                            title="Original Strategy"
                                            description="The race strategy to reset to after Junior Year. The bot will use this strategy for races in Year 2 and beyond."
                                            onPress={() => setOriginalPickerOpen(true)}
                                            right={<ValuePill label={originalRaceStrategy} />}
                                        />
                                    </SearchableItem>
                                </>
                            ) : (
                                <>
                                    <View style={{ padding: SPACING.md, paddingBottom: 0 }}>
                                        <Text style={[TYPE.caption, { color: colors.textMuted }]}>
                                            Set a different race strategy for each track distance. Auto picks the best strategy. Default leaves the in-game strategy alone.
                                        </Text>
                                    </View>
                                    <View>
                                        <Text style={styles.perDistanceGroupLabel}>JUNIOR YEAR</Text>
                                        <View style={styles.perDistanceBody}>
                                            {(["Short", "Mile", "Medium", "Long"] as const).map((distance) => (
                                                <SearchableItem
                                                    key={`junior-${distance}`}
                                                    id={`junior-strategy-${distance.toLowerCase()}`}
                                                    title={`Junior Year ${distance} Distance Strategy`}
                                                    description={`The race strategy to use for ${distance.toLowerCase()} distance races during Junior Year.`}
                                                >
                                                    <View style={styles.perDistanceItem}>
                                                        <Text style={[TYPE.body, { color: colors.text, flex: 1 }]}>{distance}</Text>
                                                        <Pressable
                                                            onPress={() => setPerDistancePicker({ year: "junior", distance })}
                                                            android_ripple={{ color: colors.ripple, foreground: true }}
                                                            accessibilityRole="button"
                                                        >
                                                            <ValuePill label={juniorYearPerDistanceStrategies?.[distance] ?? "Default"} />
                                                        </Pressable>
                                                    </View>
                                                </SearchableItem>
                                            ))}
                                        </View>
                                    </View>
                                    <View>
                                        <Text style={[styles.perDistanceGroupLabel, { paddingTop: 0 }]}>CLASSIC AND SENIOR YEAR</Text>
                                        <View style={styles.perDistanceBody}>
                                            {(["Short", "Mile", "Medium", "Long"] as const).map((distance) => (
                                                <SearchableItem
                                                    key={`original-${distance}`}
                                                    id={`original-strategy-${distance.toLowerCase()}`}
                                                    title={`Original ${distance} Distance Strategy`}
                                                    description={`The race strategy to use for ${distance.toLowerCase()} distance races in Year 2 and beyond.`}
                                                >
                                                    <View style={styles.perDistanceItem}>
                                                        <Text style={[TYPE.body, { color: colors.text, flex: 1 }]}>{distance}</Text>
                                                        <Pressable
                                                            onPress={() => setPerDistancePicker({ year: "original", distance })}
                                                            android_ripple={{ color: colors.ripple, foreground: true }}
                                                            accessibilityRole="button"
                                                        >
                                                            <ValuePill label={originalPerDistanceStrategies?.[distance] ?? "Default"} />
                                                        </Pressable>
                                                    </View>
                                                </SearchableItem>
                                            ))}
                                        </View>
                                    </View>
                                </>
                            )}
                        </Section>

                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            In-Game Race Agenda */}
                        <Section label="In-Game Race Agenda">
                            <ToggleSetting
                                id="enable-user-in-game-race-agenda"
                                title="Enable User In-Game Race Agenda"
                                description="When enabled, the bot will load your selected in-game race agenda and race the turns it lists. Note that this will turn off the Smart Race Solver, since the two cannot both own the racing schedule."
                                checked={enableUserInGameRaceAgenda}
                                onCheckedChange={(checked) => updateRacingSetting("enableUserInGameRaceAgenda", checked)}
                            />
                            {enableUserInGameRaceAgenda && (
                                <>
                                    <InfoContainer style={{ marginHorizontal: SPACING.md }}>
                                        Critical energy level and consecutive race limits are ignored for the user in-game racing agenda.
                                    </InfoContainer>
                                    <SearchableItem
                                        id="user-in-game-race-agenda"
                                        title="Select Agenda"
                                        description="The in-game race agenda the bot loads when the toggle above is enabled."
                                        parentId="enable-user-in-game-race-agenda"
                                    >
                                        <Row
                                            title="Select Agenda"
                                            description="The in-game race agenda the bot loads when the toggle above is enabled."
                                            onPress={() => setAgendaPickerOpen(true)}
                                            right={<ValuePill label={racingSettings.selectedUserAgenda} />}
                                        />
                                    </SearchableItem>
                                    <SearchableItem
                                        id="custom-agenda-title"
                                        title="Custom Agenda Title"
                                        description="If you renamed your agenda in-game, enter the custom title here. Leave blank to use the selected agenda name above."
                                        parentId="enable-user-in-game-race-agenda"
                                    >
                                        <View style={{ padding: SPACING.md, gap: SPACING.xs }}>
                                            <Text style={[TYPE.body, { color: colors.text, fontWeight: "500" as const }]}>Custom Agenda Title (Optional)</Text>
                                            <Text style={[TYPE.caption, { color: colors.textMuted }]}>
                                                If you renamed your agenda in-game, enter the custom title here. Leave blank to use the selected agenda name above.
                                            </Text>
                                            <TextInput
                                                style={[styles.input, { marginTop: SPACING.sm }]}
                                                value={customAgendaTitle}
                                                onChangeText={(text) => updateRacingSetting("customAgendaTitle", text)}
                                                placeholder="Leave blank to use selected agenda name"
                                                placeholderTextColor={colors.textMuted}
                                                autoCapitalize="none"
                                                autoCorrect={false}
                                            />
                                        </View>
                                    </SearchableItem>
                                    <ToggleSetting
                                        id="limit-races-to-in-game-agenda"
                                        title="Limit Extra Races to Agenda"
                                        description="When enabled, the bot will override the racing behavior of any scenario such that it will not run any extra races except for the ones scheduled by the selected user's in-game racing agenda."
                                        parentId="enable-user-in-game-race-agenda"
                                        checked={limitRacesToInGameAgenda}
                                        onCheckedChange={(checked) => updateRacingSetting("limitRacesToInGameAgenda", checked)}
                                    />
                                    <ToggleSetting
                                        id="skip-summer-training-for-agenda"
                                        title="Skip Summer Training for Agenda"
                                        description="When enabled, the bot will perform scheduled races from the in-game racing agenda during Summer instead of prioritizing Summer training. Note that this requires 'Enable User In-Game Race Agenda' to be enabled."
                                        parentId="enable-user-in-game-race-agenda"
                                        checked={skipSummerTrainingForAgenda}
                                        onCheckedChange={(checked) => updateRacingSetting("skipSummerTrainingForAgenda", checked)}
                                    />
                                </>
                            )}
                        </Section>
                    </View>
                </ScrollView>

                {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                    //////////////////////////////////////////////////////////////////////////////////////////////////
                    Strategy picker modals */}
                <SheetModal
                    visible={juniorPickerOpen}
                    onRequestClose={() => setJuniorPickerOpen(false)}
                    description={STRATEGY_PICKER_DESCRIPTION}
                    header={<ModalHeader title="JUNIOR YEAR STRATEGY" onClose={() => setJuniorPickerOpen(false)} />}
                    footer={null}
                >
                    {renderStrategyOptions(juniorYearRaceStrategy, (value) => {
                        updateRacingSetting("juniorYearRaceStrategy", value)
                        setJuniorPickerOpen(false)
                    })}
                </SheetModal>

                <SheetModal
                    visible={originalPickerOpen}
                    onRequestClose={() => setOriginalPickerOpen(false)}
                    description={STRATEGY_PICKER_DESCRIPTION}
                    header={<ModalHeader title="ORIGINAL STRATEGY" onClose={() => setOriginalPickerOpen(false)} />}
                    footer={null}
                >
                    {renderStrategyOptions(originalRaceStrategy, (value) => {
                        updateRacingSetting("originalRaceStrategy", value)
                        setOriginalPickerOpen(false)
                    })}
                </SheetModal>

                <SheetModal
                    visible={agendaPickerOpen}
                    onRequestClose={() => setAgendaPickerOpen(false)}
                    header={<ModalHeader title="SELECT AGENDA" onClose={() => setAgendaPickerOpen(false)} />}
                    footer={null}
                >
                    <View style={modalShellStyles.modalBodyList}>
                        {RACE_AGENDA_OPTIONS.map((option) => (
                            <ModalRadioRow
                                key={option}
                                label={option}
                                selected={option === racingSettings.selectedUserAgenda}
                                onPress={() => {
                                    updateRacingSetting("selectedUserAgenda", option)
                                    setAgendaPickerOpen(false)
                                }}
                            />
                        ))}
                    </View>
                </SheetModal>

                <SheetModal
                    visible={perDistancePicker !== null}
                    onRequestClose={() => setPerDistancePicker(null)}
                    description={STRATEGY_PICKER_DESCRIPTION}
                    header={<ModalHeader title={perDistancePicker ? `${perDistancePicker.distance.toUpperCase()} STRATEGY` : ""} onClose={() => setPerDistancePicker(null)} />}
                    footer={null}
                >
                    {perDistancePicker &&
                        renderStrategyOptions(
                            (perDistancePicker.year === "junior" ? juniorYearPerDistanceStrategies : originalPerDistanceStrategies)?.[perDistancePicker.distance] ?? "Default",
                            (value) => {
                                const { year, distance } = perDistancePicker
                                const key = year === "junior" ? "juniorYearPerDistanceStrategies" : "originalPerDistanceStrategies"
                                const current = year === "junior" ? juniorYearPerDistanceStrategies : originalPerDistanceStrategies
                                updateRacingSetting(key, { ...current, [distance]: value })
                                setPerDistancePicker(null)
                            }
                        )}
                </SheetModal>
            </SearchPageProvider>
        </View>
    )
}

export default RacingSettings
