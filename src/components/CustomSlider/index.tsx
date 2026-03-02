import React, { useMemo, useState, useRef, useEffect } from "react"
import { View, Text, StyleSheet, Animated, LayoutChangeEvent, ViewStyle } from "react-native"
import Slider from "@react-native-community/slider"
import { useTheme } from "../../context/ThemeContext"
import { Input } from "../ui/input"
import SearchableItem from "../SearchableItem"

interface CustomSliderProps {
    /** The current value of the slider. */
    value: number
    /** Callback fired when the slider value changes. */
    onValueChange: (value: number) => void
    /** Optional callback fired when the user finishes dragging the slider. */
    onSlidingComplete?: (value: number) => void
    /** The minimum allowed value. */
    min: number
    /** The maximum allowed value. */
    max: number
    /** The step increment between values. */
    step: number
    /** Optional label text displayed above the slider. */
    label?: string
    /** Optional unit suffix displayed next to the value (e.g. "%"). */
    labelUnit?: string
    /** Optional placeholder value shown in the input field when empty. */
    placeholder?: number
    /** Whether to show the current value input below the slider. */
    showValue?: boolean
    /** Whether to show min/max labels below the slider. */
    showLabels?: boolean
    /** Optional description text displayed below the label. */
    description?: string
    /** Optional custom style for the container. */
    style?: ViewStyle
    /** Optional search ID for registering this item in the search index. */
    searchId?: string
    /** Optional override for the searchable title (defaults to label). */
    searchTitle?: string
    /** Optional override for the searchable description. */
    searchDescription?: string
    /** Optional condition controlling whether this item is registered in the search index. */
    searchCondition?: boolean
    /** Optional ID of the parent searchable item for hierarchical search. */
    parentId?: string
    /** Optional children rendered below the slider. */
    children?: React.ReactNode
}

/**
 * A themed slider component with a custom draggable tooltip, editable input field, and search integration.
 * Supports animated thumb scaling and tooltip display during drag interactions.
 * Wraps content in a `SearchableItem` when a `searchId` is provided.
 * @param value The current value of the slider.
 * @param onValueChange Callback fired when the slider value changes.
 * @param min The minimum allowed value.
 * @param max The maximum allowed value.
 * @param step The step increment between values.
 * @param label Optional label text displayed above the slider.
 * @param labelUnit Optional unit suffix displayed next to the value (e.g. "%" ).
 * @param placeholder Optional placeholder value shown in the input field when empty.
 * @param showValue Whether to show the current value input below the slider.
 * @param showLabels Whether to show min/max labels below the slider.
 * @param description Optional description text displayed below the label.
 * @param style Optional custom style for the container.
 * @param searchId Optional search ID for registering this item in the search index.
 * @param searchTitle Optional override for the searchable title (defaults to label).
 * @param searchDescription Optional override for the searchable description.
 * @param searchCondition Optional condition controlling whether this item is registered in the search index.
 * @param parentId Optional ID of the parent searchable item for hierarchical search.
 * @param children Optional children rendered below the slider.
 */
