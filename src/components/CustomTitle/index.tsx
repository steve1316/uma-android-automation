import React, { useMemo } from "react"
import { Text, StyleSheet, TextStyle } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import SearchableItem from "../SearchableItem"

interface CustomTitleProps {
    /** The title text to display. */
    title: string
    /** Optional description text displayed below the title. */
    description?: string
    /** Optional custom style for the title text. */
    style?: TextStyle
    /** Optional search ID for registering this item in the search index. */
    searchId?: string
    /** Optional override for the searchable title (defaults to title). */
    searchTitle?: string
    /** Optional override for the searchable description. */
    searchDescription?: string
    /** Optional condition controlling whether this item is registered in the search index. */
    searchCondition?: boolean
    /** Optional ID of the parent searchable item for hierarchical search. */
    parentId?: string
}

/**
 * A themed section title component with optional description and search integration.
 * Wraps content in a `SearchableItem` when a `searchId` is provided.
 * @param title The title text to display.
 * @param description Optional description text displayed below the title.
 * @param style Optional custom style for the title text.
 * @param searchId Optional search ID for registering this item in the search index.
 * @param searchTitle Optional override for the searchable title (defaults to title).
 * @param searchDescription Optional override for the searchable description.
 * @param searchCondition Optional condition controlling whether this item is registered in the search index.
 * @param parentId Optional ID of the parent searchable item for hierarchical search.
 */
const CustomTitle = ({ title, description, style, searchId, searchTitle, searchDescription, searchCondition, parentId }: CustomTitleProps) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                sectionTitle: {
                    fontSize: 18,
                    fontWeight: "600",
                    color: colors.foreground,
                    marginBottom: 12,
                },
                description: {
                    fontSize: 14,
                    color: colors.foreground,
                    opacity: 0.7,
                    marginBottom: 16,
                    lineHeight: 20,
                },
            }),
        [colors]
    )

    const content = (
        <>
            <Text style={[styles.sectionTitle, style]}>{title}</Text>
            {description && <Text style={styles.description}>{description}</Text>}
        </>
    )

    if (searchId) {
        return (
            <SearchableItem id={searchId} title={searchTitle || title} description={searchDescription || description || undefined} parentId={parentId} condition={searchCondition}>
                {content}
            </SearchableItem>
        )
    }

    return content
}

export default React.memo(CustomTitle)
