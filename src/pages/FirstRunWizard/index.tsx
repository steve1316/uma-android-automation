import { useCallback, useEffect, useMemo, useState } from "react"
import { ActivityIndicator, BackHandler, Pressable, ScrollView, StyleSheet, Text, View } from "react-native"
import Ionicons from "@react-native-vector-icons/ionicons"
import CustomButton from "../../components/CustomButton"
import SystemChecksWizard, { SystemCheckResults } from "../../components/SystemChecksWizard"
import { useTheme } from "../../context/ThemeContext"
import { useLegacyFileScan } from "../../hooks/useLegacyFileScan"
import { RADII } from "../../lib/radii"
import { SPACING } from "../../lib/spacing"
import { storageBridge, PickedFolder, INTERNAL_STORAGE_FOLDER } from "../../lib/storageBridge"
import { TYPE } from "../../lib/type"

const styles = StyleSheet.create({
    root: { flex: 1 },
    header: { paddingHorizontal: SPACING.md, paddingTop: SPACING.md, paddingBottom: SPACING.sm, alignItems: "center" },
    headerText: { ...TYPE.display },
    scrollArea: { flex: 1 },
    scrollContent: { padding: SPACING.md, paddingBottom: SPACING.md },
    accessBanner: { padding: 12, borderWidth: 1, borderRadius: RADII.md, marginBottom: SPACING.md },
    accessBannerText: { fontSize: 13, lineHeight: 18 },
    card: { borderRadius: RADII.lg, borderWidth: 1, overflow: "hidden", marginBottom: SPACING.md },
    progressRow: { flexDirection: "row", alignItems: "center", gap: SPACING.sm, paddingHorizontal: SPACING.md, paddingVertical: SPACING.md },
    progressTrack: { flex: 1, height: 4, borderRadius: 2, overflow: "hidden" },
    progressFill: { height: "100%" },
    progressLabel: { ...TYPE.monoLabel, fontSize: 11, letterSpacing: 0.6 },
    cardLabelRow: { flexDirection: "row", alignItems: "center", gap: SPACING.sm, paddingHorizontal: SPACING.md, paddingTop: SPACING.md, paddingBottom: SPACING.xs },
    cardLabel: { ...TYPE.monoLabel, fontSize: 11, letterSpacing: 0.6 },
    cardStatusPending: { width: 16, height: 16, borderRadius: 8, borderWidth: 1 },
    cardStatusComplete: { width: 16, height: 16, borderRadius: 8, alignItems: "center", justifyContent: "center" },
    cardBody: { paddingHorizontal: SPACING.md, paddingBottom: SPACING.md },
    headline: { fontSize: 18, fontWeight: "700", marginBottom: SPACING.sm },
    hint: { fontSize: 13, lineHeight: 19, marginBottom: SPACING.md },
    selected: { borderWidth: 1, borderRadius: RADII.md, padding: 14 },
    selectedLabel: { fontSize: 11, fontWeight: "600", letterSpacing: 0.6, marginBottom: 4 },
    selectedName: { fontSize: 16, fontWeight: "600", marginBottom: 8 },
    selectedSub: { fontSize: 12, lineHeight: 18, marginBottom: 8 },
    changeLink: { fontSize: 12, textAlign: "center", textDecorationLine: "underline" },
    changeLinkPressable: { alignSelf: "center", paddingVertical: 10, paddingHorizontal: 12 },
    errorBlock: { marginTop: 12, gap: 8 },
    error: { fontSize: 13 },
    migrationHint: { fontSize: 13, lineHeight: 19, marginBottom: SPACING.sm },
    migCard: { flexDirection: "row", gap: 12, padding: 12, borderWidth: 1, borderRadius: RADII.md, marginBottom: 8, alignItems: "center" },
    migCardDisabled: { opacity: 0.5 },
    migIcon: { fontSize: 18, fontWeight: "700", width: 22, textAlign: "center" },
    migTitle: { fontSize: 14, fontWeight: "600" },
    migMeta: { fontSize: 12, marginTop: 2 },
    migErrorBox: { borderWidth: 1, borderRadius: RADII.md, padding: 12, marginBottom: 12 },
    migErrorText: { fontSize: 13, marginBottom: 8 },
    migConfirmation: { flexDirection: "row", gap: 12, padding: 4, alignItems: "center" },
    footer: { padding: SPACING.md, borderTopWidth: 1 },
    saveError: { fontSize: 12, marginBottom: 8, textAlign: "center" },
    footerRow: { flexDirection: "row", gap: 12 },
    footerButton: { flex: 1 },
})

