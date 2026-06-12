package com.mobilerun.portal.sms

import android.content.Context
import android.util.Log
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.taskprompt.PortalCloudClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to numbers-api over plain HTTPS, authenticated with the platform cloud
 * token (Traefik injects X-User-ID). Synchronous calls — invoked from the sync
 * worker thread. Endpoint shapes track numbers-api SMS_GATEWAY_PORT_PLAN.md.
 */
class SmsApiClient(context: Context) {
    private val config = ConfigManager.getInstance(context)
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String? {
        val rest = PortalCloudClient.deriveRestBaseUrl(config.reverseConnectionUrlOrDefault) ?: return null
        return "$rest/numbers"
    }

    private fun authedBuilder(url: String): Request.Builder =
        Request.Builder().url(url).addHeader("Authorization", "Bearer ${config.reverseConnectionToken}")

    fun registerDevice(deviceId: String, name: String, simCards: JSONArray): Boolean {
        val base = baseUrl() ?: return false
        val body = JSONObject().put("deviceId", deviceId).put("name", name).put("simCards", simCards)
        return execute(authedBuilder("$base/devices/sms").post(body.toString().toRequestBody(JSON)).build())
    }

    fun ping(deviceId: String, state: String): Boolean {
        val base = baseUrl() ?: return false
        val body = JSONObject().put("state", state)
        return execute(authedBuilder("$base/devices/$deviceId/sms/ping").post(body.toString().toRequestBody(JSON)).build())
    }

    fun getPending(deviceId: String): List<PendingSend> {
        val base = baseUrl() ?: return emptyList()
        val req = authedBuilder("$base/devices/$deviceId/sms/pending?order=lifo").get().build()
        return try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                parsePending(res.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "getPending failed", e)
            emptyList()
        }
    }

    fun reportStatus(deviceId: String, rows: List<SmsStore.OutboundRow>): Boolean {
        if (rows.isEmpty()) return true
        val base = baseUrl() ?: return false
        val payload = JSONArray()
        for (r in rows) {
            payload.put(
                JSONObject()
                    .put("id", r.sendTaskId)
                    .put("state", r.state)
                    .put(
                        "recipients",
                        JSONArray().put(
                            JSONObject().put("phoneNumber", r.phoneNumber).put("state", r.state)
                                .apply { r.error?.let { put("error", it) } },
                        ),
                    ),
            )
        }
        return execute(authedBuilder("$base/devices/$deviceId/sms/status").patch(payload.toString().toRequestBody(JSON)).build())
    }

    fun postInbound(deviceId: String, sms: InboundSms): Boolean {
        val base = baseUrl() ?: return false
        val body = JSONObject()
            .put("messageId", sms.localId)
            .put("sender", sms.sender)
            .put("recipient", sms.recipient)
            .put("text", sms.body)
            .apply { sms.simNumber?.let { put("simNumber", it) } }
            .put("receivedAt", sms.receivedAtMs)
        return execute(authedBuilder("$base/devices/$deviceId/sms/inbound").post(body.toString().toRequestBody(JSON)).build())
    }

    private fun execute(req: Request): Boolean = try {
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        Log.w(TAG, "request failed: ${req.url}", e)
        false
    }

    private fun parsePending(body: String): List<PendingSend> {
        if (body.isBlank()) return emptyList()
        // Tolerate either a bare array or a { data: { items: [...] } } / { data: [...] } envelope.
        val items: JSONArray = when {
            body.trimStart().startsWith("[") -> JSONArray(body)
            else -> {
                val root = JSONObject(body)
                val data = root.opt("data")
                when (data) {
                    is JSONArray -> data
                    is JSONObject -> data.optJSONArray("items") ?: JSONArray()
                    else -> root.optJSONArray("items") ?: JSONArray()
                }
            }
        }
        return buildList {
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { o.optString("sendTaskId") }
                if (id.isBlank()) continue
                val text = o.optJSONObject("textMessage")?.optString("text")
                    ?: o.optString("message")
                val phones = o.optJSONArray("phoneNumbers") ?: JSONArray()
                add(
                    PendingSend(
                        sendTaskId = id,
                        text = text,
                        phoneNumbers = (0 until phones.length()).map { phones.optString(it) }.filter { it.isNotBlank() },
                        simNumber = o.optInt("simNumber").takeIf { o.has("simNumber") },
                        withDeliveryReport = o.optBoolean("withDeliveryReport", false),
                        validUntilMs = null,
                    ),
                )
            }
        }
    }

    companion object {
        private const val TAG = "SmsApiClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