const CustomSlider: React.FC<CustomSliderProps> = ({
    value,
    onValueChange,
    onSlidingComplete,
    min,
    max,
    step,
    label,
    labelUnit = "",
    placeholder = "",
    showValue = true,
    showLabels = true,
    description,
    style,
    searchId,
    searchTitle,
    searchDescription,
    searchCondition,
    parentId,
    children,
}) => {
    const { colors } = useTheme()
    const [isDragging, setIsDragging] = useState(false)
    const [isTyping, setIsTyping] = useState(false)
    const [sliderWidth, setSliderWidth] = useState(0)
    const [tooltipPosition, setTooltipPosition] = useState(0)
    const [localValue, setLocalValue] = useState(value)
    const [inputValue, setInputValue] = useState(value.toString())
    const thumbScale = useRef(new Animated.Value(1)).current
    const tooltipOpacity = useRef(new Animated.Value(0)).current

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: {
                    marginVertical: 16,
                },
                label: {
                    fontSize: 16,
                    fontWeight: "600",
                    color: colors.foreground,
                    marginBottom: 12,
                },
                sliderContainer: {
                    marginHorizontal: 20,
                    position: "relative",
                },
                valueContainer: {
                    alignItems: "center",
                    marginTop: 8,
                    flexDirection: "row",
                    justifyContent: "space-between",
                    marginHorizontal: 20,
                },
                labelText: {
                    fontSize: 12,
                    color: colors.primary,
                },
                descriptionText: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginBottom: 8,
                    marginTop: -4,
                },
                customThumb: {
                    position: "absolute",
                    width: 20,
                    height: 20,
                    borderRadius: 10,
                    backgroundColor: colors.primary,
                    zIndex: 1,
                    top: 10, // Position it in the middle of the slider height.
                },
                tooltip: {
                    position: "absolute",
                    backgroundColor: colors.primary,
                    paddingHorizontal: 12,
                    paddingVertical: 8,
                    borderRadius: 6,
                    top: -45,
                    transform: [{ translateX: -20 }],
                    zIndex: 50,
                    shadowColor: "#000",
                    shadowOffset: {
                        width: 0,
                        height: 2,
                    },
                    shadowOpacity: 0.25,
                    shadowRadius: 3.84,
                    elevation: 5,
                },
                tooltipText: {
                    color: colors.background,
                    fontSize: 12,
                    fontWeight: "600",
                    textAlign: "center",
                },
                inputContainer: {
                    flexDirection: "row",
                    alignItems: "center",
                    justifyContent: "center",
                },
                input: {
                    width: 80,
                    textAlign: "center",
                    fontSize: 18,
                    fontWeight: "600",
                    color: colors.foreground,
                    backgroundColor: colors.input,
                    borderColor: colors.foreground,
                },
                unitText: {
                    fontSize: 18,
                    fontWeight: "600",
                    color: colors.foreground,
                    marginLeft: 4,
                },
            }),
        [colors]
    )

    /**
     * Calculates the tooltip position based on the current value.
     * @param currentValue The current value of the slider.
     * @returns The tooltip position.
     */
    const calculateTooltipPosition = (currentValue: number) => {
        if (sliderWidth === 0) return 0
        const percentage = (currentValue - min) / (max - min)
        return percentage * sliderWidth
    }

    /**
     * Calculates the number of decimal places based on the step value.
     * @param stepValue The step value.
     * @returns The number of decimal places.
     */
    const getDecimalPlaces = (stepValue: number) => {
        if (stepValue >= 1) return 0
        const stepStr = stepValue.toString()
        const decimalIndex = stepStr.indexOf(".")
        if (decimalIndex === -1) return 0
        return stepStr.length - decimalIndex - 1
    }

    // Initialize tooltip position when component mounts or value changes.
    useEffect(() => {
        if (sliderWidth > 0) {
            const position = calculateTooltipPosition(value)
            setTooltipPosition(position)
        }
    }, [sliderWidth, value, min, max])

    // Update local value when external value changes (but not during dragging or typing).
    useEffect(() => {
        if (!isDragging && !isTyping) {
            setLocalValue(value)
            setInputValue(value.toFixed(getDecimalPlaces(step)))
        }
    }, [value, isDragging, isTyping, step])

    /**
     * Callback fired when the user starts dragging the slider.
     * @param sliderValue The current value of the slider.
     */
    const handleSlidingStart = (sliderValue: number) => {
        setIsDragging(true)
        setLocalValue(sliderValue)
        Animated.parallel([
            Animated.timing(thumbScale, {
                toValue: 1.3,
                duration: 200,
                useNativeDriver: true,
            }),
            Animated.timing(tooltipOpacity, {
                toValue: 1,
                duration: 200,
                useNativeDriver: true,
            }),
        ]).start()

        const position = calculateTooltipPosition(sliderValue)
        setTooltipPosition(position)
    }

    /**
     * Callback fired when the user finishes dragging the slider.
     */
    const handleSlidingComplete = () => {
        setIsDragging(false)
        onValueChange(localValue)
        Animated.parallel([
            Animated.timing(thumbScale, {
                toValue: 1,
                duration: 200,
                useNativeDriver: true,
            }),
            Animated.timing(tooltipOpacity, {
                toValue: 0,
                duration: 200,
                useNativeDriver: true,
            }),
        ]).start()

        // Call the onSlidingComplete prop if provided.
        if (onSlidingComplete) {
            onSlidingComplete(localValue)
        }
    }

    /**
     * Callback fired when the slider value changes.
     * @param sliderValue The current value of the slider.
     */
    const handleValueChange = (sliderValue: number) => {
        // Round to nearest step to avoid floating point precision issues.
        const roundedValue = Math.round(sliderValue / step) * step
        setLocalValue(roundedValue)
        setInputValue(roundedValue.toFixed(getDecimalPlaces(step)))
        if (isDragging) {
            const position = calculateTooltipPosition(roundedValue)
            setTooltipPosition(position)
        }
    }

    /**
     * Callback fired when the input value changes.
     * @param text The current input value.
     */
    const handleInputChange = (text: string) => {
        setIsTyping(true)
        setInputValue(text)
        const numValue = parseFloat(text)

        // Only update if we have a valid number that's within range.
        if (!isNaN(numValue) && numValue >= min && numValue <= max) {
            // Round to nearest step.
            const roundedValue = Math.round(numValue / step) * step
            setLocalValue(roundedValue)
            onValueChange(roundedValue)
        }
    }

    /**
     * Callback fired when the input value is submitted.
     */
    const handleInputSubmit = () => {
        setIsTyping(false)
        const numValue = parseFloat(inputValue)
        if (!isNaN(numValue)) {
            // Clamp value to min/max and round to nearest step.
            const clampedValue = Math.max(min, Math.min(max, numValue))
            const roundedValue = Math.round(clampedValue / step) * step
            setLocalValue(roundedValue)
            onValueChange(roundedValue)
            setInputValue(roundedValue.toFixed(getDecimalPlaces(step)))
        } else {
            // Reset to current value if invalid.
            setInputValue(localValue.toFixed(getDecimalPlaces(step)))
        }
    }

    /**
     * Callback fired when the slider layout changes.
     * @param event The layout event.
     */
    const handleLayout = (event: LayoutChangeEvent) => {
        const { width } = event.nativeEvent.layout
        setSliderWidth(width)
    }

    const content = (
        <View style={[styles.container, style]}>
            {label && <Text style={styles.label}>{label}</Text>}
            {description && <Text style={styles.descriptionText}>{description}</Text>}

            <View style={styles.sliderContainer} onLayout={handleLayout}>
                {/* Custom tooltip */}
                <Animated.View
                    style={[
                        styles.tooltip,
                        {
                            opacity: tooltipOpacity,
                            left: tooltipPosition,
                        },
                    ]}
                    pointerEvents="none"
                >
                    <Text style={styles.tooltipText}>
                        {localValue.toFixed(getDecimalPlaces(step))}
                        {labelUnit}
                    </Text>
                </Animated.View>

                {/* Custom thumb overlay for scaling effect */}
                <Animated.View
                    style={[
                        styles.customThumb,
                        {
                            transform: [{ scale: thumbScale }],
                            left: tooltipPosition - 10, // Center the thumb overlay
                        },
                    ]}
                    pointerEvents="none"
                />

                {/* Slider with hidden default thumb */}
                <Slider
                    style={{ width: "100%", height: 40 }}
                    value={localValue}
                    onValueChange={handleValueChange}
                    onSlidingStart={handleSlidingStart}
                    onSlidingComplete={handleSlidingComplete}
                    minimumValue={min}
                    maximumValue={max}
                    step={step}
                    minimumTrackTintColor={colors.primary}
                    maximumTrackTintColor={colors.border}
                    thumbTintColor="transparent" // Hide the default thumb
                />
            </View>

            {showValue && (
                <View style={styles.valueContainer}>
                    <Text style={styles.labelText}>{showLabels ? min + labelUnit : ""}</Text>
                    <View style={styles.inputContainer}>
                        <Input
                            value={inputValue}
                            onChangeText={handleInputChange}
                            onEndEditing={handleInputSubmit}
                            onBlur={handleInputSubmit}
                            keyboardType="numeric"
                            style={styles.input}
                            placeholder={placeholder.toString()}
                        />
                        {labelUnit && <Text style={styles.unitText}>{labelUnit}</Text>}
                    </View>
                    <Text style={styles.labelText}>{showLabels ? max + labelUnit : ""}</Text>
                </View>
            )}
            {children}
        </View>
    )

    if (searchId) {
        return (
            <SearchableItem id={searchId} title={searchTitle || label || ""} description={searchDescription || description || undefined} parentId={parentId} condition={searchCondition}>
                {content}
            </SearchableItem>
        )
    }

    return content
}

export default React.memo(CustomSlider)
