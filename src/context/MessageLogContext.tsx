import { createContext, useCallback, useMemo, useState } from "react"

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
 * Context value interface for the MessageLog provider.
 * Exposes the message log array and methods to update it.
 */
export interface MessageLogProviderProps {
    /** The array of all message log entries. */
    messageLog: MessageLogEntry[]
    /** Direct setter for the entire message log array. */
    setMessageLog: React.Dispatch<React.SetStateAction<MessageLogEntry[]>>
    /** Appends a new message entry to the log. */
    addMessageToLog: (id: number, message: string) => void
}

export const MessageLogContext = createContext<MessageLogProviderProps>({} as MessageLogProviderProps)

/**
 * Provider component for the MessageLog context.
 * Manages the message log entries and provides methods to add new messages.
 * @param children The child components to render within the provider.
 * @returns The message log context provider.
 */
export const MessageLogProvider = ({ children }: any): React.ReactElement => {
    const [messageLog, setMessageLog] = useState<MessageLogEntry[]>([])

    /**
     * Add to the message log while keeping track of the sequential message IDs to prevent duplication.
     * @param id The sequential message ID from the bot service.
     * @param message The text content of the log message.
     */
    const addMessageToLog = useCallback((id: number, message: string) => {
        setMessageLog((prev) => [...prev, { id, message }])
    }, [])

    // Memoize the provider value to prevent cascading re-renders.
    const providerValues = useMemo<MessageLogProviderProps>(
        () => ({
            messageLog,
            setMessageLog,
            addMessageToLog,
        }),
        [messageLog, addMessageToLog]
    )

    return <MessageLogContext.Provider value={providerValues}>{children}</MessageLogContext.Provider>
}
