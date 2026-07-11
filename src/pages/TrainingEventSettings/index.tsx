import { useContext, useState, useMemo, useCallback, useRef } from "react"
import { View, Text, ScrollView, StyleSheet, Pressable, TextInput } from "react-native"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import { GlassSurface } from "../../components/ui/glass-surface"
import { FlashList } from "@shopify/flash-list"
import { useTheme } from "../../context/ThemeContext"
import { TrainingEventContext, defaultSettings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import CustomSelect from "../../components/CustomSelect"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import { Section } from "../../components/ui/section"
import { Switch } from "../../components/ui/switch"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { ChevronDown, ChevronUp, Plus, Search, X } from "lucide-react-native"
import PageHeader from "../../components/PageHeader"
import CustomSlider from "../../components/CustomSlider"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

// Import the data files.
import charactersData from "../../data/characters.json"
import supportsData from "../../data/supports.json"
import scenariosData from "../../data/scenarios.json"

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

/**
 * The Training Event Settings page.
 * Allows configuration of special event option overrides (holiday, race results,
 * training failures, misc), character/support event overrides via a searchable modal,
 * and energy prioritization preferences.
 */
const TrainingEventSettings = () => {
    usePerformanceLogging("TrainingEventSettings")
    const { colors } = useTheme()
    const modalShellStyles = useModalShellStyles()
    const { trainingEvent, updateTrainingEvent } = useContext(TrainingEventContext)
    const scrollViewRef = useRef<ScrollView>(null)

    // Merge current training event settings with defaults to handle missing properties.
    const {
        enablePrioritizeEnergyOptions,
        enableAutomaticOCRRetry,
        ocrConfidence,
        enableHideOCRComparisonResults,
        specialEventOverrides,
        characterEventOverrides,
        supportEventOverrides,
        scenarioEventOverrides,
    } = { ...defaultSettings.trainingEvent, ...trainingEvent }

    const [eventOverrideModalVisible, setEventOverrideModalVisible] = useState(false)
    const [eventOverrideSearchQuery, setEventOverrideSearchQuery] = useState("")
    const [specialOverrideOpen, setSpecialOverrideOpen] = useState<{ holiday: boolean; raceResult: boolean; trainingFailure: boolean; misc: boolean }>({
        holiday: false,
        raceResult: false,
        trainingFailure: false,
        misc: false,
    })
    const toggleSpecialOverride = useCallback((key: "holiday" | "raceResult" | "trainingFailure" | "misc") => {
        setSpecialOverrideOpen((prev) => ({ ...prev, [key]: !prev[key] }))
    }, [])
    const [optionSelectionModalVisible, setOptionSelectionModalVisible] = useState(false)
    const [selectedEventForOption, setSelectedEventForOption] = useState<{
        key: string
        characterOrSupport: string
        eventName: string
        options: string[]
        type: "character" | "support" | "scenario"
    } | null>(null)

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
        { value: "Option 2: Energy -5/-20 and random stat gain", label: "Option 2: Energy -5/-20 and random stat gain" },
    ]
    const solidShowingOptions = [
        { value: "Option 1: Energy -15 and random stat gain", label: "Option 1: Energy -15 and random stat gain" },
        { value: "Option 2: Energy -5/-20 and random stat gain", label: "Option 2: Energy -5/-20 and random stat gain" },
    ]
    const defeatOptions = [
        { value: "Option 1: Energy -25 and random stat gain", label: "Option 1: Energy -25 and random stat gain" },
        { value: "Option 2: Energy -15/-35 and random stat gain", label: "Option 2: Energy -15/-35 and random stat gain" },
    ]
    const energyAwareHint = "When enabled, picks this option at 21-100% energy and swaps to the other option at <=20% energy."
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
        { value: "Team Carrot (Last Option)", label: "Team Carrot" },
    ]

    /**
     * Update a training event setting.
     * @param key The key of the setting to update.
     * @param value The value to set the setting to.
     */
    const updateTrainingEventSetting = (key: keyof typeof trainingEvent, value: any) => {
        updateTrainingEvent({ [key]: value } as any)
    }

    /**
     * Update a specific field for a special event override.
     * @param eventName The name of the special event (e.g., `"New Year's Resolutions"`).
     * @param field The field to update (`selectedOption` or `requiresConfirmation`).
     * @param value The new value for the field.
     */
    const updateSpecialEventOverride = (eventName: string, field: "selectedOption" | "requiresConfirmation" | "enableEnergyBasedSelection", value: any) => {
        updateTrainingEvent((prev) => ({
            ...prev,
            specialEventOverrides: {
                ...prev.specialEventOverrides,
                [eventName]: {
                    ...prev.specialEventOverrides[eventName],
                    [field]: value,
                },
            },
        }))
    }

    /**
     * Build a flattened list of all available events from character and support data.
     * Filters out excluded events and events with fewer than two options.
     */
    const allEvents = useMemo(() => {
        const events: Array<{ key: string; characterOrSupport: string; eventName: string; options: string[]; type: "character" | "support" | "scenario" }> = []

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
                            eventName: eventName.replace(/\n/g, " "),
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
                            eventName: eventName.replace(/\n/g, " "),
                            options: eventOptions,
                            type: "support",
                        })
                    }
                })
            }
        })

        // Add all scenario events from the data file.
        Object.keys(scenariosData).forEach((scenarioName) => {
            const scenarioEvents = scenariosData[scenarioName as keyof typeof scenariosData] as Record<string, string[]>
            if (scenarioEvents) {
                Object.keys(scenarioEvents).forEach((eventName) => {
                    const eventOptions = scenarioEvents[eventName]
                    // Skip events that are already covered in Special Event Overrides and that have fewer than 2 options.
                    if (!excludedEventNames.has(eventName) && eventOptions && eventOptions.length >= 2) {
                        events.push({
                            key: `${scenarioName}|${eventName}`,
                            characterOrSupport: scenarioName,
                            eventName: eventName.replace(/\n/g, " "),
                            options: eventOptions,
                            type: "scenario",
                        })
                    }
                })
            }
        })

        return events
    }, [])

    /**
     * Filter the available events based on search query and existing overrides.
     */
    const filteredEvents = useMemo(() => {
        const characterOverrides = characterEventOverrides || {}
        const supportOverrides = supportEventOverrides || {}

        // Filter out events that already have overrides.
        let availableEvents = allEvents.filter((event) => {
            if (event.type === "character") {
                return !(event.key in characterOverrides)
            } else if (event.type === "support") {
                return !(event.key in supportOverrides)
            } else {
                return !(event.key in (scenarioEventOverrides || {}))
            }
        })

        // Apply search query filter.
        if (!eventOverrideSearchQuery.trim()) return availableEvents
        const query = eventOverrideSearchQuery.toLowerCase()
        return availableEvents.filter((event) => {
            return event.characterOrSupport.toLowerCase().includes(query) || event.eventName.toLowerCase().includes(query)
        })
    }, [allEvents, eventOverrideSearchQuery, characterEventOverrides, supportEventOverrides, scenarioEventOverrides])

    /**
     * Add or update an event override for a specific character or support event.
     * @param eventKey The unique key identifying the event.
     * @param optionIndex The index of the selected option.
     */
    const updateEventOverride = (eventKey: string, optionIndex: number) => {
        const eventType = allEvents.find((e) => e.key === eventKey)?.type

        if (eventType === "character") {
            updateTrainingEvent((prev) => ({
                ...prev,
                characterEventOverrides: { ...(prev.characterEventOverrides || {}), [eventKey]: optionIndex },
            }))
        } else if (eventType === "support") {
            updateTrainingEvent((prev) => ({
                ...prev,
                supportEventOverrides: { ...(prev.supportEventOverrides || {}), [eventKey]: optionIndex },
            }))
        } else if (eventType === "scenario") {
            updateTrainingEvent((prev) => ({
                ...prev,
                scenarioEventOverrides: { ...(prev.scenarioEventOverrides || {}), [eventKey]: optionIndex },
            }))
        }
        // Close the option selection modal.
        setOptionSelectionModalVisible(false)
        setSelectedEventForOption(null)
    }

    /**
     * Remove an event override for a specific character or support event.
     * @param eventKey The unique key identifying the event to remove.
     */
    const removeEventOverride = (eventKey: string) => {
        const eventType = allEvents.find((e) => e.key === eventKey)?.type

        if (eventType === "character") {
            updateTrainingEvent((prev) => {
                const next = { ...(prev.characterEventOverrides || {}) }
                delete next[eventKey]
                return { ...prev, characterEventOverrides: next }
            })
        } else if (eventType === "support") {
            updateTrainingEvent((prev) => {
                const next = { ...(prev.supportEventOverrides || {}) }
                delete next[eventKey]
                return { ...prev, supportEventOverrides: next }
            })
        } else if (eventType === "scenario") {
            updateTrainingEvent((prev) => {
                const next = { ...(prev.scenarioEventOverrides || {}) }
                delete next[eventKey]
                return { ...prev, scenarioEventOverrides: next }
            })
        }
    }

    /**
     * Retrieve a list of all current character and support event overrides.
     */
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
        Object.keys(scenarioEventOverrides || {}).forEach((key) => {
            const event = allEvents.find((e) => e.key === key)
            if (event) {
                overrides.push({
                    key,
                    characterOrSupport: event.characterOrSupport,
                    eventName: event.eventName,
                    optionIndex: scenarioEventOverrides[key],
                    options: event.options,
                })
            }
        })
        return overrides
    }, [characterEventOverrides, supportEventOverrides, scenarioEventOverrides, allEvents])

    // Event names that belong to each special-event accordion. Used to compute count pills shown at the top of each category.
    const holidayEventNames = ["New Year's Resolutions", "New Year's Shrine Visit"]
    const raceResultEventNames = ["Victory!", "Solid Showing", "Defeat"]
    const trainingFailureEventNames = ["Get Well Soon!", "Don't Overdo It!"]
    const miscEventNames = ["Extra Training", "Acupuncture (Just an Acupuncturist, No Worries! ☆)", "Etsuko's Exhaustive Coverage", "A Team at Last"]

    /**
     * Count how many of the given event names have an entry in `specialEventOverrides`. Counts every present key, including ones that were set back to a default value.
     * @param names Event names that belong to a single accordion category.
     * @returns Number of events in `names` that are present in `specialEventOverrides`.
     */
    const countOverrides = useCallback(
        (names: string[]) => {
            const overrides = specialEventOverrides || {}
            return names.reduce((acc, name) => (overrides[name] ? acc + 1 : acc), 0)
        },
        [specialEventOverrides]
    )

    const holidayCount = countOverrides(holidayEventNames)
    const raceResultCount = countOverrides(raceResultEventNames)
    const trainingFailureCount = countOverrides(trainingFailureEventNames)
    const miscCount = countOverrides(miscEventNames)
    const totalOverrideCount = currentOverrides.length

    /**
     * Render a single event item for the selection list.
     * @param event The event data to render.
     */
    const renderEventItem = useCallback(
        ({ item: event }: { item: { key: string; characterOrSupport: string; eventName: string; options: string[]; type: "character" | "support" | "scenario" } }) => {
            return (
                <Pressable
                    style={styles.eventRow}
                    android_ripple={{ color: colors.ripple, foreground: true }}
                    accessibilityRole="button"
                    onPress={() => {
                        setSelectedEventForOption(event)
                        setEventOverrideModalVisible(false)
                        setOptionSelectionModalVisible(true)
                    }}
                >
                    <Text style={styles.eventTag}>{event.characterOrSupport.toUpperCase()}</Text>
                    <Text style={styles.eventName}>{event.eventName}</Text>
                </Pressable>
            )
        },
        [colors]
    )

    /**
     * Extract a unique key for an event item in the list.
     * @param item The event item.
     * @returns The unique key.
     */
    const keyExtractor = useCallback((item: { key: string; characterOrSupport: string; eventName: string; options: string[]; type: "character" | "support" | "scenario" }) => item.key, [])

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
                overrideCard: {
                    paddingVertical: 12,
                    paddingHorizontal: 12,
                    borderRadius: 8,
                    borderWidth: 1,
                    marginBottom: 10,
                    backgroundColor: colors.surface,
                    borderColor: colors.borderHair,
                },
                overrideCardHeader: {
                    flexDirection: "row",
                    alignItems: "flex-start",
                    justifyContent: "space-between",
                    marginBottom: 8,
                },
                overrideCharacterName: {
                    fontSize: 12,
                    color: colors.textMuted,
                    marginBottom: 4,
                },
                overrideEventName: {
                    fontSize: 16,
                    fontWeight: "600",
                    color: colors.text,
                },
                removeButton: {
                    padding: 4,
                },
                overrideOptionContainer: {
                    marginTop: 8,
                    paddingTop: 8,
                    borderTopWidth: 1,
                    borderTopColor: colors.borderHair,
                },
                overrideOptionLabel: {
                    fontSize: 12,
                    color: colors.textMuted,
                    marginBottom: 4,
                },
                overrideOptionText: {
                    fontSize: 14,
                    color: colors.text,
                },
                searchRow: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: SPACING.sm,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: SPACING.xs + 2,
                    borderRadius: RADII.md,
                    borderWidth: 1,
                    borderColor: colors.brandBorder,
                    backgroundColor: colors.surface,
                    marginBottom: SPACING.sm,
                },
                searchInput: { flex: 1, ...TYPE.body, color: colors.text, padding: 0 },
                searchClear: { padding: 4 },
                eventRow: {
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: SPACING.sm,
                    borderRadius: RADII.md,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    backgroundColor: colors.surfaceRaised,
                    marginBottom: SPACING.xs + 2,
                    overflow: "hidden",
                    gap: 2,
                },
                eventTag: { ...TYPE.monoLabel, color: colors.textMuted, fontSize: 9, letterSpacing: 1.5 },
                eventName: { ...TYPE.body, color: colors.text },
                noResults: { ...TYPE.body, color: colors.textMuted, textAlign: "center", paddingVertical: SPACING.lg },
                optionHeaderBlock: { gap: 2, marginBottom: SPACING.sm },
                optionHeaderTag: { ...TYPE.monoLabel, color: colors.textMuted, fontSize: 9, letterSpacing: 1.5 },
                optionHeaderName: { ...TYPE.body, color: colors.text, fontWeight: "600" as const },
                categoryHeader: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: SPACING.sm,
                    paddingHorizontal: SPACING.xs,
                    paddingBottom: SPACING.sm,
                },
                categoryHeaderTitle: {
                    ...TYPE.body,
                    color: colors.text,
                    fontWeight: "600",
                    flex: 1,
                },
                countPill: {
                    ...TYPE.monoLabel,
                    color: colors.brand,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: 2,
                    backgroundColor: colors.brandSubtle,
                    borderRadius: RADII.pill,
                },
                subSectionRow: {
                    flexDirection: "row" as const,
                    alignItems: "center" as const,
                    paddingVertical: SPACING.md,
                    paddingHorizontal: SPACING.lg,
                    gap: SPACING.md,
                },
                subSectionRowTitle: { ...TYPE.body, color: colors.text, fontWeight: "500" as const, flex: 1 },
                subSectionBody: { paddingHorizontal: SPACING.md, paddingTop: SPACING.sm, paddingBottom: SPACING.sm, gap: SPACING.sm },
                ctaCard: {
                    borderRadius: RADII.lg,
                    overflow: "hidden",
                },
                ctaCardInner: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: SPACING.md,
                    padding: SPACING.md,
                },
                ctaCardIcon: {
                    width: 32,
                    height: 32,
                    borderRadius: 999,
                    backgroundColor: colors.brand,
                    alignItems: "center",
                    justifyContent: "center",
                },
                ctaCardTitle: {
                    ...TYPE.body,
                    color: colors.brand,
                    fontWeight: "600",
                },
                ctaCardCaption: {
                    ...TYPE.caption,
                    color: colors.textMuted,
                },
            }),
        [colors]
    )

    return (
        <View style={styles.root}>
            <SearchPageProvider page="TrainingEventSettings" scrollViewRef={scrollViewRef}>
                <PageHeader title="Training Event Settings" />
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <Section label="General">
                            <ToggleSetting
                                id="prioritize-energy-options"
                                title="Prioritize Energy Options"
                                description="When enabled, the bot will prioritize training event choices that provide energy recovery or avoid energy consumption, helping to maintain optimal energy levels for training sessions."
                                checked={enablePrioritizeEnergyOptions}
                                onCheckedChange={(checked) => updateTrainingEventSetting("enablePrioritizeEnergyOptions", checked)}
                            />
                        </Section>

                        <Section label="OCR Recognition Settings">
                            <View style={{ padding: SPACING.md, paddingBottom: 0 }}>
                                <SearchableItem
                                    id="ocr-recognition-settings"
                                    title="OCR Recognition Settings"
                                    description="Configure settings for detecting and recognizing Training Event titles using OCR. These settings only affect the Training Event recognition process."
                                >
                                    <Text style={[TYPE.caption, { color: colors.textMuted, marginBottom: SPACING.md }]}>
                                        Configure settings for detecting and recognizing Training Event titles using OCR. These settings only affect the Training Event recognition process.
                                    </Text>
                                </SearchableItem>
                            </View>

                            <ToggleSetting
                                id="automatic-ocr-retry-training"
                                title="Enable Automatic OCR Retry for Training Events"
                                description="When enabled, the bot will automatically retry OCR detection with adjusted settings if the initial attempt for a training event title fails or has low confidence."
                                checked={enableAutomaticOCRRetry}
                                onCheckedChange={(checked) => updateTrainingEventSetting("enableAutomaticOCRRetry", checked)}
                            />

                            <View style={{ padding: SPACING.md }}>
                                <CustomSlider
                                    searchId="ocr-confidence-training"
                                    label="OCR Confidence for Training Events"
                                    description="The minimum confidence level required for a Training Event title to be considered a match. Higher values ensure more accurate recognition but may lead to more missed events."
                                    min={50}
                                    max={100}
                                    step={1}
                                    value={ocrConfidence}
                                    onValueChange={(value: number) => updateTrainingEventSetting("ocrConfidence", value)}
                                    showValue={true}
                                    showLabels={true}
                                />
                            </View>

                            <ToggleSetting
                                id="hide-ocr-comparison-results-training"
                                title="Hide OCR String Comparison Results"
                                description="If enabled, the bot will suppress detailed logging of individual string similarity scores during training event detection to keep the logs cleaner."
                                checked={enableHideOCRComparisonResults}
                                onCheckedChange={(checked) => updateTrainingEventSetting("enableHideOCRComparisonResults", checked)}
                            />
                        </Section>

                        <Section label="Training Event Option Overrides">
                            <View style={{ padding: SPACING.md }}>
                                <SearchableItem
                                    id="training-event-option-overrides"
                                    title="Training Event Option Overrides"
                                    description="Force the bot to select a specific option for character or support training events. Search through all available events and select which option to use. This overrides the normal stat prioritization logic."
                                >
                                    <Text style={[TYPE.caption, { color: colors.textMuted, marginBottom: SPACING.md }]}>
                                        Force the bot to select a specific option for character or support training events. Search through all available events and select which option to use. This
                                        overrides the normal stat prioritization logic.
                                    </Text>
                                </SearchableItem>

                                <Pressable onPress={() => setEventOverrideModalVisible(true)} android_ripple={{ color: colors.ripple, foreground: true }} accessibilityRole="button">
                                    <GlassSurface style={styles.ctaCard}>
                                        <View style={styles.ctaCardInner}>
                                            <View style={styles.ctaCardIcon}>
                                                <Plus size={18} color={colors.onBrand} />
                                            </View>
                                            <View style={{ flex: 1 }}>
                                                <Text style={styles.ctaCardTitle}>Add event override</Text>
                                                <Text style={styles.ctaCardCaption}>
                                                    <Text style={[TYPE.monoValue, { color: colors.textMuted }]}>{totalOverrideCount}</Text>
                                                    {" overrides active · tap to search events"}
                                                </Text>
                                            </View>
                                        </View>
                                    </GlassSurface>
                                </Pressable>
                            </View>
                        </Section>

                        {currentOverrides.length > 0 && (
                            <View style={styles.section}>
                                <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>
                                    Current Overrides (<Text style={[TYPE.monoValue, { color: colors.text }]}>{currentOverrides.length}</Text>)
                                </Text>
                                {currentOverrides.map((override) => {
                                    const event = allEvents.find((e) => e.key === override.key)
                                    return (
                                        <Pressable
                                            key={override.key}
                                            style={styles.overrideCard}
                                            android_ripple={{ color: colors.ripple, foreground: true }}
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
                                                <Pressable
                                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                                    onPress={(e) => {
                                                        e.stopPropagation()
                                                        removeEventOverride(override.key)
                                                    }}
                                                    style={styles.removeButton}
                                                >
                                                    <X size={20} color={colors.destructive} />
                                                </Pressable>
                                            </View>
                                            <View style={styles.overrideOptionContainer}>
                                                <Text style={styles.overrideOptionLabel}>
                                                    Selected Option: <Text style={[TYPE.monoValue, { color: colors.textMuted }]}>{override.optionIndex + 1}</Text>
                                                </Text>
                                                <Text style={styles.overrideOptionText}>{override.options[override.optionIndex]}</Text>
                                            </View>
                                        </Pressable>
                                    )
                                })}
                            </View>
                        )}

                        <Section label="Special Event Overrides">
                            <SearchableItem
                                id="special-event-overrides"
                                title="Special Event Overrides"
                                description="Override the bot's normal stat prioritization for specific training events. These settings bypass the standard weight calculation system."
                            >
                                <View style={{ padding: SPACING.md }}>
                                    <Text style={[TYPE.caption, { color: colors.textMuted }]}>
                                        Override the bot's normal stat prioritization for specific training events. These settings bypass the standard weight calculation system.
                                    </Text>
                                </View>
                            </SearchableItem>

                            {/* Holiday Events */}
                            <View>
                                <Pressable
                                    onPress={() => toggleSpecialOverride("holiday")}
                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                    style={styles.subSectionRow}
                                    accessibilityRole="button"
                                    accessibilityState={{ expanded: specialOverrideOpen.holiday }}
                                >
                                    <Text style={styles.subSectionRowTitle}>Holiday Events</Text>
                                    <Text style={styles.countPill}>{holidayCount}</Text>
                                    {specialOverrideOpen.holiday ? <ChevronUp size={16} color={colors.textMuted} /> : <ChevronDown size={16} color={colors.textMuted} />}
                                </Pressable>
                                {specialOverrideOpen.holiday && (
                                    <View style={styles.subSectionBody}>
                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>New Year's Resolutions (Classic Year)</Text>
                                            <CustomSelect
                                                options={newYearResolutionsOptions}
                                                value={specialEventOverrides["New Year's Resolutions"]?.selectedOption || "Option 2: Energy +20"}
                                                onValueChange={(value) => updateSpecialEventOverride("New Year's Resolutions", "selectedOption", value)}
                                                placeholder="Select Option"
                                                width="100%"
                                            />
                                        </View>

                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>New Year's Shrine Visit (Senior Year)</Text>
                                            <CustomSelect
                                                options={newYearShrineVisitOptions}
                                                value={specialEventOverrides["New Year's Shrine Visit"]?.selectedOption || "Option 1: Energy +30"}
                                                onValueChange={(value) => updateSpecialEventOverride("New Year's Shrine Visit", "selectedOption", value)}
                                                placeholder="Select Option"
                                                width="100%"
                                            />
                                        </View>
                                    </View>
                                )}
                            </View>

                            {/* Race Result Events */}
                            <View>
                                <Pressable
                                    onPress={() => toggleSpecialOverride("raceResult")}
                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                    style={styles.subSectionRow}
                                    accessibilityRole="button"
                                    accessibilityState={{ expanded: specialOverrideOpen.raceResult }}
                                >
                                    <Text style={styles.subSectionRowTitle}>Race Result Events</Text>
                                    <Text style={styles.countPill}>{raceResultCount}</Text>
                                    {specialOverrideOpen.raceResult ? <ChevronUp size={16} color={colors.textMuted} /> : <ChevronDown size={16} color={colors.textMuted} />}
                                </Pressable>
                                {specialOverrideOpen.raceResult && (
                                    <View style={styles.subSectionBody}>
                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>Victory!</Text>
                                            <CustomSelect
                                                options={victoryOptions}
                                                value={specialEventOverrides["Victory!"]?.selectedOption || "Option 1: Energy -15 and random stat gain"}
                                                onValueChange={(value) => updateSpecialEventOverride("Victory!", "selectedOption", value)}
                                                placeholder="Select Option"
                                                width="100%"
                                            />
                                            <View style={{ flexDirection: "row", alignItems: "center", marginTop: SPACING.sm, gap: SPACING.md }}>
                                                <View style={{ flex: 1 }}>
                                                    <Text style={[TYPE.body, { color: colors.text, fontWeight: "600" as const }]}>Energy-aware swap</Text>
                                                    <Text style={[TYPE.caption, { color: colors.textMuted, marginTop: 2 }]}>{energyAwareHint}</Text>
                                                </View>
                                                <Switch
                                                    checked={specialEventOverrides["Victory!"]?.enableEnergyBasedSelection || false}
                                                    onCheckedChange={(checked) => updateSpecialEventOverride("Victory!", "enableEnergyBasedSelection", checked)}
                                                />
                                            </View>
                                        </View>

                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>Solid Showing</Text>
                                            <CustomSelect
                                                options={solidShowingOptions}
                                                value={specialEventOverrides["Solid Showing"]?.selectedOption || "Option 1: Energy -15 and random stat gain"}
                                                onValueChange={(value) => updateSpecialEventOverride("Solid Showing", "selectedOption", value)}
                                                placeholder="Select Option"
                                                width="100%"
                                            />
                                            <View style={{ flexDirection: "row", alignItems: "center", marginTop: SPACING.sm, gap: SPACING.md }}>
                                                <View style={{ flex: 1 }}>
                                                    <Text style={[TYPE.body, { color: colors.text, fontWeight: "600" as const }]}>Energy-aware swap</Text>
                                                    <Text style={[TYPE.caption, { color: colors.textMuted, marginTop: 2 }]}>{energyAwareHint}</Text>
                                                </View>
                                                <Switch
                                                    checked={specialEventOverrides["Solid Showing"]?.enableEnergyBasedSelection || false}
                                                    onCheckedChange={(checked) => updateSpecialEventOverride("Solid Showing", "enableEnergyBasedSelection", checked)}
                                                />
                                            </View>
                                        </View>

                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>Defeat</Text>
                                            <CustomSelect
                                                options={defeatOptions}
                                                value={specialEventOverrides["Defeat"]?.selectedOption || "Option 1: Energy -25 and random stat gain"}
                                                onValueChange={(value) => updateSpecialEventOverride("Defeat", "selectedOption", value)}
                                                placeholder="Select Option"
                                                width="100%"
                                            />
                                            <View style={{ flexDirection: "row", alignItems: "center", marginTop: SPACING.sm, gap: SPACING.md }}>
                                                <View style={{ flex: 1 }}>
                                                    <Text style={[TYPE.body, { color: colors.text, fontWeight: "600" as const }]}>Energy-aware swap</Text>
                                                    <Text style={[TYPE.caption, { color: colors.textMuted, marginTop: 2 }]}>{energyAwareHint}</Text>
                                                </View>
                                                <Switch
                                                    checked={specialEventOverrides["Defeat"]?.enableEnergyBasedSelection || false}
                                                    onCheckedChange={(checked) => updateSpecialEventOverride("Defeat", "enableEnergyBasedSelection", checked)}
                                                />
                                            </View>
                                        </View>
                                    </View>
                                )}
                            </View>

                            {/* Training Failure Events */}
                            <View>
                                <Pressable
                                    onPress={() => toggleSpecialOverride("trainingFailure")}
                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                    style={styles.subSectionRow}
                                    accessibilityRole="button"
                                    accessibilityState={{ expanded: specialOverrideOpen.trainingFailure }}
                                >
                                    <Text style={styles.subSectionRowTitle}>Training Failure Events</Text>
                                    <Text style={styles.countPill}>{trainingFailureCount}</Text>
                                    {specialOverrideOpen.trainingFailure ? <ChevronUp size={16} color={colors.textMuted} /> : <ChevronDown size={16} color={colors.textMuted} />}
                                </Pressable>
                                {specialOverrideOpen.trainingFailure && (
                                    <View style={styles.subSectionBody}>
                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>Get Well Soon!</Text>
                                            <CustomSelect
                                                options={getWellSoonOptions}
                                                value={specialEventOverrides["Get Well Soon!"]?.selectedOption || "Option 2: (Random) Mood -1 / Stat decrease / Get Practice Poor negative status"}
                                                onValueChange={(value) => updateSpecialEventOverride("Get Well Soon!", "selectedOption", value)}
                                                placeholder="Select Option"
                                                width="100%"
                                            />
                                        </View>

                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>Don't Overdo It!</Text>
                                            <CustomSelect
                                                options={dontOverdoItOptions}
                                                value={specialEventOverrides["Don't Overdo It!"]?.selectedOption || "Option 2: (Random) Mood -3 / Stat decrease / Get Practice Poor negative status"}
                                                onValueChange={(value) => updateSpecialEventOverride("Don't Overdo It!", "selectedOption", value)}
                                                placeholder="Select Option"
                                                width="100%"
                                            />
                                        </View>
                                    </View>
                                )}
                            </View>

                            {/* Miscellaneous Events */}
                            <View>
                                <Pressable
                                    onPress={() => toggleSpecialOverride("misc")}
                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                    style={styles.subSectionRow}
                                    accessibilityRole="button"
                                    accessibilityState={{ expanded: specialOverrideOpen.misc }}
                                >
                                    <Text style={styles.subSectionRowTitle}>Miscellaneous Events</Text>
                                    <Text style={styles.countPill}>{miscCount}</Text>
                                    {specialOverrideOpen.misc ? <ChevronUp size={16} color={colors.textMuted} /> : <ChevronDown size={16} color={colors.textMuted} />}
                                </Pressable>
                                {specialOverrideOpen.misc && (
                                    <View style={styles.subSectionBody}>
                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>Extra Training</Text>
                                            <CustomSelect
                                                options={extraTrainingOptions}
                                                value={specialEventOverrides["Extra Training"]?.selectedOption || "Option 2: Energy +5"}
                                                onValueChange={(value) => updateSpecialEventOverride("Extra Training", "selectedOption", value)}
                                                placeholder="Select Option"
                                                width="100%"
                                            />
                                        </View>

                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 8 }}>Acupuncture (Just an Acupuncturist, No Worries! ☆)</Text>
                                            <Text style={{ fontSize: 14, color: colors.textMuted, marginBottom: 12 }}>
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
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 12 }}>Etsuko's Exhaustive Coverage</Text>
                                            <CustomSelect
                                                options={etsukoOptions}
                                                value={specialEventOverrides["Etsuko's Exhaustive Coverage"]?.selectedOption || "Option 2: Energy Down / Gain skill points"}
                                                onValueChange={(value) => updateSpecialEventOverride("Etsuko's Exhaustive Coverage", "selectedOption", value)}
                                                placeholder="Select Option"
                                                width="100%"
                                            />
                                        </View>

                                        <View style={styles.section}>
                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text, marginBottom: 8 }}>A Team at Last (Unity Cup)</Text>
                                            <Text style={{ fontSize: 14, color: colors.textMuted, marginBottom: 12 }}>
                                                Select your preferred team name for Unity Cup (must be available via your chosen trainee or supports). The available options depend on which characters
                                                you have bonded with. "Default" will always select the first option.
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
                                )}
                            </View>
                        </Section>
                    </View>
                </ScrollView>
            </SearchPageProvider>

            {/* Event Override Selection Modal */}
            <SheetModal
                visible={eventOverrideModalVisible}
                onRequestClose={() => setEventOverrideModalVisible(false)}
                scrollableBody={false}
                header={
                    <View style={modalShellStyles.modalHeaderRow}>
                        <Text style={modalShellStyles.modalTitleMono}>EVENT OVERRIDE</Text>
                        <Pressable
                            style={modalShellStyles.modalCloseChip}
                            onPress={() => setEventOverrideModalVisible(false)}
                            android_ripple={{ color: colors.ripple, foreground: true }}
                            accessibilityLabel="Close"
                        >
                            <X size={18} color={colors.text} />
                        </Pressable>
                    </View>
                }
                footer={null}
            >
                <View style={styles.searchRow}>
                    <Search size={16} color={colors.textMuted} />
                    <TextInput
                        style={styles.searchInput}
                        placeholder="Search by character/support or event name..."
                        placeholderTextColor={colors.textMuted}
                        value={eventOverrideSearchQuery}
                        onChangeText={setEventOverrideSearchQuery}
                    />
                    {eventOverrideSearchQuery.length > 0 ? (
                        <Pressable
                            style={styles.searchClear}
                            onPress={() => setEventOverrideSearchQuery("")}
                            android_ripple={{ color: colors.ripple, foreground: true }}
                            accessibilityLabel="Clear search"
                        >
                            <X size={14} color={colors.textMuted} />
                        </Pressable>
                    ) : null}
                </View>

                <View style={{ flex: 1 }}>
                    <FlashList
                        data={filteredEvents}
                        renderItem={renderEventItem}
                        keyExtractor={keyExtractor}
                        ListEmptyComponent={
                            <Text style={styles.noResults}>
                                {allEvents.length === 0
                                    ? "No events available. Please select characters and/or support cards in the sections below to see their events."
                                    : filteredEvents.length === 0 &&
                                        (Object.keys(characterEventOverrides || {}).length > 0 ||
                                            Object.keys(supportEventOverrides || {}).length > 0 ||
                                            Object.keys(scenarioEventOverrides || {}).length > 0)
                                      ? "All available events have been overridden. Remove an override to add it again."
                                      : "No events match your search. Try a different search term."}
                            </Text>
                        }
                    />
                </View>
            </SheetModal>

            {/* Option Selection Modal */}
            <SheetModal
                visible={optionSelectionModalVisible}
                onRequestClose={() => {
                    setOptionSelectionModalVisible(false)
                    setEventOverrideModalVisible(true)
                }}
                header={
                    <View style={modalShellStyles.modalHeaderRow}>
                        <Text style={modalShellStyles.modalTitleMono}>SELECT OPTION</Text>
                        <Pressable
                            style={modalShellStyles.modalCloseChip}
                            onPress={() => {
                                setOptionSelectionModalVisible(false)
                                setEventOverrideModalVisible(true)
                            }}
                            android_ripple={{ color: colors.ripple, foreground: true }}
                            accessibilityLabel="Close"
                        >
                            <X size={18} color={colors.text} />
                        </Pressable>
                    </View>
                }
                footer={null}
            >
                {selectedEventForOption ? (
                    <>
                        <View style={styles.optionHeaderBlock}>
                            <Text style={styles.optionHeaderTag}>{selectedEventForOption.characterOrSupport.toUpperCase()}</Text>
                            <Text style={styles.optionHeaderName}>{selectedEventForOption.eventName}</Text>
                        </View>

                        <View style={modalShellStyles.modalBodyList}>
                            {selectedEventForOption.options.map((option: string, index: number) => {
                                const characterOverrides = characterEventOverrides || {}
                                const supportOverrides = supportEventOverrides || {}
                                const scenarioOverrides = scenarioEventOverrides || {}
                                const currentOverride =
                                    selectedEventForOption.type === "character"
                                        ? characterOverrides[selectedEventForOption.key]
                                        : selectedEventForOption.type === "support"
                                          ? supportOverrides[selectedEventForOption.key]
                                          : scenarioOverrides[selectedEventForOption.key]
                                const isOptionSelected = currentOverride === index
                                return (
                                    <ModalRadioRow
                                        key={index}
                                        tag={`OPTION ${index + 1}`}
                                        label={option}
                                        selected={isOptionSelected}
                                        onPress={() => updateEventOverride(selectedEventForOption.key, index)}
                                    />
                                )
                            })}
                        </View>
                    </>
                ) : null}
            </SheetModal>
        </View>
    )
}

export default TrainingEventSettings
