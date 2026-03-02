import React, { useEffect, useRef, useState } from "react"
import { Animated, View, StyleProp, ViewStyle } from "react-native"
import { useRoute, useNavigation } from "@react-navigation/native"
import { useTheme } from "../../context/ThemeContext"
import { useSearchRegistry } from "../../context/SearchRegistryContext"
import { useSearchPage } from "../../context/SearchPageContext"

interface SearchableItemProps {
    /** The unique identifier for this item. */
    id: string
    /** The searchable title. If not provided, it will be extracted from the children. */
    title?: string
    /** The searchable description. If not provided, it will be extracted from the children. */
    description?: string
    /** The target route name to navigate to. */
    page?: string
    /** The content of the item. */
    children: React.ReactNode
    /** Pass the parent ScrollView ref to automatically scroll to this item. */
    scrollViewRef?: React.RefObject<any>
    /** If this item is conditionally hidden, pass the parent ID to highlight instead. */
    parentId?: string
    /** If provided and false, the content will not render but the item remains searchable, falling back to its parent. */
    condition?: boolean
    /** Optional style to apply to the item. */
    style?: StyleProp<ViewStyle>
}

/**
 * The inner content which consumes useRoute for highlight and scroll-to behavior.
 * @param id The ID of the item.
 * @param children The children of the item.
 * @param scrollViewRef The ref of the parent ScrollView.
 * @param style Optional style to apply to the item.
 */
const SearchableItemContent = ({ id, children, scrollViewRef, style }: SearchableItemProps) => {
    const route = useRoute<any>()
    const navigation = useNavigation<any>()
    const { colors } = useTheme()
    const highlightAnim = useRef(new Animated.Value(0)).current
    const highlightColor = useRef(colors.primary).current
    const highlightFallbackColor = "orange"
    const viewRef = useRef<any>(null)
    const pageContext = useSearchPage()

    // State-driven so that re-render updates the interpolation color before animation starts.
    const [isFallback, setIsFallback] = useState(false)
    const [animTrigger, setAnimTrigger] = useState(0)

    const finalScrollViewRef = scrollViewRef || pageContext?.scrollViewRef

    /**
     * Runs the highlight animation and scrolls to the element.
     */
    const runHighlight = () => {
        // Animate the border color to indicate the selected item.
        Animated.sequence([
            Animated.timing(highlightAnim, {
                toValue: 1,
                duration: 300,
                useNativeDriver: false,
            }),
            Animated.delay(1000),
            Animated.timing(highlightAnim, {
                toValue: 0,
                duration: 500,
                useNativeDriver: false,
            }),
        ]).start()

        // Scroll to the element.
        if (finalScrollViewRef?.current && viewRef.current) {
            // Wait for layout to settle before measuring.
            setTimeout(() => {
                if (viewRef.current && finalScrollViewRef.current) {
                    try {
                        viewRef.current.measureLayout(
                            finalScrollViewRef.current as any,
                            (x: number, y: number) => {
                                // Scroll slightly above the item so it's not hugging the top edge.
                                finalScrollViewRef.current.scrollTo({ y: Math.max(0, y - 20), animated: true })
                            },
                            () => {
                                console.warn("Failed to measure layout for scrolling from SearchableItem.")
                            }
                        )
                    } catch (e) {
                        console.warn("Error measuring layout while scrolling from SearchableItem.", e)
                    }
                }
            }, 100) // Small delay to ensure render is complete.
        }
    }

    // Primary highlight: triggers immediately when this item is the direct target.
    useEffect(() => {
        if (route.params?.targetId === id) {
            // Clear fallbackTargetId so the parent doesn't also highlight.
            if (route.params?.fallbackTargetId) {
                navigation.setParams({ fallbackTargetId: undefined })
            }

            setIsFallback(false)
            setAnimTrigger((prev) => prev + 1)
        }
    }, [route.params?.targetId, id])

    // Fallback highlight: triggers after a delay when this item is the fallback parent.
    // The delay gives the primary target time to mount and clear fallbackTargetId.
    useEffect(() => {
        if (route.params?.fallbackTargetId === id) {
            const timer = setTimeout(() => {
                setIsFallback(true)
                setAnimTrigger((prev) => prev + 1)
            }, 300)

            return () => clearTimeout(timer)
        }
    }, [route.params?.fallbackTargetId, id])

    // Runs the animation after state has been committed so the interpolation uses the correct color.
    useEffect(() => {
        if (animTrigger > 0) {
            runHighlight()
        }
    }, [animTrigger])

    // This border color animates from transparent to the highlight color (or fallback color if needed).
    const borderColor = highlightAnim.interpolate({
        inputRange: [0, 1],
        outputRange: ["transparent", isFallback ? highlightFallbackColor : highlightColor],
    })

    return (
        <View ref={viewRef} collapsable={false} style={style}>
            <Animated.View style={{ borderColor, borderWidth: 2, borderRadius: 8 }}>{children}</Animated.View>
        </View>
    )
}

