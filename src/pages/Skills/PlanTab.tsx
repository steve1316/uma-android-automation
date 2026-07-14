import React, { useMemo, useContext, useState, useCallback, useEffect } from "react"
import { View, Text, StyleSheet, Pressable, Image } from "react-native"
import { CircleCheckBig, Trash2 } from "lucide-react-native"
import { skillPlanSettingsPages } from "../SkillPlanSettings/config"
import { SkillsContext, defaultSettings } from "../../context/BotStateContext"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { Section } from "../../components/ui/section"
import { SectionLabel } from "../../components/ui/section-label"
import { Row } from "../../components/ui/row"
import { Switch } from "../../components/ui/switch"
import { Input } from "../../components/ui/input"
import SearchableItem from "../../components/SearchableItem"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import { ValuePill } from "../../components/ui/value-pill"
import { CountBadge } from "../../components/ui/count-badge"
import { ModalHeader } from "../../components/ui/modal-header"
import CustomButton from "../../components/CustomButton"
import CustomScrollView from "../../components/CustomScrollView"
import WarningContainer from "../../components/WarningContainer"
import skillsData from "../../data/skills.json"
import icons from "./icons"

/** Represents a skill entry from the `skills.json` data file. */
interface Skill {
    /** The unique skill ID. */
    id: number
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
    /** The activation condition string for the skill. */
    condition: string
    /** The precondition string that must be met before activation. */
    precondition: string
    /** Whether this is an inherited unique skill. */
    inherited: boolean
    /** The community tier list rating, or null if unrated. */
    community_tier: number | null
    /** The ID of the upgraded version of this skill, or null. */
    upgrade: number | null
    /** The ID of the downgraded version of this skill, or null. */
    downgrade: number | null
}

const skillData: Skill[] = Object.values(skillsData)

/** Automated skill-point spending strategies. `label` shows in the modal, `chipLabel` in the row chip, `description` explains each option in the modal. */
const SKILL_STRATEGY_OPTIONS = [
    { value: "default", label: "Do Not Spend Remaining Points", chipLabel: "Off", description: "Leaves any leftover skill points unspent." },
    { value: "optimize_skills", label: "Best Skills First", chipLabel: "Best Skills First", description: "Buys from a community tier list, best skills first. Optimizes rank within each tier." },
    { value: "optimize_rank", label: "Optimize Rank", chipLabel: "Optimize Rank", description: "Buys whatever maximizes trainee rank. Avoid for TT or CM." },
] as const

/** Props for `PlanTab`. */
interface PlanTabProps {
    /** Which plan to render (matches a key in `skillPlanSettingsPages`). */
    planKey: string
}

/**
 * Renders the per-plan body sections (Skill Type Filters, Strategy & Planned Skills, Configuration Summary) for a single
 * skill plan tab. The plan enable toggles and Skill Point Check threshold live in the SKILL PLANS section in the parent
 * `Skills` page; this component only renders the remaining sections, which appear only when the plan is enabled.
 * @param planKey Plan identifier matching `skillPlanSettingsPages`.
 * @returns A View containing the per-plan configuration sections.
 */
