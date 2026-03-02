import React, { useMemo, useContext, useState, useRef, useCallback, FC, ReactNode } from "react"
import { View, Text, ScrollView, StyleSheet, TouchableOpacity, TextInput, Image, Modal, Dimensions } from "react-native"
import { FlashList, ListRenderItem } from "@shopify/flash-list"
import { Search, X, Trash2 } from "lucide-react-native"
import { Divider, Snackbar } from "react-native-paper"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext, defaultSettings } from "../../context/BotStateContext"
import CustomTitle from "../../components/CustomTitle"
import CustomSelect from "../../components/CustomSelect"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomButton from "../../components/CustomButton"
import PageHeader from "../../components/PageHeader"
import WarningContainer from "../../components/WarningContainer"
import { SearchPageProvider } from "../../context/SearchPageContext"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import skillsData from "../../data/skills.json"
import icons from "../SkillSettings/icons"

const MAX_SKILLS_IN_LIST: number = 10

/**
 * Represents a skill entry from the `skills.json` data file.
 */
interface Skill {
    /** The unique skill ID. */
    id: number
    /** The skill ID for the inherited version of the skill. Same as ID if skill can't be inherited. */
    gene_id: number
    /** The English display name of the skill. */
    name_en: string
    /** The English description of the skill. */
    desc_en: string
    /** The icon ID used for rendering the skill icon. */
    icon_id: number
    /** The skill point cost to purchase this skill. */
    cost: number
    /** The evaluated point value of the skill. */
    eval_pt: number
    /** The point-to-cost ratio for ranking efficiency. */
    pt_ratio: number
    /** The rarity tier of the skill. */
    rarity: number
    /** The activation condition string for the skill. */
    condition: string
    /** The precondition string that must be met before activation. */
    precondition: string
    /** Whether this is an inherited unique skill. */
    inherited: boolean
    /** The community tier list rating, or null if unrated. */
    community_tier: number | null
    /** The game version numbers where this skill is available. */
    versions: number[]
    /** The ID of the upgraded version of this skill, or null. */
    upgrade: number | null
    /** The ID of the downgraded version of this skill, or null. */
    downgrade: number | null
}

/**
 * Props for the `SkillPlanSettings` component.
 * Each instance configures a specific skill plan (e.g. `skillPointCheck`, `preFinals`, `careerComplete`).
 */
export interface SkillPlanSettingsProps {
    /** The key identifying this plan in the settings object. */
    planKey: string
    /** The navigation name for this plan's screen. */
    name: string
    /** The display title for this plan. */
    title: string
    /** The description shown at the top of the plan page. */
    description: string
}

/**
 * Dynamic map of plan keys to their settings page props.
 */
export interface DynamicSkillPlanSettingsProps {
    [key: string]: SkillPlanSettingsProps
}

/** Registry of all available skill plan settings pages and their configuration. */
export const skillPlanSettingsPages: DynamicSkillPlanSettingsProps = {
    skillPointCheck: {
        planKey: "skillPointCheck",
        name: "SkillPlanSettingsSkillPointCheck",
        title: "Skill Point Check",
        description:
            "Configure the skills to buy when the skill point threshold has been reached.\n\nEvaluated ratings are sourced from Umamusume Wiki and community tier list ratings are sourced from Game8.",
    },
    preFinals: {
        planKey: "preFinals",
        name: "SkillPlanSettingsPreFinals",
        title: "Pre-Finals",
        description: "Configure the skills to buy just before the finale season.\n\nEvaluated ratings are sourced from Umamusume Wiki and community tier list ratings are sourced from Game8.",
    },
    careerComplete: {
        planKey: "careerComplete",
        name: "SkillPlanSettingsCareerComplete",
        title: "Career Complete",
        description: "Configure the skills to buy after the career has completed.\n\nEvaluated ratings are sourced from Umamusume Wiki and community tier list ratings are sourced from Game8.",
    },
}

interface SkillItemCardProps {
    item: Skill
    onPress?: () => void
    children?: ReactNode
}

// Convert skills.json to array.
const skillData: Skill[] = Object.values(skillsData)

