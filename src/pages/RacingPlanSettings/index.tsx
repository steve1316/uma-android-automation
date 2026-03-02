import { useMemo, useContext, useState, useEffect, useRef } from "react"
import { View, Text, ScrollView, StyleSheet, TouchableOpacity } from "react-native"
import { Divider } from "react-native-paper"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext, defaultSettings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomButton from "../../components/CustomButton"
import CustomScrollView from "../../components/CustomScrollView"
import { Input } from "../../components/ui/input"
import { CircleCheckBig, Plus, Trash2 } from "lucide-react-native"
import racesData from "../../data/races.json"
import PageHeader from "../../components/PageHeader"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import SearchableItem from "../../components/SearchableItem"

/**
 * Represents a race entry from the `races.json` data file.
 */
interface Race {
    /** The display name of the race. */
    name: string
    /** The in-game date when the race occurs. */
    date: string
    /** The race grade (e.g. G1, G2, G3). */
    grade: string
    /** The terrain type (e.g. Turf, Dirt). */
    terrain: string
    /** The distance category (e.g. Short, Mile, Medium, Long). */
    distanceType: string
    /** The actual distance in meters. */
    distanceMeters: number
    /** The number of fans gained from this race. */
    fans: number
    /** The turn number when this race occurs. */
    turnNumber: number
}

/**
 * Represents a race that has been added to the user's racing plan.
 */
interface PlannedRace {
    /** The name of the planned race. */
    raceName: string
    /** The in-game date when the planned race occurs. */
    date: string
    /** The priority order of this race in the plan. */
    priority: number
    /** The turn number when this race occurs. */
    turnNumber: number
}

/**
 * The Racing Plan Settings page.
 * Provides smart race planning with opportunity cost analysis, configurable quality thresholds, terrain/grade/distance filters,
 * time decay factors, and a selectable list of planned races.
 */
