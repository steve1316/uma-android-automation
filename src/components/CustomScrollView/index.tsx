import { useEffect, useRef, useState, useMemo, Children, forwardRef } from "react"
import { Animated, ViewStyle, View, LayoutChangeEvent, NativeSyntheticEvent, NativeScrollEvent, PanResponder } from "react-native"
import { FlashList, FlashListProps } from "@shopify/flash-list"
import { getIndicatorPositionStyle } from "./helpers.ts"

type CustomIndicatorProps = {
    /** Animated value controlling indicator's translation. */
    indicatorPosition: Animated.Value
    /** Animated value controlling indicator's scale. */
    indicatorScale: Animated.Value
    /** Whether the scroll direction is horizontal. */
    horizontal: boolean
    /** Length (width/height) of the indicator thumb. */
    indicatorSize: number
    /** Maximum distance indicator can travel within the track. */
    scrollableTrackLength: number
    /** Whether scroll direction is inverted. */
    inverted: boolean
    /** Reference to FlashList for programmatic scrolling. */
    flashListRef: React.RefObject<any>
    /** Total content size of the list. */
    contentSize: number
    /** Viewport size of the list. */
    visibleSize: number
    /** Style positioning the indicator. */
    indicatorPositionStyle: ViewStyle
    /** Style defining the indicator's appearance. */
    indicatorStyle: ViewStyle
}

/**
 * CustomIndicator moves in sync with the scroll content.
 * - Dragging the indicator moves the content.
 * - Scrolling the content moves the indicator.
 * - Gesture handling ensures smooth, precise control.
 *
 * @param indicatorPosition Animated value controlling indicator's translation.
 * @param indicatorScale Animated value controlling indicator's scale.
 * @param horizontal Whether the scroll direction is horizontal.
 * @param indicatorSize Length (width/height) of the indicator thumb.
 * @param scrollableTrackLength Maximum distance indicator can travel within the track.
 * @param inverted Whether scroll direction is inverted.
 * @param flashListRef Reference to FlashList for programmatic scrolling.
 * @param contentSize Total content size of the list.
 * @param visibleSize Viewport size of the list.
 * @param indicatorPositionStyle Style positioning the indicator.
 * @param indicatorStyle Style defining the indicator's appearance.
 */
