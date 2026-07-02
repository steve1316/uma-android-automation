import React, { useCallback, useEffect, useMemo, useState } from "react"
import { View, ScrollView, StyleSheet, TextInput, Text, NativeModules, Pressable, KeyboardAvoidingView } from "react-native"
import { useFocusEffect } from "@react-navigation/native"
import { type MarkedStyles } from "react-native-marked"
import type { UserTheme } from "react-native-marked/dist/typescript/theme/types"
import { KotlinCode, DARK_PALETTE, LIGHT_PALETTE } from "../../components/KotlinCode"
import { useTheme } from "../../context/ThemeContext"
import CustomButton from "../../components/CustomButton"
import PageHeader from "../../components/PageHeader"
import { MarkdownView } from "../../components/ChatMarkdown"
import { databaseManager } from "../../lib/database"
import * as llamaRunner from "../../lib/chat/llamaRunner"
import * as verifier from "../../lib/chat/groundingVerifier"
import { loadChatTuning, trimToCap, type ChatTuning } from "../../lib/chat/chatSettings"
import { ACTIVE_MODEL_SETTING, resolveActiveModel } from "../../lib/chat/activeModel"
import { isEmbedderReady } from "../../lib/chat/embedder"
import { Section } from "../../components/ui/section"
import { SheetModal } from "../../components/ui/sheet-modal"
import { ModalRadioRow } from "../../components/ui/modal-list"
import { useModalShellStyles } from "../../components/ui/modal-shell-styles"
import { ValuePill } from "../../components/ui/value-pill"
import { ModalHeader } from "../../components/ui/modal-header"
import InfoCallout from "../../components/ui/info-callout"
import { TYPE } from "../../lib/type"
import { SPACING } from "../../lib/spacing"

const HISTORY_CATEGORY = "chat"
const HISTORY_KEY = "questionHistory"
const HISTORY_MAX = 20
const TOP_K = 4

type CitationKind = "doc" | "code"
interface DocResult {
    id: string
    source: string
    heading: string
    text: string
    score: number
    expandedText: string
    kind: CitationKind
}

type ChatMode = "generated" | "retrieveOnly" | "verifierFallback"

interface ChatResult {
    answer: string
    mode: ChatMode
    overlap?: number
    rejectedAnswer?: string
    citations: DocResult[]
    stats?: llamaRunner.ChatStats | null
}

const SYSTEM_INSTRUCTIONS =
    "You are a friendly documentation guide for an Android automation app. The excerpts may include user-facing documentation (markdown) or Kotlin source code from the implementation; explain functionality drawn from the code in plain language rather than echoing the code back. Using only the excerpts provided in this conversation, write a detailed, well-structured explanation that answers the user's question.\n\n" +
    "Rules:\n" +
    "- Paraphrase in your own words. Do NOT copy sentences verbatim from the excerpts.\n" +
    "- Aim for 4-10 sentences depending on how much the excerpts cover. Use multiple short paragraphs and bullet lists where they aid clarity.\n" +
    '- Do NOT prefix output with "Answer:" or repeat the question.\n' +
    "- Only use facts that appear in the excerpts. Do not invent features, numbers, button names, or behavior.\n" +
    "- If the excerpts do not answer the question, reply with exactly: NOT_IN_DOCS"

/** Props for BlinkingCursor. */
interface BlinkingCursorProps {
    /** Color of the cursor block, typically `colors.brand`. */
    color: string
}

/** Small blinking caret rendered at the end of a streaming reply. */
const BlinkingCursor: React.FC<BlinkingCursorProps> = ({ color }) => {
    const [visible, setVisible] = useState(true)
    useEffect(() => {
        const id = setInterval(() => setVisible((v) => !v), 500)
        return () => clearInterval(id)
    }, [])
    return <View style={{ width: 6, height: 12, marginLeft: 2, backgroundColor: color, opacity: visible ? 1 : 0, borderRadius: 1 }} />
}

