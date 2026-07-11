import { useMemo, useCallback, useContext, useRef, useState, useEffect } from "react"
import { View, Text, ScrollView, StyleSheet, NativeModules, Pressable } from "react-native"
import Ionicons from "@react-native-vector-icons/ionicons"
import * as Clipboard from "expo-clipboard"
import { useTheme } from "../../context/ThemeContext"
import { DebugContext, BotMetaContext } from "../../context/BotStateContext"
import CustomSlider from "../../components/CustomSlider"
import PageHeader from "../../components/PageHeader"
import WarningContainer from "../../components/WarningContainer"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import SystemChecksWizard from "../../components/SystemChecksWizard"
import { SearchPageProvider } from "../../context/SearchPageContext"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import { Section } from "../../components/ui/section"
import { Row } from "../../components/ui/row"
import { Switch } from "../../components/ui/switch"
import { SectionLabel } from "../../components/ui/section-label"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { Snackbar } from "react-native-paper"
import { useLogcatDump } from "../../hooks/useLogcatDump"

/** Descriptor for a single diagnostic test surfaced in the Debug Tests section. Drives the mutually-exclusive Switch rows. */
interface DebugTestDescriptor {
    /** Settings key on `debug`. */
    key:
        | "debugMode_startTemplateMatchingTest"
        | "debugMode_startSingleTrainingOCRTest"
        | "debugMode_startComprehensiveTrainingOCRTest"
        | "debugMode_startRaceListDetectionTest"
        | "debugMode_startMainScreenUpdateTest"
        | "debugMode_startSkillListBuyTest"
        | "debugMode_startScrollBarDetectionTest"
        | "debugMode_startRainbowDetectionTest"
        | "debugMode_startTrackblazerRaceSelectionTest"
        | "debugMode_startTrackblazerInventorySyncTest"
        | "debugMode_startTrackblazerBuyItemsTest"
    /** Stable id used for search registration. */
    searchId: string
    /** Visible Row title. */
    title: string
    /** Row description. */
    description: string
}

const DEBUG_TESTS: DebugTestDescriptor[] = [
    {
        key: "debugMode_startTemplateMatchingTest",
        searchId: "debug-template-matching-test",
        title: "Start Basic Template Matching Test",
        description:
            "Disables normal bot operations and starts the template match test. Only on the Home screen and will check if it can find certain essential buttons on the screen. It will also output what scale it had the most success with.",
    },
    {
        key: "debugMode_startSingleTrainingOCRTest",
        searchId: "debug-single-training-ocr-test",
        title: "Start Single Training OCR Test",
        description:
            "Disables normal bot operations and starts the single training OCR test. Only on the Training screen and tests the current training on display for stat gains and failure chances.",
    },
    {
        key: "debugMode_startComprehensiveTrainingOCRTest",
        searchId: "debug-comprehensive-training-ocr-test",
        title: "Start Comprehensive Training OCR Test",
        description: "Disables normal bot operations and starts the comprehensive training OCR test. Only on the Training screen and tests all 5 trainings for their stat gains and failure chances.",
    },
    {
        key: "debugMode_startRaceListDetectionTest",
        searchId: "debug-race-list-detection-test",
        title: "Start Race List Detection Test",
        description:
            "Disables normal bot operations and starts the Race List detection test. Only on the Race List screen and tests detecting the races with double star predictions currently on display.",
    },
    {
        key: "debugMode_startMainScreenUpdateTest",
        searchId: "debug-main-screen-update-test",
        title: "Start Main Screen Update Test",
        description: "Disables normal bot operations and starts the Main Screen update test. This test will go through all Main Screen updates and then print the Trainee information.",
    },
    {
        key: "debugMode_startSkillListBuyTest",
        searchId: "debug-skill-list-buy-test",
        title: "Start Skill List Buy Test",
        description:
            "Processes the list of skills in the Skills screen, reads all skills in the list, logs a summary and then logs another summary of which skills it will buy to bring down the current Skill Points as close to zero as possible and then it will stop there without actually doing the buying.",
    },
    {
        key: "debugMode_startScrollBarDetectionTest",
        searchId: "debug-scrollbar-detection-test",
        title: "Start Scrollbar Detection Test",
        description:
            "Disables normal bot operations and starts the Scrollbar detection test. Detects the scrollbar on the current screen and attempts to scroll it up and down to verify functionality.",
    },
    {
        key: "debugMode_startRainbowDetectionTest",
        searchId: "debug-rainbow-detection-test",
        title: "Start Rainbow Detection Test",
        description:
            "Disables normal bot operations and starts the Rainbow detection test. Run this on the Training screen: it detects the rainbow glow ring on each support face circle for a few seconds, logs the per-support metrics and the derived rainbow count, and saves an annotated crop to help calibrate the detector.",
    },
    {
        key: "debugMode_startTrackblazerRaceSelectionTest",
        searchId: "debug-trackblazer-race-selection-test",
        title: "Start Trackblazer Race Selection Test",
        description:
            "Disables normal bot operations and starts the Trackblazer race selection test. Navigates to the Race List if on the Main Screen and identifies the best race to run, including Rivals.",
    },
    {
        key: "debugMode_startTrackblazerInventorySyncTest",
        searchId: "debug-trackblazer-inventory-sync-test",
        title: "Start Trackblazer Inventory Sync Test",
        description:
            "Disables normal bot operations and starts the Trackblazer inventory sync test. Opens the Training Items dialog if on the Main Screen and logs inventory contents and quick-use intentions.",
    },
    {
        key: "debugMode_startTrackblazerBuyItemsTest",
        searchId: "debug-trackblazer-buy-items-test",
        title: "Start Trackblazer Buy Items Test",
        description:
            "Disables normal bot operations and starts the Trackblazer buy items test. Opens the Shop if on the Main Screen and logs shop contents and purchase intentions without actually buying anything.",
    },
]

