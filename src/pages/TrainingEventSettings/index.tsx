import { useContext, useState, useMemo, useCallback, useRef } from "react"
import { View, Text, ScrollView, StyleSheet, TouchableOpacity, Modal, TextInput, Dimensions } from "react-native"
import { FlashList } from "@shopify/flash-list"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext, defaultSettings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import CustomAccordion from "../../components/CustomAccordion"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomSelect from "../../components/CustomSelect"
import CustomTitle from "../../components/CustomTitle"
import CustomButton from "../../components/CustomButton"
import { Search, X } from "lucide-react-native"
import PageHeader from "../../components/PageHeader"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

// Import the data files.
import charactersData from "../../data/characters.json"
import supportsData from "../../data/supports.json"

// List of events that are already covered in Special Event Overrides and should be excluded.
const excludedEventNames = new Set([
    "Acupuncture (Just an Acupuncturist, No Worries! ☆)",
    "New Year's Resolutions",
    "New Year's Shrine Visit",
    "Victory! (G1)\n1st",
    "Victory! (G2/G3)\n1st",
    "Victory! (Pre/OP)\n1st",
    "Solid Showing (G1)\n2nd-5th",
    "Solid Showing (G2/G3)\n2nd-5th",
    "Solid Showing (Pre/OP)\n2nd-5th",
    "Defeat (G1)\n6th or worse",
    "Defeat (G2/G3)\n6th or worse",
    "Defeat (Pre/OP)\n6th or worse",
    "Etsuko's Exhaustive Coverage (G1)",
    "Etsuko's Exhaustive Coverage (G2/G3)",
    "Etsuko's Exhaustive Coverage (Pre/OP)",
    "Failed training (Get Well Soon!)",
    "Failed training (Don't Overdo It!)",
    "Extra Training",
    "A Team at Last",
])