/** Props for `FirstRunWizard`. */
interface Props {
    /** Called when the user taps Finish and folder validation succeeds. Should mark the SQLite flag and unmount. */
    onComplete: () => Promise<void>
}

/** The user's choice on the migration card. */
export type MigrationChoice = "move" | "leave" | "delete"

/** Build one of the three migration option Pressables.
 *
 * @param choice Stable identifier passed back to `onPress` when this card is tapped.
 * @param icon Single-glyph string shown on the left of the card.
 * @param title Heading text.
 * @param meta Sub-text below the title.
 * @param danger When true, paints the card in the error palette.
 * @param primary When true, paints the card in the primary palette (used for the recommended choice).
 * @param unavailable When true, dims the card and ignores taps, e.g. "Move them" before a real folder is picked.
 * @param busy The choice currently mid-flight, or `null` if none. Disables all three when non-null.
 * @param onPress Fires when the user taps an enabled card.
 * @param colors Theme palette from `useTheme()`.
 * @returns A `Pressable` rendering one migration option card.
 */
const renderMigrationCard = (
    choice: MigrationChoice,
    icon: string,
    title: string,
    meta: string,
    danger: boolean,
    primary: boolean,
    unavailable: boolean,
    busy: MigrationChoice | null,
    onPress: (c: MigrationChoice) => void,
    colors: ReturnType<typeof useTheme>["colors"]
) => {
    const accent = danger ? colors.error : primary ? colors.primary : colors.text
    const border = danger ? colors.error : primary ? colors.primary : colors.borderHair
    return (
        <Pressable
            key={choice}
            onPress={() => onPress(choice)}
            disabled={unavailable || busy !== null}
            style={[styles.migCard, { borderColor: border, backgroundColor: colors.surface }, unavailable && styles.migCardDisabled]}
        >
            <Text style={[styles.migIcon, { color: accent }]}>{icon}</Text>
            <View style={{ flex: 1 }}>
                <Text style={[styles.migTitle, { color: accent }]}>{title}</Text>
                <Text style={[styles.migMeta, { color: colors.textMuted }]}>{meta}</Text>
            </View>
            {busy === choice && <ActivityIndicator size="small" color={colors.primary} />}
        </Pressable>
    )
}

/** Build the small 16-px status badge shown next to each card label.
 *
 * @param complete When true, renders a filled `colors.success` circle with an `Ionicons` checkmark inside.
 *                 When false, renders an empty circle outlined with `colors.borderHair`.
 * @param colors Theme palette from `useTheme()`.
 * @returns A `View` rendering the badge.
 */
const renderStatusBadge = (complete: boolean, colors: ReturnType<typeof useTheme>["colors"]) => {
    if (complete) {
        return (
            <View style={[styles.cardStatusComplete, { backgroundColor: colors.success }]}>
                <Ionicons name="checkmark" size={10} color={colors.background} />
            </View>
        )
    }
    return <View style={[styles.cardStatusPending, { borderColor: colors.borderHair }]} />
}

/** First-run wizard rendered as a single scrollable page. Three cards stack inline: Storage folder, Move existing files (only when legacy files
 * exist), and System permissions. A fixed Cancel + Finish footer drives the page. Cancel exits the app; Finish re-validates folder access then
 * awaits `onComplete`.
 *
 * @param props See `Props`.
 * @returns A React node.
 */
