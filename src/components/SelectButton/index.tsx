import React, { useRef, useState, useMemo } from "react"
import { View, LayoutChangeEvent, StyleSheet, StyleProp, ViewStyle } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { Option, Select, SelectContent, SelectGroup, SelectItem, SelectLabel, NativeSelectScrollView } from "../ui/select"
import { Separator } from "../ui/separator"
import CustomButton from "../CustomButton"
import Ionicons from "@react-native-vector-icons/ionicons"
import * as SelectPrimitive from "@rn-primitives/select"

/**
 * Represents an option in the select dropdown.
 */
interface SelectOption {
    /** The value of the option. */
    value: string
    /** The display label of the option. */
    label: string
    /** Whether the option is disabled. */
    disabled?: boolean
}

/**
 * Props for the SelectButton component.
 */
interface SelectButtonProps {
    /** The visual style variant of the button. */
    variant?: "default" | "destructive" | "outline" | "primary" | "secondary" | "ghost" | "link" | "success" | "info" | "warning" | "error"
    /** The size preset for the button. */
    size?: "default" | "sm" | "lg" | "icon"
    /** Optional custom icon element. */
    icon?: React.ReactElement
    /** The name of the Ionicons icon to display. */
    iconName?: React.ComponentProps<typeof Ionicons>["name"]
    /** Whether the icon appears to the left or right of the text. */
    iconPosition?: "left" | "right"
    /** The list of options for the select dropdown. */
    options?: SelectOption[]
    /** The label for the option group. */
    groupLabel?: string
    /** The portal host for the select dropdown. */
    portalHost?: string
    /** The default selected value. */
    defaultValue?: string
    /** The placeholder text when no option is selected. */
    placeholder?: string
    /** The currently selected value. */
    value?: string
    /** The function to set the selected value. */
    setValue?: React.Dispatch<React.SetStateAction<string>>
    /** Callback fired when the value changes. */
    onValueChange?: (value: string | undefined) => void
    /** Callback fired when the button is pressed. */
    onPress?: () => void
    /** Optional custom style for the container. */
    style?: StyleProp<ViewStyle>
}

/**
 * A themed, configurable button component that opens a select dropdown.
 * Automatically applies theme-aware colors based on the selected variant and dark/light mode.
 * @param variant The visual style variant of the button.
 * @param size The size preset for the button.
 * @param icon Optional icon element.
 * @param iconName The name of the Ionicons icon to display.
 * @param iconPosition Whether the icon appears left or right.
 * @param options The list of options.
 * @param groupLabel The label for the option group.
 * @param portalHost The portal host for the select dropdown.
 * @param defaultValue The default selected value.
 * @param placeholder The placeholder text.
 * @param value The currently selected value.
 * @param setValue The function to set the selected value.
 * @param onValueChange Callback fired when the value changes.
 * @param onPress Callback fired when the button is pressed.
 * @param style Optional custom style for the container.
 */
