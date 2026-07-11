import React, { useMemo } from "react"
import { View, Text, StyleSheet } from "react-native"
import { useTheme } from "../../context/ThemeContext"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"
import { RADII } from "../../lib/radii"
import CustomButton from "../CustomButton"

/** Bot run states surfaced on the hero card. */
export type HeroStatus = "ready" | "running" | "stopped" | "error"

/** Props for `HeroStatusCard`. */
interface HeroStatusCardProps {
    /** Current bot status pill. */
    status: HeroStatus
    /** Active profile name (e.g. "Default"). */
    profile: string
    /** Press handler for the default Start CTA. Ignored when `cta` is provided. */
    onStart?: () => void
    /** Whether the default Start button is disabled. Defaults to false. Ignored when `cta` is provided. */
    startDisabled?: boolean
    /** Optional custom action rendered on the right side in place of the default Start button. */
    cta?: React.ReactNode
    /** Optional nav-chips rendered on the status line beside the pill (SRS / Debug / Test / Style shortcuts). */
    chips?: React.ReactNode
    /** Optional "at a glance" zone rendered below a divider (skill plans, stat priority). */
    glance?: React.ReactNode
}

const STATUS_LABEL: Record<HeroStatus, string> = {
    ready: "Ready",
    running: "Running",
    stopped: "Stopped",
    error: "Error",
}

const BULLET = "●" // BLACK CIRCLE

/**
 * Home dashboard hero card: status pill + active profile + primary action.
 * Brand-tinted surface anchors the page; campaign name and avatar live in the
 * scenario picker and the drawer respectively, so neither is repeated here.
 * @param status Current bot status.
 * @param profile Active profile name.
 * @param onStart Press handler for the default Start CTA. Ignored when `cta` is provided.
 * @param startDisabled Whether the default Start button is disabled. Ignored when `cta` is provided.
 * @param cta Optional custom right-side action that replaces the default Start button.
 * @param chips Optional nav-chips rendered on the status line beside the pill.
 * @param glance Optional "at a glance" zone rendered below a divider (skill plans, stat priority).
 * @returns A brand-tinted card containing the status pill, nav-chips, profile name, primary action, and optional glance zone.
 */
const HeroStatusCard: React.FC<HeroStatusCardProps> = ({ status, profile, onStart, startDisabled = false, cta, chips, glance }) => {
    const { colors } = useTheme()
    // Status pill color: ready/running -> success token, stopped/error -> warning.
    const isHealthy = status === "ready" || status === "running"
    const styles = useMemo(
        () =>
            StyleSheet.create({
                card: {
                    backgroundColor: colors.brandSubtle,
                    borderWidth: 1,
                    borderColor: colors.brandBorder,
                    borderRadius: RADII.xl,
                },
                row: { flexDirection: "row", alignItems: "center", gap: SPACING.md, padding: SPACING.md },
                body: { flex: 1, gap: 4 },
                statusLine: { flexDirection: "row", flexWrap: "wrap", alignItems: "center", gap: 6 },
                statusPill: {
                    ...TYPE.monoLabel,
                    color: isHealthy ? colors.success : colors.warning,
                    paddingHorizontal: SPACING.sm,
                    paddingVertical: 2,
                    backgroundColor: isHealthy ? colors.successSubtle : colors.warningSubtle,
                    borderRadius: RADII.pill,
                },
                profile: { ...TYPE.h2, color: colors.text },
                hairline: { height: 1, backgroundColor: colors.borderHair, marginHorizontal: SPACING.md },
            }),
        [colors, isHealthy]
    )
    return (
        <View style={styles.card}>
            <View style={styles.row}>
                <View style={styles.body}>
                    <View style={styles.statusLine}>
                        <Text style={styles.statusPill}>{`${BULLET} ${STATUS_LABEL[status]}`}</Text>
                        {chips}
                    </View>
                    <Text style={styles.profile}>{profile}</Text>
                </View>
                {cta ?? (
                    <CustomButton variant="primary" size="sm" onPress={onStart} disabled={startDisabled}>
                        Start
                    </CustomButton>
                )}
            </View>
            {glance ? (
                <>
                    <View style={styles.hairline} />
                    {glance}
                </>
            ) : null}
        </View>
    )
}

export default React.memo(HeroStatusCard)
