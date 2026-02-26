import { useMemo, useContext, useRef } from "react"
import { View, Text, ScrollView, StyleSheet } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext } from "../../context/BotStateContext"
import CustomSlider from "../../components/CustomSlider"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomTitle from "../../components/CustomTitle"
import CustomSelect from "../../components/CustomSelect"
import { Separator } from "../../components/ui/separator"
import PageHeader from "../../components/PageHeader"
import WarningContainer from "../../components/WarningContainer"
import { SearchPageProvider } from "../../context/SearchPageContext"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

const DebugSettings = () => {
    usePerformanceLogging("DebugSettings")
    const { colors } = useTheme()
    const bsc = useContext(BotStateContext)
    const scrollViewRef = useRef<ScrollView>(null)

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

    return (
        <View style={styles.root}>
            <PageHeader title="Debug Settings" />

            <SearchPageProvider page="DebugSettings" scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <View style={{ marginTop: 16 }}>
                            <CustomCheckbox
                                searchId="enable-debug-mode"
                                checked={bsc.settings.debug.enableDebugMode}
                                onCheckedChange={(checked) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, enableDebugMode: checked },
                                    })
                                }}
                                label="Enable Debug Mode"
                                description="Allows debugging messages in the log and test images to be created in the /temp/ folder."
                            />

                            {bsc.settings.debug.enableDebugMode && (
                                <WarningContainer style={{ marginTop: 8 }}>⚠️ Significantly extends the average runtime of the bot due to increased IO operations.</WarningContainer>
                            )}

                            <CustomSlider
                                searchId="template-match-confidence"
                                value={bsc.settings.debug.templateMatchConfidence}
                                placeholder={bsc.defaultSettings.debug.templateMatchConfidence}
                                onValueChange={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, templateMatchConfidence: value },
                                    })
                                }}
                                onSlidingComplete={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, templateMatchConfidence: value },
                                    })
                                }}
                                min={0.5}
                                max={1.0}
                                step={0.01}
                                label="Adjust Confidence for Template Matching"
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                                description="Sets the minimum confidence level for template matching with 1080p as the baseline. Consider lowering this to something like 0.7 or 70% at lower resolutions. Making it too low will cause the bot to match on too many things as false positives."
                            />

                            <CustomSlider
                                searchId="template-match-custom-scale"
                                value={bsc.settings.debug.templateMatchCustomScale}
                                placeholder={bsc.defaultSettings.debug.templateMatchCustomScale}
                                onValueChange={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, templateMatchCustomScale: value },
                                    })
                                }}
                                onSlidingComplete={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, templateMatchCustomScale: value },
                                    })
                                }}
                                min={0.5}
                                max={3.0}
                                step={0.01}
                                label="Set the Custom Image Scale for Template Matching"
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                                description="Manually set the scale to do template matching. The Basic Template Matching Test can help find your recommended scale. Making it too low or too high will cause the bot to match on too little or too many things as false positives."
                            />

                            <Separator style={{ marginVertical: 16 }} />

                            <CustomTitle title="Screen Recording Settings" description="Configure the quality settings for screen recording." />

                            <CustomCheckbox
                                searchId="enable-screen-recording"
                                checked={bsc.settings.debug.enableScreenRecording}
                                onCheckedChange={(checked) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, enableScreenRecording: checked },
                                    })
                                }}
                                label="Enable Screen Recording"
                                description="Records the screen while the bot is running. The mp4 file will be saved to the /recordings folder of the app's data directory. Note that performance and battery life may be impacted while recording."
                            />

                            <CustomSlider
                                searchId="recording-bit-rate"
                                searchCondition={bsc.settings.debug.enableScreenRecording}
                                parentId="enable-screen-recording"
                                value={bsc.settings.debug.recordingBitRate}
                                placeholder={bsc.defaultSettings.debug.recordingBitRate}
                                onValueChange={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, recordingBitRate: value },
                                    })
                                }}
                                onSlidingComplete={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, recordingBitRate: value },
                                    })
                                }}
                                min={1}
                                max={20}
                                step={1}
                                label="Recording Quality (Bit Rate)"
                                labelUnit=" Mbps"
                                showValue={true}
                                showLabels={true}
                                description="Sets the video bit rate for screen recording. Higher values produce better quality but larger file sizes."
                            />

                            <CustomSelect
                                searchId="recording-frame-rate"
                                searchCondition={bsc.settings.debug.enableScreenRecording}
                                parentId="enable-screen-recording"
                                value={bsc.settings.debug.recordingFrameRate.toString()}
                                options={[
                                    { value: "30", label: "30 FPS" },
                                    { value: "60", label: "60 FPS" },
                                ]}
                                onValueChange={(value) => {
                                    if (value) {
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: { ...bsc.settings.debug, recordingFrameRate: parseInt(value, 10) },
                                        })
                                    }
                                }}
                                label="Recording Frame Rate"
                                description="Sets the frame rate for screen recording."
                                placeholder="Select Frame Rate for Recording"
                                style={{ marginTop: 8, marginBottom: 16 }}
                            />

                            <CustomSlider
                                searchId="recording-resolution-scale"
                                searchCondition={bsc.settings.debug.enableScreenRecording}
                                parentId="enable-screen-recording"
                                value={bsc.settings.debug.recordingResolutionScale}
                                placeholder={bsc.defaultSettings.debug.recordingResolutionScale}
                                onValueChange={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, recordingResolutionScale: value },
                                    })
                                }}
                                onSlidingComplete={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, recordingResolutionScale: value },
                                    })
                                }}
                                min={0.25}
                                max={1.0}
                                step={0.05}
                                label="Recording Resolution Scale"
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                                description="Scales the recording resolution. Lower values produce smaller file sizes but lower quality. 1.0 = full resolution, 0.5 = half resolution."
                            />

                            <Separator style={{ marginVertical: 16 }} />

                            <CustomTitle title="Debug Tests" description="Run diagnostic tests to verify template matching and OCR functionality. Only one test can be enabled at a time." />

                            {/* Warning message for debug tests */}
                            <WarningContainer style={{ marginBottom: 16 }}>
                                {"⚠️ Only one debug test can be enabled at a time. Enabling a test will automatically disable the others.\n\nHaving Debug Mode enabled will output more helpful logs."}
                            </WarningContainer>

                            <CustomCheckbox
                                searchId="debug-template-matching-test"
                                checked={bsc.settings.debug.debugMode_startTemplateMatchingTest}
                                onCheckedChange={(checked) => {
                                    if (checked) {
                                        // Disable other tests when enabling this one.
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: {
                                                ...bsc.settings.debug,
                                                debugMode_startTemplateMatchingTest: true,
                                                debugMode_startSingleTrainingOCRTest: false,
                                                debugMode_startComprehensiveTrainingOCRTest: false,
                                                debugMode_startDateOCRTest: false,
                                                debugMode_startRaceListDetectionTest: false,
                                                debugMode_startAptitudesDetectionTest: false,
                                            },
                                        })
                                    } else {
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: { ...bsc.settings.debug, debugMode_startTemplateMatchingTest: false },
                                        })
                                    }
                                }}
                                label="Start Basic Template Matching Test"
                                description="Disables normal bot operations and starts the template match test. Only on the Home screen and will check if it can find certain essential buttons on the screen. It will also output what scale it had the most success with."
                                style={{ marginTop: 10 }}
                            />

                            <CustomCheckbox
                                searchId="debug-single-training-ocr-test"
                                checked={bsc.settings.debug.debugMode_startSingleTrainingOCRTest}
                                onCheckedChange={(checked) => {
                                    if (checked) {
                                        // Disable other tests when enabling this one.
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: {
                                                ...bsc.settings.debug,
                                                debugMode_startTemplateMatchingTest: false,
                                                debugMode_startSingleTrainingOCRTest: true,
                                                debugMode_startComprehensiveTrainingOCRTest: false,
                                                debugMode_startDateOCRTest: false,
                                                debugMode_startRaceListDetectionTest: false,
                                                debugMode_startAptitudesDetectionTest: false,
                                            },
                                        })
                                    } else {
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: { ...bsc.settings.debug, debugMode_startSingleTrainingOCRTest: false },
                                        })
                                    }
                                }}
                                label="Start Single Training OCR Test"
                                description="Disables normal bot operations and starts the single training OCR test. Only on the Training screen and tests the current training on display for stat gains and failure chances."
                                style={{ marginTop: 10 }}
                            />

                            <CustomCheckbox
                                searchId="debug-comprehensive-training-ocr-test"
                                checked={bsc.settings.debug.debugMode_startComprehensiveTrainingOCRTest}
                                onCheckedChange={(checked) => {
                                    if (checked) {
                                        // Disable other tests when enabling this one.
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: {
                                                ...bsc.settings.debug,
                                                debugMode_startTemplateMatchingTest: false,
                                                debugMode_startSingleTrainingOCRTest: false,
                                                debugMode_startComprehensiveTrainingOCRTest: true,
                                                debugMode_startDateOCRTest: false,
                                                debugMode_startRaceListDetectionTest: false,
                                                debugMode_startAptitudesDetectionTest: false,
                                            },
                                        })
                                    } else {
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: { ...bsc.settings.debug, debugMode_startComprehensiveTrainingOCRTest: false },
                                        })
                                    }
                                }}
                                label="Start Comprehensive Training OCR Test"
                                description="Disables normal bot operations and starts the comprehensive training OCR test. Only on the Training screen and tests all 5 trainings for their stat gains and failure chances."
                                style={{ marginTop: 10 }}
                            />

                            <CustomCheckbox
                                searchId="debug-date-ocr-test"
                                checked={bsc.settings.debug.debugMode_startDateOCRTest}
                                onCheckedChange={(checked) => {
                                    if (checked) {
                                        // Disable other tests when enabling this one.
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: {
                                                ...bsc.settings.debug,
                                                debugMode_startTemplateMatchingTest: false,
                                                debugMode_startSingleTrainingOCRTest: false,
                                                debugMode_startComprehensiveTrainingOCRTest: false,
                                                debugMode_startDateOCRTest: true,
                                                debugMode_startRaceListDetectionTest: false,
                                                debugMode_startAptitudesDetectionTest: false,
                                            },
                                        })
                                    } else {
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: { ...bsc.settings.debug, debugMode_startDateOCRTest: false },
                                        })
                                    }
                                }}
                                label="Start Date OCR Test"
                                description="Disables normal bot operations and starts the date OCR test. Only on the Main screen and the Race List screen and tests detecting the current date."
                                style={{ marginTop: 10 }}
                            />

                            <CustomCheckbox
                                searchId="debug-race-list-detection-test"
                                checked={bsc.settings.debug.debugMode_startRaceListDetectionTest}
                                onCheckedChange={(checked) => {
                                    if (checked) {
                                        // Disable other tests when enabling this one.
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: {
                                                ...bsc.settings.debug,
                                                debugMode_startTemplateMatchingTest: false,
                                                debugMode_startSingleTrainingOCRTest: false,
                                                debugMode_startComprehensiveTrainingOCRTest: false,
                                                debugMode_startDateOCRTest: false,
                                                debugMode_startRaceListDetectionTest: true,
                                                debugMode_startAptitudesDetectionTest: false,
                                            },
                                        })
                                    } else {
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: { ...bsc.settings.debug, debugMode_startRaceListDetectionTest: false },
                                        })
                                    }
                                }}
                                label="Start Race List Detection Test"
                                description="Disables normal bot operations and starts the Race List detection test. Only on the Race List screen and tests detecting the races with double star predictions currently on display."
                                style={{ marginTop: 10 }}
                            />

                            <CustomCheckbox
                                searchId="debug-aptitudes-detection-test"
                                checked={bsc.settings.debug.debugMode_startAptitudesDetectionTest}
                                onCheckedChange={(checked) => {
                                    if (checked) {
                                        // Disable other tests when enabling this one.
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: {
                                                ...bsc.settings.debug,
                                                debugMode_startTemplateMatchingTest: false,
                                                debugMode_startSingleTrainingOCRTest: false,
                                                debugMode_startComprehensiveTrainingOCRTest: false,
                                                debugMode_startDateOCRTest: false,
                                                debugMode_startRaceListDetectionTest: false,
                                                debugMode_startAptitudesDetectionTest: true,
                                            },
                                        })
                                    } else {
                                        bsc.setSettings({
                                            ...bsc.settings,
                                            debug: { ...bsc.settings.debug, debugMode_startAptitudesDetectionTest: false },
                                        })
                                    }
                                }}
                                label="Start Aptitudes Detection Test"
                                description="Disables normal bot operations and starts the Aptitudes detection test. Only on the Main screen and tests detecting the current aptitudes."
                                style={{ marginTop: 10 }}
                            />
                        </View>
                    </View>
                </ScrollView>
            </SearchPageProvider>
        </View>
    )
}

export default DebugSettings
