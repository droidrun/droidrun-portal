package com.mobilerun.portal.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * File type enum matching devices-api FileType.
 * See: devices-api/internal/device/tools/tools.go
 */
enum class FileType(val value: String) {
    FILE("file"),
    DIRECTORY("directory"),
    SYMLINK("symlink"),
    BLOCK_DEVICE("block_device"),
    CHARACTER_DEVICE("character_device"),
    FIFO("fifo"),
    SOCKET("socket"),
    UNKNOWN("unknown");

    companion object {
        fun fromModeChar(char: Char): FileType = when (char) {
            '-' -> FILE
            'd' -> DIRECTORY
            'l' -> SYMLINK
            'b' -> BLOCK_DEVICE
            'c' -> CHARACTER_DEVICE
            'p' -> FIFO
            's' -> SOCKET
            else -> UNKNOWN
        }
    }
}

/**
 * Permission set for owner/group/others.
 */
data class PermissionSet(
    val read: Boolean,
    val write: Boolean,
    val execute: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("read", read)
        put("write", write)
        put("execute", execute)
    }
}

/**
 * Special permission bits (setuid, setgid, sticky).
 */
data class SpecialPermissions(
    val setUid: Boolean,
    val setGid: Boolean,
    val sticky: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("setUid", setUid)
        put("setGid", setGid)
        put("sticky", sticky)
    }
}

/**
 * Full file permissions structure.
 */
data class FilePermissions(
    val owner: PermissionSet,
    val group: PermissionSet,
    val others: PermissionSet,
    val special: SpecialPermissions
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("owner", owner.toJson())
        put("group", group.toJson())
        put("others", others.toJson())
        put("special", special.toJson())
    }

    companion object {
        /**
         * Parse permissions from mode string (e.g., "rwxr-xr-x").
         * Port from: devices-api/internal/device/tools/adb.go parsePermissions()
         */
        fun fromModeString(mode: String): FilePermissions? {
            if (mode.length < 10) return null
            val perm = mode.substring(1, 10)

            return FilePermissions(
                owner = PermissionSet(
                    read = perm[0] == 'r',
                    write = perm[1] == 'w',
                    execute = perm[2] == 'x' || perm[2] == 's' || perm[2] == 'S'
                ),
                group = PermissionSet(
                    read = perm[3] == 'r',
                    write = perm[4] == 'w',
                    execute = perm[5] == 'x' || perm[5] == 's' || perm[5] == 'S'
                ),
                others = PermissionSet(
                    read = perm[6] == 'r',
                    write = perm[7] == 'w',
                    execute = perm[8] == 'x' || perm[8] == 't' || perm[8] == 'T'
                ),
                special = SpecialPermissions(
                    setUid = perm[2] == 's' || perm[2] == 'S',
                    setGid = perm[5] == 's' || perm[5] == 'S',
                    sticky = perm[8] == 't' || perm[8] == 'T'
                )
            )
        }
    }
}

/**
 * File information structure matching devices-api FileInfo.
 * See: devices-api/internal/device/tools/tools.go
 */
data class FileInfo(
    val name: String,
    val owner: String,
    val group: String,
    val size: Long,
    val type: FileType,
    val permissions: FilePermissions,
    val hardLinks: Int,
    val modifiedAt: Date,
    val symlinkTarget: String = "",
    val extendedAttributes: Boolean = false
) {
    companion object {
        private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("owner", owner)
        put("group", group)
        put("size", size)
        put("type", type.value)
        put("permissions", permissions.toJson())
        put("hardLinks", hardLinks)
        put("modifiedAt", ISO_DATE_FORMAT.format(modifiedAt))
        if (symlinkTarget.isNotEmpty()) {
            put("symlinkTarget", symlinkTarget)
        }
        put("extendedAttributes", extendedAttributes)
    }
}

/**
 * Response wrapper for file listing.
 */
data class FileListResponse(
    val path: String,
    val total: Int,
    val files: List<FileInfo>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("total", total)
        put("files", JSONArray(files.map { it.toJson() }))
    }
}