const PlanTab: React.FC<PlanTabProps> = ({ planKey }) => {
    const { colors } = useTheme()
    const modalShellStyles = useModalShellStyles()
    const [strategyPickerOpen, setStrategyPickerOpen] = useState(false)
    const config = skillPlanSettingsPages[planKey]
    const { skills, updateSkills } = useContext(SkillsContext)

    const combinedConfig = { ...defaultSettings.skills.plans, ...skills.plans }
    const planData = combinedConfig[planKey] ?? defaultSettings.skills.plans[planKey]
    const { enabled, strategy, enableBuyNegativeSkills, plan, blacklist, excludeGreenSkills, excludeRedSkills, excludeUniqueSkills, excludeDoubleCircleSkills } = planData

    const [searchQuery, setSearchQuery] = useState("")
    const [showSelected, setShowSelected] = useState(false)
    const [selectionMode, setSelectionMode] = useState<"plan" | "blacklist">("plan")

    const planIds: number[] = useMemo(() => {
        return plan && plan !== "" && typeof plan === "string" ? plan.split(",").map((s) => Number(s)) : []
    }, [plan])

    const blacklistIds: number[] = useMemo(() => {
        return blacklist && blacklist !== "" && typeof blacklist === "string" ? blacklist.split(",").map((s) => Number(s)) : []
    }, [blacklist])

    const activeIds: number[] = selectionMode === "plan" ? planIds : blacklistIds

    useEffect(() => {
        if (activeIds.length === 0) {
            setShowSelected(false)
        }
    }, [activeIds])

    const filteredSkills = useMemo(() => {
        const base: Skill[] = showSelected ? skillData.filter((s) => activeIds.includes(s.id)) : skillData
        return base.filter((s) => s.name_en.toLowerCase().includes(searchQuery.toLowerCase()))
    }, [searchQuery, activeIds, showSelected])

    /**
     * Update a per-plan skill setting.
     * @param key Setting key.
     * @param value Setting value.
     */
    const updatePlanSetting = useCallback(
        (key: string, value: any) => {
            updateSkills((prev) => ({
                ...prev,
                plans: {
                    ...prev.plans,
                    [planKey]: { ...prev.plans[planKey], [key]: value },
                },
            }))
        },
        [planKey, updateSkills]
    )

    /**
     * Toggle the selection of a single skill in the currently active list (plan or blacklist).
     * @param skill The skill to add or remove.
     */
    const handleSkillPress = useCallback(
        (skill: Skill) => {
            const targetKey: "plan" | "blacklist" = selectionMode
            const currentIds: number[] = selectionMode === "plan" ? planIds : blacklistIds
            const isSelected = currentIds.includes(skill.id)
            const newIds: number[] = isSelected ? currentIds.filter((id) => id !== skill.id) : [...currentIds, skill.id]
            updatePlanSetting(targetKey, newIds.join(","))
        },
        [selectionMode, planIds, blacklistIds, updatePlanSetting]
    )

    /** Clear all entries from the currently active list. */
    const clearActiveList = useCallback(() => {
        updatePlanSetting(selectionMode, "")
    }, [selectionMode, updatePlanSetting])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                unknown: { ...TYPE.body, color: colors.textMuted, padding: SPACING.md },
                hostPad: { padding: SPACING.md },
                sectionDescription: { ...TYPE.caption, color: colors.textMuted, lineHeight: 18 },
                subsectionTitle: { ...TYPE.body, color: colors.text, fontWeight: "600", marginBottom: SPACING.xs },
                listHeader: { flexDirection: "row", alignItems: "center", marginBottom: SPACING.sm, gap: SPACING.sm },
                listHelperText: { ...TYPE.caption, color: colors.textMuted, lineHeight: 18 },
                modeTabsRow: { flexDirection: "row", marginBottom: SPACING.sm, gap: 8 },
                modeTab: {
                    flex: 1,
                    paddingVertical: 10,
                    borderRadius: RADII.md,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    backgroundColor: colors.bg,
                    alignItems: "center",
                    overflow: "hidden",
                },
                modeTabActive: { backgroundColor: colors.brand, borderColor: colors.brand },
                modeTabLabel: { ...TYPE.body, color: colors.text, fontWeight: "600" },
                modeTabLabelActive: { color: colors.onBrand },
                searchInput: {
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.md,
                    padding: 12,
                    fontSize: 16,
                    color: colors.text,
                    backgroundColor: colors.bg,
                    marginBottom: SPACING.sm,
                },
                skillItem: {
                    backgroundColor: colors.surface,
                    padding: 16,
                    borderRadius: RADII.md,
                    marginBottom: 8,
                    flexDirection: "row",
                    justifyContent: "space-between",
                    alignItems: "center",
                },
                skillName: { ...TYPE.body, color: colors.text, fontWeight: "600" },
                skillDescription: { ...TYPE.caption, color: colors.textMuted, marginTop: 2 },
                skillSubtext: { ...TYPE.caption, color: colors.brand, marginTop: 2 },
                summaryHost: { marginTop: SPACING.sm, marginBottom: SPACING.lg },
                specCard: {
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.md,
                    overflow: "hidden",
                    marginTop: SPACING.sm,
                },
                specRow: { flexDirection: "row", paddingHorizontal: SPACING.md, paddingVertical: SPACING.sm, gap: SPACING.md, alignItems: "flex-start" },
                specRowDivider: { borderTopWidth: 1, borderTopColor: colors.borderHair },
                specLabel: { ...TYPE.monoLabel, color: colors.textMuted, width: 96, paddingTop: 2 },
                specValue: { ...TYPE.monoValue, color: colors.text, flex: 1, flexWrap: "wrap" },
                specValueMuted: { ...TYPE.monoValue, color: colors.textMuted, flex: 1, fontStyle: "italic" },
                chipList: { flexDirection: "row", flexWrap: "wrap", gap: 4, flex: 1, alignItems: "center" },
                chipPill: {
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: 2,
                    backgroundColor: colors.surface,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: RADII.pill,
                },
                chipPillText: { ...TYPE.monoValue, color: colors.text, fontSize: 11 },
            }),
        [colors]
    )

    if (!config) {
        return <Text style={styles.unknown}>Unknown plan: {planKey}</Text>
    }

    if (!enabled) {
        return null
    }

    const currentStrategy = SKILL_STRATEGY_OPTIONS.find((o) => o.value === strategy)
    const strategyLabel: string = currentStrategy?.label ?? strategy
    const excludedCategories: string[] = []
    if (excludeGreenSkills) excludedCategories.push("Green")
    if (excludeRedSkills) excludedCategories.push("Red")
    if (excludeUniqueSkills) excludedCategories.push("Unique")
    if (excludeDoubleCircleSkills) excludedCategories.push("Double-O")
    const idsToNames = (ids: number[]): string[] => ids.map((id) => skillData.find((s) => s.id === id)?.name_en ?? `Unknown (ID ${id})`)

    const renderSpecChipList = (ids: number[]) => {
        if (ids.length === 0) {
            return <Text style={styles.specValueMuted}>(none)</Text>
        }
        const names = idsToNames(ids)
        return (
            <View style={styles.chipList}>
                <CountBadge count={ids.length} />
                {names.map((skillName, i) => (
                    <View key={`${ids[i]}-${i}`} style={styles.chipPill}>
                        <Text style={styles.chipPillText}>{skillName}</Text>
                    </View>
                ))}
            </View>
        )
    }

    const isPlanMode = selectionMode === "plan"
    const helperText = isPlanMode ? "Select skills that the bot will always attempt to buy." : "Select skills the bot must never purchase, even when a strategy ranks them highly."

    return (
        <View>
            <Section label="Skill Type Filters">
                <View style={{ padding: SPACING.md }}>
                    <Text style={styles.sectionDescription}>
                        Exclude entire skill color categories from purchase. Useful when the Spending Strategies "Best Skills First" or "Optimize Rank" is picking unwanted skills like debuffs or stat
                        boosts.
                    </Text>
                </View>
                <SearchableItem id={`exclude-green-skills-${config.name}`} title="Skip All Green Skills" description="Exclude green stat-trigger skills">
                    <Row
                        title="Skip Green Skills"
                        description="Exclude green stat-trigger skills"
                        right={<Switch checked={excludeGreenSkills} onCheckedChange={(checked) => updatePlanSetting("excludeGreenSkills", checked)} />}
                    />
                </SearchableItem>
                <SearchableItem id={`exclude-red-skills-${config.name}`} title="Skip All Red Skills (Debuffs)" description="Exclude red debuff skills">
                    <Row
                        title="Skip Red Skills"
                        description="Exclude red debuff skills"
                        right={<Switch checked={excludeRedSkills} onCheckedChange={(checked) => updatePlanSetting("excludeRedSkills", checked)} />}
                    />
                </SearchableItem>
                <SearchableItem id={`exclude-unique-skills-${config.name}`} title="Skip All Unique Skills" description="Exclude inherited unique (legacy) skills">
                    <Row
                        title="Skip Unique Skills"
                        description="Exclude inherited unique (legacy) skills"
                        right={<Switch checked={excludeUniqueSkills} onCheckedChange={(checked) => updatePlanSetting("excludeUniqueSkills", checked)} />}
                    />
                </SearchableItem>
                <SearchableItem
                    id={`exclude-double-circle-skills-${config.name}`}
                    title="Skip All Double-O (Circle) Skills"
                    description="Only buy the single-circle version; skip the double-circle upgrade"
                >
                    <Row
                        title="Skip Double-O (Circle) Skills"
                        description="Skip double-circle upgrades in the auto-strategy. Ones you add to the plan are still bought."
                        right={<Switch checked={excludeDoubleCircleSkills} onCheckedChange={(checked) => updatePlanSetting("excludeDoubleCircleSkills", checked)} />}
                    />
                </SearchableItem>
            </Section>

            <Section label="Strategy & Planned Skills">
                <View>
                    <Row
                        title="Automated Skill Point Spending Strategy"
                        description="What the bot does with leftover skill points after buying your planned skills."
                        onPress={() => setStrategyPickerOpen(true)}
                        right={<ValuePill label={currentStrategy?.chipLabel ?? "Select Strategy"} />}
                    />
                    {strategy === "optimize_rank" && (
                        <WarningContainer style={{ marginHorizontal: SPACING.md, marginBottom: SPACING.md }}>
                            Warning: Optimize Rank ignores any of the Skill Style Overrides set in the Skills page.
                        </WarningContainer>
                    )}
                </View>

                <View style={styles.hostPad}>
                    <View style={styles.listHeader}>
                        <View style={{ flex: 1 }}>
                            <Text style={styles.subsectionTitle}>{isPlanMode ? "Planned Skills" : "Blacklisted Skills"}</Text>
                            <Text style={styles.listHelperText}>
                                Selected <Text style={[TYPE.monoValue, { color: colors.text }]}>{activeIds.length}</Text> /{" "}
                                <Text style={[TYPE.monoValue, { color: colors.text }]}>{filteredSkills.length}</Text> skills
                            </Text>
                        </View>
                        <CustomButton icon={<Trash2 size={16} color={colors.text} />} onPress={() => clearActiveList()}>
                            Clear
                        </CustomButton>
                    </View>

                    <View style={styles.modeTabsRow}>
                        <Pressable onPress={() => setSelectionMode("plan")} style={[styles.modeTab, isPlanMode && styles.modeTabActive]} android_ripple={{ color: colors.ripple, foreground: true }}>
                            <Text style={[styles.modeTabLabel, isPlanMode && styles.modeTabLabelActive]}>
                                Plan (<Text style={TYPE.monoValue}>{planIds.length}</Text>)
                            </Text>
                        </Pressable>
                        <Pressable
                            onPress={() => setSelectionMode("blacklist")}
                            style={[styles.modeTab, !isPlanMode && styles.modeTabActive]}
                            android_ripple={{ color: colors.ripple, foreground: true }}
                        >
                            <Text style={[styles.modeTabLabel, !isPlanMode && styles.modeTabLabelActive]}>
                                Blacklist (<Text style={TYPE.monoValue}>{blacklistIds.length}</Text>)
                            </Text>
                        </Pressable>
                    </View>

                    <Text style={styles.listHelperText}>{helperText}</Text>
                </View>

                <SearchableItem id={`show-selected-skills-${config.name}`} title="Show Only Selected Skills" description="Filter the list to only currently-selected skills">
                    <Row
                        title="Show Only Selected Skills"
                        description="Filter the list to only currently-selected skills"
                        right={
                            <Switch
                                checked={activeIds.length === 0 ? false : showSelected}
                                disabled={activeIds.length === 0}
                                onCheckedChange={(checked) => setShowSelected(checked && activeIds.length !== 0)}
                            />
                        }
                    />
                </SearchableItem>

                <View style={styles.hostPad}>
                    <Input style={styles.searchInput} value={searchQuery} onChangeText={setSearchQuery} placeholder="Search skills by name..." />
                    <View style={{ height: 700 }}>
                        <CustomScrollView
                            targetProps={{
                                data: filteredSkills,
                                renderItem: ({ item: skill }: { item: Skill }) => (
                                    <Pressable onPress={() => handleSkillPress(skill)} style={styles.skillItem} android_ripple={{ color: colors.ripple, foreground: true }}>
                                        <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
                                            <Image source={icons[skill.icon_id]} style={{ width: 64, height: 64, marginRight: 8 }} />
                                            <View style={{ flex: 1 }}>
                                                <Text style={styles.skillName}>{skill.name_en}</Text>
                                                <Text style={styles.skillDescription}>{skill.desc_en}</Text>
                                                <Text style={styles.skillSubtext}>
                                                    ID: <Text style={TYPE.monoValue}>{skill.id}</Text>
                                                </Text>
                                            </View>
                                            {activeIds.includes(skill.id) && <CircleCheckBig size={18} color={selectionMode === "plan" ? "green" : "red"} />}
                                        </View>
                                    </Pressable>
                                ),
                                nestedScrollEnabled: true,
                            }}
                            position="right"
                            horizontal={false}
                            persistentScrollbar={true}
                            indicatorStyle={{ width: 10, backgroundColor: colors.text }}
                            containerStyle={{ flex: 1 }}
                            minIndicatorSize={50}
                        />
                    </View>
                </View>
            </Section>

            <View style={styles.summaryHost}>
                <SectionLabel label="Configuration Summary" />
                <View style={styles.specCard}>
                    <View style={styles.specRow}>
                        <Text style={styles.specLabel}>Strategy</Text>
                        <Text style={styles.specValue}>{strategyLabel}</Text>
                    </View>
                    <View style={[styles.specRow, styles.specRowDivider]}>
                        <Text style={styles.specLabel}>Negative</Text>
                        <Text style={styles.specValue}>{enableBuyNegativeSkills ? "Yes" : "No"}</Text>
                    </View>
                    <View style={[styles.specRow, styles.specRowDivider]}>
                        <Text style={styles.specLabel}>Excluded</Text>
                        {excludedCategories.length === 0 ? (
                            <Text style={styles.specValueMuted}>(none)</Text>
                        ) : (
                            <View style={styles.chipList}>
                                {excludedCategories.map((c) => (
                                    <View key={c} style={styles.chipPill}>
                                        <Text style={styles.chipPillText}>{c}</Text>
                                    </View>
                                ))}
                            </View>
                        )}
                    </View>
                    <View style={[styles.specRow, styles.specRowDivider]}>
                        <Text style={styles.specLabel}>Planned</Text>
                        {renderSpecChipList(planIds)}
                    </View>
                    <View style={[styles.specRow, styles.specRowDivider]}>
                        <Text style={styles.specLabel}>Blacklisted</Text>
                        {renderSpecChipList(blacklistIds)}
                    </View>
                </View>
            </View>
            <SheetModal
                visible={strategyPickerOpen}
                onRequestClose={() => setStrategyPickerOpen(false)}
                header={<ModalHeader title="SKILL POINT STRATEGY" onClose={() => setStrategyPickerOpen(false)} />}
                footer={null}
            >
                <View style={modalShellStyles.modalBodyList}>
                    {SKILL_STRATEGY_OPTIONS.map((option) => (
                        <ModalRadioRow
                            key={option.value}
                            label={option.label}
                            description={option.description}
                            selected={option.value === strategy}
                            onPress={() => {
                                updatePlanSetting("strategy", option.value)
                                setStrategyPickerOpen(false)
                            }}
                        />
                    ))}
                </View>
            </SheetModal>
        </View>
    )
}

export default React.memo(PlanTab)
