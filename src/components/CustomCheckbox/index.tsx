import React from "react"
import { View, ViewStyle } from "react-native"
import { Checkbox } from "../ui/checkbox"
import { Label } from "../ui/label"
import { Text } from "../ui/text"
import { useTheme } from "../../context/ThemeContext"
import SearchableItem from "../SearchableItem"

interface CustomCheckboxProps {
    id?: string
    checked: boolean
    onCheckedChange: (checked: boolean) => void
    label: string
    description?: string | null
    className?: string
    style?: ViewStyle
    // Search props
    searchId?: string
    searchTitle?: string
    searchDescription?: string
    searchCondition?: boolean
    parentId?: string
    children?: React.ReactNode
}

const CustomCheckbox: React.FC<CustomCheckboxProps> = ({
    id = undefined,
    checked,
    onCheckedChange,
    label,
    description,
    className = "",
    style,
    searchId,
    searchTitle,
    searchDescription,
    searchCondition,
    parentId,
    children,
}) => {
    const { colors } = useTheme()

    const content = (
        <View className={`flex-row items-start gap-3 ${className}`} style={style}>
            <Checkbox id={id} checked={checked} onCheckedChange={onCheckedChange} className="dark:border-gray-400" />

            {/* flexShrink is used to make sure the description wraps properly and not overflow past the right side of the screen. */}
            <View style={{ flexShrink: 1 }}>
                <Label style={{ color: colors.foreground, fontWeight: "bold" }} onPress={() => onCheckedChange(!checked)}>
                    {label}
                </Label>
                {description && (
                    <Text
                        className="text-sm mt-1"
                        style={{
                            color: colors.mutedForeground,
                        }}
                    >
                        {description}
                    </Text>
                )}
                {children}
            </View>
        </View>
    )

    if (searchId) {
        return (
            <SearchableItem id={searchId} title={searchTitle || label} description={searchDescription || description || undefined} parentId={parentId} condition={searchCondition}>
                {content}
            </SearchableItem>
        )
    }

    return content
}

export default React.memo(CustomCheckbox)
