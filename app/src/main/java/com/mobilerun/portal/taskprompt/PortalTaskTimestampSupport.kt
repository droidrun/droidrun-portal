package com.mobilerun.portal.taskprompt

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object PortalTaskTimestampSupport {
    private val isoOffsetFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val isoLocalFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    internal fun parseEpochMillis(raw: String?): Long? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) return null

        return runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()
            ?: runCatching {
                OffsetDateTime.parse(normalized, isoOffsetFormatter)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(normalized, isoLocalFormatter)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }.getOrNull()
            ?: normalized.toLongOrNull()?.let { rawValue ->
                when {
                    rawValue >= 1_000_000_000_000L -> rawValue
                    rawValue >= 1_000_000_000L -> rawValue * 1000L
                    else -> null
                }
            }
    }

    fun formatForDisplay(
        raw: String?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) return null

        val epochMillis = parseEpochMillis(normalized) ?: return normalized
        val zonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        val currentYear = Instant.ofEpochMilli(nowMs).atZone(zoneId).year
        val pattern = if (zonedDateTime.year == currentYear) {
            "MMM d, HH:mm"
        } else {
            "MMM d, yyyy, HH:mm"
        }
        return zonedDateTime.format(DateTimeFormatter.ofPattern(pattern, locale))
    }
}
