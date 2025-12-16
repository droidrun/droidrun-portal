package com.droidrun.portal.api

import org.json.JSONArray
import org.json.JSONObject

sealed class ApiResponse {
    data class Success(val data: Any) : ApiResponse()
    data class Error(val message: String) : ApiResponse()
    data class RawObject(val json: JSONObject) : ApiResponse()
    data class RawArray(val json: JSONArray) : ApiResponse()
    data class Binary(val data: ByteArray) : ApiResponse() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Binary

            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()

    }

    data class Text(val data: String) : ApiResponse()

    fun toJson(id: Int? = null): String = when (this) {
        is Success -> JSONObject().apply {
            id?.let { put("id", id) }
            put("status", "success")
            put("result", data)
        }.toString()

        is Error -> JSONObject().apply {
            id?.let { put("id", id) }
            put("status", "error")
            put("error", message)
        }.toString()

        is RawObject -> JSONObject().apply {
            id?.let { put("id", id) }
            put("status", "success")
            put("result", json)
        }.toString()

        is RawArray -> JSONObject().apply {
            id?.let { put("id", id) }
            put("status", "success")
            put("result", json)
        }.toString()

        is Binary -> JSONObject().apply {
            id?.let { put("id", id) }
            put("status", "success")
            put("result", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
        }.toString()

        is Text -> JSONObject().apply {
            id?.let { put("id", id) }
            put("status", "success")
            put("result", data)
        }.toString()
    }
}
