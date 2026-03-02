import React from "react"
import type { GapRecord } from "../../lib/eventLogParser"
import WarningContainer from "../WarningContainer"

type Props = {
    /** The gap record indicating missing day(s) in the event log. */
    gap: GapRecord
}

/**
 * Displays a warning notice for missing days in the event log data.
 * Formats the message based on whether a single day or a range of days is missing.
 * @param gap The gap record indicating the missing day range.
 */
const GapsNotice: React.FC<Props> = ({ gap }) => {
    return <WarningContainer style={{ marginBottom: 10, marginTop: 0 }}>⚠️ {gap.from === gap.to ? `Day ${gap.from} missing.` : `Days ${gap.from}-${gap.to} missing.`}</WarningContainer>
}

export default GapsNotice
