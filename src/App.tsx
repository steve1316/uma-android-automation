import { NavigationContainer } from "@react-navigation/native"
import { createDrawerNavigator } from "@react-navigation/drawer"
import { createNativeStackNavigator } from "@react-navigation/native-stack"
import { useCallback } from "react"
import { LogBox } from "react-native"
import { PortalHost } from "@rn-primitives/portal"
import { StatusBar } from "expo-status-bar"
import { SafeAreaView, SafeAreaProvider } from "react-native-safe-area-context"
import { GestureHandlerRootView } from "react-native-gesture-handler"
import { useFonts, Geist_400Regular, Geist_500Medium, Geist_600SemiBold, Geist_700Bold } from "@expo-google-fonts/geist"
import { GeistMono_400Regular, GeistMono_500Medium } from "@expo-google-fonts/geist-mono"
import { BotStateProvider } from "./context/BotStateContext"
import { MessageLogProvider } from "./context/MessageLogContext"
import { SettingsProvider } from "./context/SettingsContext"
import { ThemeProvider, useTheme } from "./context/ThemeContext"
import { SearchProvider } from "./context/SearchRegistryContext"
import { ProfileProvider } from "./context/ProfileContext"
import { useBootstrap } from "./hooks/useBootstrap"
import { useFirstRunGate } from "./hooks/useFirstRunGate"
import FirstRunWizard from "./pages/FirstRunWizard"
import Home from "./pages/Home"
import Settings from "./pages/Settings"
import Schedule from "./pages/Schedule"
import TrainingSettings from "./pages/TrainingSettings"
import TrainingEventSettings from "./pages/TrainingEventSettings"
import RacingSettings from "./pages/RacingSettings"
import Skills from "./pages/Skills"
import EventLogVisualizer from "./pages/EventLogVisualizer"
import ImportSettingsPreview from "./pages/ImportSettingsPreview"
import ScenarioOverridesSettings from "./pages/ScenarioOverridesSettings"
import DebugSettings from "./pages/DebugSettings"
import DiscordSettings from "./pages/DiscordSettings"
import LLMSettings from "./pages/LLMSettings"
import Chat from "./pages/Chat"
import DrawerContent from "./components/DrawerContent"
import { NAV_THEME } from "./lib/navTheme"

export const Tag = "UAA"

const Drawer = createDrawerNavigator()
const Stack = createNativeStackNavigator()

// Suppress deprecation warning from nativewind's transitive dependency
// (react-native-css-interop registers RN's deprecated SafeAreaView via cssInterop).
// Our own code uses SafeAreaView from react-native-safe-area-context.
LogBox.ignoreLogs(["SafeAreaView has been deprecated"])

/**
 * Stack navigator for Settings and all sub-pages.
 * This enables proper back button navigation that respects the navigation history.
 */
function SettingsStack() {
    return (
        <Stack.Navigator screenOptions={{ headerShown: false, freezeOnBlur: true }}>
            <Stack.Screen name="SettingsMain" component={Settings} />
            <Stack.Screen name="TrainingSettings" component={TrainingSettings} />
            <Stack.Screen name="TrainingEventSettings" component={TrainingEventSettings} />
            <Stack.Screen name="RacingSettings" component={RacingSettings} />
            <Stack.Screen name="ScheduleScreen" component={Schedule} initialParams={{ tab: "raceSolver" }} />
            <Stack.Screen name="Skills" component={Skills} initialParams={{ tab: "skillPointCheck" }} />
            <Stack.Screen name="EventLogVisualizer" component={EventLogVisualizer} />
            <Stack.Screen name="ImportSettingsPreview" component={ImportSettingsPreview} />
            <Stack.Screen name="ScenarioOverridesSettings" component={ScenarioOverridesSettings} />
            <Stack.Screen name="DebugSettings" component={DebugSettings} />
            <Stack.Screen name="DiscordSettings" component={DiscordSettings} />
            <Stack.Screen name="LLMSettings" component={LLMSettings} />
        </Stack.Navigator>
    )
}

function MainDrawer() {
    const { colors } = useTheme()

    // Stabilize the drawerContent callback to prevent unnecessary remounts.
    const renderDrawerContent = useCallback((props: any) => <DrawerContent {...props} />, [])

    return (
        <Drawer.Navigator
            drawerContent={renderDrawerContent}
            screenOptions={{
                headerShown: false,
                drawerType: "front",
                swipeEdgeWidth: 0,
                drawerStyle: {
                    width: 280,
                    backgroundColor: colors.card,
                },
                drawerActiveTintColor: colors.primary,
                drawerInactiveTintColor: colors.foreground,
                overlayColor: colors.glassBackdrop,
            }}
        >
            <Drawer.Screen name="Home" component={Home} />
            <Drawer.Screen name="Settings" component={SettingsStack} />
            <Drawer.Screen name="Chat" component={Chat} />
        </Drawer.Navigator>
    )
}

function AppWithBootstrap({ theme, colors }: { theme: string; colors: any }) {
    // Initialize app with bootstrap logic.
    useBootstrap()
    const { ready, isFirstRun, markComplete } = useFirstRunGate()

    if (!ready) return null

    return (
        <SafeAreaView edges={["top"]} style={{ flex: 1, backgroundColor: colors.background }}>
            <NavigationContainer theme={NAV_THEME[theme as "light" | "dark"]}>
                <StatusBar style={theme === "light" ? "dark" : "light"} />
                {isFirstRun ? <FirstRunWizard onComplete={markComplete} /> : <MainDrawer />}
                <PortalHost />
            </NavigationContainer>
        </SafeAreaView>
    )
}

function AppContent() {
    const { theme, colors } = useTheme()

    return (
        <SearchProvider>
            <BotStateProvider>
                <ProfileProvider>
                    <MessageLogProvider>
                        <SettingsProvider>
                            <AppWithBootstrap theme={theme} colors={colors} />
                        </SettingsProvider>
                    </MessageLogProvider>
                </ProfileProvider>
            </BotStateProvider>
        </SearchProvider>
    )
}

function App() {
    // Wait for Geist + Geist Mono to load before rendering navigation so the first paint uses the brand fonts. The OS splash covers this window.
    const [fontsLoaded] = useFonts({
        Geist_400Regular,
        Geist_500Medium,
        Geist_600SemiBold,
        Geist_700Bold,
        GeistMono_400Regular,
        GeistMono_500Medium,
    })

    if (!fontsLoaded) return null

    return (
        <GestureHandlerRootView style={{ flex: 1 }}>
            <SafeAreaProvider>
                <ThemeProvider>
                    <AppContent />
                </ThemeProvider>
            </SafeAreaProvider>
        </GestureHandlerRootView>
    )
}

export default App