const TrainingEventSettings = () => {
    usePerformanceLogging("TrainingEventSettings")
    const { colors } = useTheme()
    const bsc = useContext(BotStateContext)
    const scrollViewRef = useRef<ScrollView>(null)

    const { settings, setSettings } = bsc
    // Merge current training event settings with defaults to handle missing properties.
    const trainingEventSettings = { ...defaultSettings.trainingEvent, ...settings.trainingEvent }
    const { enablePrioritizeEnergyOptions, specialEventOverrides, characterEventOverrides, supportEventOverrides } = trainingEventSettings

    const [eventOverrideModalVisible, setEventOverrideModalVisible] = useState(false)
    const [eventOverrideSearchQuery, setEventOverrideSearchQuery] = useState("")
    const [optionSelectionModalVisible, setOptionSelectionModalVisible] = useState(false)
    const [selectedEventForOption, setSelectedEventForOption] = useState<{ key: string; characterOrSupport: string; eventName: string; options: string[]; type: "character" | "support" } | null>(null)

    const acupunctureOptions = [
        { value: "Option 1: All stats +20", label: "Option 1: All stats +20" },
        { value: "Option 2: Get Corner and Straightaway Recovery skills", label: "Option 2: Get Corner and Straightaway Recovery skills" },
        { value: "Option 3: Energy recovery + Heal all negative status effects", label: "Option 3: Energy recovery + Heal all negative status effects" },
        { value: "Option 4: Get Charming status effect", label: "Option 4: Get Charming status effect" },
        { value: "Option 5: Energy +10", label: "Option 5: Energy +10" },
    ]
    const etsukoOptions = [
        { value: "Option 1: (Random) Energy Down / Mood -1 / Random stat increase / Gain skill points", label: "Option 1: (Random) Energy Down / Mood -1 / Random stat increase / Gain skill points" },
        { value: "Option 2: Energy Down / Gain skill points", label: "Option 2: Energy Down / Gain skill points" },
    ]
    const newYearResolutionsOptions = [
        { value: "Option 1: Stat +10", label: "Option 1: Stat +10" },
        { value: "Option 2: Energy +20", label: "Option 2: Energy +20" },
        { value: "Option 3: Skill points +20", label: "Option 3: Skill points +20" },
    ]
    const newYearShrineVisitOptions = [
        { value: "Option 1: Energy +30", label: "Option 1: Energy +30" },
        { value: "Option 2: All stats +5", label: "Option 2: All stats +5" },
        { value: "Option 3: Skill points +35", label: "Option 3: Skill points +35" },
    ]
    const victoryOptions = [
        { value: "Option 1: Energy -15 and random stat gain", label: "Option 1: Energy -15 and random stat gain" },
        { value: "Option 2: Energy -5 and random stat gain", label: "Option 2: Energy -5 and random stat gain" },
    ]
    const solidShowingOptions = [
        { value: "Option 1: Energy -15 and random stat gain", label: "Option 1: Energy -15 and random stat gain" },
        { value: "Option 2: Energy -5/-20 and random stat gain", label: "Option 2: Energy -5/-20 and random stat gain" },
    ]
    const defeatOptions = [
        { value: "Option 1: Energy -25 and random stat gain", label: "Option 1: Energy -25 and random stat gain" },
        { value: "Option 2: Energy -15/-35 and random stat gain", label: "Option 2: Energy -15/-35 and random stat gain" },
    ]
    const getWellSoonOptions = [
        { value: "Option 1: Mood -1 / Stat decrease / Get Practice Poor negative status", label: "Option 1: Mood -1 / Stat decrease / Get Practice Poor negative status" },
        { value: "Option 2: (Random) Mood -1 / Stat decrease / Get Practice Poor negative status", label: "Option 2: (Random) Mood -1 / Stat decrease / Get Practice Poor negative status" },
    ]
    const dontOverdoItOptions = [
        { value: "Option 1: Energy +10 / Mood -2 / Stat decrease / Get Practice Poor negative status", label: "Option 1: Energy +10 / Mood -2 / Stat decrease / Get Practice Poor negative status" },
        { value: "Option 2: (Random) Mood -3 / Stat decrease / Get Practice Poor negative status", label: "Option 2: (Random) Mood -3 / Stat decrease / Get Practice Poor negative status" },
    ]
    const extraTrainingOptions = [
        { value: "Option 1: Energy -5 / Stat increase / (Random) Heal a negative status effect", label: "Option 1: Energy -5 / Stat increase / (Random) Heal a negative status effect" },
        { value: "Option 2: Energy +5", label: "Option 2: Energy +5" },
    ]
    const aTeamAtLastOptions = [
        { value: "Default", label: "Default (First Option)" },
        { value: "Happy Hoppers, like Taiki suggested", label: "Happy Hoppers (Taiki)" },
        { value: "Sunny Runners, like Fukukitaru suggested", label: "Sunny Runners (Fukukitaru)" },
        { value: "Carrot Pudding, like Urara suggested", label: "Carrot Pudding (Urara)" },
        { value: "Blue Bloom, like Rice Shower suggested", label: "Blue Bloom (Rice Shower)" },
    ]

    const updateTrainingEventSetting = (key: keyof typeof settings.trainingEvent, value: any) => {
        setSettings({
            ...bsc.settings,
            trainingEvent: {
                ...bsc.settings.trainingEvent,
                [key]: value,
            },
        })
    }

    const updateSpecialEventOverride = (eventName: string, field: "selectedOption" | "requiresConfirmation", value: any) => {
        setSettings({
            ...bsc.settings,
            trainingEvent: {
                ...bsc.settings.trainingEvent,
                specialEventOverrides: {
                    ...bsc.settings.trainingEvent.specialEventOverrides,
                    [eventName]: {
                        ...bsc.settings.trainingEvent.specialEventOverrides[eventName],
                        [field]: value,
                    },
                },
            },
        })
    }

    // Build a flat list of all events with their character/support names.
    // Use the full data files so users can search through all events, not just selected ones.
    const allEvents = useMemo(() => {
        const events: Array<{ key: string; characterOrSupport: string; eventName: string; options: string[]; type: "character" | "support" }> = []

        // Add all character events from the data file.
        Object.keys(charactersData).forEach((characterName) => {
            const characterEvents = charactersData[characterName as keyof typeof charactersData] as Record<string, string[]>
            if (characterEvents) {
                Object.keys(characterEvents).forEach((eventName) => {
                    const eventOptions = characterEvents[eventName]
                    // Skip events that are already covered in Special Event Overrides and that have fewer than 2 options.
                    if (!excludedEventNames.has(eventName) && eventOptions && eventOptions.length >= 2) {
                        events.push({
                            key: `${characterName}|${eventName}`,
                            characterOrSupport: characterName,
                            eventName,
                            options: eventOptions,
                            type: "character",
                        })
                    }
                })
            }
        })

        // Add all support events from the data file.
        Object.keys(supportsData).forEach((supportName) => {
            const supportEvents = supportsData[supportName as keyof typeof supportsData] as Record<string, string[]>
            if (supportEvents) {
                Object.keys(supportEvents).forEach((eventName) => {
                    const eventOptions = supportEvents[eventName]
                    // Skip events that are already covered in Special Event Overrides and that have fewer than 2 options.
                    if (!excludedEventNames.has(eventName) && eventOptions && eventOptions.length >= 2) {
                        events.push({
                            key: `${supportName}|${eventName}`,
                            characterOrSupport: supportName,
                            eventName,
                            options: eventOptions,
                            type: "support",
                        })
                    }
                })
            }
        })

        return events
    }, [])

    // Filter events based on search query and exclude already-overridden events.
    const filteredEvents = useMemo(() => {
        const characterOverrides = characterEventOverrides || {}
        const supportOverrides = supportEventOverrides || {}

        // Filter out events that already have overrides.
        let availableEvents = allEvents.filter((event) => {
            if (event.type === "character") {
                return !(event.key in characterOverrides)
            } else {
                return !(event.key in supportOverrides)
            }
        })

        // Apply search query filter.
        if (!eventOverrideSearchQuery.trim()) return availableEvents
        const query = eventOverrideSearchQuery.toLowerCase()
        return availableEvents.filter((event) => {
            return event.characterOrSupport.toLowerCase().includes(query) || event.eventName.toLowerCase().includes(query)
        })
    }, [allEvents, eventOverrideSearchQuery, characterEventOverrides, supportEventOverrides])

    const updateEventOverride = (eventKey: string, optionIndex: number) => {
        const isCharacter = allEvents.find((e) => e.key === eventKey)?.type === "character"

        if (isCharacter) {
            setSettings({
                ...bsc.settings,
                trainingEvent: {
                    ...bsc.settings.trainingEvent,
                    characterEventOverrides: {
                        ...(bsc.settings.trainingEvent.characterEventOverrides || {}),
                        [eventKey]: optionIndex,
                    },
                },
            })
        } else {
            setSettings({
                ...bsc.settings,
                trainingEvent: {
                    ...bsc.settings.trainingEvent,
                    supportEventOverrides: {
                        ...(bsc.settings.trainingEvent.supportEventOverrides || {}),
                        [eventKey]: optionIndex,
                    },
                },
            })
        }
        // Close the option selection modal.
        setOptionSelectionModalVisible(false)
        setSelectedEventForOption(null)
    }

    const removeEventOverride = (eventKey: string) => {
        const isCharacter = allEvents.find((e) => e.key === eventKey)?.type === "character"

        if (isCharacter) {
            const newOverrides = { ...(bsc.settings.trainingEvent.characterEventOverrides || {}) }
            delete newOverrides[eventKey]
            setSettings({
                ...bsc.settings,
                trainingEvent: {
                    ...bsc.settings.trainingEvent,
                    characterEventOverrides: newOverrides,
                },
            })
        } else {
            const newOverrides = { ...(bsc.settings.trainingEvent.supportEventOverrides || {}) }
            delete newOverrides[eventKey]
            setSettings({
                ...bsc.settings,
                trainingEvent: {
                    ...bsc.settings.trainingEvent,
                    supportEventOverrides: newOverrides,
                },
            })
        }
    }

    // Get all currently set overrides.
    const currentOverrides = useMemo(() => {
        const overrides: Array<{ key: string; characterOrSupport: string; eventName: string; optionIndex: number; options: string[] }> = []
        const characterOverrides = characterEventOverrides || {}
        const supportOverrides = supportEventOverrides || {}

        Object.keys(characterOverrides).forEach((key) => {
            const event = allEvents.find((e) => e.key === key)
            if (event) {
                overrides.push({
                    key,
                    characterOrSupport: event.characterOrSupport,
                    eventName: event.eventName,
                    optionIndex: characterOverrides[key],
                    options: event.options,
                })
            }
        })
        Object.keys(supportOverrides).forEach((key) => {
            const event = allEvents.find((e) => e.key === key)
            if (event) {
                overrides.push({
                    key,
                    characterOrSupport: event.characterOrSupport,
                    eventName: event.eventName,
                    optionIndex: supportOverrides[key],
                    options: event.options,
                })
            }
        })
        return overrides
    }, [characterEventOverrides, supportEventOverrides, allEvents])

    // Render function for event items - memoized for performance.
    const renderEventItem = useCallback(({ item: event }: { item: { key: string; characterOrSupport: string; eventName: string; options: string[]; type: "character" | "support" } }) => {
        return (
            <TouchableOpacity
                style={styles.eventItem}
                onPress={() => {
                    // Store the event and close search modal, then open option selection modal.
                    setSelectedEventForOption(event)
                    setEventOverrideModalVisible(false)
                    setOptionSelectionModalVisible(true)
                }}
            >
                <View style={styles.eventItemHeader}>
                    <View style={{ flex: 1 }}>
                        <Text style={styles.eventItemCharacterName}>{event.characterOrSupport}</Text>
                        <Text style={styles.eventItemEventName}>{event.eventName}</Text>
                    </View>
                </View>
            </TouchableOpacity>
        )
    }, [])

    const keyExtractor = useCallback((item: { key: string; characterOrSupport: string; eventName: string; options: string[]; type: "character" | "support" }) => item.key, [])

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
                overrideCard: {
                    paddingVertical: 12,
                    paddingHorizontal: 12,
                    borderRadius: 8,
                    borderWidth: 1,
                    marginBottom: 10,
                    backgroundColor: colors.card,
                    borderColor: colors.border,
                },
                overrideCardHeader: {
                    flexDirection: "row",
                    alignItems: "flex-start",
                    justifyContent: "space-between",
                    marginBottom: 8,
                },
                overrideCharacterName: {
                    fontSize: 12,
                    color: colors.lightlyMuted,
                    marginBottom: 4,
                },
                overrideEventName: {
                    fontSize: 16,
                    fontWeight: "600",
                    color: colors.foreground,
                },
                removeButton: {
                    padding: 4,
                },
                overrideOptionContainer: {
                    marginTop: 8,
                    paddingTop: 8,
                    borderTopWidth: 1,
                    borderTopColor: colors.border,
                },
                overrideOptionLabel: {
                    fontSize: 12,
                    color: colors.lightlyMuted,
                    marginBottom: 4,
                },
                overrideOptionText: {
                    fontSize: 14,
                    color: colors.foreground,
                },
                modalOverlay: {
                    flex: 1,
                    backgroundColor: "rgba(0, 0, 0, 0.5)",
                    justifyContent: "center",
                    alignItems: "center",
                },
                modalContent: {
                    backgroundColor: colors.background,
                    borderRadius: 16,
                    padding: 20,
                    width: Dimensions.get("window").width * 0.9,
                    maxHeight: Dimensions.get("window").height * 0.8,
                    flexDirection: "column",
                    justifyContent: "flex-start",
                },
                modalHeader: {
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "center",
                    marginBottom: 20,
                },
                modalTitle: {
                    fontSize: 20,
                    fontWeight: "bold",
                    color: colors.foreground,
                },
                closeButton: {
                    padding: 8,
                },
                searchContainer: {
                    flexDirection: "row",
                    alignItems: "center",
                    backgroundColor: colors.card,
                    borderWidth: 1,
                    borderColor: colors.border,
                    borderRadius: 8,
                    paddingHorizontal: 12,
                    marginBottom: 20,
                },
                searchInput: {
                    flex: 1,
                    paddingVertical: 12,
                    color: colors.foreground,
                    fontSize: 12,
                    backgroundColor: "transparent",
                },
                clearSearchButton: {
                    padding: 8,
                    marginLeft: 8,
                },
                eventList: {
                    height: 400,
                    minHeight: 400,
                },
                eventItem: {
                    paddingVertical: 12,
                    paddingHorizontal: 12,
                    borderRadius: 8,
                    borderWidth: 1,
                    marginBottom: 10,
                    backgroundColor: colors.card,
                    borderColor: colors.border,
                },
                eventItemHeader: {
                    flexDirection: "row",
                    alignItems: "flex-start",
                    justifyContent: "space-between",
                },
                eventItemCharacterName: {
                    fontSize: 12,
                    color: colors.lightlyMuted,
                    marginBottom: 4,
                },
                eventItemEventName: {
                    fontSize: 16,
                    fontWeight: "600",
                    color: colors.foreground,
                    flex: 1,
                },
                optionSelectContainer: {
                    marginTop: 12,
                    paddingTop: 12,
                    borderTopWidth: 1,
                    borderTopColor: colors.border,
                },
                optionSelectLabel: {
                    fontSize: 14,
                    color: colors.foreground,
                    marginBottom: 8,
                    fontWeight: "600",
                },
                optionButton: {
                    paddingVertical: 10,
                    paddingHorizontal: 12,
                    borderRadius: 6,
                    marginBottom: 8,
                    borderWidth: 1,
                    borderColor: colors.border,
                },
                optionButtonSelected: {
                    backgroundColor: colors.primary,
                    borderColor: colors.primary,
                },
                optionButtonText: {
                    fontSize: 14,
                    color: colors.foreground,
                },
                optionButtonTextSelected: {
                    color: colors.primaryForeground,
                },
                noResults: {
                    textAlign: "center",
                    color: colors.foreground,
                    opacity: 0.6,
                    padding: 20,
                },
            }),
        [colors],
    )

    return (
        <View style={styles.root}>
            <PageHeader title="Training Event Settings" />

            <SearchPageProvider page="TrainingEventSettings" scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <View style={styles.section}>
                            <CustomCheckbox
                                searchId="prioritize-energy-options"
                                checked={enablePrioritizeEnergyOptions}
                                onCheckedChange={(checked) => updateTrainingEventSetting("enablePrioritizeEnergyOptions", checked)}
                                label="Prioritize Energy Options"
                                description="When enabled, the bot will prioritize training event choices that provide energy recovery or avoid energy consumption, helping to maintain optimal energy levels for training sessions."
                                className="my-2"
                            />
                        </View>

                        <CustomTitle
                            searchId="training-event-option-overrides"
                            title="Training Event Option Overrides"
                            description="Force the bot to select a specific option for character or support training events. Search through all available events and select which option to use. This overrides the normal stat prioritization logic."
                        />

                        <View style={styles.section}>
                            <CustomButton onPress={() => setEventOverrideModalVisible(true)} variant="default">
                                Search Events
                            </CustomButton>
                        </View>

                        {currentOverrides.length > 0 && (
                            <View style={styles.section}>
                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>Current Overrides ({currentOverrides.length})</Text>
                                {currentOverrides.map((override) => {
                                    const event = allEvents.find((e) => e.key === override.key)
                                    return (
                                        <TouchableOpacity
                                            key={override.key}
                                            style={styles.overrideCard}
                                            onPress={() => {
                                                if (event) {
                                                    setSelectedEventForOption(event)
                                                    setOptionSelectionModalVisible(true)
                                                }
                                            }}
                                        >
                                            <View style={styles.overrideCardHeader}>
                                                <View style={{ flex: 1 }}>
                                                    <Text style={styles.overrideCharacterName}>{override.characterOrSupport}</Text>
                                                    <Text style={styles.overrideEventName}>{override.eventName}</Text>
                                                </View>
                                                <TouchableOpacity
                                                    onPress={(e) => {
                                                        e.stopPropagation()
                                                        removeEventOverride(override.key)
                                                    }}
                                                    style={styles.removeButton}
                                                >
                                                    <X size={20} color={colors.destructive} />
                                                </TouchableOpacity>
                                            </View>
                                            <View style={styles.overrideOptionContainer}>
                                                <Text style={styles.overrideOptionLabel}>Selected Option: {override.optionIndex + 1}</Text>
                                                <Text style={styles.overrideOptionText}>{override.options[override.optionIndex]}</Text>
                                            </View>
                                        </TouchableOpacity>
                                    )
                                })}
                            </View>
                        )}

                        <CustomTitle
                            searchId="special-event-overrides"
                            title="Special Event Overrides"
                            description="Override the bot's normal stat prioritization for specific training events. These settings bypass the standard weight calculation system."
                        />

                        <CustomAccordion
                            type="single"
                            style={{ marginBottom: 24 }}
                            sections={[
                                {
                                    value: "holiday-events",
                                    title: "Holiday Events",
                                    children: (
                                        <View>
                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>New Year's Resolutions (Classic Year)</Text>
                                                <CustomSelect
                                                    options={newYearResolutionsOptions}
                                                    value={specialEventOverrides["New Year's Resolutions"]?.selectedOption || "Option 2: Energy +20"}
                                                    onValueChange={(value) => updateSpecialEventOverride("New Year's Resolutions", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>

                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>New Year's Shrine Visit (Senior Year)</Text>
                                                <CustomSelect
                                                    options={newYearShrineVisitOptions}
                                                    value={specialEventOverrides["New Year's Shrine Visit"]?.selectedOption || "Option 1: Energy +30"}
                                                    onValueChange={(value) => updateSpecialEventOverride("New Year's Shrine Visit", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>
                                        </View>
                                    ),
                                },
                                {
                                    value: "race-results",
                                    title: "Race Result Events",
                                    children: (
                                        <View>
                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>Victory!</Text>
                                                <CustomSelect
                                                    options={victoryOptions}
                                                    value={specialEventOverrides["Victory!"]?.selectedOption || "Option 2: Energy -5 and random stat gain"}
                                                    onValueChange={(value) => updateSpecialEventOverride("Victory!", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>

                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>Solid Showing</Text>
                                                <CustomSelect
                                                    options={solidShowingOptions}
                                                    value={specialEventOverrides["Solid Showing"]?.selectedOption || "Option 2: Energy -5/-20 and random stat gain"}
                                                    onValueChange={(value) => updateSpecialEventOverride("Solid Showing", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>

                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>Defeat</Text>
                                                <CustomSelect
                                                    options={defeatOptions}
                                                    value={specialEventOverrides["Defeat"]?.selectedOption || "Option 1: Energy -25 and random stat gain"}
                                                    onValueChange={(value) => updateSpecialEventOverride("Defeat", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>
                                        </View>
                                    ),
                                },
                                {
                                    value: "training-failures",
                                    title: "Training Failure Events",
                                    children: (
                                        <View>
                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>Get Well Soon!</Text>
                                                <CustomSelect
                                                    options={getWellSoonOptions}
                                                    value={specialEventOverrides["Get Well Soon!"]?.selectedOption || "Option 2: (Random) Mood -1 / Stat decrease / Get Practice Poor negative status"}
                                                    onValueChange={(value) => updateSpecialEventOverride("Get Well Soon!", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>

                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>Don't Overdo It!</Text>
                                                <CustomSelect
                                                    options={dontOverdoItOptions}
                                                    value={
                                                        specialEventOverrides["Don't Overdo It!"]?.selectedOption || "Option 2: (Random) Mood -3 / Stat decrease / Get Practice Poor negative status"
                                                    }
                                                    onValueChange={(value) => updateSpecialEventOverride("Don't Overdo It!", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>
                                        </View>
                                    ),
                                },
                                {
                                    value: "miscellaneous",
                                    title: "Miscellaneous Events",
                                    children: (
                                        <View>
                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>Extra Training</Text>
                                                <CustomSelect
                                                    options={extraTrainingOptions}
                                                    value={specialEventOverrides["Extra Training"]?.selectedOption || "Option 2: Energy +5"}
                                                    onValueChange={(value) => updateSpecialEventOverride("Extra Training", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>

                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 8 }}>Acupuncture (Just an Acupuncturist, No Worries! ☆)</Text>
                                                <Text style={{ fontSize: 14, color: colors.mutedForeground, marginBottom: 12 }}>
                                                    Select your preferred option for the Acupuncture event. Note: Options 1-4 have a 70%/55%/30%/15% chance to fail, while Option 5 will always succeed.
                                                </Text>
                                                <CustomSelect
                                                    options={acupunctureOptions}
                                                    value={specialEventOverrides["Acupuncture (Just an Acupuncturist, No Worries! ☆)"]?.selectedOption || "Option 5: Energy +10"}
                                                    onValueChange={(value) => updateSpecialEventOverride("Acupuncture (Just an Acupuncturist, No Worries! ☆)", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>

                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 12 }}>Etsuko's Exhaustive Coverage</Text>
                                                <CustomSelect
                                                    options={etsukoOptions}
                                                    value={specialEventOverrides["Etsuko's Exhaustive Coverage"]?.selectedOption || "Option 2: Energy Down / Gain skill points"}
                                                    onValueChange={(value) => updateSpecialEventOverride("Etsuko's Exhaustive Coverage", "selectedOption", value)}
                                                    placeholder="Select Option"
                                                    width="100%"
                                                />
                                            </View>

                                            <View style={styles.section}>
                                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground, marginBottom: 8 }}>A Team at Last (Unity Cup)</Text>
                                                <Text style={{ fontSize: 14, color: colors.mutedForeground, marginBottom: 12 }}>
                                                    Select your preferred team name for Unity Cup (must be available via your chosen trainee or supports). The available options depend on which
                                                    characters you have bonded with. "Default" will always select the first option.
                                                </Text>
                                                <CustomSelect
                                                    options={aTeamAtLastOptions}
                                                    value={specialEventOverrides["A Team at Last"]?.selectedOption || "Default"}
                                                    onValueChange={(value) => updateSpecialEventOverride("A Team at Last", "selectedOption", value)}
                                                    placeholder="Select Team Name for Unity Cup"
                                                    width="100%"
                                                />
                                            </View>
                                        </View>
                                    ),
                                },
                            ]}
                        />
                    </View>
                </ScrollView>
            </SearchPageProvider>

            {/* Event Override Selection Modal */}
            <Modal animationType="slide" transparent={true} visible={eventOverrideModalVisible} onRequestClose={() => setEventOverrideModalVisible(false)}>
                <TouchableOpacity style={styles.modalOverlay} activeOpacity={1} onPress={() => setEventOverrideModalVisible(false)}>
                    <TouchableOpacity style={styles.modalContent} activeOpacity={1} onPress={(e) => e.stopPropagation()}>
                        <View style={styles.modalHeader}>
                            <Text style={styles.modalTitle}>Select Event Override</Text>
                            <TouchableOpacity style={styles.closeButton} onPress={() => setEventOverrideModalVisible(false)}>
                                <X size={24} color={colors.foreground} />
                            </TouchableOpacity>
                        </View>

                        <View style={styles.searchContainer}>
                            <Search size={20} color={colors.foreground} />
                            <TextInput
                                style={styles.searchInput}
                                placeholder="Search by character/support or event name..."
                                placeholderTextColor={colors.mutedForeground}
                                value={eventOverrideSearchQuery}
                                onChangeText={setEventOverrideSearchQuery}
                            />
                            {eventOverrideSearchQuery.length > 0 && (
                                <TouchableOpacity style={styles.clearSearchButton} onPress={() => setEventOverrideSearchQuery("")}>
                                    <X size={16} color={colors.foreground} />
                                </TouchableOpacity>
                            )}
                        </View>

                        <View style={styles.eventList}>
                            <FlashList
                                data={filteredEvents}
                                renderItem={renderEventItem}
                                keyExtractor={keyExtractor}
                                ListEmptyComponent={
                                    <View style={{ padding: 20 }}>
                                        <Text style={styles.noResults}>
                                            {allEvents.length === 0
                                                ? "No events available. Please select characters and/or support cards in the sections below to see their events."
                                                : filteredEvents.length === 0 && (Object.keys(characterEventOverrides || {}).length > 0 || Object.keys(supportEventOverrides || {}).length > 0)
                                                ? "All available events have been overridden. Remove an override to add it again."
                                                : "No events match your search. Try a different search term."}
                                        </Text>
                                    </View>
                                }
                            />
                        </View>
                    </TouchableOpacity>
                </TouchableOpacity>
            </Modal>

            {/* Option Selection Modal */}
            <Modal
                animationType="slide"
                transparent={true}
                visible={optionSelectionModalVisible}
                onRequestClose={() => {
                    setOptionSelectionModalVisible(false)
                    setEventOverrideModalVisible(true)
                }}
            >
                <TouchableOpacity
                    style={styles.modalOverlay}
                    activeOpacity={1}
                    onPress={() => {
                        setOptionSelectionModalVisible(false)
                        setEventOverrideModalVisible(true)
                    }}
                >
                    <TouchableOpacity style={styles.modalContent} activeOpacity={1} onPress={(e) => e.stopPropagation()}>
                        <View style={styles.modalHeader}>
                            <Text style={styles.modalTitle}>Select Option</Text>
                            <TouchableOpacity
                                style={styles.closeButton}
                                onPress={() => {
                                    setOptionSelectionModalVisible(false)
                                    setEventOverrideModalVisible(true)
                                }}
                            >
                                <X size={24} color={colors.foreground} />
                            </TouchableOpacity>
                        </View>

                        {selectedEventForOption && (
                            <>
                                <View style={{ marginBottom: 20 }}>
                                    <Text style={styles.overrideCharacterName}>{selectedEventForOption.characterOrSupport}</Text>
                                    <Text style={styles.overrideEventName}>{selectedEventForOption.eventName}</Text>
                                </View>

                                <View style={styles.optionSelectContainer}>
                                    <Text style={styles.optionSelectLabel}>Select Option:</Text>
                                    {selectedEventForOption.options.map((option: string, index: number) => {
                                        const characterOverrides = characterEventOverrides || {}
                                        const supportOverrides = supportEventOverrides || {}
                                        const currentOverride =
                                            selectedEventForOption.type === "character" ? characterOverrides[selectedEventForOption.key] : supportOverrides[selectedEventForOption.key]
                                        const isOptionSelected = currentOverride === index
                                        return (
                                            <TouchableOpacity
                                                key={index}
                                                style={[styles.optionButton, isOptionSelected && styles.optionButtonSelected]}
                                                onPress={() => updateEventOverride(selectedEventForOption.key, index)}
                                            >
                                                <Text style={[styles.optionButtonText, isOptionSelected && styles.optionButtonTextSelected]}>
                                                    Option {index + 1}: {option}
                                                </Text>
                                            </TouchableOpacity>
                                        )
                                    })}
                                </View>

                                <View style={{ marginTop: 20 }}>
                                    <CustomButton
                                        onPress={() => {
                                            setOptionSelectionModalVisible(false)
                                            setEventOverrideModalVisible(true)
                                        }}
                                        variant="default"
                                    >
                                        Cancel
                                    </CustomButton>
                                </View>
                            </>
                        )}
                    </TouchableOpacity>
                </TouchableOpacity>
            </Modal>
        </View>
    )
}

export default TrainingEventSettings