const Chat = () => {
    const { colors, isDark } = useTheme()
    const modalShellStyles = useModalShellStyles()
    const [query, setQuery] = useState("")
    const [result, setResult] = useState<ChatResult | null>(null)
    const [partialAnswer, setPartialAnswer] = useState("")
    const [streamingTokens, setStreamingTokens] = useState(0)
    const [streamingTokensPerSec, setStreamingTokensPerSec] = useState(0)
    const [isSearching, setIsSearching] = useState(false)
    const [isAborting, setIsAborting] = useState(false)
    const [searched, setSearched] = useState(false)
    const [history, setHistory] = useState<string[]>([])
    const [tuning, setTuning] = useState<ChatTuning | null>(null)
    const [activeModelFilename, setActiveModelFilename] = useState<string | null | undefined>(undefined)
    const [downloadedModels, setDownloadedModels] = useState<string[]>([])
    const [modelPickerOpen, setModelPickerOpen] = useState(false)
    const [embedderReady, setEmbedderReady] = useState<boolean | null>(null)

    const refreshActiveModel = useCallback(async () => {
        const resolved = await resolveActiveModel()
        setActiveModelFilename(resolved?.filename ?? null)
        try {
            const list = await NativeModules.LLMChatModule.listModels()
            setDownloadedModels(Array.isArray(list) ? list.map((m: { filename: string }) => m.filename) : [])
        } catch {
            setDownloadedModels([])
        }
        try {
            setEmbedderReady(await isEmbedderReady())
        } catch {
            setEmbedderReady(false)
        }
    }, [])

    const handleSelectModel = useCallback((filename: string | undefined) => {
        if (!filename) return
        setActiveModelFilename(filename)
        NativeModules.LLMChatModule.setActiveModel(filename)
        databaseManager.saveSetting(ACTIVE_MODEL_SETTING.category, ACTIVE_MODEL_SETTING.key, filename, true).catch(() => undefined)
    }, [])

    useFocusEffect(
        useCallback(() => {
            refreshActiveModel().catch(() => undefined)
        }, [refreshActiveModel])
    )

    useEffect(() => {
        let cancelled = false
        ;(async () => {
            try {
                const stored = await databaseManager.loadSetting(HISTORY_CATEGORY, HISTORY_KEY)
                if (!cancelled && Array.isArray(stored)) setHistory(stored.filter((x): x is string => typeof x === "string"))
            } catch {}
            try {
                const t = await loadChatTuning()
                if (!cancelled) setTuning(t)
            } catch {}
            try {
                const resolved = await resolveActiveModel()
                if (!cancelled) setActiveModelFilename(resolved?.filename ?? null)
            } catch {}
        })()
        return () => {
            cancelled = true
        }
    }, [])

    const handleSearch = useCallback(async () => {
        const q = query.trim()
        if (!q || !tuning) return
        setIsSearching(true)
        setSearched(true)
        setPartialAnswer("")
        setStreamingTokens(0)
        setStreamingTokensPerSec(0)
        setResult(null)
        // Prepend to history, dedupe, cap.
        setHistory((prev) => {
            const next = [q, ...prev.filter((x) => x !== q)].slice(0, HISTORY_MAX)
            databaseManager.saveSetting(HISTORY_CATEGORY, HISTORY_KEY, next, true).catch(() => undefined)
            return next
        })

        try {
            const citations = (await NativeModules.LLMChatModule.searchDocs(q, TOP_K)) as DocResult[]
            console.log(
                "[Chat] retrieval:",
                citations.length,
                "chunks",
                citations.map((c) => `${c.source}#${c.id} (${(c.score * 100).toFixed(0)}%)`)
            )

            if (citations.length === 0) {
                setResult({ answer: "No matching documentation found.", mode: "retrieveOnly", citations: [] })
                return
            }

            // Pick a downloaded GGUF model. If none, retrieve-only fallback.
            const resolvedModel = await resolveActiveModel()
            if (!resolvedModel) {
                setResult({ answer: citations[0].expandedText, mode: "retrieveOnly", citations })
                return
            }

            await llamaRunner.ensureContext(resolvedModel.path, { nCtx: tuning.modelContextWindow })

            // Build prompt: system instructions + per-citation excerpts in the system message; question in the user message.
            const trimmed = citations.map((c) => trimToCap(c.expandedText, tuning.llmCitationCharCap))
            const contextBlock = trimmed.join("\n\n---\n\n")
            const messages = [
                { role: "system" as const, content: `${SYSTEM_INSTRUCTIONS}\n\nExcerpts (separated by ---):\n\n${contextBlock}` },
                { role: "user" as const, content: q },
            ]

            let tokenCount = 0
            let firstTokenMs = 0
            const completion = await llamaRunner.chat(
                {
                    messages,
                    maxTokens: tuning.maxOutputTokens,
                    temperature: 0.35,
                },
                (tok) => {
                    setPartialAnswer((prev) => prev + tok)
                    tokenCount += 1
                    if (firstTokenMs === 0) firstTokenMs = Date.now()
                    const elapsedSec = (Date.now() - firstTokenMs) / 1000
                    setStreamingTokens(tokenCount)
                    if (elapsedSec > 0.25) setStreamingTokensPerSec(tokenCount / elapsedSec)
                }
            )
            const generated = completion.text.trim()
            const stats = completion.stats
            if (stats) {
                console.log(
                    `[Chat] generation: ${stats.tokensPredicted} tok in ${(stats.predictedMs / 1000).toFixed(2)}s ` +
                        `= ${stats.predictedPerSecond.toFixed(2)} tok/s (prefill ${stats.tokensEvaluated} tok @ ${stats.promptPerSecond.toFixed(2)} tok/s)`
                )
            }

            if (!generated || generated.toUpperCase() === "NOT_IN_DOCS") {
                setResult({ answer: citations[0].expandedText, mode: "retrieveOnly", citations })
                return
            }

            const overlap = verifier.overlap(generated, trimmed)
            console.log("[Chat] verifier overlap:", overlap.toFixed(3))
            if (overlap >= verifier.SUMMARY_THRESHOLD) {
                setResult({ answer: generated, mode: "generated", overlap, citations, stats })
            } else {
                setResult({
                    answer: citations[0].expandedText,
                    mode: "verifierFallback",
                    overlap,
                    rejectedAnswer: generated,
                    citations,
                    stats,
                })
            }
        } catch (err) {
            console.log("[Chat] error:", err)
            const msg = err instanceof Error ? err.message : String(err)
            setResult({ answer: `Chat failed: ${msg}`, mode: "retrieveOnly", citations: [] })
        } finally {
            setIsSearching(false)
            setIsAborting(false)
            setPartialAnswer("")
            refreshActiveModel().catch(() => undefined)
        }
    }, [query, tuning])

    const handleStop = useCallback(async () => {
        setIsAborting(true)
        await llamaRunner.stop()
    }, [])

    const handleHistoryTap = useCallback((q: string) => {
        setQuery(q)
    }, [])

    const handleClearHistory = useCallback(() => {
        setHistory([])
        databaseManager.saveSetting(HISTORY_CATEGORY, HISTORY_KEY, [], true).catch(() => undefined)
    }, [])

    const modeLabel = useMemo(() => {
        if (!result) return null
        switch (result.mode) {
            case "generated":
                return `Generated · grounding ${Math.round((result.overlap ?? 0) * 100)}%`
            case "retrieveOnly":
                return "Verbatim from docs (no model)"
            case "verifierFallback":
                return `Verifier rejected generated answer (${Math.round((result.overlap ?? 0) * 100)}% grounding). Showing source instead.`
        }
    }, [result])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: { flex: 1, margin: 10, backgroundColor: colors.bg },
                input: {
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: 6,
                    paddingHorizontal: 10,
                    paddingVertical: 8,
                    color: colors.text,
                    backgroundColor: colors.surface,
                    minHeight: 96,
                    maxHeight: 200,
                    textAlignVertical: "top",
                },
                answerCard: {
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: 6,
                    padding: 12,
                    marginBottom: 12,
                    backgroundColor: colors.surface,
                },
                modeLabel: { ...TYPE.caption, fontSize: 11, color: colors.textMuted, marginTop: 8, fontStyle: "italic" },
                resultCard: {
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: 6,
                    padding: 10,
                    marginBottom: 8,
                    backgroundColor: colors.surface,
                },
                resultHeading: { ...TYPE.body, fontWeight: "600", color: colors.text, marginBottom: 4 },
                resultMeta: { ...TYPE.caption, fontSize: 11, color: colors.textMuted, marginBottom: 6 },
                codeBlock: {
                    fontFamily: "monospace",
                    fontSize: 12,
                    color: colors.text,
                    backgroundColor: colors.surfaceRaised,
                    borderRadius: 6,
                    padding: 8,
                },
                emptyText: { ...TYPE.body, color: colors.textMuted, textAlign: "center", marginTop: 20, paddingHorizontal: 20 },
                modelStatus: { ...TYPE.caption, color: colors.text },
                modelStatusInactive: { ...TYPE.caption, color: colors.textMuted, fontStyle: "italic" },
                modelSelectorRow: { flexDirection: "row" as const, alignItems: "center" as const, gap: 8 },
                embedderCtaTitle: { ...TYPE.body, color: colors.text, fontWeight: "600" as const, marginBottom: 4 },
                embedderCtaBody: { ...TYPE.body, color: colors.textMuted, fontSize: 13, lineHeight: 18 },
                modelSelectorControl: { flex: 1 },
                historyClear: { ...TYPE.caption, fontSize: 11, color: colors.textMuted, textDecorationLine: "underline" },
                historyChip: {
                    borderWidth: 1,
                    borderColor: colors.borderHair,
                    borderRadius: 14,
                    paddingHorizontal: 10,
                    paddingVertical: 6,
                    marginRight: 6,
                    marginBottom: 6,
                    backgroundColor: colors.surface,
                    overflow: "hidden",
                },
                historyChipText: { ...TYPE.caption, color: colors.text },
                historyChipRow: { flexDirection: "row", flexWrap: "wrap" },
                streamingNotice: { ...TYPE.caption, fontSize: 11, color: colors.textMuted, marginBottom: 4, fontStyle: "italic" },
            }),
        [colors]
    )

    const markedTheme = useMemo<UserTheme>(
        () => ({
            colors: {
                text: colors.text,
                code: colors.text,
                link: colors.brand,
                border: colors.borderHair,
            },
        }),
        [colors]
    )

    const markedStyles = useMemo<MarkedStyles>(
        () => ({
            text: { color: colors.text, fontSize: 15, lineHeight: 22 },
            paragraph: { marginTop: 0, marginBottom: 8, paddingHorizontal: 0 },
            strong: { color: colors.text, fontWeight: "700" },
            em: { color: colors.text, fontStyle: "italic" },
            link: { color: colors.brand, textDecorationLine: "underline" },
            h1: { color: colors.text, fontWeight: "700", fontSize: 20, marginTop: 10, marginBottom: 6 },
            h2: { color: colors.text, fontWeight: "700", fontSize: 17, marginTop: 10, marginBottom: 6 },
            h3: { color: colors.text, fontWeight: "600", fontSize: 15, marginTop: 8, marginBottom: 4 },
            h4: { color: colors.text, fontWeight: "600", fontSize: 14, marginTop: 6, marginBottom: 4 },
            h5: { color: colors.text, fontWeight: "600", fontSize: 13, marginTop: 4, marginBottom: 2 },
            h6: { color: colors.text, fontWeight: "600", fontSize: 13, marginTop: 4, marginBottom: 2 },
            codespan: { color: colors.text, backgroundColor: colors.surfaceRaised, borderRadius: 4, paddingHorizontal: 4, fontFamily: "monospace" },
            code: { backgroundColor: colors.surfaceRaised, borderRadius: 6, padding: 8, marginVertical: 4 },
            blockquote: {
                backgroundColor: colors.surfaceRaised,
                borderLeftColor: colors.borderHair,
                borderLeftWidth: 3,
                paddingLeft: 8,
                paddingVertical: 4,
                marginVertical: 4,
            },
            list: { marginBottom: 8 },
            li: { color: colors.text, fontSize: 15, lineHeight: 22 },
            hr: { backgroundColor: colors.borderHair, height: 1, marginVertical: 8 },
            table: { borderColor: colors.borderHair, borderWidth: 1, borderRadius: 4, marginVertical: 6 },
            tableRow: { borderColor: colors.borderHair },
            tableCell: { padding: 6 },
        }),
        [colors]
    )

    /** Smaller-font variant of [markedStyles] used for the doc-citation cards under "Sources". Mirrors the
     *  answer-card styles but with reduced font sizes and tighter line heights so each citation card stays
     *  compact. Tune the numbers here in isolation from the main answer card. */
    const citationMarkedStyles = useMemo<MarkedStyles>(
        () => ({
            ...markedStyles,
            text: { color: colors.text, fontSize: 12, lineHeight: 18 },
            li: { color: colors.text, fontSize: 12, lineHeight: 18 },
            h1: { color: colors.text, fontWeight: "700", fontSize: 16, marginTop: 8, marginBottom: 4 },
            h2: { color: colors.text, fontWeight: "700", fontSize: 14, marginTop: 8, marginBottom: 4 },
            h3: { color: colors.text, fontWeight: "600", fontSize: 13, marginTop: 6, marginBottom: 3 },
            h4: { color: colors.text, fontWeight: "600", fontSize: 12, marginTop: 4, marginBottom: 2 },
            h5: { color: colors.text, fontWeight: "600", fontSize: 12, marginTop: 4, marginBottom: 2 },
            h6: { color: colors.text, fontWeight: "600", fontSize: 12, marginTop: 4, marginBottom: 2 },
        }),
        [colors, markedStyles]
    )

    return (
        <KeyboardAvoidingView style={styles.root} behavior="padding">
            <PageHeader title="Ask the Docs" />
            <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ flexGrow: 1, paddingBottom: SPACING.xl }}>
                <InfoCallout title="About" collapsible={false} style={{ marginVertical: SPACING.md }}>
                    <Text style={[TYPE.caption, { color: colors.textMuted }]}>
                        Ask the Docs answers questions about this app by searching its bundled documentation and source code, all on-device.{"\n\n"}Responses are grounded in README.md,
                        HOW_IT_WORKS.md, in-app option descriptions, and the app's Kotlin source code. Fully offline.
                    </Text>
                </InfoCallout>
                {activeModelFilename !== undefined && (
                    <Section label="Model">
                        <View style={{ padding: SPACING.md }}>
                            {downloadedModels.length > 0 ? (
                                <View style={styles.modelSelectorRow}>
                                    <Text style={styles.modelStatus}>Model:</Text>
                                    <View style={styles.modelSelectorControl}>
                                        <Pressable
                                            onPress={() => setModelPickerOpen(true)}
                                            android_ripple={{ color: colors.ripple, foreground: true }}
                                            accessibilityRole="button"
                                            style={{ alignSelf: "flex-start" }}
                                        >
                                            <ValuePill label={activeModelFilename ?? "Select a model"} />
                                        </Pressable>
                                    </View>
                                </View>
                            ) : (
                                <Text style={styles.modelStatusInactive}>No model · retrieve-only mode</Text>
                            )}
                        </View>
                    </Section>
                )}

                {embedderReady === false ? (
                    <Section label="Setup">
                        <View style={{ padding: SPACING.md }}>
                            <Text style={styles.embedderCtaTitle}>Engine not installed</Text>
                            <Text style={styles.embedderCtaBody}>
                                Ask the Docs needs to download a small embedder (~22 MB) before it can search the documentation. Open LLM Settings to start the download.
                            </Text>
                        </View>
                    </Section>
                ) : (
                    <Section label="Ask a Question">
                        <View style={{ padding: SPACING.md, gap: SPACING.sm }}>
                            <TextInput
                                style={styles.input}
                                value={query}
                                onChangeText={setQuery}
                                placeholder="Ask a question about the app..."
                                placeholderTextColor={colors.textMuted}
                                multiline
                                textAlignVertical="top"
                                editable={!isSearching}
                            />
                            {isSearching ? (
                                <CustomButton variant="destructive" onPress={handleStop} disabled={isAborting} isLoading={true}>
                                    {isAborting ? "Stopping..." : "Stop"}
                                </CustomButton>
                            ) : (
                                <CustomButton variant="primary" onPress={handleSearch} disabled={query.trim().length === 0 || !tuning}>
                                    Ask
                                </CustomButton>
                            )}
                        </View>
                    </Section>
                )}

                {history.length > 0 && (
                    <Section
                        label="Recent Questions"
                        labelRight={
                            <Pressable onPress={handleClearHistory} android_ripple={{ color: colors.ripple, foreground: true }} hitSlop={8}>
                                <Text style={styles.historyClear}>Clear</Text>
                            </Pressable>
                        }
                    >
                        <View style={{ padding: SPACING.md }}>
                            <View style={styles.historyChipRow}>
                                {history.map((q) => (
                                    <Pressable key={q} style={styles.historyChip} onPress={() => handleHistoryTap(q)} android_ripple={{ color: colors.ripple, foreground: true }}>
                                        <Text style={styles.historyChipText} numberOfLines={1}>
                                            {q}
                                        </Text>
                                    </Pressable>
                                ))}
                            </View>
                        </View>
                    </Section>
                )}

                {isSearching && partialAnswer.length > 0 && (
                    <View style={styles.answerCard}>
                        <Text style={styles.streamingNotice}>
                            Generating… {streamingTokens} tok{streamingTokensPerSec > 0 ? ` · ${streamingTokensPerSec.toFixed(1)} tok/s` : ""}
                        </Text>
                        <MarkdownView theme={markedTheme} mdStyles={markedStyles}>
                            {partialAnswer}
                        </MarkdownView>
                        <View style={{ flexDirection: "row", alignItems: "center", marginTop: -8 }}>
                            <BlinkingCursor color={colors.brand} />
                        </View>
                    </View>
                )}

                {result && (
                    <>
                        <View style={styles.answerCard}>
                            <MarkdownView theme={markedTheme} mdStyles={markedStyles}>
                                {result.answer}
                            </MarkdownView>
                            {modeLabel && <Text style={styles.modeLabel}>{modeLabel}</Text>}
                            {result.stats && (
                                <Text style={styles.modeLabel}>
                                    {`${result.stats.tokensPredicted} tok in ${(result.stats.predictedMs / 1000).toFixed(2)}s · ${result.stats.predictedPerSecond.toFixed(2)} tok/s · prefill ${result.stats.tokensEvaluated} tok @ ${result.stats.promptPerSecond.toFixed(2)} tok/s`}
                                </Text>
                            )}
                        </View>
                        {result.citations.length > 0 && (
                            <Section label="Sources">
                                <View style={{ padding: SPACING.md, gap: SPACING.sm }}>
                                    {result.citations.map((r) => (
                                        <View key={r.id} style={styles.resultCard}>
                                            <Text style={styles.resultHeading}>{citationHeading(r)}</Text>
                                            <Text style={styles.resultMeta}>{`${r.source} · similarity ${(r.score * 100).toFixed(0)}%`}</Text>
                                            {r.kind === "code" ? (
                                                <View style={styles.codeBlock}>
                                                    <KotlinCode text={r.text} palette={isDark ? DARK_PALETTE : LIGHT_PALETTE} style={{ fontSize: 10, lineHeight: 18 }} />
                                                </View>
                                            ) : (
                                                <MarkdownView theme={markedTheme} mdStyles={citationMarkedStyles}>
                                                    {r.text}
                                                </MarkdownView>
                                            )}
                                        </View>
                                    ))}
                                </View>
                            </Section>
                        )}
                    </>
                )}
                {searched && !isSearching && !result && <Text style={styles.emptyText}>No matching documentation found.</Text>}
            </ScrollView>
            <SheetModal
                visible={modelPickerOpen}
                onRequestClose={() => setModelPickerOpen(false)}
                header={<ModalHeader title="DOWNLOADED MODELS" onClose={() => setModelPickerOpen(false)} />}
                footer={null}
            >
                <View style={modalShellStyles.modalBodyList}>
                    {downloadedModels.map((f) => (
                        <ModalRadioRow
                            key={f}
                            label={f}
                            selected={f === activeModelFilename}
                            onPress={() => {
                                handleSelectModel(f)
                                setModelPickerOpen(false)
                            }}
                        />
                    ))}
                </View>
            </SheetModal>
        </KeyboardAvoidingView>
    )
}

/** Code citations show as `Racing.kt::findSuitableRace`; doc citations keep their hierarchical heading. */
function citationHeading(r: DocResult): string {
    if (r.kind !== "code") return r.heading
    const lastSep = r.heading.lastIndexOf(" › ")
    const member = lastSep >= 0 ? r.heading.slice(lastSep + 3) : r.heading
    return `${r.source}::${member}`
}

export default Chat
