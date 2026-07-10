import { useContext, useMemo, useState, useEffect, useRef, useCallback } from "react"
import { View, Text, ScrollView, Pressable, StyleSheet } from "react-native"
import { useTheme } from "../../../context/ThemeContext"
import { RacingContext, defaultSettings } from "../../../context/BotStateContext"
import { Input } from "../../../components/ui/input"
import { APTITUDE_RANKS, CharacterPresetEntry } from "../../../lib/solver/constants"
import characterPresetsData from "../../../data/characterPresets.json"
import { SPACING } from "../../../lib/spacing"

/** All character presets from the bundled data, computed once. */
const ALL_PRESETS: CharacterPresetEntry[] = Object.values(characterPresetsData) as CharacterPresetEntry[]

/** Distance/surface filter chips for narrowing the preset list. */
const DISTANCE_CHIPS: { key: "all" | "Sprint" | "Mile" | "Medium" | "Long" | "Dirt"; label: string }[] = [
    { key: "all", label: "All" },
    { key: "Sprint", label: "Sprint" },
    { key: "Mile", label: "Mile" },
    { key: "Medium", label: "Medium" },
    { key: "Long", label: "Long" },
    { key: "Dirt", label: "Dirt" },
]

/** Props for `CharacterPresetSelector`. */
interface CharacterPresetSelectorProps {
    /** Called after a preset is applied, so a host (e.g. the Trainee sheet) can dismiss itself on selection. */
    onSelect?: () => void
}

/**
 * The trainee/character-preset selector: distance/surface filter chips, a name search box, and a scrolling list of presets. Picking one saves the preset name and seeds the six
 * aptitude slots from its defaults. Self-contained - reads and writes `RacingContext` directly - so it can be dropped anywhere (the Schedule page's Trainee sheet) with only an
 * optional `onSelect`. The selected preset drives the calendar's mandatory races and is not gated by the Smart Race Solver toggle.
 * @param onSelect Optional callback fired after a preset is applied.
 * @returns The character-preset selector body.
 */
