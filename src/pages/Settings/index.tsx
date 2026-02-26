import scenarios from "../../data/scenarios.json"
import { useMemo, useContext, useEffect, useState, useRef } from "react"
import { SearchPageProvider } from "../../context/SearchPageContext"
import { BotStateContext } from "../../context/BotStateContext"
import { ScrollView, StyleSheet, Text, View } from "react-native"
import { Snackbar } from "react-native-paper"
import { useNavigation } from "@react-navigation/native"
import ThemeToggle from "../../components/ThemeToggle"
import { useTheme } from "../../context/ThemeContext"
import CustomSelect from "../../components/CustomSelect"
import NavigationLink from "../../components/NavigationLink"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomSlider from "../../components/CustomSlider"
import CustomTitle from "../../components/CustomTitle"
import CustomButton from "../../components/CustomButton"
import PageHeader from "../../components/PageHeader"
import { Separator } from "../../components/ui/separator"
import WarningContainer from "../../components/WarningContainer"
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "../../components/ui/alert-dialog"
import { useSettings } from "../../context/SettingsContext"
import { useSettingsFileManager } from "../../hooks/useSettingsFileManager"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

const Settings = () => {
    usePerformanceLogging("Settings")
    const [snackbarOpen, setSnackbarOpen] = useState<boolean>(false)
    const scrollViewRef = useRef<ScrollView>(null)

    const bsc = useContext(BotStateContext)
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
                    backgroundColor: colors.background,
                },
            }),
        [colors],
    )

    //////////////////////////////////////////////////
    //////////////////////////////////////////////////
    // Callbacks

    useEffect(() => {
        // Manually set this flag to false as the snackbar autohiding does not set this to false automatically.
        setSnackbarOpen(true)
        setTimeout(() => setSnackbarOpen(false), 2500)
    }, [bsc.readyStatus])

    const handleResetSettings = async () => {
        const success = await resetSettings()
        if (success) {
            setSnackbarOpen(true)
            setTimeout(() => setSnackbarOpen(false), 2500)
        }
    }

    //////////////////////////////////////////////////
    //////////////////////////////////////////////////
    // Rendering

    const renderCampaignPicker = () => {
        return (
            <View>
                <CustomSelect
                    searchId="settings-scenario-picker"
                    label="Scenario"
                    description="Choose a scenario that will dictate the bot's logic and behavior."
                    placeholder="Select a Scenario"
                    width="100%"
                    groupLabel="Scenarios"
                    options={scenarios}
                    value={bsc.settings.general.scenario}
                    onValueChange={(value) => {
                        const newScenario = value || ""
                        bsc.setSettings({ ...bsc.settings, general: { ...bsc.settings.general, scenario: newScenario } })
                        bsc.setReadyStatus(newScenario !== "")
                    }}
                />
                {!bsc.settings.general.scenario && <WarningContainer>⚠️ A scenario must be selected before starting the bot.</WarningContainer>}
            </View>
        )
    }

    const renderTrainingLink = () => {
        return (
            <NavigationLink
                title="Go to Training Settings"
                description="Configure which stats to train, set priorities, and customize training behavior."
                onPress={() => navigation.navigate("TrainingSettings" as never)}
            />
        )
    }

    const renderTrainingEventLink = () => {
        return (
            <NavigationLink
                title="Go to Training Event Settings"
                description="Configure training event preferences and event selection behavior."
                onPress={() => navigation.navigate("TrainingEventSettings" as never)}
            />
        )
    }

    const renderOCRLink = () => {
        return (
            <NavigationLink
                title="Go to OCR Settings"
                description="Configure OCR text detection parameters, threshold settings, and retry behavior."
                onPress={() => navigation.navigate("OCRSettings" as never)}
            />
        )
    }

    const renderRacingLink = () => {
        return (
            <NavigationLink
                title="Go to Racing Settings"
                description="Configure racing behavior, retry settings, mandatory race handling, and more."
                onPress={() => navigation.navigate("RacingSettings" as never)}
            />
        )
    }

    const renderSkillsLink = () => {
        return <NavigationLink title="Go to Skills Settings" description="Configure skill purchasing behavior." onPress={() => navigation.navigate("SkillSettings" as never)} />
    }

    const renderEventLogVisualizerLink = () => {
        return (
            <NavigationLink
                title="Go to Event Log Visualizer (Beta)"
                description="Import logs and view a day-by-day timeline of actions."
                onPress={() => navigation.navigate("EventLogVisualizer" as never)}
            />
        )
    }

    const renderDebugLink = () => {
        return (
            <NavigationLink
                title="Go to Debug Settings"
                description="Configure debug mode, template matching settings, and diagnostic tests for bot troubleshooting."
                onPress={() => navigation.navigate("DebugSettings" as never)}
            />
        )
    }

    const renderMiscSettings = () => {
        return (
            <View style={{ marginTop: 16 }}>
                <Separator style={{ marginVertical: 16 }} />

                <CustomTitle title="Misc Settings" description="General settings for the bot that don't fit into the other categories." />

                <CustomCheckbox
                    searchId="settings-popup-check"
                    checked={bsc.settings.general.enablePopupCheck}
                    onCheckedChange={(checked) => {
                        bsc.setSettings({
                            ...bsc.settings,
                            general: { ...bsc.settings.general, enablePopupCheck: checked },
                        })
                    }}
                    label="Enable Popup Check"
                    description="Enables check for warning popups like lack of fans or lack of trophies gained. Stops the bot if detected for the user to deal with them manually."
                    className="mt-4"
                />

                <CustomCheckbox
                    searchId="settings-stop-before-finals"
                    checked={bsc.settings.general.enableStopBeforeFinals}
                    onCheckedChange={(checked) => {
                        bsc.setSettings({
                            ...bsc.settings,
                            general: { ...bsc.settings.general, enableStopBeforeFinals: checked },
                        })
                    }}
                    label="Stop before Finals"
                    description="Stops the bot on turn 72 so you can purchase skills before the final races."
                    className="mt-4"
                />

                <CustomCheckbox
                    searchId="settings-crane-game-attempt"
                    checked={bsc.settings.general.enableCraneGameAttempt}
                    onCheckedChange={(checked) => {
                        bsc.setSettings({
                            ...bsc.settings,
                            general: { ...bsc.settings.general, enableCraneGameAttempt: checked },
                        })
                    }}
                    label="Enable Crane Game Attempt"
                    description="When enabled, the bot will attempt to complete the crane game. By default, the bot will stop when it is detected."
                    className="mt-4"
                />

                <CustomCheckbox
                    searchId="settings-enable-settings-display"
                    checked={bsc.settings.misc.enableSettingsDisplay}
                    onCheckedChange={(checked) => {
                        bsc.setSettings({
                            ...bsc.settings,
                            misc: { ...bsc.settings.misc, enableSettingsDisplay: checked },
                        })
                    }}
                    label="Enable Settings Display in Message Log"
                    description="Shows current bot configuration settings at the top of the message log."
                    className="mt-4"
                />

                <CustomCheckbox
                    searchId="settings-enable-message-id-display"
                    checked={bsc.settings.misc.enableMessageIdDisplay}
                    onCheckedChange={(checked) => {
                        bsc.setSettings({
                            ...bsc.settings,
                            misc: { ...bsc.settings.misc, enableMessageIdDisplay: checked },
                        })
                    }}
                    label="Enable Message ID Display"
                    description="Shows message IDs in the message log to help with debugging."
                    className="mt-4"
                />

                <CustomSlider
                    searchId="settings-wait-delay"
                    value={bsc.settings.general.waitDelay}
                    placeholder={bsc.defaultSettings.general.waitDelay}
                    onValueChange={(value) => {
                        bsc.setSettings({ ...bsc.settings, general: { ...bsc.settings.general, waitDelay: value } })
                    }}
                    onSlidingComplete={(value) => {
                        bsc.setSettings({ ...bsc.settings, general: { ...bsc.settings.general, waitDelay: value } })
                    }}
                    min={0.0}
                    max={1.0}
                    step={0.1}
                    label="Wait Delay"
                    labelUnit="s"
                    showValue={true}
                    showLabels={true}
                    description="Sets the delay between actions and imaging operations. Lowering this will make the bot run much faster."
                />

                <CustomSlider
                    searchId="settings-overlay-button-size"
                    value={bsc.settings.misc.overlayButtonSizeDP}
                    placeholder={bsc.defaultSettings.misc.overlayButtonSizeDP}
                    onValueChange={(value) => {
                        bsc.setSettings({ ...bsc.settings, misc: { ...bsc.settings.misc, overlayButtonSizeDP: value } })
                    }}
                    onSlidingComplete={(value) => {
                        bsc.setSettings({ ...bsc.settings, misc: { ...bsc.settings.misc, overlayButtonSizeDP: value } })
                    }}
                    min={30}
                    max={60}
                    step={5}
                    label="Overlay Button Size"
                    labelUnit=" dp"
                    showValue={true}
                    showLabels={true}
                    description="Sets the size of the floating overlay button in density-independent pixels (dp). Higher values make the button easier to tap."
                />

                <Separator style={{ marginVertical: 16 }} />

                <CustomTitle searchId="settings-management-title" title="Settings Management" description="Import and export settings from JSON file or access the app's data directory." />

                <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
                    <CustomButton onPress={handleImportSettings} variant="default" style={{ width: 150 }}>
                        📥 Import Settings
                    </CustomButton>

                    <CustomButton onPress={handleExportSettings} variant="default" style={{ width: 150 }}>
                        📤 Export Settings
                    </CustomButton>
                </View>

                <View style={{ flexDirection: "row", marginTop: 16, justifyContent: "space-between" }}>
                    <CustomButton onPress={openDataDirectory} variant="default" style={{ width: 150 }} fontSize={12}>
                        📁 Open Data Directory
                    </CustomButton>

                    <CustomButton onPress={() => setShowResetDialog(true)} variant="destructive" style={{ width: 150 }}>
                        🔄 Reset Settings
                    </CustomButton>
                </View>

                <WarningContainer style={{ marginBottom: 12 }}>
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
            <PageHeader title="Settings" rightComponent={<ThemeToggle />} />

            <SearchPageProvider page="SettingsMain" scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        {renderCampaignPicker()}
                        {renderTrainingLink()}
                        {renderTrainingEventLink()}
                        {renderOCRLink()}
                        {renderRacingLink()}
                        {renderSkillsLink()}
                        {renderEventLogVisualizerLink()}
                        {renderDebugLink()}
                        {renderMiscSettings()}
                    </View>
                </ScrollView>
            </SearchPageProvider>

            <Snackbar
                visible={snackbarOpen}
                onDismiss={() => setSnackbarOpen(false)}
                action={{
                    label: "Close",
                    onPress: () => {
                        setSnackbarOpen(false)
                    },
                }}
                style={{ backgroundColor: bsc.readyStatus ? "green" : "red", borderRadius: 10 }}
            >
                {bsc.readyStatus ? "Bot is ready!" : "Bot is not ready!"}
            </Snackbar>

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
        </View>
    )
}

export default Settings
