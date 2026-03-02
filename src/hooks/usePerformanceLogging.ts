import { useEffect, useRef } from "react"
import { startTiming, markNavigationEnd } from "../lib/performanceLogger"

/**
 * A custom hook that logs component lifecycle events (mount, unmount, re-render).
 * All logs are gated by `PerformanceLogger.ENABLED`.
 * @param componentName - The name of the component to track.
 */
export const usePerformanceLogging = (componentName: string) => {
    const renderCount = useRef(1)

    useEffect(() => {
        // Log mount event.
        const endTiming = startTiming(`${componentName}_mount`, "ui")
        endTiming({ status: "mounted" })

        // Mark the end of a navigation if one was pending for this component.
        markNavigationEnd(componentName)

        return () => {
            // Log unmount event.
            const endTiming = startTiming(`${componentName}_unmount`, "ui")
            endTiming({ status: "unmounted", totalRenders: renderCount.current })
        }
    }, [])

    useEffect(() => {
        // Log re-render events (skip the first render as it's part of mount).
        if (renderCount.current > 1) {
            const endTiming = startTiming(`${componentName}_render`, "ui")
            endTiming({ renderCount: renderCount.current })
        }
        renderCount.current++
    })
}
