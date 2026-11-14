import { NativeModules } from "react-native"

/** The fixed port the server listens on, matching `DevMcpServer.PORT` in Kotlin. */
export const DEV_MCP_PORT = 9100

/** Status of the developer MCP server, surfaced to JS by `getStatus` and `setEnabled`. */
export interface DevMcpStatus {
    /** Whether the server is listening. */
    running: boolean
    /** The fixed port the server listens on, on 127.0.0.1. */
    port: number
    /** The last start failure, or null when the last start succeeded. */
    error: string | null
    /** Whether the MCP bridge sent a request in the last 15 seconds. */
    clientConnected: boolean
}

/** Typed surface of the native `DevMcpModule`. */
interface DevMcpBridgeApi {
    /** Start or stop the server. Rejects with `START_FAILED` when it could not start. */
    setEnabled(enabled: boolean): Promise<DevMcpStatus>
    /** Report whether the server is running. */
    getStatus(): Promise<DevMcpStatus>
}

const { DevMcpModule } = NativeModules as { DevMcpModule: DevMcpBridgeApi }

/** Singleton wrapper around the native developer MCP server bridge. */
export const devMcpBridge = DevMcpModule