/**
 * Wraps a setting component. If the current route is targeting this component's ID,
 * it temporarily highlights it and automatically scrolls the parent `ScrollView` to it.
 * Also dynamically registers the item into the global `SearchRegistry`.
 * @param id The unique identifier for this item.
 * @param title The searchable title. If not provided, it will be extracted from the children.
 * @param description The searchable description. If not provided, it will be extracted from the children.
 * @param page The target route name to navigate to.
 * @param children The content of the item.
 * @param scrollViewRef The ref of the parent ScrollView.
 * @param parentId If provided and false, the content will not render but the item remains searchable, falling back to its parent.
 * @param condition If provided and false, the content will not render but the item remains searchable, falling back to its parent.
 * @param style Optional style to apply to the item.
 */
const SearchableItem = ({ id, title, description, page, children, scrollViewRef, parentId, condition, style }: SearchableItemProps) => {
    const { registerItem } = useSearchRegistry()
    const pageContext = useSearchPage()

    const finalPage = page || pageContext?.page

    let derivedTitle = title
    let derivedDescription = description

    /**
     * Recursively traverses the children to find title and description metadata.
     * @param node The node to extract from.
     */
    const extractFromChildren = (node: React.ReactNode) => {
        React.Children.forEach(node, (child) => {
            if (React.isValidElement(child)) {
                const element = child as React.ReactElement<any>

                // Attempt to harvest the title/label from the child's props.
                if (!derivedTitle && (element.props.label || element.props.title)) {
                    derivedTitle = element.props.label || element.props.title
                }

                // Attempt to harvest the description from the child's props.
                if (!derivedDescription && element.props.description) {
                    derivedDescription = element.props.description
                }

                // If we still haven't found both, recurse deeper into the children.
                if (element.props.children && (!derivedTitle || !derivedDescription)) {
                    extractFromChildren(element.props.children)
                }
            }
        })
    }

    // If we don't have a title or description yet, try to extract them from the children.
    if (!derivedTitle || !derivedDescription) {
        extractFromChildren(children)
    }

    // Use the ID as a fallback for the title if it's still missing.
    const finalTitle = derivedTitle || id
    const finalDescription = derivedDescription || ""

    useEffect(() => {
        // Automatically register this item into the global search index on mount.
        // We defer this until after interactions (like the drawer opening/closing or page mounting)
        // have finished to reduce the UI lag during transitions.
        if (finalPage) {
            const timeoutHandle = setTimeout(() => {
                // Only emit the parentId fallback if we are actively hiding the component.
                const effectiveParentId = condition === false ? parentId : undefined
                registerItem({ id, title: finalTitle, description: finalDescription, page: finalPage, parentId: effectiveParentId })
            }, 0)

            return () => clearTimeout(timeoutHandle)
        }
    }, [id, finalTitle, finalDescription, finalPage, parentId, condition, registerItem])

    // If a condition is explicitly passed and is false, do not render the content.
    // This allows conditionally hidden items to remain searchable and fallback to their parent.
    if (condition === false) {
        return null
    }

    return (
        <SearchableItemContent id={id} title={title} description={description} page={page} scrollViewRef={scrollViewRef} style={style}>
            {children}
        </SearchableItemContent>
    )
}

export default SearchableItem
