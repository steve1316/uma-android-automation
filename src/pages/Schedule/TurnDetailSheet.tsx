import { memo, useMemo } from "react"
import { View, Text, Pressable, StyleSheet } from "react-native"
import { Divider } from "react-native-paper"
import { useTheme } from "../../context/ThemeContext"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalHeader } from "../../components/ui/modal-header"
import { Row } from "../../components/ui/row"
import { Switch } from "../../components/ui/switch"
import CustomButton from "../../components/CustomButton"
import { SCHEDULE_SOURCES, planClaim, type ScheduleModel } from "../../lib/schedule/registry"
import type { ScheduleMutators, ScheduleSourceContext, ScheduleSourceId } from "../../lib/schedule/types"
import { formatCareerTurn, shortenRaceName, formatGradeLabel, gradeColor, TRAIN_LOCK_SENTINEL, type RaceEntry } from "../../lib/solver/constants"
import { raceProgressionLines } from "./raceEditing"
import { SPACING } from "../../lib/spacing"
import { TYPE } from "../../lib/type"

/** Source lookup by id so the sheet can call claim/release for any source. */
const SOURCE_BY_ID = Object.fromEntries(SCHEDULE_SOURCES.map((source) => [source.id, source])) as Record<ScheduleSourceId, (typeof SCHEDULE_SOURCES)[number]>

/** Props for `TurnDetailSheet`. */
interface TurnDetailSheetProps {
    /** The selected turn, or null when the sheet is closed. */
    turn: number | null
    /** The merged schedule model. */
    model: ScheduleModel
    /** The source context (read-only inputs). */
    ctx: ScheduleSourceContext
    /** The settings writers. */
    mutators: ScheduleMutators
    /** Eligible races bucketed by turn, for the "Switch to an eligible race" alternatives list. */
    eligibleRacesByTurn: Map<number, RaceEntry[]>
    /** Epithet names visible under the active scenario/character gates, for the epithet-progression list. */
    allowedEpithetNames: Set<string>
    /** Parsed SRS manual-locks map (turn -> race name or the train sentinel), for the Lock/Unlock button state. */
    manualLocks: Record<string, string>
    /** Close the sheet. */
    onClose: () => void
}

/** The modal title for a turn, plus whether that turn is a recreation/Pure-Passion date. */
interface ModalTitleResult {
    /** The rendered title string, e.g. "T12 · Classic Jun (1)" or "Recreation date #3". */
    title: string
    /** Whether the turn is a recreation date or the Pure Passion date. */
    isRecreation: boolean
}

/**
 * Owner-aware modal title. Recreation dates read "Recreation date #N" (Pure Passion adds " - Pure Passion"), where N is the 1-based position of the
 * turn in the ascending union of recreation + pure-passion turns. Any other turn reads "T{turn} · {formatCareerTurn(turn)}".
 * @param turn The selected 1-indexed turn.
 * @param recreationTurns The pinned recreation turns.
 * @param purePassionTurn The Pure Passion turn, or a non-positive value when unset.
 * @returns The modal title and whether the turn is a recreation/Pure-Passion date.
 */
function modalTitle(turn: number, recreationTurns: number[], purePassionTurn: number): ModalTitleResult {
    const isPure = purePassionTurn > 0 && turn === purePassionTurn
    const isRec = isPure || recreationTurns.includes(turn)
    if (!isRec) return { title: `T${turn} · ${formatCareerTurn(turn)}`, isRecreation: false }
    const all = Array.from(new Set([...recreationTurns, ...(purePassionTurn > 0 ? [purePassionTurn] : [])])).sort((a, b) => a - b)
    const n = all.indexOf(turn) + 1
    const title = isPure ? `Recreation date #${n} - Pure Passion` : `Recreation date #${n}`
    return { title, isRecreation: true }
}

/** Props for `RaceLine`. */
interface RaceLineProps {
    /** Race grade shown in the badge, e.g. "G1", "PRE_OP". */
    grade: string
    /** Display name, shortened via `shortenRaceName` before rendering. */
    name: string
    /** Full race entry for the meta line (track / terrain / distance / fans), or undefined to omit the meta line. */
    race: RaceEntry | undefined
    /** Style for the name text (e.g. `styles.raceName` or `styles.altName`). */
    nameStyle: object
    /** Style for the meta text (e.g. `styles.raceMeta` or `styles.altMeta`). */
    metaStyle: object
    /** Style sheet from the parent, for the shared grade-badge styles. */
    styles: any
}

