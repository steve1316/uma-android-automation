import { Easing } from "react-native"

// //////////////////////////////////////////////////////////////////////////////////////////////////
// //////////////////////////////////////////////////////////////////////////////////////////////////
// Motion tokens
//
// Durations and easing curves for `Animated.timing` and `LayoutAnimation`.
// Standard polish: section collapse, modal enter/exit, switch thumb travel.

export const MOTION = {
    duration: {
        fast: 120,
        base: 200,
        slow: 320,
    },
    easing: {
        easeOut: Easing.bezier(0.16, 1, 0.3, 1),
        easeInOut: Easing.bezier(0.65, 0, 0.35, 1),
    },
} as const
