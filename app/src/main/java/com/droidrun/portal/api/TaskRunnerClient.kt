package com.droidrun.portal.api

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Synchronous HTTP client for the Droidrun Cloud Tasks API.
 * All methods are blocking and must be called from a background thread.
 */
object TaskRunnerClient {

    private const val TAG = "TaskRunnerClient"
    private const val BASE_URL = "https://dev-api.droidrun.ai/v1/tasks"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    data class TaskCreatedResponse(val id: String, val streamUrl: String, val token: String)
    data class TaskStatusResponse(val status: String)
    data class TaskDetailResponse(
        val status: String,
        val succeeded: Boolean?,
        val output: String?,
        val steps: Int?,
        val task: String
    )

    class TaskApiException(val httpCode: Int, message: String) : Exception(message)

    fun createTask(
        apiKey: String,
        deviceId: String,
        taskDescription: String,
        userAgent: String
    ): TaskCreatedResponse {
        val body = JSONObject().apply {
            put("task", taskDescription)
            put("llmModel", "google/gemini-3-flash")
            put("deviceId", deviceId)
            put("stealth", false)
            put("reasoning", true)
        }

        val connection = openConnection(BASE_URL, "POST", apiKey, userAgent)
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { os ->
                os.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = readErrorBody(connection)
                Log.w(TAG, "createTask failed: HTTP $code — $errorBody")
                throw TaskApiException(code, parseErrorMessage(errorBody, code))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)
            return TaskCreatedResponse(
                id = json.getString("id"),
                streamUrl = json.optString("streamUrl", ""),
                token = json.optString("token", "")
            )
        } finally {
            connection.disconnect()
        }
    }

    fun cancelTask(apiKey: String, taskId: String, userAgent: String): Boolean {
        val url = "$BASE_URL/$taskId/cancel"
        val connection = openConnection(url, "POST", apiKey, userAgent)
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { os ->
                os.write("{}".toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = readErrorBody(connection)
                Log.w(TAG, "cancelTask failed: HTTP $code — $errorBody")
                return false
            }
            return true
        } finally {
            connection.disconnect()
        }
    }

    fun getTaskStatus(apiKey: String, taskId: String, userAgent: String): TaskStatusResponse {
        val url = "$BASE_URL/$taskId/status"
        val connection = openConnection(url, "GET", apiKey, userAgent)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = readErrorBody(connection)
                Log.w(TAG, "getTaskStatus failed: HTTP $code — $errorBody")
                throw TaskApiException(code, parseErrorMessage(errorBody, code))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)
            return TaskStatusResponse(status = json.getString("status"))
        } finally {
            connection.disconnect()
        }
    }

    fun getTaskDetail(apiKey: String, taskId: String, userAgent: String): TaskDetailResponse {
        val url = "$BASE_URL/$taskId"
        val connection = openConnection(url, "GET", apiKey, userAgent)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val errorBody = readErrorBody(connection)
                Log.w(TAG, "getTaskDetail failed: HTTP $code — $errorBody")
                throw TaskApiException(code, parseErrorMessage(errorBody, code))
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            Log.d(TAG, "getTaskDetail response: ${responseBody.take(2000)}")
            val json = JSONObject(responseBody)

            // API wraps task data in a "task" key
            val taskObj = json.optJSONObject("task") ?: json

            val outputStr = when {
                taskObj.isNull("output") -> null
                else -> {
                    val outputVal = taskObj.opt("output")
                    when (outputVal) {
                        is JSONObject -> outputVal.toString(2)
                        is String -> outputVal
                        null -> null
                        else -> outputVal.toString()
                    }
                }
            }

            val succeeded = if (taskObj.isNull("succeeded")) null else taskObj.optBoolean("succeeded")

            return TaskDetailResponse(
                status = taskObj.optString("status", "unknown"),
                succeeded = succeeded,
                output = outputStr,
                steps = if (taskObj.isNull("steps")) null else taskObj.optInt("steps"),
                task = taskObj.optString("task", "")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        urlString: String,
        method: String,
        apiKey: String,
        userAgent: String
    ): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("User-Agent", userAgent)
        return connection
    }

    private fun readErrorBody(connection: HttpURLConnection): String {
        return try {
            connection.errorStream?.bufferedReader()?.use { reader ->
                val text = reader.readText()
                if (text.length > 1024) text.take(1024) else text
            } ?: ""
        } catch (e: IOException) {
            ""
        }
    }

    private fun parseErrorMessage(errorBody: String, httpCode: Int): String {
        if (errorBody.isBlank()) return "HTTP $httpCode"
        return try {
            val json = JSONObject(errorBody)
            json.optString("detail", null)
                ?: json.optString("error", null)
                ?: json.optString("message", null)
                ?: "HTTP $httpCode"
        } catch (e: Exception) {
            errorBody.take(200)
        }
    }
}