/**
 * Shared race-line content: grade badge, shortened name, and a "track · terrain · distanceType (Xm) · fans" meta line. Used for both the
 * scheduled-race card and each eligible-race alternative row - the caller supplies the outer wrapper (View or Pressable) and per-context styles.
 * @param props The `RaceLineProps` for this line.
 * @returns The grade badge, name, and meta line.
 */
function RaceLine({ grade, name, race, nameStyle, metaStyle, styles }: RaceLineProps) {
    const { colors } = useTheme()
    return (
        <>
            <View style={[styles.gradeBadge, { backgroundColor: gradeColor(grade) ?? colors.brand }]}>
                <Text style={styles.gradeBadgeText}>{formatGradeLabel(grade)}</Text>
            </View>
            <View style={{ flex: 1 }}>
                <Text style={nameStyle}>{shortenRaceName(name)}</Text>
                {race && (
                    <Text style={metaStyle}>
                        {race.raceTrack} · {race.terrain} · {race.distanceType} ({race.distanceMeters}m) · {race.fans.toLocaleString()} fans
                    </Text>
                )}
            </View>
        </>
    )
}

/**
 * Per-turn editor for the Schedule screen. Shows the turn's current owner, an SRS race section (race card, epithet progression, eligible-race
 * alternatives, and Lock/Delete actions) when a race is scheduled, and a toggle row per non-race source. Enforces one owner per action turn: turning
 * on a source that collides with an existing owner prompts an override that clears it first. A mandatory turn is read-only. Stop-at-date coexists.
 * @param turn The selected turn, or null.
 * @param model The merged schedule model.
 * @param ctx The source context.
 * @param mutators The settings writers.
 * @param eligibleRacesByTurn Eligible races bucketed by turn, for the alternatives list.
 * @param allowedEpithetNames Epithet names visible under the active scenario/character gates.
 * @param manualLocks Parsed SRS manual-locks map, for the Lock/Unlock button state.
 * @param onClose Close handler.
 * @returns The detail sheet modal.
 */
