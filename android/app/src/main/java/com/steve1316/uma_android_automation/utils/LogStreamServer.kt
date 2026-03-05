package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.steve1316.automation_library.data.SharedData
import com.steve1316.automation_library.events.JSEvent
import com.steve1316.automation_library.utils.MessageLog
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Embedded WebSocket server that streams MessageLog entries in real-time
 * to any browser on the local network. Built on NanoHTTPD/NanoWSD.
 *
 * When running, the server serves:
 * - HTTP GET "/" → the log viewer HTML page (from assets).
 * - WebSocket "/ws" → real-time log message streaming.
 */
object LogStreamServer {
	private const val TAG: String = "${SharedData.loggerTag}LogStreamServer"

	private var server: LogWebSocketServer? = null

	@Volatile
	var isRunning = false
		private set

	/**
	 * Starts the log streaming server on the specified port and registers with EventBus.
	 *
	 * @param context The application context used for accessing assets and system services.
	 * @param port The network port number to listen on.
	 */
	fun start(context: Context, port: Int) {
		// Prevent starting multiple instances of the server.
		if (isRunning) {
			Log.w(TAG, "Server is already running.")
			return
		}

		try {
			server = LogWebSocketServer(context, port)
			// Use 0 as the timeout for WebSockets to prevent "Read timed out" disconnects.
			server?.start(0, false)
			isRunning = true

			// Register with EventBus to receive real-time log messages.
			if (!EventBus.getDefault().isRegistered(this)) {
				EventBus.getDefault().register(this)
			}

			// Determine and log the device IP address for easy access.
			val ip = getDeviceIpAddress(context)
			Log.i(TAG, "Log stream server started on http://$ip:$port")
			
			// Populate the initial buffer from existing logs so late-joining clients see the history.
			server?.populateBuffer(MessageLog.getMessageLogCopy())
			
			Log.d(TAG, "LogStreamServer registered with EventBus: ${EventBus.getDefault().isRegistered(this)}")
			MessageLog.i(TAG, "Remote Log Viewer started at http://$ip:$port")
		} catch (e: IOException) {
			// Handle cases where the server fails to bind to the port.
			Log.e(TAG, "Failed to start log stream server: ${e.message}")
			MessageLog.e(TAG, "Failed to start Remote Log Viewer: ${e.message}")
			isRunning = false
		}
	}

	/**
	 * Stops the log streaming server, clears the buffer, and unregisters from EventBus.
	 */
	fun stop() {
		if (!isRunning) return

		try {
			// Unregister to stop receiving log events.
			EventBus.getDefault().unregister(this)
		} catch (_: Exception) {
			// Ignore exceptions if the server was not registered.
		}

		// Shutdown the underlying NanoWSD server instance.
		server?.stop()
		// Clear the message buffer to free up memory.
		server?.clearBuffer()
		server = null
		isRunning = false
		Log.i(TAG, "Log stream server stopped.")
	}

	/**
	 * Subscriber for MessageLog events via EventBus.
	 * 
	 * Broadcasts each received log message to all currently connected WebSocket clients.
	 *
	 * @param event The JSEvent object containing the log message metadata and content.
	 */
	@Subscribe(threadMode = ThreadMode.BACKGROUND)
	fun onMessageLogEvent(event: JSEvent) {
		// Only process events that are identified as MessageLog entries.
		if (event.eventName == "MessageLog") {
			server?.broadcast(event.message)
		}
	}

