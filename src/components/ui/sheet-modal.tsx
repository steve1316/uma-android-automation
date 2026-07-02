import React, { useMemo } from "react"
import { Dimensions, Modal, Pressable, ScrollView, StyleSheet, Text, View } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import { TYPE } from "../../lib/type"

/** Props for `SheetModal`. */
export interface SheetModalProps {
    /** Whether the sheet is visible. */
    visible: boolean
    /** Called on backdrop tap or Android back. */
    onRequestClose: () => void
    /** Header slot. Usually a title row + close chip. Rendered above a hairline divider. */
    header: React.ReactNode
    /** Optional muted intro line rendered under the header, above the body. Use to frame what the modal's choices do. */
    description?: string
    /** Optional sticky slot rendered between the header and the scrollable body. Use for search inputs, filter chips, or any controls that should remain visible while the body scrolls. */
    subHeader?: React.ReactNode
    /** Body slot. Rendered inside a flex-1 ScrollView so nested scroll regions resolve. */
    children: React.ReactNode
    /** Footer slot. Rendered below a hairline divider. */
    footer: React.ReactNode
    /** Override the default 0.80 screen-height fraction. Clamped between 0.4 and 0.95. */
    heightFraction?: number
    /** Override the default 560px max width. Use when the modal hosts wider content (e.g. multi-column grids). */
    maxWidth?: number
    /** Set false to disable tap-outside-to-dismiss. Default true. */
    dismissOnBackdropPress?: boolean
    /** When false, the body is wrapped in a `flex: 1` `View` instead of a `ScrollView`. Use for bodies that manage their own scroll (e.g. `FlashList`). Default true. */
    scrollableBody?: boolean
}

/**
 * A modal shell with locked header and footer slots and a scrollable body. Built on `Modal` with a definite-height card so nested
 * `ScrollView` regions have a bounded parent and flex children grow as expected. Use this for any form-style or list-style modal
 * where the body content may exceed available space. For auto-sized alerts and short confirmations, prefer `GlassModal`.
 *
 * @param visible Whether the sheet is visible.
 * @param onRequestClose Called on backdrop tap or Android back.
 * @param header Header slot rendered above a hairline divider.
 * @param description Optional muted intro line rendered under the header.
 * @param subHeader Optional sticky slot rendered between the header and the scrollable body.
 * @param children Body slot rendered inside a flex-1 ScrollView.
 * @param footer Footer slot rendered below a hairline divider.
 * @param heightFraction Override the default 0.80 screen-height fraction. Clamped between 0.4 and 0.95.
 * @param dismissOnBackdropPress Set false to disable tap-outside-to-dismiss. Default true.
 * @returns A full-screen `Modal` with a centered card whose layout is header / optional sub-header / scrollable body / footer.
 */
const SheetModalImpl = ({
    visible,
    onRequestClose,
    header,
    description,
    subHeader,
    children,
    footer,
    heightFraction = 0.8,
    maxWidth = 560,
    dismissOnBackdropPress = true,
    scrollableBody = true,
}: SheetModalProps) => {
    const { colors } = useTheme()
    const clamped = Math.max(0.4, Math.min(0.95, heightFraction))
    const cardHeight = Math.round(Dimensions.get("window").height * clamped)
    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: { flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: colors.glassBackdrop },
                backdrop: StyleSheet.absoluteFill as object,
                card: {
                    width: "92%",
                    maxWidth,
                    height: cardHeight,
                    borderRadius: RADII.xl,
                    borderWidth: 1,
                    borderColor: colors.brandBorder,
                    backgroundColor: colors.surface,
                    overflow: "hidden",
                },
                header: { paddingHorizontal: SPACING.md, paddingTop: SPACING.md },
                description: { ...TYPE.caption, color: colors.textMuted, paddingHorizontal: SPACING.md, paddingTop: SPACING.xs },
                subHeader: { paddingHorizontal: SPACING.md },
                body: { flex: 1 },
                bodyContent: { paddingHorizontal: SPACING.md, paddingVertical: SPACING.md },
                footer: { paddingHorizontal: SPACING.md, paddingVertical: SPACING.md },
            }),
        [colors, cardHeight, maxWidth]
    )
    return (
        <Modal transparent visible={visible} animationType="fade" onRequestClose={onRequestClose} statusBarTranslucent>
            <View style={styles.root}>
                <Pressable style={styles.backdrop} onPress={dismissOnBackdropPress ? onRequestClose : undefined} />
                <View style={styles.card}>
                    <View style={styles.header}>{header}</View>
                    {description != null ? <Text style={styles.description}>{description}</Text> : null}
                    {subHeader != null ? <View style={styles.subHeader}>{subHeader}</View> : null}
                    {scrollableBody ? (
                        <ScrollView style={styles.body} contentContainerStyle={styles.bodyContent} keyboardShouldPersistTaps="handled" nestedScrollEnabled>
                            {children}
                        </ScrollView>
                    ) : (
                        <View style={[styles.body, styles.bodyContent]}>{children}</View>
                    )}
                    {footer != null ? <View style={styles.footer}>{footer}</View> : null}
                </View>
            </View>
        </Modal>
    )
}

export const SheetModal = React.memo(SheetModalImpl)
export default SheetModal
