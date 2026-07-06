import { memo, useContext, useState, useCallback } from "react"
import { View } from "react-native"
import { GeneralMiscContext } from "../../context/BotStateContext"
import { Row } from "../../components/ui/row"
import { Section } from "../../components/ui/section"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import { ValuePill } from "../../components/ui/value-pill"
import { ModalHeader } from "../../components/ui/modal-header"
import InfoContainer from "../../components/InfoContainer"
import SearchableItem from "../../components/SearchableItem"
import ToggleSetting from "../../components/ToggleSetting"
import { DATING_SCHEDULE_CUSTOM, DATING_SCHEDULE_PRESETS } from "../../lib/datingSchedule"
import { SPACING } from "../../lib/spacing"

/** Preset options for the recreation dating-schedule selector, plus a Custom entry for hand-editing the calendar. */
const datingPresetOptions = [...Object.entries(DATING_SCHEDULE_PRESETS).map(([value, preset]) => ({ label: preset.label, value })), { label: "Custom", value: DATING_SCHEDULE_CUSTOM }]

/**
 * Recreation tab: the Support Card Dating schedule config (enable toggle, catch-up, schedule preset picker, Pure Passion info, and total outings).
 * Per-turn recreation pinning is not here, it lives on the Calendar tab.
 * @returns The recreation tab body.
 */
function RecreationTab() {
    const { general, updateGeneral } = useContext(GeneralMiscContext)
    const modalShellStyles = useModalShellStyles()
    const [datingPresetPickerOpen, setDatingPresetPickerOpen] = useState(false)

    const handleDatingPresetChange = useCallback(
        (preset: string) => {
            const selected = DATING_SCHEDULE_PRESETS[preset]
            if (selected) {
                updateGeneral({
                    datingSchedulePreset: preset,
                    recreationTurns: [...selected.recreationTurns],
                    purePassionTurn: selected.purePassionTurn,
                    recreationTotalOutings: selected.totalOutings,
                })
            } else {
                updateGeneral({ datingSchedulePreset: DATING_SCHEDULE_CUSTOM, recreationTurns: [], purePassionTurn: -1 })
            }
        },
        [updateGeneral]
    )

    return (
        <View>
            <Section label="SUPPORT CARD DATING">
                <ToggleSetting
                    id="settings-dating-schedule"
                    title="Support Card Dating Schedule"
                    description="On a pinned turn the bot does a support-card recreation outing over every other action, including scheduled races (your in-game racing agenda or the Smart Race Solver). Only mandatory career-goal races take priority."
                    checked={general.enableDatingSchedule}
                    onCheckedChange={(checked) => updateGeneral({ enableDatingSchedule: checked })}
                />

                {general.enableDatingSchedule && (
                    <>
                        <ToggleSetting
                            id="settings-recreation-catch-up"
                            title="Catch Up On Missed Dates"
                            description="If a scheduled outing gets skipped (e.g. a mandatory race lands on it), make it up on the next available turn instead of losing it."
                            checked={general.enableRecreationCatchUp}
                            onCheckedChange={(checked) => updateGeneral({ enableRecreationCatchUp: checked })}
                        />

                        <SearchableItem
                            id="settings-dating-preset"
                            title="Schedule Preset"
                            description="Pick an optimized preset (Pure Passion timed for a summer camp) or Custom to hand-pick turns on the Schedule screen."
                            parentId="settings-dating-schedule"
                        >
                            <Row
                                title="Schedule Preset"
                                description="Pick an optimized preset (Pure Passion timed for a summer camp) or Custom to hand-pick turns on the Schedule screen."
                                onPress={() => setDatingPresetPickerOpen(true)}
                                right={<ValuePill label={datingPresetOptions.find((o) => o.value === general.datingSchedulePreset)?.label ?? "Custom"} />}
                            />
                            {general.purePassionTurn > 0 && (
                                <View style={{ paddingHorizontal: SPACING.md, paddingBottom: SPACING.md }}>
                                    <InfoContainer>
                                        Pure Passion activates when you complete the Heir to the Throne's final recreation date. For about 3 turns, Friendship Training occurs on a facility regardless
                                        of bond. This preset pins one date per outing and holds the final one for Senior June Late, so those turns land on Senior Summer Training where the gains matter
                                        most.
                                    </InfoContainer>
                                </View>
                            )}
                        </SearchableItem>
                    </>
                )}
            </Section>

            <SheetModal
                visible={datingPresetPickerOpen}
                onRequestClose={() => setDatingPresetPickerOpen(false)}
                header={<ModalHeader title="SCHEDULE PRESET" onClose={() => setDatingPresetPickerOpen(false)} />}
                footer={null}
            >
                <View style={modalShellStyles.modalBodyList}>
                    {datingPresetOptions.map((option) => (
                        <ModalRadioRow
                            key={option.value}
                            label={option.label}
                            selected={option.value === general.datingSchedulePreset}
                            onPress={() => {
                                handleDatingPresetChange(option.value)
                                setDatingPresetPickerOpen(false)
                            }}
                        />
                    ))}
                </View>
            </SheetModal>
        </View>
    )
}

export default memo(RecreationTab)
