import React, { useMemo, useState, useRef, useEffect } from "react"
import { View, Text, StyleSheet, Animated, LayoutChangeEvent, ViewStyle } from "react-native"
import Slider from "@react-native-community/slider"
import { useTheme } from "../../context/ThemeContext"
import { Input } from "../ui/input"
import SearchableItem from "../SearchableItem"

interface CustomSliderProps {
    value: number
    onValueChange: (value: number) => void
    onSlidingComplete?: (value: number) => void
    min: number
    max: number
    step: number
    label?: string
    labelUnit?: string
    placeholder?: number
    showValue?: boolean
    showLabels?: boolean
    description?: string
    style?: ViewStyle
    // Search props
    searchId?: string
    searchTitle?: string
    searchDescription?: string
    searchCondition?: boolean
    parentId?: string
    children?: React.ReactNode
}

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
        [colors],
    )

    const calculateTooltipPosition = (currentValue: number) => {
        if (sliderWidth === 0) return 0
        const percentage = (currentValue - min) / (max - min)
        return percentage * sliderWidth
    }

    // Calculate the number of decimal places based on the step value.
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