/** Available recording frame rate options surfaced in the Row+chip selector. */
const FRAME_RATE_OPTIONS = [
    { value: 30, label: "30 FPS" },
    { value: 60, label: "60 FPS" },
] as const

/**
 * The Debug Settings page.
 * Provides controls for debug mode, template matching confidence/scale, screen recording settings (bit rate, frame rate, resolution), and diagnostic tests (template matching, OCR, date, race list,
 * aptitudes).
 */
const DebugSettings = () => {
    usePerformanceLogging("DebugSettings")
    const { colors } = useTheme()
    const { debug, updateDebug } = useContext(DebugContext)
    const { defaultSettings } = useContext(BotMetaContext)
    const scrollViewRef = useRef<ScrollView>(null)
    const modalShellStyles = useModalShellStyles()
    const { dumping, message: logcatMessage, dump: dumpLogcat, clearMessage: clearLogcatMessage } = useLogcatDump()

    /**
     * Handles mutual exclusivity for diagnostic debug tests. When one test is enabled, all others are automatically disabled.
     * @param key The settings key of the test being toggled.
     * @param checked The new checked state.
     */
    const handleDebugTestToggle = (key: DebugTestDescriptor["key"], checked: boolean) => {
        if (checked) {
            const updates = DEBUG_TESTS.reduce((acc, t) => {
                acc[t.key] = t.key === key
                return acc
            }, {} as any)
            updateDebug(updates)
        } else {
            updateDebug({ [key]: false } as any)
        }
    }

    const [deviceIp, setDeviceIp] = useState<string>("<phone-ip>")
    const [frameRatePickerOpen, setFrameRatePickerOpen] = useState(false)

    useEffect(() => {
        if (debug.enableRemoteLogViewer) {
            NativeModules.StartModule.getDeviceIpAddress()
                .then((ip: string) => setDeviceIp(ip))
                .catch(() => setDeviceIp("<phone-ip>"))
        }
    }, [debug.enableRemoteLogViewer])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: { flex: 1, flexDirection: "column", justifyContent: "center", margin: 10, backgroundColor: colors.bg },
                hostPad: { padding: SPACING.md },
                sectionDescription: { ...TYPE.caption, color: colors.textMuted, lineHeight: 18, paddingHorizontal: SPACING.md, paddingTop: SPACING.sm },
                chip: {
                    ...TYPE.monoLabel,
                    color: colors.brand,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: 2,
                    backgroundColor: colors.brandSubtle,
                    borderRadius: RADII.pill,
                    overflow: "hidden",
                },
            }),
        [colors]
    )

    const rlvUrl = `http://${deviceIp === "10.0.2.15" ? "localhost" : deviceIp}:${debug.remoteLogViewerPort}`

    const handleCopyRlvUrl = useCallback(async () => {
        try {
            await Clipboard.setStringAsync(rlvUrl)
        } catch {
            // swallow - clipboard may fail silently on web/sim
        }
    }, [rlvUrl])

    const currentFrameRateLabel = FRAME_RATE_OPTIONS.find((o) => o.value === debug.recordingFrameRate)?.label ?? "30 FPS"

    return (
        <View style={styles.root}>
            <SearchPageProvider page="DebugSettings" scrollViewRef={scrollViewRef}>
                <PageHeader title="Debug Settings" />
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            Debug Mode */}
                        <Section label="Debug Mode">
                            <ToggleSetting id="enable-debug-mode" title="Enable Debug Mode" description="Allows debugging messages in the log and test images to be created in the /temp/ folder." checked={debug.enableDebugMode} onCheckedChange={(checked) => updateDebug({ enableDebugMode: checked })} />
                        </Section>
                        {debug.enableDebugMode && (
                            <WarningContainer style={{ marginTop: 0, marginBottom: SPACING.md }}>
                                ⚠️ Significantly extends the average runtime of the bot due to increased IO operations.
                            </WarningContainer>
                        )}

                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            Image/OCR Recognition */}
                        <Section label="Image/OCR Recognition">
                            <View style={styles.hostPad}>
                                <CustomSlider
                                    searchId="template-match-confidence"
                                    value={debug.templateMatchConfidence}
                                    placeholder={defaultSettings.debug.templateMatchConfidence}
                                    onValueChange={(value) => updateDebug({ templateMatchConfidence: value })}
                                    onSlidingComplete={(value) => updateDebug({ templateMatchConfidence: value })}
                                    min={0.5}
                                    max={1.0}
                                    step={0.01}
                                    label="Template Match Confidence"
                                    labelUnit=""
                                    showValue={true}
                                    showLabels={true}
                                    description="Sets the minimum confidence level for template matching with 1080p as the baseline. Consider lowering this to something like 0.7 or 70% at lower resolutions. Making it too low will cause the bot to match on too many things as false positives."
                                />
                            </View>
                            <View style={styles.hostPad}>
                                <CustomSlider
                                    searchId="template-match-custom-scale"
                                    value={debug.templateMatchCustomScale}
                                    placeholder={defaultSettings.debug.templateMatchCustomScale}
                                    onValueChange={(value) => updateDebug({ templateMatchCustomScale: value })}
                                    onSlidingComplete={(value) => updateDebug({ templateMatchCustomScale: value })}
                                    min={0.5}
                                    max={3.0}
                                    step={0.01}
                                    label="Template Match Custom Scale"
                                    labelUnit=""
                                    showValue={true}
                                    showLabels={true}
                                    description="Manually set the scale to do template matching. The Basic Template Matching Test can help find your recommended scale. Making it too low or too high will cause the bot to match on too little or too many things as false positives."
                                />
                            </View>
                            <View style={styles.hostPad}>
                                <CustomSlider
                                    searchId="ocr-threshold"
                                    value={debug.ocrThreshold}
                                    placeholder={defaultSettings.debug.ocrThreshold}
                                    onValueChange={(value: number) => updateDebug({ ocrThreshold: value })}
                                    onSlidingComplete={(value: number) => updateDebug({ ocrThreshold: value })}
                                    min={100}
                                    max={255}
                                    step={5}
                                    label="OCR Threshold"
                                    labelUnit=""
                                    showValue={true}
                                    showLabels={true}
                                    description="The brightness threshold used to distinguish text from the background during OCR. Note: This setting does not affect high-precision features like Stat Detection or Training Failure Chance detection, as they use specialized processing."
                                />
                            </View>
                        </Section>

                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            Remote Log Viewer */}
                        <Section label="Remote Log Viewer">
                            <View style={{ padding: SPACING.md, gap: SPACING.sm }}>
                                <Text style={[TYPE.caption, { color: colors.textMuted }]}>
                                    Starts an HTTP server on this device when the bot runs. Open the URL shown below in a browser on your computer to view logs in real-time.
                                </Text>
                                <View style={{ flexDirection: "row", alignItems: "center", gap: SPACING.sm, paddingVertical: SPACING.md }}>
                                    <View style={{ width: 28, height: 28, borderRadius: 999, backgroundColor: colors.brandSubtle, alignItems: "center", justifyContent: "center" }}>
                                        <Ionicons name="cellular-outline" size={14} color={colors.brand} />
                                    </View>
                                    <View style={{ flex: 1 }}>
                                        <Text style={{ ...TYPE.body, color: colors.text, fontWeight: "600" as const }}>Remote Log Viewer</Text>
                                        <Text style={{ ...TYPE.caption, color: colors.textMuted }}>Same WiFi required</Text>
                                    </View>
                                    <SearchableItem
                                        id="settings-enable-remote-log-viewer"
                                        title="Enable Remote Log Viewer"
                                        description="Starts an HTTP server on this device when the bot runs. Open the URL shown below in a browser on your computer to view logs in real-time."
                                    >
                                        <Switch checked={debug.enableRemoteLogViewer} onCheckedChange={(checked) => updateDebug({ enableRemoteLogViewer: checked })} />
                                    </SearchableItem>
                                </View>
                                {debug.enableRemoteLogViewer && (
                                    <>
                                        <Pressable
                                            onPress={handleCopyRlvUrl}
                                            android_ripple={{ color: colors.ripple, foreground: true }}
                                            style={{ padding: SPACING.sm, backgroundColor: colors.surfaceRaised, borderRadius: RADII.md, flexDirection: "row", alignItems: "center", gap: SPACING.sm }}
                                        >
                                            <Text style={{ ...TYPE.monoLabel, color: colors.brand, flex: 1 }}>{rlvUrl}</Text>
                                            <Ionicons name="copy-outline" size={14} color={colors.textMuted} />
                                        </Pressable>
                                        <Text style={{ ...TYPE.caption, color: colors.textMuted }}>Port {debug.remoteLogViewerPort} · Active</Text>
                                        <CustomSlider
                                            searchId="settings-remote-log-viewer-port"
                                            searchCondition={debug.enableRemoteLogViewer}
                                            parentId="settings-enable-remote-log-viewer"
                                            value={debug.remoteLogViewerPort}
                                            placeholder={defaultSettings.debug.remoteLogViewerPort}
                                            onValueChange={(value) => updateDebug({ remoteLogViewerPort: value })}
                                            onSlidingComplete={(value) => updateDebug({ remoteLogViewerPort: value })}
                                            min={1024}
                                            max={65535}
                                            step={1}
                                            showValue
                                            showLabels
                                            label="Server Port"
                                            description="Port number for the log stream server. Change only if the default conflicts with another service."
                                        />
                                        {deviceIp === "10.0.2.15" && (
                                            <Text style={{ ...TYPE.caption, color: colors.warningText }}>
                                                Emulator detected - direct connection to {deviceIp} will fail. Use ADB port forwarding instead.
                                            </Text>
                                        )}
                                    </>
                                )}
                            </View>
                        </Section>

                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            Screen Recording Settings */}
                        <Section label="Screen Recording Settings" firstDivider={false} noDividers={!debug.enableScreenRecording}>
                            <View style={{ padding: SPACING.md, paddingBottom: 0 }}>
                                <Text style={[TYPE.caption, { color: colors.textMuted }]}>Configure the quality settings for screen recording.</Text>
                            </View>
                            <ToggleSetting id="enable-screen-recording" title="Enable Screen Recording" description="Records the screen while the bot is running. The mp4 file will be saved to the /recordings folder of the app's data directory. Note that performance and battery life may be impacted while recording." checked={debug.enableScreenRecording} onCheckedChange={(checked) => updateDebug({ enableScreenRecording: checked })} />
                            <View style={{ paddingHorizontal: SPACING.md }}>
                                <CustomSlider
                                    searchId="recording-bit-rate"
                                    searchCondition={debug.enableScreenRecording}
                                    parentId="enable-screen-recording"
                                    value={debug.recordingBitRate}
                                    placeholder={defaultSettings.debug.recordingBitRate}
                                    onValueChange={(value) => updateDebug({ recordingBitRate: value })}
                                    onSlidingComplete={(value) => updateDebug({ recordingBitRate: value })}
                                    min={1}
                                    max={20}
                                    step={1}
                                    label="Recording Quality (Bit Rate)"
                                    labelUnit=" Mbps"
                                    showValue={true}
                                    showLabels={true}
                                    description="Sets the video bit rate for screen recording. Higher values produce better quality but larger file sizes."
                                />
                            </View>
                            {debug.enableScreenRecording && (
                                <SearchableItem
                                    id="recording-frame-rate"
                                    title="Recording Frame Rate"
                                    description="Sets the frame rate for screen recording."
                                    parentId="enable-screen-recording"
                                    condition={debug.enableScreenRecording}
                                >
                                    <Row
                                        title="Recording Frame Rate"
                                        description="Sets the frame rate for screen recording."
                                        onPress={() => setFrameRatePickerOpen(true)}
                                        right={<Text style={styles.chip}>{currentFrameRateLabel}</Text>}
                                    />
                                </SearchableItem>
                            )}
                            <View style={styles.hostPad}>
                                <CustomSlider
                                    searchId="recording-resolution-scale"
                                    searchCondition={debug.enableScreenRecording}
                                    parentId="enable-screen-recording"
                                    value={debug.recordingResolutionScale}
                                    placeholder={defaultSettings.debug.recordingResolutionScale}
                                    onValueChange={(value) => updateDebug({ recordingResolutionScale: value })}
                                    onSlidingComplete={(value) => updateDebug({ recordingResolutionScale: value })}
                                    min={0.25}
                                    max={1.0}
                                    step={0.05}
                                    label="Recording Resolution Scale"
                                    labelUnit=""
                                    showValue={true}
                                    showLabels={true}
                                    description="Scales the recording resolution. Lower values produce smaller file sizes but lower quality. 1.0 = full resolution, 0.5 = half resolution."
                                />
                            </View>
                        </Section>

                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            System Checks (wizard) */}
                        <Section label="System Checks">
                            <SystemChecksWizard embeddedInWizard />
                        </Section>

                        <Section label="DEBUG SETTINGS">
                            <View style={{ padding: SPACING.md }}>
                                <CustomSlider
                                    searchId="settings-overlay-button-size"
                                    value={debug.overlayButtonSizeDP}
                                    placeholder={defaultSettings.debug.overlayButtonSizeDP}
                                    onValueChange={(value) => updateDebug({ overlayButtonSizeDP: value })}
                                    onSlidingComplete={(value) => updateDebug({ overlayButtonSizeDP: value })}
                                    min={30}
                                    max={60}
                                    step={5}
                                    label="Overlay Button Size"
                                    labelUnit=" dp"
                                    showValue={true}
                                    showLabels={true}
                                    description="Sets the size of the floating overlay button in density-independent pixels (dp). Higher values make the button easier to tap."
                                />
                            </View>

                            <ToggleSetting id="settings-enable-message-id-display" title="Enable Message ID Display" description="Shows message IDs in the message log to help with debugging." checked={debug.enableMessageIdDisplay} onCheckedChange={(checked) => updateDebug({ enableMessageIdDisplay: checked })} />
                        </Section>

                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            Debug Tests */}
                        <View style={{ marginTop: SPACING.sm, marginBottom: SPACING.lg }}>
                            <SectionLabel label="Debug Tests" />
                            <View style={{ backgroundColor: colors.surface, borderRadius: RADII.lg, borderWidth: 1, borderColor: colors.borderHair, overflow: "hidden" }}>
                                <View style={{ padding: SPACING.md, paddingBottom: 0 }}>
                                    <Text style={[TYPE.caption, { color: colors.textMuted, lineHeight: 18, marginBottom: SPACING.sm }]}>
                                        Run diagnostic tests to verify template matching and OCR functionality.
                                    </Text>
                                    <WarningContainer style={{ marginTop: 0 }}>
                                        {"⚠️ Only one debug test can be enabled at a time. \n\nHaving Debug Mode enabled will output more helpful logs."}
                                    </WarningContainer>
                                </View>
                                {DEBUG_TESTS.map((test, idx) => (
                                    <View key={test.key}>
                                        <ToggleSetting id={test.searchId} title={test.title} description={test.description} checked={!!debug[test.key]} onCheckedChange={(checked) => handleDebugTestToggle(test.key, checked)} />
                                        {idx < DEBUG_TESTS.length - 1 && <View style={{ height: 1, backgroundColor: colors.borderHair, marginLeft: SPACING.lg }} />}
                                    </View>
                                ))}
                            </View>
                        </View>

                        {/* //////////////////////////////////////////////////////////////////////////////////////////////////
                            //////////////////////////////////////////////////////////////////////////////////////////////////
                            Diagnostics */}
                        <Section label="Diagnostics" firstDivider={false}>
                            <Text style={styles.sectionDescription}>
                                {
                                    "Saves this app's recent logcat output to a timestamped .txt file at the root of your storage folder. The 6-hour window is capped by the device's log buffer size. To increase it, enable Developer Options (tap Build number 7 times under Settings > About phone), then raise Logger buffer sizes in Developer options."
                                }
                            </Text>
                            <SearchableItem
                                id="dump-logcat"
                                title="Dump logcat (last 6h)"
                                description="Saves this app's recent logcat output to a timestamped .txt file at the root of your storage folder."
                            >
                                <View style={styles.hostPad}>
                                    <Pressable
                                        onPress={dumpLogcat}
                                        disabled={dumping}
                                        android_ripple={{ color: colors.ripple, foreground: true }}
                                        style={{
                                            padding: SPACING.sm,
                                            backgroundColor: colors.surfaceRaised,
                                            borderRadius: RADII.md,
                                            flexDirection: "row",
                                            alignItems: "center",
                                            gap: SPACING.sm,
                                            opacity: dumping ? 0.6 : 1,
                                        }}
                                    >
                                        <Ionicons name="download-outline" size={16} color={colors.brand} />
                                        <Text style={{ ...TYPE.monoLabel, color: colors.brand, flex: 1 }}>{dumping ? "Dumping logcat..." : "Dump logcat (last 6h)"}</Text>
                                    </Pressable>
                                </View>
                            </SearchableItem>
                        </Section>
                    </View>
                </ScrollView>
            </SearchPageProvider>

            <SheetModal
                visible={frameRatePickerOpen}
                onRequestClose={() => setFrameRatePickerOpen(false)}
                header={
                    <View style={modalShellStyles.modalHeaderRow}>
                        <Text style={modalShellStyles.modalTitleMono}>RECORDING FRAME RATE</Text>
                        <Pressable
                            style={modalShellStyles.modalCloseChip}
                            onPress={() => setFrameRatePickerOpen(false)}
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
                    {FRAME_RATE_OPTIONS.map((o) => (
                        <ModalRadioRow
                            key={o.value}
                            label={o.label}
                            selected={o.value === debug.recordingFrameRate}
                            onPress={() => {
                                updateDebug({ recordingFrameRate: o.value })
                                setFrameRatePickerOpen(false)
                            }}
                        />
                    ))}
                </View>
            </SheetModal>
            <Snackbar visible={logcatMessage !== null} onDismiss={clearLogcatMessage} duration={4000} style={{ backgroundColor: colors.surfaceRaised, borderRadius: 10 }}>
                {logcatMessage ?? ""}
            </Snackbar>
        </View>
    )
}

export default DebugSettings
