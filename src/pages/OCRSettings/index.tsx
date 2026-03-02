import { useMemo, useContext, useRef } from "react"
import { View, ScrollView, StyleSheet } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext, defaultSettings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import CustomSlider from "../../components/CustomSlider"
import CustomCheckbox from "../../components/CustomCheckbox"
import PageHeader from "../../components/PageHeader"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

/**
 * The OCR Settings page.
 * Provides controls for OCR threshold, confidence level, automatic retry behavior,
 * and hiding OCR comparison result logs during training event detection.
 */
const OCRSettings = () => {
    usePerformanceLogging("OCRSettings")
    const { colors } = useTheme()
    const bsc = useContext(BotStateContext)
    const scrollViewRef = useRef<ScrollView>(null)

    const { settings, setSettings } = bsc
    // Merge current OCR settings with defaults to handle missing properties.
    const ocrSettings = { ...defaultSettings.ocr, ...settings.ocr }
    const { ocrThreshold, enableAutomaticOCRRetry, ocrConfidence } = ocrSettings

    const updateOCRSetting = (key: keyof typeof settings.ocr, value: any) => {
        setSettings({
            ...bsc.settings,
            ocr: {
                ...bsc.settings.ocr,
                [key]: value,
            },
        })
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
            }),
        [colors]
    )

    return (
        <View style={styles.root}>
            <PageHeader title="OCR Settings" />

            <SearchPageProvider page="OCRSettings" scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <View style={styles.section}>
                            <CustomSlider
                                value={ocrThreshold}
                                placeholder={bsc.defaultSettings.ocr.ocrThreshold}
                                onValueChange={(value) => updateOCRSetting("ocrThreshold", value)}
                                min={100}
                                max={255}
                                step={5}
                                label="OCR Threshold"
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                                description="Adjust the threshold for OCR text detection. Higher values make text detection more strict, lower values make it more lenient."
                                searchId="ocrThreshold"
                            />
                        </View>

                        <View style={styles.section}>
                            <CustomCheckbox
                                checked={enableAutomaticOCRRetry}
                                onCheckedChange={(checked) => updateOCRSetting("enableAutomaticOCRRetry", checked)}
                                label="Enable Automatic OCR Retry"
                                description="When enabled, the bot will automatically retry OCR detection if the initial attempt fails or has low confidence."
                                className="my-2"
                                searchId="enableAutomaticOCRRetry"
                            />
                        </View>

                        <View style={styles.section}>
                            <CustomSlider
                                value={ocrConfidence}
                                placeholder={bsc.defaultSettings.ocr.ocrConfidence}
                                onValueChange={(value) => updateOCRSetting("ocrConfidence", value)}
                                min={50}
                                max={100}
                                step={1}
                                label="OCR Confidence"
                                labelUnit="%"
                                showValue={true}
                                showLabels={true}
                                description="Set the minimum confidence level required for OCR text detection. Higher values ensure more accurate text recognition but may miss some text."
                                searchId="ocrConfidence"
                            />
                        </View>

                        <View style={styles.section}>
                            <CustomCheckbox
                                checked={bsc.settings.debug.enableHideOCRComparisonResults}
                                onCheckedChange={(checked) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        debug: { ...bsc.settings.debug, enableHideOCRComparisonResults: checked },
                                    })
                                }}
                                label="Hide OCR String Comparison Results during Training Event detection"
                                description="Hides the log messages involved in the string comparison process during training event detection."
                                style={{ marginTop: 10 }}
                                searchId="enableHideOCRComparisonResults"
                            />
                        </View>
                    </View>
                </ScrollView>
            </SearchPageProvider>
        </View>
    )
}

export default OCRSettings
