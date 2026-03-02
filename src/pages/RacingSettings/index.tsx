import { useMemo, useContext, useRef } from "react"
import { View, Text, ScrollView, StyleSheet } from "react-native"
import { useNavigation } from "@react-navigation/native"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext, defaultSettings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomSelect from "../../components/CustomSelect"
import CustomTitle from "../../components/CustomTitle"
import { Input } from "../../components/ui/input"
import NavigationLink from "../../components/NavigationLink"
import PageHeader from "../../components/PageHeader"
import WarningContainer from "../../components/WarningContainer"
import SearchableItem from "../../components/SearchableItem"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

/**
 * The Racing Settings page.
 * Provides configuration for fan farming, race retries, mandatory race handling, race strategies (Junior vs. Original),
 * force racing, in-game race agenda, and navigation to the Racing Plan Settings sub-page.
 */
const RacingSettings = () => {
    usePerformanceLogging("RacingSettings")
    const { colors } = useTheme()
    const navigation = useNavigation()
    const bsc = useContext(BotStateContext)
    const scrollViewRef = useRef<ScrollView>(null)

    const { settings, setSettings } = bsc
    // Merge current racing settings with defaults to handle missing properties.
    const racingSettings = { ...defaultSettings.racing, ...settings.racing }
    const {
        enableFarmingFans,
        ignoreConsecutiveRaceWarning,
        daysToRunExtraRaces,
        disableRaceRetries,
        enableFreeRaceRetry,
        enableCompleteCareerOnFailure,
        enableStopOnMandatoryRaces,
        enableForceRacing,
        juniorYearRaceStrategy,
        originalRaceStrategy,
        enableUserInGameRaceAgenda,
    } = racingSettings

    /**
     * Update a racing setting with special handling for the in-game race agenda.
     * When the in-game race agenda is enabled, it automatically disables the Farming Fans and Racing Plan settings to prevent conflicts.
     * @param key The key of the setting to update.
     * @param value The value to set the setting to.
     */
    const updateRacingSetting = (key: keyof typeof settings.racing, value: any) => {
        if (key === "enableUserInGameRaceAgenda" && value) {
            setSettings({
                ...bsc.settings,
                racing: {
                    // Disable the Farming Fans and Racing Plan settings when User In Game Race Agenda is enabled.
                    ...bsc.settings.racing,
                    enableFarmingFans: false,
                    enableUserInGameRaceAgenda: true,
                    enableRacingPlan: false,
                },
            })
        } else {
            setSettings({
                ...bsc.settings,
                racing: {
                    ...bsc.settings.racing,
                    [key]: value,
                },
            })
        }
    }

    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: {
                    flex: 1,
                    flexDirection: "column",
                    justifyContent: "center",
                    margin: 10,
                    backgroundColor: colors.background,
                },
                section: {
                    marginBottom: 24,
                },
                inputContainer: {
                    marginBottom: 16,
                },
                inputLabel: {
                    fontSize: 16,
                    color: colors.foreground,
                    marginBottom: 8,
                },
                input: {
                    borderWidth: 1,
                    borderColor: colors.border,
                    borderRadius: 8,
                    padding: 12,
                    fontSize: 16,
                    color: colors.foreground,
                    backgroundColor: colors.background,
                },
                inputDescription: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginTop: 4,
                },
            }),
        [colors]
    )

    return (
        <View style={styles.root}>
            <PageHeader title="Racing Settings" />

            <SearchPageProvider page="RacingSettings" scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <View style={styles.section}>
                            <CustomCheckbox
                                searchId="enable-farming-fans"
                                checked={enableFarmingFans}
                                onCheckedChange={(checked) => updateRacingSetting("enableFarmingFans", checked)}
                                label="Enable Farming Fans"
                                description="When enabled, the bot will start running extra races to gain fans."
                                className="my-2"
                            />
                        </View>

                        <SearchableItem id="days-to-run-extra-races" title="Days to Run Extra Races" description="Controls when extra races can be run using modulo arithmetic." style={styles.section}>
                            <Text style={styles.inputLabel}>Days to Run Extra Races</Text>
                            <Input
                                style={styles.input}
                                value={daysToRunExtraRaces.toString()}
                                onChangeText={(text) => {
                                    const value = parseInt(text) || 1
                                    updateRacingSetting("daysToRunExtraRaces", value)
                                }}
                                keyboardType="numeric"
                                placeholder="5"
                            />
                            <Text style={styles.inputDescription}>
                                Controls when extra races can be run using modulo arithmetic. For example, if set to 5, extra races will only be available on days 5, 10, 15, etc. (when current day % 5
                                = 0). Note: This setting has no effect when Racing Plan is enabled, as Racing Plan controls when races occur based on opportunity cost analysis or mandatory race
                                detection.
                            </Text>
                        </SearchableItem>

                        <View style={styles.section}>
                            <CustomCheckbox
                                searchId="ignore-consecutive-race-warning"
                                checked={ignoreConsecutiveRaceWarning}
                                onCheckedChange={(checked) => updateRacingSetting("ignoreConsecutiveRaceWarning", checked)}
                                label="Ignore Consecutive Race Warning"
                                description="When enabled, the bot will ignore the warning popup about consecutive races and continue racing."
                                className="my-2"
                            />
                        </View>

                        <CustomTitle title="Mandatory Race Settings" />

                        <View style={styles.inputContainer}>
                            <CustomCheckbox
                                searchId="disable-race-retries"
                                checked={disableRaceRetries}
                                onCheckedChange={(checked) => updateRacingSetting("disableRaceRetries", checked)}
                                label="Disable Race Retries"
                                description="When enabled, the bot will not retry mandatory races if they fail and will stop."
                                className="my-2"
                            />
                            <CustomCheckbox
                                searchId="enable-free-race-retry"
                                searchCondition={disableRaceRetries}
                                parentId="disable-race-retries"
                                checked={enableFreeRaceRetry}
                                onCheckedChange={(checked) => updateRacingSetting("enableFreeRaceRetry", checked)}
                                label="Allow Daily Free Race Retry"
                                description="When enabled, the bot will attempt to retry a failed mandatory race only if the daily free race retry is available."
                                className="my-2"
                            />
                            <CustomCheckbox
                                searchId="enable-complete-career-on-failure"
                                checked={enableCompleteCareerOnFailure}
                                onCheckedChange={(checked) => updateRacingSetting("enableCompleteCareerOnFailure", checked)}
                                label="Complete Career on Failure"
                                description="When enabled, the bot will proceed to the career completion screen when a mandatory race is failed and it has run out of retries (or if retries are disabled). This is as opposed to the bot stopping at the Try Again dialog."
                                className="my-2"
                            />
                            <CustomCheckbox
                                searchId="enable-stop-on-mandatory-races"
                                checked={enableStopOnMandatoryRaces}
                                onCheckedChange={(checked) => updateRacingSetting("enableStopOnMandatoryRaces", checked)}
                                label="Stop on Mandatory Races"
                                description="When enabled, the bot will automatically stop when it encounters a mandatory race, allowing you to manually handle them."
                                className="my-2"
                            />
                        </View>

                        <View style={styles.inputContainer}>
                            <Text style={styles.inputLabel}>Junior Year Race Strategy</Text>
                            <CustomSelect
                                searchId="junior-year-race-strategy"
                                searchTitle="Junior Year Race Strategy"
                                searchDescription="The race strategy to use for all races during Junior Year."
                                options={[
                                    { value: "Default", label: "Default" },
                                    { value: "Auto", label: "Auto" },
                                    { value: "Front", label: "Front" },
                                    { value: "Pace", label: "Pace" },
                                    { value: "Late", label: "Late" },
                                    { value: "End", label: "End" },
                                ]}
                                value={juniorYearRaceStrategy}
                                onValueChange={(value) => updateRacingSetting("juniorYearRaceStrategy", value)}
                                placeholder="Select strategy"
                            />
                            <Text style={styles.inputDescription}>
                                The race strategy to use for all races during Junior Year. If Auto is selected, the bot will auto-select the best strategy that puts them closest to the front of the
                                pack.
                            </Text>
                        </View>
                        <View style={styles.inputContainer}>
                            <Text style={styles.inputLabel}>Original Race Strategy</Text>
                            <CustomSelect
                                searchId="original-race-strategy"
                                searchTitle="Original Race Strategy"
                                searchDescription="The race strategy to reset to after Junior Year. The bot will use this strategy for races in Year 2 and beyond."
                                options={[
                                    { value: "Default", label: "Default" },
                                    { value: "Auto", label: "Auto" },
                                    { value: "Front", label: "Front" },
                                    { value: "Pace", label: "Pace" },
                                    { value: "Late", label: "Late" },
                                    { value: "End", label: "End" },
                                ]}
                                value={originalRaceStrategy}
                                onValueChange={(value) => updateRacingSetting("originalRaceStrategy", value)}
                                placeholder="Select strategy"
                            />
                            <Text style={styles.inputDescription}>
                                The race strategy to reset to after Junior Year. The bot will use this strategy for races in Year 2 and beyond. If Auto is selected, the bot will auto-select the best
                                strategy that puts them closest to the front of the pack. If Default is selected, the bot will not change whatever strategy is currently in effect.
                            </Text>
                        </View>

                        <View style={styles.section}>
                            <CustomCheckbox
                                searchId="enable-force-racing"
                                checked={enableForceRacing}
                                onCheckedChange={(checked) => updateRacingSetting("enableForceRacing", checked)}
                                label="Force Racing"
                                description="When enabled, the bot will skip all training, rest, and mood recovery activities and focus exclusively on racing every day."
                                className="my-2"
                            />
                            {enableForceRacing && <WarningContainer>⚠️ Warning: Enabling this will override all other racing settings and they will be ignored.</WarningContainer>}
                        </View>

                        <CustomCheckbox
                            searchId="enable-user-in-game-race-agenda"
                            checked={enableUserInGameRaceAgenda}
                            onCheckedChange={(checked) => updateRacingSetting("enableUserInGameRaceAgenda", checked)}
                            label="Enable User In-Game Race Agenda"
                            description={
                                "When enabled, the bot will load your selected in-game race agenda instead of using the racing plan settings. Note that this will disable the farming fans and racing plan settings."
                            }
                            style={{ marginBottom: 16 }}
                        />

                        <CustomSelect
                            searchId="user-in-game-race-agenda"
                            searchTitle="Select User In-Game Race Agenda"
                            searchDescription="The in-game race agenda to use when 'Enable User In-Game Race Agenda' is enabled."
                            searchCondition={enableUserInGameRaceAgenda}
                            parentId="enable-user-in-game-race-agenda"
                            placeholder="Select an Agenda"
                            width="100%"
                            options={[
                                { value: "Agenda 1", label: "Agenda 1" },
                                { value: "Agenda 2", label: "Agenda 2" },
                                { value: "Agenda 3", label: "Agenda 3" },
                                { value: "Agenda 4", label: "Agenda 4" },
                                { value: "Agenda 5", label: "Agenda 5" },
                                { value: "Agenda 6", label: "Agenda 6" },
                                { value: "Agenda 7", label: "Agenda 7" },
                                { value: "Agenda 8", label: "Agenda 8" },
                            ]}
                            value={racingSettings.selectedUserAgenda}
                            onValueChange={(value) => updateRacingSetting("selectedUserAgenda", value)}
                        />

                        <NavigationLink
                            title="Go to Racing Plan Settings"
                            description="Configure prioritized races to target including enabling additional filters for race selection."
                            disabled={!enableFarmingFans || enableForceRacing || enableUserInGameRaceAgenda}
                            disabledDescription="Farming Fans must be enabled and Force Racing and User In-Game Race Agenda settings must be disabled in order to use the Racing Plan Settings."
                            onPress={() => navigation.navigate("RacingPlanSettings" as never)}
                            style={{ ...styles.section, marginTop: 0 }}
                        />
                    </View>
                </ScrollView>
            </SearchPageProvider>
        </View>
    )
}

export default RacingSettings