const SkillItemCard: FC<SkillItemCardProps> = ({ item, onPress, children }) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                skillItemCard: {
                    paddingVertical: 12,
                    paddingHorizontal: 12,
                    borderRadius: 8,
                    borderWidth: 1,
                    marginBottom: 10,
                    backgroundColor: colors.card,
                    borderColor: colors.border,
                },
                skillItemHeader: {
                    flexDirection: "row",
                    alignItems: "flex-start",
                    justifyContent: "space-between",
                },
                skillItemIcon: {
                    width: 64,
                    height: 64,
                    marginRight: 8,
                },
                skillItemName: {
                    fontSize: 16,
                    fontWeight: "600",
                    color: colors.foreground,
                },
                skillItemDescription: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginTop: 4,
                },
                skillItemSubtext: {
                    fontSize: 14,
                    color: colors.primary,
                    marginTop: 4,
                },
            }),
        [colors]
    )
    return (
        <TouchableOpacity style={styles.skillItemCard} onPress={onPress}>
            <View style={styles.skillItemHeader}>
                <Image source={icons[item.icon_id]} style={styles.skillItemIcon} />
                <View style={{ flex: 1 }}>
                    <Text style={styles.skillItemName}>{item.name_en}</Text>
                    <Text style={styles.skillItemDescription}>{item.desc_en}</Text>
                    <Text style={styles.skillItemSubtext}>ID: {item.id}</Text>
                </View>
                {children}
            </View>
        </TouchableOpacity>
    )
}

/**
 * The Skill Plan Settings page.
 * Configures a specific skill plan's purchasing strategy, inherited/negative skill options,
 * and a searchable list of skills to add to the plan.
 * @param planKey - The key identifying this plan in the settings object.
 * @param name - The navigation name for this plan's screen.
 * @param title - The display title for this plan.
 * @param description - The description shown at the top of the plan page.
 */
