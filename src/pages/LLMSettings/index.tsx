import { useCallback, useContext, useEffect, useMemo, useState } from "react"
import { View, ScrollView, StyleSheet, Text, TextInput, NativeModules, NativeEventEmitter, Alert, Linking, Pressable } from "react-native"
import { Check, Trash2 } from "lucide-react-native"
import { useTheme } from "../../context/ThemeContext"
import { BotStateContext } from "../../context/BotStateContext"
import CustomButton from "../../components/CustomButton"
import CustomCheckbox from "../../components/CustomCheckbox"
import CustomSlider from "../../components/CustomSlider"
import PageHeader from "../../components/PageHeader"
import WarningContainer from "../../components/WarningContainer"
import InfoContainer from "../../components/InfoContainer"
import { databaseManager } from "../../lib/database"
import { DEFAULTS as TUNING_DEFAULTS, saveTuning } from "../../lib/chat/chatSettings"
import { ACTIVE_MODEL_SETTING } from "../../lib/chat/activeModel"
import { EMBEDDER_SHA256, EMBEDDER_SIZE_BYTES, EMBEDDER_URL, isEmbedderReady } from "../../lib/chat/embedder"
import {
    accelerationTier,
    accelerationTierLabel,
    type DeviceCapabilities,
    fetchModelSizeBytes,
    formatBytes,
    loadDeviceCapabilities,
    PRESET_RAM_REQUIREMENTS_BYTES,
    presetFitsRam,
    recommendedPreset,
    RUNTIME_RAM_OVERHEAD_FACTOR,
} from "../../lib/chat/deviceCapabilities"

const MODEL_URL_SETTING = { category: "chat", key: "modelUrl" } as const
/**
 * Hugging Face access token persistence key. Lives under the "chat" category, not BotStateContext, so it is
 * NOT included in settings exports - a token is a user-specific secret and must never leak into a shared JSON.
 */
const HF_TOKEN_SETTING = { category: "chat", key: "hfToken" } as const
const MAX_OUTPUT_TOKENS_SETTING = { category: "chat", key: "maxOutputTokens" } as const
const CITATION_CHAR_CAP_SETTING = { category: "chat", key: "llmCitationCharCap" } as const
const MODEL_CONTEXT_WINDOW_SETTING = { category: "chat", key: "modelContextWindow" } as const

/** Sentinel `url` for the Custom preset card. Persisted as the user's `modelUrl` when they pick "Custom" but
 *  haven't yet pasted a real URL - the URL/token TextInputs become visible so they can fill them in. */
const CUSTOM_URL_SENTINEL = "__custom__"

/** Known Qwen 2.5 Instruct GGUF models for llama.rn. All Q4_K_M quants - the size/quality sweet spot. Sizes
 *  verified against the official Qwen Hugging Face repos. These repos are public (no HF token required). */
const MODEL_PRESETS: Array<{ label: string; detail: string; url: string }> = [
    {
        label: "Qwen 2.5 0.5B Instruct (491 MB, fast, weak summaries)",
        detail: "Smallest option. Runs on almost any phone, but paraphrasing quality is limited.",
        url: "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
    },
    {
        label: "Qwen 2.5 1.5B Instruct (1.12 GB, balanced)",
        detail: "Notably better summaries than 0.5B. Needs ~2 GB free RAM; runs comfortably on most phones.",
        url: "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
    },
    {
        label: "Qwen 2.5 3B Instruct (2.1 GB, highest quality)",
        detail: "Best summarization quality at this size tier. Needs ~4 GB free RAM and a recent phone for acceptable speed.",
        url: "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
    },
    {
        label: "Custom",
        detail: "Paste your own .gguf URL (Hugging Face or any HTTPS host).",
        url: CUSTOM_URL_SENTINEL,
    },
]

const DEFAULT_MODEL_URL = MODEL_PRESETS[0].url

/**
 * Derive the local model filename the downloader will use for [url]. Mirrors `filenameFromUrl` in
 * [LLMChatModule.kt] so the UI can check whether a preset is already downloaded before offering the button.
 */
const filenameFromUrl = (url: string): string => {
    const noQuery = url.split("?")[0].split("#")[0]
    const last = noQuery.substring(noQuery.lastIndexOf("/") + 1).trim()
    const isModel = last.toLowerCase().endsWith(".gguf") || last.toLowerCase().endsWith(".task")
    return last.length > 0 && isModel ? last : "chat-model.gguf"
}

