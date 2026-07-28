import React, { useState, useCallback, useContext, useEffect, useMemo } from "react"
import { View, Text, ScrollView, StyleSheet } from "react-native"
import PageHeader from "../../components/PageHeader"
import { Section } from "../../components/ui/section"
import InfoCallout from "../../components/ui/info-callout"
import TabStrip, { TabStripItem } from "../../components/ui/tab-strip"
import { Row } from "../../components/ui/row"
import { Switch } from "../../components/ui/switch"
import SearchableItem from "../../components/SearchableItem"
import CustomSlider from "../../components/CustomSlider"
import { SkillsContext, defaultSettings } from "../../context/BotStateContext"
import { useTheme } from "../../context/ThemeContext"
import { useTranslation } from "../../lib/translations"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { skillPlanSettingsPages } from "../SkillPlanSettings/config"
import PlanTab from "./PlanTab"
import StyleSection from "./StyleSection"

/** Ordered list of plan tabs. Keys match `skillPlanSettingsPages` plan keys. */
const TAB_ITEMS: TabStripItem[] = [
    { key: "skillPointCheck", label: skillPlanSettingsPages.skillPointCheck.title },
    { key: "preFinals", label: skillPlanSettingsPages.preFinals.title },
    { key: "careerComplete", label: skillPlanSettingsPages.careerComplete.title },
]

/** Optional route params for deep-linking to a specific plan tab. */
interface SkillsRouteParams {
    /** Initial plan tab key. Falls back to `skillPointCheck` if missing or invalid. */
    tab?: string
}

/**
 * Consolidated Skills page. Hosts global Style settings at the top, a SKILL PLANS section that contains the tab strip,
 * the active plan's header, the Skill Point Check toggle + threshold (when applicable), the Enable Plan toggle, and
 * Purchase Negative Skills. Per-plan body (filters, strategy, planned skills, summary) renders below as its own sections.
 * @param route Optional navigation route carrying initial tab params.
 * @returns A scrollable Skills page with three tabs.
 */
