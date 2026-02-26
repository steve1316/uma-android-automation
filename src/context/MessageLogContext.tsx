import { createContext, useCallback, useMemo, useState } from "react"

export interface MessageLogEntry {
    id: number
    message: string
}

export interface MessageLogProviderProps {
    messageLog: MessageLogEntry[]
    setMessageLog: React.Dispatch<React.SetStateAction<MessageLogEntry[]>>
    addMessageToLog: (id: number, message: string) => void
}

export const MessageLogContext = createContext<MessageLogProviderProps>({} as MessageLogProviderProps)

// https://stackoverflow.com/a/60130448 and https://stackoverflow.com/a/60198351
export const MessageLogProvider = ({ children }: any): React.ReactElement => {
    const [messageLog, setMessageLog] = useState<MessageLogEntry[]>([])

    // Add to the message log while keeping track of the sequential message IDs to prevent duplication.
    const addMessageToLog = useCallback((id: number, message: string) => {
        setMessageLog((prev) => [...prev, { id, message }])
    }, [])

    // Memoize the provider value to prevent cascading re-renders.
    const providerValues = useMemo<MessageLogProviderProps>(() => ({
        messageLog,
        setMessageLog,
        addMessageToLog,
    }), [messageLog, addMessageToLog])

    return <MessageLogContext.Provider value={providerValues}>{children}</MessageLogContext.Provider>
}