interface DownloadState {
    /** Discriminator emitted by `LLMChatModule.emitDownloadState`; routes the event to the chat-model UI or the embedder UI. */
    kind?: "chat" | "embedder"
    status: "pending" | "running" | "paused" | "complete" | "failed" | "error"
    bytesDownloaded: number
    bytesTotal: number
    error?: string
}

interface DownloadedModel {
    filename: string
    sizeBytes: number
    lastModifiedMillis: number
}

/**
 * LLM Settings page.
 *
 * Manages the on-device documentation chatbot's generative model: download/cancel/delete `.gguf` files (used
 * by llama.rn) and pick which downloaded model is active. Retrieve-only search is always available regardless
 * of what happens here.
 */
const LLMSettings = () => {
    const { colors } = useTheme()
    const bsc = useContext(BotStateContext)
    const enableAskTheDocs = bsc.settings.chat?.enableAskTheDocs ?? false
    const [downloadState, setDownloadState] = useState<DownloadState | null>(null)
    const [embedderState, setEmbedderState] = useState<DownloadState | null>(null)
    const [embedderReady, setEmbedderReady] = useState(false)
    const [deviceCaps, setDeviceCaps] = useState<DeviceCapabilities | null>(null)
    const [hfToken, setHfToken] = useState("")
    const [modelUrl, setModelUrl] = useState(DEFAULT_MODEL_URL)
    const [downloadedModels, setDownloadedModels] = useState<DownloadedModel[]>([])
    const [activeModelFilename, setActiveModelFilename] = useState<string | null>(null)
    const [maxOutputTokens, setMaxOutputTokens] = useState<number>(TUNING_DEFAULTS.maxOutputTokens)
    const [llmCitationCharCap, setLlmCitationCharCap] = useState<number>(TUNING_DEFAULTS.llmCitationCharCap)
    const [modelContextWindow, setModelContextWindow] = useState<number>(TUNING_DEFAULTS.modelContextWindow)

    const refreshModels = useCallback(async () => {
        try {
            const list: DownloadedModel[] = await NativeModules.LLMChatModule.listModels()
            setDownloadedModels(list)
        } catch {
            setDownloadedModels([])
        }
    }, [])

    // Load persisted model URL + HF token + active model selection on mount. Token lives outside BotStateContext so
    // it is never exported.
    useEffect(() => {
        let cancelled = false
        ;(async () => {
            try {
                const [url, token, active, maxOut, citationCap, ctxWindow] = await Promise.all([
                    databaseManager.loadSetting(MODEL_URL_SETTING.category, MODEL_URL_SETTING.key),
                    databaseManager.loadSetting(HF_TOKEN_SETTING.category, HF_TOKEN_SETTING.key),
                    databaseManager.loadSetting(ACTIVE_MODEL_SETTING.category, ACTIVE_MODEL_SETTING.key),
                    databaseManager.loadSetting(MAX_OUTPUT_TOKENS_SETTING.category, MAX_OUTPUT_TOKENS_SETTING.key),
                    databaseManager.loadSetting(CITATION_CHAR_CAP_SETTING.category, CITATION_CHAR_CAP_SETTING.key),
                    databaseManager.loadSetting(MODEL_CONTEXT_WINDOW_SETTING.category, MODEL_CONTEXT_WINDOW_SETTING.key),
                ])
                if (cancelled) return
                if (typeof url === "string" && url.length > 0) setModelUrl(url)
                if (typeof token === "string" && token.length > 0) setHfToken(token)
                if (typeof active === "string" && active.length > 0) {
                    setActiveModelFilename(active)
                    NativeModules.LLMChatModule.setActiveModel(active)
                }
                if (!cancelled) {
                    if (typeof maxOut === "number") setMaxOutputTokens(maxOut)
                    if (typeof citationCap === "number") setLlmCitationCharCap(citationCap)
                    if (typeof ctxWindow === "number") setModelContextWindow(ctxWindow)
                }
            } catch {
                // First run or DB not initialized - keep defaults.
            }
            refreshModels()
        })()
        return () => {
            cancelled = true
        }
    }, [refreshModels])

    const handleSelectActiveModel = useCallback((filename: string) => {
        setActiveModelFilename(filename)
        NativeModules.LLMChatModule.setActiveModel(filename)
        databaseManager.saveSetting(ACTIVE_MODEL_SETTING.category, ACTIVE_MODEL_SETTING.key, filename, true).catch(() => undefined)
    }, [])

    const handleDeleteModelFile = useCallback(
        (filename: string) => {
            Alert.alert("Delete this model?", `Removes ${filename} (~${((downloadedModels.find((m) => m.filename === filename)?.sizeBytes ?? 0) / 1024 / 1024) | 0} MB) from disk.`, [
                { text: "Cancel", style: "cancel" },
                {
                    text: "Delete",
                    style: "destructive",
                    onPress: async () => {
                        await NativeModules.LLMChatModule.deleteModelFile(filename)
                        if (activeModelFilename === filename) {
                            setActiveModelFilename(null)
                            NativeModules.LLMChatModule.setActiveModel("")
                            databaseManager.saveSetting(ACTIVE_MODEL_SETTING.category, ACTIVE_MODEL_SETTING.key, "", true).catch(() => undefined)
                        }
                        await refreshModels()
                    },
                },
            ])
        },
        [activeModelFilename, downloadedModels, refreshModels]
    )

    /** Commit a tuning value to SQLite. The Chat page reads these values JS-side on each chat call. */
    const commitMaxOutputTokens = useCallback((value: number) => {
        setMaxOutputTokens(value)
        saveTuning("maxOutputTokens", value)
    }, [])

    const commitLlmCitationCharCap = useCallback((value: number) => {
        setLlmCitationCharCap(value)
        saveTuning("llmCitationCharCap", value)
    }, [])

    const commitModelContextWindow = useCallback((value: number) => {
        setModelContextWindow(value)
        saveTuning("modelContextWindow", value)
    }, [])

    const handleResetTuning = useCallback(() => {
        commitMaxOutputTokens(TUNING_DEFAULTS.maxOutputTokens)
        commitLlmCitationCharCap(TUNING_DEFAULTS.llmCitationCharCap)
        commitModelContextWindow(TUNING_DEFAULTS.modelContextWindow)
    }, [commitMaxOutputTokens, commitLlmCitationCharCap, commitModelContextWindow])

    /** Warn when the active model's filename advertises a baked-in KV cache smaller than the requested context window
     *  - only relevant for legacy Gemma `_ekvN.task` files; modern GGUF models advertise their context window
     *  in metadata and llama.rn honors `n_ctx` directly. */
    const ekvCapWarning = useMemo(() => {
        const active = activeModelFilename ?? downloadedModels[0]?.filename
        if (!active) return null
        const match = active.match(/_ekv(\d+)\b/i)
        if (!match) return null
        const ekv = parseInt(match[1], 10)
        return modelContextWindow > ekv ? `Active model is exported with KV cache ${ekv}; values above ${ekv} have no effect for this file.` : null
    }, [activeModelFilename, downloadedModels, modelContextWindow])

    const persistHfToken = useCallback((value: string) => {
        setHfToken(value)
        databaseManager.saveSetting(HF_TOKEN_SETTING.category, HF_TOKEN_SETTING.key, value, true).catch(() => undefined)
    }, [])

    const persistModelUrl = useCallback((url: string) => {
        setModelUrl(url)
        databaseManager.saveSetting(MODEL_URL_SETTING.category, MODEL_URL_SETTING.key, url, true).catch(() => undefined)
    }, [])

    useEffect(() => {
        const emitter = new NativeEventEmitter(NativeModules.LLMChatModule)
        const sub = emitter.addListener("LLMChatModule.DownloadState", (state: DownloadState) => {
            // Route by `kind`; legacy events without the field default to chat-model so older bridges keep working.
            if (state.kind === "embedder") {
                setEmbedderState(state)
                if (state.status === "complete" || state.status === "failed" || state.status === "error") {
                    isEmbedderReady()
                        .then(setEmbedderReady)
                        .catch(() => undefined)
                }
            } else {
                setDownloadState(state)
                if (state.status === "complete" || state.status === "failed" || state.status === "error") {
                    refreshModels()
                }
            }
        })
        return () => sub.remove()
    }, [refreshModels])

    useEffect(() => {
        isEmbedderReady()
            .then(setEmbedderReady)
            .catch(() => undefined)
        loadDeviceCapabilities()
            .then(setDeviceCaps)
            .catch(() => undefined)
    }, [])

    const tier = useMemo(() => accelerationTier(deviceCaps?.cpuFeatures ?? []), [deviceCaps])
    const recommended = useMemo(() => recommendedPreset(deviceCaps), [deviceCaps])

    const handleDownload = useCallback(async () => {
        if (modelUrl === CUSTOM_URL_SENTINEL || modelUrl.trim().length === 0) {
            Alert.alert("No URL specified", "Paste a .gguf URL into the Custom field before downloading.")
            return
        }
        const preset = MODEL_PRESETS.find((p) => p.url === modelUrl && p.url !== CUSTOM_URL_SENTINEL)
        const startDownload = () => {
            const title = preset ? `Download ${preset.label.split(" (")[0]}?` : "Download custom model?"
            const body = preset
                ? `${preset.label}\n\n${preset.detail}\n\nGated on Hugging Face - accept the license on the model page and paste a read-access token below before downloading. Prefer Wi-Fi.`
                : `Downloading from:\n${modelUrl}\n\nGated models require an accepted license and a read-access token. Prefer Wi-Fi.`
            Alert.alert(title, body, [
                { text: "Cancel", style: "cancel" },
                {
                    text: "Download",
                    onPress: async () => {
                        try {
                            NativeModules.LLMChatModule.setAuthToken(hfToken.trim())
                            await NativeModules.LLMChatModule.downloadModel(modelUrl.trim() || DEFAULT_MODEL_URL)
                        } catch (e: any) {
                            Alert.alert("Download failed to start", e?.message ?? "Unknown error")
                        }
                    },
                },
            ])
        }
        const warnTooLarge = (message: string) => {
            Alert.alert("Device may not have enough RAM", message, [
                { text: "Cancel", style: "cancel" },
                { text: "Download anyway", style: "destructive", onPress: startDownload },
            ])
        }
        // Pre-download fit check for known presets: compare against the preset's hand-tuned RAM requirement.
        if (!presetFitsRam(deviceCaps, modelUrl)) {
            const required = PRESET_RAM_REQUIREMENTS_BYTES.find((p) => modelUrl.includes(p.urlSubstring))
            const avail = deviceCaps ? formatBytes(deviceCaps.availRamBytes) : "?"
            warnTooLarge(
                required
                    ? `${required.label} typically needs ~${formatBytes(required.requiredAvailRamBytes)} of free RAM, but only ${avail} is available right now. Loading the model may crash. Download anyway?`
                    : `Free RAM is low (${avail}). Loading this model may crash. Download anyway?`
            )
            return
        }
        // Pre-download fit check for custom URLs: HEAD the file to read Content-Length, scale by the runtime
        // overhead factor, and warn if it overshoots free RAM. Falls open on HEAD/Content-Length failures so
        // a server that doesn't support HEAD doesn't block legitimate downloads.
        if (!preset && deviceCaps) {
            const sizeBytes = await fetchModelSizeBytes(modelUrl, hfToken)
            if (sizeBytes && sizeBytes * RUNTIME_RAM_OVERHEAD_FACTOR > deviceCaps.availRamBytes) {
                const estRam = sizeBytes * RUNTIME_RAM_OVERHEAD_FACTOR
                warnTooLarge(
                    `This model is ${formatBytes(sizeBytes)} on disk and typically needs ~${formatBytes(estRam)} of free RAM, but only ${formatBytes(deviceCaps.availRamBytes)} is available right now. Loading the model may crash. Download anyway?`
                )
                return
            }
        }
        startDownload()
    }, [deviceCaps, hfToken, modelUrl])

    const handleCancel = useCallback(async () => {
        await NativeModules.LLMChatModule.cancelDownload()
        setDownloadState(null)
    }, [])

    const handleDownloadEmbedder = useCallback(() => {
        Alert.alert(
            "Download Ask the Docs engine?",
            `Fetches the MiniLM embedding model (~${Math.round(EMBEDDER_SIZE_BYTES / 1024 / 1024)} MB) used to retrieve grounding excerpts. Required before any chat or retrieve-only search works. Prefer Wi-Fi.`,
            [
                { text: "Cancel", style: "cancel" },
                {
                    text: "Download",
                    onPress: async () => {
                        try {
                            await NativeModules.LLMChatModule.downloadEmbedder(EMBEDDER_URL, EMBEDDER_SHA256)
                        } catch (e: any) {
                            Alert.alert("Download failed to start", e?.message ?? "Unknown error")
                        }
                    },
                },
            ]
        )
    }, [])

    const handleDeleteEmbedder = useCallback(() => {
        Alert.alert("Delete the Ask the Docs engine?", `Removes the MiniLM model (~${Math.round(EMBEDDER_SIZE_BYTES / 1024 / 1024)} MB). You can re-download it any time from this page.`, [
            { text: "Cancel", style: "cancel" },
            {
                text: "Delete",
                style: "destructive",
                onPress: async () => {
                    await NativeModules.LLMChatModule.deleteEmbedder()
                    setEmbedderReady(false)
                    setEmbedderState(null)
                },
            },
        ])
    }, [])

    const isEmbedderDownloading = embedderState?.status === "running" || embedderState?.status === "pending" || embedderState?.status === "paused"

    const embedderProgressText = useMemo(() => {
        if (!embedderState) return null
        if (embedderState.status === "complete") return "Engine downloaded."
        if (embedderState.status === "failed" || embedderState.status === "error") return `Engine download failed${embedderState.error ? ` (${embedderState.error})` : ""}.`
        const total = embedderState.bytesTotal
        const done = embedderState.bytesDownloaded
        if (total > 0) {
            const pct = Math.round((done / total) * 100)
            return `Downloading: ${pct}% (${(done / 1024 / 1024).toFixed(1)} / ${(total / 1024 / 1024).toFixed(1)} MB)`
        }
        return "Preparing download..."
    }, [embedderState])

    const handleDelete = useCallback(() => {
        const totalMB = Math.round(downloadedModels.reduce((acc, m) => acc + m.sizeBytes, 0) / 1024 / 1024)
        Alert.alert("Delete every downloaded chat model?", `Frees ~${totalMB} MB across ${downloadedModels.length} file${downloadedModels.length === 1 ? "" : "s"}.`, [
            { text: "Cancel", style: "cancel" },
            {
                text: "Delete",
                style: "destructive",
                onPress: async () => {
                    await NativeModules.LLMChatModule.deleteModel()
                    setActiveModelFilename(null)
                    NativeModules.LLMChatModule.setActiveModel("")
                    databaseManager.saveSetting(ACTIVE_MODEL_SETTING.category, ACTIVE_MODEL_SETTING.key, "", true).catch(() => undefined)
                    await refreshModels()
                },
            },
        ])
    }, [downloadedModels, refreshModels])

    const isDownloading = downloadState?.status === "running" || downloadState?.status === "pending" || downloadState?.status === "paused"

    /** Custom is selected when the user explicitly chose it (sentinel persisted) or when the persisted URL
     *  doesn't match any Qwen preset. The latter case keeps existing custom-URL setups visible after upgrade. */
    const isCustomSelected = useMemo(() => modelUrl === CUSTOM_URL_SENTINEL || !MODEL_PRESETS.some((p) => p.url === modelUrl), [modelUrl])

    const selectedFilename = useMemo(() => (modelUrl === CUSTOM_URL_SENTINEL ? "" : filenameFromUrl(modelUrl)), [modelUrl])
    const selectedAlreadyDownloaded = useMemo(() => downloadedModels.some((m) => m.filename === selectedFilename), [downloadedModels, selectedFilename])

    const progressText = useMemo(() => {
        if (!downloadState) return null
        if (downloadState.status === "complete") return "Download complete."
        if (downloadState.status === "failed" || downloadState.status === "error") return `Download failed${downloadState.error ? ` (${downloadState.error})` : ""}.`
        const total = downloadState.bytesTotal
        const done = downloadState.bytesDownloaded
        if (total > 0) {
            const pct = Math.round((done / total) * 100)
            return `Downloading: ${pct}% (${(done / 1024 / 1024).toFixed(1)} / ${(total / 1024 / 1024).toFixed(1)} MB)`
        }
        return "Preparing download..."
    }, [downloadState])

    const styles = useMemo(
        () =>
            StyleSheet.create({
                root: { flex: 1, margin: 10, backgroundColor: colors.background },
                section: { marginTop: 14 },
                sectionLabel: { fontSize: 13, fontWeight: "600", color: colors.foreground, marginBottom: 6 },
                statusRow: { color: colors.foreground, marginBottom: 4 },
                hint: { fontSize: 11, color: colors.mutedForeground, marginTop: 4 },
                linkRowContainer: { flexDirection: "row" as const, gap: 16, marginTop: 4 },
                linkRow: { paddingVertical: 10 },
                link: { fontSize: 14, color: colors.primary, textDecorationLine: "underline" as const },
                tokenInput: {
                    borderWidth: 1,
                    borderColor: colors.border,
                    borderRadius: 6,
                    paddingHorizontal: 10,
                    paddingVertical: 8,
                    color: colors.foreground,
                    backgroundColor: colors.card,
                    marginTop: 6,
                },
                presetCard: {
                    borderWidth: 1,
                    borderColor: colors.border,
                    borderRadius: 6,
                    paddingHorizontal: 10,
                    paddingVertical: 8,
                    marginTop: 6,
                    backgroundColor: colors.card,
                },
                presetCardSelected: { borderColor: colors.primary, borderWidth: 2 },
                presetLabel: { color: colors.foreground, fontSize: 13, fontWeight: "600" },
                presetDetail: { color: colors.mutedForeground, fontSize: 11, marginTop: 2 },
                modelRow: {
                    flexDirection: "row" as const,
                    alignItems: "center" as const,
                    justifyContent: "space-between" as const,
                    borderWidth: 1,
                    borderColor: colors.border,
                    borderRadius: 6,
                    paddingHorizontal: 10,
                    paddingVertical: 8,
                    marginTop: 6,
                    backgroundColor: colors.card,
                },
                modelRowActive: { borderColor: colors.primary, borderWidth: 2 },
                modelInfo: { flex: 1, marginRight: 8 },
                modelFilename: { color: colors.foreground, fontSize: 13, fontWeight: "600" as const },
                modelMeta: { color: colors.mutedForeground, fontSize: 11, marginTop: 2 },
                modelActions: { flexDirection: "row" as const, gap: 6 },
                modelActionButton: {
                    paddingHorizontal: 10,
                    paddingVertical: 6,
                    borderRadius: 4,
                    borderWidth: 1,
                    borderColor: colors.border,
                },
                modelActionText: { color: colors.foreground, fontSize: 12 },
                modelActionActiveText: { color: colors.primary, fontSize: 12, fontWeight: "600" as const },
                activeBadge: { flexDirection: "row" as const, alignItems: "center" as const, gap: 4, paddingHorizontal: 4 },
                tuningHeader: { flexDirection: "row" as const, alignItems: "center" as const, justifyContent: "space-between" as const },
                warningHint: { fontSize: 11, color: colors.warningBorder ?? colors.foreground, marginTop: 6 },
                buttonRow: { flexDirection: "row", gap: 8, marginTop: 8 },
            }),
        [colors]
    )

    return (
        <View style={styles.root}>
            <PageHeader title="LLM Settings" />
            <ScrollView>
                <InfoContainer>Retrieve-only search always works. The options below add optional natural-language answers backed by an on-device model.</InfoContainer>

                <View style={styles.section}>
                    <CustomCheckbox
                        checked={enableAskTheDocs}
                        onCheckedChange={(checked) => bsc.setSettings({ ...bsc.settings, chat: { ...bsc.settings.chat, enableAskTheDocs: checked } })}
                        label="Enable Ask the Docs feature"
                        description="Show the Ask the Docs page in the navigation drawer and reveal the rest of these LLM options. Off by default."
                        searchId="llm-enable-ask-the-docs"
                    />
                </View>

                {enableAskTheDocs && (
                    <>
                        <View style={styles.section}>
                            <Text style={styles.sectionLabel}>Device Fitness</Text>
                            {deviceCaps ? (
                                <>
                                    <Text style={styles.statusRow}>
                                        RAM: {formatBytes(deviceCaps.totalRamBytes)} ({formatBytes(deviceCaps.availRamBytes)} free) · Acceleration: {accelerationTierLabel(tier)}
                                    </Text>
                                    <Text style={styles.hint}>
                                        {recommended
                                            ? `Recommended preset based on free RAM: ${recommended.label}.`
                                            : "Free RAM is below the threshold for any preset. Generation may crash; consider closing background apps before downloading."}
                                    </Text>
                                </>
                            ) : (
                                <Text style={styles.hint}>Reading device capabilities...</Text>
                            )}
                        </View>

                        <View style={styles.section}>
                            <Text style={styles.sectionLabel}>Ask the Docs Engine</Text>
                            <Text style={styles.hint}>
                                The MiniLM embedder (~{Math.round(EMBEDDER_SIZE_BYTES / 1024 / 1024)} MB) powers documentation retrieval. It is downloaded on demand to keep the APK small; both
                                retrieve-only search and the chat model require it. Hosted on Hugging Face; no token required.
                            </Text>
                            <Text style={styles.statusRow}>{embedderReady ? `✅ Installed (~${Math.round(EMBEDDER_SIZE_BYTES / 1024 / 1024)} MB)` : "❌ Not installed"}</Text>
                            {embedderProgressText && <Text style={styles.hint}>{embedderProgressText}</Text>}
                            <View style={styles.buttonRow}>
                                {!embedderReady && !isEmbedderDownloading && (
                                    <CustomButton variant="primary" onPress={handleDownloadEmbedder}>
                                        Download engine
                                    </CustomButton>
                                )}
                                {isEmbedderDownloading && (
                                    <CustomButton variant="destructive" onPress={handleCancel}>
                                        Cancel
                                    </CustomButton>
                                )}
                                {embedderReady && !isEmbedderDownloading && (
                                    <CustomButton variant="destructive" onPress={handleDeleteEmbedder}>
                                        Delete engine
                                    </CustomButton>
                                )}
                            </View>
                        </View>

                        <View style={styles.section}>
                            <Text style={styles.sectionLabel}>Chat Model (llama.cpp / GGUF)</Text>
                            {downloadedModels.length === 0 && <Text style={styles.statusRow}>Not downloaded</Text>}
                            <>
                                <Text style={styles.hint}>
                                    The Qwen presets are public, no token required. Bigger models summarize better but need more RAM and download time. Pick Custom to paste a different .gguf URL; the
                                    token field will appear if the source is gated.
                                </Text>
                                {MODEL_PRESETS.map((p) => {
                                    const selected = p.url === CUSTOM_URL_SENTINEL ? isCustomSelected : modelUrl === p.url
                                    const onPress =
                                        p.url === CUSTOM_URL_SENTINEL
                                            ? () => {
                                                  if (MODEL_PRESETS.some((q) => q.url !== CUSTOM_URL_SENTINEL && q.url === modelUrl)) {
                                                      persistModelUrl(CUSTOM_URL_SENTINEL)
                                                  }
                                              }
                                            : () => persistModelUrl(p.url)
                                    return (
                                        <Pressable key={p.url} style={[styles.presetCard, selected && styles.presetCardSelected]} onPress={onPress}>
                                            <Text style={styles.presetLabel}>{p.label}</Text>
                                            <Text style={styles.presetDetail}>{p.detail}</Text>
                                        </Pressable>
                                    )
                                })}
                                {isCustomSelected && (
                                    <>
                                        <View style={styles.linkRowContainer}>
                                            {modelUrl !== CUSTOM_URL_SENTINEL && modelUrl.trim().length > 0 && (
                                                <Pressable style={styles.linkRow} onPress={() => Linking.openURL(modelUrl.replace(/\/resolve\/main\/.*$/, ""))}>
                                                    <Text style={styles.link}>Open selected model page</Text>
                                                </Pressable>
                                            )}
                                            <Pressable style={styles.linkRow} onPress={() => Linking.openURL("https://huggingface.co/settings/tokens")}>
                                                <Text style={styles.link}>Create token</Text>
                                            </Pressable>
                                        </View>
                                        <TextInput
                                            style={styles.tokenInput}
                                            value={hfToken}
                                            onChangeText={persistHfToken}
                                            placeholder="hf_... (only for gated repos)"
                                            placeholderTextColor={colors.mutedForeground}
                                            autoCapitalize="none"
                                            autoCorrect={false}
                                        />
                                        <TextInput
                                            style={styles.tokenInput}
                                            value={modelUrl === CUSTOM_URL_SENTINEL ? "" : modelUrl}
                                            onChangeText={persistModelUrl}
                                            placeholder="Model .gguf URL"
                                            placeholderTextColor={colors.mutedForeground}
                                            autoCapitalize="none"
                                            autoCorrect={false}
                                        />
                                    </>
                                )}
                            </>
                            {progressText && <Text style={styles.hint}>{progressText}</Text>}
                            <View style={styles.buttonRow}>
                                {!isDownloading && (
                                    <CustomButton variant="primary" onPress={handleDownload} disabled={selectedAlreadyDownloaded}>
                                        {selectedAlreadyDownloaded ? "Already downloaded" : downloadedModels.length > 0 ? "Download another model" : "Download"}
                                    </CustomButton>
                                )}
                                {isDownloading && (
                                    <CustomButton variant="destructive" onPress={handleCancel}>
                                        Cancel
                                    </CustomButton>
                                )}
                            </View>
                        </View>

                        {downloadedModels.length > 0 && (
                            <View style={styles.section}>
                                <Text style={styles.sectionLabel}>Downloaded Models</Text>
                                <Text style={styles.hint}>Tap Use to switch the active chat model. Keep multiple variants so you can A/B without re-downloading.</Text>
                                {downloadedModels.map((m) => {
                                    const isActive = (activeModelFilename ?? downloadedModels[0]?.filename) === m.filename
                                    return (
                                        <View key={m.filename} style={[styles.modelRow, isActive && styles.modelRowActive]}>
                                            <View style={styles.modelInfo}>
                                                <Text style={styles.modelFilename} numberOfLines={1}>
                                                    {m.filename}
                                                </Text>
                                                <Text style={styles.modelMeta}>{(m.sizeBytes / 1024 / 1024).toFixed(0)} MB</Text>
                                            </View>
                                            <View style={styles.modelActions}>
                                                {isActive ? (
                                                    <View style={styles.activeBadge}>
                                                        <Check size={14} color={colors.primary} />
                                                        <Text style={styles.modelActionActiveText}>Active</Text>
                                                    </View>
                                                ) : (
                                                    <Pressable style={styles.modelActionButton} onPress={() => handleSelectActiveModel(m.filename)}>
                                                        <Text style={styles.modelActionText}>Use</Text>
                                                    </Pressable>
                                                )}
                                                <Pressable
                                                    style={styles.modelActionButton}
                                                    onPress={() => handleDeleteModelFile(m.filename)}
                                                    accessibilityLabel={`Delete ${m.filename}`}
                                                    accessibilityRole="button"
                                                >
                                                    <Trash2 size={14} color={colors.foreground} />
                                                </Pressable>
                                            </View>
                                        </View>
                                    )
                                })}
                            </View>
                        )}

                        <View style={styles.section}>
                            <View style={styles.tuningHeader}>
                                <Text style={styles.sectionLabel}>Generation Tuning</Text>
                                <Pressable onPress={handleResetTuning} style={styles.linkRow}>
                                    <Text style={styles.link}>Reset to defaults</Text>
                                </Pressable>
                            </View>
                            <Text style={styles.hint}>Bigger numbers = longer, slower answers. Changes apply to the next chat call. Engine context window changes reload the loaded model.</Text>
                            <CustomSlider
                                label="Max output tokens"
                                description="Upper bound on answer length. 768 default is enough for 4-10 sentences; 1024+ slows generation noticeably on phones."
                                value={maxOutputTokens}
                                onValueChange={setMaxOutputTokens}
                                onSlidingComplete={commitMaxOutputTokens}
                                min={128}
                                max={2048}
                                step={64}
                            />
                            <CustomSlider
                                label="Context per citation (chars)"
                                description="How much of each retrieved doc section is fed to the LLM. Larger gives the model more to summarize from but eats KV cache budget."
                                value={llmCitationCharCap}
                                onValueChange={setLlmCitationCharCap}
                                onSlidingComplete={commitLlmCitationCharCap}
                                min={500}
                                max={4000}
                                step={100}
                            />
                            <CustomSlider
                                label="Model context window (tokens)"
                                description="Engine KV cache size. 4096 default fits 4 expanded citations + scaffold + 768 output. Raising this requires the model to support it."
                                value={modelContextWindow}
                                onValueChange={setModelContextWindow}
                                onSlidingComplete={commitModelContextWindow}
                                min={2048}
                                max={16384}
                                step={1024}
                            />
                            {ekvCapWarning && <Text style={styles.warningHint}>{ekvCapWarning}</Text>}
                        </View>

                        <WarningContainer>
                            Generated answers may occasionally be wrong or phrased imprecisely. A verifier guards against clear hallucinations by falling back to showing the source text verbatim, but
                            always cross-check important answers against the full docs.
                        </WarningContainer>
                    </>
                )}
            </ScrollView>
        </View>
    )
}

export default LLMSettings