const Skills: React.FC<{ route?: { params?: SkillsRouteParams } }> = ({ route }) => {
    const { colors } = useTheme()
    const t = useTranslation()
    const initialTab = route?.params?.tab && TAB_ITEMS.some((t) => t.key === route.params!.tab) ? route.params!.tab! : "skillPointCheck"
    const [activeKey, setActiveKey] = useState<string>(initialTab)
    const onChange = useCallback((key: string) => setActiveKey(key), [])

    const { skills, updateSkills } = useContext(SkillsContext)
    const activeConfig = skillPlanSettingsPages[activeKey]
    const isSkillPointCheck = activeKey === "skillPointCheck"
    const combinedPlans = { ...defaultSettings.skills.plans, ...skills.plans }
    const planData = combinedPlans[activeKey] ?? defaultSettings.skills.plans[activeKey]
    const { enabled, enableBuyNegativeSkills } = planData

    const updatePlanSetting = useCallback(
        (key: string, value: any) => {
            updateSkills((prev) => ({
                ...prev,
                plans: {
                    ...prev.plans,
                    [activeKey]: { ...prev.plans[activeKey], [key]: value },
                },
            }))
        },
        [activeKey, updateSkills]
    )

    // Enabling the Skill Point Check plan also flips the top-level Skill Point Check flag (legacy mirror).
    useEffect(() => {
        if (skills.plans.skillPointCheck.enabled && !skills.enableSkillPointCheck) {
            updateSkills({ enableSkillPointCheck: true })
        }
    }, [skills.plans.skillPointCheck.enabled, skills.enableSkillPointCheck, updateSkills])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: { flex: 1, backgroundColor: colors.background },
                scroll: { padding: SPACING.md, gap: SPACING.sm },
                intro: { ...TYPE.body, color: colors.text, marginBottom: SPACING.sm },
                tabHost: { padding: SPACING.xs },
                planHead: { padding: SPACING.md, paddingBottom: 0, gap: 2 },
                planTitle: { ...TYPE.h2, color: colors.text },
                planDescription: { ...TYPE.caption, color: colors.textMuted, paddingBottom: SPACING.md },
                sliderHost: { padding: SPACING.md },
            }),
        [colors]
    )

    const translatedTabItems = useMemo(() => TAB_ITEMS.map((item) => ({ ...item, label: t(item.label) })), [t])

    return (
        <View style={styles.container}>
            <PageHeader title={t("Skills")} />
            <ScrollView contentContainerStyle={styles.scroll}>
                <InfoCallout title={t("How skill spending works")}>
                    <Text style={styles.intro}>{t("Allows configuration of automated skill point spending.")}</Text>
                    <Text style={[styles.intro, { marginBottom: 0 }]}>
                        {t(
                            "This feature is not made of magic. If you wish to train an uma up for TT or CM, then you should buy your skills manually. The main purpose of this feature is to make the process of farming rank in events less of a hassle."
                        )}
                    </Text>
                </InfoCallout>
                <StyleSection />
                <Section label={t("Skill Plans")} firstDivider={false}>
                    <View style={styles.tabHost}>
                        <TabStrip items={translatedTabItems} activeKey={activeKey} onChange={onChange} />
                    </View>
                    <View style={styles.planHead}>
                        <Text style={styles.planTitle}>{t(activeConfig.title)}</Text>
                        <Text style={styles.planDescription}>{t(activeConfig.description)}</Text>
                    </View>
                    {isSkillPointCheck && (
                        <SearchableItem id="enable-skill-point-check" title={t("Enable Skill Point Check")} description={t("Stop the bot when the skill point threshold is reached")}>
                            <Row
                                title={t("Enable Skill Point Check")}
                                description={t("Stop the bot when the skill point threshold is reached")}
                                right={
                                    <Switch
                                        checked={skills.enableSkillPointCheck}
                                        onCheckedChange={(checked) => {
                                            if (checked) {
                                                updateSkills({ enableSkillPointCheck: true })
                                            } else {
                                                // Cascade off: also disable the Skill Point Check plan so the legacy mirror effect doesn't immediately flip the top toggle back on.
                                                updateSkills((prev) => ({
                                                    ...prev,
                                                    enableSkillPointCheck: false,
                                                    plans: { ...prev.plans, skillPointCheck: { ...prev.plans.skillPointCheck, enabled: false } },
                                                }))
                                            }
                                        }}
                                    />
                                }
                            />
                        </SearchableItem>
                    )}
                    {isSkillPointCheck && skills.enableSkillPointCheck && (
                        <View style={styles.sliderHost}>
                            <CustomSlider
                                searchId="skill-point-check"
                                searchCondition={skills.enableSkillPointCheck}
                                parentId="enable-skill-point-check"
                                value={skills.skillPointCheck}
                                placeholder={defaultSettings.skills.skillPointCheck}
                                onValueChange={(value) => updateSkills({ skillPointCheck: value })}
                                onSlidingComplete={(value) => updateSkills({ skillPointCheck: value })}
                                min={100}
                                max={2000}
                                step={10}
                                label={t("Skill Point Threshold")}
                                description={t("The number of skill points to accumulate before stopping the bot.")}
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                            />
                        </View>
                    )}
                    <SearchableItem
                        id={isSkillPointCheck ? "skill-point-check-plan" : `enable-skill-plan-${activeKey}`}
                        title={t(`Enable ${activeConfig.title} Plan`)}
                        description={t("Purchase skills based on this plan's configuration")}
                    >
                        <Row
                            title={t(`Enable ${activeConfig.title} Plan (Beta)`)}
                            description={t("Purchase skills based on this plan's configuration")}
                            right={<Switch checked={enabled} onCheckedChange={(checked) => updatePlanSetting("enabled", checked)} />}
                        />
                    </SearchableItem>
                    {enabled && (
                        <SearchableItem
                            id={`enable-buy-negative-skills-${activeConfig.name}`}
                            title={t("Purchase All Negative Skills")}
                            description={t("Attempt to buy all negative skills (e.g. Firm Conditions x)")}
                        >
                            <Row
                                title={t("Purchase All Negative Skills")}
                                description={t("Attempt to buy all negative skills (e.g. Firm Conditions x)")}
                                right={<Switch checked={enableBuyNegativeSkills} onCheckedChange={(checked) => updatePlanSetting("enableBuyNegativeSkills", checked)} />}
                            />
                        </SearchableItem>
                    )}
                </Section>
                <PlanTab planKey={activeKey} />
            </ScrollView>
        </View>
    )
}

export default Skills
