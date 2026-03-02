import React, { useRef, useState } from "react"
import { View, LayoutChangeEvent, ViewStyle } from "react-native"
import { Text } from "../ui/text"
import { useTheme } from "../../context/ThemeContext"
import { Option, Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue, NativeSelectScrollView } from "../ui/select"
import SearchableItem from "../SearchableItem"

interface SelectOption {
    /** The underlying value for this option. */
    value: string
    /** The display label for this option. */
    label: string
    /** Whether this option is disabled. */
    disabled?: boolean
}

interface CustomSelectProps {
    /** The placeholder text shown when no option is selected. */
    placeholder?: string
    /** The list of selectable options. */
    options?: SelectOption[]
    /** The width of the select trigger. */
    width?: string | number
    /** Optional label displayed above the options in the dropdown. */
    groupLabel?: string
    /** Callback fired when the selected value changes. */
    onValueChange?: (value: string | undefined) => void
    /** Optional state setter for two-way binding of the selected value. */
    setValue?: React.Dispatch<React.SetStateAction<string>>
    /** The initial default value. */
    defaultValue?: string
    /** The currently selected value (controlled mode). */
    value?: string
    /** Whether the select is disabled. */
    disabled?: boolean
    /** Optional custom style for the container. */
    style?: ViewStyle
    /** Optional portal host name for rendering the dropdown content. */
    portalHost?: string
    /** Optional label text displayed above the select. */
    label?: string
    /** Optional description text displayed below the label. */
    description?: string
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
    /** Optional children rendered below the select. */
    children?: React.ReactNode
}

/**
 * A themed select dropdown component with optional label, description, and search integration.
 * Measures the trigger width to size the dropdown content correctly.
 * Wraps content in a SearchableItem when a searchId is provided.
 * @param placeholder The placeholder text shown when no option is selected.
 * @param options The list of selectable options.
 * @param width The width of the select trigger.
 * @param groupLabel Optional label displayed above the options in the dropdown.
 * @param onValueChange Callback fired when the selected value changes.
 * @param setValue Optional state setter for two-way binding of the selected value.
 * @param defaultValue The initial default value.
 * @param value The currently selected value (controlled mode).
 * @param disabled Whether the select is disabled.
 * @param style Optional custom style for the container.
 * @param portalHost Optional portal host name for rendering the dropdown content.
 * @param label Optional label text displayed above the select.
 * @param description Optional description text displayed below the label.
 * @param searchId Optional search ID for registering this item in the search index.
 * @param searchTitle Optional override for the searchable title (defaults to label).
 * @param searchDescription Optional override for the searchable description.
 * @param searchCondition Optional condition controlling whether this item is registered in the search index.
 * @param parentId Optional ID of the parent searchable item for hierarchical search.
 * @param children Optional children rendered below the select.
 */
const CustomSelect: React.FC<CustomSelectProps> = ({
    placeholder = "Select an option",
    options = [],
    width = "100%",
    groupLabel,
    onValueChange,
    setValue,
    defaultValue,
    value,
    disabled = false,
    style,
    portalHost,
    label,
    description,
    searchId,
    searchTitle,
    searchDescription,
    searchCondition,
    parentId,
    children,
}) => {
    const [triggerWidth, setTriggerWidth] = useState<number>(0)
    const triggerRef = useRef<View>(null)
    const { colors } = useTheme()

    const currentLabel = options.find((item) => item.value === (value || defaultValue))?.label

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
     * Callback fired when the selected value changes.
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

    const content = (
        <View style={style}>
            {label && (
                <View style={{ marginBottom: 4 }}>
                    <Text style={{ fontSize: 16, fontWeight: "600", color: colors.foreground }}>{label}</Text>
                </View>
            )}
            {description && (
                <View>
                    <Text style={{ fontSize: 14, color: colors.foreground, opacity: 0.7, marginBottom: 4 }}>{description}</Text>
                </View>
            )}
            <Select onValueChange={handleValueChange} value={value as any} defaultValue={defaultValue as any} disabled={disabled}>
                <View ref={triggerRef} style={[{ width: width as any }]} onLayout={onTriggerLayout}>
                    <SelectTrigger style={{ backgroundColor: colors.background, borderColor: colors.border }}>
                        <SelectValue placeholder={value || defaultValue ? currentLabel ?? "ERROR" : placeholder} style={{ color: colors.foreground }} />
                    </SelectTrigger>
                </View>
                <SelectContent style={{ width: triggerWidth }} portalHost={portalHost}>
                    <NativeSelectScrollView>
                        <SelectGroup>
                            {groupLabel && <SelectLabel>{groupLabel}</SelectLabel>}
                            {options &&
                                options.map((option) => (
                                    <SelectItem key={option.value} label={option.label} value={option.value} disabled={option.disabled}>
                                        {option.label}
                                    </SelectItem>
                                ))}
                        </SelectGroup>
                    </NativeSelectScrollView>
                </SelectContent>
            </Select>
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

export default React.memo(CustomSelect)
