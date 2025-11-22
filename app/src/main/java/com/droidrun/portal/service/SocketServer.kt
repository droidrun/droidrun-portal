package com.droidrun.portal.service

import android.content.ContentValues
import android.net.Uri
import android.util.Log
import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.api.ApiResponse
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SocketServer(private val apiHandler: ApiHandler) {
    companion object {
        private const val TAG = "DroidrunSocketServer"
        private const val DEFAULT_PORT = 8080
        private const val THREAD_POOL_SIZE = 5
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = AtomicBoolean(false)
    private var executorService: ExecutorService? = null
    private var port: Int = DEFAULT_PORT

    fun start(port: Int = DEFAULT_PORT): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running on port ${this.port}")
            return true
        }

        this.port = port
        Log.i(TAG, "Starting socket server on port $port...")
        
        return try {
            executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
            serverSocket = ServerSocket(port)
            isRunning.set(true)
            
            executorService?.submit {
                acceptConnections()
            }
            
            Log.i(TAG, "Socket server started successfully on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start socket server on port $port", e)
            isRunning.set(false)
            executorService?.shutdown()
            executorService = null
            false
        }
    }

    fun stop() {
        if (!isRunning.get()) return

        isRunning.set(false)
        
        try {
            serverSocket?.close()
            executorService?.shutdown()
            executorService = null
            Log.i(TAG, "Socket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping socket server", e)
        }
    }

    fun isRunning(): Boolean = isRunning.get()
    fun getPort(): Int = port

    private fun acceptConnections() {
        while (isRunning.get()) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                executorService?.submit {
                    handleClient(clientSocket)
                }
            } catch (e: SocketException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Socket exception while accepting connections", e)
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting connection", e)
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val outputStream = socket.getOutputStream()

                val requestLine = reader.readLine()
                if (requestLine == null) return

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendErrorResponse(outputStream, 400, "Bad Request")
                    return
                }

                val method = parts[0]
                val path = parts[1]

                // Consume headers
                while (reader.readLine().isNullOrEmpty() == false) {}

                val response = when (method) {
                    "GET" -> handleGetRequest(path)
                    "POST" -> handlePostRequest(path, reader)
                    else -> ApiResponse.Error("Method not allowed: $method").toJson()
                }

                sendHttpResponse(outputStream, response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        }
    }

    private fun handleGetRequest(path: String): String {
        return try {
            val response = when {
                path.startsWith("/ping") -> apiHandler.ping()
                path.startsWith("/a11y_tree_full") -> apiHandler.getTreeFull(parseFilterParam(path))
                path.startsWith("/a11y_tree") -> apiHandler.getTree()
                path.startsWith("/state_full") -> apiHandler.getStateFull(parseFilterParam(path))
                path.startsWith("/state") -> apiHandler.getState()
                path.startsWith("/phone_state") -> apiHandler.getPhoneState()
                path.startsWith("/version") -> apiHandler.getVersion()
                path.startsWith("/packages") -> apiHandler.getPackages()
                path.startsWith("/screenshot") -> {
                    val hideOverlay = if (path.contains("?")) {
                         !path.contains("hideOverlay=false")
                    } else true
                    apiHandler.getScreenshot(hideOverlay)
                }
                else -> ApiResponse.Error("Unknown endpoint: $path")
            }
            response.toJson()
        } catch (e: Exception) {
            ApiResponse.Error("Internal server error: ${e.message}").toJson()
        }
    }

    private fun handlePostRequest(path: String, reader: BufferedReader): String {
        return try {
            val postData = StringBuilder()
            if (reader.ready()) {
                val char = CharArray(1024)
                val bytesRead = reader.read(char)
                if (bytesRead > 0) {
                    postData.append(char, 0, bytesRead)
                }
            }
            val values = parsePostData(postData.toString())

            val response = when {
                path.startsWith("/keyboard/input") -> 
                    apiHandler.keyboardInput(values.getAsString("base64_text") ?: "", values.getAsBoolean("clear") ?: true)
                path.startsWith("/keyboard/clear") -> apiHandler.keyboardClear()
                path.startsWith("/keyboard/key") -> 
                    apiHandler.keyboardKey(values.getAsInteger("key_code") ?: 0)
                path.startsWith("/overlay_offset") -> 
                    apiHandler.setOverlayOffset(values.getAsInteger("offset") ?: 0)
                path.startsWith("/socket_port") ->
                    apiHandler.setSocketPort(values.getAsInteger("port") ?: 0)
                else -> ApiResponse.Error("Unknown POST endpoint: $path")
            }
            response.toJson()
        } catch (e: Exception) {
            ApiResponse.Error("Internal server error: ${e.message}").toJson()
        }
    }

    private fun parsePostData(data: String): ContentValues {
        val values = ContentValues()
        if (data.isBlank()) return values

        try {
            if (data.trim().startsWith("{")) {
                val json = JSONObject(data)
                json.keys().forEach { key ->
                    val value = json.get(key)
                    when (value) {
                        is String -> values.put(key, value)
                        is Int -> values.put(key, value)
                        is Boolean -> values.put(key, value)
                        else -> values.put(key, value.toString())
                    }
                }
            } else {
                data.split("&").forEach { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = Uri.decode(parts[0])
                        val value = Uri.decode(parts[1])
                        val intValue = value.toIntOrNull()
                        if (intValue != null) {
                            values.put(key, intValue)
                        } else {
                            when (value.lowercase()) {
                                "true" -> values.put(key, true)
                                "false" -> values.put(key, false)
                                else -> values.put(key, value)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing POST data", e)
        }
        return values
    }

    private fun parseFilterParam(path: String): Boolean {
        if (!path.contains("?")) return true
        val queryString = path.substringAfter("?")
        return !queryString.contains("filter=false")
    }

    private fun sendHttpResponse(outputStream: OutputStream, response: String) {
        try {
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            outputStream.write(headers.toByteArray(Charsets.UTF_8))
            outputStream.write(responseBytes)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP response", e)
        }
    }
    
    private fun sendErrorResponse(outputStream: OutputStream, code: Int, message: String) {
        // Implementation similar to previous, but maybe using ApiResponse if possible, 
        // though this is for protocol-level errors (400, 500)
        try {
             val errorResponse = ApiResponse.Error(message).toJson()
             val responseBytes = errorResponse.toByteArray(Charsets.UTF_8)
             val headers = "HTTP/1.1 $code $message\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            outputStream.write(headers.toByteArray(Charsets.UTF_8))
            outputStream.write(responseBytes)
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending error response", e)
        }
    }
}
