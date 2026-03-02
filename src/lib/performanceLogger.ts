/**
 * Performance logging utility for tracking operation timing and performance metrics.
 * Provides detailed timing information for debugging performance issues.
 *
 * Log Structure Breakdown:
 * `[PERF] CATEGORY - Operation: Duration | Details`
 *
 * 1. [PERF]: Static prefix for easy filtering in the console.
 * 2. CATEGORY: The system area being monitored (UI, DATABASE, STATE, SETTINGS).
 * 3. Operation: Functional name of the task (e.g., Home_render, save_settings).
 * 4. Duration: Time taken in milliseconds (ms).
 * 5. Details: (Optional) JSON payload for additional context (e.g., render counts, mount status).
 *
 * Interpretation Guide:
 * - renderCount: Number of times a component has updated. High counts for a single action suggest unnecessary re-renders.
 *   Skip counts (action: "skip") in search registration indicate efficient indexing.
 * - status: Tracks "mounted" vs "unmounted" lifecycle states.
 * - duration: Critical for background operations. Slow operations will trigger a `console.warn` to highlight performance bottlenecks.
 *
 * Examples:
 * - UI Render: `[PERF] UI - Home_render: 0.00ms | Details: {"renderCount":5}`
 * - UI Mount: `[PERF] UI - Settings_mount: 0.00ms | Details: {"status":"mounted"}`
 * - Search Registry: `[PERF] UI - search_register_item: 0.01ms | Details: {"id":"...","action":"skip"}`
 * - Database: `[PERF] DATABASE - database_load_all_settings: 12.45ms`
 */

export interface PerformanceMetric {
    /** The name of the operation being timed. */
    operation: string
    /** The duration of the operation in milliseconds. */
    duration: number
    /** The timestamp when the operation started. */
    timestamp: number
    /** Additional details about the operation. */
    details?: Record<string, any>
    /** The category of the operation (e.g., "database", "settings", "state", "ui"). */
    category: "database" | "settings" | "state" | "ui"
}

/**
 * Performance logging utility for tracking operation timing and performance metrics.
 * Provides detailed timing information for debugging performance issues.
 */
export class PerformanceLogger {
    public static ENABLED = false
    public static SUPPRESS_LOGGING = true

    private metrics: PerformanceMetric[] = []
    private maxMetricsHistory = 100

    private pendingNavigations: Map<string, number> = new Map()

    /**
     * Start timing an operation.
     * @param operation The name of the operation being timed.
     * @param category The category of the operation (e.g., "database", "settings", "state", "ui").
     * @returns A function to stop timing the operation and record the metric.
     */
    startTiming(operation: string, category: PerformanceMetric["category"] = "settings"): (details?: Record<string, any>) => PerformanceMetric {
        if (!PerformanceLogger.ENABLED) {
            return () => ({
                operation,
                duration: 0,
                timestamp: Date.now(),
                category,
            })
        }

        const startTime = performance.now()
        const timestamp = Date.now()

        return (details?: Record<string, any>) => {
            const endTime = performance.now()
            const duration = endTime - startTime

            const metric: PerformanceMetric = {
                operation,
                duration,
                timestamp,
                details,
                category,
            }

            this.recordMetric(metric)
            return metric
        }
    }

    /**
     * Mark the start of a navigation.
     * @param target The target of the navigation.
     */
    markNavigationStart(target: string) {
        if (!PerformanceLogger.ENABLED) return
        this.pendingNavigations.set(target, performance.now())
    }

    /**
     * Mark the end of a navigation and record the duration.
     * @param target The target of the navigation.
     * @param category The category of the navigation (e.g., "ui").
     */
    markNavigationEnd(target: string, category: PerformanceMetric["category"] = "ui") {
        if (!PerformanceLogger.ENABLED) return
        const startTime = this.pendingNavigations.get(target)
        if (startTime === undefined) return

        const duration = performance.now() - startTime
        this.pendingNavigations.delete(target)

        const metric: PerformanceMetric = {
            operation: `navigation_to_${target}`,
            duration,
            timestamp: Date.now(),
            category,
        }

        this.recordMetric(metric)
    }

    /**
     * Record a performance metric.
     * @param metric The metric to record.
     */
    recordMetric(metric: PerformanceMetric) {
        if (!PerformanceLogger.ENABLED) return

        this.metrics.push(metric)

        // Keep only the most recent metrics to prevent memory issues.
        if (this.metrics.length > this.maxMetricsHistory) {
            this.metrics = this.metrics.slice(-this.maxMetricsHistory)
        }

        this.logMetric(metric)
    }

    /**
     * Log a performance metric to console.
     * @param metric The metric to log.
     */
    private logMetric(metric: PerformanceMetric) {
        if (!PerformanceLogger.ENABLED) return
        const logMessage = `[PERF] ${metric.category.toUpperCase()} - ${metric.operation}: ${metric.duration.toFixed(2)}ms${metric.details ? ` | Details: ${JSON.stringify(metric.details)}` : ""}`

        if (metric.duration >= 300) {
            console.warn(logMessage) // Warn for slow operations.
        } else {
            console.log(logMessage)
        }
    }
}

// Create singleton instance.
export const performanceLogger = new PerformanceLogger()

// Export convenience functions.
export const startTiming = (operation: string, category?: PerformanceMetric["category"]) => performanceLogger.startTiming(operation, category)
export const markNavigationStart = (target: string) => performanceLogger.markNavigationStart(target)
export const markNavigationEnd = (target: string, category?: PerformanceMetric["category"]) => performanceLogger.markNavigationEnd(target, category)
