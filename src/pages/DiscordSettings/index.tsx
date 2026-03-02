import { useMemo, useContext, useRef, useState, useCallback } from "react"
import { View, ScrollView, StyleSheet, TextInput, Text } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext, defaultSettings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomButton from "../../components/CustomButton"
import PageHeader from "../../components/PageHeader"
import WarningContainer from "../../components/WarningContainer"
import SearchableItem from "../../components/SearchableItem"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import { NativeModules } from "react-native"

/**
 * Discord Settings page for configuring Discord bot notifications.
 *
 * Allows the user to enable Discord notifications, enter their bot token
 * and user ID, and test the connection by sending a test DM.
 */
const DiscordSettings = () => {
    usePerformanceLogging("DiscordSettings")
    const { colors, isDark } = useTheme()
    const bsc = useContext(BotStateContext)
    const scrollViewRef = useRef<ScrollView>(null)

    // Test connection state.
    const [isTesting, setIsTesting] = useState(false)
    const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)

    const { settings, setSettings } = bsc

    // Merge current Discord settings with defaults to handle missing properties.
    const discordSettings = { ...defaultSettings.discord, ...settings.discord }
    const { enableDiscordNotifications, discordToken } = discordSettings
    // Coerce to string since SQLite may store numeric IDs as numbers.
    const discordUserID = String(discordSettings.discordUserID || "")

    /**
     * Updates a single Discord setting value.
     *
     * @param key The setting key to update.
     * @param value The new value for the setting.
     */
    const updateDiscordSetting = useCallback(
        (key: keyof typeof settings.discord, value: any) => {
            setSettings({
                ...bsc.settings,
                discord: {
                    ...bsc.settings.discord,
                    [key]: value,
                },
            })
        },
        [bsc.settings, setSettings]
    )

    /**
     * Tests the Discord connection by sending a test DM via the native module.
     * Displays success or failure feedback to the user.
     */
    const handleTestConnection = useCallback(async () => {
        if (!discordToken || !discordUserID) {
            setTestResult({ success: false, message: "Please enter both a bot token and user ID." })
            return
        }

        setIsTesting(true)
        setTestResult(null)

        try {
            const result = await NativeModules.StartModule.testDiscordConnection(discordToken, discordUserID)
            setTestResult({ success: true, message: result || "Test message sent successfully!" })
        } catch (error: any) {
            setTestResult({ success: false, message: error?.message || "Failed to connect to Discord." })
        } finally {
            setIsTesting(false)
        }
    }, [discordToken, discordUserID])

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
                inputLabel: {
                    fontSize: 14,
                    fontWeight: "600",
                    color: colors.foreground,
                    marginBottom: 6,
                },
                inputDescription: {
                    fontSize: 12,
                    color: colors.foreground + "99",
                    marginBottom: 8,
                },
                textInput: {
                    borderWidth: 1,
                    borderColor: isDark ? "#444" : "#ccc",
                    borderRadius: 8,
                    padding: 12,
                    fontSize: 14,
                    color: colors.foreground,
                    backgroundColor: isDark ? "#1a1a1a" : "#f9f9f9",
                },
                textInputDisabled: {
                    opacity: 0.5,
                },
                resultContainer: {
                    marginTop: 12,
                    padding: 12,
                    borderRadius: 8,
                    borderWidth: 1,
                },
                resultSuccess: {
                    backgroundColor: isDark ? "#0a3d0a" : "#e6ffe6",
                    borderColor: isDark ? "#1a6b1a" : "#00cc00",
                },
                resultFailure: {
                    backgroundColor: isDark ? "#3d0a0a" : "#ffe6e6",
                    borderColor: isDark ? "#6b1a1a" : "#cc0000",
                },
                resultText: {
                    fontSize: 13,
                    color: colors.foreground,
                },
            }),
        [colors, isDark]
    )

    return (
        <View style={styles.root}>
            <PageHeader title="Discord Settings" />

            <SearchPageProvider page="DiscordSettings" scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        {/* Enable Discord Notifications */}
                        <View style={styles.section}>
                            <CustomCheckbox
                                checked={enableDiscordNotifications}
                                onCheckedChange={(checked) => updateDiscordSetting("enableDiscordNotifications", checked)}
                                label="Enable Discord Notifications"
                                description="When enabled, the Discord bot will send a DM notification when it stops, including the run status and any error messages."
                                className="my-2"
                                searchId="enableDiscordNotifications"
                            />
                        </View>

                        {/* Discord Bot Token */}
                        <SearchableItem
                            id="discordBotToken"
                            title="Discord Bot Token"
                            description="The token generated from the Discord Developer Portal. Your Discord bot must share a server with you."
                            style={styles.section}
                        >
                            <Text style={styles.inputLabel}>Discord Bot Token</Text>
                            <Text style={styles.inputDescription}>The token generated from the Discord Developer Portal. Your Discord bot must share a server with you.</Text>
                            <TextInput
                                style={[styles.textInput, !enableDiscordNotifications && styles.textInputDisabled]}
                                value={discordToken}
                                onChangeText={(text) => updateDiscordSetting("discordToken", text)}
                                placeholder="Enter your Discord bot token..."
                                placeholderTextColor={colors.foreground + "55"}
                                editable={enableDiscordNotifications}
                                secureTextEntry={true}
                                autoCapitalize="none"
                                autoCorrect={false}
                            />
                        </SearchableItem>

                        {/* Discord User ID */}
                        <SearchableItem
                            id="discordUserID"
                            title="Discord User ID"
                            description="Your Discord user ID. Enable Developer Mode in Discord settings, then click your name and select 'Copy User ID'."
                            style={styles.section}
                        >
                            <Text style={styles.inputLabel}>Discord User ID</Text>
                            <Text style={styles.inputDescription}>Your Discord user ID. Enable Developer Mode in Discord settings, then click your name and select 'Copy User ID'.</Text>
                            <TextInput
                                style={[styles.textInput, !enableDiscordNotifications && styles.textInputDisabled]}
                                value={discordUserID}
                                onChangeText={(text) => updateDiscordSetting("discordUserID", text)}
                                placeholder="Enter your Discord user ID..."
                                placeholderTextColor={colors.foreground + "55"}
                                editable={enableDiscordNotifications}
                                keyboardType="numeric"
                                autoCapitalize="none"
                                autoCorrect={false}
                            />
                        </SearchableItem>

                        {/* Test Connection */}
                        <View style={styles.section}>
                            <CustomButton
                                onPress={handleTestConnection}
                                variant="default"
                                disabled={!enableDiscordNotifications || !discordToken || !discordUserID || isTesting}
                                isLoading={isTesting}
                                style={{ width: 200 }}
                            >
                                {isTesting ? "Testing..." : "🔗 Test Connection"}
                            </CustomButton>

                            {/* Test result feedback. */}
                            {testResult && (
                                <View style={[styles.resultContainer, testResult.success ? styles.resultSuccess : styles.resultFailure]}>
                                    <Text style={styles.resultText}>
                                        {testResult.success ? "✅ " : "❌ "}
                                        {testResult.message}
                                    </Text>
                                </View>
                            )}
                        </View>

                        <WarningContainer>
                            <View style={{ flexDirection: "row", flexWrap: "wrap" }}>
                                <Text style={{ fontWeight: "bold", color: colors.warningText }}>⚠️ Security Note: </Text>
                                <Text style={{ fontSize: 14, color: colors.warningText, lineHeight: 20 }}>
                                    Your token is stored locally on this device and will not be included when exporting settings.
                                </Text>
                            </View>
                        </WarningContainer>
                    </View>
                </ScrollView>
            </SearchPageProvider>
        </View>
    )
}

export default DiscordSettings
