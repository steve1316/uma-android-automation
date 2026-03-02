import { PerformanceLogger } from "./performanceLogger"

/**
 * Get a formatted timestamp for logging.
 * @returns The formatted timestamp.
 */
export const getTimestamp = (): string => {
    const now = new Date()
    return now.toISOString().replace("T", " ").replace("Z", "").substring(0, 23)
}

/**
 * Log a message with timestamp prefix.
 * @param message The message to log.
 */
export const logWithTimestamp = (message: string): void => {
    if (PerformanceLogger.SUPPRESS_LOGGING) return
    console.log(`[${getTimestamp()}] ${message}`)
}

/**
 * Log an error with timestamp prefix.
 * @param message The message to log.
 * @param error The error to log.
 */
export const logErrorWithTimestamp = (message: string, error?: any): void => {
    console.error(`[${getTimestamp()}] ${message}`, error || "")
}

/**
 * Log a warning with timestamp prefix.
 * @param message The message to log.
 */
export const logWarningWithTimestamp = (message: string): void => {
    console.warn(`[${getTimestamp()}] ${message}`)
}
