package com.droidrun.portal.api

import org.json.JSONObject

sealed class ApiResponse {
    data class Success(val data: Any) : ApiResponse()
    data class Error(val message: String) : ApiResponse()
    data class Raw(val json: JSONObject) : ApiResponse()

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
    }
}
