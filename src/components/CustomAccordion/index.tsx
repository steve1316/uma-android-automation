import React, { useMemo, useState } from "react"
import { Text, StyleSheet, ViewStyle } from "react-native"
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "../ui/accordion"
import * as AccordionPrimitive from "@rn-primitives/accordion"
import { useTheme } from "../../context/ThemeContext"

interface AccordionSection {
    /** The unique value identifier for this accordion section. */
    value: string
    /** The display title for this accordion section. */
    title: string
    /** The content to render inside this accordion section. */
    children: React.ReactNode
}

interface CustomAccordionProps {
    /** The list of accordion sections to render. */
    sections: AccordionSection[]
    /** Whether only one section can be open at a time or multiple. */
    type?: "single" | "multiple"
    /** The initially expanded section values. */
    defaultValue?: string[]
    /** Optional NativeWind class name. */
    className?: string
    /** Optional custom style for the accordion container. */
    style?: ViewStyle
}

/**
 * Wraps accordion content to defer rendering until the section is first expanded.
 * This prevents heavy children (e.g., 20 sliders) from mounting on initial page load.
 * @param children The content to render inside this accordion section.
 */
const LazyAccordionContent: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    const { isExpanded } = AccordionPrimitive.useItemContext()
    const [hasBeenExpanded, setHasBeenExpanded] = useState(false)

    // Once expanded, keep the content mounted to preserve state.
    if (isExpanded && !hasBeenExpanded) {
        setHasBeenExpanded(true)
    }

    return <AccordionContent>{hasBeenExpanded ? children : null}</AccordionContent>
}

/**
 * A themed accordion component that supports single or multiple open sections.
 * Each section uses lazy content rendering to defer mounting until first expanded.
 * @param sections The list of accordion sections to render.
 * @param type Whether only one or multiple sections can be open at a time.
 * @param defaultValue The initially expanded section values.
 * @param className Optional NativeWind class name.
 * @param style Optional custom style for the accordion container.
 */
const CustomAccordion: React.FC<CustomAccordionProps> = ({ sections, type = "single", defaultValue = [], className, style }) => {
    const { colors } = useTheme()

    const styles = useMemo(
        () =>
            StyleSheet.create({
                sectionTitle: {
                    fontSize: 16,
                    fontWeight: "600",
                    color: colors.foreground,
                    marginBottom: 0,
                },
            }),
        [colors]
    )

    return (
        <Accordion type={type} defaultValue={defaultValue} className={className} style={style}>
            {sections.map((section) => (
                <AccordionItem key={section.value} value={section.value}>
                    <AccordionTrigger>
                        <Text style={styles.sectionTitle}>{section.title}</Text>
                    </AccordionTrigger>
                    <LazyAccordionContent>{section.children}</LazyAccordionContent>
                </AccordionItem>
            ))}
        </Accordion>
    )
}

export default CustomAccordion
