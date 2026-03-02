import React from "react"
import { View, ViewStyle } from "react-native"
import { Checkbox } from "../ui/checkbox"
import { Label } from "../ui/label"
import { Text } from "../ui/text"
import { useTheme } from "../../context/ThemeContext"
import SearchableItem from "../SearchableItem"

interface CustomCheckboxProps {
    /** Whether the checkbox is currently checked. */
    checked: boolean
    /** Callback fired when the checked state changes. */
    onCheckedChange: (checked: boolean) => void
    /** The label text displayed next to the checkbox. */
    label: string
    /** Optional description text displayed below the label. */
    description?: string | null
    /** Optional NativeWind class name. */
    className?: string
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
    /** Optional children rendered below the description. */
    children?: React.ReactNode
}

/**
 * A themed checkbox component with a label, optional description, and search integration.
 * Wraps content in a `SearchableItem` when a `searchId` is provided.
 * @param checked Whether the checkbox is currently checked.
 * @param onCheckedChange Callback fired when the checked state changes.
 * @param label The label text displayed next to the checkbox.
 * @param description Optional description text displayed below the label.
 * @param className Optional NativeWind class name.
 * @param style Optional custom style for the container.
 * @param searchId Optional search ID for registering this item in the search index.
 * @param searchTitle Optional override for the searchable title (defaults to label).
 * @param searchDescription Optional override for the searchable description.
 * @param searchCondition Optional condition controlling whether this item is registered in the search index.
 * @param parentId Optional ID of the parent searchable item for hierarchical search.
 * @param children Optional children rendered below the description.
 */
const CustomCheckbox: React.FC<CustomCheckboxProps> = ({
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
            <Checkbox checked={checked} onCheckedChange={onCheckedChange} className="dark:border-gray-400" />

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
