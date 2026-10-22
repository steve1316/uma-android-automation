import React, { useContext, useMemo, useRef, useState } from "react"
import { View, ScrollView, StyleSheet, Text, Pressable } from "react-native"
import Ionicons from "@react-native-vector-icons/ionicons"
import { useTheme } from "../../context/ThemeContext"
import { GeneralMiscContext, RacingContext, SkillsContext, defaultSettings } from "../../context/BotStateContext"
import { SearchPageProvider } from "../../context/SearchPageContext"
import PageHeader from "../../components/PageHeader"
import WarningContainer from "../../components/WarningContainer"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import CustomSlider from "../../components/CustomSlider"
import DraggablePriorityList from "../../components/DraggablePriorityList"
import { Row } from "../../components/ui/row"
import { Section } from "../../components/ui/section"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalFooterChip, ModalRadioRow } from "../../components/ui/modal-list"
import { ModalHeader } from "../../components/ui/modal-header"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import { Input } from "../../components/ui/input"
import { CountBadge } from "../../components/ui/count-badge"
import ValuePill from "../../components/ui/value-pill"
import { usePerformanceLogging } from "../../hooks/usePerformanceLogging"
import traineeCardsData from "../../data/live/traineeCards"
import supportCardsData from "../../data/live/supportCards"
import characterObjectivesData from "../../data/live/characterObjectives"
import { smartSparks, solverPresetFor } from "../../lib/careerSetup"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import type { ThemeColors } from "../../lib/theme"

/**
 * Builds the page styles for the current theme.
 * @param colors The theme colors.
 * @returns The style sheet.
 */
const createStyles = (colors: ThemeColors) =>
    StyleSheet.create({
        root: { flex: 1, flexDirection: "column", justifyContent: "center", margin: 10, backgroundColor: colors.bg },
        sliderShell: { padding: SPACING.md },
        disabled: { opacity: 0.5 },
        selectorRow: { flexDirection: "row", alignItems: "center", gap: SPACING.sm, paddingHorizontal: SPACING.lg, paddingVertical: SPACING.lg },
        selectorMain: { flex: 1, gap: SPACING.sm },
        selectorTitle: { ...TYPE.body, color: colors.text, fontWeight: "600" },
        selectorDescription: { ...TYPE.caption, color: colors.textMuted, marginTop: 2 },
        selectorChips: { flexDirection: "row", flexWrap: "wrap", gap: 6 },
        selectorChip: {
            flexDirection: "row",
            alignItems: "center",
            gap: SPACING.xs,
            backgroundColor: colors.brandSubtle,
            borderWidth: 1,
            borderColor: colors.brandBorder,
            borderRadius: RADII.pill,
            paddingLeft: 3,
            paddingRight: SPACING.sm,
            paddingVertical: 2,
        },
        selectorChipText: { ...TYPE.caption, color: colors.brand, fontWeight: "600" },
        searchShell: { paddingBottom: SPACING.sm },
        searchInput: { borderWidth: 1, borderColor: colors.borderHair, borderRadius: RADII.md, padding: SPACING.md, ...TYPE.body, color: colors.text, backgroundColor: colors.bg },
        emptyText: { ...TYPE.caption, color: colors.textMuted, padding: SPACING.md },
        modalFooterRow: { flexDirection: "row", gap: SPACING.sm },
    })

/** Widest a value pill grows before its label is cut with "...", so outfit and card names fit the row. */
const PILL_MAX_WIDTH = 170

/** Every spark the custom wish list can hold: blue stats, then pink track, distance and style. */
const SPARK_ITEMS = ["SPD", "STA", "PWR", "GUTS", "WIT", "TURF", "DIRT", "SPRNT", "MILE", "MED", "LONG", "FRONT", "PACE", "LATE", "END"].map((spark) => ({ id: spark, label: spark }))