const CustomIndicator = ({
    indicatorPosition,
    indicatorScale,
    horizontal,
    indicatorSize,
    scrollableTrackLength,
    inverted,
    flashListRef,
    contentSize,
    visibleSize,
    indicatorPositionStyle,
    indicatorStyle,
}: CustomIndicatorProps) => {
    // Store offset at drag start to calculate relative motion.
    const dragStartOffset = useRef(0)

    const refs = useRef({
        horizontal,
        inverted,
        scrollableTrackLength,
        contentSize,
        visibleSize,
    }).current

    useEffect(() => {
        refs.horizontal = horizontal
        refs.inverted = inverted
        refs.scrollableTrackLength = scrollableTrackLength
        refs.contentSize = contentSize
        refs.visibleSize = visibleSize
    }, [horizontal, inverted, scrollableTrackLength, contentSize, visibleSize])

    // Create an Animated.ValueXY to track drag gestures.
    const pan = useRef(new Animated.ValueXY()).current

    // Listen to changes in pan.x and pan.y to update indicator and list scroll.
    pan.addListener(({ x, y }) => {
        // Calculate new indicator offset based on gesture direction.
        const gestureOffset = refs.horizontal ? (refs.inverted ? -x : x) : refs.inverted ? -y : y
        // Apply the drag start offset to maintain continuity.
        const newIndicatorOffset = Math.min(
            Math.max(gestureOffset + dragStartOffset.current, 0),
            refs.scrollableTrackLength // Ensure the indicator stays within the track.
        )

        // Update the Animated.Value controlling the indicator translation.
        indicatorPosition.setValue(newIndicatorOffset)

        // Convert indicator offset to proportional content offset.
        const maxContentOffset = refs.contentSize - refs.visibleSize
        const contentOffset = refs.scrollableTrackLength > 0 ? (newIndicatorOffset / refs.scrollableTrackLength) * maxContentOffset : 0

        // Scroll the list without animation as we are handling the animation ourselves.
        flashListRef.current?.scrollToOffset({
            offset: contentOffset,
            animated: false,
        })
    })

    const panResponder = useRef(
        PanResponder.create({
            // Always allow pan gestures.
            onMoveShouldSetPanResponder: () => true,

            // When user starts dragging the indicator.
            onPanResponderGrant: () => {
                // @ts-ignore: Accessing private Animated value is intentional for runtime state capture of the current indicator position.
                dragStartOffset.current = indicatorPosition._value || 0
                // Reset the offset layer so gesture deltas start from zero relative to current position.
                pan.setOffset({ x: 0, y: 0 })
                // Reset gesture movement values to zero.
                // Ensures that the first movement pixels are calculated correctly.
                pan.setValue({ x: 0, y: 0 })
            },

            // While dragging, map gesture movement to pan.x and pan.y.
            onPanResponderMove: Animated.event([null, { dx: pan.x, dy: pan.y }], {
                useNativeDriver: false,
            }),

            // When the drag ends.
            onPanResponderRelease: () => {
                // Merge the temporary pan offset into the base animated value.
                // This ensures the next drag starts from the current indicator position.
                // and prevents jumps or cumulative offset errors.
                pan.flattenOffset()
            },
        })
    ).current

    // Interpolates the indicator's translation along the scroll track based on the current animated value `indicatorPosition`.
    // - inputRange: 0 → maximum distance the indicator can travel (scrollableTrackLength).
    // - outputRange: maps to actual position in pixels on the screen.
    //   - If inverted is true, the mapping is reversed so the indicator moves in the opposite direction.
    // - extrapolate: "extend" allows values outside the input range to continue linearly.
    const translateAnim = indicatorPosition.interpolate({
        inputRange: [0, scrollableTrackLength],
        outputRange: inverted ? [scrollableTrackLength, 0] : [0, scrollableTrackLength],
        extrapolate: "extend",
    })

    // Interpolates the indicator's scale for subtle feedback (e.g., shrinking or stretching) as it moves along the track. Typically used for visual effect while scrolling.
    // - inputRange: 0 → 1 normalized scale factor.
    // - outputRange: 0 → 1 (full scale).
    // - extrapolate: "clamp" prevents the scale from exceeding these bounds.
    const scaleAnim = indicatorScale.interpolate({
        inputRange: [0, 1],
        outputRange: [0, 1],
        extrapolate: "clamp",
    })

    return (
        <Animated.View
            style={{
                ...indicatorStyle,
                ...indicatorPositionStyle,
                position: "absolute",
                height: horizontal ? indicatorStyle.width : indicatorSize,
                width: horizontal ? indicatorSize : indicatorStyle.width,
                transform: horizontal ? [{ translateX: translateAnim }, { scaleX: scaleAnim }] : [{ translateY: translateAnim }, { scaleY: scaleAnim }],
            }}
            {...panResponder.panHandlers}
        />
    )
}

type CustomScrollViewProps<T> = {
    /** Props passed directly to FlashList. */
    targetProps?: Partial<FlashListProps<T>>
    /** Indicator position: left/right/top/bottom or percentage. */
    position?: string | number
    /** Scroll orientation. */
    horizontal?: boolean
    /** Hide scrollbar if true. */
    hideScrollbar?: boolean
    /** Always show scrollbar if true. */
    persistentScrollbar?: boolean
    /** Styling for scrollbar indicator. */
    indicatorStyle?: ViewStyle
    /** Container view styling. */
    containerStyle?: ViewStyle
    /** Child elements (alternative to data prop). */
    children?: React.ReactNode | React.ReactNode[]
    /** Minimum pixel size of indicator. */
    minIndicatorSize?: number
    /** Enable custom indicator (WIP). When false, uses native Android scrollbar. */
    enableCustomIndicator?: boolean
}

