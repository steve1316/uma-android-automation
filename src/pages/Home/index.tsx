import * as Application from "expo-application"
import MessageLog from "../../components/MessageLog"
import { useCallback, useContext, useEffect, useMemo, useRef, useState } from "react"
import { BotMetaContext, GeneralMiscContext, DebugContext, RacingContext, SkillsContext, TrainingContext } from "../../context/BotStateContext"
import { useNavigation, CommonActions } from "@react-navigation/native"
import { useSettings } from "../../context/SettingsContext"
import { logWithTimestamp, logErrorWithTimestamp } from "../../lib/logger"
import { Animated, DeviceEventEmitter, StyleSheet, View, NativeModules } from "react-native"
import { Snackbar } from "react-native-paper"
import { MessageLogDispatchContext } from "../../context/MessageLogContext"
import { useTheme } from "../../context/ThemeContext"
import { Text } from "../../components/ui/text"
import { AlertDialog, AlertDialogAction, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "../../components/ui/alert-dialog"
import Ionicons from "@react-native-vector-icons/ionicons"
import { Tooltip, TooltipContent, TooltipTrigger } from "../../components/ui/tooltip"
import PageHeader from "../../components/PageHeader"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import SelectButton from "../../components/SelectButton"
import PermissionSetupDialog from "../../components/PermissionSetupDialog"
import { loadDeviceCapabilities, shouldSuggestX8664Variant } from "../../lib/chat/deviceCapabilities"
import HeroStatusCard, { HeroStatus } from "../../components/HeroStatusCard"
import HeroGlance, { HeroGlanceTarget } from "../../components/HeroStatusCard/HeroGlance"
import HeroChips from "../../components/HeroStatusCard/HeroChips"
import { findActiveDebugTest, activeSkillPlans, abbreviateStatPriority, raceStrategyLabel, raceStrategyTargetId } from "../../components/HeroStatusCard/heroGlanceData"
import { useProfileContext, DEFAULT_PROFILE_NAME } from "../../context/ProfileContext"
import { SPACING } from "../../lib/spacing"

const styles = StyleSheet.create({
    root: {
        flex: 1,
        flexDirection: "column",
        alignItems: "center",
        paddingHorizontal: 10,
        paddingVertical: 10,
    },
    contentContainer: {
        flex: 1,
        width: "100%",
        flexDirection: "column",
    },
    hero: {
        width: "100%",
        marginBottom: SPACING.md,
    },
    logBody: {
        flex: 1,
    },
    button: {
        width: 100,
    },
})

// Maps a hero glance target to its screen name (and optional nested route params) inside the nested Settings stack navigator.
const HERO_NAV_ROUTES: Record<HeroGlanceTarget, { screen: string; params?: Record<string, string> }> = {
    debug: { screen: "DebugSettings", params: { targetId: "enable-debug-mode" } },
    debugTest: { screen: "DebugSettings" },
    srs: { screen: "ScheduleScreen", params: { tab: "raceSolver", targetId: "enable-smart-race-solver" } },
    skills: { screen: "Skills" },
    training: { screen: "TrainingSettings" },
    racing: { screen: "RacingSettings" },
}

/**
 * List of scenarios that are supported by the app.
 */
const scenarios = [
    {
        value: "URA Finale",
        label: "URA Finale",
        disabled: false,
    },
    {
        value: "Unity Cup",
        label: "Unity Cup",
        disabled: false,
    },
    {
        value: "Trackblazer",
        label: "Trackblazer",
        disabled: false,
    },
    {
        value: "Grand Live",
        label: "Grand Live",
        disabled: false,
    },
]

/**
 * The main Home page of the application.
 * Displays the Start/Stop button for the bot, a message log, and handles bot lifecycle events including settings persistence and readiness checks.
 */
const Home = () => {
    usePerformanceLogging("Home")
    const { StartModule } = NativeModules

    const { colors } = useTheme()
    const [isRunning, setIsRunning] = useState<boolean>(false)
    const [showNotReadyDialog, setShowNotReadyDialog] = useState<boolean>(false)
    const [snackbarOpen, setSnackbarOpen] = useState<boolean>(false)
    const [snackbarMessage, setSnackbarMessage] = useState<string>("")
    const [deviceMetrics, setDeviceMetrics] = useState<{ width: number; height: number; dpi: number } | null>(null)
    const [unsupportedReason, setUnsupportedReason] = useState<string | null>(null)
    const [showPermissionDialog, setShowPermissionDialog] = useState<boolean>(false)
    const [abiMismatch, setAbiMismatch] = useState<boolean>(false)

    const { readyStatus, setReadyStatus, setAppName, setAppVersion } = useContext(BotMetaContext)
    const { general, updateGeneral } = useContext(GeneralMiscContext)
    const { debug } = useContext(DebugContext)
    const { racing } = useContext(RacingContext)
    const { skills } = useContext(SkillsContext)
    const { training } = useContext(TrainingContext)
    const mlc = useContext(MessageLogDispatchContext)
    const { saveSettings } = useSettings()
    const { currentProfileName } = useProfileContext()
    const navigation = useNavigation()

    const pulseAnim = useRef(new Animated.Value(1)).current

    useEffect(() => {
        let animation: Animated.CompositeAnimation | null = null

        if (unsupportedReason !== null || abiMismatch) {
            // Pulsate the icon to grab attention when there's an unsupported device or a slow ABI variant installed.
            animation = Animated.loop(
                Animated.sequence([
                    Animated.timing(pulseAnim, {
                        toValue: 1.25,
                        duration: 700,
                        useNativeDriver: true,
                    }),
                    Animated.timing(pulseAnim, {
                        toValue: 1,
                        duration: 700,
                        useNativeDriver: true,
                    }),
                ])
            )
            animation.start()
        } else {
            pulseAnim.setValue(1)
        }

        return () => {
            animation?.stop()
        }
    }, [unsupportedReason, abiMismatch])

    useEffect(() => {
        const mediaProjectionSubscription = DeviceEventEmitter.addListener("MediaProjectionService", (data) => {
            setIsRunning(data["message"] === "Running")
        })

        const botServiceSubscription = DeviceEventEmitter.addListener("BotService", (data) => {
            if (data["message"] === "Running") {
                mlc.setMessageLog([])
            }
        })

        getVersion()
        fetchDeviceMetrics()
        checkAbiMismatch()

        return () => {
            mediaProjectionSubscription.remove()
            botServiceSubscription.remove()
        }
    }, [])

    /**
     * Checks if the currently selected scenario exists in the available scenarios data.
     */
    const isScenarioValid: boolean = useMemo(() => {
        return scenarios.some((it) => it.value === general.scenario)
    }, [general.scenario])

    /**
     * Fetch device metrics from NativeModule.
     */
    const fetchDeviceMetrics = async () => {
        try {
            const metrics = await StartModule.getDeviceDimensions()
            setDeviceMetrics(metrics)

            const { width, height, dpi } = metrics
            const isConfig1 = width === 1080 && height === 1920 && dpi === 240
            const isConfig2 = width === 1080 && height === 2340 && dpi === 450

            if (isConfig1 || isConfig2) {
                setUnsupportedReason(null)
            } else {
                setUnsupportedReason(`unsupported configuration: ${width}x${height} @ ${dpi} DPI`)
            }
        } catch (error) {
            logErrorWithTimestamp("[Home] Failed to fetch device dimensions:", error)
        }
    }

    /**
     * Grab the program name and version.
     */
    const getVersion = () => {
        const appName = Application.applicationName || "App"
        var version = Application.nativeApplicationVersion || "0.0.0"
        version += " (" + (Application.nativeBuildVersion || "0") + ")"
        logWithTimestamp(`Android app ${appName} version is ${version}`)
        setAppName(appName)
        setAppVersion(version)
    }

    /**
     * One-shot mount check for the x86_64-capable-but-arm64-installed mismatch. Mirrors `fetchDeviceMetrics` - load once, set state
     * once, never re-runs. Result is consumed by `renderStatus` (warning icon + tooltip section) and the pulse-animation effect.
     */
    const checkAbiMismatch = async () => {
        const caps = await loadDeviceCapabilities()
        if (shouldSuggestX8664Variant(caps)) setAbiMismatch(true)
    }

    /**
     * Saves settings then starts the native bot service. Shared by both the inline start path and the
     * post-permission-grant chain from PermissionSetupDialog.
     */
    const proceedToStart = async () => {
        logWithTimestamp("[Home] Saving settings before starting bot...")
        try {
            await saveSettings()
            logWithTimestamp("[Home] Settings saved successfully, starting bot...")
        } catch (error) {
            logErrorWithTimestamp("[Home] Failed to save settings:", error)
            setSnackbarMessage(`Failed to save settings before starting: ${error}`)
            setSnackbarOpen(true)
            return
        }
        StartModule.start()
    }

    /**
     * Handles the button press for starting or stopping the bot.
     */
    const handleButtonPress = async () => {
        if (isRunning) {
            StartModule.stop()
            return
        }
        if (!readyStatus) {
            setShowNotReadyDialog(true)
            return
        }

        // Gate the start on all 3 system permissions (accessibility, overlay, battery optimization).
        // If any are missing, surface the unified PermissionSetupDialog and let it chain back into proceedToStart.
        try {
            const [accessibility, overlay, battery] = await Promise.all([StartModule.getAccessibilityStatus(), StartModule.getOverlayStatus(), StartModule.getBatteryOptimizationStatus()])
            const allGranted = accessibility.enabled && accessibility.active && overlay.enabled && battery.enabled
            if (!allGranted) {
                setShowPermissionDialog(true)
                return
            }
        } catch (error) {
            logErrorWithTimestamp("[Home] Failed to check permission statuses:", error)
        }

        await proceedToStart()
    }

    /** Gets the appropriate icon name for the SelectButton based on device state. */
    const getSelectButtonIconName = (): React.ComponentProps<typeof Ionicons>["name"] | undefined => {
        if (!isScenarioValid) {
            return undefined
        } else if (isRunning) {
            return "stop-outline"
        } else {
            return "play-outline"
        }
    }

    /** Gets the SelectButton variant based on device state. */
    const getSelectButtonVariant = (): any => {
        if (isRunning) {
            // Not an error, but we want the button to be red to indicate that
            // pressing it will stop the service.
            // Must come first because we always want the button to be red
            // if the bot is running, regardless of the other conditions.
            return "error"
        } else if (unsupportedReason !== null || abiMismatch) {
            return "warning"
        } else if (deviceMetrics === null) {
            return "warning"
        } else if (isScenarioValid) {
            return "success"
        } else {
            return "primary"
        }
    }

    /** Returns a status indicator based on the device state. */
    const renderStatus = (): React.ReactElement | null => {
        const warningSections: string[] = []
        if (unsupportedReason) {
            warningSections.push(`Current Display: ${deviceMetrics?.width}x${deviceMetrics?.height} (${deviceMetrics?.dpi} DPI).

Warning: Performance may be degraded due to ${unsupportedReason}.

Supported Configurations:
• 1080x1920 @ 240 DPI
• 1080x2340 @ 450 DPI

Note: Height is not as important to meet as the width. In addition, DPI is tied to the width and height together. How to calculate your specific DPI:

DPI = sqrt(width^2 + height^2) / diagonal

where width and height of the screen is in pixels, and diagonal is the diagonal size of the physical screen in inches.`)
        }
        if (abiMismatch) {
            warningSections.push(`Installed Build: arm64-v8a
Device Supports: x86_64

Warning: The arm64 build runs through Android's binary translator on this device, which is significantly slower than running natively.

Note: Reinstall using the x86_64 release APK for much better performance.`)
        }
        const warningText = warningSections.join("\n\n----\n\n")

        if (unsupportedReason || abiMismatch) {
            return (
                <Tooltip delayDuration={150}>
                    <TooltipTrigger>
                        <Animated.View style={{ transform: [{ scale: pulseAnim }] }}>
                            <Ionicons name="alert-circle-outline" size={24} color={colors.warning} />
                        </Animated.View>
                    </TooltipTrigger>
                    <TooltipContent sideOffset={12} side="bottom" style={{ maxWidth: 350, backgroundColor: colors.warningBg, borderColor: colors.warningBorder, borderWidth: 1 }}>
                        <Text style={{ color: colors.warningText }}>{warningText}</Text>
                    </TooltipContent>
                </Tooltip>
            )
        }

        if (!readyStatus && !isRunning) {
            return (
                <Tooltip delayDuration={150}>
                    <TooltipTrigger>
                        <Ionicons name="information-circle-outline" size={24} color={colors.info} />
                    </TooltipTrigger>
                    <TooltipContent sideOffset={12} side="bottom" style={{ width: 200 }}>
                        <Text>Select a Scenario to start from the selector button dropdown.</Text>
                    </TooltipContent>
                </Tooltip>
            )
        }

        if (deviceMetrics) {
            return (
                <Tooltip delayDuration={150}>
                    <TooltipTrigger>
                        <Ionicons name="checkmark-circle-outline" size={24} color={colors.success} />
                    </TooltipTrigger>
                    <TooltipContent sideOffset={12} side="bottom">
                        <Text>Everything looks good and ready to go!</Text>
                    </TooltipContent>
                </Tooltip>
            )
        }

        return null
    }

    // Map the existing bot state to the hero card's status pill. Running takes priority. Warnings (unsupported display
    // or ABI mismatch) surface as "error". An unselected scenario lands on "stopped". Otherwise the bot is "ready".
    const heroStatus: HeroStatus = isRunning ? "running" : unsupportedReason !== null || abiMismatch ? "error" : readyStatus && deviceMetrics !== null ? "ready" : "stopped"
    const heroProfile = currentProfileName ?? DEFAULT_PROFILE_NAME

    // Derive the hero data from the settings slices. The SRS/Debug/Test chips and the Plans/Priority rows render only when active; the Style chip is always shown on the status line.
    const activeTest = useMemo(() => findActiveDebugTest(debug), [debug])
    const { names: planNames, spThreshold } = useMemo(() => activeSkillPlans(skills), [skills])
    const statPriority = useMemo(() => abbreviateStatPriority(training.statPrioritization ?? []), [training.statPrioritization])
    const raceStyle = raceStrategyLabel(racing)
    // The Style chip and the armed-test chip highlight a row whose id depends on current state, so resolve those targets here rather than in the static route map.
    const raceStyleTargetId = raceStrategyTargetId(racing)
    // The glance zone holds only the Plans and Priority rows now, so omit it (and its divider) when neither has content.
    const showGlance = planNames.length > 0 || statPriority.length > 0
    const handleHeroNavigate = useCallback(
        (target: HeroGlanceTarget) => {
            const { screen, params } = HERO_NAV_ROUTES[target]
            // Merge the state-dependent highlight target for chips whose row varies (the armed test's row, the race-strategy row).
            const dynamicTargetId = target === "debugTest" ? activeTest?.searchId : target === "racing" ? raceStyleTargetId : undefined
            const finalParams = dynamicTargetId ? { ...params, targetId: dynamicTargetId } : params
            navigation.dispatch(CommonActions.navigate({ name: "Settings", params: { screen, params: finalParams, initial: false } }))
        },
        [navigation, activeTest, raceStyleTargetId]
    )

    return (
        <View style={styles.root}>
            {/* MessageLog uses FlashList, which doesn't support sticky headers the same way as ScrollView, so PageHeader stays a sibling above (non-sticky). */}
            <PageHeader title="Home" showHomeButton={false} style={{ width: "100%" }} rightComponent={renderStatus()} />

            <View style={styles.hero}>
                <HeroStatusCard
                    status={heroStatus}
                    profile={heroProfile}
                    chips={
                        <HeroChips debugMode={debug.enableDebugMode} activeTest={activeTest?.name ?? null} srs={racing.enableSmartRaceSolver} raceStyle={raceStyle} onNavigate={handleHeroNavigate} />
                    }
                    glance={showGlance ? <HeroGlance planNames={planNames} spThreshold={spThreshold} priority={statPriority} onNavigate={handleHeroNavigate} /> : undefined}
                    cta={
                        <SelectButton
                            variant={getSelectButtonVariant()}
                            iconName={getSelectButtonIconName()}
                            options={scenarios}
                            placeholder={deviceMetrics ? "Select a Scenario" : "Not Ready"}
                            value={general.scenario}
                            onValueChange={(value) => {
                                const newScenario = value || ""
                                updateGeneral({ scenario: newScenario })
                                setReadyStatus(newScenario !== "")
                            }}
                            onPress={handleButtonPress}
                        />
                    }
                />
            </View>

            <View style={styles.contentContainer}>
                <View style={styles.logBody}>
                    <MessageLog />
                </View>
            </View>

            <AlertDialog open={showNotReadyDialog} onOpenChange={setShowNotReadyDialog}>
                <AlertDialogContent onDismiss={() => setShowNotReadyDialog(false)}>
                    <AlertDialogHeader>
                        <AlertDialogTitle>Not Ready</AlertDialogTitle>
                        <AlertDialogDescription>A scenario must be selected before starting the bot. Tap the dropdown on this Start button to pick one.</AlertDialogDescription>
                    </AlertDialogHeader>
                    <AlertDialogFooter>
                        <AlertDialogAction onPress={() => setShowNotReadyDialog(false)}>
                            <Text>OK</Text>
                        </AlertDialogAction>
                    </AlertDialogFooter>
                </AlertDialogContent>
            </AlertDialog>

            <PermissionSetupDialog open={showPermissionDialog} onOpenChange={setShowPermissionDialog} onAllGranted={proceedToStart} />

            <Snackbar
                visible={snackbarOpen}
                onDismiss={() => setSnackbarOpen(false)}
                action={{
                    label: "Close",
                    onPress: () => {
                        setSnackbarOpen(false)
                    },
                }}
                style={{ backgroundColor: colors.error, borderRadius: 10 }}
            >
                {snackbarMessage}
            </Snackbar>
        </View>
    )
}

export default Home
