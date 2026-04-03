package com.droidrun.portal.service

import android.os.Build
import android.os.Environment
import android.util.Log
import com.droidrun.portal.model.FileInfo
import com.droidrun.portal.model.FileListResponse
import com.droidrun.portal.model.FilePermissions
import com.droidrun.portal.model.FileType
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * File operations for reading, writing, listing, and deleting files.
 * All operations are restricted to /sdcard (external storage).
 *
 * Port of: devices-api/internal/device/tools/adb.go file operations
 */
class FileOperations {

    companion object {
        private const val TAG = "FileOperations"
        private const val MAX_FILE_SIZE = 100 * 1024 * 1024L // 100MB limit

        private fun checkWritePermission(): SecurityException? {
            if (!Environment.isExternalStorageManager()) {
                return SecurityException(
                    "MANAGE_EXTERNAL_STORAGE not granted. " +
                        "Grant it via: adb shell appops set com.droidrun.portal MANAGE_EXTERNAL_STORAGE allow"
                )
            }
            return null
        }
    }

    private fun getBasePath(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    /**
     * Validates and resolves a relative path to an absolute path under /sdcard.
     * Security: blocks path traversal attacks and sensitive files.
     */
    @Throws(SecurityException::class)
    fun resolvePath(relativePath: String): String {
        if (relativePath.contains("..")) {
            throw SecurityException("Path contains '..' - path traversal not allowed")
        }

        if (relativePath.contains("id.txt")) {
            throw SecurityException("Access to id.txt is not allowed")
        }

        val basePath = getBasePath()
        val cleanPath = relativePath.trimStart('/')
        val resolved = File(basePath, cleanPath).canonicalPath

        if (!resolved.startsWith(basePath)) {
            throw SecurityException("Resolved path escapes base directory")
        }

        return resolved
    }

    fun listFiles(relativePath: String): Result<FileListResponse> {
        return try {
            val resolvedPath = resolvePath(relativePath)
            val file = File(resolvedPath)

            if (!file.exists()) {
                return Result.failure(NoSuchFileException(file, reason = "Path not found"))
            }

            if (!file.isDirectory) {
                return Result.failure(IllegalArgumentException("Path is not a directory"))
            }

            val process = Runtime.getRuntime().exec(arrayOf("ls", "-la", resolvedPath))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val files = mutableListOf<FileInfo>()

            reader.useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("total ")) {
                        parseLsLine(trimmed)?.let { files.add(it) }
                    }
                }
            }

