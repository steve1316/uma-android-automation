import { memo, useEffect, useRef } from "react"
import { Animated, Pressable, View, type StyleProp, type ViewStyle } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { GlassSurface } from "./glass-surface"

/** Props for `GlassFab`. */
export interface GlassFabProps {
    /** Triggered on press. */
    onPress: () => void
    /** Icon node rendered inside the FAB. Use a brand-tinted lucide / Ionicons icon at ~20px. */
    icon: React.ReactNode
    /** Disable presses. */
    disabled?: boolean
    /** Outer wrapper style (positioning, margin). */
    style?: StyleProp<ViewStyle>
    /** Accessibility label for screen readers. */
    accessibilityLabel?: string
}

/**
 * 40x40 rounded glass button used as a floating action button, the Material small-FAB size so it stays out of the way on narrow phones. Springs into view on mount.
 * Respects theme glass tokens for backdrop and border. Replaces RecalcFab and MessageLog floating scroll buttons.
 *
 * @param props See `GlassFabProps`.
 * @returns Animated glass FAB.
 */
export const GlassFab = memo(({ onPress, icon, disabled, style, accessibilityLabel }: GlassFabProps) => {
    const { colors } = useTheme()
    const scale = useRef(new Animated.Value(0)).current
    useEffect(() => {
        Animated.spring(scale, { toValue: 1, friction: 5, tension: 90, useNativeDriver: true }).start()
    }, [scale])
    return (
        <Animated.View style={[{ transform: [{ scale }] }, style]}>
            <GlassSurface intensity={50} style={{ borderRadius: 20, overflow: "hidden" }}>
                <Pressable
                    onPress={onPress}
                    disabled={disabled}
                    android_ripple={{ color: colors.ripple, foreground: true }}
                    accessibilityRole="button"
                    accessibilityLabel={accessibilityLabel}
                    hitSlop={4}
                    style={{ width: 40, height: 40, alignItems: "center", justifyContent: "center", opacity: disabled ? 0.4 : 1 }}
                >
                    <View>{icon}</View>
                </Pressable>
            </GlassSurface>
        </Animated.View>
    )
})