/**
 * CustomScrollView wraps a `FlashList` and provides a custom draggable scrollbar indicator.
 * - The indicator size and position are calculated based on content size and viewport size.
 * - Scroll events update the indicator position and scale.
 * - Dragging the indicator scrolls the content in sync.
 * - Supports both horizontal and vertical scrolling.
 *
 * Interaction flows:
 * 1. Dragging the scrollbar thumb:
 *    - `onPanResponderGrant` captures the current thumb position.
 *    - `onPanResponderMove` updates the thumb position and scrolls the content proportionally.
 *    - `onPanResponderRelease` finalizes the position to prevent jumps.
 *
 * 2. Dragging the content itself:
 *    - `onScroll` updates the indicator position to match the content offset.
 *    - This keeps the indicator in sync without any user interaction with the thumb.
 *
 * @param targetProps Props passed directly to `FlashList`.
 * @param position Indicator position: left/right/top/bottom or percentage.
 * @param horizontal Scroll orientation.
 * @param hideScrollbar Hide scrollbar if true.
 * @param persistentScrollbar Always show scrollbar if true.
 * @param indicatorStyle Styling for scrollbar indicator.
 * @param containerStyle Container view styling.
 * @param children Child elements (alternative to data prop).
 * @param minIndicatorSize Minimum pixel size of indicator.
 * @param enableCustomIndicator Enable custom indicator (WIP). When false, uses native Android scrollbar.
 */
