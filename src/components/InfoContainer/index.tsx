import { useMemo, ReactNode } from "react"
import { View, Text, StyleSheet, StyleProp, ViewStyle } from "react-native"
import { useTheme } from "../../context/ThemeContext"

interface Props {
    /** Custom style for the container view. */
    style?: StyleProp<ViewStyle>
    /** The content to display inside the container. */
    children: ReactNode
}

/**
 * A reusable component for displaying informational content.
 * Renders text with default info styles if children is a string, otherwise renders children directly.
 * @param style Optional custom style for the container view.
 * @param children The content to display inside the container. Can be a string or a ReactNode.
 */
const InfoContainer = ({ style, children }: Props) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: {
                    backgroundColor: (colors as any).infoBg,
                    borderLeftWidth: 4,
                    borderLeftColor: (colors as any).infoBorder,
                    padding: 12,
                    marginTop: 12,
                    borderRadius: 8,
                },
                text: {
                    fontSize: 14,
                    color: (colors as any).infoText,
                    lineHeight: 20,
                },
            }),
        [colors]
    )

    return <View style={[styles.container, style]}>{typeof children === "string" ? <Text style={styles.text}>{children}</Text> : children}</View>
}

export default InfoContainer
