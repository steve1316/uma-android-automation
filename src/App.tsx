import { NavigationContainer } from "@react-navigation/native"
import { createDrawerNavigator } from "@react-navigation/drawer"
import { createNativeStackNavigator } from "@react-navigation/native-stack"
import { useCallback } from "react"
import { PortalHost } from "@rn-primitives/portal"
import { StatusBar } from "expo-status-bar"
import { SafeAreaView, SafeAreaProvider } from "react-native-safe-area-context"
import { BotStateProvider } from "./context/BotStateContext"
import { MessageLogProvider } from "./context/MessageLogContext"
import { SettingsProvider } from "./context/SettingsContext"
import { ThemeProvider, useTheme } from "./context/ThemeContext"
import { SearchProvider } from "./context/SearchRegistryContext"
import { ProfileProvider } from "./context/ProfileContext"
import { useBootstrap } from "./hooks/useBootstrap"
import Home from "./pages/Home"
import Settings from "./pages/Settings"
import TrainingSettings from "./pages/TrainingSettings"
import TrainingEventSettings from "./pages/TrainingEventSettings"
import OCRSettings from "./pages/OCRSettings"
import RacingSettings from "./pages/RacingSettings"
import RacingPlanSettings from "./pages/RacingPlanSettings"
import SkillSettings from "./pages/SkillSettings"
import SkillPlanSettings, { skillPlanSettingsPages } from "./pages/SkillPlanSettings"
import EventLogVisualizer from "./pages/EventLogVisualizer"
import ImportSettingsPreview from "./pages/ImportSettingsPreview"
import DebugSettings from "./pages/DebugSettings"
import DrawerContent from "./components/DrawerContent"
import { NAV_THEME } from "./lib/theme"

export const Tag = "UAA"

const Drawer = createDrawerNavigator()
const Stack = createNativeStackNavigator()

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
            <Stack.Screen name="OCRSettings" component={OCRSettings} />
            <Stack.Screen name="RacingSettings" component={RacingSettings} />
            <Stack.Screen name="RacingPlanSettings" component={RacingPlanSettings} />
            <Stack.Screen name="SkillSettings" component={SkillSettings} />
            {Object.entries(skillPlanSettingsPages).map(([key, config]) => (
                <Stack.Screen key={key} name={config.name}>
                    {(props) => <SkillPlanSettings {...props} planKey={config.planKey} name={config.name} title={config.title} description={config.description} />}
                </Stack.Screen>
            ))}
            <Stack.Screen name="EventLogVisualizer" component={EventLogVisualizer} />
            <Stack.Screen name="ImportSettingsPreview" component={ImportSettingsPreview} />
            <Stack.Screen name="DebugSettings" component={DebugSettings} />
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
                drawerStyle: {
                    width: 280,
                    backgroundColor: colors.card,
                },
                drawerActiveTintColor: colors.primary,
                drawerInactiveTintColor: colors.foreground,
                overlayColor: "rgba(0, 0, 0, 0.5)",
            }}
        >
            <Drawer.Screen name="Home" component={Home} />
            <Drawer.Screen name="Settings" component={SettingsStack} />
        </Drawer.Navigator>
    )
}

function AppWithBootstrap({ theme, colors }: { theme: string; colors: any }) {
    // Initialize app with bootstrap logic.
    useBootstrap()

    return (
        <SafeAreaView edges={["top"]} style={{ flex: 1, backgroundColor: colors.background }}>
            <NavigationContainer theme={NAV_THEME[theme as "light" | "dark"]}>
                <StatusBar style={theme === "light" ? "dark" : "light"} />
                <MainDrawer />
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
    return (
        <SafeAreaProvider>
            <ThemeProvider>
                <AppContent />
            </ThemeProvider>
        </SafeAreaProvider>
    )
}

export default App
