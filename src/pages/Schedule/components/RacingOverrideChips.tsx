import { memo, useMemo } from "react"
import { View, Text, StyleSheet } from "react-native"
import { useNavigation, CommonActions } from "@react-navigation/native"
import { Dumbbell, ListChecks, OctagonX, Repeat, RotateCcw, Zap } from "lucide-react-native"
import type { LucideIcon } from "lucide-react-native"
import { useTheme } from "../../../context/ThemeContext"
import { Settings } from "../../../context/BotStateContext"
import { TintedChip } from "../../../components/ui/tinted-chip"
import { SPACING } from "../../../lib/spacing"
import { TYPE } from "../../../lib/type"

/** How badly an override interferes with the solver: `critical` stops it from running at all, `warning` drops individual races, `info` is advisory. */
type OverrideSeverity = "critical" | "warning" | "info"

/** One Racing setting that changes how the Smart Race Solver behaves at runtime. */
interface RacingOverride {
    /** Key in the racing settings slice whose truthiness arms this chip. */
    key: keyof Settings["racing"]
    /** Uppercase mono label shown in the chip. */
    label: string
    /** Lucide icon rendered ahead of the label. */
    icon: LucideIcon
    /** Drives the chip tint. */
    severity: OverrideSeverity
    /** `SearchableItem` id on the Racing Settings page that the chip deep-links to. */
    targetId: string
}

/**
 * The Racing Settings toggles that override or perturb the solver, ordered by how much damage they do. Each one is checked in the bot before the solver's
 * schedule is consulted, so an armed chip means the calculated schedule will not execute exactly as the calendar shows it.
 */
export const RACING_OVERRIDES: RacingOverride[] = [
    { key: "enableForceRacing", label: "Force Racing", icon: Zap, severity: "critical", targetId: "enable-force-racing" },
    { key: "enableUserInGameRaceAgenda", label: "In-Game Agenda", icon: ListChecks, severity: "critical", targetId: "enable-user-in-game-race-agenda" },
    { key: "enableG1DayPreference", label: "G1 Training", icon: Dumbbell, severity: "warning", targetId: "enable-g1-day-preference" },
    { key: "enableStopOnMandatoryRaces", label: "Stop on Mandatory", icon: OctagonX, severity: "warning", targetId: "enable-stop-on-mandatory-races" },
    { key: "disableRaceRetries", label: "No Race Retries", icon: RotateCcw, severity: "warning", targetId: "disable-race-retries" },
    { key: "ignoreConsecutiveRaceWarning", label: "Consecutive OK", icon: Repeat, severity: "info", targetId: "ignore-consecutive-race-warning" },
]

/** Props for `RacingOverrideChips`. */
export interface RacingOverrideChipsProps {
    /** The merged racing settings slice, used to decide which chips are armed. */
    racing: Settings["racing"]
}

/**
 * A wrapping row of chips naming the Racing Settings that are currently overriding the Smart Race Solver. Renders nothing when none are on. Each chip
 * deep-links to the setting that armed it, and is tinted by how much it interferes: red kills the solver outright, amber drops individual races, muted is advisory.
 * @param racing The merged racing settings slice.
 * @returns The chip row, or null when no override is active.
 */
function RacingOverrideChips({ racing }: RacingOverrideChipsProps) {
    const { colors } = useTheme()
    const navigation = useNavigation()

    // Severity is a fixed three-way scale, so resolve it against the theme here rather than baking colors into the module-level table.
    const { styles, tints } = useMemo(
        () => ({
            styles: StyleSheet.create({
                container: { paddingHorizontal: SPACING.md, paddingBottom: SPACING.md, gap: SPACING.sm },
                caption: { ...TYPE.caption, color: colors.textMuted },
                row: { flexDirection: "row", flexWrap: "wrap", gap: SPACING.xs },
            }),
            tints: { critical: colors.error, warning: colors.warning, info: colors.textMuted } as Record<OverrideSeverity, string>,
        }),
        [colors]
    )

    // Built once so each chip keeps a stable onPress and TintedChip's memo can bail out instead of re-rendering every chip on every parent render.
    const handlers = useMemo(
        () => new Map(RACING_OVERRIDES.map((override) => [override.key, () => navigation.dispatch(CommonActions.navigate({ name: "RacingSettings", params: { targetId: override.targetId } }))])),
        [navigation]
    )

    // An override only means anything while the solver owns the schedule, so the "solver is off" case is this component's own invariant rather than the caller's.
    const active = racing.enableSmartRaceSolver ? RACING_OVERRIDES.filter((override) => Boolean(racing[override.key])) : []
    if (active.length === 0) return null

    return (
        <View style={styles.container}>
            <Text style={styles.caption}>These Racing settings change how the solver runs:</Text>
            <View style={styles.row}>
                {active.map((override) => (
                    <TintedChip key={override.key} icon={override.icon} label={override.label} tint={tints[override.severity]} onPress={handlers.get(override.key)} />
                ))}
            </View>
        </View>
    )
}

// `racing` is the whole racing slice, so it changes identity on any racing write - including the solver weights and epithet edits on this very tab. Compare only the
// handful of fields this component reads so those edits skip the subtree entirely.
export default memo(
    RacingOverrideChips,
    (prev, next) => prev.racing.enableSmartRaceSolver === next.racing.enableSmartRaceSolver && RACING_OVERRIDES.every((override) => prev.racing[override.key] === next.racing[override.key])
)