	/**
	 * Retrieves the device's local IP address as a human-readable string.
	 *
	 * Prioritizes actual network interfaces over the WifiManager, which can be unreliable
	 * or restricted on newer Android versions.
	 *
	 * @param context The application context.
	 * @return The device's local IP address or "0.0.0.0" if unavailable.
	 */
	fun getDeviceIpAddress(context: Context): String {
		val foundIps = mutableListOf<String>()
		try {
			// Enumerate all network interfaces on the device.
			val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
			for (intf in interfaces) {
				val addrs = java.util.Collections.list(intf.inetAddresses)
				for (addr in addrs) {
					// Skip loopback addresses to find real network IPs.
					if (!addr.isLoopbackAddress) {
						val sAddr = addr.hostAddress
						val isIPv4 = sAddr.indexOf(':') < 0
						if (isIPv4) {
							foundIps.add(sAddr)
						}
					}
				}
			}
		} catch (e: Exception) {
			Log.w(TAG, "Could not determine IP address via NetworkInterface: ${e.message}")
		}

		if (foundIps.isNotEmpty()) {
			Log.d(TAG, "Found local IPs: $foundIps")
			
			// Prioritize private network address ranges common in local networks.
			val bestIp = foundIps.find { it.startsWith("192.168.") }
				?: foundIps.find { it.startsWith("10.") && it != "10.0.2.15" }
				?: foundIps.find { it.startsWith("172.16.") || it.startsWith("172.31.") }
				?: foundIps[0]
				
			return bestIp
		}

		// Fallback to the WifiManager for older devices or if the interface scan fails.
		try {
			val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
			val wifiInfo = wifiManager.connectionInfo
			val ipInt = wifiInfo.ipAddress

			if (ipInt != 0) {
				// Convert the integer representation of the IP address to a dotted string.
				return String.format(
					"%d.%d.%d.%d",
					ipInt and 0xff,
					ipInt shr 8 and 0xff,
					ipInt shr 16 and 0xff,
					ipInt shr 24 and 0xff
				)
			}
		} catch (e: Exception) {
			Log.w(TAG, "Could not determine WiFi IP address via WifiManager: ${e.message}")
		}

		return "0.0.0.0"
	}

	/**
	 * Custom implementation of the NanoWSD WebSocket server.
	 *
	 * @param context The application context used for asset retrieval.
	 * @param port The port to bind the server to.
	 */
	private class LogWebSocketServer(private val context: Context, port: Int) : NanoWSD(port) {

		// Thread-safe set of currently active WebSocket client connections.
		private val clients = CopyOnWriteArraySet<LogWebSocket>()

		// Circular buffer for storing the most recent log messages.
		private val messageBuffer = ArrayList<String>()

		// Synchronization lock for thread-safe access to the message buffer.
		private val bufferLock = Object()

		// Maximum number of messages to retain in the history buffer.
		private val maxBufferSize = 5000

		/**
		 * Clears all messages from the history buffer.
		 */
		fun clearBuffer() {
			synchronized(bufferLock) {
				messageBuffer.clear()
			}
		}

		/**
		 * Pre-fills the history buffer with a given list of existing log messages.
		 * 
		 * @param history A list of log message strings to initialize the buffer.
		 */
		fun populateBuffer(history: List<String>) {
			synchronized(bufferLock) {
				messageBuffer.clear()
				// Only retain the last maxBufferSize messages to avoid overflow.
				val start = (history.size - maxBufferSize).coerceAtLeast(0)
				for (i in start until history.size) {
					messageBuffer.add(history[i])
				}
				Log.d(TAG, "Populated buffer with ${messageBuffer.size} historical logs.")
			}
		}

		/**
		 * Handles incoming WebSocket connection requests.
		 * 
		 * @param handshake The HTTP session representing the WebSocket handshake.
		 * @returns An instance of LogWebSocket to handle the connection.
		 */
		override fun openWebSocket(handshake: IHTTPSession): WebSocket {
			return LogWebSocket(handshake)
		}

