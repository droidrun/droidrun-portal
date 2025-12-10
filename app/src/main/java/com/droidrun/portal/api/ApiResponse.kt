package com.droidrun.portal.api

import org.json.JSONObject

sealed class ApiResponse {
    data class Success(val data: Any) : ApiResponse()
    data class Error(val message: String) : ApiResponse()
    data class Raw(val json: JSONObject) : ApiResponse()
    data class Binary(val data: ByteArray) : ApiResponse() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Binary

            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            return data.contentHashCode()
        }
    }

    fun toJson(): String = when (this) {
        is Success -> JSONObject().apply {
            put("status", "success")
            put("data", data)
        }.toString()
        is Error -> JSONObject().apply {
            put("status", "error")
            put("error", message)
        }.toString()
        is Raw -> json.toString()
        is Binary -> JSONObject().apply {
            put("status", "success")
            put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
        }.toString()
    }
}