            process.waitFor()
            Result.success(FileListResponse(relativePath, files.size, files))
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files at $relativePath", e)
            Result.failure(e)
        }
    }

    fun readFile(relativePath: String): Result<ByteArray> {
        return try {
            val resolvedPath = resolvePath(relativePath)
            val file = File(resolvedPath)

            if (!file.exists()) {
                return Result.failure(NoSuchFileException(file, reason = "File not found"))
            }

            if (!file.isFile) {
                return Result.failure(IllegalArgumentException("Path is not a regular file"))
            }

            if (file.length() > MAX_FILE_SIZE) {
                return Result.failure(
                    IllegalArgumentException("File too large (max ${MAX_FILE_SIZE / 1024 / 1024}MB)")
                )
            }

            Result.success(file.readBytes())
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file at $relativePath", e)
            Result.failure(e)
        }
    }

    fun writeFile(relativePath: String, data: ByteArray): Result<Unit> {
        checkWritePermission()?.let { return Result.failure(it) }
        return try {
            if (data.size > MAX_FILE_SIZE) {
                return Result.failure(
                    IllegalArgumentException("Data too large (max ${MAX_FILE_SIZE / 1024 / 1024}MB)")
                )
            }

            val resolvedPath = resolvePath(relativePath)
            val file = File(resolvedPath)
            file.parentFile?.mkdirs()
            file.writeBytes(data)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file at $relativePath", e)
            Result.failure(e)
        }
    }

    fun deleteFile(relativePath: String): Result<Unit> {
        checkWritePermission()?.let { return Result.failure(it) }
        return try {
            val resolvedPath = resolvePath(relativePath)
            val file = File(resolvedPath)

            if (!file.exists()) {
                return Result.failure(NoSuchFileException(file, reason = "File not found"))
            }

            if (file.delete()) {
                Result.success(Unit)
            } else {
                Result.failure(java.io.IOException("Failed to delete file"))
            }
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file at $relativePath", e)
            Result.failure(e)
        }
    }

    fun fetchFile(url: String, relativePath: String): Result<Unit> {
        checkWritePermission()?.let { return Result.failure(it) }
        return try {
            val resolvedPath = resolvePath(relativePath)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return Result.failure(java.io.IOException("HTTP $responseCode fetching file from $url"))
            }

            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_FILE_SIZE) {
                connection.disconnect()
                return Result.failure(
                    IllegalArgumentException("File too large (max ${MAX_FILE_SIZE / 1024 / 1024}MB)")
                )
            }

            val destFile = File(resolvedPath)
            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parentFile, "${destFile.name}.download.tmp")

            try {
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        copyToWithLimit(input, output)
                    }
                }
                if (destFile.exists() && !destFile.isFile) {
                    tempFile.delete()
                    return Result.failure(java.io.IOException("Destination path is not a file"))
                }
                if (destFile.exists() && !destFile.delete()) {
                    tempFile.delete()
                    return Result.failure(java.io.IOException("Failed to replace existing file"))
                }
                if (!tempFile.renameTo(destFile)) {
                    tempFile.delete()
                    return Result.failure(java.io.IOException("Failed to finalize fetched file"))
                }
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            } finally {
                connection.disconnect()
            }

            Log.i(TAG, "Fetched file from $url to $resolvedPath")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching file from $url to $relativePath", e)
            Result.failure(e)
        }
    }

    private fun copyToWithLimit(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) {
        val buffer = ByteArray(8 * 1024)
        var totalRead = 0L

        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) {
                return
            }
            totalRead += bytesRead
            if (totalRead > MAX_FILE_SIZE) {
                throw IllegalArgumentException("File too large (max ${MAX_FILE_SIZE / 1024 / 1024}MB)")
            }
            output.write(buffer, 0, bytesRead)
        }
    }

    fun pushFile(url: String, relativePath: String): Result<Unit> {
        return try {
            val resolvedPath = resolvePath(relativePath)
            val file = File(resolvedPath)

            if (!file.exists()) {
                return Result.failure(NoSuchFileException(file, reason = "File not found"))
            }

            if (!file.isFile) {
                return Result.failure(IllegalArgumentException("Path is not a regular file"))
            }

            if (file.length() > MAX_FILE_SIZE) {
                return Result.failure(
                    IllegalArgumentException("File too large (max ${MAX_FILE_SIZE / 1024 / 1024}MB)")
                )
            }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Content-Length", file.length().toString())
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            file.inputStream().use { input ->
                connection.outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode != HttpURLConnection.HTTP_OK &&
                responseCode != HttpURLConnection.HTTP_NO_CONTENT
            ) {
                return Result.failure(java.io.IOException("HTTP $responseCode pushing file to $url"))
            }

            Log.i(TAG, "Pushed file from $resolvedPath to $url")
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error pushing file from $relativePath to $url", e)
            Result.failure(e)
        }
    }

    /**
     * Parse a single line of `ls -la` output into FileInfo.
     */
    fun parseLsLine(line: String): FileInfo? {
        val fields = line.split(Regex("\\s+"))
        if (fields.size < 8) {
            Log.w(TAG, "Unexpected column count: ${fields.size} for line: $line")
            return null
        }

        val mode = fields[0]
        val extendedAttributes = mode.length > 10 && (mode[10] == '@' || mode[10] == '+')
        val permissions = FilePermissions.fromModeString(mode)
        if (permissions == null) {
            Log.w(TAG, "Failed to parse permissions from mode: $mode")
            return null
        }

        val hardLinks = fields[1].toIntOrNull()
        if (hardLinks == null) {
            Log.w(TAG, "Invalid hard links value: ${fields[1]}")
            return null
        }

        val owner = fields[2]
        val group = fields[3]
        val size = fields[4].toLongOrNull()
        if (size == null) {
            Log.w(TAG, "Invalid size value: ${fields[4]}")
            return null
        }

        val modifiedAt = parseModifiedAt(fields[5], fields[6])
        var name = fields.subList(7, fields.size).joinToString(" ")
        var symlinkTarget = ""

        val symlinkIndex = name.indexOf(" -> ")
        if (symlinkIndex >= 0) {
            symlinkTarget = name.substring(symlinkIndex + 4)
            name = name.take(symlinkIndex)
        }

        val fileType = if (mode.isNotEmpty()) FileType.fromModeChar(mode[0]) else FileType.UNKNOWN

        return FileInfo(
            name = name,
            owner = owner,
            group = group,
            size = size,
            type = fileType,
            permissions = permissions,
            hardLinks = hardLinks,
            modifiedAt = modifiedAt,
            symlinkTarget = symlinkTarget,
            extendedAttributes = extendedAttributes
        )
    }

    private fun parseModifiedAt(date: String, time: String): Date {
        val value = "$date $time"

        val layouts = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("MMM d HH:mm", Locale.US),
            SimpleDateFormat("MMM dd HH:mm", Locale.US),
            SimpleDateFormat("MMM d yyyy", Locale.US),
            SimpleDateFormat("MMM dd yyyy", Locale.US)
        )

        for (format in layouts) {
            try {
                val parsed = format.parse(value)
                if (parsed != null) {
                    val pattern = format.toPattern()
                    if (!pattern.contains("yyyy") && !pattern.contains("yy")) {
                        val cal = Calendar.getInstance()
                        val currentYear = cal.get(Calendar.YEAR)
                        cal.time = parsed
                        cal.set(Calendar.YEAR, currentYear)
                        return cal.time
                    }
                    return parsed
                }
            } catch (_: Exception) {
                // Try next format.
            }
        }

        Log.w(TAG, "Failed to parse date: $value")
        return Date(0)
    }
}