/** Legacy parent modes with their row descriptions. */
const LEGACY_MODES: { value: string; label: string; description: string }[] = [
    { value: "keep", label: "Keep Last", description: "Keeps the last parents. A slot left empty, such as when guest borrows run out, gets a Smart pick." },
    { value: "smart", label: "Smart", description: "Picks parents by the sparks the trainee needs, worked out from its outfit's grades." },
    { value: "custom", label: "Custom", description: "Picks parents by your own spark wish list." },
]

/** Deck choices: keep the current deck, or Deck 1-10. */
const DECK_OPTIONS: { value: number; label: string }[] = [{ value: 0, label: "Keep current" }, ...Array.from({ length: 10 }, (_, i) => ({ value: i + 1, label: `Deck ${i + 1}` }))]

/** Which sheet is open. */
type OpenSheet = null | "trainee" | "legacyMode" | "wishList" | "deck" | "friend"

/** One trainee outfit as listed in the picker. */
interface OutfitOption {
    /** Card id stored in settings. */
    id: string
    /** Character name. */
    name: string
    /** Outfit title in brackets. */
    title: string
    /** Grades keyed by surface, distance and style. */
    aptitudes: Record<string, string>
    /** Growth bonuses in percent keyed by stat. */
    growth: Record<string, number>
}

/** One support card as listed in the picker. */
interface CardOption {
    /** Support id stored in settings. */
    id: string
    /** Character name. */
    name: string
    /** Card title in brackets. */
    title: string
    /** Card type such as Speed or Friend. */
    type: string
    /** R, SR or SSR. */
    rarity: string
}

/** Trainee outfits sorted by name, then title. */
const OUTFITS: OutfitOption[] = Object.entries(traineeCardsData as Record<string, Omit<OutfitOption, "id">>)
    .map(([id, card]) => ({ id, ...card }))
    .sort((a, b) => a.name.localeCompare(b.name) || a.title.localeCompare(b.title))

/** Support cards sorted SSR first, then by name and title. */
const CARDS: CardOption[] = Object.entries(supportCardsData as Record<string, Omit<CardOption, "id">>)
    .map(([id, card]) => ({ id, ...card }))
    .sort((a, b) => (b.rarity === "SSR" ? 1 : 0) - (a.rarity === "SSR" ? 1 : 0) || a.name.localeCompare(b.name) || a.title.localeCompare(b.title))

/**
 * Whether a character has a goal race that is always a Long race. A choice between races only counts when every option is Long. Mirrors `CareerSetupSettings` in `CareerSetup.kt`.
 * @param name The character name.
 * @returns True if Smart Auto-Select should rank Stamina first for this character.
 */
const hasLongGoal = (name: string): boolean => {
    const goals = (characterObjectivesData as Record<string, { mandatoryRaces?: { options: { distanceType: string }[] }[] }>)[name]?.mandatoryRaces ?? []
    return goals.some((race) => race.options.length > 0 && race.options.every((option) => option.distanceType === "Long"))
}

/**
 * Shows an outfit or card the way the game does.
 * @param item The outfit or card, or undefined when none is chosen.
 * @returns "[Title] Name", or undefined.
 */
const labelOf = (item?: { title: string; name: string }): string | undefined => (item ? `${item.title} ${item.name}` : undefined)

/**
 * Keeps the entries whose label contains the search text.
 * @param items The outfits or cards.
 * @param query The search text.
 * @returns The matching entries.
 */
const filterByLabel = <T extends { title: string; name: string }>(items: T[], query: string): T[] => {
    const q = query.trim().toLowerCase()
    return q ? items.filter((item) => labelOf(item)!.toLowerCase().includes(q)) : items
}

/**
 * Career Setup Settings page: how the bot starts a new career when a Career run begins outside one.
 * @returns The settings page.
 */