		/**
		 * Handles standard HTTP GET requests for the server.
		 * 
		 * @param session The HTTP session containing the request details (URI, method, etc).
		 * @returns Redone response containing the requested resource or an error.
		 */
		override fun serveHttp(session: IHTTPSession): Response {
			val uri = session.uri

			// Serve the main log viewer HTML application.
			if (uri == "/" || uri == "/index.html") {
				return try {
					val htmlStream = context.assets.open("log_viewer.html")
					val html = htmlStream.bufferedReader().use { it.readText() }
					newFixedLengthResponse(Response.Status.OK, "text/html", html)
				} catch (e: IOException) {
					Log.e(TAG, "Failed to load log_viewer.html asset: ${e.message}")
					newFixedLengthResponse(
						Response.Status.INTERNAL_ERROR,
						NanoHTTPD.MIME_PLAINTEXT,
						"Failed to load log viewer page."
					)
				}
			}

			// Provide a health check endpoint for monitoring the server status.
			if (uri == "/health") {
				return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
			}

			// Return a 404 response for any unrecognized endpoints.
			return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found")
		}

		/**
		 * Broadcasts a log message to all connected clients and adds it to the history buffer.
		 *
		 * @param message The log message string to be sent to clients.
		 */
		fun broadcast(message: String) {
			// Persist the message in the history buffer for late joiners.
			synchronized(bufferLock) {
				messageBuffer.add(message)
				// Maintain the buffer size within the specified limit.
				if (messageBuffer.size > maxBufferSize) {
					messageBuffer.removeAt(0)
				}
			}

			// If no clients are connected, there is no need to proceed with broadcasting.
			if (clients.isEmpty()) {
				return
			}

			// Iterate through all active clients and attempt to deliver the message.
			for (client in clients) {
				try {
					client.send(message)
				} catch (e: Exception) {
					// Remove the client if delivery fails (likely disconnected).
					Log.w(TAG, "Failed to send message to client: ${e.message}")
					clients.remove(client)
				}
			}
		}

		/**
		 * Represents an active WebSocket connection with an individual browser client.
         *
         * @param handshake The HTTP session representing the WebSocket handshake.
		 */
		private inner class LogWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {

			/**
			 * Invoked when a new WebSocket connection is established.
			 */
			override fun onOpen() {
				Log.d(TAG, "WebSocket client connected. Total clients: ${clients.size + 1}")
				clients.add(this)

				// Create a snapshot of the current history buffer.
				val historyToSync = synchronized(bufferLock) {
					messageBuffer.toList()
				}

				// Synchronize the new client by sending the historical logs in a background thread.
				Thread {
					try {
						// Group messages into batches of 100 to optimize network throughput.
						val batched = historyToSync.chunked(100)
						for (batch in batched) {
							val combined = "BATCH:" + batch.joinToString("---LOG_SEPARATOR---")
							send(combined)
						}
					} catch (e: Exception) {
						Log.w(TAG, "Failed to send history to new client: ${e.message}")
					}
				}.start()
			}

			/**
			 * Invoked when the WebSocket connection is closed.
             * 
             * @param code The close code indicating the reason for closing the connection.
             * @param reason The reason for closing the connection.
             * @param initiatedByRemote True if the connection was initiated by the remote client, false otherwise.
			 */
			override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
				clients.remove(this)
				Log.d(TAG, "WebSocket client disconnected. Remaining clients: ${clients.size}")
			}

			/**
			 * Invoked when a text message is received from the client.
             *
             * @param message The text message received from the client.
			 */
			override fun onMessage(message: NanoWSD.WebSocketFrame?) {
				// The server does not expect or handle incoming messages from clients.
			}

			/**
			 * Invoked when a pong frame is received.
             * 
             * @param pong The pong frame received from the client.
			 */
			override fun onPong(pong: NanoWSD.WebSocketFrame?) {
				// No processing required for pong frames in this implementation.
			}

			/**
			 * Invoked when an exception occurs on the WebSocket connection.
             * 
             * @param exception The exception that occurred on the WebSocket connection.
			 */
			override fun onException(exception: IOException?) {
				clients.remove(this)
				Log.w(TAG, "WebSocket exception: ${exception?.message}")
			}
		}
	}
}