const RacingPlanSettings = () => {
    usePerformanceLogging("RacingPlanSettings")
    const { colors } = useTheme()
    const bsc = useContext(BotStateContext)
    const scrollViewRef = useRef<ScrollView>(null)

    const { settings, setSettings } = bsc

    // Merge current racing settings with defaults to handle missing properties.
    const racingSettings = { ...defaultSettings.racing, ...settings.racing }
    const {
        enableRacingPlan,
        enableMandatoryRacingPlan,
        racingPlan,
        minFansThreshold,
        preferredTerrain,
        lookAheadDays,
        smartRacingCheckInterval,
        minimumQualityThreshold,
        timeDecayFactor,
        improvementThreshold,
        preferredGrades,
        preferredDistances,
    } = racingSettings

    const [searchQuery, setSearchQuery] = useState("")
    // Local state for decimal inputs to preserve intermediate values while typing (e.g., "7.").
    const [minimumQualityThresholdInput, setMinimumQualityThresholdInput] = useState(minimumQualityThreshold.toString())
    const [timeDecayFactorInput, setTimeDecayFactorInput] = useState(timeDecayFactor.toString())
    const [improvementThresholdInput, setImprovementThresholdInput] = useState(improvementThreshold.toString())

    // Sync local input state when settings change externally (e.g., settings reset).
    useEffect(() => {
        setMinimumQualityThresholdInput(minimumQualityThreshold.toString())
    }, [minimumQualityThreshold])

    useEffect(() => {
        setTimeDecayFactorInput(timeDecayFactor.toString())
    }, [timeDecayFactor])

    useEffect(() => {
        setImprovementThresholdInput(improvementThreshold.toString())
    }, [improvementThreshold])

    // Parse racing plan from JSON string.
    const parsedRacingPlan: PlannedRace[] = useMemo(() => {
        return racingPlan && racingPlan !== "[]" && typeof racingPlan === "string" ? JSON.parse(racingPlan) : []
    }, [racingPlan])

    // Convert races.json to array.
    const allRaces: Race[] = useMemo(() => Object.values(racesData), [])

    // Filter races based on search and preferences.
    const filteredRaces = useMemo(() => {
        return allRaces.filter((race) => {
            const matchesSearch = race.name.toLowerCase().includes(searchQuery.toLowerCase()) || race.date.toLowerCase().includes(searchQuery.toLowerCase())

            const matchesFans = race.fans >= minFansThreshold
            const matchesTerrain = preferredTerrain === "Any" || race.terrain === preferredTerrain
            const matchesGrade = preferredGrades.includes(race.grade) && race.grade !== "OP" && race.grade !== "Pre-OP"
            const matchesDistance = preferredDistances.includes(race.distanceType)

            return matchesSearch && matchesFans && matchesTerrain && matchesGrade && matchesDistance
        })
    }, [allRaces, searchQuery, minFansThreshold, preferredTerrain, preferredGrades, preferredDistances])

    /**
     * Update a racing setting with special handling for the racing plan settings.
     * @param key The key of the setting to update.
     * @param value The value to set the setting to.
     */
    const updateRacingSetting = (key: string, value: any) => {
        if (key === "enableRacingPlan" && value) {
            setSettings({
                ...bsc.settings,
                racing: {
                    // The following settings need to be set due to being two distinct racing systems.
                    ...bsc.settings.racing,
                    enableFarmingFans: true,
                    enableForceRacing: false,
                    enableUserInGameRaceAgenda: false,
                    enableRacingPlan: true,
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

    /**
     * Toggle the selection of a race within the racing plan.
     * If the race is already present in the plan, it will be removed. Otherwise, it is added to the plan.
     * @param race The specific race instance to add or remove.
     */
    const handleRacePress = (race: Race) => {
        // Determine if this should be added to the racing plan or removed.
        // Use raceName + date + turnNumber to uniquely identify each race instance.
        const isRaceSelected = parsedRacingPlan.some((planned) => planned.raceName === race.name && planned.date === race.date && planned.turnNumber === race.turnNumber)

        let newPlan: PlannedRace[] = []
        if (isRaceSelected) {
            // Remove the race from the racing plan.
            newPlan = parsedRacingPlan.filter((planned) => !(planned.raceName === race.name && planned.date === race.date && planned.turnNumber === race.turnNumber))
        } else {
            // Add the race to the racing plan.
            const newPlannedRace: PlannedRace = {
                raceName: race.name,
                date: race.date,
                priority: parsedRacingPlan.length,
                turnNumber: race.turnNumber,
            }
            newPlan = [...parsedRacingPlan, newPlannedRace]
        }

        // Update the racing plan with the changes.
        updateRacingSetting("racingPlan", JSON.stringify(newPlan))
    }

    /**
     * Add all currently filtered races to the racing plan.
     */
    const addAllRacesToPlan = () => {
        const newPlan: PlannedRace[] = filteredRaces.map((race, index) => ({
            raceName: race.name,
            date: race.date,
            priority: index,
            turnNumber: race.turnNumber,
        }))

        updateRacingSetting("racingPlan", JSON.stringify(newPlan))
    }

    /**
     * Remove all races from the racing plan.
     */
    const clearAllRacesFromPlan = () => {
        updateRacingSetting("racingPlan", JSON.stringify([]))
    }

    /**
     * Toggle a race grade in the preferred grades list.
     * @param grade The grade to add or remove (e.g., `G1`, `G2`, `G3`).
     */
    const toggleGrade = (grade: string) => {
        if (preferredGrades.includes(grade)) {
            updateRacingSetting(
                "preferredGrades",
                preferredGrades.filter((g: string) => g !== grade)
            )
        } else {
            updateRacingSetting("preferredGrades", [...preferredGrades, grade])
        }
    }

    /**
     * Toggle a distance category in the preferred distances list.
     * @param distance The distance category to add or remove (e.g., `Mile`, `Medium`).
     */
    const toggleDistance = (distance: string) => {
        if (preferredDistances.includes(distance)) {
            updateRacingSetting(
                "preferredDistances",
                preferredDistances.filter((d: string) => d !== distance)
            )
        } else {
            updateRacingSetting("preferredDistances", [...preferredDistances, distance])
        }
    }

    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: {
                    flex: 1,
                    flexDirection: "column",
                    margin: 10,
                    backgroundColor: colors.background,
                },
                description: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginBottom: 16,
                    lineHeight: 20,
                },
                section: {
                    marginBottom: 24,
                },
                sectionTitle: {
                    fontSize: 18,
                    fontWeight: "600",
                    color: colors.foreground,
                    marginBottom: 12,
                },
                raceItem: {
                    backgroundColor: colors.card,
                    padding: 16,
                    borderRadius: 8,
                    marginBottom: 8,
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "center",
                },
                raceName: {
                    fontSize: 16,
                    fontWeight: "600",
                    color: colors.foreground,
                },
                raceDate: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginTop: 4,
                },
                raceFans: {
                    fontSize: 14,
                    color: colors.primary,
                    marginTop: 4,
                },
                input: {
                    borderWidth: 1,
                    borderColor: colors.border,
                    borderRadius: 8,
                    padding: 12,
                    fontSize: 16,
                    color: colors.foreground,
                    backgroundColor: colors.background,
                    marginBottom: 12,
                },
                inputLabel: {
                    fontSize: 16,
                    color: colors.foreground,
                    marginBottom: 8,
                },
                inputDescription: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                },
                terrainButton: {
                    padding: 12,
                    borderRadius: 8,
                    marginRight: 8,
                    marginTop: 8,
                },
                terrainButtonText: {
                    fontSize: 14,
                    fontWeight: "600",
                },
            }),
        [colors]
    )

    /**
     * Render the configuration options for the racing plan.
     * Includes thresholds for fans, look-ahead days, quality, and preferred race attributes.
     * @returns A React element containing the configuration options.
     */
    const renderOptions = () => {
        return (
            <>
                <SearchableItem
                    id="minimum-fans-threshold"
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                    title="Minimum Fans Threshold"
                    description="Bot will prioritize races with at least this many fans."
                    style={styles.section}
                >
                    <Text style={styles.inputLabel}>Minimum Fans Threshold</Text>
                    <Input
                        style={styles.input}
                        value={minFansThreshold.toString()}
                        onChangeText={(text) => {
                            const value = parseInt(text) || 0
                            updateRacingSetting("minFansThreshold", value)
                        }}
                        keyboardType="numeric"
                        placeholder="0"
                    />
                    <Text style={styles.inputDescription}>Bot will prioritize races with at least this many fans.</Text>
                </SearchableItem>

                <SearchableItem
                    id="look-ahead-days"
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                    title="Look-Ahead Days"
                    description="Number of days to look ahead when making smart racing decisions."
                    style={styles.section}
                >
                    <Text style={styles.inputLabel}>Look-Ahead Days</Text>
                    <Input
                        style={styles.input}
                        value={lookAheadDays.toString()}
                        onChangeText={(text) => {
                            const value = parseInt(text) || 0
                            updateRacingSetting("lookAheadDays", value)
                        }}
                        keyboardType="numeric"
                        placeholder="10"
                    />
                    <Text style={styles.inputDescription}>Number of days to look ahead when making smart racing decisions.</Text>
                </SearchableItem>

                <SearchableItem
                    id="smart-racing-check-interval"
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                    title="Smart Racing Check Interval"
                    description="Interval in seconds between smart racing checks."
                    style={styles.section}
                >
                    <Text style={styles.inputLabel}>Smart Racing Check Interval</Text>
                    <Input
                        style={styles.input}
                        value={smartRacingCheckInterval.toString()}
                        onChangeText={(text) => {
                            const value = parseInt(text) || 0
                            updateRacingSetting("smartRacingCheckInterval", value)
                        }}
                        keyboardType="numeric"
                        placeholder="2"
                    />
                    <Text style={styles.inputDescription}>How often the bot checks for optimal racing opportunities. Lower values = more frequent checks.</Text>
                </SearchableItem>

                <SearchableItem
                    id="minimum-quality-threshold"
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                    title="Minimum Quality Threshold"
                    description="The minimum score a race must have to be considered acceptable. Races scoring below this will be skipped even if no better options are available soon."
                    style={styles.section}
                >
                    <Text style={styles.inputLabel}>Minimum Quality Threshold</Text>
                    <Input
                        style={styles.input}
                        value={minimumQualityThresholdInput}
                        onChangeText={(text) => {
                            // Allow empty string and intermediate decimal states (e.g., "7.", "-", "0.").
                            if (text === "" || /^-?\d*\.?\d*$/.test(text)) {
                                setMinimumQualityThresholdInput(text)
                            }
                        }}
                        onBlur={() => {
                            // Parse and save when user finishes editing.
                            const value = parseFloat(minimumQualityThresholdInput) || 0
                            setMinimumQualityThresholdInput(value.toString())
                            updateRacingSetting("minimumQualityThreshold", value)
                        }}
                        keyboardType="decimal-pad"
                        placeholder="70.0"
                    />
                    <Text style={styles.inputDescription}>
                        The minimum score a race must have to be considered acceptable. Races scoring below this will be skipped even if no better options are available soon.
                        {"\n\n"}
                        Example: If set to 70, a race scoring 65 will be skipped, but a race scoring 75 will be considered.
                    </Text>
                </SearchableItem>

                <SearchableItem
                    id="time-decay-factor"
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                    title="Time Decay Factor"
                    description="Future races are worth this percentage of their raw score. Lower values mean future races are discounted more heavily, making the bot less willing to wait."
                    style={styles.section}
                >
                    <Text style={styles.inputLabel}>Time Decay Factor</Text>
                    <Input
                        style={styles.input}
                        value={timeDecayFactorInput}
                        onChangeText={(text) => {
                            // Allow empty string and intermediate decimal states (e.g., "0.", "-", "0.9").
                            if (text === "" || /^-?\d*\.?\d*$/.test(text)) {
                                setTimeDecayFactorInput(text)
                            }
                        }}
                        onBlur={() => {
                            // Parse and save when user finishes editing.
                            const value = parseFloat(timeDecayFactorInput) || 0
                            setTimeDecayFactorInput(value.toString())
                            updateRacingSetting("timeDecayFactor", value)
                        }}
                        keyboardType="decimal-pad"
                        placeholder="0.90"
                    />
                    <Text style={styles.inputDescription}>
                        Future races are worth this percentage of their raw score. Lower values mean future races are discounted more heavily, making the bot less willing to wait.
                        {"\n\n"}
                        Example: If set to 0.90, a future race scoring 100 becomes 90 after discounting. If set to 0.70, the same race becomes 70.
                    </Text>
                </SearchableItem>

                <SearchableItem
                    id="improvement-threshold"
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                    title="Improvement Threshold"
                    description="The minimum improvement (in points) needed from waiting for a future race to make waiting worthwhile. Only wait if the improvement exceeds this value."
                    style={styles.section}
                >
                    <Text style={styles.inputLabel}>Improvement Threshold</Text>
                    <Input
                        style={styles.input}
                        value={improvementThresholdInput}
                        onChangeText={(text) => {
                            // Allow empty string and intermediate decimal states (e.g., "25.", "-", "2.5").
                            if (text === "" || /^-?\d*\.?\d*$/.test(text)) {
                                setImprovementThresholdInput(text)
                            }
                        }}
                        onBlur={() => {
                            // Parse and save when user finishes editing.
                            const value = parseFloat(improvementThresholdInput) || 0
                            setImprovementThresholdInput(value.toString())
                            updateRacingSetting("improvementThreshold", value)
                        }}
                        keyboardType="decimal-pad"
                        placeholder="25.0"
                    />
                    <Text style={styles.inputDescription}>
                        The minimum improvement (in points) needed from waiting for a future race to make waiting worthwhile. Only wait if the improvement exceeds this value.
                        {"\n\n"}
                        Example: If set to 25, the bot will only wait if the discounted future race score is at least 25 points better than the current best race.
                    </Text>
                </SearchableItem>

                <SearchableItem
                    id="preferred-terrain"
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                    title="Preferred Terrain"
                    description="The preferred terrain for races. The bot will prioritize races with this terrain when selecting races to enter."
                    style={styles.section}
                >
                    <Text style={styles.sectionTitle}>Preferred Terrain</Text>
                    <Text style={styles.inputDescription}>The preferred terrain for races. The bot will prioritize races with this terrain when selecting races to enter.</Text>
                    <View style={{ flexDirection: "row" }}>
                        {["Any", "Turf", "Dirt"].map((terrain) => (
                            <TouchableOpacity
                                key={terrain}
                                onPress={() => updateRacingSetting("preferredTerrain", terrain)}
                                style={[
                                    styles.terrainButton,
                                    {
                                        backgroundColor: preferredTerrain === terrain ? colors.primary : colors.card,
                                    },
                                ]}
                            >
                                <Text
                                    style={[
                                        styles.terrainButtonText,
                                        {
                                            color: preferredTerrain === terrain ? colors.background : colors.foreground,
                                        },
                                    ]}
                                >
                                    {terrain}
                                </Text>
                            </TouchableOpacity>
                        ))}
                    </View>
                </SearchableItem>

                <SearchableItem
                    id="preferred-race-grades"
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                    title="Preferred Race Grades"
                    description="Select which race grades the bot should prioritize."
                    style={styles.section}
                >
                    <Text style={styles.sectionTitle}>Preferred Race Grades</Text>
                    <Text style={styles.inputDescription}>Select which race grades the bot should prioritize.</Text>
                    <View style={{ flexDirection: "row", flexWrap: "wrap", marginTop: 8 }}>
                        {["G1", "G2", "G3"].map((grade) => (
                            <TouchableOpacity
                                key={grade}
                                onPress={() => toggleGrade(grade)}
                                style={[
                                    styles.terrainButton,
                                    {
                                        backgroundColor: preferredGrades.includes(grade) ? colors.primary : colors.card,
                                    },
                                ]}
                            >
                                <Text
                                    style={[
                                        styles.terrainButtonText,
                                        {
                                            color: preferredGrades.includes(grade) ? colors.background : colors.foreground,
                                        },
                                    ]}
                                >
                                    {grade}
                                </Text>
                            </TouchableOpacity>
                        ))}
                    </View>
                </SearchableItem>

                <SearchableItem
                    id="preferred-race-distances"
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                    title="Preferred Race Distances"
                    description="Select which race distances the bot should prioritize."
                    style={styles.section}
                >
                    <Text style={styles.sectionTitle}>Preferred Race Distances</Text>
                    <Text style={styles.inputDescription}>Select which race distances the bot should prioritize.</Text>
                    <View style={{ flexDirection: "row", flexWrap: "wrap", marginTop: 8 }}>
                        {["Short", "Mile", "Medium", "Long"].map((distance) => (
                            <TouchableOpacity
                                key={distance}
                                onPress={() => toggleDistance(distance)}
                                style={[
                                    styles.terrainButton,
                                    {
                                        backgroundColor: preferredDistances.includes(distance) ? colors.primary : colors.card,
                                    },
                                ]}
                            >
                                <Text
                                    style={[
                                        styles.terrainButtonText,
                                        {
                                            color: preferredDistances.includes(distance) ? colors.background : colors.foreground,
                                        },
                                    ]}
                                >
                                    {distance}
                                </Text>
                            </TouchableOpacity>
                        ))}
                    </View>
                </SearchableItem>
            </>
        )
    }

    /**
     * Render the searchable list of races available for planning.
     * Users can filter and select specific races to add to their optimized racing plan.
     * @returns A React element containing the searchable race list.
     */
    const renderRaceList = () => {
        return (
            <View style={enableRacingPlan ? styles.section : { display: "none" }}>
                <SearchableItem
                    id="planned-races"
                    title="Planned Races"
                    description="Select which races the bot should prioritize using opportunity cost analysis."
                    style={styles.section}
                    condition={enableRacingPlan}
                    parentId="enable-racing-plan"
                >
                    <View style={{ flexDirection: "row", alignItems: "center", marginBottom: 12, gap: 12 }}>
                        <View style={{ flex: 1 }}>
                            <Text style={styles.sectionTitle}>Planned Races</Text>
                            <Text style={[styles.inputDescription, { marginTop: 0 }]}>
                                Selected {parsedRacingPlan.length} / {filteredRaces.length} races
                            </Text>
                        </View>
                        <View style={{ flexDirection: "row", gap: 8 }}>
                            <CustomButton icon={<Plus size={16} />} onPress={() => addAllRacesToPlan()}>
                                Add All
                            </CustomButton>
                            <CustomButton icon={<Trash2 size={16} />} onPress={() => clearAllRacesFromPlan()}>
                                Clear
                            </CustomButton>
                        </View>
                    </View>

                    <View style={{ flexDirection: "row" }}>
                        <View style={{ flex: 1 }}>
                            <Text style={[styles.inputDescription, { marginTop: 0 }]}>
                                Select which races the bot should prioritize using opportunity cost analysis. Be sure to double check your selected races after making changes to the filters.
                            </Text>
                        </View>
                    </View>
                </SearchableItem>

                <View style={{ marginBottom: 16 }}>
                    <Input style={styles.input} value={searchQuery} onChangeText={setSearchQuery} placeholder="Search races by name or date..." />
                    <View style={{ height: 300 }}>
                        <CustomScrollView
                            targetProps={{
                                data: filteredRaces,
                                renderItem: ({ item: race }) => (
                                    <TouchableOpacity onPress={() => handleRacePress(race)} style={styles.raceItem}>
                                        <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
                                            <View style={{ flex: 1 }}>
                                                <Text style={styles.raceName}>{race.name}</Text>
                                                <Text style={styles.raceDate}>{race.date}</Text>
                                                <Text style={styles.raceFans}>
                                                    {race.fans} fans • {race.grade} • {race.terrain} • {race.distanceType}
                                                </Text>
                                            </View>
                                            {parsedRacingPlan.some((planned) => planned.raceName === race.name && planned.date === race.date && planned.turnNumber === race.turnNumber) && (
                                                <CircleCheckBig size={18} color={"green"} />
                                            )}
                                        </View>
                                    </TouchableOpacity>
                                ),
                                nestedScrollEnabled: true,
                            }}
                            position="right"
                            horizontal={false}
                            persistentScrollbar={true}
                            indicatorStyle={{
                                width: 10,
                                backgroundColor: colors.foreground,
                            }}
                            containerStyle={{
                                flex: 1,
                            }}
                            minIndicatorSize={50}
                        />
                    </View>
                </View>
            </View>
        )
    }

    return (
        <View style={styles.root}>
            <PageHeader title="Racing Plan" />

            <SearchPageProvider page="RacingPlanSettings" scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <View style={styles.section}>
                            <Text style={styles.description}>
                                {
                                    "Uses opportunity cost analysis to optimize race selection by looking ahead N days for races matching your character's aptitudes (A/S terrain/distance). Scores races by fans, grade, and aptitude matches.\n\nUses standard settings until Classic Year, then combines both this and standard racing settings during Classic Year. Only fully activates in Senior Year. Races when current opportunities are good enough and waiting doesn't offer significantly better value, ensuring steady fan accumulation without endless waiting.\n\nNote: When Racing Plan is enabled, the \"Days to Run Extra Races\" setting in Racing Settings is ignored, as Racing Plan controls when races occur based on opportunity cost analysis or mandatory race detection."
                                }
                            </Text>

                            <Divider style={{ marginBottom: 16 }} />

                            <CustomCheckbox
                                searchId="enable-racing-plan"
                                checked={enableRacingPlan}
                                onCheckedChange={(checked) => updateRacingSetting("enableRacingPlan", checked)}
                                label="Enable Racing Plan (Beta)"
                                description={"When enabled, the bot will use smart race planning to optimize race selection."}
                            />

                            <CustomCheckbox
                                searchId="enable-mandatory-racing-plan"
                                searchCondition={enableRacingPlan}
                                parentId="enable-racing-plan"
                                checked={enableMandatoryRacingPlan}
                                onCheckedChange={(checked) => updateRacingSetting("enableMandatoryRacingPlan", checked)}
                                label="Treat Planned Races as Mandatory"
                                description={
                                    "When enabled, the bot will prioritize the specific planned race that matches the current turn number, bypassing opportunity cost analysis. Note that it will only run the races if the racer's aptitudes are double predictions (both terrain and distance must be B or greater)."
                                }
                                style={{ marginTop: 16 }}
                            />
                        </View>

                        {renderOptions()}
                        {renderRaceList()}
                    </View>
                </ScrollView>
            </SearchPageProvider>
        </View>
    )
}

export default RacingPlanSettings
