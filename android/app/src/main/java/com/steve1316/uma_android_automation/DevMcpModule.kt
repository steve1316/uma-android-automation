package com.steve1316.uma_android_automation

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.steve1316.automation_library.utils.SettingsHelper
import com.steve1316.uma_android_automation.utils.DevMcpServer

/**
 * Bridge module for the developer MCP server toggle in Debug Settings. The server is not tied to a bot run: it starts with the app when the setting is on, and the
 * toggle starts or stops it immediately.
 *
 * @param reactContext The React Native application context.
 */
class DevMcpModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    companion object {
        private val TAG = "[${MainActivity.loggerTag}]DevMcpModule"
    }

    override fun getName(): String {
        return "DevMcpModule"
    }

    /** Starts the server at app launch when the saved setting is on. */
    override fun initialize() {
        super.initialize()
        try {
            if (!SettingsHelper.isAvailable()) SettingsHelper.initialize(reactApplicationContext)
            if (SettingsHelper.getBooleanSetting("debug", "enableDevMcpServer", false)) DevMcpServer.start(reactApplicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the developer MCP server setting: ${e.message}")
        }
    }

    /**
     * Starts or stops the server. Resolves with the resulting status, or rejects with `START_FAILED` when the server could not start.
     *
     * @param enabled Whether the server should be running.
     * @param promise Resolved with the status map from [getStatus].
     */
    @ReactMethod
    fun setEnabled(enabled: Boolean, promise: Promise) {
        if (!enabled) {
            DevMcpServer.stop()
            promise.resolve(statusMap())
            return
        }

        DevMcpServer.start(reactApplicationContext)
        if (!DevMcpServer.isRunning) {
            promise.reject("START_FAILED", DevMcpServer.lastError ?: "The developer MCP server did not start.")
            return
        }
        promise.resolve(statusMap())
    }

    /**
     * Reports whether the server is running.
     *
     * @param promise Resolved with `{running, port, error, clientConnected}`.
     */
    @ReactMethod
    fun getStatus(promise: Promise) {
        promise.resolve(statusMap())
    }

    /**
     * Builds the status map sent to JS.
     *
     * @returns The `{running, port, error, clientConnected}` map.
     */
    private fun statusMap(): WritableMap {
        val map = Arguments.createMap()
        map.putBoolean("running", DevMcpServer.isRunning)
        map.putInt("port", DevMcpServer.PORT)
        map.putString("error", DevMcpServer.lastError)
        map.putBoolean("clientConnected", DevMcpServer.isClientConnected)
        return map
    }
}
