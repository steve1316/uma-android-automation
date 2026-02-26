import React, { useRef, useState } from "react"
import { View, LayoutChangeEvent, ViewStyle } from "react-native"
import { Text } from "../ui/text"
import { useTheme } from "../../context/ThemeContext"
import { Option, Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectTrigger, SelectValue, NativeSelectScrollView } from "../ui/select"
import SearchableItem from "../SearchableItem"

interface SelectOption {
    value: string
    label: string
    disabled?: boolean
}

interface CustomSelectProps {
    placeholder?: string
    options?: SelectOption[]
    width?: string | number
    groupLabel?: string
    onValueChange?: (value: string | undefined) => void
    setValue?: React.Dispatch<React.SetStateAction<string>>
    defaultValue?: string
    value?: string
    disabled?: boolean
    style?: ViewStyle
    portalHost?: string
    label?: string
    description?: string
    // Search props
    searchId?: string
    searchTitle?: string
    searchDescription?: string
    searchCondition?: boolean
    parentId?: string
    children?: React.ReactNode
}

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

    const onTriggerLayout = (event: LayoutChangeEvent) => {
        const { width: measuredWidth } = event.nativeEvent.layout
        setTriggerWidth(measuredWidth)
    }

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