const SelectButton: React.FC<SelectButtonProps> = ({
    variant = "default",
    size = "default",
    icon,
    iconName,
    iconPosition = "left",
    options = [],
    groupLabel,
    portalHost,
    defaultValue,
    placeholder,
    value,
    setValue,
    onValueChange,
    onPress,
    style,
}) => {
    // The current theme colors and dark mode status.
    const { colors, isDark } = useTheme()

    // Reference to the trigger element to measure its width.
    const triggerRef = useRef<View>(null)

    // The width of the trigger element to match the dropdown width.
    const [triggerWidth, setTriggerWidth] = useState<number>(0)

    // The currently selected option's label.
    const currentLabel = options.find((item) => item.value === value || item.value === defaultValue)?.label

    /**
     * Determine the background color based on variant and theme.
     * @returns The background color for the button.
     */
    const getBackgroundColor = () => {
        switch (variant) {
            case "destructive":
                return colors.destructive
            case "outline":
                return isDark ? "black" : "white"
            case "primary":
                return colors.brand
            case "secondary":
                return colors.surfaceRaised
            case "ghost":
                return "transparent"
            case "link":
                return "transparent"
            case "success":
                return colors.success
            case "info":
                return colors.info
            case "warning":
                return colors.warning
            case "error":
                return colors.error
            default:
                return colors.surfaceRaised
        }
    }

    /**
     * Determine the text color based on variant and theme.
     * @returns The text color for the button.
     */
    const getTextColor = () => {
        switch (variant) {
            case "destructive":
                return colors.destructiveForeground
            case "outline":
                return isDark ? "white" : "black"
            case "primary":
                return colors.onBrand
            case "secondary":
                return colors.text
            case "ghost":
                return isDark ? "white" : "black"
            case "link":
                return isDark ? "white" : "black"
            case "success":
                return colors.successContent
            case "info":
                return colors.infoContent
            case "warning":
                return colors.warningContent
            case "error":
                return colors.errorContent
            default:
                return colors.text
        }
    }

    const getSelectContentBorderColor = () => {
        switch (variant) {
            case "outline":
                return isDark ? "white" : "black"
            default:
                return getBackgroundColor()
        }
    }

    const styles = useMemo(
        () =>
            StyleSheet.create({
                container: {
                    flexDirection: "row",
                    alignItems: "center",
                },
                button: {
                    flex: 1,
                    borderTopRightRadius: 0,
                    borderBottomRightRadius: 0,
                    borderWidth: 0,
                },
                buttonDropdown: {
                    borderTopLeftRadius: 0,
                    borderBottomLeftRadius: 0,
                    borderColor: "transparent",
                    borderWidth: 0,
                    // The icon preset is 40 dp tall, so match the small preset's height to keep both halves even.
                    ...(size === "sm" ? { height: 36, width: 36 } : {}),
                },
            }),
        [colors, size]
    )

    /**
     * Callback fired when the trigger layout changes.
     * The trigger layout is used to determine the width of the dropdown content.
     * @param event The layout change event.
     */
    const onTriggerLayout = (event: LayoutChangeEvent) => {
        const { width: measuredWidth } = event.nativeEvent.layout
        setTriggerWidth(measuredWidth)
    }

    /**
     * Callback fired when the main button is pressed.
     */
    const onPressButton = () => {
        if (onPress) {
            onPress()
        }
    }

    /**
     * Callback fired when an option is selected from the dropdown.
     * @param option The selected option.
     */
    const handleValueChange = (option: Option) => {
        if (onValueChange) {
            onValueChange(option?.value || "")
        }
        if (setValue) {
            setValue(option?.value || "")
        }
    }

    /**
     * Determine which icon to display based on props.
     * @returns The icon element to render.
     */
    const getIcon = (): React.ReactElement | undefined => {
        if (icon) {
            return icon
        } else if (iconName) {
            return <Ionicons name={iconName} size={20} color={getTextColor()} />
        } else {
            return undefined
        }
    }

    return (
        <Select onValueChange={handleValueChange} value={value as any} defaultValue={defaultValue as any}>
            <View style={[styles.container, style]} ref={triggerRef} onLayout={onTriggerLayout}>
                <CustomButton style={styles.button} variant={variant as any} icon={getIcon()} iconPosition={iconPosition} size={size} isLoading={false} onPress={onPressButton}>
                    {currentLabel ?? placeholder}
                </CustomButton>
                <Separator orientation="vertical" style={{ backgroundColor: "transparent" }} />
                <SelectPrimitive.Trigger asChild>
                    <CustomButton style={styles.buttonDropdown} variant={variant as any} size={"icon"} isLoading={false}>
                        <Ionicons name="caret-down" size={size === "sm" ? 16 : 20} color={getTextColor()} />
                    </CustomButton>
                </SelectPrimitive.Trigger>
            </View>
            <SelectContent
                style={{
                    width: triggerWidth,
                    backgroundColor: getBackgroundColor(),
                    borderColor: getSelectContentBorderColor(),
                }}
                align="end"
                sideOffset={1}
                position="popper"
                portalHost={portalHost}
            >
                <NativeSelectScrollView>
                    <SelectGroup>
                        {groupLabel && <SelectLabel style={{ color: getTextColor() }}>{groupLabel}</SelectLabel>}
                        {options &&
                            options.map((option) => (
                                <SelectItem key={option.value} label={option.label} value={option.value} disabled={option.disabled} textStyle={{ color: getTextColor() }}>
                                    {option.label}
                                </SelectItem>
                            ))}
                    </SelectGroup>
                </NativeSelectScrollView>
            </SelectContent>
        </Select>
    )
}

export default React.memo(SelectButton)
