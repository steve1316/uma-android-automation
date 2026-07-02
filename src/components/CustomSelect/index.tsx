import React, { useRef, useState } from "react"
import { View, LayoutChangeEvent, ViewStyle, Pressable } from "react-native"
import { Text } from "../ui/text"
import { useTheme } from "../../context/ThemeContext"
import { copyToClipboard } from "../../lib/utils"
import { pressSurfaceInner, pressSurfaceOuter } from "../../lib/pressSurface"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { Option, Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectSeparator, SelectTrigger, SelectValue, NativeSelectScrollView } from "../ui/select"
import SearchableItem from "../SearchableItem"

interface SelectOption {
    /** The underlying value for this option. */
    value: string
    /** The display label for this option. */
    label: string
    /** Optional muted description rendered below the label inside the dropdown. Use to explain an ambiguous option value. */
    description?: string
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
    /** Optional muted intro line rendered at the top of the dropdown, below the group label. Distinct from `description`, which renders outside the trigger. */
    menuDescription?: string
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
 * @param menuDescription Optional muted intro line rendered at the top of the dropdown, below the group label.
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
    menuDescription,
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
        <View style={[pressSurfaceOuter, style]}>
            <Pressable style={pressSurfaceInner} onLongPress={label ? () => copyToClipboard(label) : undefined} android_ripple={label ? { color: colors.ripple, foreground: true } : undefined}>
                {label && (
                    <View style={{ marginBottom: SPACING.xs }}>
                        <Text style={{ ...TYPE.h2, color: colors.text }}>{label}</Text>
                    </View>
                )}
                {description && (
                    <View>
                        <Text style={{ ...TYPE.body, color: colors.text, opacity: 0.7, marginBottom: SPACING.xs }}>{description}</Text>
                    </View>
                )}
                <Select onValueChange={handleValueChange} value={value as any} defaultValue={defaultValue as any} disabled={disabled}>
                    <View ref={triggerRef} style={[{ width: width as any }]} onLayout={onTriggerLayout}>
                        <SelectTrigger disabled={disabled}>
                            <SelectValue placeholder={value || defaultValue ? (currentLabel ?? "ERROR") : placeholder} style={{ color: colors.text }} />
                        </SelectTrigger>
                    </View>
                    <SelectContent style={{ width: triggerWidth }} portalHost={portalHost}>
                        <NativeSelectScrollView>
                            <SelectGroup>
                                {groupLabel && <SelectLabel>{groupLabel}</SelectLabel>}
                                {menuDescription && <Text style={{ ...TYPE.caption, color: colors.textMuted, paddingHorizontal: SPACING.sm, paddingBottom: SPACING.xs }}>{menuDescription}</Text>}
                                {options &&
                                    options.map((option, index) => (
                                        <React.Fragment key={option.value}>
                                            {index > 0 && <SelectSeparator />}
                                            <SelectItem label={option.label} value={option.value} disabled={option.disabled} description={option.description}>
                                                {option.label}
                                            </SelectItem>
                                        </React.Fragment>
                                    ))}
                            </SelectGroup>
                        </NativeSelectScrollView>
                    </SelectContent>
                </Select>
                {children}
            </Pressable>
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
