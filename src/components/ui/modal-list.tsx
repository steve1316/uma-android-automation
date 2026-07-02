import React, { useMemo } from "react"
import { View, Text, Pressable, StyleSheet, ViewStyle } from "react-native"
import Ionicons from "@react-native-vector-icons/ionicons"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** Props for `ModalCheckRow`. */
export interface ModalCheckRowProps {
    /** The visible label. */
    label: string
    /** Whether the row is currently selected. */
    checked: boolean
    /** Called when the row is tapped. */
    onPress: () => void
    /** Optional dim level when the row is in an unselected secondary state (priority list bottom zone). */
    dim?: boolean
    /** Optional extra container style. */
    style?: ViewStyle
}

/**
 * A card-tile row with a 18x18 check box, used by multi-select and priority modals.
 * @param label Visible label.
 * @param checked Whether selected.
 * @param onPress Tap handler.
 * @param dim Render the label at 60% opacity when true.
 * @param style Optional outer style override.
 * @returns A Pressable card tile.
 */
const ModalCheckRowImpl = ({ label, checked, onPress, dim, style }: ModalCheckRowProps) => {
    const { colors } = useTheme()
    const styles = useMemo(
        () =>
            StyleSheet.create({
                row: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: SPACING.sm,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: SPACING.sm,
                    borderRadius: RADII.md,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    backgroundColor: colors.surfaceRaised,
                    overflow: "hidden",
                },
                rowActive: { borderColor: colors.brandBorder, backgroundColor: colors.brandSubtle },
                box: {
                    width: 18,
                    height: 18,
                    borderRadius: RADII.sm,
                    borderWidth: 1.5,
                    borderColor: colors.borderHair,
                    alignItems: "center",
                    justifyContent: "center",
                },
                boxActive: { borderColor: colors.brand, backgroundColor: colors.brand },
                label: { ...TYPE.body, color: colors.text, flex: 1 },
                labelDim: { opacity: 0.6 },
            }),
        [colors]
    )
    return (
        <Pressable
            onPress={onPress}
            style={[styles.row, checked && styles.rowActive, style]}
            android_ripple={{ color: colors.ripple, foreground: true }}
            accessibilityRole="checkbox"
            accessibilityState={{ checked }}
        >
            <View style={[styles.box, checked && styles.boxActive]}>{checked ? <Ionicons name="checkmark" size={14} color={colors.onBrand} /> : null}</View>
            <Text style={[styles.label, dim && !checked && styles.labelDim]}>{label}</Text>
        </Pressable>
    )
}

export const ModalCheckRow = React.memo(ModalCheckRowImpl)

/** Props for `ModalRadioRow`. */
export interface ModalRadioRowProps {
    /** Top-line uppercase mono tag. When provided, the row renders two lines. */
    tag?: string
    /** The visible label (sole content if no tag, otherwise the second line). */
    label: string
    /** Optional muted description rendered below the label. Use to explain an ambiguous option value. */
    description?: string
    /** Whether the row is currently selected. */
    selected: boolean
    /** Called when the row is tapped. */
    onPress: () => void
}

/**
 * A card-tile row with a circular radio indicator. Used by single-select auto-dismiss modals.
 * @param tag Optional uppercase mono tag rendered above the label.
 * @param label Primary label.
 * @param description Optional muted line rendered below the label.
 * @param selected Whether selected.
 * @param onPress Tap handler.
 * @returns A Pressable card tile.
 */
const ModalRadioRowImpl = ({ tag, label, description, selected, onPress }: ModalRadioRowProps) => {
    const { colors } = useTheme()
    const styles = useMemo(
        () =>
            StyleSheet.create({
                row: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: SPACING.sm,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: SPACING.sm,
                    borderRadius: RADII.md,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    backgroundColor: colors.surfaceRaised,
                    overflow: "hidden",
                },
                rowActive: { borderColor: colors.brandBorder, backgroundColor: colors.brandSubtle },
                radio: {
                    width: 16,
                    height: 16,
                    borderRadius: 999,
                    borderWidth: 1.5,
                    borderColor: colors.borderHair,
                    alignItems: "center",
                    justifyContent: "center",
                },
                radioActive: { borderColor: colors.brand },
                dot: { width: 8, height: 8, borderRadius: 999, backgroundColor: colors.brand },
                textBlock: { flex: 1, gap: 2 },
                tag: { ...TYPE.monoLabel, color: colors.textMuted, fontSize: 9, letterSpacing: 1.5 },
                tagActive: { color: colors.brand },
                label: { ...TYPE.body, color: colors.text },
                labelActive: { color: colors.brand, fontWeight: "600" as const },
                description: { ...TYPE.caption, color: colors.textMuted },
            }),
        [colors]
    )
    return (
        <Pressable
            onPress={onPress}
            style={[styles.row, selected && styles.rowActive]}
            android_ripple={{ color: colors.ripple, foreground: true }}
            accessibilityRole="radio"
            accessibilityState={{ selected }}
        >
            <View style={[styles.radio, selected && styles.radioActive]}>{selected ? <View style={styles.dot} /> : null}</View>
            <View style={styles.textBlock}>
                {tag ? <Text style={[styles.tag, selected && styles.tagActive]}>{tag}</Text> : null}
                <Text style={[styles.label, selected && styles.labelActive]}>{label}</Text>
                {description ? <Text style={styles.description}>{description}</Text> : null}
            </View>
        </Pressable>
    )
}

export const ModalRadioRow = React.memo(ModalRadioRowImpl)

/** Props for `ModalFooterChip`. */
export interface ModalFooterChipProps {
    /** Button label. */
    label: string
    /** Called when the chip is tapped. */
    onPress: () => void
    /** Visual tone. Default neutral. */
    tone?: "neutral" | "primary" | "danger"
    /** Disabled state. Default false. */
    disabled?: boolean
}

/**
 * A footer chip used inside SheetModal footers. Replaces CustomButton (which has a black-on-black bug in outline variant on dark theme).
 * @param label Button label.
 * @param onPress Tap handler.
 * @param tone Visual tone: neutral outline, primary brand-filled, or danger outline.
 * @param disabled When true, renders at 0.5 opacity and ignores presses.
 * @returns A Pressable chip that fills its flex parent.
 */
const ModalFooterChipImpl = ({ label, onPress, tone = "neutral", disabled = false }: ModalFooterChipProps) => {
    const { colors } = useTheme()
    const styles = useMemo(
        () =>
            StyleSheet.create({
                chip: {
                    flex: 1,
                    paddingVertical: SPACING.sm,
                    borderRadius: RADII.md,
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    alignItems: "center",
                    overflow: "hidden",
                },
                chipPrimary: { borderColor: colors.brand, backgroundColor: colors.brand },
                chipDanger: { borderColor: colors.destructive },
                chipDisabled: { opacity: 0.5 },
                text: { ...TYPE.body, color: colors.text, fontWeight: "600" as const },
                textPrimary: { color: colors.onBrand },
                textDanger: { color: colors.destructive },
            }),
        [colors]
    )
    return (
        <Pressable
            onPress={onPress}
            disabled={disabled}
            style={[styles.chip, tone === "primary" && styles.chipPrimary, tone === "danger" && styles.chipDanger, disabled && styles.chipDisabled]}
            android_ripple={{ color: colors.ripple, foreground: true }}
            accessibilityRole="button"
            accessibilityState={{ disabled }}
        >
            <Text style={[styles.text, tone === "primary" && styles.textPrimary, tone === "danger" && styles.textDanger]}>{label}</Text>
        </Pressable>
    )
}

export const ModalFooterChip = React.memo(ModalFooterChipImpl)