const CareerSetupSettings = () => {
    usePerformanceLogging("CareerSetupSettings")
    const { colors } = useTheme()
    const styles = useMemo(() => createStyles(colors), [colors])
    const modalShellStyles = useModalShellStyles()
    const scrollViewRef = useRef<ScrollView>(null)
    const { careerSetup, updateCareerSetup, dailies, updateDailies } = useContext(GeneralMiscContext)
    const { updateRacing } = useContext(RacingContext)
    const { skills } = useContext(SkillsContext)
    const settings = { ...defaultSettings.careerSetup, ...careerSetup }
    const dailiesSettings = { ...defaultSettings.dailies, ...dailies }
    const [openSheet, setOpenSheet] = useState<OpenSheet>(null)
    const [search, setSearch] = useState("")
    const shownOutfits = useMemo(() => filterByLabel(OUTFITS, search), [search])
    const shownCards = useMemo(() => filterByLabel(CARDS, search), [search])

    const outfit = OUTFITS.find((o) => o.id === settings.trainee)
    const friend = CARDS.find((c) => c.id === settings.friendCard)
    const mode = LEGACY_MODES.find((m) => m.value === settings.legacyMode) ?? LEGACY_MODES[0]
    const smartList = outfit ? smartSparks(outfit.aptitudes, outfit.growth, hasLongGoal(outfit.name)) : []
    // Keep Last uses the Smart list for a slot left empty, so it shows that list too.
    const shownSparks = settings.legacyMode === "custom" ? settings.sparkWishList : smartList
    const wishDescription =
        settings.legacyMode === "smart"
            ? outfit && !settings.usePreselectedTrainee
                ? `Worked out from ${labelOf(outfit)}.`
                : "Worked out from the outfit selected when the run starts."
            : settings.legacyMode === "custom"
              ? "Sparks to look for in the two parents, most wanted first."
              : "Smart sparks, used only for a slot left empty."

    /**
     * Opens a sheet and clears its search box.
     * @param sheet The sheet to open.
     */
    const show = (sheet: OpenSheet) => {
        setSearch("")
        setOpenSheet(sheet)
    }

    /** Closes the open sheet. */
    const close = () => setOpenSheet(null)

    /**
     * Renders a value as the row's right-side pill.
     * @param label The pill text.
     * @returns The pill.
     */
    const pill = (label: string) => <ValuePill label={label} maxWidth={PILL_MAX_WIDTH} />

    /**
     * Renders the search box shown above a long picker list.
     * @param placeholder The hint text.
     * @returns The search box.
     */
    const searchBox = (placeholder: string) => (
        <View style={styles.searchShell}>
            <Input style={styles.searchInput} value={search} onChangeText={setSearch} placeholder={placeholder} />
        </View>
    )

    return (
        <View style={styles.root}>
            <SearchPageProvider page="CareerSetupSettings" scrollViewRef={scrollViewRef}>
                <PageHeader title="Career Setup" />
                <ScrollView ref={scrollViewRef} nestedScrollEnabled={true} showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} contentContainerStyle={{ flexGrow: 1 }}>
                    <View className="m-1">
                        <Section label="Career Chain">
                            {/* One child of the Section, so the warning sits directly under the toggle with no divider between them. */}
                            <View>
                                <ToggleSetting
                                    id="career-setup-start-next-career"
                                    title="Start the Next Career When Done"
                                    description="Completes each career on its end screen and starts the next one with these settings, until TP runs out. A finished Independent Training is always claimed and completed first."
                                    checked={settings.startNextCareerWhenDone}
                                    onCheckedChange={(checked) => updateCareerSetup({ startNextCareerWhenDone: checked })}
                                />
                                {settings.startNextCareerWhenDone && !skills.plans.careerComplete.enabled && (
                                    <WarningContainer style={{ marginBottom: SPACING.md }}>
                                        Warning: The bot completes each career without stopping, so any skill points left at the end are lost. Turn on the Career Complete skill plan to spend them
                                        first.
                                    </WarningContainer>
                                )}
                            </View>
                            <ToggleSetting
                                id="dailies-start-career-when-done"
                                title="Start a Career After the Dailies"
                                description="Starts a new career for the Home screen's scenario once the dailies finish, using these settings, then plays it."
                                checked={dailiesSettings.startCareerWhenDone}
                                onCheckedChange={(checked) => updateDailies({ startCareerWhenDone: checked })}
                            />
                        </Section>

                        <Section label="Trainee">
                            <ToggleSetting
                                id="career-setup-use-preselected-trainee"
                                title="Use Preselected Trainee"
                                description="Keeps whichever outfit the game has selected on Trainee Select. With the Smart Race Solver on, the run stops if it is not the solver's character."
                                checked={settings.usePreselectedTrainee}
                                onCheckedChange={(checked) => updateCareerSetup({ usePreselectedTrainee: checked })}
                            />
                            <SearchableItem id="career-setup-trainee" title="Trainee" description="The outfit to pick on Trainee Select. Also sets the Smart Race Solver's character and aptitudes.">
                                <Row
                                    title="Trainee"
                                    description="The outfit to pick on Trainee Select. Also sets the Smart Race Solver's character and aptitudes."
                                    disabled={settings.usePreselectedTrainee}
                                    onPress={() => show("trainee")}
                                    right={pill(labelOf(outfit) ?? "Not set")}
                                />
                            </SearchableItem>
                        </Section>

                        <Section label="Legacy">
                            <SearchableItem id="career-setup-legacy-mode" title="Legacy Parents" description="Keep the last parents, pick them by the trainee's needs, or by your own wish list.">
                                <Row title="Legacy Parents" description={mode.description} onPress={() => show("legacyMode")} right={pill(mode.label)} />
                            </SearchableItem>
                            <SearchableItem id="career-setup-spark-wish-list" title="Spark Wish List" description="Sparks to look for in the two parents, most wanted first.">
                                <Pressable
                                    style={[styles.selectorRow, settings.legacyMode !== "custom" && styles.disabled]}
                                    disabled={settings.legacyMode !== "custom"}
                                    onPress={() => show("wishList")}
                                    android_ripple={{ color: colors.ripple, foreground: true }}
                                >
                                    <View style={styles.selectorMain}>
                                        <Text style={styles.selectorTitle}>Spark Wish List</Text>
                                        <Text style={styles.selectorDescription}>{wishDescription}</Text>
                                        {shownSparks.length > 0 ? (
                                            <View style={styles.selectorChips}>
                                                {shownSparks.map((spark, i) => (
                                                    <View key={spark} style={styles.selectorChip}>
                                                        <CountBadge count={i + 1} />
                                                        <Text style={styles.selectorChipText}>{spark}</Text>
                                                    </View>
                                                ))}
                                            </View>
                                        ) : null}
                                    </View>
                                    <Ionicons name="chevron-forward" size={16} color={colors.textMuted} />
                                </Pressable>
                            </SearchableItem>
                        </Section>

                        <Section label="Support Formation">
                            <SearchableItem id="career-setup-deck" title="Deck" description="The support deck to start the career with.">
                                <Row
                                    title="Deck"
                                    description="The support deck to start the career with."
                                    onPress={() => show("deck")}
                                    right={pill(settings.deckNumber > 0 ? `Deck ${settings.deckNumber}` : "Keep current")}
                                />
                            </SearchableItem>
                            <SearchableItem id="career-setup-friend-card" title="Friend Card" description="The support card to borrow from the friend list.">
                                <Row
                                    title="Friend Card"
                                    description={
                                        friend
                                            ? `${friend.type} ${friend.rarity}. If it never appears, the best ${friend.type} card is borrowed instead.`
                                            : "Borrows the scenario's favorite cards, else the best card of the type your deck has fewest of."
                                    }
                                    onPress={() => show("friend")}
                                    right={pill(labelOf(friend) ?? "Smart")}
                                />
                            </SearchableItem>
                            <View style={styles.sliderShell}>
                                <CustomSlider
                                    searchId="career-setup-friend-max-refreshes"
                                    value={settings.friendMaxRefreshes}
                                    placeholder={defaultSettings.careerSetup.friendMaxRefreshes}
                                    onValueChange={(value) => updateCareerSetup({ friendMaxRefreshes: value })}
                                    min={0}
                                    max={10}
                                    step={1}
                                    label="Max Refreshes"
                                    showValue={true}
                                    showLabels={true}
                                    description="How many times to refresh the friend list looking for the card before falling back."
                                />
                            </View>
                        </Section>
                    </View>
                </ScrollView>
            </SearchPageProvider>

            <SheetModal
                visible={openSheet === "trainee"}
                onRequestClose={close}
                header={<ModalHeader title="TRAINEE" onClose={close} />}
                subHeader={searchBox("Search outfits or trainees...")}
                footer={null}
            >
                <View style={modalShellStyles.modalBodyList}>
                    {shownOutfits.map((o) => (
                        <ModalRadioRow
                            key={o.id}
                            tag={o.title}
                            label={o.name}
                            selected={o.id === settings.trainee}
                            onPress={() => {
                                updateCareerSetup({ trainee: o.id })
                                updateRacing(solverPresetFor(o))
                                close()
                            }}
                        />
                    ))}
                    {shownOutfits.length === 0 ? <Text style={styles.emptyText}>No matches.</Text> : null}
                </View>
            </SheetModal>

            <SheetModal visible={openSheet === "legacyMode"} onRequestClose={close} header={<ModalHeader title="LEGACY PARENTS" onClose={close} />} footer={null}>
                <View style={modalShellStyles.modalBodyList}>
                    {LEGACY_MODES.map((m) => (
                        <ModalRadioRow
                            key={m.value}
                            label={m.label}
                            description={m.description}
                            selected={m.value === settings.legacyMode}
                            onPress={() => {
                                updateCareerSetup({ legacyMode: m.value })
                                close()
                            }}
                        />
                    ))}
                </View>
            </SheetModal>

            <SheetModal
                visible={openSheet === "wishList"}
                onRequestClose={close}
                header={<ModalHeader title="SPARK WISH LIST" onClose={close} />}
                footer={
                    <View style={styles.modalFooterRow}>
                        <ModalFooterChip label="Reset" tone="danger" onPress={() => updateCareerSetup({ sparkWishList: defaultSettings.careerSetup.sparkWishList })} />
                        <ModalFooterChip label="Select All" onPress={() => updateCareerSetup({ sparkWishList: SPARK_ITEMS.map((s) => s.id) })} />
                    </View>
                }
            >
                <DraggablePriorityList
                    items={SPARK_ITEMS}
                    selectedItems={settings.sparkWishList}
                    onSelectionChange={(next) => updateCareerSetup({ sparkWishList: next })}
                    onOrderChange={(next) => updateCareerSetup({ sparkWishList: next })}
                />
            </SheetModal>

            <SheetModal visible={openSheet === "deck"} onRequestClose={close} header={<ModalHeader title="DECK" onClose={close} />} footer={null}>
                <View style={modalShellStyles.modalBodyList}>
                    {DECK_OPTIONS.map((d) => (
                        <ModalRadioRow
                            key={d.value}
                            label={d.label}
                            selected={d.value === settings.deckNumber}
                            onPress={() => {
                                updateCareerSetup({ deckNumber: d.value })
                                close()
                            }}
                        />
                    ))}
                </View>
            </SheetModal>

            <SheetModal
                visible={openSheet === "friend"}
                onRequestClose={close}
                header={<ModalHeader title="FRIEND CARD" onClose={close} />}
                subHeader={searchBox("Search cards or trainees...")}
                footer={null}
            >
                <View style={modalShellStyles.modalBodyList}>
                    {search.trim() === "" ? (
                        <ModalRadioRow
                            label="Smart"
                            description="The scenario's favorite cards, else the best card of the type your deck has fewest of."
                            selected={settings.friendCard === ""}
                            onPress={() => {
                                updateCareerSetup({ friendCard: "" })
                                close()
                            }}
                        />
                    ) : null}
                    {shownCards.map((c) => (
                        <ModalRadioRow
                            key={c.id}
                            tag={`${c.type} ${c.rarity}`}
                            label={labelOf(c)!}
                            selected={c.id === settings.friendCard}
                            onPress={() => {
                                updateCareerSetup({ friendCard: c.id })
                                close()
                            }}
                        />
                    ))}
                </View>
            </SheetModal>
        </View>
    )
}

export default CareerSetupSettings
