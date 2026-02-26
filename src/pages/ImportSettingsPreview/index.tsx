import { useMemo } from "react"
import { View, Text, ScrollView, StyleSheet } from "react-native"
import { useNavigation, useRoute, CommonActions } from "@react-navigation/native"
import { useTheme } from "../../context/ThemeContext"
import CustomButton from "../../components/CustomButton"
import { SettingsChange } from "../../hooks/useSettingsFileManager"
import { useSettings } from "../../context/SettingsContext"
import PageHeader from "../../components/PageHeader"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

/**
 * Route params passed from the settings file manager when navigating to this screen.
 */
interface ImportSettingsPreviewParams {
    changes: SettingsChange[]
    fileUri: string
}

const ImportSettingsPreview = () => {
    usePerformanceLogging("ImportSettingsPreview")
    const { colors, isDark } = useTheme()
    const navigation = useNavigation()
    const route = useRoute()
    const { importSettings } = useSettings()

    // Get the changes and fileUri from navigation params.
    const params = (route.params as ImportSettingsPreviewParams) || { changes: [], fileUri: "" }
    const changes = params.changes || []
    const fileUri = params.fileUri || ""

    // Group changes by category.
    const groupedChanges = useMemo(() => {
        return changes.reduce((acc, change) => {
            if (!acc[change.category]) {
                acc[change.category] = []
            }
            acc[change.category].push(change)
            return acc
        }, {} as Record<string, SettingsChange[]>)
    }, [changes])

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
                content: {
                    flex: 1,
                    padding: 12,
                },
                description: {
                    fontSize: 13,
                    color: colors.mutedForeground,
                    marginBottom: 16,
                    fontWeight: "500",
                },
                noChangesContainer: {
                    flex: 1,
                    justifyContent: "center",
                    alignItems: "center",
                    padding: 32,
                },
                noChangesText: {
                    fontSize: 15,
                    color: colors.mutedForeground,
                    textAlign: "center",
                    lineHeight: 22,
                },
                categorySection: {
                    marginBottom: 14,
                },
                categoryHeader: {
                    fontSize: 11,
                    fontWeight: "700",
                    color: colors.mutedForeground,
                    textTransform: "uppercase",
                    letterSpacing: 1,
                    marginBottom: 8,
                    paddingHorizontal: 2,
                },
                categoryContent: {
                    backgroundColor: colors.card,
                    borderRadius: 10,
                    borderWidth: 1,
                    borderColor: colors.border,
                    overflow: "hidden",
                    shadowColor: "#000",
                    shadowOffset: { width: 0, height: 2 },
                    shadowOpacity: 0.1,
                    shadowRadius: 4,
                    elevation: 2,
                },
                settingItem: {
                    flexDirection: "row",
                    paddingVertical: 10,
                    paddingHorizontal: 12,
                    borderBottomWidth: 0.5,
                    borderBottomColor: colors.border,
                    backgroundColor: "transparent",
                },
                settingItemLast: {
                    borderBottomWidth: 0,
                },
                settingKey: {
                    fontSize: 11,
                    fontWeight: "600",
                    color: colors.foreground,
                    width: 125,
                    marginRight: 10,
                    lineHeight: 17,
                },
                settingValues: {
                    flex: 1,
                    flexDirection: "row",
                    gap: 10,
                },
                valuePair: {
                    flex: 1,
                },
                valueLabel: {
                    fontSize: 10,
                    fontWeight: "700",
                    marginBottom: 3,
                    letterSpacing: 0.5,
                    textTransform: "uppercase",
                },
                valueText: {
                    fontSize: 11,
                    color: colors.foreground,
                    flexWrap: "wrap",
                    lineHeight: 16,
                },
                footer: {
                    paddingHorizontal: 12,
                    paddingVertical: 12,
                    borderTopWidth: 1,
                    borderTopColor: colors.border,
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "center",
                    backgroundColor: colors.background,
                },
            }),
        [colors],
    )

    const handleConfirm = async () => {
        if (fileUri) {
            await importSettings(fileUri)
        }
        // Reset the stack to SettingsMain, removing ImportSettingsPreview from history.
        navigation.dispatch(
            CommonActions.reset({
                index: 0,
                routes: [{ name: "SettingsMain" }],
            }),
        )
    }

    const handleCancel = () => {
        // Reset the stack to SettingsMain, removing ImportSettingsPreview from history.
        navigation.dispatch(
            CommonActions.reset({
                index: 0,
                routes: [{ name: "SettingsMain" }],
            }),
        )
    }

    return (
        <View style={styles.root}>
            <PageHeader title="Import Settings Preview" />

            <ScrollView style={styles.content} showsVerticalScrollIndicator={true}>
                {changes.length === 0 ? (
                    <View style={styles.noChangesContainer}>
                        <Text style={styles.noChangesText}>No settings would be changed. The imported settings are identical to your current settings.</Text>
                    </View>
                ) : (
                    <>
                        <Text style={styles.description}>
                            {changes.length} setting{changes.length !== 1 ? "s" : ""} will be changed:
                        </Text>
                        {Object.entries(groupedChanges).map(([category, categoryChanges]) => (
                            <View key={category} style={styles.categorySection}>
                                <Text style={styles.categoryHeader}>{category}</Text>
                                <View style={styles.categoryContent}>
                                    {categoryChanges.map((item, index) => (
                                        <View
                                            key={`${item.category}-${item.key}-${index}`}
                                            style={[styles.settingItem, index === categoryChanges.length - 1 && styles.settingItemLast, index % 2 === 1 && { backgroundColor: colors.muted }]}
                                        >
                                            <Text style={styles.settingKey}>{item.key}</Text>
                                            <View style={styles.settingValues}>
                                                <View style={styles.valuePair}>
                                                    <Text style={[styles.valueLabel, { color: colors.warningText }]}>Old</Text>
                                                    <Text style={styles.valueText} numberOfLines={2}>
                                                        {item.formattedOldValue}
                                                    </Text>
                                                </View>
                                                <View style={styles.valuePair}>
                                                    <Text style={[styles.valueLabel, { color: colors.primary }]}>New</Text>
                                                    <Text style={styles.valueText} numberOfLines={2}>
                                                        {item.formattedNewValue}
                                                    </Text>
                                                </View>
                                            </View>
                                        </View>
                                    ))}
                                </View>
                            </View>
                        ))}
                    </>
                )}
            </ScrollView>

            <View style={styles.footer}>
                <CustomButton onPress={handleCancel} variant="outline">
                    Cancel
                </CustomButton>
                {changes.length > 0 && (
                    <CustomButton onPress={handleConfirm} variant={isDark ? "default" : "secondary"}>
                        Confirm Import
                    </CustomButton>
                )}
            </View>
        </View>
    )
}

export default ImportSettingsPreview
