import { memo } from "react"
import { View, Text, Pressable } from "react-native"
import { useTheme } from "../../../context/ThemeContext"
import { APTITUDE_RANKS, AptitudeMap } from "../../../lib/solver/constants"

interface AptitudeRowProps {
    /** The aptitude slot this row controls (e.g. "Sprint", "Mile"). */
    slot: keyof AptitudeMap
    /** Display label (typically same as `slot`). */
    label: string
    /** Currently selected rank for this slot. */
    currentRank: string
    /** Called when the user picks a rank. */
    onChange: (slot: keyof AptitudeMap, rank: string) => void
    /** Style sheet from the parent (stable across renders). */
    styles: any
}

/**
 * Memoized row of rank buttons (S..G) for one aptitude slot.
 *
 * @param props The {@link AptitudeRowProps} for this row.
 * @returns The rendered aptitude row.
 */
export const AptitudeRow = memo(({ slot, label, currentRank, onChange, styles }: AptitudeRowProps) => {
    const { colors } = useTheme()
    return (
        <View style={styles.aptRow}>
            <Text style={styles.aptLabel}>{label}</Text>
            <View style={styles.aptButtons}>
                {APTITUDE_RANKS.map((rank) => {
                    const active = currentRank === rank
                    return (
                        <Pressable key={rank} style={[styles.aptBtn, active && styles.aptBtnActive]} onPress={() => onChange(slot, rank)} android_ripple={{ color: colors.ripple, foreground: true }}>
                            <Text style={active ? styles.aptBtnTextActive : styles.aptBtnText}>{rank}</Text>
                        </Pressable>
                    )
                })}
            </View>
        </View>
    )
})
AptitudeRow.displayName = "AptitudeRow"

interface EpithetChipProps {
    /** The epithet entry to render (data file row). */
    epithet: { name: string; bullet_points?: string[]; [k: string]: any }
    /** Whether this chip is currently in the parent's selected list. */
    selected: boolean
    /** Stable parent callback that flips the chip's selection state by name. */
    onToggle: (name: string) => void
    /** Style sheet from the parent (stable across renders). */
    styles: any
}

/**
 * Memoized chip for a single epithet in the target / forced pickers.
 *
 * @param props The {@link EpithetChipProps} for this chip.
 * @returns The rendered epithet chip.
 */
export const EpithetChip = memo(({ epithet, selected, onToggle, styles }: EpithetChipProps) => {
    const { colors } = useTheme()
    const bullets = epithet.bullet_points ?? []
    // Last bullet is the reward; earlier bullets are conditions.
    const conditionBullets = bullets.length > 1 ? bullets.slice(0, -1) : []
    const rewardBullet = bullets.length > 0 ? bullets[bullets.length - 1] : null
    // Red dot in the corner flags epithets the solver can't track or advance (see "Epithets without matchers" info block).
    const hasMatchers = (epithet.matchers ?? []).length > 0
    return (
        <Pressable style={[styles.chip, selected && styles.chipActive]} onPress={() => onToggle(epithet.name)} android_ripple={{ color: colors.ripple, foreground: true }}>
            {hasMatchers ? null : <View style={styles.chipNoMatcherDot} />}
            <Text style={selected ? styles.chipTextActive : styles.chipText}>{epithet.name}</Text>
            {conditionBullets.map((b, idx) => (
                <Text key={idx} style={selected ? styles.chipConditionActive : styles.chipCondition} numberOfLines={2}>
                    {b}
                </Text>
            ))}
            {rewardBullet ? (
                <Text style={selected ? styles.chipRewardActive : styles.chipReward} numberOfLines={2}>
                    {rewardBullet}
                </Text>
            ) : null}
        </Pressable>
    )
})
EpithetChip.displayName = "EpithetChip"
