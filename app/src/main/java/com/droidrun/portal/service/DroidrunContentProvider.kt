package com.droidrun.portal.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.droidrun.portal.api.ApiHandler
import com.droidrun.portal.api.ApiResponse
import com.droidrun.portal.core.StateRepository
import com.droidrun.portal.input.DroidrunKeyboardIME
import com.droidrun.portal.service.DroidrunAccessibilityService

class DroidrunContentProvider : ContentProvider() {
    companion object {
        private const val TAG = "DroidrunContentProvider"
        private const val AUTHORITY = "com.droidrun.portal"
        private const val A11Y_TREE = 1
        private const val PHONE_STATE = 2
        private const val PING = 3
        private const val KEYBOARD_ACTIONS = 4
        private const val STATE = 5
        private const val OVERLAY_OFFSET = 6
        private const val PACKAGES = 7
        private const val A11Y_TREE_FULL = 8
        private const val VERSION = 9
        private const val STATE_FULL = 10
        private const val SOCKET_PORT = 11
        private const val OVERLAY_VISIBLE = 12

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "a11y_tree", A11Y_TREE)
            addURI(AUTHORITY, "a11y_tree_full", A11Y_TREE_FULL)
            addURI(AUTHORITY, "phone_state", PHONE_STATE)
            addURI(AUTHORITY, "ping", PING)
            addURI(AUTHORITY, "keyboard/*", KEYBOARD_ACTIONS)
            addURI(AUTHORITY, "state", STATE)
            addURI(AUTHORITY, "state_full", STATE_FULL)
            addURI(AUTHORITY, "overlay_offset", OVERLAY_OFFSET)
            addURI(AUTHORITY, "packages", PACKAGES)
            addURI(AUTHORITY, "version", VERSION)
            addURI(AUTHORITY, "socket_port", SOCKET_PORT)
            addURI(AUTHORITY, "overlay_visible", OVERLAY_VISIBLE)
        }
    }
    
    private var apiHandler: ApiHandler? = null

    override fun onCreate(): Boolean {
        Log.d(TAG, "DroidrunContentProvider created")
        return true
    }
    
    private fun getHandler(): ApiHandler? {
        if (apiHandler != null) return apiHandler
        
        val service = DroidrunAccessibilityService.getInstance()
        if (service != null && context != null) {
            apiHandler = ApiHandler(
                stateRepo = StateRepository(service),
                getKeyboardIME = { DroidrunKeyboardIME.getInstance() },
                getPackageManager = { context!!.packageManager },
                appVersionProvider = { 
                    try {
                        context!!.packageManager.getPackageInfo(context!!.packageName, 0).versionName
                    } catch (e: Exception) { "unknown" }
                }
            )
        }
        return apiHandler
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("result"))
        
        try {
            val handler = getHandler()
            val response = if (handler == null) {
                ApiResponse.Error("Accessibility service not available")
            } else {
                when (uriMatcher.match(uri)) {
                    A11Y_TREE -> handler.getTree()
                    A11Y_TREE_FULL -> handler.getTreeFull(uri.getBooleanQueryParameter("filter", true))
                    PHONE_STATE -> handler.getPhoneState()
                    PING -> handler.ping()
                    STATE -> handler.getState()
                    STATE_FULL -> handler.getStateFull(uri.getBooleanQueryParameter("filter", true))
                    PACKAGES -> handler.getPackages()
                    VERSION -> handler.getVersion()
                    else -> ApiResponse.Error("Unknown endpoint: ${uri.path}")
                }
            }
            cursor.addRow(arrayOf(response.toJson()))
            
        } catch (e: Exception) {
            Log.e(TAG, "Query execution failed", e)
            cursor.addRow(arrayOf(ApiResponse.Error("Execution failed: ${e.message}").toJson()))
        }
        
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val handler = getHandler()
        if (handler == null) {
            return "content://$AUTHORITY/result?status=error&message=${Uri.encode("Accessibility service not available")}".toUri()
        }

        val result = try {
            val response = when (uriMatcher.match(uri)) {
                KEYBOARD_ACTIONS -> {
                    val action = uri.lastPathSegment
                    val vals = values ?: ContentValues()
                    when (action) {
                        "input" -> handler.keyboardInput(
                            vals.getAsString("base64_text") ?: "", 
                            vals.getAsBoolean("clear") ?: true
                        )
                        "clear" -> handler.keyboardClear()
                        "key" -> handler.keyboardKey(vals.getAsInteger("key_code") ?: 0)
                        else -> ApiResponse.Error("Unknown keyboard action")
                    }
                }
                OVERLAY_OFFSET -> {
                    val offset = values?.getAsInteger("offset") ?: 0
                    handler.setOverlayOffset(offset)
                }
                SOCKET_PORT -> {
                    val port = values?.getAsInteger("port") ?: 0
                    handler.setSocketPort(port)
                }
                OVERLAY_VISIBLE -> {
                    val visible = values?.getAsBoolean("visible") ?: true
                    handler.setOverlayVisible(visible)
                }
                else -> ApiResponse.Error("Unsupported insert endpoint")
            }
            response
        } catch (e: Exception) {
            ApiResponse.Error("Exception: ${e.message}")
        }
        
        // Convert response to URI
        return if (result is ApiResponse.Success) {
            "content://$AUTHORITY/result?status=success&message=${Uri.encode(result.data.toString())}".toUri()
        } else {
             val errorMsg = (result as ApiResponse.Error).message
            "content://$AUTHORITY/result?status=error&message=${Uri.encode(errorMsg)}".toUri()
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}