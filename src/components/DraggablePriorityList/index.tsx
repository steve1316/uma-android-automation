import React, { useEffect, useState, useMemo } from "react"
import { View, Text, Pressable, StyleSheet, ViewStyle } from "react-native"
import DragList, { DragListRenderItemInfo } from "react-native-draglist"
import Ionicons from "@react-native-vector-icons/ionicons"
import { useTheme } from "../../context/ThemeContext"
import { ModalCheckRow } from "../ui/modal-list"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"

/** A single priority list item. */
export interface PriorityItem {
    /** Stable identifier used by the drag list. */
    id: string
    /** Visible label. */
    label: string
    /** Optional muted second line, rendered under the label on both the ranked and unranked rows. */
    description?: string
}

/** Props for `DraggablePriorityList`. */
interface DraggablePriorityListProps {
    /** All available items. */
    items: PriorityItem[]
    /** Subset of `items.id` representing selected items in priority order (index 0 = highest). */
    selectedItems: string[]
    /** Called when the user toggles a row's selection. */
    onSelectionChange: (next: string[]) => void
    /** Called when the user reorders selected items via drag. */
    onOrderChange: (next: string[]) => void
    /** Optional outer style. */
    style?: ViewStyle
}

/**
 * A drag-to-reorder list paired with checkbox toggles. Selected items render on top with a numeric badge, a remove button, and a grip handle, and the row
 * body is the drag target. Unselected items render below a dashed separator with a plain checkbox and are appended to the end when selected. An item
 * carrying a description shows it as a muted second line on both lists, so a choice can be made without leaving the sheet.
 * Consumed inside `SheetModal` - the parent owns scroll so this component does not wrap its rows in a ScrollView.
 * @param items All items.
 * @param selectedItems Selected items in priority order.
 * @param onSelectionChange Selection toggle callback.
 * @param onOrderChange Reorder callback.
 * @param style Optional outer style override.
 * @returns A view containing the priority list, separator, unselected rows, and empty-state caption.
 */
const DraggablePriorityList = ({ items, selectedItems, onSelectionChange, onOrderChange, style }: DraggablePriorityListProps) => {
    const { colors } = useTheme()
    const [orderedSelected, setOrderedSelected] = useState<string[]>(selectedItems)

    useEffect(() => {
        setOrderedSelected(selectedItems)
    }, [selectedItems])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                tip: { ...TYPE.monoLabel, color: colors.textMuted, fontSize: 10, letterSpacing: 1.2, paddingHorizontal: 4, paddingBottom: SPACING.sm },
                empty: { ...TYPE.monoLabel, color: colors.textMuted, fontSize: 10, letterSpacing: 1.2, textAlign: "center", paddingTop: SPACING.md },
                selectedRow: {
                    flexDirection: "row",
                    alignItems: "center",
                    gap: SPACING.sm,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: SPACING.sm,
                    borderRadius: RADII.md,
                    borderWidth: 1,
                    borderColor: colors.brandBorder,
                    backgroundColor: colors.brandSubtle,
                    overflow: "hidden",
                    marginBottom: SPACING.xs + 2,
                },
                badge: {
                    width: 22,
                    height: 22,
                    borderRadius: 999,
                    backgroundColor: colors.brand,
                    alignItems: "center",
                    justifyContent: "center",
                },
                badgeText: { ...TYPE.monoValue, color: colors.onBrand, fontSize: 11, fontWeight: "700" as const },
                selectedTextBlock: { flex: 1, gap: 2 },
                selectedLabel: { ...TYPE.body, color: colors.text },
                selectedDescription: { ...TYPE.caption, color: colors.textMuted },
                grip: { opacity: 0.7 },
                remove: { opacity: 0.7, paddingHorizontal: 2 },
                separator: { borderTopWidth: 1, borderStyle: "dashed", borderColor: colors.borderHair, marginVertical: SPACING.sm },
                unselectedList: { gap: SPACING.xs + 2 },
            }),
        [colors]
    )

    const renderSelectedItem = (info: DragListRenderItemInfo<PriorityItem>) => {
        const { item, onDragStart, onDragEnd } = info
        const priorityNumber = orderedSelected.indexOf(item.id) + 1
        // The row body starts a drag rather than toggling selection. Removal lives on its own button, since a drag that the pan responder read as a
        // tap used to silently deselect the item, and re-selecting it appended it to the bottom of the list.
        return (
            <Pressable
                style={styles.selectedRow}
                onPressIn={onDragStart}
                onPressOut={onDragEnd}
                android_ripple={{ color: colors.ripple, foreground: true }}
                accessibilityLabel={`${item.label} priority ${priorityNumber}, drag to reorder`}
            >
                <View style={styles.badge}>
                    <Text style={styles.badgeText}>{priorityNumber}</Text>
                </View>
                <View style={styles.selectedTextBlock}>
                    <Text style={styles.selectedLabel}>{item.label}</Text>
                    {item.description ? <Text style={styles.selectedDescription}>{item.description}</Text> : null}
                </View>
                <Pressable
                    onPress={() => onSelectionChange(orderedSelected.filter((id) => id !== item.id))}
                    hitSlop={SPACING.sm}
                    style={styles.remove}
                    accessibilityRole="button"
                    accessibilityLabel={`Remove ${item.label} from the priority list`}
                >
                    <Ionicons name="close" size={18} color={colors.textMuted} />
                </Pressable>
                <View style={styles.grip}>
                    <Ionicons name="reorder-three" size={20} color={colors.brand} />
                </View>
            </Pressable>
        )
    }

    const handleReordered = (fromIndex: number, toIndex: number) => {
        const copy = [...orderedSelected]
        const [removed] = copy.splice(fromIndex, 1)
        copy.splice(toIndex, 0, removed)
        setOrderedSelected(copy)
        onOrderChange(copy)
    }

    // Both lists read `orderedSelected` so a reorder that has not yet propagated back through the parent cannot make an item appear in both or neither.
    const selectedData = orderedSelected.map((id) => items.find((it) => it.id === id)).filter((x): x is PriorityItem => !!x)
    const unselected = items.filter((it) => !orderedSelected.includes(it.id))

    return (
        <View style={style}>
            <Text style={styles.tip}>DRAG TO REORDER - TOP = HIGHEST</Text>

            {selectedData.length > 0 ? <DragList data={selectedData} keyExtractor={(item) => item.id} onReordered={handleReordered} renderItem={renderSelectedItem} scrollEnabled={false} /> : null}

            {selectedData.length > 0 && unselected.length > 0 ? <View style={styles.separator} /> : null}

            {unselected.length > 0 ? (
                <View style={styles.unselectedList}>
                    {unselected.map((item) => (
                        <ModalCheckRow key={item.id} label={item.label} description={item.description} checked={false} dim onPress={() => onSelectionChange([...orderedSelected, item.id])} />
                    ))}
                </View>
            ) : null}

            {selectedData.length === 0 ? <Text style={styles.empty}>NO ITEMS SELECTED - SELECT TO SET ORDER</Text> : null}
        </View>
    )
}

export default React.memo(DraggablePriorityList)
