import React, { useContext, useMemo, useRef, useState } from "react"
import { View, ScrollView, StyleSheet } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { GeneralMiscContext, defaultSettings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import PageHeader from "../../components/PageHeader"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import { Row } from "../../components/ui/row"
import { Section } from "../../components/ui/section"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { ModalHeader } from "../../components/ui/modal-header"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import ValuePill from "../../components/ui/value-pill"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import { DAILY_RACE_REWARDS, DAILY_RACE_TYPES, LEGENDS, SASHES, SHOES } from "../../data/dailies"
import type { ThemeColors } from "../../lib/theme"

/**
 * Builds the page styles for the current theme.
 * @param colors The theme colors.
 * @returns The style sheet.
 */
const createStyles = (colors: ThemeColors) =>
    StyleSheet.create({
        root: { flex: 1, backgroundColor: colors.background },
    })

/** Widest a picker's value pill grows before its label is cut with "...", so long sash names fit the row. */
const PILL_MAX_WIDTH = 160

/** One option in a picker sheet. */
interface PickerOption {
    /** Stored value. */
    value: string
    /** Label shown in the sheet. */
    label: string
    /** Small uppercase tag above the label, or none. */
    tag?: string
}

/** Which picker sheet is open. */
type OpenPicker = null | "raceType" | "legend" | "item"

/** Options for the Daily Race picker. */
const RACE_TYPE_OPTIONS: PickerOption[] = DAILY_RACE_TYPES.map((type) => ({ value: type, label: type, tag: DAILY_RACE_REWARDS[type] }))

/** Options for the Legend Race picker, A-Z by trainee name and tagged with each legend's race. */
const LEGEND_OPTIONS: PickerOption[] = [...LEGENDS].sort((a, b) => a.name.localeCompare(b.name)).map((legend) => ({ value: legend.name, label: legend.name, tag: legend.race }))

/**
 * Sorts item names alphabetically without changing the source list.
 * @param items The item names.
 * @returns A sorted copy.
 */
const alphabetical = (items: string[]): string[] => [...items].sort((a, b) => a.localeCompare(b))

/** Options for the Club item picker: shoes A-Z, then sashes A-Z. */
const ITEM_OPTIONS: PickerOption[] = [
    ...alphabetical(SHOES).map((item) => ({ value: item, label: item, tag: "Shoes" })),
    ...alphabetical(SASHES).map((item) => ({ value: item, label: item, tag: "Winner's Sash" })),
]

/**
 * Dailies Settings page: which daily chores the Dailies run does and the choices each one needs.
 * @returns The settings page.
 */
const DailiesSettings = () => {
    usePerformanceLogging("DailiesSettings")
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors), [colors])
    const modalShellStyles = useModalShellStyles()
    const scrollViewRef = useRef<ScrollView>(null)
    const { dailies, updateDailies } = useContext(GeneralMiscContext)
    const settings = { ...defaultSettings.dailies, ...dailies }
    const [openPicker, setOpenPicker] = useState<OpenPicker>(null)

    /**
     * Renders a picker value as the row's right-side pill.
     * @param label The pill text.
     * @returns The pill.
     */
    const chipFor = (label: string) => <ValuePill label={label} maxWidth={PILL_MAX_WIDTH} />

    /**
     * Renders a picker sheet.
     * @param kind Which picker this is.
     * @param titleMono The sheet title.
     * @param options The options.
     * @param current The selected value.
     * @param onSelect Called with the picked value.
     * @returns The sheet.
     */
    const renderModal = (kind: OpenPicker, titleMono: string, options: PickerOption[], current: string, onSelect: (value: string) => void) => (
        <SheetModal visible={openPicker === kind} onRequestClose={() => setOpenPicker(null)} header={<ModalHeader title={titleMono} onClose={() => setOpenPicker(null)} />} footer={null}>
            <View style={modalShellStyles.modalBodyList}>
                {options.map((o) => (
                    <ModalRadioRow
                        key={o.value}
                        tag={o.tag}
                        label={o.label}
                        selected={o.value === current}
                        onPress={() => {
                            onSelect(o.value)
                            setOpenPicker(null)
                        }}
                    />
                ))}
            </View>
        </SheetModal>
    )

    return (
        <View style={styles.root}>
            <SearchPageProvider page="DailiesSettings" scrollViewRef={scrollViewRef}>
                <PageHeader title="Dailies Settings" />
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <Section label="Team Trials">
                            <ToggleSetting
                                id="dailies-team-trials"
                                title="Team Trials"
                                description="Spends all RP racing the weakest of the three opponents."
                                checked={settings.enableTeamTrials}
                                onCheckedChange={(checked) => updateDailies({ enableTeamTrials: checked })}
                            />
                            <ToggleSetting
                                id="dailies-team-trials-parfait"
                                title="Use Pleasing Parfait in Team Trials"
                                description="Raises your team's mood to Great before each Team Trials race. Uses one Parfait per race."
                                checked={settings.teamTrialsUseParfait}
                                onCheckedChange={(checked) => updateDailies({ teamTrialsUseParfait: checked })}
                                condition={settings.enableTeamTrials}
                                parentId="dailies-team-trials"
                            />
                        </Section>

                        <Section label="Daily Races">
                            <ToggleSetting
                                id="dailies-daily-races"
                                title="Daily Races"
                                description="Spends every Daily Race ticket at the hardest difficulty, using Multi-Race."
                                checked={settings.enableDailyRaces}
                                onCheckedChange={(checked) => updateDailies({ enableDailyRaces: checked })}
                            />
                            <SearchableItem
                                id="dailies-daily-race-type"
                                title="Daily Race"
                                description="Moonlight Sho earns Monies. Jupiter Cup earns Support Points."
                                condition={settings.enableDailyRaces}
                                parentId="dailies-daily-races"
                            >
                                <Row
                                    title="Daily Race"
                                    description="Moonlight Sho earns Monies. Jupiter Cup earns Support Points."
                                    onPress={() => setOpenPicker("raceType")}
                                    right={chipFor(settings.dailyRaceType)}
                                />
                            </SearchableItem>
                        </Section>

                        <Section label="Legend Race">
                            <ToggleSetting
                                id="dailies-legend-race"
                                title="Daily Legend Race"
                                description="Spends the daily Legend Race ticket."
                                checked={settings.enableLegendRace}
                                onCheckedChange={(checked) => updateDailies({ enableLegendRace: checked })}
                            />
                            <SearchableItem
                                id="dailies-legend-character"
                                title="Legend to Race"
                                description="If this legend is already maxed, the bot races the first legend that can still unlock a star."
                                condition={settings.enableLegendRace}
                                parentId="dailies-legend-race"
                            >
                                <Row
                                    title="Legend to Race"
                                    description="If this legend is already maxed, the bot races the first legend that can still unlock a star."
                                    onPress={() => setOpenPicker("legend")}
                                    right={chipFor(settings.legendRaceCharacter)}
                                />
                            </SearchableItem>
                            <ToggleSetting
                                id="dailies-legend-parfait"
                                title="Use Pleasing Parfait in Legend Race"
                                description="Raises your runner's mood to Great before the Legend Race."
                                checked={settings.legendRaceUseParfait}
                                onCheckedChange={(checked) => updateDailies({ legendRaceUseParfait: checked })}
                                condition={settings.enableLegendRace}
                                parentId="dailies-legend-race"
                            />
                        </Section>

                        <Section label="Club">
                            <ToggleSetting
                                id="dailies-club-request"
                                title="Request an Item"
                                description="Collects an expired item request, then asks the Club for a new one. Skipped while a request is still running."
                                checked={settings.clubRequestEnabled}
                                onCheckedChange={(checked) => updateDailies({ clubRequestEnabled: checked })}
                            />
                            <SearchableItem
                                id="dailies-club-item"
                                title="Item to Request"
                                description="The shoes or Winner's Sash to ask the Club for."
                                condition={settings.clubRequestEnabled}
                                parentId="dailies-club-request"
                            >
                                <Row
                                    title="Item to Request"
                                    description="The shoes or Winner's Sash to ask the Club for."
                                    onPress={() => setOpenPicker("item")}
                                    right={chipFor(settings.clubRequestItem)}
                                />
                            </SearchableItem>
                            <ToggleSetting
                                id="dailies-club-donate"
                                title="Donate to All"
                                description="Donates to every Club member's item request the game allows today."
                                checked={settings.clubDonateEnabled}
                                onCheckedChange={(checked) => updateDailies({ clubDonateEnabled: checked })}
                            />
                        </Section>

                        <Section label="Collect">
                            <ToggleSetting
                                id="dailies-missions"
                                title="Mission Rewards"
                                description="Collects finished mission rewards from the Missions screen."
                                checked={settings.enableMissions}
                                onCheckedChange={(checked) => updateDailies({ enableMissions: checked })}
                            />
                            <ToggleSetting
                                id="dailies-presents"
                                title="Presents"
                                description="Collects everything in the Presents box."
                                checked={settings.enablePresents}
                                onCheckedChange={(checked) => updateDailies({ enablePresents: checked })}
                            />
                        </Section>
                    </View>
                </ScrollView>
            </SearchPageProvider>

            {renderModal("raceType", "DAILY RACE", RACE_TYPE_OPTIONS, settings.dailyRaceType, (v) => updateDailies({ dailyRaceType: v }))}
            {renderModal("legend", "LEGEND TO RACE", LEGEND_OPTIONS, settings.legendRaceCharacter, (v) => updateDailies({ legendRaceCharacter: v }))}
            {renderModal("item", "ITEM TO REQUEST", ITEM_OPTIONS, settings.clubRequestItem, (v) => updateDailies({ clubRequestItem: v }))}
        </View>
    )
}

export default DailiesSettings