const FirstRunWizard = ({ onComplete }: Props) => {
    const { colors } = useTheme()
    const { counts, hasLegacyFiles } = useLegacyFileScan()
    const [picked, setPicked] = useState<PickedFolder | null>(null)
    const [pickError, setPickError] = useState<{ message: string; canRetry: boolean } | null>(null)
    const [migrationChoice, setMigrationChoice] = useState<MigrationChoice | null>(null)
    const [migrationBusy, setMigrationBusy] = useState<MigrationChoice | null>(null)
    const [migrationError, setMigrationError] = useState<string | null>(null)
    const [permissionsGranted, setPermissionsGranted] = useState<SystemCheckResults | null>(null)
    const [accessError, setAccessError] = useState<string | null>(null)
    const [saveError, setSaveError] = useState<string | null>(null)
    const [finishing, setFinishing] = useState(false)

    useEffect(() => {
        let cancelled = false
        ;(async () => {
            try {
                const existing = await storageBridge.getCurrentFolder()
                if (!cancelled && existing) setPicked(existing)
            } catch (e) {
                console.warn("[FirstRunWizard] getCurrentFolder failed", e)
            }
        })()
        return () => {
            cancelled = true
        }
    }, [])

    const handleCancel = useCallback(() => {
        BackHandler.exitApp()
    }, [])

    useEffect(() => {
        const sub = BackHandler.addEventListener("hardwareBackPress", () => {
            handleCancel()
            return true
        })
        return () => sub.remove()
    }, [handleCancel])

    const handlePick = useCallback(async () => {
        setPickError(null)
        try {
            if ((await storageBridge.pickFolder()) == null) return
            const folder = await storageBridge.getCurrentFolder()
            if (folder) {
                setPicked(folder)
                setAccessError(null)
            }
        } catch (e) {
            // NO_PICKER means the device has no document picker app, so retrying is pointless - steer them to the fallback below.
            if ((e as { code?: string })?.code === "NO_PICKER") {
                setPickError({ message: "This device has no compatible folder picker functionality. Use app default storage below.", canRetry: false })
            } else {
                setPickError({ message: "Couldn't open the folder picker. Retry?", canRetry: true })
            }
        }
    }, [])

    const handleUseInternalStorage = useCallback(async () => {
        setPickError(null)
        try {
            await storageBridge.clearFolder()
        } catch (e) {
            console.warn("[FirstRunWizard] clearFolder failed", e)
        }
        setPicked(INTERNAL_STORAGE_FOLDER)
        setAccessError(null)
    }, [])

    // Moving needs a real SAF folder to copy into. With none picked, or with app default storage, the copy target is the source file itself.
    const hasPickedFolder = picked !== null && picked.uri !== ""

    const handleMigrationChoice = useCallback(
        async (choice: MigrationChoice) => {
            if (choice === "move" && !hasPickedFolder) return
            setMigrationError(null)
            setMigrationBusy(choice)
            try {
                if (choice !== "leave") {
                    const result = await storageBridge.migrateLegacyFiles(choice)
                    if (result.error === "NO_DESTINATION") {
                        setMigrationError("Nothing was moved. The picked folder is no longer accessible. Pick it again or leave the files where they are.")
                        setMigrationBusy(null)
                        return
                    }
                    if (result.error) {
                        const total = (counts?.logs ?? 0) + (counts?.recordings ?? 0)
                        const moved = result.movedLogs + result.movedRecordings
                        const reason = result.error === "OUT_OF_SPACE" ? "Out of space on your new folder." : "Permission denied on a source file."
                        setMigrationError(`Moved ${moved} of ${total} files. ${reason}`)
                        setMigrationBusy(null)
                        return
                    }
                }
                setMigrationChoice(choice)
                setMigrationBusy(null)
            } catch (e) {
                console.warn("[FirstRunWizard] migrate failed", e)
                setMigrationError("Migration failed unexpectedly. Retry or continue without moving.")
                setMigrationBusy(null)
            }
        },
        [counts, hasPickedFolder]
    )

    const folderComplete = picked !== null
    const usingInternalDefault = picked?.uri === ""
    const migrationComplete = !hasLegacyFiles || migrationChoice !== null
    const permissionsComplete = permissionsGranted !== null && permissionsGranted.accessibility && permissionsGranted.overlay && permissionsGranted.battery
    const canFinish = folderComplete && migrationComplete && permissionsComplete
    const stepsTotal = 2 + (hasLegacyFiles ? 1 : 0)
    const stepsCompleted = (folderComplete ? 1 : 0) + (hasLegacyFiles && migrationComplete ? 1 : 0) + (permissionsComplete ? 1 : 0)

    const handleFinish = useCallback(async () => {
        if (!canFinish) return
        setSaveError(null)
        setFinishing(true)
        // Internal storage has no SAF Uri to probe, and validateAccess() reports false for a null tree, so accept it directly.
        let ok = false
        if (usingInternalDefault) {
            ok = true
        } else {
            try {
                ok = await storageBridge.validateAccess()
            } catch {
                ok = false
            }
        }
        if (!ok) {
            setFinishing(false)
            setPicked(null)
            setAccessError("That folder is no longer accessible. Pick another one.")
            return
        }
        // Success path leaves `finishing` true intentionally. `onComplete` is contracted to unmount the wizard, so resetting
        // the flag would just flicker `Finish` back from `Saving...` before unmount.
        try {
            await onComplete()
        } catch {
            setSaveError("Couldn't save your setup. Tap Finish to retry.")
            setFinishing(false)
        }
    }, [canFinish, onComplete, usingInternalDefault])

    const migrationConfirmationLabel = useMemo(() => {
        if (migrationChoice === "move") return "Moved to new folder"
        if (migrationChoice === "leave") return "Left at old location"
        if (migrationChoice === "delete") return "Deleted"
        return null
    }, [migrationChoice])

    return (
        <View style={[styles.root, { backgroundColor: colors.background }]}>
            <View style={[styles.header, { borderBottomColor: colors.borderHair }]}>
                <Text style={[styles.headerText, { color: colors.text }]}>First time setup</Text>
            </View>
            <View style={styles.progressRow}>
                <View style={[styles.progressTrack, { backgroundColor: colors.surface }]}>
                    <View style={[styles.progressFill, { width: `${(stepsCompleted / stepsTotal) * 100}%`, backgroundColor: colors.brand }]} />
                </View>
                <Text style={[styles.progressLabel, { color: colors.textMuted }]}>
                    {stepsCompleted} OF {stepsTotal}
                </Text>
            </View>
            <ScrollView style={styles.scrollArea} contentContainerStyle={styles.scrollContent} keyboardShouldPersistTaps="handled" showsVerticalScrollIndicator>
                {accessError && (
                    <View style={[styles.accessBanner, { backgroundColor: colors.warningSubtle ?? colors.surface, borderColor: colors.warning }]}>
                        <Text style={[styles.accessBannerText, { color: colors.warning }]}>{accessError}</Text>
                    </View>
                )}

                <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.borderHair }]}>
                    <View style={styles.cardLabelRow}>
                        {renderStatusBadge(folderComplete, colors)}
                        <Text style={[styles.cardLabel, { color: colors.textMuted }]}>STORAGE FOLDER</Text>
                    </View>
                    <View style={styles.cardBody}>
                        {picked === null ? (
                            <>
                                <Text style={[styles.headline, { color: colors.text }]}>Where should the bot save your files?</Text>
                                <Text style={[styles.hint, { color: colors.textMuted }]}>
                                    Choose a folder somewhere you can open in your file manager. Recent Android versions hide app-private storage from file managers, so the bot needs a spot you
                                    control. It will put its logs/ and recordings/ subfolders inside whatever you pick.
                                </Text>
                                <CustomButton variant="primary" onPress={handlePick}>
                                    Pick a folder
                                </CustomButton>
                                {pickError && (
                                    <View style={styles.errorBlock}>
                                        <Text style={[styles.error, { color: colors.error }]}>{pickError.message}</Text>
                                        {pickError.canRetry && (
                                            <CustomButton variant="primary" onPress={handlePick}>
                                                Retry
                                            </CustomButton>
                                        )}
                                    </View>
                                )}
                                <Pressable onPress={handleUseInternalStorage} style={styles.changeLinkPressable} hitSlop={8}>
                                    <Text style={[styles.changeLink, { color: colors.primary }]}>Use app default storage instead</Text>
                                </Pressable>
                            </>
                        ) : (
                            <View style={[styles.selected, { borderColor: colors.success }]}>
                                <Text style={[styles.selectedLabel, { color: colors.success }]}>SELECTED</Text>
                                <Text style={[styles.selectedName, { color: colors.text }]}>{picked.name}</Text>
                                <Text style={[styles.selectedSub, { color: colors.textMuted }]}>logs/{"\n"}recordings/</Text>
                                <Pressable onPress={handlePick} style={styles.changeLinkPressable} hitSlop={8}>
                                    <Text style={[styles.changeLink, { color: colors.primary }]}>{usingInternalDefault ? "Pick a folder instead" : "Change folder"}</Text>
                                </Pressable>
                            </View>
                        )}
                    </View>
                </View>

                {hasLegacyFiles && counts && (
                    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.borderHair }]}>
                        <View style={styles.cardLabelRow}>
                            {renderStatusBadge(migrationComplete, colors)}
                            <Text style={[styles.cardLabel, { color: colors.textMuted }]}>MOVE YOUR EXISTING FILES?</Text>
                        </View>
                        <View style={styles.cardBody}>
                            {migrationChoice === null ? (
                                <>
                                    <Text style={[styles.migrationHint, { color: colors.textMuted }]}>
                                        Found {counts.logs} logs and {counts.recordings} recordings at the old location. Pick what to do with them.
                                    </Text>
                                    {migrationError && (
                                        <View style={[styles.migErrorBox, { borderColor: colors.error }]}>
                                            <Text style={[styles.migErrorText, { color: colors.error }]}>{migrationError}</Text>
                                            <CustomButton onPress={() => setMigrationChoice("leave")}>Continue with partial move</CustomButton>
                                        </View>
                                    )}
                                    {renderMigrationCard(
                                        "move",
                                        "->",
                                        "Move them",
                                        hasPickedFolder ? "Copy to your new folder, remove originals" : "Pick a folder above to move them",
                                        false,
                                        true,
                                        !hasPickedFolder,
                                        migrationBusy,
                                        handleMigrationChoice,
                                        colors
                                    )}
                                    {renderMigrationCard("leave", "x", "Leave them", "Keep at old path, new files use new folder", false, false, false, migrationBusy, handleMigrationChoice, colors)}
                                    {renderMigrationCard("delete", "X", "Delete them", "Permanent.", true, false, false, migrationBusy, handleMigrationChoice, colors)}
                                </>
                            ) : (
                                <View style={styles.migConfirmation}>
                                    <Text style={[styles.migIcon, { color: colors.success }]}>v</Text>
                                    <Text style={[styles.migTitle, { color: colors.text }]}>{migrationConfirmationLabel}</Text>
                                </View>
                            )}
                        </View>
                    </View>
                )}

                <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.borderHair }]}>
                    <View style={styles.cardLabelRow}>
                        {renderStatusBadge(permissionsComplete, colors)}
                        <Text style={[styles.cardLabel, { color: colors.textMuted }]}>SYSTEM PERMISSIONS</Text>
                    </View>
                    <SystemChecksWizard onPermissionsChange={setPermissionsGranted} embeddedInWizard />
                </View>
            </ScrollView>

            <View style={[styles.footer, { backgroundColor: colors.background, borderTopColor: colors.borderHair }]}>
                {saveError && <Text style={[styles.saveError, { color: colors.error }]}>{saveError}</Text>}
                <View style={styles.footerRow}>
                    <View style={styles.footerButton}>
                        <CustomButton variant="ghost" onPress={handleCancel} disabled={finishing}>
                            Cancel
                        </CustomButton>
                    </View>
                    <View style={styles.footerButton}>
                        <CustomButton onPress={handleFinish} disabled={!canFinish || finishing}>
                            {finishing ? "Saving..." : "Finish"}
                        </CustomButton>
                    </View>
                </View>
            </View>
        </View>
    )
}

export default FirstRunWizard
