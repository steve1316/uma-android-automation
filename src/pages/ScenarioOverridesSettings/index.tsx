import { useMemo, useContext, useRef, useState, useCallback, useEffect } from "react"
import { View, Text, ScrollView, StyleSheet, Image, Pressable, InteractionManager } from "react-native"
import { useRoute } from "@react-navigation/native"
import Ionicons from "@react-native-vector-icons/ionicons"
import { useTheme } from "../../context/ThemeContext"
import { ScenarioOverridesContext, BotMetaContext, Settings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import CustomSlider from "../../components/CustomSlider"
import CustomButton from "../../components/CustomButton"
import PageHeader from "../../components/PageHeader"
import { Input } from "../../components/ui/input"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import ToggleSetting from "../../components/ToggleSetting"
import { CircleCheckBig, Trash2 } from "lucide-react-native"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import trackblazerIcons from "./icons"
import { Section } from "../../components/ui/section"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "../../components/ui/alert-dialog"
import CampaignCard from "../../components/CampaignCard"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** Scenarios that currently have a dedicated set of overrides on this page. Only these appear in the campaign picker, since picking any other scenario would render nothing. */
const SCENARIOS_WITH_OVERRIDES = ["Trackblazer", "Unity Cup"] as const

/** Props for `ChipMultiSelect`. */
interface ChipMultiSelectProps {
    /** Section header shown above the chip grid. */
    title: string
    /** Muted description shown under the header. */
    description: string
    /** All selectable chip labels. */
    options: string[]
    /** Currently-selected labels. */
    selected: string[]
    /** Called with the next selection whenever a chip is toggled. */
    onToggle: (next: string[]) => void
}

/**
 * A labeled multi-select grid of toggle chips backed by a string-array setting. Tapping a chip adds or removes it and reports the next array via `onToggle`.
 * @param props See `ChipMultiSelectProps`.
 * @returns The labeled chip grid.
 */
function ChipMultiSelect({ title, description, options, selected, onToggle }: ChipMultiSelectProps) {
    const { colors } = useTheme()
    return (
        <View style={{ padding: SPACING.md }}>
            <Text style={{ fontSize: 16, color: colors.text, marginBottom: 8 }}>{title}</Text>
            <Text style={{ fontSize: 14, color: colors.text, opacity: 0.7, marginBottom: 12 }}>{description}</Text>
            <View style={{ flexDirection: "row", flexWrap: "wrap", marginHorizontal: 20 }}>
                {options.map((opt) => {
                    const isSelected = selected.includes(opt)
                    return (
                        <Pressable
                            key={opt}
                            style={{ padding: 10, borderRadius: 8, marginRight: 8, marginBottom: 8, overflow: "hidden", backgroundColor: isSelected ? colors.brand : colors.surface }}
                            onPress={() => onToggle(isSelected ? selected.filter((o) => o !== opt) : [...selected, opt])}
                            android_ripple={{ color: isSelected ? colors.rippleInverse : colors.ripple, foreground: true }}
                        >
                            <Text style={{ fontSize: 14, fontWeight: "600", color: isSelected ? colors.onBrand : colors.text }}>{opt}</Text>
                        </Pressable>
                    )
                })}
            </View>
        </View>
    )
}

/**
 * The Scenario Overrides Settings page.
 * Provides configuration for scenario-specific behavior overrides.
 */
const ScenarioOverridesSettings = () => {
    usePerformanceLogging("ScenarioOverridesSettings")
    const { colors } = useTheme()
    const { scenarioOverrides, updateScenarioOverrides } = useContext(ScenarioOverridesContext)
    const { defaultSettings } = useContext(BotMetaContext)
    const route = useRoute<any>()
    const scrollViewRef = useRef<ScrollView>(null)
    const modalShellStyles = useModalShellStyles()

    const [searchQuery, setSearchQuery] = useState("")
    const [showResetAll, setShowResetAll] = useState(false)
    const [scenarioPickerOpen, setScenarioPickerOpen] = useState(false)

    // Which scenario the page is currently editing overrides for. Independent of the bot's active scenario (`general.scenario`) so switching campaigns here does not change the bot's actual run target.
    const [editingCampaign, setEditingCampaign] = useState<string>(SCENARIOS_WITH_OVERRIDES[0])
    const activeCampaign = editingCampaign

    // Two-phase mount, mirroring the TrainingSettings deferral pattern from PR #299. Renders the page header on the first paint
    // so navigation feels instant; the heavy accordion body commits one tick later via InteractionManager. When navigating in
    // from in-app search, skip the deferral so SearchableItem's measureLayout-based scroll-to runs against mounted content.
    const hasTargetId = typeof route.params?.targetId === "string" && (route.params.targetId as string).length > 0
    const [showBody, setShowBody] = useState<boolean>(hasTargetId)
    useEffect(() => {
        if (showBody) return
        const handle = InteractionManager.runAfterInteractions(() => setShowBody(true))
        return () => handle.cancel()
    }, [showBody])

    const filteredItems = useMemo(() => {
        return Object.keys(trackblazerIcons).filter((itemName) => {
            const item = trackblazerIcons[itemName]
            const query = searchQuery.toLowerCase()
            return itemName.toLowerCase().includes(query) || item.description.toLowerCase().includes(query)
        })
    }, [searchQuery])

    /**
     * Update a scenario override setting.
     * @param key The key of the setting to update.
     * @param value The value to set the setting to.
     */
    const updateOverrideSetting = useCallback(
        (key: keyof Settings["scenarioOverrides"], value: any) => {
            updateScenarioOverrides({ [key]: value } as Partial<Settings["scenarioOverrides"]>)
        },
        [updateScenarioOverrides]
    )

    /**
     * Toggle the exclusion status of an item.
     * @param itemName The name of the item to toggle.
     */
    const handleItemPress = useCallback(
        (itemName: string) => {
            const currentExcluded = scenarioOverrides.trackblazerExcludedItems
            if (currentExcluded.includes(itemName)) {
                updateOverrideSetting(
                    "trackblazerExcludedItems",
                    currentExcluded.filter((id) => id !== itemName)
                )
            } else {
                updateOverrideSetting("trackblazerExcludedItems", [...currentExcluded, itemName])
            }
        },
        [scenarioOverrides.trackblazerExcludedItems, updateOverrideSetting]
    )

    /** Open the inline scenario picker so the user can switch the active campaign without leaving this page. */
    const handleSwitch = useCallback(() => {
        setScenarioPickerOpen(true)
    }, [])

    /** Reset Racing section sliders to defaults. */
    const resetRacingDefaults = useCallback(() => {
        updateOverrideSetting("trackblazerConsecutiveRacesLimit", defaultSettings.scenarioOverrides.trackblazerConsecutiveRacesLimit)
        updateOverrideSetting("trackblazerIgnoreLowEnergyRacingBlock", defaultSettings.scenarioOverrides.trackblazerIgnoreLowEnergyRacingBlock)
        updateOverrideSetting("trackblazerMaxRetriesPerRace", defaultSettings.scenarioOverrides.trackblazerMaxRetriesPerRace)
        updateOverrideSetting("trackblazerRetryRacesBeforeFinalGrades", defaultSettings.scenarioOverrides.trackblazerRetryRacesBeforeFinalGrades)
        updateOverrideSetting("trackblazerPreferredDistances", defaultSettings.scenarioOverrides.trackblazerPreferredDistances)
        updateOverrideSetting("trackblazerPreferredSurfaces", defaultSettings.scenarioOverrides.trackblazerPreferredSurfaces)
    }, [updateOverrideSetting, defaultSettings])

    /** Reset Energy & Resources section sliders to defaults. */
    const resetEnergyDefaults = useCallback(() => {
        updateOverrideSetting("trackblazerEnergyThreshold", defaultSettings.scenarioOverrides.trackblazerEnergyThreshold)
        updateOverrideSetting("trackblazerForceTrainEnergyFloor", defaultSettings.scenarioOverrides.trackblazerForceTrainEnergyFloor)
        updateOverrideSetting("trackblazerSkipBadMoodItemsBelowGain", defaultSettings.scenarioOverrides.trackblazerSkipBadMoodItemsBelowGain)
        updateOverrideSetting("trackblazerSkipEmpoweringMegaphoneBelowGain", defaultSettings.scenarioOverrides.trackblazerSkipEmpoweringMegaphoneBelowGain)
        updateOverrideSetting("trackblazerSkipMotivatingMegaphoneBelowGain", defaultSettings.scenarioOverrides.trackblazerSkipMotivatingMegaphoneBelowGain)
        updateOverrideSetting("trackblazerSkipCoachingMegaphoneBelowGain", defaultSettings.scenarioOverrides.trackblazerSkipCoachingMegaphoneBelowGain)
    }, [updateOverrideSetting, defaultSettings])

    /** Reset Training section sliders to defaults. */
    const resetTrainingDefaults = useCallback(() => {
        updateOverrideSetting("trackblazerSkipRiskyCharmTrainingBelowGain", defaultSettings.scenarioOverrides.trackblazerSkipRiskyCharmTrainingBelowGain)
        updateOverrideSetting("trackblazerIrregularTrainingMinStatGain", defaultSettings.scenarioOverrides.trackblazerIrregularTrainingMinStatGain)
        updateOverrideSetting("trackblazerEnableIrregularTraining", defaultSettings.scenarioOverrides.trackblazerEnableIrregularTraining)
        updateOverrideSetting("trackblazerWhistleForcesTraining", defaultSettings.scenarioOverrides.trackblazerWhistleForcesTraining)
    }, [updateOverrideSetting, defaultSettings])

    /** Reset Shop & Items section sliders to defaults. */
    const resetShopDefaults = useCallback(() => {
        updateOverrideSetting("trackblazerShopCheckFrequency", defaultSettings.scenarioOverrides.trackblazerShopCheckFrequency)
        updateOverrideSetting("trackblazerShopCheckGrades", defaultSettings.scenarioOverrides.trackblazerShopCheckGrades)
        updateOverrideSetting("trackblazerExcludedItems", defaultSettings.scenarioOverrides.trackblazerExcludedItems)
    }, [updateOverrideSetting, defaultSettings])

    /** Reset Item Conservation section sliders to defaults. */
    const resetConservationDefaults = useCallback(() => {
        updateOverrideSetting("trackblazerEnergyItemReserve", defaultSettings.scenarioOverrides.trackblazerEnergyItemReserve)
        updateOverrideSetting("trackblazerCupcakeReserve", defaultSettings.scenarioOverrides.trackblazerCupcakeReserve)
        updateOverrideSetting("trackblazerMasterHammerFinaleReserve", defaultSettings.scenarioOverrides.trackblazerMasterHammerFinaleReserve)
        updateOverrideSetting("trackblazerArtisanHammerMinStockForG3", defaultSettings.scenarioOverrides.trackblazerArtisanHammerMinStockForG3)
        updateOverrideSetting("trackblazerArtisanHammerMinStockForG2", defaultSettings.scenarioOverrides.trackblazerArtisanHammerMinStockForG2)
        updateOverrideSetting("trackblazerGlowStickFinalReserve", defaultSettings.scenarioOverrides.trackblazerGlowStickFinalReserve)
        updateOverrideSetting("trackblazerGlowStickMinFans", defaultSettings.scenarioOverrides.trackblazerGlowStickMinFans)
    }, [updateOverrideSetting, defaultSettings])

    /** Reset the Unity Cup Training section to defaults. */
    const resetUnityCupDefaults = useCallback(() => {
        updateOverrideSetting("unityCupBurstMaxFailureChance", defaultSettings.scenarioOverrides.unityCupBurstMaxFailureChance)
    }, [updateOverrideSetting, defaultSettings])

    /** Reset the currently-edited scenario's overrides to defaults. */
    const resetAllDefaults = useCallback(() => {
        if (activeCampaign === "Unity Cup") {
            resetUnityCupDefaults()
        } else {
            resetRacingDefaults()
            resetEnergyDefaults()
            resetTrainingDefaults()
            resetShopDefaults()
            resetConservationDefaults()
        }
        setShowResetAll(false)
    }, [activeCampaign, resetRacingDefaults, resetEnergyDefaults, resetTrainingDefaults, resetShopDefaults, resetConservationDefaults, resetUnityCupDefaults])

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
                accordionDescription: {
                    fontSize: 14,
                    color: colors.text,
                    opacity: 0.7,
                    marginBottom: 12,
                },
                itemContainer: {
                    backgroundColor: colors.surface,
                    padding: 12,
                    borderRadius: 8,
                    marginBottom: 8,
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "center",
                },
                conservationSectionIntro: {
                    fontSize: 13,
                    color: colors.text,
                    opacity: 0.7,
                },
                resetLink: { ...TYPE.caption, color: colors.brand, fontWeight: "600" as const },
            }),
        [colors]
    )

    /** Shared "Reset" pressable used in each section label's right slot. */
    const makeResetLink = (onPress: () => void) => (
        <Pressable onPress={onPress} android_ripple={{ color: colors.ripple, foreground: true }} hitSlop={8}>
            <Text style={styles.resetLink}>Reset</Text>
        </Pressable>
    )

    return (
        <View style={styles.root}>
            <SearchPageProvider page="ScenarioOverridesSettings" scrollViewRef={scrollViewRef}>
                <PageHeader title="Scenario Overrides Settings" />
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <View style={{ marginBottom: SPACING.lg }}>
                            <CampaignCard campaign={activeCampaign} onSwitch={SCENARIOS_WITH_OVERRIDES.length > 1 ? handleSwitch : undefined} />
                        </View>

                        {showBody && (
                            <>
                                {activeCampaign === "Trackblazer" && (
                                    <>
                                        {/* Racing */}
                                        <Section label="Racing" collapsible labelRight={makeResetLink(resetRacingDefaults)}>
                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-consecutive-races-limit"
                                                    value={scenarioOverrides.trackblazerConsecutiveRacesLimit}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerConsecutiveRacesLimit}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerConsecutiveRacesLimit", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerConsecutiveRacesLimit", value)}
                                                    min={3}
                                                    max={30}
                                                    step={1}
                                                    label="Consecutive Races Limit"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Sets the maximum number of consecutive races the bot is allowed to run in the Trackblazer scenario before stopping. Note that a -30 stat penalty can apply starting from 3 consecutive races."
                                                />
                                            </View>

                                            <ToggleSetting id="trackblazer-ignore-low-energy-racing-block" title="Ignore Low Energy Racing Block" description="When enabled, the Trackblazer bot will not block racing when energy is critically low (<=1%) with 3+ consecutive races." checked={scenarioOverrides.trackblazerIgnoreLowEnergyRacingBlock} onCheckedChange={(checked) => updateOverrideSetting("trackblazerIgnoreLowEnergyRacingBlock", checked)} />

                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-max-retries-per-race"
                                                    value={scenarioOverrides.trackblazerMaxRetriesPerRace}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerMaxRetriesPerRace}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerMaxRetriesPerRace", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerMaxRetriesPerRace", value)}
                                                    min={0}
                                                    max={5}
                                                    step={1}
                                                    label="Max Retries per Race"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="The maximum number of times the bot will attempt to retry a failed race in the Trackblazer scenario."
                                                />
                                            </View>

                                            <ChipMultiSelect
                                                title="Race Grades to use Race Retries on"
                                                description="Select which race grades should allow using a Race Retry in the Trackblazer scenario."
                                                options={["G1", "G2", "G3"]}
                                                selected={scenarioOverrides.trackblazerRetryRacesBeforeFinalGrades}
                                                onToggle={(next) => updateOverrideSetting("trackblazerRetryRacesBeforeFinalGrades", next)}
                                            />

                                            <ChipMultiSelect
                                                title="Preferred Track Distances"
                                                description="Select preferred track distances for extra race selection. Matching races will be prioritized. Leave empty for no preference."
                                                options={["Sprint", "Mile", "Medium", "Long"]}
                                                selected={scenarioOverrides.trackblazerPreferredDistances}
                                                onToggle={(next) => updateOverrideSetting("trackblazerPreferredDistances", next)}
                                            />

                                            <ChipMultiSelect
                                                title="Preferred Track Surfaces"
                                                description="Select preferred track surfaces for extra race selection. Matching races will be prioritized. Leave empty for no preference."
                                                options={["Turf", "Dirt"]}
                                                selected={scenarioOverrides.trackblazerPreferredSurfaces}
                                                onToggle={(next) => updateOverrideSetting("trackblazerPreferredSurfaces", next)}
                                            />
                                        </Section>

                                        {/* Energy & Resources */}
                                        <Section label="Energy & Resources" collapsible defaultOpen={false} labelRight={makeResetLink(resetEnergyDefaults)}>
                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-energy-threshold"
                                                    value={scenarioOverrides.trackblazerEnergyThreshold}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerEnergyThreshold}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerEnergyThreshold", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerEnergyThreshold", value)}
                                                    min={0}
                                                    max={100}
                                                    step={5}
                                                    label="Energy Threshold to use Energy Items"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="The energy level below which the bot will attempt to use energy-restoring items in the Trackblazer scenario."
                                                />
                                            </View>

                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-force-train-energy-floor"
                                                    value={scenarioOverrides.trackblazerForceTrainEnergyFloor}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerForceTrainEnergyFloor}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerForceTrainEnergyFloor", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerForceTrainEnergyFloor", value)}
                                                    min={0}
                                                    max={50}
                                                    step={5}
                                                    label="Force-Train Energy Floor (Summer/Finale)"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="During Summer and the Finale, the Trackblazer scenario normally always picks training. If energy is at or below this floor, the bot skips that override and falls through to the standard decision flow so items are not queued for a training with little hope for success."
                                                />
                                            </View>

                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-skip-bad-mood-items-below-gain"
                                                    value={scenarioOverrides.trackblazerSkipBadMoodItemsBelowGain}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerSkipBadMoodItemsBelowGain}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerSkipBadMoodItemsBelowGain", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerSkipBadMoodItemsBelowGain", value)}
                                                    min={0}
                                                    max={50}
                                                    step={1}
                                                    label="Skip Items During Bad Mood Below Main Stat Gain"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="When mood is BAD or AWFUL, refuse to use Reset Whistle / Good-Luck Charm / Megaphone if the selected training's main stat gain is below this floor. Prevents wasting items on structurally low-return turns where the mood multiplier caps the stat gains."
                                                />
                                            </View>

                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-skip-empowering-megaphone-below-gain"
                                                    value={scenarioOverrides.trackblazerSkipEmpoweringMegaphoneBelowGain}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerSkipEmpoweringMegaphoneBelowGain}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerSkipEmpoweringMegaphoneBelowGain", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerSkipEmpoweringMegaphoneBelowGain", value)}
                                                    min={0}
                                                    max={100}
                                                    step={1}
                                                    label="Skip Empowering Megaphone Below Main Stat Gain"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Skip the Empowering Megaphone (+60% for 2 turns) when the selected training's main stat gain is below this value, falling through to a lower tier whose threshold is met. 0 = always allowed."
                                                />
                                            </View>

                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-skip-motivating-megaphone-below-gain"
                                                    value={scenarioOverrides.trackblazerSkipMotivatingMegaphoneBelowGain}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerSkipMotivatingMegaphoneBelowGain}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerSkipMotivatingMegaphoneBelowGain", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerSkipMotivatingMegaphoneBelowGain", value)}
                                                    min={0}
                                                    max={100}
                                                    step={1}
                                                    label="Skip Motivating Megaphone Below Main Stat Gain"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Skip the Motivating Megaphone (+40% for 3 turns) when the selected training's main stat gain is below this value, falling through to a lower tier whose threshold is met. 0 = always allowed."
                                                />
                                            </View>

                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-skip-coaching-megaphone-below-gain"
                                                    value={scenarioOverrides.trackblazerSkipCoachingMegaphoneBelowGain}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerSkipCoachingMegaphoneBelowGain}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerSkipCoachingMegaphoneBelowGain", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerSkipCoachingMegaphoneBelowGain", value)}
                                                    min={0}
                                                    max={100}
                                                    step={1}
                                                    label="Skip Coaching Megaphone Below Main Stat Gain"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Skip the Coaching Megaphone (+20% for 4 turns) when the selected training's main stat gain is below this value. 0 = always allowed."
                                                />
                                            </View>
                                        </Section>

                                        {/* Training */}
                                        <Section label="Training" collapsible defaultOpen={false} labelRight={makeResetLink(resetTrainingDefaults)}>
                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-skip-risky-charm-training-below-gain"
                                                    value={scenarioOverrides.trackblazerSkipRiskyCharmTrainingBelowGain}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerSkipRiskyCharmTrainingBelowGain}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerSkipRiskyCharmTrainingBelowGain", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerSkipRiskyCharmTrainingBelowGain", value)}
                                                    min={20}
                                                    max={100}
                                                    step={5}
                                                    label="Skip Risky Charm Training Below Main Stat Gain"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="When a Good-Luck Charm is available to override a risky training's failure chance, skip that training anyway if its main stat gain is below this value. Prevents committing the Charm to low-value risky picks."
                                                />
                                            </View>

                                            <ToggleSetting
                                                id="trackblazer-enable-irregular-training"
                                                title="Enable Irregular Training"
                                                description="When enabled, the bot will occasionally check for highly profitable training sessions before opting for extra races."
                                                checked={scenarioOverrides.trackblazerEnableIrregularTraining}
                                                onCheckedChange={(checked) => updateOverrideSetting("trackblazerEnableIrregularTraining", checked)}
                                            />

                                            {scenarioOverrides.trackblazerEnableIrregularTraining && (
                                                <View style={{ padding: SPACING.md }}>
                                                    <CustomSlider
                                                        searchId="trackblazer-irregular-training-min-stat-gain"
                                                        searchCondition={scenarioOverrides.trackblazerEnableIrregularTraining}
                                                        parentId="trackblazer-enable-irregular-training"
                                                        value={scenarioOverrides.trackblazerIrregularTrainingMinStatGain}
                                                        placeholder={defaultSettings.scenarioOverrides.trackblazerIrregularTrainingMinStatGain}
                                                        onValueChange={(value) => updateOverrideSetting("trackblazerIrregularTrainingMinStatGain", value)}
                                                        onSlidingComplete={(value) => updateOverrideSetting("trackblazerIrregularTrainingMinStatGain", value)}
                                                        min={20}
                                                        max={100}
                                                        step={5}
                                                        label="Minimum Main Stat Gain for Irregular Training"
                                                        labelUnit=""
                                                        showValue={true}
                                                        showLabels={true}
                                                        description="Sets the minimum main stat gain required to skip racing and perform Irregular Training instead."
                                                    />
                                                </View>
                                            )}

                                            <ToggleSetting
                                                id="trackblazer-whistle-forces-training"
                                                title="Reset Whistle Forces Training"
                                                description="Whether or not using a Reset Whistle means it can ignore the failure chance thresholds in the Training Settings page. If enabled, the bot will pick the best available training after usage even if it's risky."
                                                checked={scenarioOverrides.trackblazerWhistleForcesTraining}
                                                onCheckedChange={(checked) => updateOverrideSetting("trackblazerWhistleForcesTraining", checked)}
                                            />
                                        </Section>

                                        {/* Shop & Items */}
                                        <Section label="Shop & Items" collapsible defaultOpen={false} labelRight={makeResetLink(resetShopDefaults)}>
                                            <View style={{ padding: SPACING.md }}>
                                                <CustomSlider
                                                    searchId="trackblazer-shop-check-frequency"
                                                    value={scenarioOverrides.trackblazerShopCheckFrequency}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerShopCheckFrequency}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerShopCheckFrequency", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerShopCheckFrequency", value)}
                                                    min={1}
                                                    max={4}
                                                    step={1}
                                                    label="Shop Check Frequency"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Sets the frequency of shop checks after races in the Trackblazer scenario. 1 = every race, 2 = 1 day after, 3 = 2 days after, etc."
                                                />
                                            </View>

                                            <ChipMultiSelect
                                                title="Race Grades to check Shop Afterwards"
                                                description="Select which race grades should trigger a shop check after the race in the Trackblazer scenario."
                                                options={["G1", "G2", "G3"]}
                                                selected={scenarioOverrides.trackblazerShopCheckGrades}
                                                onToggle={(next) => updateOverrideSetting("trackblazerShopCheckGrades", next)}
                                            />

                                            <View style={{ padding: SPACING.md }}>
                                                <View style={{ flexDirection: "row", alignItems: "center", marginBottom: 12, gap: 12 }}>
                                                    <View style={{ flex: 1 }}>
                                                        <Text style={{ fontSize: 16, color: colors.text }}>Items to Exclude from Shop</Text>
                                                        <Text style={{ fontSize: 14, color: colors.text, opacity: 0.7, marginTop: 4 }}>
                                                            Selected <Text style={[TYPE.monoValue, { color: colors.text }]}>{scenarioOverrides.trackblazerExcludedItems.length}</Text> /{" "}
                                                            <Text style={[TYPE.monoValue, { color: colors.text }]}>{Object.keys(trackblazerIcons).length}</Text> items
                                                        </Text>
                                                    </View>
                                                    <View style={{ flexDirection: "row", gap: 8 }}>
                                                        <CustomButton icon={<Trash2 size={16} color={colors.text} />} onPress={() => updateOverrideSetting("trackblazerExcludedItems", [])}>
                                                            Clear
                                                        </CustomButton>
                                                    </View>
                                                </View>

                                                <Text style={{ fontSize: 14, color: colors.text, opacity: 0.7, marginBottom: 12 }}>
                                                    Select items that the bot will never purchase from the shop in the Trackblazer scenario.
                                                </Text>

                                                <View style={{ marginBottom: 16 }}>
                                                    <Input
                                                        style={{
                                                            borderWidth: 1,
                                                            borderColor: colors.borderHair,
                                                            borderRadius: 8,
                                                            padding: 12,
                                                            fontSize: 16,
                                                            color: colors.text,
                                                            backgroundColor: colors.bg,
                                                            marginBottom: 12,
                                                        }}
                                                        value={searchQuery}
                                                        onChangeText={setSearchQuery}
                                                        placeholder="Search items by name..."
                                                    />
                                                    <View style={{ height: 400 }}>
                                                        <ScrollView nestedScrollEnabled={true} showsVerticalScrollIndicator={true}>
                                                            {filteredItems.map((itemName) => (
                                                                <Pressable
                                                                    key={itemName}
                                                                    onPress={() => handleItemPress(itemName)}
                                                                    style={styles.itemContainer}
                                                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                                                >
                                                                    <View style={{ flexDirection: "row", alignItems: "center", gap: 8, flex: 1 }}>
                                                                        <Image source={trackblazerIcons[itemName].icon} style={{ width: 48, height: 48, marginRight: 8 }} />
                                                                        <View style={{ flex: 1 }}>
                                                                            <Text style={{ fontSize: 16, fontWeight: "600", color: colors.text }}>{itemName}</Text>
                                                                            <Text style={{ fontSize: 12, color: colors.text, opacity: 0.6, marginTop: 2 }}>
                                                                                {trackblazerIcons[itemName].description}
                                                                            </Text>
                                                                        </View>
                                                                        {scenarioOverrides.trackblazerExcludedItems.includes(itemName) && <CircleCheckBig size={18} color={"green"} />}
                                                                    </View>
                                                                </Pressable>
                                                            ))}
                                                        </ScrollView>
                                                    </View>
                                                </View>
                                            </View>
                                        </Section>

                                        {/* Item Conservation */}
                                        <Section label="Item Conservation" collapsible defaultOpen={false} labelRight={makeResetLink(resetConservationDefaults)}>
                                            <View style={{ padding: SPACING.md }}>
                                                <Text style={styles.conservationSectionIntro}>
                                                    Controls how aggressively the bot saves items for high-value turns. Set any threshold to 0 to disable that conservation rule and use items freely.
                                                </Text>
                                            </View>

                                            <View style={{ padding: SPACING.md }}>
                                                <Text style={[TYPE.monoLabel, { color: colors.textMuted, marginBottom: SPACING.sm }]}>ENERGY</Text>
                                                <CustomSlider
                                                    searchId="trackblazer-energy-item-reserve"
                                                    value={scenarioOverrides.trackblazerEnergyItemReserve}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerEnergyItemReserve}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerEnergyItemReserve", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerEnergyItemReserve", value)}
                                                    min={0}
                                                    max={3}
                                                    step={1}
                                                    label="Energy Item Emergency Reserve"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Number of energy items (lowest-tier first) to keep reserved for emergency race recovery when energy hits 1% or below with 3+ consecutive races."
                                                />
                                            </View>

                                            <View style={{ padding: SPACING.md }}>
                                                <Text style={[TYPE.monoLabel, { color: colors.textMuted, marginBottom: SPACING.sm }]}>MOOD</Text>
                                                <CustomSlider
                                                    searchId="trackblazer-cupcake-reserve"
                                                    value={scenarioOverrides.trackblazerCupcakeReserve}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerCupcakeReserve}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerCupcakeReserve", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerCupcakeReserve", value)}
                                                    min={0}
                                                    max={3}
                                                    step={1}
                                                    label="Cupcake Reserve for Kale Juice Synergy"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Number of cupcakes (Plain preferred) to keep so the mood penalty from Royal Kale Juice can be offset."
                                                />
                                            </View>

                                            <View style={{ padding: SPACING.md, gap: SPACING.md }}>
                                                <Text style={[TYPE.monoLabel, { color: colors.textMuted }]}>RACE ITEMS</Text>
                                                <Text style={styles.conservationSectionIntro}>
                                                    Reserves and stock floors below take effect starting Turn 65 (right after Senior Year Summer training). Before Turn 65, the bot uses Hammers freely
                                                    on every race it takes. The Glow Stick Min Fans floor is the only race-item threshold that applies before Turn 65.
                                                </Text>

                                                <CustomSlider
                                                    searchId="trackblazer-master-hammer-finale-reserve"
                                                    value={scenarioOverrides.trackblazerMasterHammerFinaleReserve}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerMasterHammerFinaleReserve}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerMasterHammerFinaleReserve", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerMasterHammerFinaleReserve", value)}
                                                    min={0}
                                                    max={3}
                                                    step={1}
                                                    label="Master Cleat Hammer Finale Reserve"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Master Cleat Hammers held back for the Finale days (73-75). Pre-finale days only spend the surplus above this reserve, and only on G1/G2 races."
                                                />

                                                <CustomSlider
                                                    searchId="trackblazer-artisan-hammer-min-stock-for-g3"
                                                    value={scenarioOverrides.trackblazerArtisanHammerMinStockForG3}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerArtisanHammerMinStockForG3}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerArtisanHammerMinStockForG3", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerArtisanHammerMinStockForG3", value)}
                                                    min={0}
                                                    max={3}
                                                    step={1}
                                                    label="Artisan Hammer Min Stock for G3"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Minimum Artisan Cleat Hammer inventory before the bot is allowed to spend one on a G3 race."
                                                />

                                                <CustomSlider
                                                    searchId="trackblazer-artisan-hammer-min-stock-for-g2"
                                                    value={scenarioOverrides.trackblazerArtisanHammerMinStockForG2}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerArtisanHammerMinStockForG2}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerArtisanHammerMinStockForG2", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerArtisanHammerMinStockForG2", value)}
                                                    min={0}
                                                    max={3}
                                                    step={1}
                                                    label="Artisan Hammer Min Stock for G2"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Minimum Artisan Cleat Hammer inventory before the bot is allowed to spend one on a G2 race. G1 is always allowed."
                                                />

                                                <CustomSlider
                                                    searchId="trackblazer-glow-stick-final-reserve"
                                                    value={scenarioOverrides.trackblazerGlowStickFinalReserve}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerGlowStickFinalReserve}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerGlowStickFinalReserve", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerGlowStickFinalReserve", value)}
                                                    min={0}
                                                    max={3}
                                                    step={1}
                                                    label="Glow Stick Final-Day Reserve"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Glow Sticks held back for Day 75 (the Final). Pre-final-day races only spend sticks above this reserve."
                                                />

                                                <CustomSlider
                                                    searchId="trackblazer-glow-stick-min-fans"
                                                    value={scenarioOverrides.trackblazerGlowStickMinFans}
                                                    placeholder={defaultSettings.scenarioOverrides.trackblazerGlowStickMinFans}
                                                    onValueChange={(value) => updateOverrideSetting("trackblazerGlowStickMinFans", value)}
                                                    onSlidingComplete={(value) => updateOverrideSetting("trackblazerGlowStickMinFans", value)}
                                                    min={0}
                                                    max={30000}
                                                    step={1000}
                                                    label="Glow Stick Minimum Fans"
                                                    labelUnit=""
                                                    showValue={true}
                                                    showLabels={true}
                                                    description="Minimum projected fan gain on a race before the bot uses a Glow Stick on it. Applies on standard and finale days."
                                                />
                                            </View>
                                        </Section>
                                    </>
                                )}

                                {activeCampaign === "Unity Cup" && (
                                    <Section label="Training" collapsible labelRight={makeResetLink(resetUnityCupDefaults)}>
                                        <View style={{ padding: SPACING.md }}>
                                            <CustomSlider
                                                searchId="unity-cup-burst-max-failure-chance"
                                                value={scenarioOverrides.unityCupBurstMaxFailureChance}
                                                placeholder={defaultSettings.scenarioOverrides.unityCupBurstMaxFailureChance}
                                                onValueChange={(value) => updateOverrideSetting("unityCupBurstMaxFailureChance", value)}
                                                min={0}
                                                max={100}
                                                step={5}
                                                label="Burst Failure-Chance Exemption"
                                                showValue={true}
                                                showLabels={true}
                                                description="Allow a training with a Spirit Explosion gauge ready to burst up to this failure chance before it is skipped. 0 disables the exemption and uses the normal failure limit."
                                            />
                                        </View>
                                    </Section>
                                )}

                                {/* Reset All footer */}
                                <Pressable
                                    onPress={() => setShowResetAll(true)}
                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                    style={{
                                        padding: SPACING.md,
                                        backgroundColor: "rgba(239, 68, 68, 0.08)",
                                        borderColor: colors.destructive,
                                        borderWidth: 1,
                                        borderRadius: RADII.lg,
                                        alignItems: "center",
                                        marginVertical: SPACING.md,
                                    }}
                                >
                                    <Text style={{ ...TYPE.body, color: colors.destructive, fontWeight: "600" }}>Reset {activeCampaign} to Defaults</Text>
                                </Pressable>
                            </>
                        )}
                    </View>
                </ScrollView>
            </SearchPageProvider>

            <AlertDialog open={showResetAll} onOpenChange={setShowResetAll}>
                <AlertDialogContent onDismiss={() => setShowResetAll(false)}>
                    <AlertDialogHeader>
                        <AlertDialogTitle>Reset {activeCampaign} to Defaults</AlertDialogTitle>
                        <AlertDialogDescription>All {activeCampaign} overrides will be reset to their default values. This cannot be undone.</AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                        <AlertDialogCancel onPress={() => setShowResetAll(false)}>
                            <Text>Cancel</Text>
                        </AlertDialogCancel>
                        <AlertDialogAction onPress={resetAllDefaults}>
                            <Text>Reset</Text>
                        </AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>

            <SheetModal
                visible={scenarioPickerOpen}
                onRequestClose={() => setScenarioPickerOpen(false)}
                header={
                    <View style={modalShellStyles.modalHeaderRow}>
                        <Text style={modalShellStyles.modalTitleMono}>SWITCH CAMPAIGN</Text>
                        <Pressable
                            style={modalShellStyles.modalCloseChip}
                            onPress={() => setScenarioPickerOpen(false)}
                            android_ripple={{ color: colors.ripple, foreground: true }}
                            accessibilityLabel="Close"
                        >
                            <Ionicons name="close" size={18} color={colors.text} />
                        </Pressable>
                    </View>
                }
                footer={null}
            >
                <View style={modalShellStyles.modalBodyList}>
                    {SCENARIOS_WITH_OVERRIDES.map((scenario) => (
                        <ModalRadioRow
                            key={scenario}
                            label={scenario}
                            selected={scenario === editingCampaign}
                            onPress={() => {
                                setEditingCampaign(scenario)
                                setScenarioPickerOpen(false)
                            }}
                        />
                    ))}
                </View>
            </SheetModal>
        </View>
    )
}

export default ScenarioOverridesSettings