function CharacterPresetSelector({ onSelect }: CharacterPresetSelectorProps) {
    const { colors } = useTheme()
    const { racing, updateRacing } = useContext(RacingContext)
    const racingSettings = useMemo(() => ({ ...defaultSettings.racing, ...racing }), [racing])
    const selected = racingSettings.smartRaceSolverCharacterPreset

    const [presetSearch, setPresetSearch] = useState("")
    const [distanceFilter, setDistanceFilter] = useState<(typeof DISTANCE_CHIPS)[number]["key"]>("all")

    // Refs backing the snap-to-active-preset behaviour on open. The list can be long, so we remember each row's measured y-offset via onLayout and snap the list to the active
    // preset the first time it lays out. presetForFocusRef holds the active name in a ref so the snap helper stays identity-stable and its effect never re-runs on selection.
    const presetScrollRef = useRef<ScrollView>(null)
    const presetLayoutsRef = useRef<Map<string, number>>(new Map())
    const didInitialPresetScrollRef = useRef(false)
    const presetForFocusRef = useRef<string>("")

    const filteredPresets = useMemo(() => {
        let list = ALL_PRESETS
        if (distanceFilter !== "all") {
            list = list.filter((p) => {
                const rank = distanceFilter === "Dirt" ? p.surfaceAptitudes.Dirt : p.distanceAptitudes[distanceFilter]
                return APTITUDE_RANKS.indexOf(rank) <= APTITUDE_RANKS.indexOf("A")
            })
        }
        if (presetSearch) {
            const q = presetSearch.toLowerCase()
            list = list.filter((p) => p.name.toLowerCase().includes(q))
        }
        return list
    }, [presetSearch, distanceFilter])

    /**
     * Apply a character preset by saving its name and seeding the six aptitude slots (four distance + two surface) from the preset's defaults. The user can still override
     * individual aptitudes afterwards on the Race Solver tab.
     * @param preset The character preset whose name and aptitudes are written into the racing settings.
     */
    const applyPreset = (preset: CharacterPresetEntry) => {
        updateRacing({
            smartRaceSolverCharacterPreset: preset.name,
            smartRaceSolverAptitudes: JSON.stringify({
                Sprint: preset.distanceAptitudes.Sprint,
                Mile: preset.distanceAptitudes.Mile,
                Medium: preset.distanceAptitudes.Medium,
                Long: preset.distanceAptitudes.Long,
                Turf: preset.surfaceAptitudes.Turf,
                Dirt: preset.surfaceAptitudes.Dirt,
            }),
        })
        onSelect?.()
    }

    /**
     * Snaps the preset list to the currently-selected preset on first open. Reads the target name from `presetForFocusRef` so this callback's identity is stable and its effect
     * does not re-run on selection. Bails until the active row's onLayout has measured a y-offset; once the snap fires it locks itself off so the user's own scrolling is not yanked.
     */
    const maybeScrollToActivePreset = useCallback(() => {
        if (didInitialPresetScrollRef.current) return
        const target = presetForFocusRef.current
        if (!target) return
        const y = presetLayoutsRef.current.get(target)
        if (y == null) return
        presetScrollRef.current?.scrollTo({ y: Math.max(0, y - 8), animated: false })
        didInitialPresetScrollRef.current = true
    }, [])

    // Mirror the active preset into a ref so `maybeScrollToActivePreset` can read it without a state dep, keeping the snap effect from re-running on every selection.
    useEffect(() => {
        presetForFocusRef.current = selected || ""
    }, [selected])

    useEffect(() => {
        maybeScrollToActivePreset()
    }, [maybeScrollToActivePreset])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                description: { fontSize: 13, color: colors.textMuted, marginBottom: SPACING.md },
                input: { backgroundColor: colors.bg, color: colors.text, marginBottom: 4 },
                inputDescription: { fontSize: 12, color: colors.textMuted, marginBottom: 4 },
                distanceChip: {
                    paddingHorizontal: SPACING.md,
                    paddingVertical: SPACING.xs,
                    borderRadius: 20,
                    borderWidth: 1,
                    borderColor: colors.border,
                    backgroundColor: colors.surface,
                    overflow: "hidden",
                },
                distanceChipActive: { backgroundColor: colors.brand, borderColor: colors.brand },
                distanceChipText: { color: colors.text, fontSize: 12, fontWeight: "600" },
                distanceChipTextActive: { color: colors.onBrand, fontSize: 12, fontWeight: "700" },
                presetList: {
                    flex: 1,
                    marginBottom: 8,
                    borderWidth: StyleSheet.hairlineWidth,
                    borderColor: colors.borderHair,
                    borderRadius: 6,
                },
                presetItem: {
                    paddingVertical: 8,
                    paddingHorizontal: 6,
                    borderBottomWidth: StyleSheet.hairlineWidth,
                    borderColor: colors.borderHair,
                },
                presetItemActive: { backgroundColor: colors.brand },
                presetName: { color: colors.text, fontSize: 14 },
                presetNameActive: { color: colors.onBrand, fontSize: 14, fontWeight: "700" },
                presetAptitudes: { color: colors.textMuted, fontSize: 11, marginTop: 2 },
            }),
        [colors]
    )

    return (
        <View style={{ paddingHorizontal: SPACING.md, flex: 1 }}>
            <Text style={styles.description}>Selected: {selected || "(none)"}</Text>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ flexGrow: 0 }} contentContainerStyle={{ gap: SPACING.xs, marginBottom: SPACING.sm }}>
                {DISTANCE_CHIPS.map((c) => (
                    <Pressable
                        key={c.key}
                        onPress={() => setDistanceFilter(c.key)}
                        style={[styles.distanceChip, distanceFilter === c.key && styles.distanceChipActive]}
                        android_ripple={{ color: colors.ripple, foreground: true }}
                    >
                        <Text style={[styles.distanceChipText, distanceFilter === c.key && styles.distanceChipTextActive]}>{c.label}</Text>
                    </Pressable>
                ))}
            </ScrollView>
            <Input style={styles.input} value={presetSearch} onChangeText={setPresetSearch} placeholder="Search characters..." />
            <ScrollView ref={presetScrollRef} style={styles.presetList} keyboardShouldPersistTaps="handled">
                {filteredPresets.map((p) => {
                    const active = selected === p.name
                    return (
                        <Pressable
                            key={p.name}
                            style={[styles.presetItem, active && styles.presetItemActive]}
                            android_ripple={{ color: colors.ripple, foreground: true }}
                            onPress={() => applyPreset(p)}
                            onLayout={(e) => {
                                presetLayoutsRef.current.set(p.name, e.nativeEvent.layout.y)
                                if (active) maybeScrollToActivePreset()
                            }}
                        >
                            <Text style={active ? styles.presetNameActive : styles.presetName}>{p.name}</Text>
                            <Text style={styles.presetAptitudes}>
                                Sprint {p.distanceAptitudes.Sprint} · Mile {p.distanceAptitudes.Mile} · Med {p.distanceAptitudes.Medium} · Long {p.distanceAptitudes.Long} · Turf{" "}
                                {p.surfaceAptitudes.Turf} · Dirt {p.surfaceAptitudes.Dirt}
                            </Text>
                        </Pressable>
                    )
                })}
                {presetSearch && filteredPresets.length === 0 && <Text style={styles.inputDescription}>No matches.</Text>}
            </ScrollView>
            <Text style={styles.inputDescription}>
                Showing {filteredPresets.length} preset{filteredPresets.length === 1 ? "" : "s"}.
            </Text>
        </View>
    )
}

export default CharacterPresetSelector
