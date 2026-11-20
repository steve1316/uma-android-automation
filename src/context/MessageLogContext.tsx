import { createContext, useCallback, useEffect, useMemo, useRef, useState } from "react"

/**
 * Represents a single entry in the message log.
 */
export interface MessageLogEntry {
    /** The sequential message ID from the bot service. */
    id: number
    /** The text content of the log message. */
    message: string
}

/**
 * Data slice of the message log context. Holds the array itself, which gets a new identity on
 * every log push. Subscribe here only if you actually read `messageLog`.
 */
export interface MessageLogDataProps {
    /** The array of all message log entries. */
    messageLog: MessageLogEntry[]
}

/**
 * Dispatch slice of the message log context. Holds identity-stable setters that never change
 * after the first render, so subscribers do not re-commit when the log array changes.
 */
export interface MessageLogDispatchProps {
    /** Replaces the entire message log array. */
    setMessageLog: (log: MessageLogEntry[]) => void
    /** Appends a new message entry to the log. */
    addMessageToLog: (id: number, message: string) => void
}

export const MessageLogDataContext = createContext<MessageLogDataProps>({} as MessageLogDataProps)
export const MessageLogDispatchContext = createContext<MessageLogDispatchProps>({} as MessageLogDispatchProps)

/** Most entries kept on screen, including the settings block and the hidden-lines marker. The full log still goes to the saved log file. */
export const MAX_LOG_ENTRIES = 10000

/** How long new lines are collected before they are added to the log in one update. */
const LOG_FLUSH_MS = 300

/** How many leading entries to search for the end of the run's settings block. */
const SETTINGS_SCAN_LIMIT = 500

/** The line the bot writes above the run's settings block. */
const SETTINGS_HEADER = "[SETTINGS] Current Bot Configuration:"

/** The divider the bot writes above and below the settings list. */
const SETTINGS_DIVIDER = "====================================="

/**
 * Counts the entries at the top of the log that make up the run's settings block, so trimming keeps them.
 * @param log The log entries, oldest first.
 * @returns How many leading entries to keep, or 0 if no finished settings block is found.
 */
const countSettingsEntries = (log: MessageLogEntry[]): number => {
    const limit = Math.min(log.length, SETTINGS_SCAN_LIMIT)
    let inBlock = false
    let dividers = 0
    for (let i = 0; i < limit; i++) {
        const message = log[i].message
        if (!inBlock) {
            inBlock = message.includes(SETTINGS_HEADER)
        } else if (message.includes(SETTINGS_DIVIDER) && ++dividers === 2) {
            return i + 1
        }
    }
    return 0
}

/**
 * Trims the log to `MAX_LOG_ENTRIES`. The settings block stays at the top, and one marker line in its place says how many lines were hidden.
 * The marker borrows the timestamp of the first kept line and an ID half below it, so the viewer's sort puts it right before that line.
 * Real IDs are whole numbers, so the old marker is found by its fractional ID and replaced on every trim.
 * @param entries The current log plus the new batch, oldest first. It may hold a marker from an earlier trim.
 * @param total How many lines arrived since the log was last cleared.
 * @returns The trimmed log.
 */
const trimMessageLog = (entries: MessageLogEntry[], total: number): MessageLogEntry[] => {
    const log = entries.filter((entry) => Number.isInteger(entry.id))
    if (log.length <= MAX_LOG_ENTRIES && total <= log.length) return log

    const head = countSettingsEntries(log)
    const tail = log.slice(Math.max(head, log.length - (MAX_LOG_ENTRIES - head - 1)))
    const hidden = total - head - tail.length
    const timestamp = tail[0]?.message.match(/^\s*(\d{2}:\d{2}:\d{2}\.\d{3})/)?.[1] ?? ""
    const marker = {
        id: (tail[0]?.id ?? 0) - 0.5,
        message: `\n${timestamp} ··· ${hidden.toLocaleString()} earlier lines hidden to keep the log fast. The saved log file has all of them. ···`,
    }
    return [...log.slice(0, head), marker, ...tail]
}

/**
 * Provider component for the message log contexts. Splits the log array and the setters into
 * two separate contexts so high-frequency log pushes from the Kotlin bot service do not
 * re-render every consumer. Only the viewer that actually reads `messageLog` subscribes to
 * the data context. Pages that just clear or append (Home, useBootstrap) subscribe to the
 * stable dispatch context and never re-commit on log churn.
 *
 * @param children The child components to render within the provider.
 * @returns The nested data + dispatch context providers.
 */
export const MessageLogProvider = ({ children }: any): React.ReactElement => {
    const [messageLog, setMessageLog] = useState<MessageLogEntry[]>([])
    const pendingRef = useRef<MessageLogEntry[]>([])
    const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
    // Lines added since the last clear, so the marker can say how many were hidden. Counted outside the state updater so a replayed updater cannot double it.
    const totalRef = useRef(0)

    useEffect(
        () => () => {
            if (flushTimerRef.current !== null) clearTimeout(flushTimerRef.current)
        },
        []
    )

    /** Adds the collected lines to the log in one update, trimmed by `trimMessageLog`. */
    const flushPending = useCallback(() => {
        flushTimerRef.current = null
        const batch = pendingRef.current
        pendingRef.current = []
        if (batch.length === 0) return
        totalRef.current += batch.length
        const total = totalRef.current
        setMessageLog((prev) => trimMessageLog(prev.concat(batch), total))
    }, [])

    /**
     * Add to the message log while keeping track of the sequential message IDs to prevent duplication.
     * Lines are collected for `LOG_FLUSH_MS` and added together, so a burst of lines copies the log once instead of once per line.
     * @param id The sequential message ID from the bot service.
     * @param message The text content of the log message.
     */
    const addMessageToLog = useCallback(
        (id: number, message: string) => {
            pendingRef.current.push({ id, message })
            if (flushTimerRef.current === null) flushTimerRef.current = setTimeout(flushPending, LOG_FLUSH_MS)
        },
        [flushPending]
    )

    /** Replaces the whole log. Lines still waiting for the next flush are dropped so they do not show up after a clear. */
    const replaceMessageLog = useCallback((value: MessageLogEntry[]) => {
        pendingRef.current = []
        totalRef.current = value.length
        setMessageLog(value)
    }, [])

    const dataValue = useMemo<MessageLogDataProps>(() => ({ messageLog }), [messageLog])
    const dispatchValue = useMemo<MessageLogDispatchProps>(() => ({ setMessageLog: replaceMessageLog, addMessageToLog }), [replaceMessageLog, addMessageToLog])

    return (
        <MessageLogDataContext.Provider value={dataValue}>
            <MessageLogDispatchContext.Provider value={dispatchValue}>{children}</MessageLogDispatchContext.Provider>
        </MessageLogDataContext.Provider>
    )
}