const SkillPlanSettings: FC<SkillPlanSettingsProps> = ({ planKey, name, title, description }) => {
    usePerformanceLogging(name)
    const { colors } = useTheme()
    const bsc = useContext(BotStateContext)

    const { settings, setSettings } = bsc

    // Merge current skills settings with defaults to handle missing properties.
    const combinedConfig = { ...defaultSettings.skills.plans, ...settings.skills.plans }

    const { enabled, strategy, enableBuyInheritedUniqueSkills, enableBuyNegativeSkills, plan } = combinedConfig[planKey]

    const [searchModalVisible, setSearchModalVisible] = useState(false)
    const [selectedSkillsModalVisible, setSelectedSkillsModalVisible] = useState(false)
    const [searchQuery, setSearchQuery] = useState("")
    const [snackbarVisible, setSnackbarVisible] = useState(false)
    const [snackbarMessage, setSnackbarMessage] = useState("")
    // Modal list data used to ensure data immutability when modifying lists.
    const [searchModalData, setSearchModalData] = useState<Skill[]>(skillData)
    const [selectedSkillsModalData, setSelectedSkillsModalData] = useState<Skill[]>([])

    const snackbarTimeoutRef = useRef<NodeJS.Timeout | null>(null)
    const scrollViewRef = useRef<ScrollView>(null)

    // Parse skill plan from CSV string.
    const planIds: number[] = useMemo(() => {
        return plan && plan !== "" && typeof plan === "string" ? plan.split(",").map((s) => Number(s)) : []
    }, [plan])

    React.useEffect(() => {
        const availableSkills: Skill[] = skillData.filter((item: Skill) => !planIds.includes(item.id))
        if (!searchQuery.trim()) {
            setSearchModalData(availableSkills)
            return
        }

        const query = searchQuery.toLowerCase()
        setSearchModalData(availableSkills.filter((item: Skill) => item.name_en.toLowerCase().includes(query)))
    }, [searchQuery, planIds, skillData])

    React.useEffect(() => {
        setSelectedSkillsModalData(skillData.filter((item: Skill) => planIds.includes(item.id)))
    }, [planIds, skillData])

    const keyExtractor = useCallback((item: Skill) => `${item.id.toString()}${item.name_en}`, [])

    /** Update a skill plan setting.
     *
     * @param key The key of the setting to update.
     * @param value The value to set the setting to.
     */
    const updateSkillsSetting = useCallback(
        (key: string, value: any) => {
            setSettings({
                ...bsc.settings,
                skills: {
                    ...bsc.settings.skills,
                    plans: {
                        ...bsc.settings.skills.plans,
                        [planKey]: {
                            ...bsc.settings.skills.plans[planKey],
                            [key]: value,
                        },
                    },
                },
            })
        },
        [bsc.settings, planKey, setSettings]
    )

    /** Displays a snackbar popup with a message.
     *
     * @param msg - The message to display in the snackbar.
     */
    const showSnackbar = useCallback(
        (msg: string) => {
            if (!searchModalVisible) {
                return
            }

            if (snackbarTimeoutRef.current) {
                clearTimeout(snackbarTimeoutRef.current)
            }

            setSnackbarMessage(msg)
            setSnackbarVisible(true)

            snackbarTimeoutRef.current = setTimeout(() => {
                setSnackbarVisible(false)
            }, 2000)
        },
        [searchModalVisible, snackbarTimeoutRef, setSnackbarMessage, setSnackbarVisible]
    )

    /** Removes a skill from the planned skill IDs.
     *
     * @param skill - The Skill interface to remove.
     */
    const removeSkillFromPlan = useCallback(
        (skill: Skill) => {
            const newPlanIds: number[] = planIds.filter((id) => id !== skill.id)

            // Update the racing plan with the changes.
            updateSkillsSetting("plan", newPlanIds.join(","))
        },
        [planIds, updateSkillsSetting]
    )

    /** Adds a skill to the planned skill IDs.
     *
     * @param skill - The Skill interface to add.
     */
    const addSkillToPlan = useCallback(
        (skill: Skill) => {
            if (planIds.includes(skill.id)) {
                return
            }

            const newPlanIds: number[] = [...planIds, skill.id]

            // Update the racing plan with the changes.
            updateSkillsSetting("plan", newPlanIds.join(","))

            showSnackbar("Added skill to plan: " + skill.name_en)
        },
        [planIds, updateSkillsSetting, showSnackbar]
    )

    /** Remove all skills from the current skill plan. */
    const clearAllSkillsFromPlan = useCallback(() => {
        updateSkillsSetting("plan", "")
    }, [updateSkillsSetting])

    /** Hides the snackbar and resets it whenever it is dismissed. */
    const onDismissSnackbar = useCallback(() => {
        setSnackbarVisible(false)
        setSnackbarMessage("")
        if (snackbarTimeoutRef.current) {
            clearTimeout(snackbarTimeoutRef.current)
        }
    }, [setSnackbarVisible, setSnackbarMessage, snackbarTimeoutRef])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: {
                    flex: 1,
                    flexDirection: "column",
                    margin: 10,
                    backgroundColor: colors.background,
                },
                description: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginBottom: 16,
                    lineHeight: 20,
                },
                section: {
                    marginBottom: 24,
                },
                sectionTitle: {
                    fontSize: 18,
                    fontWeight: "600",
                    color: colors.foreground,
                    marginBottom: 12,
                },
                skillItemCard: {
                    paddingVertical: 12,
                    paddingHorizontal: 12,
                    borderRadius: 8,
                    borderWidth: 1,
                    marginBottom: 10,
                    backgroundColor: colors.card,
                    borderColor: colors.border,
                },
                skillItemHeader: {
                    flexDirection: "row",
                    alignItems: "flex-start",
                    justifyContent: "space-between",
                },
                skillItemIcon: {
                    width: 64,
                    height: 64,
                    marginRight: 8,
                },
                skillItemName: {
                    fontSize: 16,
                    fontWeight: "600",
                    color: colors.foreground,
                },
                skillItemDescription: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginTop: 4,
                },
                skillItemSubtext: {
                    fontSize: 14,
                    color: colors.primary,
                    marginTop: 4,
                },
                input: {
                    borderWidth: 1,
                    borderColor: colors.border,
                    borderRadius: 8,
                    padding: 12,
                    fontSize: 16,
                    color: colors.foreground,
                    backgroundColor: colors.background,
                    marginBottom: 12,
                },
                inputLabel: {
                    fontSize: 16,
                    color: colors.foreground,
                    marginBottom: 8,
                },
                inputDescription: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginTop: 8,
                },
                inputContainer: {
                    marginBottom: 16,
                },
                modalOverlay: {
                    flex: 1,
                    backgroundColor: "rgba(0, 0, 0, 0.5)",
                    justifyContent: "center",
                    alignItems: "center",
                },
                modalContent: {
                    backgroundColor: colors.background,
                    borderRadius: 16,
                    padding: 20,
                    width: Dimensions.get("window").width * 0.9,
                    maxHeight: Dimensions.get("window").height * 0.8,
                    flexDirection: "column",
                    justifyContent: "flex-start",
                },
                modalHeader: {
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "center",
                    marginBottom: 20,
                },
                modalTitle: {
                    fontSize: 20,
                    fontWeight: "bold",
                    color: colors.foreground,
                },
                closeButton: {
                    padding: 8,
                },
                searchContainer: {
                    flexDirection: "row",
                    alignItems: "center",
                    backgroundColor: colors.card,
                    borderWidth: 1,
                    borderColor: colors.border,
                    borderRadius: 8,
                    paddingHorizontal: 12,
                    marginBottom: 20,
                },
                searchInput: {
                    flex: 1,
                    paddingVertical: 12,
                    color: colors.foreground,
                    fontSize: 12,
                    backgroundColor: "transparent",
                },
                clearSearchButton: {
                    padding: 8,
                    marginLeft: 8,
                },
                searchSkillsList: {
                    height: 400,
                    minHeight: 400,
                },
                noResults: {
                    textAlign: "center",
                    color: colors.foreground,
                    opacity: 0.6,
                    padding: 20,
                },
                removeButton: {
                    padding: 4,
                },
            }),
        [colors]
    )

    /** Renders the options for this page. */
    const renderOptions = useCallback(() => {
        return (
            <>
                <View style={styles.inputContainer}>
                    <CustomCheckbox
                        searchId={`enable-buy-inherited-unique-skills-${name}`}
                        checked={enableBuyInheritedUniqueSkills}
                        onCheckedChange={(checked) => updateSkillsSetting("enableBuyInheritedUniqueSkills", checked)}
                        label="Purchase All Inherited Unique Skills"
                        description={"When enabled, the bot will attempt to purchase all inherited unique skills regardless of their evaluated rating or community tier list rating."}
                        style={{ marginTop: 16 }}
                    />
                    <CustomCheckbox
                        searchId={`enable-buy-negative-skills-${name}`}
                        checked={enableBuyNegativeSkills}
                        onCheckedChange={(checked) => updateSkillsSetting("enableBuyNegativeSkills", checked)}
                        label="Purchase All Negative Skills"
                        description={"When enabled, the bot will attempt to purchase all negative skills (i.e. Firm Conditions ×)."}
                        style={{ marginTop: 16 }}
                    />
                </View>
                <View style={styles.inputContainer}>
                    <Text style={styles.inputLabel}>Automated Skill Point Spending Strategy</Text>
                    <CustomSelect
                        options={[
                            { value: "default", label: "Do Not Spend Remaining Points" },
                            { value: "optimize_skills", label: "Best Skills First" },
                            { value: "optimize_rank", label: "Optimize Rank" },
                        ]}
                        value={strategy}
                        defaultValue={defaultSettings.skills.plans[planKey].strategy}
                        onValueChange={(value) => updateSkillsSetting("strategy", value)}
                        placeholder="Select Strategy"
                    />
                    {strategy == "optimize_rank" && <WarningContainer>⚠️ Warning: Optimize Rank ignores any of the Skill Style Overrides set in the Skill Settings page.</WarningContainer>}
                    <Text style={styles.inputDescription}>
                        This option determines what the bot does with any remaining skill points after it has purchased all of the skills from the Planned Skills section and the other options on this
                        page.
                    </Text>
                    <Text style={styles.inputDescription}>
                        Best Skills First will use a community skill tier list to purchase better skills first and then within each tier it will attempt to optimize rank since the skills within each
                        tier are not ordered.
                    </Text>
                    <Text style={styles.inputDescription}>
                        Optimize Rank will purchase skills in a way which will result in the highest trainee rank. Avoid this option if you wish to train an uma up for TT or CM.
                    </Text>
                </View>
            </>
        )
    }, [defaultSettings, updateSkillsSetting, planKey, strategy, enableBuyInheritedUniqueSkills, enableBuyNegativeSkills])

    /** Renders a single skill item in a list. */
    const renderSelectedSkillItem = useCallback(
        (item: Skill) => (
            <SkillItemCard item={item}>
                <TouchableOpacity
                    onPress={(e) => {
                        e.stopPropagation()
                        removeSkillFromPlan(item)
                    }}
                    style={styles.removeButton}
                >
                    <X size={20} color={colors.destructive} />
                </TouchableOpacity>
            </SkillItemCard>
        ),
        [removeSkillFromPlan]
    )

    /** Renders a single skill item in the Selected Skills modal.
     *
     * @param item - The Skill interface of the item to render.
     */
    const renderSelectedSkillsModalSkillItem: ListRenderItem<Skill> = useCallback(
        ({ item }: { item: Skill }) => {
            return renderSelectedSkillItem(item)
        },
        [renderSelectedSkillItem]
    )

    /** Renders a single skill item in the Search skills modal.
     *
     * @param item - The Skill interface of the item to render.
     */
    const renderSearchModalSkillItem: ListRenderItem<Skill> = useCallback(({ item }: { item: Skill }) => <SkillItemCard item={item} onPress={() => addSkillToPlan(item)} />, [addSkillToPlan])

    /** Renders the list of all selected skills. */
    const renderSelectedSkillsList = useCallback(
        () => (
            <View style={styles.section}>
                <View
                    style={{
                        flexDirection: "row",
                        alignItems: "center",
                        justifyContent: "space-between",
                        gap: 8,
                        marginBottom: 12,
                    }}
                >
                    <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground }}>Current Skills ({selectedSkillsModalData.length})</Text>
                    <CustomButton icon={<Trash2 size={16} />} onPress={clearAllSkillsFromPlan} variant={selectedSkillsModalData.length <= 0 ? "outline" : "destructive"}>
                        Clear
                    </CustomButton>
                </View>
                {selectedSkillsModalData.slice(0, MAX_SKILLS_IN_LIST).map((item) => renderSelectedSkillItem(item))}
                {selectedSkillsModalData.length > MAX_SKILLS_IN_LIST && (
                    <View style={styles.section}>
                        <CustomButton
                            onPress={() => {
                                setSelectedSkillsModalVisible(true)
                            }}
                            variant="default"
                        >
                            {selectedSkillsModalData.length - MAX_SKILLS_IN_LIST} more skills not displayed. Click to view all skills in plan.
                        </CustomButton>
                    </View>
                )}
            </View>
        ),
        [selectedSkillsModalData, clearAllSkillsFromPlan, renderSelectedSkillItem, setSelectedSkillsModalVisible]
    )

    /** Renders the modal of all selected skills. */
    const renderSelectedSkillsModal = useCallback(
        () => (
            <Modal
                animationType="slide"
                transparent={true}
                visible={selectedSkillsModalVisible}
                onRequestClose={() => {
                    setSelectedSkillsModalVisible(false)
                }}
            >
                <TouchableOpacity style={styles.modalOverlay} activeOpacity={1} onPress={() => setSelectedSkillsModalVisible(false)}>
                    <TouchableOpacity style={styles.modalContent} activeOpacity={1} onPress={(e) => e.stopPropagation()}>
                        <View style={styles.modalHeader}>
                            <Text style={styles.modalTitle}>Selected Skills in Plan</Text>
                            <TouchableOpacity style={styles.closeButton} onPress={() => setSelectedSkillsModalVisible(false)}>
                                <X size={24} color={colors.foreground} />
                            </TouchableOpacity>
                        </View>

                        <View style={styles.searchSkillsList}>
                            <FlashList
                                data={selectedSkillsModalData}
                                renderItem={renderSelectedSkillsModalSkillItem}
                                keyExtractor={keyExtractor}
                                keyboardShouldPersistTaps="always"
                                ListEmptyComponent={
                                    <View style={{ padding: 20 }}>
                                        <Text style={styles.noResults}>{selectedSkillsModalData.length === 0 && "No skills selected in plan."}</Text>
                                    </View>
                                }
                            />
                        </View>
                    </TouchableOpacity>
                </TouchableOpacity>
            </Modal>
        ),
        [selectedSkillsModalData, selectedSkillsModalVisible, setSelectedSkillsModalVisible, renderSelectedSkillsModalSkillItem, keyExtractor]
    )

    /** Renders the Skill Selection modal. */
    const renderSkillSelectionModal = useCallback(
        () => (
            <Modal
                animationType="slide"
                transparent={true}
                visible={searchModalVisible}
                onRequestClose={() => {
                    setSearchModalVisible(false)
                    onDismissSnackbar()
                }}
            >
                <TouchableOpacity style={styles.modalOverlay} activeOpacity={1} onPress={() => setSearchModalVisible(false)}>
                    <TouchableOpacity style={styles.modalContent} activeOpacity={1} onPress={(e) => e.stopPropagation()}>
                        <View style={styles.modalHeader}>
                            <Snackbar
                                visible={snackbarVisible}
                                onDismiss={onDismissSnackbar}
                                action={{
                                    label: "Close",
                                    onPress: () => {
                                        onDismissSnackbar()
                                    },
                                }}
                                style={{ backgroundColor: "#388e3c", borderRadius: 10 }}
                                duration={Number.POSITIVE_INFINITY}
                            >
                                {snackbarMessage}
                            </Snackbar>
                            <Text style={styles.modalTitle}>Select Skill</Text>
                            <TouchableOpacity style={styles.closeButton} onPress={() => setSearchModalVisible(false)}>
                                <X size={24} color={colors.foreground} />
                            </TouchableOpacity>
                        </View>

                        <View style={styles.searchContainer}>
                            <Search size={20} color={colors.foreground} />
                            <TextInput
                                style={styles.searchInput}
                                placeholder="Search by skill name..."
                                placeholderTextColor={colors.mutedForeground}
                                value={searchQuery}
                                onChangeText={setSearchQuery}
                            />
                            {searchQuery.length > 0 && (
                                <TouchableOpacity style={styles.clearSearchButton} onPress={() => setSearchQuery("")}>
                                    <X size={16} color={colors.foreground} />
                                </TouchableOpacity>
                            )}
                        </View>

                        <View style={styles.searchSkillsList}>
                            <FlashList
                                data={searchModalData}
                                renderItem={renderSearchModalSkillItem}
                                keyExtractor={keyExtractor}
                                keyboardShouldPersistTaps="always"
                                ListEmptyComponent={
                                    <View style={{ padding: 20 }}>
                                        <Text style={styles.noResults}>No skills match your search.</Text>
                                    </View>
                                }
                            />
                        </View>
                    </TouchableOpacity>
                </TouchableOpacity>
            </Modal>
        ),
        [searchModalData, searchModalVisible, setSearchModalVisible, onDismissSnackbar, snackbarVisible, searchQuery, setSearchQuery, renderSearchModalSkillItem, keyExtractor]
    )

    return (
        <View style={styles.root}>
            <PageHeader title={`${title} Plan`} />
            <SearchPageProvider page={name} scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <Text style={styles.description}>{description}</Text>
                        <Divider style={{ marginBottom: 16 }} />
                        <CustomCheckbox
                            searchId={`enable-skill-plan-${planKey}`}
                            checked={enabled}
                            onCheckedChange={(checked) => updateSkillsSetting("enabled", checked)}
                            label={`Enable ${title} Plan (Beta)`}
                            description={"When enabled, the bot will attempt to purchase skills based on the following configuration."}
                        />
                        {enabled && (
                            <View style={styles.section}>
                                {renderOptions()}
                                <Divider style={{ marginBottom: 16 }} />
                                <View style={styles.section}>
                                    <CustomTitle searchId={`skill-plan-settings-${planKey}`} title="Planned Skills" description="Select skills that the bot will always attempt to buy." />
                                    <CustomButton
                                        onPress={() => {
                                            onDismissSnackbar()
                                            setSearchModalVisible(true)
                                        }}
                                        variant="default"
                                    >
                                        Search Skills
                                    </CustomButton>
                                </View>
                                {renderSelectedSkillsList()}
                            </View>
                        )}
                    </View>
                </ScrollView>
            </SearchPageProvider>
            {renderSkillSelectionModal()}
            {renderSelectedSkillsModal()}
        </View>
    )
}

export default React.memo(SkillPlanSettings)
