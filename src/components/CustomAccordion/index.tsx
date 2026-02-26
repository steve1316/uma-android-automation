import React, { useMemo, useState } from "react"
import { Text, StyleSheet, ViewStyle } from "react-native"
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "../ui/accordion"
import * as AccordionPrimitive from "@rn-primitives/accordion"
import { useTheme } from "../../context/ThemeContext"

interface AccordionSection {
    value: string
    title: string
    children: React.ReactNode
}

interface CustomAccordionProps {
    sections: AccordionSection[]
    type?: "single" | "multiple"
    defaultValue?: string[]
    className?: string
    style?: ViewStyle
}

/**
 * Wraps accordion content to defer rendering until the section is first expanded.
 * This prevents heavy children (e.g., 20 sliders) from mounting on initial page load.
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
        [colors],
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
