import React from "react"
import { View, Text, Pressable } from "react-native"
import Ionicons from "@react-native-vector-icons/ionicons"
import { useTheme } from "../../context/ThemeContext"
import { useModalShellStyles } from "./modal-shell-styles"

/** Props for `ModalHeader`. */
export interface ModalHeaderProps {
    /** The uppercase mono title shown on the left. */
    title: string
    /** Called when the close chip is tapped. */
    onClose: () => void
}

/**
 * The standard `SheetModal` header: a mono title on the left and a close chip on the right.
 * @param title The uppercase mono title.
 * @param onClose Tap handler for the close chip.
 * @returns A header row for a `SheetModal`.
 */
const ModalHeaderImpl = ({ title, onClose }: ModalHeaderProps) => {
    const { colors } = useTheme()
    const modalShellStyles = useModalShellStyles()
    return (
        <View style={modalShellStyles.modalHeaderRow}>
            <Text style={modalShellStyles.modalTitleMono}>{title}</Text>
            <Pressable style={modalShellStyles.modalCloseChip} onPress={onClose} android_ripple={{ color: colors.ripple, foreground: true }} accessibilityLabel="Close">
                <Ionicons name="close" size={18} color={colors.text} />
            </Pressable>
        </View>
    )
}

export const ModalHeader = React.memo(ModalHeaderImpl)
export default ModalHeader