export const CustomScrollView = forwardRef<any, CustomScrollViewProps<any>>(
    (
        {
            targetProps,
            position = "right",
            horizontal = false,
            hideScrollbar = false,
            persistentScrollbar = false,
            indicatorStyle = {},
            containerStyle = { flex: 1 },
            children,
            minIndicatorSize,
            enableCustomIndicator = false,
        },
        ref
    ) => {
        // Total size of the content inside the scroll view (width for horizontal, height for vertical).
        const [contentSize, setContentSize] = useState(1)

        // Size of the visible viewport of the scroll view (width for horizontal, height for vertical).
        const [visibleSize, setVisibleSize] = useState(0)

        // Size orthogonal to the scroll direction (height for horizontal, width for vertical).
        const [orthogonalSize, setOrthogonalSize] = useState(0)

        // Reference to the FlashList for programmatic scrolling.
        const flashListRef = useRef<any>(null)

        // Convert children into an array if provided.
        const childArray = useMemo(() => {
            return children ? Children.toArray(children) : []
        }, [children])

        // Compute the indicator size based on the content-to-viewport ratio.
        const calculatedSize = contentSize > visibleSize ? (visibleSize * visibleSize) / contentSize : visibleSize

        // Ensure the indicator is at least minIndicatorSize, if provided.
        const indicatorSize = minIndicatorSize !== undefined ? Math.max(calculatedSize, minIndicatorSize) : calculatedSize

        // Maximum distance the indicator can travel along the scroll track.
        // This ensures the indicator stays fully inside the viewport.
        const scrollableTrackLength = visibleSize > indicatorSize ? visibleSize - indicatorSize : 1

        // Animated value controlling the translation of the indicator along the track.
        const indicatorPosition = useRef(new Animated.Value(0)).current

        // Animated value controlling the scale of the indicator for visual feedback.
        const indicatorScale = useRef(new Animated.Value(1)).current

        /**
         * Callback fired when the FlashList content size changes.
         * @param width The width of the content.
         * @param height The height of the content.
         */
        const handleContentSizeChange = (width: number, height: number) => {
            setContentSize(horizontal ? width : height)
        }

        /**
         * Callback fired when the FlashList layout changes.
         * Updates visible and orthogonal size for scroll calculations.
         * @param event The layout change event.
         */
        const handleLayoutChange = (event: LayoutChangeEvent) => {
            const { width, height } = event.nativeEvent.layout
            setVisibleSize(horizontal ? width : height)
            setOrthogonalSize(horizontal ? height : width)
        }

        /**
         * Callback fired on scroll events to update indicator position and scale.
         * @param event The scroll event.
         */
        const handleScroll = (event: NativeSyntheticEvent<NativeScrollEvent>) => {
            // Current scroll offset along the scroll direction.
            const contentOffset = horizontal ? event.nativeEvent.contentOffset.x : event.nativeEvent.contentOffset.y

            // Maximum scrollable distance of the content.
            const maxContentOffset = contentSize - visibleSize

            // Compute proportional indicator offset along the track.
            const indicatorOffset = maxContentOffset > 0 ? (contentOffset * scrollableTrackLength) / maxContentOffset : 0

            // Update the indicator translation.
            indicatorPosition.setValue(indicatorOffset)

            // Update the indicator scale for visual feedback while scrolling.
            indicatorScale.setValue(indicatorOffset >= 0 ? (indicatorSize + 2 * scrollableTrackLength - 2 * indicatorOffset) / indicatorSize : (indicatorSize + 2 * indicatorOffset) / indicatorSize)
        }

        // Forward the ref to the FlashList so parent components can control scrolling.
        useEffect(() => {
            if (ref) {
                if (typeof ref === "function") {
                    ref(flashListRef.current)
                } else {
                    ref.current = flashListRef.current
                }
            }
        }, [ref, flashListRef])

        return (
            <View style={containerStyle}>
                <FlashList
                    ref={flashListRef}
                    data={children ? (childArray as any) : (targetProps?.data as any)}
                    renderItem={children ? ({ item }: any) => <View style={{ width: "100%" }}>{item}</View> : (targetProps?.renderItem as any)}
                    horizontal={horizontal}
                    showsVerticalScrollIndicator={!hideScrollbar && !enableCustomIndicator && !horizontal}
                    showsHorizontalScrollIndicator={!hideScrollbar && !enableCustomIndicator && horizontal}
                    onContentSizeChange={enableCustomIndicator ? handleContentSizeChange : targetProps?.onContentSizeChange}
                    onLayout={(e) => {
                        if (enableCustomIndicator) {
                            handleLayoutChange(e)
                        }
                        // Preserve user-provided onLayout if it exists.
                        if (typeof targetProps?.onLayout === "function") {
                            targetProps.onLayout(e)
                        }
                    }}
                    // Update onScroll every 16ms (~60fps) for smooth indicator movement.
                    scrollEventThrottle={enableCustomIndicator ? 16 : targetProps?.scrollEventThrottle}
                    onScroll={(e) => {
                        if (enableCustomIndicator) {
                            handleScroll(e)
                        }
                        // Preserve user-provided onScroll if it exists.
                        if (typeof targetProps?.onScroll === "function") {
                            targetProps.onScroll(e)
                        }
                    }}
                    {...(targetProps as any)}
                />

                {enableCustomIndicator && (persistentScrollbar || indicatorSize < visibleSize) && (
                    <CustomIndicator
                        indicatorPosition={indicatorPosition}
                        indicatorScale={indicatorScale}
                        horizontal={horizontal}
                        indicatorSize={indicatorSize}
                        scrollableTrackLength={scrollableTrackLength}
                        inverted={false}
                        flashListRef={flashListRef}
                        contentSize={contentSize}
                        visibleSize={visibleSize}
                        indicatorPositionStyle={getIndicatorPositionStyle(horizontal, position, orthogonalSize, indicatorStyle.width as number)}
                        indicatorStyle={indicatorStyle}
                    />
                )}
            </View>
        )
    }
)

export default CustomScrollView
