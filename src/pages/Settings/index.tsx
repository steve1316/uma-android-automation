import { useMemo, useContext, useEffect, useState, useRef } from "react"
import { SearchPageProvider } from "../../context/SearchPageContext"
import { BotMetaContext, GeneralMiscContext } from "../../context/BotStateContext"
import { InteractionManager, Pressable, ScrollView, StyleSheet, Text, View } from "react-native"
import { Snackbar } from "react-native-paper"
import { useNavigation } from "@react-navigation/native"
import { Ionicons } from "@react-native-vector-icons/ionicons"
import ThemeToggle from "../../components/ThemeToggle"
import { useTheme } from "../../context/ThemeContext"
import CustomSlider from "../../components/CustomSlider"
import PageHeader from "../../components/PageHeader"
import { Row } from "../../components/ui/row"
import { Switch } from "../../components/ui/switch"
import { Section } from "../../components/ui/section"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "../../components/ui/alert-dialog"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import { useSettings } from "../../context/SettingsContext"
import { useSettingsFileManager } from "../../hooks/useSettingsFileManager"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

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
    const { colors } = useTheme()
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

    // Shared chevron icon used as the right-aligned affordance on every navigation Row.
    const chevron = <Ionicons name="chevron-forward" size={20} color={colors.textMuted} />

    const renderNavigationSections = () => {
        return (
            <>
                <Section label="GAMEPLAY">
                    <Row title="Training" description="Stat priorities, training behavior, and customization." right={chevron} onPress={() => navigation.navigate("TrainingSettings" as never)} />
                    <Row title="Training Events" description="Training event preferences and event selection." right={chevron} onPress={() => navigation.navigate("TrainingEventSettings" as never)} />
                    <Row title="Racing" description="Racing behavior, retries, and mandatory race handling." right={chevron} onPress={() => navigation.navigate("RacingSettings" as never)} />
                    <Row title="Schedule" description="Race solver, recreation, and the unified career calendar." right={chevron} onPress={() => navigation.navigate("ScheduleScreen" as never)} />
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

    const renderMiscSettings = () => {
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

            <Snackbar visible={snackbarMessage !== null} onDismiss={() => setSnackbarMessage(null)} style={{ backgroundColor: colors.surfaceRaised, borderRadius: 10 }}>
                {snackbarMessage ?? ""}
            </Snackbar>
        </View>
    )
}

export default Settings