function TurnDetailSheet({ turn, model, ctx, mutators, eligibleRacesByTurn, allowedEpithetNames, manualLocks, onClose }: TurnDetailSheetProps) {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                subLine: { ...TYPE.caption, color: colors.textMuted, marginBottom: SPACING.sm },
                ownerBox: {
                    marginVertical: SPACING.sm,
                    padding: SPACING.md,
                    borderRadius: 8,
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    flexDirection: "row",
                    alignItems: "center",
                    gap: 8,
                },
                ownerText: { ...TYPE.body, color: colors.text },
                lockedNote: { ...TYPE.body, color: colors.warning, textAlign: "center", marginTop: SPACING.md },
                raceCard: {
                    flexDirection: "row",
                    alignItems: "center",
                    marginTop: SPACING.sm,
                    padding: SPACING.md,
                    borderRadius: 8,
                    backgroundColor: colors.surfaceRaised,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    gap: 8,
                },
                gradeBadge: { minWidth: 34, height: 20, borderRadius: 4, paddingHorizontal: 4, alignItems: "center", justifyContent: "center" },
                gradeBadgeText: { color: "#fff", fontSize: 10, fontWeight: "700" },
                raceName: { ...TYPE.body, fontWeight: "700", color: colors.text },
                raceMeta: { ...TYPE.caption, color: colors.textMuted, marginTop: 2 },
                sectionLabel: { ...TYPE.body, fontWeight: "700", color: colors.text, marginTop: SPACING.md, marginBottom: SPACING.xs },
                epithetLine: { ...TYPE.body, color: colors.text, marginTop: 2 },
                epithetPending: { ...TYPE.caption, color: colors.textMuted, fontStyle: "italic", marginTop: 1 },
                emptyNote: { ...TYPE.caption, color: colors.textMuted, fontStyle: "italic", marginTop: 2 },
                altRow: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: 8,
                    paddingVertical: 6,
                    paddingHorizontal: 4,
                    borderRadius: 6,
                    borderBottomWidth: StyleSheet.hairlineWidth,
                    borderColor: colors.borderHair,
                },
                altName: { ...TYPE.body, fontWeight: "600", color: colors.text },
                altMeta: { ...TYPE.caption, color: colors.textMuted },
                actionRow: { flexDirection: "row", gap: 8, marginTop: SPACING.md },
                lockButton: { borderWidth: 1, borderColor: colors.borderStrong },
                hint: { ...TYPE.caption, color: colors.textMuted, fontStyle: "italic", marginTop: SPACING.md, textAlign: "center" },
                displacedLabel: { ...TYPE.caption, color: colors.textMuted, fontStyle: "italic", marginTop: SPACING.md, marginBottom: SPACING.xs },
                displacedCard: { opacity: 0.55 },
            }),
        [colors]
    )

    const merged = turn != null ? model.byTurn.get(turn) : undefined
    const owner = merged?.owner
    const isMandatory = owner?.ownership === "mandatory"
    const stopPresent = !!merged?.annotations.some((event) => event.sourceId === "stopAtDate")

    const { title, isRecreation } = turn != null ? modalTitle(turn, ctx.general.recreationTurns, ctx.general.purePassionTurn) : { title: "", isRecreation: false }

    const entry = turn != null ? ctx.preview?.decisions?.[String(turn)] : undefined
    const race = entry?.type === "Race" && entry.raceKey ? ctx.racesByKey[entry.raceKey] : undefined
    const hasRace = !!race || isMandatory
    const raceGrade = race?.grade ?? owner?.badge ?? ""
    const lockedValue = turn != null ? manualLocks[String(turn)] : undefined
    const isLocked = lockedValue != null && lockedValue !== TRAIN_LOCK_SENTINEL
    const raceName = race?.name ?? owner?.variant ?? owner?.label
    const progression = useMemo(
        () => (race && ctx.preview ? raceProgressionLines(race, turn!, ctx.preview, ctx.racesByKey, allowedEpithetNames) : []),
        [race, turn, ctx.preview, ctx.racesByKey, allowedEpithetNames]
    )
    const alternatives = useMemo(() => (turn != null ? (eligibleRacesByTurn.get(turn) ?? []).filter((r) => !race || r.name !== race.name) : []), [turn, eligibleRacesByTurn, race])

    /** Apply a claim/release for a source, enforcing one owner per action turn. A conflicting owner (another date, or a stop) is replaced silently - no confirmation prompt. */
    const toggle = (sourceId: ScheduleSourceId, isOn: boolean, variant?: string) => {
        if (turn == null) return
        const source = SOURCE_BY_ID[sourceId]
        if (isOn) {
            source.releaseTurn?.(turn, ctx, mutators)
            return
        }
        const plan = planClaim(turn, sourceId, model)
        if (plan.kind === "blocked-mandatory") return
        if (plan.kind === "conflict") SOURCE_BY_ID[plan.owner.sourceId].releaseTurn?.(turn, ctx, mutators)
        source.claimTurn?.(turn, ctx, mutators, variant)
    }

    const lockRace = () => raceName && turn != null && SOURCE_BY_ID.srs.claimTurn?.(turn, ctx, mutators, raceName)
    const unlockRace = () => turn != null && SOURCE_BY_ID.srs.releaseTurn?.(turn, ctx, mutators)
    const deleteRace = () => turn != null && SOURCE_BY_ID.srs.claimTurn?.(turn, ctx, mutators, TRAIN_LOCK_SENTINEL)
    const switchRace = (altName: string) => turn != null && SOURCE_BY_ID.srs.claimTurn?.(turn, ctx, mutators, altName)

    // The race card renders for scheduled races except on recreation/pure-passion turns (those own the turn instead). An auto SRS race has no explicit owner,
    // so the owner box would otherwise read "Nothing scheduled" right above the race card. Hide it in that case, but keep it for real owners and empty turns.
    // A recreation/Pure-Passion date or a stop halts the turn, displacing the solver's race - show it greyed (not editable). Mandatory races keep their own immovable section.
    const displacedByDate = owner?.sourceId === "recreation" || owner?.sourceId === "purePassion"
    const displacedRace = !isMandatory && race && (displacedByDate || stopPresent) ? race : undefined
    const displacedLabelText = displacedByDate ? "Smart Race Solver would run this race, but your date takes priority:" : "Smart Race Solver would run this race, but the bot stops here:"
    const showRaceSection = hasRace && !displacedRace
    const showOwnerBox = owner != null || (!showRaceSection && !displacedRace)
    const ownerLabel = owner ? `${owner.marker} ${owner.label}` : "Nothing scheduled"

    return (
        <SheetModal visible={turn != null} onRequestClose={onClose} heightFraction={0.62} widthFraction={0.7} header={<ModalHeader title={title} onClose={onClose} />} footer={null}>
            {turn != null && isRecreation && (
                <Text style={styles.subLine}>
                    T{turn} · {formatCareerTurn(turn)}
                </Text>
            )}

            {showOwnerBox && (
                <View style={styles.ownerBox}>
                    <Text style={styles.ownerText}>{ownerLabel}</Text>
                </View>
            )}

            {showRaceSection && (
                <>
                    <View style={styles.raceCard}>
                        <RaceLine grade={raceGrade} name={raceName ?? ""} race={race} nameStyle={styles.raceName} metaStyle={styles.raceMeta} styles={styles} />
                    </View>

                    <Text style={styles.sectionLabel}>Progresses these epithets</Text>
                    {progression.length === 0 ? (
                        <Text style={styles.emptyNote}>None - this race does not match any tracked epithet matcher.</Text>
                    ) : (
                        progression.map((p) => (
                            <View key={p.epithet}>
                                <Text style={styles.epithetLine}>
                                    • {p.progLabel}
                                    {p.epithet}
                                    {p.conditions.length ? ` - ${p.conditions.join("; ")}` : ""}
                                </Text>
                                {p.pending.map((line) => (
                                    <Text key={line} style={styles.epithetPending}>
                                        {"      "}* Still pending: {line}
                                    </Text>
                                ))}
                            </View>
                        ))
                    )}

                    {!isMandatory && (
                        <>
                            <Divider style={{ marginTop: SPACING.md }} />
                            <Text style={styles.sectionLabel}>Switch to an eligible race</Text>
                            {alternatives.length === 0 ? (
                                <Text style={styles.emptyNote}>No other eligible races on this turn.</Text>
                            ) : (
                                alternatives.map((alt) => (
                                    <Pressable key={alt.name} style={styles.altRow} onPress={() => switchRace(alt.name)} android_ripple={{ color: colors.ripple, foreground: true }}>
                                        <RaceLine grade={alt.grade} name={alt.name} race={alt} nameStyle={styles.altName} metaStyle={styles.altMeta} styles={styles} />
                                    </Pressable>
                                ))
                            )}
                        </>
                    )}

                    {isMandatory ? (
                        <Text style={styles.lockedNote}>📌 Locked to a mandatory career race - it cannot be changed.</Text>
                    ) : (
                        <>
                            <View style={styles.actionRow}>
                                <CustomButton variant={isLocked ? "secondary" : "default"} size="sm" style={styles.lockButton} onPress={isLocked ? unlockRace : lockRace}>
                                    {isLocked ? "Unlock" : "Lock"}
                                </CustomButton>
                                <CustomButton variant="destructive" size="sm" onPress={deleteRace}>
                                    Delete
                                </CustomButton>
                            </View>
                        </>
                    )}
                </>
            )}

            {displacedRace && (
                <>
                    <Text style={styles.displacedLabel}>{displacedLabelText}</Text>
                    <View style={[styles.raceCard, styles.displacedCard]}>
                        <RaceLine grade={displacedRace.grade} name={displacedRace.name} race={displacedRace} nameStyle={styles.raceName} metaStyle={styles.raceMeta} styles={styles} />
                    </View>
                </>
            )}

            <Row
                title="Recreation date"
                description="A support-card recreation outing on this turn. These dates come from certain support cards."
                disabled={isMandatory}
                right={<Switch checked={owner?.sourceId === "recreation"} disabled={isMandatory} onCheckedChange={() => toggle("recreation", owner?.sourceId === "recreation")} />}
            />
            <Row
                title="Recreation date - Pure Passion"
                description="The final date for the Heirs to the Throne support card. Grants the Pure Passion buff (Friendship Training regardless of bond). Recommended for Summer Training."
                disabled={isMandatory}
                right={<Switch checked={owner?.sourceId === "purePassion"} disabled={isMandatory} onCheckedChange={() => toggle("purePassion", owner?.sourceId === "purePassion")} />}
            />
            <Row title="Stop bot here" description="Stops the bot when it reaches this turn." right={<Switch checked={stopPresent} onCheckedChange={() => toggle("stopAtDate", stopPresent)} />} />

            {showRaceSection && !isMandatory && <Text style={styles.hint}>Changes take effect after tapping Apply Changes.</Text>}
        </SheetModal>
    )
}

export default memo(TurnDetailSheet)
