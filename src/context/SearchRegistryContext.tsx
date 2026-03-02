import { createContext, useContext, useState, ReactNode, useCallback, useMemo } from "react"
import { startTiming } from "../lib/performanceLogger"
import searchConfig from "../data/searchConfig"

export interface SearchOption {
    /** The unique identifier for this item. */
    id: string
    /** The searchable title. */
    title: string
    /** The searchable description. */
    description: string
    /** The target route name to navigate to. */
    page: string
    /** The ID of the parent item, if any. */
    parentId?: string
}

interface SearchContextType {
    /** The global registry of all searchable items. */
    searchIndex: Record<string, SearchOption>
    /** Function to register a new searchable item. */
    registerItem: (item: SearchOption) => void
}

const SearchContext = createContext<SearchContextType | undefined>(undefined)

/**
 * Builds the initial search index from the static search config.
 * This replaces the HeadlessRenderer approach which rendered all pages
 * invisibly just to collect metadata, causing a UI freeze.
 * @returns The initial search index which serves as the base for the dynamic search index.
 */
const buildInitialIndex = (): Record<string, SearchOption> => {
    const index: Record<string, SearchOption> = {}
    for (const item of searchConfig) {
        index[item.id] = item
    }
    return index
}

/**
 * Provides the global registry of all searchable items and the function to register new items.
 * The index is pre-populated from the static search config on mount.
 * @param children The children of the provider.
 * @returns The search provider.
 */
export const SearchProvider = ({ children }: { children: ReactNode }) => {
    // Pre-populate the search index from the static config.
    const [searchIndex, setSearchIndex] = useState<Record<string, SearchOption>>(buildInitialIndex)

    /**
     * Registers a new searchable item or updates an existing one. Items from the static config are already in the index, so this
     * only triggers a state update when the `parentId` changes dynamically (e.g., when a conditional toggle changes).
     * @param item The item to register.
     */
    const registerItem = useCallback((item: SearchOption) => {
        const endTiming = startTiming("search_register_item", "ui")
        setSearchIndex((prev) => {
            if (prev[item.id]) {
                // If it already exists, update it if the parentId has changed like when the setting is toggled on/off and conditional state changes.
                if (prev[item.id].parentId !== item.parentId) {
                    endTiming({ id: item.id, title: item.title, action: "update" })
                    return {
                        ...prev,
                        [item.id]: item,
                    }
                }
                endTiming({ id: item.id, title: item.title, action: "skip" })
                return prev
            }
            endTiming({ id: item.id, title: item.title, action: "add" })
            return {
                ...prev,
                [item.id]: item,
            }
        })
    }, [])

    // Memoize the provider value to prevent cascading re-renders.
    const providerValue = useMemo(() => ({ searchIndex, registerItem }), [searchIndex, registerItem])

    return <SearchContext.Provider value={providerValue}>{children}</SearchContext.Provider>
}

/**
 * Hook to access the search registry context.
 * @returns The search registry context with the search index and registration function.
 */
export const useSearchRegistry = () => {
    const context = useContext(SearchContext)
    if (context === undefined) {
        throw new Error("useSearchRegistry must be used within a SearchProvider")
    }
    return context
}
