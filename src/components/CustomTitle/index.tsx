import React, { useMemo } from "react"
import { Text, StyleSheet, TextStyle } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import SearchableItem from "../SearchableItem"

interface CustomTitleProps {
    title: string
    description?: string
    style?: TextStyle
    // Search props
    searchId?: string
    searchTitle?: string
    searchDescription?: string
    searchCondition?: boolean
    parentId?: string
}

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
        [colors],
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
