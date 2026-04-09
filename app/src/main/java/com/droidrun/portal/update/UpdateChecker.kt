package com.droidrun.portal.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.droidrun.portal.ui.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
)

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    const val GITHUB_API_URL =
        "https://api.github.com/repos/droidrun/droidrun-portal/releases/latest"
    const val CACHED_APK_FILENAME = "portal-update.apk"
    private const val NOTIFICATION_CHANNEL_ID = "update_channel"
    private const val NOTIFICATION_ID = 5001
    private const val INVALID_VERSION = "0.0.0"
    private const val USER_AGENT = "droidrun-portal"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Checks GitHub for the latest release. Must be called from a background thread.
     */
    fun checkForUpdate(context: Context): UpdateCheckResult {
        return try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", USER_AGENT)
                .build()

            // response.use ensures the connection is always released
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub API returned ${response.code}")
                    return UpdateCheckResult.Failed("Server returned ${response.code}")
                }

                val body = response.body?.string()
                    ?: return UpdateCheckResult.Failed("Empty response")
                val json = JSONObject(body)
                val tagName = json.getString("tag_name") // e.g. "v0.6.2"
                val latestVersion = tagName.trimStart('v')

                val currentVersion = getCurrentVersion(context)
                Log.i(TAG, "Current: $currentVersion, Latest: $latestVersion")

                if (!isNewerVersion(latestVersion, currentVersion)) {
                    return UpdateCheckResult.UpToDate
                }

                // Find the release APK asset; fall back to any APK if no release variant exists
                val assets = json.getJSONArray("assets")
                var downloadUrl: String? = null
                var fallbackUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        if (name.contains("release")) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                        if (fallbackUrl == null) {
                            fallbackUrl = asset.getString("browser_download_url")
                        }
                    }
                }
                if (downloadUrl == null) {
                    downloadUrl = fallbackUrl
                }

                if (downloadUrl == null) {
                    Log.w(TAG, "No APK asset found in release $tagName")
                    return UpdateCheckResult.Failed("No APK found in release")
                }

                UpdateCheckResult.Available(UpdateInfo(latestVersion, downloadUrl))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            UpdateCheckResult.Failed("Update check failed: ${e.message}")
        }
    }

    fun getCurrentVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: INVALID_VERSION
        } catch (e: Exception) {
            INVALID_VERSION
        }
    }

    /** Returns true if [latest] is newer than [current] by semver comparison. */
    fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * Downloads the APK to a cache file, then hands it to [PackageInstaller].
     * If the install later fails with a signature conflict, [UpdateInstallReceiver] will
     * copy the cached file to Downloads and notify [MainActivity].
     * Must be called from a background thread.
     */
    fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            val apkFile = File(context.cacheDir, CACHED_APK_FILENAME)

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            // response.use ensures the connection is always released regardless of early returns
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onError("Download failed: HTTP ${response.code}")
                    return
                }

                val body = response.body
                if (body == null) {
                    onError("Empty response body")
                    return
                }

                val contentLength = body.contentLength()

                // Save to cache file first so it's available if install fails
                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var totalBytes = 0L
                        var c: Int
                        while (input.read(buffer).also { c = it } != -1) {
                            output.write(buffer, 0, c)
                            totalBytes += c
                            if (contentLength > 0) {
                                onProgress((totalBytes * 100 / contentLength).toInt())
                            }
                        }
                    }
                }
            }

            // Stream the cached file into PackageInstaller
            installFromCachedFile(context, apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed", e)
            onError("Update failed: ${e.message}")
        }
    }

    private fun installFromCachedFile(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.use { s ->
            s.openWrite("update.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    var c: Int
                    while (input.read(buffer).also { c = it } != -1) {
                        out.write(buffer, 0, c)
                    }
                }
                s.fsync(out)
            }

            val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pendingIntent.intentSender)
        }
    }

    /**
     * Copies the cached APK to the public Downloads folder so it survives app uninstall.
     * Returns the content [Uri] on success, null on failure.
     */
    fun saveCachedApkToDownloads(context: Context): Uri? {
        val cacheFile = File(context.cacheDir, CACHED_APK_FILENAME)
        if (!cacheFile.exists()) {
            Log.e(TAG, "Cached APK not found")
            return null
        }
        return try {
            val resolver = context.contentResolver

            // Mark as pending so the file isn't visible to users/other apps until fully written
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "DroidrunPortal-update.apk")
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null

            val written = resolver.openOutputStream(uri)?.use { out ->
                cacheFile.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false

            if (!written) {
                resolver.delete(uri, null, null)
                Log.e(TAG, "openOutputStream returned null; deleted dangling MediaStore row")
                return null
            }

            // Clear pending flag — file is now fully written and visible in Downloads
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)

            Log.i(TAG, "APK saved to Downloads: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save APK to Downloads", e)
            null
        }
    }

    /** Posts a system notification informing the user an update is available. */
    fun showUpdateNotification(context: Context, updateInfo: UpdateInfo) {
        try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.i(TAG, "Notifications disabled, skipping update notification")
                return
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            nm.createNotificationChannel(channel)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Update available")
                .setContentText("Version ${updateInfo.latestVersion} is ready to install")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post update notification", e)
        }
    }
}
