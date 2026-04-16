package com.mobilerun.portal.events

import com.mobilerun.portal.events.model.PortalEvent
import org.java_websocket.WebSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

internal enum class LocalDeviceEventFormat {
    RPC,
    LEGACY,
}

internal object LocalDeviceEventRouting {
    private const val TOKEN_QUERY_PARAM = "token"
    private const val EVENT_FORMAT_QUERY_PARAM = "eventFormat"
    private const val LEGACY_EVENT_FORMAT = "legacy"
    private const val RPC_EVENT_FORMAT = "rpc"

    fun extractToken(resourceDescriptor: String?): String? =
        queryParam(resourceDescriptor, TOKEN_QUERY_PARAM)

    fun resolveFormat(resourceDescriptor: String?): LocalDeviceEventFormat {
        val rawFormat = queryParam(resourceDescriptor, EVENT_FORMAT_QUERY_PARAM)
        return if (rawFormat.equals(RPC_EVENT_FORMAT, ignoreCase = true)) {
            LocalDeviceEventFormat.RPC
        } else {
            LocalDeviceEventFormat.LEGACY
        }
    }

    fun encodeEvent(event: PortalEvent, format: LocalDeviceEventFormat): String {
        return when (format) {
            LocalDeviceEventFormat.RPC -> event.toReverseNotificationJson()
            LocalDeviceEventFormat.LEGACY -> event.toJson()
        }
    }

    private fun queryParam(resourceDescriptor: String?, name: String): String? {
        if (resourceDescriptor.isNullOrBlank()) return null
        val queryStart = resourceDescriptor.indexOf('?')
        if (queryStart == -1 || queryStart == resourceDescriptor.lastIndex) return null

        val query = resourceDescriptor.substring(queryStart + 1)
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val parts = pair.split('=', limit = 2)
            val key = decode(parts[0])
            if (key != name) continue
            return parts.getOrNull(1)?.let(::decode).orEmpty()
        }
        return null
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
    }
}

internal class LocalDeviceEventRelay(
    private val connectionsProvider: () -> Collection<WebSocket>,
    private val onSendFailure: (WebSocket, Exception) -> Unit = { _, _ -> },
) {
    private val connectionFormats = ConcurrentHashMap<WebSocket, LocalDeviceEventFormat>()

    fun register(connection: WebSocket, resourceDescriptor: String?) {
        connectionFormats[connection] = LocalDeviceEventRouting.resolveFormat(resourceDescriptor)
    }

    fun unregister(connection: WebSocket?) {
        if (connection != null) {
            connectionFormats.remove(connection)
        }
    }

    fun emit(event: PortalEvent) {
        for (connection in connectionsProvider()) {
            if (!connection.isOpen) continue

            val format = connectionFormats[connection] ?: LocalDeviceEventFormat.RPC
            val message = LocalDeviceEventRouting.encodeEvent(event, format)
            try {
                connection.send(message)
            } catch (e: Exception) {
                onSendFailure(connection, e)
            }
        }
    }
}
