import { useMemo, useContext, useEffect, useRef } from "react"
import { View, Text, ScrollView, StyleSheet } from "react-native"
import { useNavigation } from "@react-navigation/native"
import { Divider } from "react-native-paper"
import { useTheme } from "../../context/ThemeContext"
import NavigationLink from "../../components/NavigationLink"
import CustomSelect from "../../components/CustomSelect"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomSlider from "../../components/CustomSlider"
import CustomTitle from "../../components/CustomTitle"
import PageHeader from "../../components/PageHeader"
import { BotStateContext, defaultSettings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import { skillPlanSettingsPages } from "../SkillPlanSettings"
import InfoContainer from "../../components/InfoContainer"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"

const SkillSettings = () => {
    usePerformanceLogging("SkillSettings")
    const { colors } = useTheme()
    const navigation = useNavigation()
    const bsc = useContext(BotStateContext)
    const scrollViewRef = useRef<ScrollView>(null)

    const { settings, setSettings } = bsc

    // Merge current skills settings with defaults to handle missing properties.
    const skillSettings = { ...defaultSettings.skills, ...settings.skills }
    const { preferredRunningStyle, preferredTrackDistance, preferredTrackSurface } = skillSettings

    useEffect(() => {
        if (bsc.settings.skills.plans.skillPointCheck.enabled) {
            bsc.setSettings({
                ...bsc.settings,
                skills: {
                    ...bsc.settings.skills,
                    enableSkillPointCheck: true,
                },
            })
        }
    }, [bsc.settings.skills.plans.skillPointCheck.enabled])

    const updateSkillsSetting = (key: string, value: any) => {
        setSettings({
            ...bsc.settings,
            skills: {
                ...bsc.settings.skills,
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
                description: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginBottom: 16,
                    lineHeight: 20,
                },
                section: {
                    marginBottom: 16,
                },
                inputContainer: {
                    marginBottom: 16,
                },
                inputLabel: {
                    fontSize: 16,
                    color: colors.foreground,
                    marginBottom: 8,
                },
                infoBlock: {
                    marginTop: 12,
                },
                infoLabel: {
                    fontWeight: "bold",
                    color: colors.foreground,
                    fontSize: 14,
                    lineHeight: 22,
                    includeFontPadding: false,
                },
                infoDescription: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    lineHeight: 22,
                    includeFontPadding: false,
                    marginTop: 2,
                },
            }),
        [colors],
    )

    return (
        <View style={styles.root}>
            <PageHeader title="Skill Settings" />
            <SearchPageProvider page="SkillSettings" scrollViewRef={scrollViewRef}>
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View style={styles.inputContainer}>
                        <Text style={styles.description}>Allows configuration of automated skill point spending.</Text>
                        <Text style={styles.description}>
                            This feature is not made of magic. If you wish to train an uma up for TT or CM, then you should buy your skills manually. The main purpose of this feature is to make the
                            process of farming rank in events less of a hassle.
                        </Text>
                        <Divider style={{ marginBottom: 16 }} />
                        <CustomCheckbox
                            searchId="enable-skill-point-check"
                            checked={bsc.settings.skills.enableSkillPointCheck}
                            onCheckedChange={(checked) => {
                                bsc.setSettings({
                                    ...bsc.settings,
                                    skills: { ...bsc.settings.skills, enableSkillPointCheck: checked },
                                })
                            }}
                            label="Enable Skill Point Check"
                            description="Enables check for a certain skill point threshold. When the threshold is reached, the bot is stopped. This can be changed to allow the selected Skill Plan to spend those points instead of stopping the bot."
                        />

                        <View style={bsc.settings.skills.enableSkillPointCheck ? { marginTop: 8 } : { display: "none" }}>
                            <CustomSlider
                                searchId="skill-point-check"
                                searchCondition={bsc.settings.skills.enableSkillPointCheck}
                                parentId="enable-skill-point-check"
                                value={bsc.settings.skills.skillPointCheck}
                                placeholder={bsc.defaultSettings.skills.skillPointCheck}
                                onValueChange={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        skills: { ...bsc.settings.skills, skillPointCheck: value },
                                    })
                                }}
                                onSlidingComplete={(value) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        skills: { ...bsc.settings.skills, skillPointCheck: value },
                                    })
                                }}
                                min={100}
                                max={2000}
                                step={10}
                                label="Skill Point Threshold"
                                description="The number of skill points to accumulate before stopping the bot."
                                labelUnit=""
                                showValue={true}
                                showLabels={true}
                            />
                            <CustomCheckbox
                                searchId="skill-point-check-plan"
                                searchCondition={bsc.settings.skills.enableSkillPointCheck}
                                parentId="enable-skill-point-check"
                                checked={bsc.settings.skills.plans.skillPointCheck.enabled}
                                onCheckedChange={(checked) => {
                                    bsc.setSettings({
                                        ...bsc.settings,
                                        skills: {
                                            ...bsc.settings.skills,
                                            plans: {
                                                ...bsc.settings.skills.plans,
                                                skillPointCheck: {
                                                    ...bsc.settings.skills.plans.skillPointCheck,
                                                    enabled: checked,
                                                },
                                            },
                                        },
                                    })
                                }}
                                label="Enable Skill Plan Upon Meeting Threshold"
                                description="Instead of stopping the bot, this will run the Skill Plan to spend the skill points when the threshold is met."
                            />
                        </View>
                    </View>
                    <CustomTitle title="Skill Style Overrides" description="Override which types of skills the bot can purchase." />
                    <Text style={styles.description}>
                        Any skills whose activation condition does not match the selected override will be filtered out of the list of available skills that the bot can consider for purchasing. Skills
                        that have no activation conditions will still be available.
                    </Text>
                    <View>
                        <View style={styles.inputContainer}>
                            <CustomSelect
                                searchId="skill-plan-running-style"
                                options={[
                                    { value: "inherit", label: "Use [Racing Settings] -> [Original Race Strategy]" },
                                    { value: "no_preference", label: "Any" },
                                    { value: "front_runner", label: "Front Runner" },
                                    { value: "pace_chaser", label: "Pace Chaser" },
                                    { value: "late_surger", label: "Late Surger" },
                                    { value: "end_closer", label: "End Closer" },
                                ]}
                                value={preferredRunningStyle}
                                defaultValue={defaultSettings.skills.preferredRunningStyle}
                                onValueChange={(value) => updateSkillsSetting("preferredRunningStyle", value)}
                                label="Running Style for Skills"
                                description="Dictates which skills are considered for purchase based on the preferred running style."
                                placeholder="Select Running Style"
                            />
                            <InfoContainer>
                                <View>
                                    <Text style={styles.infoLabel}>There are two different groups of Running Style skills.</Text>
                                    <View style={styles.infoBlock}>
                                        <Text style={styles.infoDescription}>
                                            The first are skills that specifically say in their description that they are for a specific running style. These cannot be activated unless the trainee is
                                            using that running style.
                                        </Text>
                                    </View>
                                    <View style={styles.infoBlock}>
                                        <Text style={styles.infoDescription}>
                                            The second are skills that do not say they are for a running style, but have activation conditions which limit which styles would actually be able to
                                            activate them (ignoring rare cases).
                                        </Text>
                                    </View>
                                    <View style={styles.infoBlock}>
                                        <Text style={styles.infoDescription}>
                                            This setting will filter skills based on both of these conditions. This helps us avoid having situations like an End Closer purchasing a skill like "Keeping
                                            the Lead". This skill doesn't require using the Front Runner style to activate, but it does require the runner to be in the lead mid-race which is very
                                            unlikely for an End Closer.
                                        </Text>
                                    </View>
                                    <Text style={[styles.infoLabel, { marginTop: 12 }]}>Detailed breakdown of examples:</Text>

                                    <View style={styles.infoBlock}>
                                        <Text style={styles.infoLabel}>Use [Racing Settings] {"->"} [Original Race Strategy]</Text>
                                        <Text style={styles.infoDescription}>
                                            • Inherits the running style from your Racing Settings. For example, if you set the Strategy to "Late Surger" in Racing Settings, only Late Surger skills
                                            will be considered.
                                        </Text>
                                    </View>

                                    <View style={styles.infoBlock}>
                                        <Text style={styles.infoLabel}>Any</Text>
                                        <Text style={styles.infoDescription}>
                                            • Does not filter any skills based on running style. For example, even if your trainee is an "End Closer", the bot may still purchase "Pace Chaser Corners
                                            ○" (a Pace Chaser skill) if it's available.
                                        </Text>
                                    </View>

                                    <View style={styles.infoBlock}>
                                        <Text style={styles.infoLabel}>Front Runner</Text>
                                        <Text style={styles.infoDescription}>
                                            • Only considers skills that are compatible with the Front Runner style. For example, skills like "Escape Artist" will be included, while "Outer Swell"
                                            (Late Surger) will be ignored.
                                        </Text>
                                    </View>
                                </View>
                            </InfoContainer>
                        </View>
                        <View style={styles.inputContainer}>
                            <CustomSelect
                                searchId="preferred-distance-override"
                                options={[
                                    { value: "inherit", label: "Use [Training Settings] -> [Preferred Distance Override]" },
                                    { value: "no_preference", label: "Any" },
                                    { value: "sprint", label: "Sprint" },
                                    { value: "mile", label: "Mile" },
                                    { value: "medium", label: "Medium" },
                                    { value: "long", label: "Long" },
                                ]}
                                value={preferredTrackDistance}
                                defaultValue={defaultSettings.skills.preferredTrackDistance}
                                onValueChange={(value) => updateSkillsSetting("preferredTrackDistance", value)}
                                label="Track Distance for Skills"
                                description="Dictates which skills are considered for purchase based on the track distance."
                                placeholder="Select Track Distance"
                            />
                        </View>
                        <View style={styles.inputContainer}>
                            <CustomSelect
                                searchId="preferred-track-surface"
                                options={[
                                    { value: "no_preference", label: "Any" },
                                    { value: "turf", label: "Turf" },
                                    { value: "dirt", label: "Dirt" },
                                ]}
                                value={preferredTrackSurface}
                                defaultValue={defaultSettings.skills.preferredTrackSurface}
                                onValueChange={(value) => updateSkillsSetting("preferredTrackSurface", value)}
                                label="Track Surface for Skills"
                                description="Dictates which skills are considered for purchase based on the terrain."
                                placeholder="Select Track Surface"
                            />
                            <InfoContainer>
                                <Text style={styles.infoDescription}>
                                    As of 2026-02-19, there are no skills that only apply to the Turf surface type. The only track surface specific skills are ones for Dirt. So if you choose Dirt, all
                                    skills will still be available for purchase. However if you choose Turf, then all the Dirt skills will be ignored.
                                </Text>
                            </InfoContainer>
                        </View>
                    </View>
                    <Divider style={{ marginBottom: 24 }} />
                    <View style={styles.section}>
                        <View className="m-1">
                            {Object.values(skillPlanSettingsPages).map((value) => (
                                <NavigationLink
                                    key={value.name}
                                    title={`Go to ${value.title} Skill Plan Settings`}
                                    description={value.description.split("\n")[0]}
                                    onPress={() => navigation.navigate(value.name as never)}
                                    style={{ ...styles.section, marginTop: 0 }}
                                />
                            ))}
                        </View>
                    </View>
                </ScrollView>
            </SearchPageProvider>
        </View>
    )
}

export default SkillSettings
