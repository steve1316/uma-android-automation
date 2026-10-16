import * as Application from "expo-application"
import MessageLog from "../../components/MessageLog"
import { parseRunReport, parseLiveRun, runCopyText, LiveRun, RunReport } from "../../lib/runReport"
import { copyToClipboard } from "../../lib/utils"
import { useCallback, useContext, useEffect, useMemo, useRef, useState } from "react"
import { BotMetaContext, GeneralMiscContext, DebugContext, RacingContext, SkillsContext, TrainingContext, type RunMode } from "../../context/BotStateContext"
import { useNavigation, CommonActions } from "@react-navigation/native"
import { useSettings } from "../../context/SettingsContext"
import { logWithTimestamp, logErrorWithTimestamp } from "../../lib/logger"
import { Animated, AppState, DeviceEventEmitter, Pressable, StyleSheet, View, NativeModules } from "react-native"
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
import HomeHero from "../../components/HomeHero"
import { heroView, HeroHealth } from "../../components/HomeHero/heroView"
import HeroGlance, { HeroGlanceTarget } from "../../components/HomeHero/HeroGlance"
import HeroChips, { HeroDailiesChips, HeroDailiesGlance, HeroDebugChips } from "../../components/HomeHero/HeroChips"
import { dailiesChips } from "../../components/HomeHero/heroDailiesData"
import CustomButton from "../../components/CustomButton"
import { findActiveDebugTest, activeSkillPlans, abbreviateStatPriority, raceStrategyLabel, raceStrategyTargetId } from "../../components/HomeHero/heroGlanceData"
import { useProfileContext, DEFAULT_PROFILE_NAME } from "../../context/ProfileContext"
import { SPACING } from "../../lib/spacing"
import { useStorageFolder, STORAGE_FOLDER_WARNING } from "../../hooks/useStorageFolder"
import WarningContainer from "../../components/WarningContainer"

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
    // Size of the Dailies start button until the Career one has been measured.
    heroStartFallback: {
        width: 156,
        height: 36,
    },
    storageWarning: {
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
    // The last run's result. `logInMemory` is true when it arrived this session, so View log can scroll the in-memory log.
    const [lastRun, setLastRun] = useState<{ report: RunReport; logInMemory: boolean } | null>(null)
    const [logJump, setLogJump] = useState<{ term: string; nonce: number } | null>(null)
    // The current run's stats so far, read when the app comes back to the front or the drawer opens.
    const [liveRun, setLiveRun] = useState<LiveRun | null>(null)
    // Whether a run is in progress, from the bot's start until its run-end report. The overlay alone does not count, so the run mode can change between runs.
    const [runActive, setRunActive] = useState<boolean>(false)
    // The Career start button's measured size, so the Dailies one matches it and switching the run mode does not change the hero's layout.
    const [careerStartSize, setCareerStartSize] = useState<{ width: number; height: number } | null>(null)
    // Set when Start is pressed, so the game opens once the overlay reports it is running.
    const openGameOnStart = useRef(false)
    const [showNotReadyDialog, setShowNotReadyDialog] = useState<boolean>(false)
    const [snackbarOpen, setSnackbarOpen] = useState<boolean>(false)
    const [snackbarMessage, setSnackbarMessage] = useState<string>("")
    const [deviceMetrics, setDeviceMetrics] = useState<{ width: number; height: number; dpi: number } | null>(null)
    const [unsupportedReason, setUnsupportedReason] = useState<string | null>(null)
    const [showPermissionDialog, setShowPermissionDialog] = useState<boolean>(false)
    const [abiMismatch, setAbiMismatch] = useState<boolean>(false)

    const { readyStatus, setReadyStatus, setAppName, setAppVersion } = useContext(BotMetaContext)
    const { general, dailies, updateGeneral } = useContext(GeneralMiscContext)
    const isDailies = general.runMode === "dailies"
    // A Dailies run needs no scenario, so it is ready as soon as the device is.
    const canStart = isDailies || readyStatus
    const { debug } = useContext(DebugContext)
    const { racing } = useContext(RacingContext)
    const { skills } = useContext(SkillsContext)
    const { training } = useContext(TrainingContext)
    const mlc = useContext(MessageLogDispatchContext)
    const { saveSettings } = useSettings()
    const { currentProfileName } = useProfileContext()
    const navigation = useNavigation()
    const { status: storageStatus } = useStorageFolder()

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

    /** Reads the current run's stats for the drawer. Native code returns nothing when no run is going. */
    const refreshLiveRun = useCallback(() => {
        StartModule.getLiveRun()
            .then((json: string | null) => setLiveRun(parseLiveRun(json)))
            .catch(() => setLiveRun(null))
    }, [])

    /** Brings the game to the front so the next run can start from the overlay. Shows why when the game is not installed. */
    const handleOpenGame = useCallback(() => {
        StartModule.openGame().catch((error: { message: string }) => {
            setSnackbarMessage(error.message)
            setSnackbarOpen(true)
        })
    }, [])

    /**
     * Reads whether the overlay is on, the last run's saved result, and the current run's stats from native code, so Home matches the service after an app restart or a recreated screen.
     */
    const syncWithService = () => {
        StartModule.isOverlayOn()
            .then((on: boolean) => setIsRunning(on))
            .catch((error: unknown) => logErrorWithTimestamp("[Home] Failed to read the overlay state:", error))
        StartModule.isRunActive()
            .then((active: boolean) => setRunActive(active))
            .catch((error: unknown) => logErrorWithTimestamp("[Home] Failed to read whether a run is in progress:", error))
        StartModule.getLastRun()
            .then((json: string | null) => {
                const report = parseRunReport(json)
                // Keep a result that arrived live this session, since its log is still in memory for View log.
                setLastRun((prev) => (!report ? null : prev && prev.report.endedAt === report.endedAt ? prev : { report, logInMemory: false }))
            })
            .catch((error: unknown) => logErrorWithTimestamp("[Home] Failed to read the last run:", error))
        refreshLiveRun()
    }

    useEffect(() => {
        const mediaProjectionSubscription = DeviceEventEmitter.addListener("MediaProjectionService", (data) => {
            const running = data["message"] === "Running"
            setIsRunning(running)
            // Start turns the overlay on and then brings the game up. A cancelled screen-capture prompt never sends "Running", so the flag just clears later.
            if (running && openGameOnStart.current) handleOpenGame()
            openGameOnStart.current = false
        })

        const botServiceSubscription = DeviceEventEmitter.addListener("BotService", (data) => {
            if (data["message"] === "Running") {
                mlc.setMessageLog([])
                setLastRun(null)
                setRunActive(true)
            }
        })

        const runEndedSubscription = DeviceEventEmitter.addListener("RunEnded", (data) => {
            const report = parseRunReport(data["message"])
            if (report) setLastRun({ report, logInMemory: true })
            setLiveRun(null)
            setRunActive(false)
        })

        // Catch up with the service on open and whenever the app comes back to the front, since events sent while Home's screen was being recreated are lost.
        syncWithService()
        const appStateSubscription = AppState.addEventListener("change", (state) => {
            if (state === "active") syncWithService()
        })

        getVersion()
        fetchDeviceMetrics()
        checkAbiMismatch()

        return () => {
            mediaProjectionSubscription.remove()
            botServiceSubscription.remove()
            runEndedSubscription.remove()
            appStateSubscription.remove()
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
        openGameOnStart.current = true
        StartModule.start()
    }

    /**
     * Handles the button press for starting the bot.
     */
    const handleButtonPress = async () => {
        if (!canStart) {
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

    // The overlay turning on or off changes whether there is a run to read.
    useEffect(() => {
        if (isRunning) refreshLiveRun()
        else setLiveRun(null)
    }, [isRunning, refreshLiveRun])

    /** Turns the overlay off. */
    const handleStopOverlay = useCallback(() => StartModule.stop(), [])

    /** Dismisses the result card and deletes the saved report. */
    const handleDismissRun = useCallback(() => {
        StartModule.clearLastRun()
        setLastRun(null)
    }, [])

    /** Scrolls the log to the crash line, or to the end for other outcomes. */
    const handleViewLog = useCallback(() => {
        const report = lastRun?.report
        setLogJump({ term: report?.outcome === "CRASHED" && report.error ? report.error.className : "", nonce: Date.now() })
    }, [lastRun])

    /** Copies the run's error block to the clipboard. */
    const handleCopyError = useCallback(async () => {
        if (lastRun) await copyToClipboard(runCopyText(lastRun.report, Application.nativeApplicationVersion ?? ""))
    }, [lastRun])

    /** Gets the appropriate icon name for the SelectButton based on device state. */
    const getSelectButtonIconName = (): React.ComponentProps<typeof Ionicons>["name"] | undefined => {
        return isScenarioValid ? "play-outline" : undefined
    }

    /** Gets the SelectButton variant based on device state. */
    const getSelectButtonVariant = (): any => {
        if (unsupportedReason !== null || abiMismatch) {
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

        if (!canStart && !isRunning) {
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
    const heroHealth: HeroHealth = unsupportedReason !== null || abiMismatch ? "error" : canStart && deviceMetrics !== null ? "ready" : "notReady"
    const hero = useMemo(
        () => heroView({ overlayOn: isRunning, health: heroHealth, report: lastRun?.report ?? null, live: liveRun, runActive, runMode: general.runMode }),
        [isRunning, heroHealth, lastRun, liveRun, runActive, general.runMode]
    )
    const heroProfile = currentProfileName ?? DEFAULT_PROFILE_NAME

    // Derive the hero data from the settings slices. The SRS/Debug/Test chips and the Plans/Priority rows render only when active; the Style chip is always shown on the status line.
    const activeTest = useMemo(() => findActiveDebugTest(debug), [debug])
    // A test armed for the other run mode is not run. The bot stops instead, so the Test chip names the mode it needs.
    const testNeedsMode = activeTest && activeTest.dailies !== isDailies ? (activeTest.dailies ? "Dailies" : "Career") : null
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
    /** Opens Dailies Settings at the given row. */
    const handleDailiesNavigate = useCallback(
        (targetId: string) => navigation.dispatch(CommonActions.navigate({ name: "Settings", params: { screen: "DailiesSettings", params: { targetId }, initial: false } })),
        [navigation]
    )
    const enabledDailies = useMemo(() => dailiesChips(dailies), [dailies])
    /** Saves the run mode picked on the hero switch. */
    const handleRunModeChange = useCallback((mode: RunMode) => updateGeneral({ runMode: mode }), [updateGeneral])
    const heroChips = useMemo(
        () =>
            isDailies ? (
                <>
                    <HeroDebugChips debugMode={debug.enableDebugMode} activeTest={activeTest?.name ?? null} testNeedsMode={testNeedsMode} onNavigate={handleHeroNavigate} />
                    <HeroDailiesChips chips={enabledDailies} onNavigate={handleDailiesNavigate} />
                </>
            ) : (
                <HeroChips
                    debugMode={debug.enableDebugMode}
                    activeTest={activeTest?.name ?? null}
                    testNeedsMode={testNeedsMode}
                    srs={racing.enableSmartRaceSolver}
                    raceStyle={raceStyle}
                    planNames={planNames}
                    onNavigate={handleHeroNavigate}
                />
            ),
        [isDailies, enabledDailies, handleDailiesNavigate, debug.enableDebugMode, activeTest, testNeedsMode, racing.enableSmartRaceSolver, raceStyle, planNames, handleHeroNavigate]
    )
    const heroGlance = useMemo(() => {
        if (isDailies) return <HeroDailiesGlance chips={enabledDailies} onNavigate={handleDailiesNavigate} />
        return showGlance ? <HeroGlance planNames={planNames} spThreshold={spThreshold} priority={statPriority} onNavigate={handleHeroNavigate} /> : null
    }, [isDailies, enabledDailies, handleDailiesNavigate, showGlance, planNames, spThreshold, statPriority, handleHeroNavigate])

    return (
        <View style={styles.root}>
            {/* MessageLog uses FlashList, which doesn't support sticky headers the same way as ScrollView, so PageHeader stays a sibling above (non-sticky). */}
            <PageHeader title="Home" showHomeButton={false} style={{ width: "100%" }} rightComponent={renderStatus()} />

            <View style={styles.hero}>
                <HomeHero
                    view={hero}
                    profile={heroProfile}
                    runMode={general.runMode}
                    onRunModeChange={handleRunModeChange}
                    report={lastRun?.report ?? null}
                    live={liveRun}
                    arrivedLive={lastRun?.logInMemory ?? false}
                    chips={heroChips}
                    glance={heroGlance}
                    startButton={
                        isDailies ? (
                            <CustomButton
                                size="sm"
                                style={careerStartSize ?? styles.heroStartFallback}
                                variant={heroHealth === "ready" ? "success" : "warning"}
                                icon={<Ionicons name="play-outline" size={20} color={colors.successContent} />}
                                onPress={handleButtonPress}
                            >
                                Dailies
                            </CustomButton>
                        ) : (
                            <View
                                onLayout={(event) => {
                                    const { width, height } = event.nativeEvent.layout
                                    setCareerStartSize((prev) => (prev?.width === width && prev?.height === height ? prev : { width, height }))
                                }}
                            >
                                <SelectButton
                                    size="sm"
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
                            </View>
                        )
                    }
                    onOpenGame={handleOpenGame}
                    onStopOverlay={handleStopOverlay}
                    onViewLog={handleViewLog}
                    onCopyError={handleCopyError}
                    onDismiss={handleDismissRun}
                    onDrawerOpen={refreshLiveRun}
                />
            </View>

            {(storageStatus === "default" || storageStatus === "inaccessible") && (
                <Pressable
                    style={styles.storageWarning}
                    onPress={() =>
                        navigation.dispatch(CommonActions.navigate({ name: "Settings", params: { screen: "SettingsMain", params: { targetId: "settings-storage-folder" }, initial: false } }))
                    }
                >
                    <WarningContainer style={{ marginTop: 0 }}>{STORAGE_FOLDER_WARNING[storageStatus]}</WarningContainer>
                </Pressable>
            )}

            <View style={styles.contentContainer}>
                <View style={styles.logBody}>
                    <MessageLog jumpRequest={logJump} />
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
