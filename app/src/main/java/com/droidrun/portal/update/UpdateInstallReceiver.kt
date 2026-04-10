package com.droidrun.portal.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.droidrun.portal.api.ApiHandler

/**
 * Receives the result of a [PackageInstaller] session commit triggered by [UpdateChecker].
 *
 * On success or generic failure, reuses the existing [ApiHandler.ACTION_INSTALL_RESULT]
 * broadcast so [MainActivity] can display the outcome via its snackbar.
 *
 * On [PackageInstaller.STATUS_FAILURE_CONFLICT] (signature mismatch), copies the cached
 * APK to Downloads and broadcasts [ACTION_SIGNATURE_CONFLICT] so [MainActivity] can show
 * an uninstall-and-reinstall dialog.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.droidrun.portal.action.UPDATE_INSTALL_STATUS"
        const val ACTION_SIGNATURE_CONFLICT =
            "com.droidrun.portal.action.UPDATE_SIGNATURE_CONFLICT"
        const val EXTRA_DOWNLOAD_URI = "download_uri"
        private const val TAG = "UpdateInstallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Self-update installed successfully")
                broadcastResult(context, success = true, message = "Update installed successfully")
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.w(TAG, "Signature conflict: $message")
                handleSignatureConflict(context)
            }
            else -> {
                // Some ROMs report the conflict via a generic STATUS_FAILURE with a message
                if (message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true)) {
                    Log.w(TAG, "Signature conflict (via message): $message")
                    handleSignatureConflict(context)
                } else {
                    Log.e(TAG, "Self-update failed: status=$status message=$message")
                    broadcastResult(
                        context,
                        success = false,
                        message = "Update failed: $message",
                    )
                }
            }
        }
    }

    private fun handleSignatureConflict(context: Context) {
        val uri = UpdateChecker.saveCachedApkToDownloads(context)
        if (uri != null) {
            val broadcast = Intent(ACTION_SIGNATURE_CONFLICT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_DOWNLOAD_URI, uri.toString())
            context.sendBroadcast(broadcast)
        } else {
            broadcastResult(
                context,
                success = false,
                message = "Update failed: could not save APK to Downloads",
            )
        }
    }

    private fun broadcastResult(context: Context, success: Boolean, message: String) {
        val broadcast = Intent(ApiHandler.ACTION_INSTALL_RESULT)
            .setPackage(context.packageName)
            .putExtra(ApiHandler.EXTRA_INSTALL_SUCCESS, success)
            .putExtra(ApiHandler.EXTRA_INSTALL_MESSAGE, message)
        context.sendBroadcast(broadcast)
    }
}
