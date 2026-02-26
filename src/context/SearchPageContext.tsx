import React, { createContext, useContext, useMemo, ReactNode } from "react"
import { ScrollView } from "react-native"

interface SearchPageContextType {
    /** The name of the current page. */
    page: string
    /** The ref of the parent ScrollView. */
    scrollViewRef?: React.RefObject<ScrollView | null>
}

const SearchPageContext = createContext<SearchPageContextType | undefined>(undefined)

/**
 * Provides page-level metadata (like page name and ScrollView ref) to all SearchableItem components on the page.
 * This simplifies the API by removing the need to pass these props to every item manually.
 * @param page The name of the current page.
 * @param scrollViewRef The ref of the parent ScrollView.
 * @param children The children of the provider.
 */
export const SearchPageProvider = ({ page, scrollViewRef, children }: { page: string; scrollViewRef?: React.RefObject<ScrollView | null>; children: ReactNode }) => {
    // Memoize the provider value to prevent cascading re-renders.
    const value = useMemo(() => ({ page, scrollViewRef }), [page, scrollViewRef])

    return <SearchPageContext.Provider value={value}>{children}</SearchPageContext.Provider>
}

export const useSearchPage = () => {
    const context = useContext(SearchPageContext)
    // We don't throw an error here because some items might be used outside a provider (using manual props).
    return context
}
